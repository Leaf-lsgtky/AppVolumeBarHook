package com.miui.appvolumebar.glass

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.Collections
import java.util.IdentityHashMap

/** 捕获排除图层的收集结果：SurfaceControl 数组 + 它们的真实图层名 + 诊断文本。 */
private data class ExcludeLayers(
    val array: Any?,
    val count: Int,
    val names: List<String>,
    val detail: String,
) {
    companion object {
        val EMPTY = ExcludeLayers(null, 0, emptyList(), "未收集")
    }
}

/**
 * 进程级结论：本进程的同步截屏到底能不能用。
 *
 * misound 里 `IWindowManager.captureDisplay` 的 `getBuffer()` 会永久阻塞
 * （实测：800ms 看门狗连续 2 次超时）。一旦确认不可用就记在这里，
 * 后续每次呼出面板都不再尝试，避免重复制造僵尸线程。
 */
internal object PanelGlassCaptureSupport {
    @Volatile
    var unsupported = false

    @Volatile
    var reason = ""
}

/**
 * 液态玻璃的实时屏幕捕获，承袭 HyperIsland 的 RefractiveScreenCapture。
 *
 * 在 SystemUI 进程内通过隐藏 API `IWindowManager.captureDisplay` 同步截取指定区域，
 * 捕获时排除宿主窗口自身的 Surface（音量对话框窗口），拿到的就是面板背后的真实
 * 屏幕内容（应用、壁纸、状态栏）。排除链路双保险：优先 `setExcludeLayers(SurfaceControl[])`
 * 传入本窗口 root Surface，不可用时回退 `setExcludeOrIncludeLayerNames` 按图层名排除
 * 音量窗口（HyperIsland 的排除清单里该图层名为 `VolumePanelDialogController#`）。
 *
 * 所有捕获工作序列化到全进程单例捕获线程（[PanelGlassCaptureThread]）；
 * generation token 保证停用后迟到的旧帧不会复活状态。
 */
internal class PanelGlassCapture(
    host: View,
    private val prepareFrame: (Bitmap) -> Bitmap,
    private val onFrame: (Bitmap, Float, Float, Float, Float) -> Unit,
    private val log: (priority: Int, tag: String, message: String, throwable: Throwable?) -> Unit,
) {
    private val host = WeakReference(host)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var worker: Handler? = null

    @Volatile
    private var generation = 0

    @Volatile
    private var captureAccess: CaptureAccess? = null
    private var captureFps = PanelGlassConfig.CAPTURE_FPS
    private var captureScale = PanelGlassConfig.CAPTURE_SCALE
    private val location = IntArray(2)

    @Volatile
    private var viewSnapshot = ViewSnapshot.EMPTY

    @Volatile
    private var captureRegion: Rect? = null

    @Volatile
    var hasFrame = false
        private set

    @Volatile
    var screenX = 0f
        private set

    @Volatile
    var screenY = 0f
        private set

    @Volatile
    private var screenW = 0

    @Volatile
    private var screenH = 0

    /**
     * 排除图层是否真的拿到了。拿不到时捕获会把面板自己拍进去，形成
     * 「上一帧的玻璃 → 下一帧的内容」反馈，最终在面板里堆出纯色直角矩形。
     * 因此拿不到就绝不能开启真实折射。
     */
    @Volatile
    var exclusionOk = false
        private set

    @Volatile
    private var exclusionDetail = "未开始"

    @Volatile
    private var lastFrameSize = "无"

    @Volatile
    private var lastError: String? = null

    /** 帧计数，用来确认捕获到底有没有在持续出帧。 */
    @Volatile
    private var frameCount = 0

    /** 捕获被启动了多少次。远大于 1 说明它在反复起停。 */
    @Volatile
    private var startCount = 0

    /** 连续超时次数；达到 [MAX_TIMEOUTS] 后彻底放弃真实折射。 */
    @Volatile
    private var timeoutCount = 0

    /** 截屏被判定为不可用（连续超时），本次挂载不再尝试。 */
    @Volatile
    private var captureDisabled = false

    /** 捕获状态变化（起停 / 首帧 / 报错）时回调，用于把最新自检刷到状态卡片。 */
    @Volatile
    var onStateChanged: (() -> Unit)? = null

    /** 给状态卡片用的捕获自检：排除图层 / 区域 / 帧 / 错误。 */
    fun diagnosis(): String {
        val region = captureRegion
        val area = if (region == null) "无" else "(${region.left},${region.top})-(${region.right},${region.bottom})"
        val error = lastError?.let { " 错误=$it" } ?: ""
        val exclusion = when {
            exclusionOk -> "OK"
            PanelGlassCaptureSupport.unsupported -> "已停用"
            else -> "失败"
        }
        return "排除=$exclusion($exclusionDetail) 区域=$area " +
            "屏=${screenW}x$screenH 帧=$lastFrameSize($frameCount) 启动=$startCount 超时=$timeoutCount" +
            "${if (captureDisabled) " 已放弃(${PanelGlassCaptureSupport.reason})" else ""}$error"
    }

    private fun notifyStateChanged() {
        mainHandler.post { onStateChanged?.invoke() }
    }

    fun updateSettings(fps: Int, scale: Float) {
        val nextFps = fps.coerceIn(1, 90)
        val nextScale = scale.coerceIn(0.1f, 1f)
        if (captureFps == nextFps && captureScale == nextScale) return
        if (worker != null) stop()
        captureFps = nextFps
        captureScale = nextScale
    }

    fun start() {
        if (worker != null) return
        // 已经判定本进程截屏不可用时必须彻底停手：否则会退化成
        // start → stop → invalidate → draw → start 的满帧空转
        // （实测面板存活期间能起 400+ 次）。
        if (captureDisabled || PanelGlassCaptureSupport.unsupported) return
        val snapshot = viewSnapshot
        if (!snapshot.canCapture) return
        val captureWorker = PanelGlassCaptureThread.acquire()
        worker = captureWorker
        generation++
        val token = generation
        captureWorker.post { initializeCapture(token, snapshot.displayId) }
    }

    private fun initializeCapture(token: Int, displayId: Int) {
        if (token != generation) return
        if (!viewSnapshot.canCapture) {
            mainHandler.post { if (token == generation) stop() }
            return
        }
        val access = runCatching {
            CaptureAccess.create(
                displayId,
                captureScale,
                { captureRegion },
                { viewSnapshot.exclude },
            )
        }
            .onFailure {
                lastError = "初始化失败:${it.message}"
                log(Log.ERROR, TAG, "$TAG unavailable: ${it.message}", it)
                notifyStateChanged()
            }
            .getOrNull()
        if (access == null) {
            mainHandler.post { if (token == generation) stop() }
            return
        }
        captureAccess = access
        startCount++
        exclusionOk = access.excludeCount > 0
        exclusionDetail = access.excludeDetail
        if (exclusionOk) lastError = null
        notifyStateChanged()
        log(
            Log.INFO,
            TAG,
            "Screen capture started path=${access.path} fps=$captureFps scale=$captureScale " +
                "exclude=${access.excludeDetail}",
            null,
        )
        if (!exclusionOk) {
            // 没有排除图层 = 会把面板自己拍进捕获帧 = 反馈叠加成纯色矩形。
            // 直接停掉，宁可降级到边缘高光也不要画出垃圾。
            lastError = "排除图层为空:${access.excludeDetail}"
            log(
                Log.ERROR,
                TAG,
                "Screen capture has no excluded layer, disabling true refraction: " +
                    access.excludeDetail,
                null,
            )
            mainHandler.post { if (token == generation) stop() }
            notifyStateChanged()
            return
        }
        capture(token)
    }

    fun stop() {
        if (worker == null && captureAccess == null && !hasFrame) return
        generation++
        worker = null
        captureAccess = null
        val hadFrame = hasFrame
        hasFrame = false
        lastFrameSize = "无"
        // 没有画过帧就没必要 invalidate，否则每次 stop 都触发一帧重绘，
        // 与 start 配合会形成满帧空转。
        if (hadFrame) host.get()?.postInvalidateOnAnimation()
    }

    fun release() {
        stop()
        host.clear()
    }

    /** 刷新宿主可见性与排除图层快照；不可捕获时清空快照。 */
    fun updateViewSnapshot(canCapture: Boolean) {
        val view = host.get() ?: return
        if (!canCapture) {
            if (viewSnapshot.canCapture) viewSnapshot = ViewSnapshot.EMPTY
            return
        }
        runCatching {
            view.getLocationOnScreen(location)
            screenX = location[0].toFloat()
            screenY = location[1].toFloat()
            val displayId = view.display?.displayId ?: 0
            // 排除图层拿不到时必须重试：常见情况是首次快照发生在 rootView 还没挂上
            // 窗口时（getViewRootImpl 为空）。若把空结果缓存住，捕获就会把面板自己
            // 拍进去，形成「上一帧玻璃 → 下一帧内容」的反馈，堆出纯色直角矩形。
            if (viewSnapshot.canCapture && viewSnapshot.displayId == displayId &&
                viewSnapshot.exclude.count > 0
            ) return
            val layers = CaptureAccess.collectExcludeLayers(view)
            exclusionDetail = layers.detail
            viewSnapshot = ViewSnapshot(
                canCapture = true,
                displayId = displayId,
                exclude = layers,
            )
        }.onFailure { error ->
            exclusionDetail = "异常:${error.javaClass.simpleName}"
            viewSnapshot = ViewSnapshot.EMPTY
        }
    }

    /** 以面板在屏幕上的矩形为中心，外扩模糊半径与折射距离得到捕获区域。 */
    fun updateCaptureRegion(
        localBounds: RectF,
        blurRadius: Float,
        refractionDistance: Float,
    ) {
        val view = host.get() ?: return
        if (localBounds.isEmpty) return
        runCatching {
            view.getLocationOnScreen(location)
            screenX = location[0].toFloat()
            screenY = location[1].toFloat()
            // 必须用「真实显示尺寸」而不是 resources.displayMetrics：misound 这种
            // 悬浮进程的 Configuration 可能与真实屏幕不一致，按错尺寸外扩出来的
            // sourceCrop 会伸出屏幕之外，SurfaceFlinger 遇到越界裁剪可能直接不回调。
            val real = android.util.DisplayMetrics()
            runCatching { view.display?.getRealMetrics(real) }
            val metrics = view.resources.displayMetrics
            screenW = if (real.widthPixels > 0) real.widthPixels else metrics.widthPixels
            screenH = if (real.heightPixels > 0) real.heightPixels else metrics.heightPixels
            val padding = kotlin.math.ceil(
                maxOf(blurRadius * 2f, refractionDistance * 1.25f, 1f),
            ).toInt()
            val left = kotlin.math.floor(screenX + localBounds.left - padding).toInt()
                .coerceIn(0, screenW)
            val top = kotlin.math.floor(screenY + localBounds.top - padding).toInt()
                .coerceIn(0, screenH)
            val right = kotlin.math.ceil(screenX + localBounds.right + padding).toInt()
                .coerceIn(0, screenW)
            val bottom = kotlin.math.ceil(screenY + localBounds.bottom + padding).toInt()
                .coerceIn(0, screenH)
            if (right <= left || bottom <= top) {
                captureRegion = null
            } else {
                val current = captureRegion
                if (current == null || current.left != left || current.top != top ||
                    current.right != right || current.bottom != bottom
                ) {
                    captureRegion = Rect(left, top, right, bottom)
                }
            }
        }
    }

    /**
     * 给一次同步截屏套看门狗。
     *
     * misound 进程里 `IWindowManager.captureDisplay` 可能永远不回调（实测
     * `getBuffer()` 卡死：不返回也不报错，捕获线程被永久占住）。放任不管的话
     * 面板玻璃会永远停在「没有帧」的状态，线程也白白挂着。这里超时即放弃：
     * 连续超时 [MAX_TIMEOUTS] 次后彻底关掉真实折射，不再重复制造僵尸线程。
     */
    private fun runWithWatchdog(block: () -> CapturedFrame?): CapturedFrame? {
        var result: CapturedFrame? = null
        var error: Throwable? = null
        var finished = false
        val lock = Object()
        val worker = Thread({
            try {
                result = block()
            } catch (t: Throwable) {
                error = t
            } finally {
                synchronized(lock) {
                    finished = true
                    lock.notifyAll()
                }
            }
        }, "SoundManLiquidGlassCaptureOnce").apply { isDaemon = true }
        worker.start()
        synchronized(lock) {
            val deadline = SystemClock.uptimeMillis() + CAPTURE_TIMEOUT_MS
            while (!finished) {
                val remaining = deadline - SystemClock.uptimeMillis()
                if (remaining <= 0L) break
                lock.wait(remaining)
            }
        }
        if (!finished) {
            timeoutCount++
            if (timeoutCount >= MAX_TIMEOUTS) {
                captureDisabled = true
                PanelGlassCaptureSupport.unsupported = true
                PanelGlassCaptureSupport.reason = "同步截屏超时(${CAPTURE_TIMEOUT_MS}ms)"
            }
            throw java.util.concurrent.TimeoutException("capture timeout ${CAPTURE_TIMEOUT_MS}ms")
        }
        val failure = error
        if (failure != null) throw failure
        timeoutCount = 0
        return result
    }

    private fun scheduleCapture(token: Int, delay: Long) {
        worker?.postDelayed({ capture(token) }, delay)
    }

    private fun capture(token: Int) {
        if (token != generation) return
        if (!viewSnapshot.canCapture) {
            mainHandler.post { if (token == generation) stop() }
            return
        }
        val access = captureAccess ?: return
        if (captureDisabled) {
            mainHandler.post { if (token == generation) stop() }
            return
        }
        val startedAt = SystemClock.uptimeMillis()
        val result = runCatching { runWithWatchdog { access.capture() } }
        val frame = result.getOrNull()
        if (result.isFailure || frame == null) {
            lastError = "捕获失败:" + (
                result.exceptionOrNull()?.let { "${it.javaClass.simpleName}:${it.message}" }
                    ?: "空帧(区域为空?)"
                )
            log(
                Log.ERROR,
                TAG,
                "Screen capture failed, falling back to native blur: " +
                    (result.exceptionOrNull()?.message ?: "empty frame"),
                result.exceptionOrNull(),
            )
            mainHandler.post { if (token == generation) stop() }
            notifyStateChanged()
            return
        }
        if (token != generation) {
            if (!frame.bitmap.isRecycled) frame.bitmap.recycle()
            return
        }
        val preparedBitmap = runCatching { prepareFrame(frame.bitmap) }
            .onFailure { error ->
                lastError = "模糊失败:${error.javaClass.simpleName}:${error.message}"
                log(Log.ERROR, TAG, "Screen capture frame preparation failed: ${error.message}", error)
                if (!frame.bitmap.isRecycled) frame.bitmap.recycle()
                notifyStateChanged()
            }
            .getOrNull() ?: run {
            mainHandler.post { if (token == generation) stop() }
            return
        }
        mainHandler.post {
            if (token != generation) {
                if (!preparedBitmap.isRecycled) preparedBitmap.recycle()
                return@post
            }
            runCatching {
                onFrame(
                    preparedBitmap,
                    frame.scaleX,
                    frame.scaleY,
                    frame.cropX,
                    frame.cropY,
                )
            }.onSuccess {
                hasFrame = true
                frameCount++
                lastFrameSize = "${preparedBitmap.width}x${preparedBitmap.height}"
                if (frameCount == 1) {
                    lastError = null
                    notifyStateChanged()
                }
            }.onFailure { error ->
                lastError = "投递失败:${error.javaClass.simpleName}:${error.message}"
                log(Log.ERROR, TAG, "Screen capture frame delivery failed: ${error.message}", error)
                if (!preparedBitmap.isRecycled) preparedBitmap.recycle()
                if (token == generation) stop()
                notifyStateChanged()
            }
        }
        val elapsed = SystemClock.uptimeMillis() - startedAt
        scheduleCapture(token, (captureIntervalMs - elapsed).coerceAtLeast(0L))
    }

    private val captureIntervalMs: Long
        get() = (1000L / captureFps.coerceAtLeast(1)).coerceAtLeast(1L)

    private data class CapturedFrame(
        val bitmap: Bitmap,
        val scaleX: Float,
        val scaleY: Float,
        val cropX: Float,
        val cropY: Float,
    )

    private data class ViewSnapshot(
        val canCapture: Boolean,
        val displayId: Int,
        val exclude: ExcludeLayers,
    ) {
        companion object {
            val EMPTY = ViewSnapshot(false, 0, ExcludeLayers.EMPTY)
        }
    }

    private class CaptureAccess(
        val path: String,
        val excludeCount: Int,
        val excludeDetail: String,
        private val captureFrame: () -> CapturedFrame?,
    ) {
        fun capture(): CapturedFrame? = captureFrame()

        companion object {
            /**
             * 按图层名排除的兜底清单。misound 侧真实生效的是 [collectExcludeLayers]
             * 拿到的 SurfaceControl，这份名单只在排除图层拿不到时顶上。
             */
            private val EXCLUDED_LAYER_NAMES = arrayOf(
                "VolumePanelDialogController#",
                "MediaVolumePageView",
                "com.miui.misound",
            )

            /** 系统窗口的起始 type；只有这类窗口才该从折射背景里挖掉。 */
            private const val FIRST_SYSTEM_WINDOW = 2000

            /** 本进程里所有窗口根视图（宿主所在窗口一定排在最前）。 */
            private fun windowRoots(host: View): List<View> {
                val roots = ArrayList<View>()
                val hostRoot = host.rootView ?: host
                roots.add(hostRoot)
                runCatching {
                    val wmGlobal = Class.forName("android.view.WindowManagerGlobal")
                    val instance = findMethod(wmGlobal, "getInstance")?.invoke(null)
                        ?: findMethod(wmGlobal, "peekInstance")?.invoke(null)
                        ?: return@runCatching
                    val views = (
                        findMethod(wmGlobal, "getRootViews")?.invoke(instance)
                            ?: findMethod(wmGlobal, "getWindowViews")?.invoke(instance)
                            ?: findField(wmGlobal, "mViews")?.get(instance)
                        ) as? Iterable<*>
                    views?.forEach { (it as? View)?.rootView?.let { root -> roots.add(root) } }
                }
                return roots.distinct()
            }

            private fun surfaceOf(root: View, surfaceClass: Class<*>): Any? {
                val viewRoot = findMethod(View::class.java, "getViewRootImpl")?.invoke(root)
                    ?: return null
                val surface = findMethod(viewRoot.javaClass, "getSurfaceControl")?.invoke(viewRoot)
                    ?: findField(viewRoot.javaClass, "mSurfaceControl")?.get(viewRoot)
                if (surface == null || !surfaceClass.isInstance(surface)) return null
                if (findMethod(surfaceClass, "isValid")?.invoke(surface) as? Boolean == false) {
                    return null
                }
                return surface
            }

            private fun windowType(root: View): Int {
                val viewRoot = findMethod(View::class.java, "getViewRootImpl")?.invoke(root)
                    ?: return Int.MIN_VALUE
                val attrs = findField(viewRoot.javaClass, "mWindowAttributes")?.get(viewRoot)
                    ?: return Int.MIN_VALUE
                return findField(attrs.javaClass, "type")?.get(attrs) as? Int ?: Int.MIN_VALUE
            }

            /**
             * 收集本进程所有「悬浮 / 系统」窗口的 SurfaceControl 作为排除图层。
             *
             * misound 的面板是 TYPE_VOLUME_OVERLAY(2020) 的全屏窗口，跟 HyperIsland
             * 在 SystemUI 侧的 `VolumePanelDialogController#` 完全不是一回事，靠图层名
             * 永远匹配不上；这里改成按窗口 type 收 SurfaceControl，宿主窗口无条件排除，
             * 其余系统窗口（悬浮球等）一并排除，普通 Activity 窗口（type < 2000）保留，
             * 这样玻璃折射到的仍然是真正的背景内容。
             */
            fun collectExcludeLayers(host: View): ExcludeLayers {
                val surfaceClass = runCatching { Class.forName("android.view.SurfaceControl") }
                    .getOrNull()
                    ?: return ExcludeLayers(null, 0, emptyList(), "SurfaceControl 类缺失")
                val hostRoot = host.rootView ?: host
                val surfaces = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
                val reasons = ArrayList<String>()
                val names = ArrayList<String>()
                val roots = windowRoots(host)
                for (root in roots) {
                    val type = windowType(root)
                    val isHost = root === hostRoot
                    if (!isHost && type < FIRST_SYSTEM_WINDOW) {
                        reasons.add("跳过${root.javaClass.simpleName}(type=$type)")
                        continue
                    }
                    val surface = surfaceOf(root, surfaceClass)
                    if (surface == null) {
                        reasons.add("${root.javaClass.simpleName}(type=$type)无图层")
                        continue
                    }
                    surfaces.add(surface)
                    val name = layerName(surface)
                    if (name != null) names.add(name)
                    reasons.add("${root.javaClass.simpleName}(type=$type,图层=${name ?: "?"})")
                }
                if (surfaces.isEmpty()) {
                    return ExcludeLayers(
                        null,
                        0,
                        emptyList(),
                        reasons.joinToString().ifEmpty { "无窗口根视图" },
                    )
                }
                val array = java.lang.reflect.Array.newInstance(surfaceClass, surfaces.size)
                surfaces.forEachIndexed { index, surface ->
                    java.lang.reflect.Array.set(array, index, surface)
                }
                return ExcludeLayers(array, surfaces.size, names, reasons.joinToString())
            }

            /**
             * 读出 SurfaceControl 的真实图层名。misound 面板的图层名不可能是
             * `VolumePanelDialogController#`（那是 SystemUI 的），只有把真名拿回来
             * 才能用 `setExcludeOrIncludeLayerNames` 这条兜底通道兜住。
             */
            private fun layerName(surface: Any): String? {
                val raw = findField(surface.javaClass, "mName")?.get(surface) as? String
                if (!raw.isNullOrBlank()) return raw
                val text = surface.toString()
                val marker = "name="
                val start = text.indexOf(marker)
                if (start < 0) return null
                val rest = text.substring(start + marker.length)
                val end = rest.indexOfFirst { it == ',' || it == ')' || it == '/' }
                return (if (end < 0) rest else rest.substring(0, end))
                    .trim().takeIf { it.isNotBlank() }
            }

            fun create(
                displayId: Int,
                captureScale: Float,
                getCaptureRegion: () -> Rect?,
                getExclude: () -> ExcludeLayers,
            ): CaptureAccess {
                val screenCaptureClass = Class.forName("android.window.ScreenCapture")
                return createWindowManagerAccess(
                    displayId,
                    screenCaptureClass,
                    captureScale,
                    getCaptureRegion,
                    getExclude,
                )
            }

            private fun createWindowManagerAccess(
                displayId: Int,
                screenCaptureClass: Class<*>,
                captureScale: Float,
                getCaptureRegion: () -> Rect?,
                getExclude: () -> ExcludeLayers,
            ): CaptureAccess {
                val builderClass = Class.forName(
                    "android.window.ScreenCapture\$CaptureArgs\$Builder",
                )
                val constructor = builderClass.declaredConstructors.firstOrNull {
                    it.parameterCount == 0
                }?.apply { isAccessible = true } ?: error("CaptureArgs.Builder unavailable")
                val captureArgsClass = Class.forName("android.window.ScreenCapture\$CaptureArgs")
                val buildMethod = findMethod(builderClass, "build")
                    ?: error("CaptureArgs unavailable")
                val windowManagerGlobal = Class.forName("android.view.WindowManagerGlobal")
                val service = findMethod(windowManagerGlobal, "getWindowManagerService")
                    ?.invoke(null) ?: error("IWindowManager unavailable")
                val createListener = findMethod(screenCaptureClass, "createSyncCaptureListener")
                    ?: error("sync capture listener unavailable")
                val captureMethod = service.javaClass.methods.firstOrNull { method ->
                    method.name == "captureDisplay" &&
                        method.parameterCount == 3 &&
                        method.parameterTypes[0] == Int::class.javaPrimitiveType &&
                        method.parameterTypes[1].isAssignableFrom(captureArgsClass)
                }?.apply { isAccessible = true }
                    ?: service.javaClass.declaredMethods.firstOrNull { method ->
                        method.name == "captureDisplay" &&
                            method.parameterCount == 3 &&
                            method.parameterTypes[0] == Int::class.javaPrimitiveType &&
                            method.parameterTypes[1].isAssignableFrom(captureArgsClass)
                    }?.apply { isAccessible = true }
                    ?: error("IWindowManager.captureDisplay unavailable")
                return CaptureAccess(
                    path = "iwm-local",
                    excludeCount = getExclude().count,
                    excludeDetail = getExclude().detail,
                    captureFrame = {
                        val region = getCaptureRegion()
                            ?: return@CaptureAccess null
                        val exclude = getExclude()
                        val builder = constructor.newInstance()
                        configureCaptureBuilder(
                            builderClass,
                            builder,
                            exclude,
                            region,
                            captureScale,
                        )
                        val args = buildMethod.invoke(builder)
                            ?: error("CaptureArgs unavailable")
                        val listener = createListener.invoke(null)
                            ?: error("sync capture listener creation failed")
                        captureMethod.invoke(service, displayId, args, listener)
                        val buffer = findMethod(listener.javaClass, "getBuffer")?.invoke(listener)
                            ?: error("sync capture returned no buffer")
                        val bitmap = findMethod(buffer.javaClass, "asBitmap")?.invoke(buffer) as? Bitmap
                            ?: return@CaptureAccess null
                        CapturedFrame(
                            bitmap = bitmap,
                            scaleX = bitmap.width.toFloat() / region.width(),
                            scaleY = bitmap.height.toFloat() / region.height(),
                            cropX = region.left.toFloat(),
                            cropY = region.top.toFloat(),
                        )
                    },
                )
            }

            private fun configureCaptureBuilder(
                builderClass: Class<*>,
                builder: Any,
                exclude: ExcludeLayers,
                region: Rect,
                captureScale: Float,
            ) {
                val setSourceCrop = findMethod(builderClass, "setSourceCrop", Rect::class.java)
                    ?: error("local capture crop unsupported")
                setSourceCrop.invoke(builder, region)
                val setSize = findMethod(
                    builderClass,
                    "setSize",
                    Int::class.javaPrimitiveType!!,
                    Int::class.javaPrimitiveType!!,
                )
                if (setSize != null) {
                    setSize.invoke(
                        builder,
                        (region.width() * captureScale).toInt().coerceAtLeast(1),
                        (region.height() * captureScale).toInt().coerceAtLeast(1),
                    )
                } else {
                    val oneScale = findMethod(
                        builderClass,
                        "setFrameScale",
                        Float::class.javaPrimitiveType!!,
                    )
                    val twoScale = findMethod(
                        builderClass,
                        "setFrameScale",
                        Float::class.javaPrimitiveType!!,
                        Float::class.javaPrimitiveType!!,
                    )
                    when {
                        oneScale != null -> oneScale.invoke(builder, captureScale)
                        twoScale != null -> twoScale.invoke(builder, captureScale, captureScale)
                        else -> error("capture scale unsupported")
                    }
                }
                findMethod(
                    builderClass,
                    "setCaptureMode",
                    Int::class.javaPrimitiveType!!,
                )?.invoke(builder, 1)
                val setExcludeLayers = findMethod(builderClass, "setExcludeLayers")
                    ?: builderClass.methods.firstOrNull {
                        it.name == "setExcludeLayers" && it.parameterCount == 1
                    }?.apply { isAccessible = true }
                    ?: builderClass.declaredMethods.firstOrNull {
                        it.name == "setExcludeLayers" && it.parameterCount == 1
                    }?.apply { isAccessible = true }
                // 两套排除一起上，且 SurfaceControl 放最后：按名字排除那条路能顺带挖掉
                // SystemUI 侧的音量窗口（VolumePanelDialogController#），按图层排除则保证
                // misound 自己的面板一定被挖掉（它的图层名永远匹配不上名字清单）。
                val setLayerNames = findMethod(
                    builderClass,
                    "setExcludeOrIncludeLayerNames",
                    Array<String>::class.java,
                )
                if (setLayerNames != null) {
                    // 静态清单（SystemUI 音量窗口）+ 运行时读到的真实图层名
                    val names = (EXCLUDED_LAYER_NAMES.toList() + exclude.names).toTypedArray()
                    setLayerNames.invoke(builder, names)
                }
                if (exclude.array != null && setExcludeLayers != null) {
                    setExcludeLayers.invoke(builder, exclude.array)
                } else if (setLayerNames == null) {
                    error("no supported capture exclusion mechanism")
                }
            }

            private fun findField(clazz: Class<*>, name: String): java.lang.reflect.Field? {
                var current: Class<*>? = clazz
                while (current != null) {
                    runCatching {
                        return current.getDeclaredField(name).apply { isAccessible = true }
                    }
                    current = current.superclass
                }
                return null
            }

            private fun findMethod(
                clazz: Class<*>,
                name: String,
                vararg types: Class<*>,
            ): Method? {
                runCatching {
                    return clazz.getMethod(name, *types).apply { isAccessible = true }
                }
                var current: Class<*>? = clazz
                while (current != null) {
                    runCatching {
                        return current.getDeclaredMethod(name, *types).apply { isAccessible = true }
                    }
                    current = current.superclass
                }
                return null
            }
        }
    }

    private companion object {
        const val TAG = "SoundMan.LiquidGlass.Capture"
        const val CAPTURE_TIMEOUT_MS = 800L
        const val MAX_TIMEOUTS = 2
    }
}

/** 把全部真实折射捕获序列化到一条进程级捕获线程。 */
internal object PanelGlassCaptureThread {
    private val thread = android.os.HandlerThread("SoundManLiquidGlass").apply { start() }
    private val handler = Handler(thread.looper)

    fun acquire(): Handler = handler
}
