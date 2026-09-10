package com.miui.appvolumebar.misound

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.AnimationSet
import android.view.animation.DecelerateInterpolator
import android.view.animation.TranslateAnimation
import android.widget.ImageView
import android.widget.LinearLayout
import com.miui.appvolumebar.MainHook
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.ref.WeakReference

/**
 * 针对 com.miui.misound 的 Hook 逻辑：
 * 1. 拦截左侧蓝色悬浮球（阻止将其添加到 WindowManager）；
 * 2. 截获悬浮球 View 实例及 Context；
 * 3. 监听来自 SystemUI 的展开广播并触发系统原生分应用面板弹出；
 * 4. 自定义展开样式：右侧悬浮深色圆角毛玻璃卡片（硬件级实时背景高斯模糊 BackgroundBlurDrawable），背景清晰不模糊；
 * 5. 精确对齐目标样式：定制音量柱长宽比例（高度占屏幕 22.1%，宽度占 15.8%），避免音量柱过长或被挤压过细；
 * 6. 自定义平移动画：从屏幕右侧平移进入，收起时向右平移退出。
 */
object MiSoundHooker {

    private var capturedFab: WeakReference<View>? = null
    private var cachedContext: WeakReference<Context>? = null
    private var cachedControllerInstance: WeakReference<Any>? = null
    private var isReceiverRegistered = false
    private val mainHandler = Handler(Looper.getMainLooper())

    fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        hookWindowManager(lpparam.classLoader)
        hookVolumeUIService(lpparam.classLoader)
        hookMediaVolumeController(lpparam.classLoader)
    }

    private fun hookWindowManager(classLoader: ClassLoader) {
        val windowManagerImpl = XposedHelpers.findClassIfExists("android.view.WindowManagerImpl", classLoader)
        if (windowManagerImpl != null) {
            try {
                XposedBridge.hookAllMethods(windowManagerImpl, "addView", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val view = param.args.getOrNull(0) as? View ?: return
                        val params = param.args.getOrNull(1) as? WindowManager.LayoutParams ?: return

                        if (isFloatButtonView(view, params)) {
                            MainHook.log("Intercepted and suppressed Misound float button addView")
                            captureButtonAndContext(view, classLoader)
                            // 阻止向 WindowManager 添加悬浮球视图，彻底对用户隐藏
                            param.result = null
                            return
                        }

                        if (isMediaVolumePageView(view)) {
                            MainHook.log("Intercepted MediaVolumePageView addView, disabling full-screen window blur/dim")
                            // 移除全屏窗口背景高斯模糊与全屏暗化，使得桌面壁纸与应用图标保持清晰锐利
                            params.flags = params.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
                            params.flags = params.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv()
                            params.dimAmount = 0.0f
                            try {
                                params.javaClass.getMethod("setBlurBehindRadius", Int::class.javaPrimitiveType)
                                    .invoke(params, 0)
                            } catch (_: Throwable) {}
                        }
                    }
                })
                MainHook.log("Hooked WindowManagerImpl.addView successfully in Misound")
            } catch (t: Throwable) {
                MainHook.log("Failed to hook WindowManagerImpl.addView in Misound", t)
            }
        }
    }

    private fun hookVolumeUIService(classLoader: ClassLoader) {
        try {
            val serviceClass = XposedHelpers.findClassIfExists("com.miui.misound.playervolume.VolumeUIService", classLoader)
            if (serviceClass != null) {
                XposedBridge.hookAllMethods(serviceClass, "onCreate", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val service = param.thisObject as? Service ?: return
                        ensureReceiverRegistered(service.applicationContext, classLoader)
                    }
                })
                XposedBridge.hookAllMethods(serviceClass, "onStartCommand", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val service = param.thisObject as? Service ?: return
                        ensureReceiverRegistered(service.applicationContext, classLoader)
                    }
                })
                MainHook.log("Hooked VolumeUIService successfully")
            }
        } catch (t: Throwable) {
            MainHook.log("Failed to hook VolumeUIService", t)
        }
    }

    private fun hookMediaVolumeController(classLoader: ClassLoader) {
        val controllerClass = XposedHelpers.findClassIfExists("com.miui.misound.playervolume.a", classLoader)
        if (controllerClass == null) {
            MainHook.log("Controller class com.miui.misound.playervolume.a not found")
            return
        }

        // 0. Hook 构造函数，第一时间捕获全局唯一的控制器实例
        try {
            XposedBridge.hookAllConstructors(controllerClass, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    cachedControllerInstance = WeakReference(param.thisObject)
                    MainHook.log("Captured com.miui.misound.playervolume.a instance from constructor")
                }
            })
        } catch (t: Throwable) {
            MainHook.log("Failed to hook controller constructor", t)
        }

        // 1. Hook n() - 初始化 MediaVolumePageView 布局
        try {
            XposedHelpers.findAndHookMethod(controllerClass, "n", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    cachedControllerInstance = WeakReference(param.thisObject)
                    MainHook.log("com.miui.misound.playervolume.a.n() called, configuring card layout")
                    val o = XposedHelpers.getObjectField(param.thisObject, "o") as? ViewGroup ?: return
                    setupCardLayout(o, param.thisObject)
                }
            })
            MainHook.log("Hooked com.miui.misound.playervolume.a.n successfully")
        } catch (t: Throwable) {
            MainHook.log("Failed to hook com.miui.misound.playervolume.a.n", t)
        }

        // 2. Hook y() - 展开多应用音量调节面板
        try {
            XposedHelpers.findAndHookMethod(controllerClass, "y", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    cachedControllerInstance = WeakReference(param.thisObject)
                    MainHook.log("com.miui.misound.playervolume.a.y() called, triggering right slide-in animation")
                    // 清除原生代码给 ViewPager2 添加的中心放大 ScaleAnimation 动画
                    val p = XposedHelpers.getObjectField(param.thisObject, "p") as? View
                    p?.clearAnimation()

                    val o = XposedHelpers.getObjectField(param.thisObject, "o") as? ViewGroup ?: return
                    setupCardLayout(o, param.thisObject)
                    val cardContainer = findCardContainer(o) ?: return

                    cardContainer.clearAnimation()
                    val slideIn = AnimationSet(true).apply {
                        addAnimation(
                            TranslateAnimation(
                                Animation.RELATIVE_TO_SELF, 1.0f,
                                Animation.RELATIVE_TO_SELF, 0.0f,
                                Animation.RELATIVE_TO_SELF, 0.0f,
                                Animation.RELATIVE_TO_SELF, 0.0f
                            )
                        )
                        addAnimation(AlphaAnimation(0.0f, 1.0f))
                        duration = 220L
                        interpolator = DecelerateInterpolator(1.8f)
                    }
                    cardContainer.startAnimation(slideIn)
                }
            })
            MainHook.log("Hooked com.miui.misound.playervolume.a.y successfully")
        } catch (t: Throwable) {
            MainHook.log("Failed to hook com.miui.misound.playervolume.a.y", t)
        }

        // 3. Hook g() - 收起多应用音量面板
        try {
            XposedHelpers.findAndHookMethod(controllerClass, "g", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val controller = param.thisObject
                    val status = XposedHelpers.getIntField(controller, "a")
                    if (status != STATUS_EXPANDED) {
                        return
                    }
                    MainHook.log("com.miui.misound.playervolume.a.g() called, triggering right slide-out animation")
                    XposedHelpers.setIntField(controller, "a", STATUS_CLOSING)

                    val p = XposedHelpers.getObjectField(controller, "p") as? View
                    p?.clearAnimation()

                    val o = XposedHelpers.getObjectField(controller, "o") as? ViewGroup
                    val cardContainer = findCardContainer(o)
                    if (cardContainer != null) {
                        cardContainer.clearAnimation()
                        val slideOut = AnimationSet(true).apply {
                            addAnimation(
                                TranslateAnimation(
                                    Animation.RELATIVE_TO_SELF, 0.0f,
                                    Animation.RELATIVE_TO_SELF, 1.0f,
                                    Animation.RELATIVE_TO_SELF, 0.0f,
                                    Animation.RELATIVE_TO_SELF, 0.0f
                                )
                            )
                            addAnimation(AlphaAnimation(1.0f, 0.0f))
                            duration = 200L
                            interpolator = AccelerateInterpolator(1.8f)
                            fillAfter = true
                        }
                        cardContainer.startAnimation(slideOut)
                    }

                    val handler = XposedHelpers.getObjectField(controller, "x") as? Handler ?: mainHandler
                    handler.postDelayed({
                        try {
                            XposedHelpers.callMethod(controller, "p")
                        } catch (t: Throwable) {
                            MainHook.log("Error invoking controller.p() during dismiss", t)
                        }
                    }, 200L)

                    // 阻止原生 g() 逻辑执行，避免重复启动原生 ScaleAnimation 动画和冗余定时器
                    param.result = null
                }
            })
            MainHook.log("Hooked com.miui.misound.playervolume.a.g successfully")
        } catch (t: Throwable) {
            MainHook.log("Failed to hook com.miui.misound.playervolume.a.g", t)
        }

        // 4. Hook 适配器 com.miui.misound.playervolume.a$i：
        // (1) 务必保持 itemView 为 MATCH_PARENT，避免 ViewPager2 校验崩溃；
        // (2) 移除原版将 Page 根视图作为全屏点击退出监听器的逻辑，避免点击卡片内部空白时关闭面板；
        // (3) 在构造函数结束及 onBindViewHolder 时校准所有页面的滑块尺寸，彻底清除最后一项被原生逻辑附加的巨额 marginEnd
        try {
            val adapterClass = XposedHelpers.findClassIfExists("com.miui.misound.playervolume.a\$i", classLoader)
            if (adapterClass != null) {
                XposedBridge.hookAllConstructors(adapterClass, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val adapter = param.thisObject
                        val eList = try {
                            XposedHelpers.getObjectField(adapter, "e") as? List<*>
                        } catch (_: Throwable) { null }
                        if (!eList.isNullOrEmpty()) {
                            for (page in eList) {
                                val viewGroup = page as? ViewGroup ?: continue
                                adjustAllSlidersInView(viewGroup)
                            }
                        }
                    }
                })

                XposedBridge.hookAllMethods(adapterClass, "onCreateViewHolder", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val viewHolder = param.result ?: return
                        val itemView = XposedHelpers.getObjectField(viewHolder, "itemView") as? View ?: return
                        itemView.setOnClickListener(null)
                        itemView.isClickable = false
                    }
                })

                XposedBridge.hookAllMethods(adapterClass, "onBindViewHolder", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val viewHolder = param.args.getOrNull(0) ?: return
                        val itemView = XposedHelpers.getObjectField(viewHolder, "itemView") as? View ?: return
                        adjustAllSlidersInView(itemView)
                    }
                })
                MainHook.log("Hooked com.miui.misound.playervolume.a\$i successfully")
            }
        } catch (t: Throwable) {
            MainHook.log("Failed to hook adapter onCreateViewHolder/onBindViewHolder", t)
        }

        // 5. Hook 切页回调 a$e.onPageSelected：
        // 用户左右滑动页面时，即时对当前切入页面的所有滑块进行尺寸校准，并更新指示器小圆点的选中高亮颜色（当前页为白色）
        try {
            val pageCallbackClass = XposedHelpers.findClassIfExists("com.miui.misound.playervolume.a\$e", classLoader)
            if (pageCallbackClass != null) {
                XposedHelpers.findAndHookMethod(pageCallbackClass, "onPageSelected", Int::class.javaPrimitiveType, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val position = param.args[0] as Int
                        val controller = try {
                            XposedHelpers.getSurroundingThis(param.thisObject)
                        } catch (_: Throwable) {
                            cachedControllerInstance?.get()
                        }
                        val p = controller?.let {
                            try {
                                XposedHelpers.getObjectField(it, "p") as? View
                            } catch (_: Throwable) { null }
                        }
                        if (p != null) {
                            adjustAllSlidersInView(p)
                        }
                        val q = controller?.let {
                            try {
                                XposedHelpers.getObjectField(it, "q") as? ViewGroup
                            } catch (_: Throwable) { null }
                        }
                        if (q != null) {
                            for (i in 0 until q.childCount) {
                                val child = q.getChildAt(i) ?: continue
                                updateIndicatorDotStyle(child, i == position)
                            }
                        }
                    }
                })
                MainHook.log("Hooked com.miui.misound.playervolume.a\$e.onPageSelected successfully")
            }
        } catch (t: Throwable) {
            MainHook.log("Failed to hook com.miui.misound.playervolume.a\$e.onPageSelected", t)
        }

        // 6. Hook 每个音量柱的初始化方法（a$j 为系统媒体音量，a$h 为分应用独立音量），
        // 定制音量条的长宽比例，与目标效果图精准对齐
        val columnClasses = listOf("com.miui.misound.playervolume.a\$j", "com.miui.misound.playervolume.a\$h")
        for (clsName in columnClasses) {
            val cls = XposedHelpers.findClassIfExists(clsName, classLoader) ?: continue
            try {
                XposedHelpers.findAndHookMethod(cls, "a", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        applySliderDimensions(param.thisObject)
                    }
                })
                MainHook.log("Hooked $clsName.a successfully for slider dimensions")
            } catch (t: Throwable) {
                MainHook.log("Failed to hook $clsName.a", t)
            }
        }
    }

    private fun setupCardLayout(mediaVolumePageView: ViewGroup, controller: Any? = null) {
        try {
            // MediaVolumePageView 是全屏根布局，将其子项定位在右侧垂直居中
            if (mediaVolumePageView is LinearLayout) {
                mediaVolumePageView.gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }
            mediaVolumePageView.clipChildren = false
            mediaVolumePageView.clipToPadding = false

            val cardContainer = findCardContainer(mediaVolumePageView) ?: return
            val context = cardContainer.context
            val density = context.resources.displayMetrics.density

            val marginEndPx = (16 * density).toInt()
            val padVPx = (16 * density).toInt()
            val colGap = (context.resources.displayMetrics.widthPixels * 0.038f).toInt()
            val halfGap = colGap / 2
            // 确保卡片边缘到滑块外边缘的间距（padHPx + halfGap）与上下边距（padVPx = 16dp）严格一致
            val padHPx = (padVPx - halfGap).coerceAtLeast(0)
            val cornerRadius = 28f * density

            // 根据当前正在播放的音频流数量，动态设置内部 ViewPager2 的宽度，
            // 使得卡片能够紧凑贴合 1~3 个音量柱，且每个音量柱都具备充裕展示空间，绝不发生挤压
            val viewPager = findViewPager2(cardContainer)
            if (viewPager != null) {
                val ctrl = controller ?: cachedControllerInstance?.get() ?: getControllerInstance(context, cardContainer.context.classLoader)
                val uList = if (ctrl != null) {
                    try {
                        XposedHelpers.getObjectField(ctrl, "u") as? List<*>
                    } catch (_: Throwable) {
                        null
                    }
                } else null
                val sliderCount = uList?.size ?: 1
                val cols = sliderCount.coerceIn(1, 3)
                val pWidth = calculateViewPagerWidth(context, cols)

                val vpLp = viewPager.layoutParams
                if (vpLp != null) {
                    if (vpLp.width != pWidth) {
                        vpLp.width = pWidth
                    }
                    if (vpLp is ViewGroup.MarginLayoutParams) {
                        vpLp.topMargin = 0
                        vpLp.bottomMargin = 0
                    }
                    viewPager.layoutParams = vpLp
                } else {
                    viewPager.layoutParams = ViewGroup.MarginLayoutParams(pWidth, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = 0
                        bottomMargin = 0
                    }
                }

                // 处理多页面指示器（当音频流总数 > 3 时显示；<= 3 时隐藏消除留白）
                val indId = context.resources.getIdentifier("volume_indicator", "id", context.packageName)
                val indicatorView = (ctrl?.let {
                    try {
                        XposedHelpers.getObjectField(it, "q") as? ViewGroup
                    } catch (_: Throwable) { null }
                })
                    ?: (if (indId != 0) cardContainer.findViewById<ViewGroup>(indId) else null)
                    ?: cardContainer.findViewById<ViewGroup>(2131362720)

                val rList = ctrl?.let {
                    try {
                        XposedHelpers.getObjectField(it, "r") as? List<*>
                    } catch (_: Throwable) { null }
                }
                val totalPages = rList?.size ?: (if (sliderCount > 3) 2 else 1)

                if (indicatorView != null) {
                    if (totalPages <= 1) {
                        indicatorView.visibility = View.GONE
                    } else {
                        indicatorView.visibility = View.VISIBLE
                        (indicatorView.layoutParams as? ViewGroup.MarginLayoutParams)?.let { mlp ->
                            mlp.topMargin = (8 * density).toInt()
                            mlp.bottomMargin = 0
                            indicatorView.layoutParams = mlp
                        }
                        val curPage = try {
                            XposedHelpers.callMethod(viewPager, "getCurrentItem") as? Int ?: 0
                        } catch (_: Throwable) { 0 }
                        // 如果指示器尚未填充小圆点，主动填充并高亮当前页
                        if (indicatorView.childCount == 0 && !rList.isNullOrEmpty()) {
                            for (i in 0 until rList.size) {
                                val iv = rList[i] as? View ?: continue
                                (iv.parent as? ViewGroup)?.removeView(iv)
                                updateIndicatorDotStyle(iv, i == curPage)
                                indicatorView.addView(iv)
                            }
                        } else {
                            for (i in 0 until indicatorView.childCount) {
                                val child = indicatorView.getChildAt(i) ?: continue
                                updateIndicatorDotStyle(child, i == curPage)
                            }
                        }
                    }
                }

                // 确保已存在的所有音量柱均应用目标长宽尺寸并清除内底边距及挤压
                adjustAllSlidersInView(viewPager)
            }

            val lp = (cardContainer.layoutParams as? LinearLayout.LayoutParams)
                ?: LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            lp.gravity = Gravity.END or Gravity.CENTER_VERTICAL
            lp.marginEnd = marginEndPx
            cardContainer.layoutParams = lp

            cardContainer.setPadding(padHPx, padVPx, padHPx, padVPx)

            // 防止点击卡片内部穿透触发全屏 MediaVolumePageView.onTouchEvent 关闭
            cardContainer.isClickable = true
            cardContainer.isFocusable = true

            // 设置圆角裁剪 Outline
            cardContainer.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, outline: Outline) {
                    outline.setRoundRect(0, 0, v.width, v.height, cornerRadius)
                }
            }
            cardContainer.clipToOutline = true
            cardContainer.elevation = 16f * density

            // 监听 Attach 状态以应用系统级硬件背景高斯模糊
            if (cardContainer.getTag(TAG_CARD_INITIALIZED) == null) {
                cardContainer.setTag(TAG_CARD_INITIALIZED, true)
                cardContainer.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {
                        applyBackdropBlur(v, cornerRadius, isNightMode(v.context))
                    }
                    override fun onViewDetachedFromWindow(v: View) {}
                })
            }

            if (cardContainer.isAttachedToWindow) {
                applyBackdropBlur(cardContainer, cornerRadius, isNightMode(context))
            }
        } catch (t: Throwable) {
            MainHook.log("Failed to setup card layout", t)
        }
    }

    /**
     * 根据目标参考图 IMG_20260910_104247.jpg 精确校准尺寸：
     * - 单个音量柱宽度占屏幕宽度的 15.8%（约 66~68dp，饱满胶囊样式）；
     * - 音量柱间距占屏幕宽度的 3.8%（约 16dp）；
     * - 音量柱高度占屏幕高度的 22.1%（约 180~190dp，较原版 225dp 缩短，更加精致和谐）；
     * - 清除原版布局中多余的 15dip 底部内边距，确保上下边距完全一致对称。
     */
    private fun applySliderDimensions(lVar: Any) {
        try {
            val seekBar = XposedHelpers.getObjectField(lVar, "b") as? View ?: return
            val context = seekBar.context
            val screenHeight = context.resources.displayMetrics.heightPixels
            val screenWidth = context.resources.displayMetrics.widthPixels

            val targetHeight = (screenHeight * 0.221f).toInt()
            val targetWidth = (screenWidth * 0.158f).toInt()
            val colGap = (screenWidth * 0.038f).toInt()
            val halfGap = colGap / 2

            val lp = seekBar.layoutParams
            if (lp != null) {
                lp.height = targetHeight
                lp.width = targetWidth
                if (lp is ViewGroup.MarginLayoutParams) {
                    lp.marginStart = halfGap
                    lp.marginEnd = halfGap
                    lp.topMargin = 0
                    lp.bottomMargin = 0
                }
                seekBar.layoutParams = lp
            }

            val rootCol = XposedHelpers.getObjectField(lVar, "a") as? View
            if (rootCol != null) {
                // 原版布局定义了 android:paddingBottom="15dip"，导致滑块到底部间距是到顶部的两倍，在此清零确保上下对称
                rootCol.setPadding(0, 0, 0, 0)
                val rlp = rootCol.layoutParams
                if (rlp is ViewGroup.MarginLayoutParams) {
                    rlp.width = ViewGroup.LayoutParams.WRAP_CONTENT
                    rlp.marginStart = 0
                    rlp.marginEnd = 0
                    rlp.topMargin = 0
                    rlp.bottomMargin = 0
                    rootCol.layoutParams = rlp
                }
            }
        } catch (t: Throwable) {
            MainHook.log("Failed to apply slider dimensions", t)
        }
    }

    private fun adjustAllSlidersInView(view: View) {
        val name = view.javaClass.name
        if (name.contains("MiuiVolumeSeekBar") || name.contains("VerticalSeekBar")) {
            val context = view.context
            val screenHeight = context.resources.displayMetrics.heightPixels
            val screenWidth = context.resources.displayMetrics.widthPixels
            val targetHeight = (screenHeight * 0.221f).toInt()
            val targetWidth = (screenWidth * 0.158f).toInt()
            val colGap = (screenWidth * 0.038f).toInt()
            val halfGap = colGap / 2

            val lp = view.layoutParams
            if (lp != null) {
                val mlp = lp as? ViewGroup.MarginLayoutParams
                val needUpdate = lp.height != targetHeight ||
                    lp.width != targetWidth ||
                    (mlp != null && (mlp.marginStart != halfGap ||
                        mlp.marginEnd != halfGap ||
                        mlp.topMargin != 0 ||
                        mlp.bottomMargin != 0))
                if (needUpdate) {
                    lp.height = targetHeight
                    lp.width = targetWidth
                    if (mlp != null) {
                        mlp.marginStart = halfGap
                        mlp.marginEnd = halfGap
                        mlp.topMargin = 0
                        mlp.bottomMargin = 0
                    }
                    view.layoutParams = lp
                }
            }

            // 清除父容器可能残留的 paddingBottom="15dip" 与上下外边距，确保上下完全对称
            (view.parent as? View)?.let { parentCol ->
                parentCol.setPadding(0, 0, 0, 0)
                (parentCol.layoutParams as? ViewGroup.MarginLayoutParams)?.let { plp ->
                    val pNeedUpdate = plp.marginStart != 0 ||
                        plp.marginEnd != 0 ||
                        plp.topMargin != 0 ||
                        plp.bottomMargin != 0 ||
                        plp.width != ViewGroup.LayoutParams.WRAP_CONTENT
                    if (pNeedUpdate) {
                        plp.width = ViewGroup.LayoutParams.WRAP_CONTENT
                        plp.marginStart = 0
                        plp.marginEnd = 0
                        plp.topMargin = 0
                        plp.bottomMargin = 0
                        parentCol.layoutParams = plp
                    }
                }
            }
            return
        }

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                adjustAllSlidersInView(view.getChildAt(i))
            }
        }
    }

    private fun calculateViewPagerWidth(context: Context, columnCount: Int): Int {
        val screenWidth = context.resources.displayMetrics.widthPixels
        val targetSliderWidth = (screenWidth * 0.158f).toInt()
        val colGap = (screenWidth * 0.038f).toInt()

        val cols = columnCount.coerceIn(1, 3)
        // 内部每列占用 targetSliderWidth + colGap（两端各留 colGap / 2 边距）
        return cols * (targetSliderWidth + colGap)
    }

    /**
     * 更新多页面指示器小圆点的样式与高亮：
     * - 当前选中的页面：纯白高亮 (#FFFFFFFF / Color.WHITE)；
     * - 未选中的页面：半透明白色 (#55FFFFFF)。
     */
    private fun updateIndicatorDotStyle(dot: View, isSelected: Boolean) {
        dot.isSelected = isSelected
        val color = if (isSelected) Color.WHITE else Color.parseColor("#55FFFFFF")
        if (dot is ImageView) {
            dot.setColorFilter(color, PorterDuff.Mode.SRC_IN)
            dot.imageTintList = ColorStateList.valueOf(color)
            dot.imageTintMode = PorterDuff.Mode.SRC_IN
        }
        dot.backgroundTintList = ColorStateList.valueOf(color)
    }

    private fun findCardContainer(root: ViewGroup?): ViewGroup? {
        if (root == null || root.childCount == 0) return null
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child is ViewGroup && hasViewPager2(child)) {
                return child
            }
        }
        return root.getChildAt(0) as? ViewGroup
    }

    private fun hasViewPager2(view: View): Boolean {
        return findViewPager2(view) != null
    }

    private fun findViewPager2(view: View): View? {
        if (view.javaClass.name.contains("ViewPager2")) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findViewPager2(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun getControllerInstance(context: Context, classLoader: ClassLoader): Any? {
        return try {
            val controllerClass = Class.forName("com.miui.misound.playervolume.a", false, classLoader)
            val instanceField = controllerClass.getDeclaredField("E").apply { isAccessible = true }
            var controller = instanceField.get(null)
            if (controller == null) {
                val getInstanceMethod = controllerClass.getDeclaredMethod("j", Context::class.java).apply { isAccessible = true }
                controller = getInstanceMethod.invoke(null, context)
            }
            controller
        } catch (_: Throwable) {
            null
        }
    }

    private fun applyBackdropBlur(view: View, cornerRadius: Float, isNight: Boolean) {
        var applied = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val getViewRootImplMethod = View::class.java.getDeclaredMethod("getViewRootImpl").apply {
                    isAccessible = true
                }
                val viewRootImpl = getViewRootImplMethod.invoke(view)
                if (viewRootImpl != null) {
                    val createBlurMethod = viewRootImpl.javaClass.getMethod("createBackgroundBlurDrawable")
                    val blurDrawable = createBlurMethod.invoke(viewRootImpl) as? Drawable
                    if (blurDrawable != null) {
                        val clsInt = Int::class.javaPrimitiveType
                        val clsFloat = Float::class.javaPrimitiveType

                        // setBlurRadius(100)
                        blurDrawable.javaClass.getMethod("setBlurRadius", clsInt).invoke(blurDrawable, 100)

                        // setCornerRadius(r, r, r, r) or setCornerRadius(r)
                        try {
                            blurDrawable.javaClass.getMethod(
                                "setCornerRadius", clsFloat, clsFloat, clsFloat, clsFloat
                            ).invoke(blurDrawable, cornerRadius, cornerRadius, cornerRadius, cornerRadius)
                        } catch (_: NoSuchMethodException) {
                            blurDrawable.javaClass.getMethod("setCornerRadius", clsFloat)
                                .invoke(blurDrawable, cornerRadius)
                        }

                        // 卡片毛玻璃底色
                        val cardBgColor = if (isNight) {
                            Color.parseColor("#801E1E22")
                        } else {
                            Color.parseColor("#77626262")
                        }
                        blurDrawable.javaClass.getMethod("setColor", clsInt).invoke(blurDrawable, cardBgColor)

                        view.background = blurDrawable
                        view.setWillNotDraw(false)
                        applied = true
                        MainHook.log("Successfully applied hardware BackgroundBlurDrawable to cardContainer")
                    }
                }
            } catch (t: Throwable) {
                MainHook.log("Hardware BackgroundBlurDrawable reflection failed, using frosted fallback", t)
            }
        }

        if (!applied) {
            val fallback = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                this.cornerRadius = cornerRadius
                val fallbackColor = if (isNight) {
                    Color.parseColor("#D01E1E22")
                } else {
                    Color.parseColor("#CC454548")
                }
                setColor(fallbackColor)
            }
            view.background = fallback
        }
    }

    private fun isNightMode(context: Context): Boolean {
        val nightModeFlags = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return nightModeFlags == Configuration.UI_MODE_NIGHT_YES
    }

    private fun isMediaVolumePageView(view: View): Boolean {
        return view.javaClass.name.contains("MediaVolumePageView")
    }

    private fun isFloatButtonView(view: View, params: WindowManager.LayoutParams): Boolean {
        // 悬浮球的 LayoutParams 特征：type = 2020 (TYPE_APPLICATION_OVERLAY)
        if (params.type != 2020) {
            return false
        }

        val context = view.context ?: return false
        val pkg = context.packageName
        if (pkg != MainHook.PKG_MISOUND) {
            return false
        }

        // Misound 的悬浮球和展开页都使用 TYPE_APPLICATION_OVERLAY(2020)。
        // JADX/反编译 f.b(false) 可见悬浮球是 WRAP_CONTENT，而 y() 的
        // MediaVolumePageView 使用 MATCH_PARENT。若只按 type + close button
        // 判断，会把真正的展开页也拦截掉，于是状态虽然变成 5000，画面却永远不出现。
        if (params.width != ViewGroup.LayoutParams.WRAP_CONTENT ||
            params.height != ViewGroup.LayoutParams.WRAP_CONTENT
        ) {
            return false
        }

        // 1. 通过固定资源 ID 匹配
        val closeBtnId = context.resources.getIdentifier("miui_volume_close_button", "id", MainHook.PKG_MISOUND)
        if (closeBtnId != 0 && view.findViewById<View>(closeBtnId) != null) {
            return true
        }

        // 2. 检查 View 树中是否有 FloatingActionButton
        if (hasFloatingActionButton(view)) {
            return true
        }

        return false
    }

    private fun hasFloatingActionButton(view: View): Boolean {
        if (view.javaClass.name.contains("FloatingActionButton")) {
            return true
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                if (hasFloatingActionButton(view.getChildAt(i))) {
                    return true
                }
            }
        }
        return false
    }

    private fun captureButtonAndContext(view: View, classLoader: ClassLoader) {
        val context = view.context.applicationContext
        cachedContext = WeakReference(context)

        val closeBtnId = context.resources.getIdentifier("miui_volume_close_button", "id", MainHook.PKG_MISOUND)
        val fab = findFloatingActionButton(view)
            ?: if (closeBtnId != 0) view.findViewById<View>(closeBtnId) else null
        capturedFab = WeakReference(fab ?: view)

        MainHook.log(
            "Captured Misound float view=${view.javaClass.name}, " +
                "clickTarget=${(fab ?: view).javaClass.name}"
        )

        ensureReceiverRegistered(context, classLoader)
    }

    private fun findFloatingActionButton(view: View): View? {
        if (view.javaClass.name.contains("FloatingActionButton")) {
            return view
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findFloatingActionButton(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    @Synchronized
    private fun ensureReceiverRegistered(context: Context, classLoader: ClassLoader) {
        if (isReceiverRegistered) return

        try {
            val filter = IntentFilter(MainHook.ACTION_EXPAND_MEDIA_VOLUME)
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.action == MainHook.ACTION_EXPAND_MEDIA_VOLUME) {
                        MainHook.log("Received ACTION_EXPAND_MEDIA_VOLUME broadcast, expanding immediately")
                        // 动态注册的广播接收器 onReceive 原生就运行在主线程，无需 post，直接同步执行
                        expandMediaVolumePanel(ctx, classLoader)
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
            isReceiverRegistered = true
            MainHook.log("Registered ACTION_EXPAND_MEDIA_VOLUME receiver")
        } catch (t: Throwable) {
            MainHook.log("Failed to register expand receiver", t)
        }
    }

    private fun expandMediaVolumePanel(context: Context, classLoader: ClassLoader) {
        val controller = cachedControllerInstance?.get()
            ?: getControllerInstance(context, classLoader)
        if (controller == null) {
            MainHook.log("expandMediaVolumePanel: controller instance not available")
            return
        }

        try {
            val status = XposedHelpers.getIntField(controller, "a")
            if (status == STATUS_EXPANDED) {
                MainHook.log("Media volume panel is already expanded")
                return
            }

            // 重置状态为 0 (STATUS_IDLE)，确保 controller.y() 顺利执行展开
            XposedHelpers.setIntField(controller, "a", STATUS_IDLE)

            // 仅当音频流列表 u 为空时才同步拉取，避免每次点击产生重复的 Binder IPC 阻塞
            val uList = XposedHelpers.getObjectField(controller, "u") as? List<*>
            if (uList.isNullOrEmpty()) {
                try {
                    XposedHelpers.callMethod(controller, "u")
                } catch (_: Throwable) {}
            }

            // 直接调用 controller.y() 极速展开面板，耗时 < 1ms，体验与原版原生点击完全一致
            XposedHelpers.callMethod(controller, "y")
            val finalStatus = XposedHelpers.getIntField(controller, "a")
            MainHook.log("Directly invoked controller.y(): status=$finalStatus")
        } catch (t: Throwable) {
            MainHook.log("Failed to directly expand media volume panel", t)
        }
    }

    private const val STATUS_IDLE = 0
    private const val STATUS_EXPANDED = 5000
    private const val STATUS_CLOSING = 301
    private const val TAG_CARD_INITIALIZED = 0x7f0a9999
}
