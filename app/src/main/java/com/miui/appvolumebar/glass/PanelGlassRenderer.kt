package com.miui.appvolumebar.glass

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import com.miui.appvolumebar.MainHook
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.min

/**
 * 给「分应用音量面板」（com.miui.misound 展开的 MediaVolumePageView）套玻璃材质。
 *
 * 渲染分层承袭 HyperIsland 超级岛、并在 SoundMan 的内置面板上实装验证：
 *   1. 真实折射：AGSL RuntimeShader 对面板后方捕获画面做折射位移、色散与混合色；
 *   2. 边缘高光：SDF 圆角矩形边缘的方向性光影、透镜带与棱镜色散。
 *
 * 与 SystemUI 侧入口按钮那套的关键区别：**不依赖
 * ViewRootImpl.createBackgroundBlurDrawable()**。背景模糊由 RenderNode +
 * RenderEffect 对捕获帧自行完成，因此不受「窗口是否支持 blur」限制，
 * 在 misound 这种独立进程里同样可用。官方背景用 LayerDrawable 保留在底层，
 * 玻璃画在其上，一切渲染失败都只是静默降级，不会让面板失去背景。
 */
object PanelGlassRenderer {

    private const val TAG = "AppVolume[PanelGlass]"

    /** 模块自建卡片容器的标记；玻璃必须落在它身上，而不是整个窗口根布局。 */
    const val TAG_GLASS_CARD = "tag_app_volume_glass_card"

    /**
     * 面板玻璃的一份挂载状态。
     *
     * **不要在这里持有 root**：owned 是 WeakHashMap<View, Owned>，若 value 强引用
     * key，这个条目就永远不会被回收，面板反复开合会不断堆积，且诊断可能读到
     * 早已收起的那个面板（表现为「帧计数永远是 0」这种假象）。
     */
    private class Owned(
        var target: View,
        val stockBackground: Drawable?,
        val config: PanelGlassConfig,
        val drawable: PanelGlassDrawable,
    ) {
        var nativeBlurInstalled = false
        var nativeBlurReason = "未尝试"
    }

    private val owned = Collections.synchronizedMap(WeakHashMap<View, Owned>())

    /** 最近一次真正挂载上的那份状态，诊断只认它。 */
    @Volatile
    private var lastState: Owned? = null

    @Volatile
    private var baseDiagnosis: String = "尚未捕获面板"

    /** 「从暗到亮」时序埋点：面板 addView / 玻璃挂载 / 首次绘制 / 底层模糊装载。 */
    @Volatile
    private var addedAtMs = 0L

    @Volatile
    private var appliedAtMs = 0L

    @Volatile
    private var blurAtMs = 0L

    /** 每次 apply 结束后回调，用于把自检结果刷到状态卡片。 */
    @Volatile
    var onApplied: (() -> Unit)? = null

    /**
     * 自检文本。捕获状态是随时间变化的（挂载 → 拿到排除图层 → 首帧到达），
     * 所以每次读取都现场拼一份最新的，避免状态卡片只记录挂载那一刻的快照。
     */
    fun diagnosis(): String {
        val state = lastState ?: owned.entries.firstOrNull()?.value
        val capture = state?.drawable?.captureDiagnosis() ?: "未挂载玻璃"
        val blur = state?.nativeBlurReason ?: "-"
        val timing = if (addedAtMs == 0L) "" else {
            val draw = state?.drawable?.firstDrawAtMs ?: 0L
            " | 时序=挂载+${appliedAtMs - addedAtMs}/首帧+${if (draw == 0L) -1 else draw - addedAtMs}" +
                "/底模糊+${if (blurAtMs == 0L) -1 else blurAtMs - addedAtMs}ms"
        }
        return "$baseDiagnosis | 面板数=${owned.size} | 底模糊=$blur | 捕获: $capture$timing"
    }

    fun isManaged(root: View): Boolean = owned.containsKey(root)

    /**
     * 面板被 add 到 WindowManager 时调用。此刻通常还没挂上窗口，因此只登记，
     * 真正挂载推迟到 attach 之后（截屏需要 rootView）。
     */
    fun onPanelAdded(root: View) {
        val settings = GlassConfigStore.current()
        if (!settings.config.isCustom) {
            baseDiagnosis = "未启用：材质=${settings.config.type.value}"
            return
        }
        addedAtMs = SystemClock.uptimeMillis()
        appliedAtMs = 0L
        blurAtMs = 0L
        val target = resolveCard(root)
        val report = StringBuilder("面板=${root.javaClass.simpleName}")
        report.append(" 落点=${target.javaClass.simpleName}")
        report.append(" 尺寸=${target.width}x${target.height}")
        report.append(" attached=${target.isAttachedToWindow}")
        baseDiagnosis = report.toString()
        scheduleApply(root)
    }

    /** 面板显示后每次都可调用：若玻璃还没贴上（或配置变了）就补一次。 */
    fun applyIfNeeded(root: View): Boolean {
        val settings = GlassConfigStore.current()
        val config = settings.config
        if (!config.isCustom || !config.isEdgeGlass) {
            if (owned.containsKey(root)) release(root)
            baseDiagnosis = "未启用：材质=${config.type.value}"
            return false
        }
        val state = owned[root]
        val next = PanelGlassConfig.from(config, settings.options)
        val target = resolveCard(root)
        if (state != null && state.config == next && state.target === target &&
            state.target.background is LayerDrawable
        ) {
            return true
        }
        return apply(root)
    }

    private fun scheduleApply(root: View) {
        root.post { retry(root) }
        runCatching {
            val observer = root.viewTreeObserver
            observer.addOnWindowAttachListener(object : ViewTreeObserver.OnWindowAttachListener {
                override fun onWindowAttached() {
                    root.post { retry(root) }
                    runCatching { observer.removeOnWindowAttachListener(this) }
                }

                override fun onWindowDetached() {
                    // 面板收起即释放，避免反复开合累积捕获线程与位图
                    release(root)
                    runCatching { observer.removeOnWindowAttachListener(this) }
                }
            })
        }.onFailure {
            MainHook.log("$TAG failed to register window attach listener: ${it.message}")
        }
    }

    private fun retry(root: View) {
        val settings = GlassConfigStore.current()
        if (!settings.config.isEdgeGlass) return
        if (owned[root] != null) return
        apply(root)
    }

    fun apply(root: View): Boolean {
        val settings = GlassConfigStore.current()
        val config = settings.config
        if (!config.isCustom || !config.isEdgeGlass) {
            baseDiagnosis = "未启用：材质=${config.type.value}（仅高光玻璃/液态玻璃会挂载）"
            release(root)
            return false
        }

        val target = resolveCard(root)
        val panelConfig = PanelGlassConfig.from(config, settings.options)

        val report = StringBuilder("材质=${config.type.value} 来源=${GlassConfigStore.diagnosis()}")
        report.append(" | 落点=${target.javaClass.simpleName}")
        report.append(" attached=${target.isAttachedToWindow}")
        report.append(" 尺寸=${target.width}x${target.height}")

        // 配置变化或落点变化时重建 Drawable（它的渲染参数是构造期固定的）
        val previous = owned[root]
        if (previous != null && (previous.config != panelConfig || previous.target !== target)) {
            previous.drawable.release()
            previous.target.background = previous.stockBackground
            owned.remove(root)
        }

        val existing = owned[root]
        if (existing != null) {
            baseDiagnosis = "$report | 已挂玻璃"
            onApplied?.invoke()
            return true
        }

        val radius = resolveCornerRadius(target)
        val drawable = runCatching {
            PanelGlassDrawable(
                context = target.context,
                host = target,
                initialConfig = panelConfig,
                initialCornerRadius = radius,
                log = { _, tag, message, throwable -> MainHook.log("[$tag] $message", throwable) },
                // 捕获是异步起步的，状态变化（首帧 / 报错）时把最新自检刷到状态卡片
                onStateChanged = { onApplied?.invoke() },
            )
        }.onFailure {
            report.append(" | 创建失败:${it.javaClass.simpleName}")
            baseDiagnosis = report.toString()
            MainHook.log("$TAG PanelGlassDrawable creation failed", it)
            onApplied?.invoke()
        }.getOrNull() ?: return false

        val stock = target.background
        // 玻璃启用时**不要**保留原有背景：模块自带的模糊/兜底底彩是深色的
        // （#77626262 / #CC454548），留着它，玻璃挂载的那一帧就会出现明显的
        // 「从暗到亮」突变。底层统一用玻璃自己的混合色，首帧到达前也有底色。
        // 原背景仍记在 stockBackground 里，release 时原样还原。
        val base = ColorDrawable(panelConfig.blendColor)
            .takeIf { Color.alpha(panelConfig.blendColor) > 0 }
        target.background = if (base != null) LayerDrawable(arrayOf(base, drawable)) else drawable
        owned[root] = Owned(target, stock, panelConfig, drawable)
        lastState = owned[root]
        appliedAtMs = SystemClock.uptimeMillis()
        // 真实折射要靠截屏，misound 里可能永远拿不到帧。底层再垫一层框架原生的
        // 背景模糊兜底：折射可用时被不透明折射层完全盖住，折射不可用时面板
        // 仍然是真实毛玻璃，而不是一层几乎透明的色块。
        installNativeBlur(owned[root])

        report.append(" | 圆角=${radius.toInt()} 折射=${panelConfig.trueRefraction} 模糊=${panelConfig.captureBlurRadius.toInt()}")
        report.append(" | background=${if (stock != null) "官方背景+玻璃" else "玻璃"}")
        baseDiagnosis = report.toString()
        MainHook.log("$TAG applied ${config.type} on panel: ${diagnosis()}")
        onApplied?.invoke()
        return true
    }

    fun release(root: View) {
        val state = owned.remove(root) ?: return
        if (lastState === state) lastState = null
        state.drawable.release()
        state.target.background = state.stockBackground
        baseDiagnosis = "面板已收起，玻璃已释放"
        MainHook.log("$TAG released panel glass")
    }

    /**
     * 找面板里真正可见的那张卡片：从窗口根布局开始广度优先，
     * 取第一个带背景且尺寸合理的容器。找不到就退回根布局本身。
     */
    private fun resolveCard(root: View): View {
        // 优先用模块自己标记的卡片容器：它带圆角裁剪与 elevation，
        // 玻璃只有落在它身上才会跟着圆角裁切，也不会铺满整个窗口。
        root.findViewWithTag<View>(TAG_GLASS_CARD)?.let { return it }
        if (root !is ViewGroup) return root
        val queue = ArrayDeque<View>()
        queue.add(root)
        var depth = 0
        while (queue.isNotEmpty() && depth < 4) {
            val level = queue.size
            repeat(level) {
                val view = queue.removeFirst()
                if (view !== root && view.background != null && view.width > 0 && view.height > 0) {
                    return view
                }
                if (view is ViewGroup) {
                    for (i in 0 until view.childCount) view.getChildAt(i)?.let(queue::addLast)
                }
            }
            depth++
        }
        return root
    }

    /**
     * 把 LayerDrawable 的底层换成框架的 BackgroundBlurDrawable。
     *
     * 它的底色用玻璃自己的混合色（浅色），**不能**沿用模块那套深色底
     * （#77626262 / #CC454548），否则玻璃挂载那一帧会出现「从暗到亮」。
     * 需要 View 已挂窗口才能创建，未挂载就等 attach 再试一次。
     */
    private fun installNativeBlur(state: Owned?) {
        val current = state ?: return
        if (current.nativeBlurInstalled) return
        val target = current.target
        // createBackgroundBlurDrawable 只依赖窗口的 ViewRootImpl，不要求必须是卡片
        // 自己挂上。卡片还没 attach 时改用同一窗口内已 attach 的 rootView 提前创建，
        // 避免「卡片先画几帧近乎透明的底色（偏暗）→ 模糊装载后才变亮」。
        val anchor = when {
            target.isAttachedToWindow -> target
            target.rootView?.isAttachedToWindow == true -> target.rootView
            else -> null
        }
        if (anchor == null) {
            target.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    v.removeOnAttachStateChangeListener(this)
                    v.post { installNativeBlur(current) }
                }

                override fun onViewDetachedFromWindow(v: View) = Unit
            })
            return
        }
        var reason = ""
        val blur = runCatching {
            GlassReflection.createBackgroundBlurDrawable(anchor) { reason = it }
        }.getOrNull()
        if (blur == null) {
            blurAtMs = SystemClock.uptimeMillis()
            current.nativeBlurInstalled = true
            current.nativeBlurReason = "不可用(${reason.ifEmpty { "未知" }})"
            onApplied?.invoke()
            return
        }
        val radius = current.config.captureBlurRadius.coerceIn(0f, 100f).toInt()
        val corner = resolveCornerRadius(target)
        GlassReflection.invoke(blur, "setBlurRadius", radius)
        if (!GlassReflection.invoke(blur, "setCornerRadius", corner, corner, corner, corner)) {
            GlassReflection.invoke(blur, "setCornerRadius", corner)
        }
        // 底色直接用配置值，不做任何补偿过渡。
        // 曾经的「从暗到亮」根因是模块自己的 AlphaAnimation(0→1)，已在
        // MiSoundHooker 里改成纯位移；之前为模糊预热加的底色补偿现在只剩副作用
        // ——它自己就是一段 240ms 的亮度缓变，所以撤掉。
        GlassReflection.invoke(blur, "setColor", current.config.blendColor)
        val layer = target.background as? LayerDrawable
        current.nativeBlurInstalled = true
        blurAtMs = SystemClock.uptimeMillis()
        if (layer != null && layer.numberOfLayers >= 2) {
            runCatching {
                layer.setDrawable(0, blur)
                target.postInvalidateOnAnimation()
            }
                .onSuccess { current.nativeBlurReason = "已启用($radius)" }
                .onFailure {
                    current.nativeBlurReason = "替换失败:${it.javaClass.simpleName}"
                    MainHook.log("$TAG failed to swap blur base: ${it.message}")
                }
        } else {
            current.nativeBlurReason = "背景非LayerDrawable"
        }
        onApplied?.invoke()
    }

    private fun resolveCornerRadius(target: View): Float {
        val height = target.height.takeIf { it > 0 }
            ?: target.layoutParams?.height?.takeIf { it > 0 }
            ?: (240 * target.resources.displayMetrics.density).toInt()
        val width = target.width.takeIf { it > 0 }
            ?: target.layoutParams?.width?.takeIf { it > 0 }
            ?: height
        // 面板卡片是圆角矩形而非全圆，取短边的 1/6 左右更像官方观感
        return min(width, height) / 6f
    }
}
