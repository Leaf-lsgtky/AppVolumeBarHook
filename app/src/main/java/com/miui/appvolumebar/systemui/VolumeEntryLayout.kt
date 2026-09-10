package com.miui.appvolumebar.systemui

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import com.miui.appvolumebar.MainHook
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.Proxy
import java.util.WeakHashMap

/**
 * 音量侧栏底部入口按钮的视图构造与视觉风格复刻。
 */
object VolumeEntryLayout {

    const val TAG_ENTRY_ROOT = "tag_app_volume_entry_root"
    const val TAG_ENTRY_SPACER = "tag_app_volume_entry_spacer"

    private const val DEFAULT_SIZE_DP = 48
    private const val DEFAULT_ICON_SIZE_DP = 22
    private const val DEFAULT_GAP_DP = 4

    // 保留官方 RingerButtonHelper 实例，使 updateExpandedH() 也能驱动复制按钮。
    private val officialHelpers = WeakHashMap<View, Any>()

    /**
     * 创建与官方静音/勿扰样式一致的圆形入口按钮
     */
    fun createEntryView(
        context: Context,
        pluginClassLoader: ClassLoader,
        onDismissRequest: () -> Unit,
        officialRingerLayout: View? = null
    ): View {
        // 真实官方按钮来自 miui_ringer_mode_layout.xml：其层级是
        // bg_blur -> miui_standard_btn -> icon。优先直接 inflate 这份 layout，
        // 并让 RingerButtonHelper 绑定原生 Folme / material / 展开收缩逻辑。
        inflateOfficialButton(context)?.let { officialButton ->
            if (configureOfficialButton(
                    officialButton,
                    context,
                    pluginClassLoader,
                    officialRingerLayout,
                    onDismissRequest
                )
            ) {
                return officialButton
            }
        }

        val density = context.resources.displayMetrics.density
        val buttonWidth = getButtonWidth(context)
        val buttonHeight = getButtonHeight(context)
        val iconSize = resolveDimension(context, "o3_miui_ringer_icon_size", (DEFAULT_ICON_SIZE_DP * density).toInt())

        val rootLayout = FrameLayout(context).apply {
            tag = TAG_ENTRY_ROOT
            isClickable = true
            isFocusable = true
        }

        val chromeView = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(buttonWidth, buttonHeight).apply {
                gravity = Gravity.CENTER
            }
        }

        // 设置圆形裁剪 Outline
        // 官方 getButtonRadius(false, true) = (height + 1) / 2；宽度通常大于高度。
        val radius = (buttonHeight + 1) / 2f
        chromeView.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        chromeView.clipToOutline = true

        // 官方按钮实际由 bg_blur（底层毛玻璃）+ miui_standard_btn（前景 chrome）两层组成。
        // 只给前景层套背景会丢掉官方的底层 backdrop，这也是之前看起来“没有背景”的原因。
        val blurLayer = OfficialRingerBackground.createBlurLayer(
            context,
            pluginClassLoader,
            radius
        )?.apply {
            layoutParams = FrameLayout.LayoutParams(buttonWidth, buttonHeight).apply {
                gravity = Gravity.CENTER
            }
            outlineProvider = chromeView.outlineProvider
            clipToOutline = true
            isClickable = false
            isFocusable = false
        }

        // 应用 HyperOS 官方前景 chrome / 半透明背景
        applyOfficialBlurOrBackground(context, chromeView, pluginClassLoader, radius)
        chromeView.isActivated = true
        chromeView.isSelected = false

        // 内部图标
        val iconView = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(iconSize, iconSize).apply {
                gravity = Gravity.CENTER
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        val iconDrawable = loadIconDrawable(context)
        iconView.setImageDrawable(iconDrawable)

        // 分应用音量入口的图标固定为白色。
        iconView.imageTintList = ColorStateList.valueOf(Color.WHITE)

        chromeView.addView(iconView)
        if (blurLayer != null) {
            rootLayout.addView(blurLayer)
        }
        rootLayout.addView(chromeView)

        val clickListener = createClickListener(context, onDismissRequest)
        chromeView.isClickable = true
        chromeView.isFocusable = true
        chromeView.setOnClickListener(clickListener)
        rootLayout.setOnClickListener(clickListener)

        return rootLayout
    }

    private fun inflateOfficialButton(context: Context): ViewGroup? {
        return try {
            val pluginContext = context.createPackageContext(
                MainHook.PKG_PLUGIN,
                Context.CONTEXT_IGNORE_SECURITY
            )
            val layoutId = pluginContext.resources.getIdentifier(
                "miui_ringer_mode_layout",
                "layout",
                MainHook.PKG_PLUGIN
            )
            if (layoutId == 0) return null
            (LayoutInflater.from(pluginContext).inflate(layoutId, null, false) as? ViewGroup)
        } catch (t: Throwable) {
            MainHook.log("Could not inflate official miui_ringer_mode_layout", t)
            null
        }
    }

    private fun configureOfficialButton(
        root: ViewGroup,
        context: Context,
        pluginClassLoader: ClassLoader,
        officialRingerLayout: View?,
        onDismissRequest: () -> Unit
    ): Boolean {
        val resourceContext = root.context
        val blurId = resourceContext.resources.getIdentifier("bg_blur", "id", MainHook.PKG_PLUGIN)
        val standardId = resourceContext.resources.getIdentifier("miui_standard_btn", "id", MainHook.PKG_PLUGIN)
        val iconId = resourceContext.resources.getIdentifier("icon", "id", MainHook.PKG_PLUGIN)
        val blurView = if (blurId != 0) root.findViewById<View>(blurId) else null
        val standardView = if (standardId != 0) root.findViewById<View>(standardId) else null
        val iconView = if (iconId != 0) root.findViewById<ImageView>(iconId) else null
        if (blurView == null || standardView == null || iconView == null) {
            MainHook.log("Official ringer template missing bg_blur/miui_standard_btn/icon")
            return false
        }

        root.tag = TAG_ENTRY_ROOT
        root.isClickable = true
        root.isFocusable = true

        val helper = if (officialRingerLayout != null) {
            bindOfficialRingerHelper(
                officialRingerLayout,
                root,
                pluginClassLoader
            )
        } else {
            null
        }
        if (helper != null) {
            officialHelpers[root] = helper
            MainHook.log("Bound official RingerButtonHelper to cloned app-volume button")
        } else {
            // 仅作为旧版本/异常 classloader 的回退；正常 HyperOS 4 会走上面的 helper。
            val radius = (getButtonHeight(context) + 1) / 2f
            OfficialRingerBackground.apply(context, standardView, pluginClassLoader, radius)
        }

        // 保留官方 icon ImageView 的尺寸和 scaleType，只替换 drawable 内容；
        // 分应用音量入口的前景图标固定为白色。
        iconView.setImageDrawable(loadIconDrawable(context))
        iconView.imageTintList = ColorStateList.valueOf(Color.WHITE)

        val clickListener = createClickListener(context, onDismissRequest)
        blurView.isClickable = true
        blurView.isFocusable = true
        blurView.setOnClickListener(clickListener)
        standardView.isClickable = true
        standardView.setOnClickListener(clickListener)
        root.setOnClickListener(clickListener)
        return true
    }

    private fun bindOfficialRingerHelper(
        owner: View,
        clonedButton: View,
        pluginClassLoader: ClassLoader
    ): Any? {
        val helperName = "com.android.systemui.miui.volume.MiuiRingerModeLayout\$RingerButtonHelper"
        val classLoaders = listOfNotNull(
            owner.javaClass.classLoader,
            pluginClassLoader,
            VolumeEntryLayout::class.java.classLoader
        ).distinct()

        for (loader in classLoaders) {
            try {
                val helperClass = Class.forName(helperName, false, loader)
                val constructor = helperClass.declaredConstructors.firstOrNull { ctor ->
                    val types = ctor.parameterTypes
                    types.size == 4 &&
                        View::class.java.isAssignableFrom(types[1]) &&
                        types[2] == Boolean::class.javaPrimitiveType &&
                        types[3] == Boolean::class.javaPrimitiveType
                } ?: continue
                constructor.isAccessible = true
                // 让复制按钮走官方关闭态胶囊；icon 的前景色在绑定后单独固定为白色。
                // 它不负责切换静音/DND 状态。
                val helper = constructor.newInstance(owner, clonedButton, false, false)

                // 构造函数负责 Folme 触摸绑定和 bg_blur 初始化；updateState() 负责
                // 官方 collapsed material、activated/selected 状态和 icon 动画基线。
                helperClass.getDeclaredMethod("updateState").apply {
                    isAccessible = true
                    invoke(helper)
                }
                return helper
            } catch (t: Throwable) {
                MainHook.log("Could not bind official RingerButtonHelper from $loader", t)
            }
        }
        return null
    }

    fun updateExpanded(root: View, expanded: Boolean) {
        val helper = officialHelpers[root] ?: return
        try {
            val method = helper.javaClass.getDeclaredMethod("onExpanded", Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }
            method.invoke(helper, expanded, false)

            // MiuiRingerModeLayout.updateExpandedH() 的官方顺序是：
            // onExpanded() -> updateExpandedStateH() -> helper.updateState()。
            // 复制按钮也必须执行第二步，否则只会改尺寸，胶囊材质/按压状态不会同步。
            helper.javaClass.getDeclaredMethod("updateState").apply {
                isAccessible = true
                invoke(helper)
            }
            // 官方 updateState() 可能按静音/DND 状态重设 icon tint，入口始终保持白色。
            applyWhiteIconTint(root)
        } catch (t: Throwable) {
            MainHook.log("Could not forward expanded state to official app-volume button", t)
        }
    }

    private fun applyWhiteIconTint(root: View) {
        try {
            val iconId = root.context.resources.getIdentifier("icon", "id", MainHook.PKG_PLUGIN)
            if (iconId != 0) {
                root.findViewById<ImageView>(iconId)?.imageTintList = ColorStateList.valueOf(Color.WHITE)
            }
        } catch (t: Throwable) {
            MainHook.log("Could not keep app-volume icon tint white", t)
        }
    }

    /**
     * 让复制按钮使用 VolumeShowHideAnimator 原本的 Folme 时间轴。
     *
     * 官方动画器默认只接收 [ringer_layout, dnd_layout] 两个目标。这里不重写
     * 动画器，而是在它即将 startAnim() 时追加一个同类型 AnimViewConfig，因而
     * 仍然使用官方的 setTo/to、EaseManager、TransitionListener 和回弹参数。
     */
    fun appendOfficialEntryAnimation(
        animator: Any?,
        originalConfigs: Any,
        entry: View
    ): Any? {
        if (animator == null || !originalConfigs.javaClass.isArray || entry.parent == null || entry.visibility != View.VISIBLE) {
            return null
        }

        val loader = animator.javaClass.classLoader ?: return null
        val configArrayLength = ReflectArray.getLength(originalConfigs)
        val componentClass = originalConfigs.javaClass.componentType ?: return null
        val configClass = Class.forName(
            "com.android.systemui.miui.volume.AnimViewConfig",
            false,
            loader
        )
        val transitionListenerClass = Class.forName(
            "miuix.animation.listener.TransitionListener",
            false,
            loader
        )
        val listener = try {
            animator.javaClass.getMethod("getListener").invoke(animator)
        } catch (_: Throwable) {
            readPrivateField(animator, "listener")
        } ?: return null

        val viewArgsClass = Class.forName(
            "com.android.systemui.miui.volume.ViewArgs",
            false,
            loader
        )
        val updateCallbackClass = Class.forName(
            "com.android.systemui.miui.volume.UpdateCallback",
            false,
            loader
        )
        val updateResultClass = Class.forName(
            "com.android.systemui.miui.volume.UpdateResult",
            false,
            loader
        )

        val expanded = readPrivateField(animator, "mExpanded") as? Boolean ?: return null
        val centerArgs = readPrivateField(animator, "centerArgs") ?: return null
        val volumeView = readPrivateField(animator, "mVolumeView") as? View ?: return null
        val centerTargetX = invokeNumber(centerArgs, "getTX") ?: return null
        val centerTargetScale = invokeNumber(centerArgs, "getTScale") ?: return null
        val responseFactor = try {
            val folmeUtils = Class.forName(
                "miui.systemui.animation.FolmeUtilsExtKt",
                false,
                loader
            )
            (folmeUtils.getMethod("getShowHideResponseFactor").invoke(null) as Number).toFloat()
        } catch (_: Throwable) {
            1.0f
        }

        val easing = if (expanded) {
            floatArrayOf(0.95f, responseFactor * 0.25f)
        } else {
            // 官方第二颗按钮的 hide easing 是 0.19 * factor；第三颗顺延一个
            // 0.01，使它不会和勿扰按钮同时飞出。
            floatArrayOf(1.1f, responseFactor * 0.18f)
        }
        val viewArgsConstructor = viewArgsClass.declaredConstructors.firstOrNull { constructor ->
            val types = constructor.parameterTypes
            types.size == 2 && types[0] == FloatArray::class.java && types[1] == Long::class.javaPrimitiveType
        } ?: return null
        viewArgsConstructor.isAccessible = true
        val viewArgs = viewArgsConstructor.newInstance(easing, 0L)
        viewArgsClass.getMethod("setDelayX", Long::class.javaPrimitiveType).invoke(
            viewArgs,
            if (expanded) 70L else 0L
        )
        viewArgsClass.getMethod("setFX", Float::class.javaPrimitiveType).invoke(
            viewArgs,
            volumeView.x + entry.x
        )
        viewArgsClass.getMethod("setTX", Float::class.javaPrimitiveType).invoke(
            viewArgs,
            centerTargetX
        )
        viewArgsClass.getMethod("setFScale", Float::class.javaPrimitiveType).invoke(
            viewArgs,
            entry.scaleX
        )
        viewArgsClass.getMethod("setTScale", Float::class.javaPrimitiveType).invoke(
            viewArgs,
            centerTargetScale
        )

        val configConstructor = configClass.declaredConstructors.firstOrNull { constructor ->
            val types = constructor.parameterTypes
            types.size == 2 &&
                View::class.java.isAssignableFrom(types[0]) &&
                types[1].isAssignableFrom(transitionListenerClass)
        } ?: return null
        configConstructor.isAccessible = true
        val config = configConstructor.newInstance(entry, listener)
        configClass.getMethod("setViewArgs", viewArgsClass).invoke(config, viewArgs)

        val updateResultConstructor = updateResultClass.getConstructor(
            Boolean::class.javaPrimitiveType,
            Float::class.javaPrimitiveType
        )
        val xProperty = configClass.getMethod("propertyName", String::class.java)
            .invoke(config, "x") as String
        val scaleProperty = configClass.getMethod("propertyName", String::class.java)
            .invoke(config, "scale") as String
        val volumeXField = findPrivateField(animator, "volumeX")
        val animationCenterY = calculateAnimationCenterY(animator, entry, volumeView)

        fun result(value: Float): Any = updateResultConstructor.newInstance(false, value)

        val xCallback = Proxy.newProxyInstance(
            updateCallbackClass.classLoader ?: loader,
            arrayOf(updateCallbackClass)
        ) { _, method, args ->
            if (method.name == "callback") {
                val value = (args?.getOrNull(0) as Number).toFloat()
                val volumeX = (volumeXField?.get(animator) as? Number)?.toFloat() ?: 0.0f
                entry.x = value - volumeX
                result(value)
            } else {
                null
            }
        }
        val scaleCallback = Proxy.newProxyInstance(
            updateCallbackClass.classLoader ?: loader,
            arrayOf(updateCallbackClass)
        ) { _, method, args ->
            if (method.name == "callback") {
                val value = (args?.getOrNull(0) as Number).toFloat()
                entry.scaleX = value
                entry.scaleY = value
                entry.translationY = animationCenterY * (value - 1.0f)
                result(value)
            } else {
                null
            }
        }

        configClass.getMethod(
            "addUpdateCallback",
            String::class.java,
            updateCallbackClass
        ).invoke(config, xProperty, xCallback)
        configClass.getMethod(
            "addUpdateCallback",
            String::class.java,
            updateCallbackClass
        ).invoke(config, scaleProperty, scaleCallback)

        val extended = ReflectArray.newInstance(componentClass, configArrayLength + 1)
        for (index in 0 until configArrayLength) {
            ReflectArray.set(extended, index, ReflectArray.get(originalConfigs, index))
        }
        ReflectArray.set(extended, configArrayLength, config)
        MainHook.log(
            "App-volume entry appended to official VolumeShowHideAnimator: " +
                "expanded=$expanded, index=$configArrayLength, delayX=${if (expanded) 70L else 0L}ms"
        )
        return extended
    }

    fun matchAnimationBaseline(entry: View, officialDnd: View) {
        entry.scaleX = officialDnd.scaleX
        entry.scaleY = officialDnd.scaleY
        entry.translationY = officialDnd.translationY
    }

    private fun calculateAnimationCenterY(animator: Any, entry: View, volumeView: View): Float {
        return try {
            val entryLocation = IntArray(2)
            val volumeLocation = IntArray(2)
            entry.getLocationOnScreen(entryLocation)
            volumeView.getLocationOnScreen(volumeLocation)
            (entryLocation[1] - volumeLocation[1]) + entry.height / 2.0f
        } catch (_: Throwable) {
            val container = readPrivateField(animator, "mVolumeContainer") as? View
            val ringer = readPrivateField(animator, "mRingerLayout") as? View
            val first = readPrivateField(animator, "mRingerBtnLayouts")
                ?.let { array -> if (array.javaClass.isArray && ReflectArray.getLength(array) > 0) ReflectArray.get(array, 0) as? View else null }
            val second = readPrivateField(animator, "mRingerBtnLayouts")
                ?.let { array -> if (array.javaClass.isArray && ReflectArray.getLength(array) > 1) ReflectArray.get(array, 1) as? View else null }
            val divider = readPrivateField(animator, "mRingerDivider") as? View
            val topMargin = (ringer?.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0
            (container?.height ?: 0) / 2.0f +
                topMargin +
                (first?.height ?: 0) +
                (divider?.height ?: 0) +
                (second?.height ?: 0) +
                (divider?.height ?: 0) +
                entry.height / 2.0f
        }
    }

    private fun invokeNumber(target: Any, methodName: String): Float? {
        return try {
            (target.javaClass.getMethod(methodName).invoke(target) as Number).toFloat()
        } catch (_: Throwable) {
            null
        }
    }

    private fun readPrivateField(target: Any, name: String): Any? {
        return findPrivateField(target, name)?.get(target)
    }

    private fun findPrivateField(target: Any, name: String): java.lang.reflect.Field? {
        var clazz: Class<*>? = target.javaClass
        while (clazz != null && clazz != Any::class.java) {
            try {
                return clazz.getDeclaredField(name).apply { isAccessible = true }
            } catch (_: NoSuchFieldException) {
                clazz = clazz.superclass
            } catch (_: Throwable) {
                return null
            }
        }
        return null
    }

    private fun createClickListener(
        context: Context,
        onDismissRequest: () -> Unit
    ): View.OnClickListener = View.OnClickListener {
        MainHook.log("Volume entry clicked, triggering misound and dismissing volume dialog")

        // 1. 优先使用前台广播极速通知 Misound 唤起面板，绕过 Android 后台广播队列调度延迟
        try {
            val intent = Intent(MainHook.ACTION_EXPAND_MEDIA_VOLUME).apply {
                setPackage(MainHook.PKG_MISOUND)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            }
            context.sendBroadcast(intent)
            MainHook.log("Broadcast ACTION_EXPAND_MEDIA_VOLUME (FOREGROUND) sent to ${MainHook.PKG_MISOUND}")
        } catch (t: Throwable) {
            MainHook.log("Error sending expand broadcast", t)
        }

        // 2. 同步触发 SystemUI 原生单音量条收回
        try {
            onDismissRequest()
        } catch (t: Throwable) {
            MainHook.log("Error executing onDismissRequest", t)
        }
    }

    fun getButtonMarginTop(context: Context): Int {
        val density = context.resources.displayMetrics.density
        return resolveDimension(context, "miui_volume_column_margin", (DEFAULT_GAP_DP * density).toInt())
    }

    fun getButtonSize(context: Context): Int {
        return getButtonHeight(context)
    }

    fun getButtonWidth(context: Context): Int {
        val density = context.resources.displayMetrics.density
        return resolveDimension(context, "o3_miui_ringer_btn_width", (DEFAULT_SIZE_DP * density).toInt())
    }

    fun getButtonHeight(context: Context): Int {
        val density = context.resources.displayMetrics.density
        return resolveDimension(context, "o3_miui_ringer_btn_height", (DEFAULT_SIZE_DP * density).toInt())
    }

    private fun resolveDimension(context: Context, resName: String, fallback: Int): Int {
        return try {
            val resId = context.resources.getIdentifier(resName, "dimen", context.packageName)
            if (resId != 0) context.resources.getDimensionPixelSize(resId) else fallback
        } catch (_: Throwable) {
            fallback
        }
    }

    private fun loadIconDrawable(context: Context): Drawable {
        // 1. 尝试从模块自身的资源中加载
        try {
            val moduleContext = context.createPackageContext(
                "com.miui.appvolumebar",
                Context.CONTEXT_IGNORE_SECURITY
            )
            val resId = moduleContext.resources.getIdentifier("ic_app_volume", "drawable", "com.miui.appvolumebar")
            if (resId != 0) {
                val d = moduleContext.getDrawable(resId)
                if (d != null) return d
            }
        } catch (_: Throwable) {}

        // 2. 尝试从宿主插件中找媒体相关图标
        val candidateResNames = listOf(
            "ic_miui_volume_media",
            "ic_miplay_phone",
            "ic_miui_volume_more"
        )
        for (name in candidateResNames) {
            try {
                val resId = context.resources.getIdentifier(name, "drawable", context.packageName)
                if (resId != 0) {
                    val d = context.getDrawable(resId)
                    if (d != null) return d
                }
            } catch (_: Throwable) {}
        }

        // 3. 终极保证：纯代码绘制的3轨均衡器图标（与官方分应用音量图标一致）
        return EqualizerDrawable()
    }

    private fun resolveExpandButtonColor(context: Context): Int? {
        return try {
            val resId = context.resources.getIdentifier("miui_volume_expand_button_color", "color", context.packageName)
            if (resId != 0) {
                context.getColor(resId)
            } else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun applyOfficialBlurOrBackground(
        context: Context,
        view: View,
        pluginClassLoader: ClassLoader,
        radius: Float
    ) {
        OfficialRingerBackground.apply(context, view, pluginClassLoader, radius)
    }

    private fun bindFolmeTouch(view: View, pluginClassLoader: ClassLoader) {
        try {
            val folmeClass = Class.forName("miuix.animation.Folme", false, pluginClassLoader)
            val useAtMethod = folmeClass.getMethod("useAt", Array<View>::class.java)
            val folmeInstance = useAtMethod.invoke(null, arrayOf(view))
            val touchMethod = folmeInstance.javaClass.getMethod("touch")
            val touchInstance = touchMethod.invoke(folmeInstance)

            val animConfigClass = Class.forName("miuix.animation.base.AnimConfig", false, pluginClassLoader)
            val animConfigArray = java.lang.reflect.Array.newInstance(animConfigClass, 0)
            val handleTouchMethod = touchInstance.javaClass.getMethod(
                "handleTouchOf",
                View::class.java,
                animConfigArray.javaClass
            )
            handleTouchMethod.invoke(touchInstance, view, animConfigArray)
        } catch (_: Throwable) {
            // Folme 绑定失败则使用默认点击反馈
        }
    }

    /**
     * 纯代码绘制的分应用音量 (3轨均衡器滑块) 图标 Drawable。
     * 无需依赖 XML 解析或外部资源，保证 100% 可视。
     */
    private class EqualizerDrawable : Drawable() {
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            color = Color.WHITE
        }
        private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }
        private var tintList: ColorStateList? = null

        override fun draw(canvas: Canvas) {
            val b = bounds
            val w = b.width().toFloat()
            val h = b.height().toFloat()
            if (w <= 0f || h <= 0f) return

            val color = tintList?.getColorForState(state, Color.WHITE) ?: Color.WHITE
            linePaint.color = color
            knobPaint.color = color

            val sX = w / 24f
            val sY = h / 24f
            linePaint.strokeWidth = 2f * sX

            fun drawTrack(xGrid: Float, knobCenterY: Float) {
                val cx = b.left + xGrid * sX
                canvas.drawLine(cx, b.top + 4.5f * sY, cx, b.top + 19.5f * sY, linePaint)
                val kw = 4.2f * sX
                val kh = 5.6f * sY
                val cy = b.top + knobCenterY * sY
                val rect = RectF(cx - kw / 2f, cy - kh / 2f, cx + kw / 2f, cy + kh / 2f)
                val r = 1.8f * sX
                canvas.drawRoundRect(rect, r, r, knobPaint)
            }

            // 3 条滑轨与滑块位置 (x: 5.5, 12, 18.5; y: 13, 8, 15)
            drawTrack(5.5f, 13f)
            drawTrack(12f, 8f)
            drawTrack(18.5f, 15f)
        }

        override fun setAlpha(alpha: Int) {
            linePaint.alpha = alpha
            knobPaint.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            linePaint.colorFilter = colorFilter
            knobPaint.colorFilter = colorFilter
            invalidateSelf()
        }

        override fun setTintList(tint: ColorStateList?) {
            tintList = tint
            invalidateSelf()
        }

        override fun isStateful(): Boolean = tintList?.isStateful == true

        override fun onStateChange(state: IntArray): Boolean {
            invalidateSelf()
            return true
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
