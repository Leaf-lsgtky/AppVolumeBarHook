package com.miui.appvolumebar.systemui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.miui.appvolumebar.MainHook
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.miui.appvolumebar.status.HookTracker
import java.lang.ref.WeakReference
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.Field

/**
 * SystemUI 注入与音量条生命周期管理。
 * 支持三种互补途径捕获 ClassLoader 并完成注入：
 * 1. 直接被 LSPosed 加载 (当 LSPosed scope 包含 miui.systemui.plugin)
 * 2. 拦截 SystemUI 内 PluginInstance (loadPlugin / getPlugin)
 * 3. 拦截 View 生命周期的通用类 (onAttachedToWindow / onFinishInflate / setVisibility)
 *
 * 动画保护：官方 VolumeShowHideAnimator 的入场/退场动画期间（mIsAnimating），
 * 本模块绝不改动 footer 布局（入口/间隔条可见性一律推迟到动画结束），
 * 并拦截重复的 MiuiVolumeDialogMotion#showVolumePanel 调用，避免面板被重新
 * 瞬移到屏外后重播入场动画（表现为"播一点不完整再从头正常播放"）。
 */
object SystemUiHooker {

    val tracker = HookTracker(MainHook.PKG_SYSTEMUI)

    private const val PLUGIN_INSTANCE_CLASS = "com.android.systemui.shared.plugins.PluginInstance"
    private const val TARGET_PLUGIN_PACKAGE = "miui.systemui.plugin"

    @Volatile
    private var isPluginHooked = false
    private var pluginClassLoader: ClassLoader? = null
    private var isExpanded = false

    private var cachedController: WeakReference<Any>? = null
    private var cachedEntryView: WeakReference<View>? = null
    private var cachedDividerView: WeakReference<View>? = null
    private var cachedDndView: WeakReference<View>? = null
    private var cachedSlideAnim: WeakReference<Any>? = null
    @Volatile private var slideAnimListenerHookedClass: String? = null
    @Volatile private var playbackCallbackRegistered = false
    @Volatile private var latestSlideScale = 1f
    @Volatile private var slideScaleSeen = false
    private val mainHandler = Handler(Looper.getMainLooper())

    // 官方 VolumeShowHideAnimator 的运行状态跟踪，用于：
    // show/hide 动画进行中冻结本模块对 footer 布局的改动（可见性变更会让入场动画中途重排）
    private var cachedAnimatorRef: WeakReference<Any>? = null
    @Volatile private var visibilityUpdateDeferred = false
    private var deferredVisibilityRetries = 0

    private const val VISIBILITY_RETRY_DELAY_MS = 64L
    private const val VISIBILITY_MAX_RETRIES = 24

    /**
     * 途径 1: 当 LSPosed 命中 miui.systemui.plugin 时直接调用此方法。
     */
    fun initPlugin(cl: ClassLoader) {
        synchronized(this) {
            if (isPluginHooked && pluginClassLoader === cl) return
            pluginClassLoader = cl
            isPluginHooked = true
        }
        tracker.recordSuccess("sysui_plugin_scope", "音量插件环境 (miui.systemui.plugin)", cl.javaClass.simpleName, "-", "成功捕获并进入插件环境")
        MainHook.log("SystemUiHooker.initPlugin acquired ClassLoader: $cl")
        hookVolumePlugin(cl)
    }

    /**
     * 当 LSPosed 加载 com.android.systemui 时调用。
     */
    fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 捕获 Application Context 用于版本信息读取与状态通信
        try {
            val appClass = XposedHelpers.findClassIfExists("android.app.Application", lpparam.classLoader)
            if (appClass != null) {
                XposedBridge.hookAllMethods(appClass, "onCreate", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val app = param.thisObject as? android.app.Application ?: return
                        tracker.attachContext(app)
                    }
                })
            }
        } catch (_: Throwable) {}

        // 途径 0: 检查 PluginInstanceInjector 是否已经持有了 miui.systemui.plugin 的 ClassLoader (HyperOS 4)
        try {
            val cl = getClassLoaderFromInjector(lpparam.classLoader, TARGET_PLUGIN_PACKAGE)
            if (cl != null) {
                tracker.recordSuccess("sysui_plugin_injector", "插件类注入器 (PluginInstanceInjector)", "PluginInstanceInjector", "sClassLoaders", "捕获 ClassLoader")
                MainHook.log("Found existing plugin ClassLoader in PluginInstanceInjector.sClassLoaders")
                initPlugin(cl)
            }
        } catch (t: Throwable) {
            MainHook.log("Failed to check PluginInstanceInjector", t)
        }

        // 途径 1: 监视 PluginInstance.loadPlugin (HyperOS 3 & 4 核心插件机制，音量条由 miui.systemui.plugin 承载)
        try {
            val pluginInstanceClass = XposedHelpers.findClassIfExists(PLUGIN_INSTANCE_CLASS, lpparam.classLoader)
            if (pluginInstanceClass != null) {
                tracker.recordSuccess("sysui_plugin_instance", "插件实例加载 (PluginInstance)", PLUGIN_INSTANCE_CLASS, "loadPlugin")
                hookPluginInstance(pluginInstanceClass)
            } else {
                tracker.recordWaiting("sysui_plugin_instance", "插件实例加载 (PluginInstance)", PLUGIN_INSTANCE_CLASS, "loadPlugin", "当前版本未找到此类")
                MainHook.log("PluginInstance class not found in SystemUI")
            }
        } catch (t: Throwable) {
            tracker.recordFailure("sysui_plugin_instance", "插件实例加载 (PluginInstance)", PLUGIN_INSTANCE_CLASS, "loadPlugin", t)
            MainHook.log("Failed to hook PluginInstance", t)
        }

        // 途径 2: 监视 PluginActionManager (PluginInstance 生命周期的上层管理器)
        hookPluginActionManager(lpparam.classLoader)

        // 途径 3 (备选): 检测当前 ClassLoader 是否已直接包含 MiuiVolumeDialogView (应对某些未解耦插件的 ROM)
        var directFound = false
        try {
            val directDialogClass = XposedHelpers.findClassIfExists("com.android.systemui.miui.volume.MiuiVolumeDialogView", lpparam.classLoader)
            if (directDialogClass != null) {
                directFound = true
                tracker.recordSuccess("sysui_plugin_direct", "内置音量视图 (Direct SystemUI)", "MiuiVolumeDialogView", "-", "系统直接集成音量视图")
                MainHook.log("Found MiuiVolumeDialogView directly in SystemUI classLoader (built-in fallback)")
                initPlugin(lpparam.classLoader)
            }
        } catch (t: Throwable) {
            MainHook.log("Failed to check direct MiuiVolumeDialogView in SystemUI", t)
        }

        // 途径 4: 兜底监视 View 生命周期（仅在尚未捕获到音量类时启用）
        if (!directFound && pluginClassLoader == null) {
            hookViewLifecycle(lpparam.classLoader)
        } else {
            MainHook.log("Skipping global View lifecycle hooks because volume plugin/classLoader is already acquired")
        }
    }

    private fun hookPluginInstance(clazz: Class<*>) {
        try {
            XposedBridge.hookAllMethods(clazz, "loadPlugin", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        tracker.recordInvoke("sysui_plugin_instance")
                        val instance = param.thisObject ?: return
                        handlePluginInstance(instance, "PluginInstance#loadPlugin")
                    } catch (t: Throwable) {
                        MainHook.log("Error in PluginInstance#loadPlugin hook", t)
                    }
                }
            })
            MainHook.log("Installed PluginInstance#loadPlugin hook")
        } catch (t: Throwable) {
            MainHook.log("Failed to hook PluginInstance#loadPlugin", t)
        }
    }

    private fun hookPluginActionManager(classLoader: ClassLoader) {
        try {
            val pamClass = XposedHelpers.findClassIfExists("com.android.systemui.shared.plugins.PluginActionManager", classLoader)
            if (pamClass != null) {
                tracker.recordSuccess("sysui_plugin_action_mgr", "插件连接管理 (PluginActionManager)", "PluginActionManager", "onPluginConnected")
                XposedBridge.hookAllMethods(pamClass, "onPluginConnected", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            tracker.recordInvoke("sysui_plugin_action_mgr")
                            val instance = param.args.getOrNull(0) ?: return
                            handlePluginInstance(instance, "PluginActionManager#onPluginConnected")
                        } catch (t: Throwable) {
                            MainHook.log("Error in PluginActionManager hook callback", t)
                        }
                    }
                })
                MainHook.log("Installed PluginActionManager#onPluginConnected hook")
            }
        } catch (t: Throwable) {
            tracker.recordFailure("sysui_plugin_action_mgr", "插件连接管理 (PluginActionManager)", "PluginActionManager", "onPluginConnected", t)
            MainHook.log("Failed to hook PluginActionManager", t)
        }
    }

    private val isHandlingPluginInstance = ThreadLocal.withInitial { false }

    private fun handlePluginInstance(instance: Any, source: String) {
        // 如果已获取且已 Hook，快速跳过，避免重复开销
        if (isPluginHooked && pluginClassLoader != null) {
            return
        }
        // 防重入保护
        if (isHandlingPluginInstance.get() == true) {
            return
        }
        try {
            isHandlingPluginInstance.set(true)

            val componentName = extractComponentName(instance)
            val pkgName = componentName?.packageName ?: extractPackageName(instance)
            val clsName = componentName?.className

            // 如果已明确是非目标插件，跳过（例如控制中心、全局操作等非音量组件）
            if (clsName != null && !clsName.contains("Volume", ignoreCase = true)) {
                if (pkgName != TARGET_PLUGIN_PACKAGE) {
                    return
                }
            }

            val isTargetPlugin = pkgName == TARGET_PLUGIN_PACKAGE ||
                    clsName?.contains("Volume", ignoreCase = true) == true

            if (!isTargetPlugin) return

            val cl = extractClassLoader(instance)
            if (cl != null) {
                MainHook.log("[$source] Acquired plugin ClassLoader via PluginInstance: $cl (pkg=$pkgName, cls=$clsName)")
                initPlugin(cl)
            } else {
                MainHook.log("[$source] PluginInstance matched (pkg=$pkgName, cls=$clsName) but ClassLoader extraction returned null")
            }
        } finally {
            isHandlingPluginInstance.set(false)
        }
    }

    private fun hookViewLifecycle(classLoader: ClassLoader) {
        try {
            val viewClass = XposedHelpers.findClass("android.view.View", classLoader)

            XposedBridge.hookAllMethods(viewClass, "onAttachedToWindow", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (isPluginHooked) return
                    val view = param.thisObject as? View ?: return
                    val name = view.javaClass.name
                    if (name.contains("MiuiVolumeDialogView") || name.contains("MiuiRingerModeLayout")) {
                        val cl = view.javaClass.classLoader
                        if (cl != null) {
                            initPlugin(cl)
                            scheduleInsertion(view, "View#onAttachedToWindow ($name)")
                        }
                    }
                }
            })

            XposedBridge.hookAllMethods(viewClass, "onFinishInflate", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (isPluginHooked) return
                    val view = param.thisObject as? View ?: return
                    val name = view.javaClass.name
                    if (name.contains("MiuiVolumeDialogView") || name.contains("MiuiRingerModeLayout")) {
                        val cl = view.javaClass.classLoader
                        if (cl != null) {
                            initPlugin(cl)
                            scheduleInsertion(view, "View#onFinishInflate ($name)")
                        }
                    }
                }
            })

            XposedBridge.hookAllMethods(viewClass, "setVisibility", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (isPluginHooked) return
                    val view = param.thisObject as? View ?: return
                    val visibility = param.args.getOrNull(0) as? Int ?: return
                    if (visibility == View.VISIBLE && view.javaClass.name.contains("MiuiVolumeDialogView")) {
                        val cl = view.javaClass.classLoader
                        if (cl != null) {
                            initPlugin(cl)
                            scheduleInsertion(view, "View#setVisibility(VISIBLE)")
                            onVolumeShowH()
                        }
                    }
                }
            })

            MainHook.log("Installed View lifecycle hooks for volume detection")
        } catch (t: Throwable) {
            MainHook.log("Failed to hook View lifecycle", t)
        }
    }

    private fun extractComponentName(instance: Any): android.content.ComponentName? {
        return getFieldValueAny(instance, "componentName", "mComponentName") as? android.content.ComponentName
    }

    private fun extractPackageName(instance: Any): String? {
        val direct = getFieldValueAny(instance, "packageName", "mPackage") as? String
        if (!direct.isNullOrEmpty()) return direct
        val factory = getFieldValueAny(instance, "pluginFactory", "mPluginFactory")
        if (factory != null) {
            val appInfo = getFieldValueAny(factory, "pluginAppInfo", "mAppInfo") as? android.content.pm.ApplicationInfo
            if (appInfo != null) return appInfo.packageName
        }
        return null
    }

    private fun extractClassLoader(instance: Any): ClassLoader? {
        // 途径 1 (HyperOS 4): pluginData -> context / plugin (纯字段反射，绝不调用方法)
        val pluginData = getFieldValueAny(instance, "pluginData")
        if (pluginData != null) {
            val plugin = getFieldValueAny(pluginData, "plugin")
            if (plugin != null) {
                val cl = plugin.javaClass.classLoader
                if (cl != null) return cl
            }
            val context = getFieldValueAny(pluginData, "context")
            if (context is Context) {
                val cl = context.classLoader
                if (cl != null) return cl
            }
            if (context != null) {
                val cl = getFieldValueAny(context, "classLoader") as? ClassLoader
                if (cl != null) return cl
            }
        }

        // 途径 2 (HyperOS 3): mPlugin / plugin 直接字段 (严禁调用 getPlugin() 方法以绝递归)
        val plugin = getFieldValueAny(instance, "mPlugin", "plugin")
        if (plugin != null) {
            val cl = plugin.javaClass.classLoader
            if (cl != null) return cl
        }

        // 途径 3 (HyperOS 3): mPluginContext / pluginContext 直接字段
        val pluginContext = getFieldValueAny(instance, "mPluginContext", "pluginContext")
        if (pluginContext is Context) {
            val cl = pluginContext.classLoader
            if (cl != null) return cl
        }

        // 途径 4: pluginFactory / mPluginFactory
        val factory = getFieldValueAny(instance, "pluginFactory", "mPluginFactory")
        if (factory != null) {
            // HyperOS 4: pluginFactory.pluginAppInfo -> 查询 PluginInstanceInjector.sClassLoaders
            val appInfo = getFieldValueAny(factory, "pluginAppInfo", "mAppInfo") as? android.content.pm.ApplicationInfo
            val pkg = appInfo?.packageName ?: TARGET_PLUGIN_PACKAGE
            val hostContext = getFieldValueAny(factory, "hostContext") as? Context
            if (hostContext != null) {
                val cl = getClassLoaderFromInjector(hostContext.classLoader, pkg)
                if (cl != null) return cl
            }

            // HyperOS 3: mClassLoaderFactory (Supplier<ClassLoader>)
            val clFactory = getFieldValueAny(factory, "mClassLoaderFactory", "classLoaderFactory")
            if (clFactory is java.util.function.Supplier<*>) {
                try {
                    val cl = clFactory.get() as? ClassLoader
                    if (cl != null) return cl
                } catch (_: Throwable) {}
            }
        }

        // 途径 5: 兜底从 instance 自身的 ClassLoader 尝试获取 PluginInstanceInjector
        val injectorCl = getClassLoaderFromInjector(instance.javaClass.classLoader, TARGET_PLUGIN_PACKAGE)
        if (injectorCl != null) return injectorCl

        return null
    }

    private fun getClassLoaderFromInjector(classLoader: ClassLoader?, pkg: String): ClassLoader? {
        if (classLoader == null) return null
        return try {
            val injectorClass = XposedHelpers.findClassIfExists("com.miui.systemui.plugin.PluginInstanceInjector", classLoader)
                ?: return null
            val sClassLoadersField = injectorClass.getDeclaredField("sClassLoaders").apply { isAccessible = true }
            val map = sClassLoadersField.get(null) as? Map<*, *> ?: return null
            (map[pkg] ?: map[TARGET_PLUGIN_PACKAGE]) as? ClassLoader
        } catch (_: Throwable) {
            null
        }
    }

    private fun getFieldValueAny(target: Any, vararg candidateNames: String): Any? {
        for (name in candidateNames) {
            try {
                var clazz: Class<*>? = target.javaClass
                while (clazz != null && clazz != Any::class.java) {
                    try {
                        val field = clazz.getDeclaredField(name).apply { isAccessible = true }
                        return field.get(target)
                    } catch (_: NoSuchFieldException) {
                        clazz = clazz.superclass
                    }
                }
            } catch (_: Throwable) {}
        }
        return null
    }

    private fun hookVolumePlugin(cl: ClassLoader) {
        // 0. Hook VolumeDialogPlugin (HyperOS 3 & 4 插件核心类)
        // HyperOS 4 后期版本（如 16.03.251211.r）把音量插件内置进了 SystemUI，
        // miui.systemui.volume.VolumeDialogPlugin 不复存在；此时改用内置
        // VolumePanelViewController#initController 作为"音量核心加载"的等效锚点。
        try {
            val pluginClass = XposedHelpers.findClassIfExists("miui.systemui.volume.VolumeDialogPlugin", cl)
            if (pluginClass != null) {
                tracker.recordSuccess("sysui_volume_plugin", "音量核心插件 (VolumeDialogPlugin)", "miui.systemui.volume.VolumeDialogPlugin", "onCreated")
                XposedBridge.hookAllMethods(pluginClass, "onCreated", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        tracker.recordInvoke("sysui_volume_plugin")
                        MainHook.log("VolumeDialogPlugin#onCreated called in plugin")
                    }
                })
                MainHook.log("Hooked VolumeDialogPlugin successfully")
            } else {
                val builtInController = XposedHelpers.findClassIfExists(
                    "com.android.systemui.miui.volume.VolumePanelViewController", cl
                )
                if (builtInController != null) {
                    tracker.recordSuccess(
                        "sysui_volume_plugin",
                        "音量核心插件 (VolumeDialogPlugin)",
                        "com.android.systemui.miui.volume.VolumePanelViewController",
                        "initController",
                        "插件已内置进 SystemUI，改用内置控制器作为等效锚点"
                    )
                    XposedBridge.hookAllMethods(builtInController, "initController", object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            tracker.recordInvoke("sysui_volume_plugin")
                            MainHook.log("Built-in VolumePanelViewController#initController called")
                        }
                    })
                    MainHook.log("VolumeDialogPlugin absent (built-in SystemUI); hooked VolumePanelViewController#initController instead")
                } else {
                    tracker.recordNotFound("sysui_volume_plugin", "音量核心插件 (VolumeDialogPlugin)", "miui.systemui.volume.VolumeDialogPlugin", "onCreated")
                }
            }
        } catch (t: Throwable) {
            tracker.recordFailure("sysui_volume_plugin", "音量核心插件 (VolumeDialogPlugin)", "miui.systemui.volume.VolumeDialogPlugin", "onCreated", t)
            MainHook.log("Failed to hook VolumeDialogPlugin", t)
        }

        // 1. Hook MiuiVolumeDialogView
        try {
            val dialogViewClass = XposedHelpers.findClass("com.android.systemui.miui.volume.MiuiVolumeDialogView", cl)
            tracker.recordSuccess("sysui_dialog_view", "音量主视图 (MiuiVolumeDialogView)", "com.android.systemui.miui.volume.MiuiVolumeDialogView", "onFinishInflate/onAttachedToWindow")
            XposedBridge.hookAllMethods(dialogViewClass, "onFinishInflate", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    tracker.recordInvoke("sysui_dialog_view")
                    val view = param.thisObject as? View ?: return
                    scheduleInsertion(view, "MiuiVolumeDialogView#onFinishInflate")
                }
            })
            XposedBridge.hookAllMethods(dialogViewClass, "onAttachedToWindow", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    tracker.recordInvoke("sysui_dialog_view")
                    val view = param.thisObject as? View ?: return
                    scheduleInsertion(view, "MiuiVolumeDialogView#onAttachedToWindow")
                }
            })
            XposedBridge.hookAllMethods(dialogViewClass, "onExpandStateUpdated", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val expanded = param.args.getOrNull(0) as? Boolean ?: false
                    onExpandedChanged(expanded)
                }
            })
            // HyperOS 4 内置版本（16.03.251211.r）上 MiuiVolumeDialogView 没有
            // isExpanded() 可供事后校验，updateExpanded(boolean, boolean) 是官方
            // 展开态切换的真实入口；补挂它保证展开态跟踪在该版本可靠。
            XposedBridge.hookAllMethods(dialogViewClass, "updateExpanded", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val expanded = param.args.getOrNull(0) as? Boolean ?: return
                    onExpandedChanged(expanded)
                }
            })
            XposedBridge.hookAllMethods(dialogViewClass, "resetView", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    updateVisibility()
                }
            })
            XposedBridge.hookAllMethods(dialogViewClass, "dismissH", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    onExpandedChanged(false)
                }
            })
            XposedBridge.hookAllMethods(dialogViewClass, "updateFooterVisibility", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    updateVisibility()
                }
            })
            MainHook.log("Hooked MiuiVolumeDialogView successfully")
        } catch (t: Throwable) {
            tracker.recordFailure("sysui_dialog_view", "音量主视图 (MiuiVolumeDialogView)", "com.android.systemui.miui.volume.MiuiVolumeDialogView", "onFinishInflate", t)
            MainHook.log("Failed to hook MiuiVolumeDialogView", t)
        }

        // 2. Hook MiuiRingerModeLayout
        try {
            val ringerLayoutClass = XposedHelpers.findClass("com.android.systemui.miui.volume.MiuiRingerModeLayout", cl)
            tracker.recordSuccess("sysui_ringer_layout", "静音/勿扰布局 (MiuiRingerModeLayout)", "com.android.systemui.miui.volume.MiuiRingerModeLayout", "onFinishInflate/updateExpandedH")
            XposedBridge.hookAllMethods(ringerLayoutClass, "onFinishInflate", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    tracker.recordInvoke("sysui_ringer_layout")
                    val view = param.thisObject as? View ?: return
                    scheduleInsertion(view, "MiuiRingerModeLayout#onFinishInflate")
                }
            })
            XposedBridge.hookAllMethods(ringerLayoutClass, "onAttachedToWindow", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    tracker.recordInvoke("sysui_ringer_layout")
                    val view = param.thisObject as? View ?: return
                    scheduleInsertion(view, "MiuiRingerModeLayout#onAttachedToWindow")
                }
            })
            XposedBridge.hookAllMethods(ringerLayoutClass, "updateExpandedH", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    tracker.recordInvoke("sysui_ringer_layout")
                    val expanded = param.args.getOrNull(0) as? Boolean ?: false
                    onExpandedChanged(expanded)
                }
            })
            MainHook.log("Hooked MiuiRingerModeLayout successfully")
        } catch (t: Throwable) {
            tracker.recordFailure("sysui_ringer_layout", "静音/勿扰布局 (MiuiRingerModeLayout)", "com.android.systemui.miui.volume.MiuiRingerModeLayout", "onFinishInflate", t)
            MainHook.log("Failed to hook MiuiRingerModeLayout", t)
        }

        // 3. Hook VolumePanelViewController
        try {
            val controllerClass = XposedHelpers.findClass("com.android.systemui.miui.volume.VolumePanelViewController", cl)
            tracker.recordSuccess("sysui_panel_controller", "音量控制器 (VolumePanelViewController)", "com.android.systemui.miui.volume.VolumePanelViewController", "showH/showVolumePanelH/prepareShow")
            listOf("showH", "showVolumePanelH", "prepareShow").forEach { methodName ->
                try {
                    XposedBridge.hookAllMethods(controllerClass, methodName, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            tracker.recordInvoke("sysui_panel_controller")
                            val controller = param.thisObject ?: return
                            cachedController = WeakReference(controller)
                            MainHook.log("VolumePanelViewController#$methodName called")
                            onVolumeShowH()
                        }
                    })
                } catch (_: Throwable) {}
            }
            MainHook.log("Hooked VolumePanelViewController show methods successfully")
        } catch (t: Throwable) {
            tracker.recordFailure("sysui_panel_controller", "音量控制器 (VolumePanelViewController)", "com.android.systemui.miui.volume.VolumePanelViewController", "showH", t)
            MainHook.log("Failed to hook VolumePanelViewController", t)
        }

        // 4. Hook VolumeShowHideAnimator
        try {
            val animatorClass = XposedHelpers.findClass("com.android.systemui.miui.volume.VolumeShowHideAnimator", cl)
            tracker.recordSuccess("sysui_animator", "动画控制器 (VolumeShowHideAnimator)", "com.android.systemui.miui.volume.VolumeShowHideAnimator", "initView/startAnim")
            XposedBridge.hookAllMethods(animatorClass, "initView", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    tracker.recordInvoke("sysui_animator")
                    val volumeView = param.args.getOrNull(0) as? View ?: return
                    tryInsertEntry(volumeView, "VolumeShowHideAnimator#initView")
                    val animator = param.thisObject ?: return
                    cachedAnimatorRef = WeakReference(animator)
                }
            })
            XposedBridge.hookAllMethods(animatorClass, "startAnim", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val animator = param.thisObject ?: return
                    cachedAnimatorRef = WeakReference(animator)

                    val entry = cachedEntryView?.get() ?: return
                    if (entry.visibility != View.VISIBLE) return
                    val configs = param.args.getOrNull(0) ?: return
                    try {
                        val extended = VolumeEntryLayout.appendOfficialEntryAnimation(animator, configs, entry)
                        if (extended != null) {
                            param.args[0] = extended
                        }
                    } catch (t: Throwable) {
                        MainHook.log("Could not append app-volume entry to official show/hide animation", t)
                    }
                }
            })
            XposedBridge.hookAllMethods(animatorClass, "onAnimComplete", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    flushDeferredVisibility()
                }
            })
            XposedBridge.hookAllMethods(animatorClass, "cancel", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    flushDeferredVisibility()
                }
            })
            XposedBridge.hookAllMethods(animatorClass, "show", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    MainHook.log("VolumeShowHideAnimator#show called")
                }
            })
            MainHook.log("Hooked VolumeShowHideAnimator successfully")
        } catch (t: Throwable) {
            MainHook.log("Failed to hook VolumeShowHideAnimator", t)
        }

        // 5. 触底拉伸 / 拖拽拉伸 / 面板按压压缩（SlideContainerAnim）。
        //    官方通过 AnimListener.setRingerY/setDndY 每帧驱动 ringer/dnd 按钮的 translationY。
        //    这里监听 setDndY / setScale / resetView，使入口与 dnd 按钮几何同步并在归位时精准复位。
        try {
            val slideAnimClass = XposedHelpers.findClassIfExists(
                "com.android.systemui.miui.volume.SlideContainerAnim",
                cl
            )
            if (slideAnimClass != null) {
                tracker.recordSuccess("sysui_slide_anim", "触底/拖拽拉伸 (SlideContainerAnim)", "SlideContainerAnim", "AnimListener.setDndY")
                XposedBridge.hookAllConstructors(slideAnimClass, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        cacheSlideAnimInstance(param.thisObject)
                    }
                })
                for (name in listOf(
                    "animKeyDown", "animKeyUp", "animDragMove", "animDragUp",
                    "animPressDownTo", "animPressUpTo", "animDownSetTo"
                )) {
                    XposedBridge.hookAllMethods(slideAnimClass, name, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            cacheSlideAnimInstance(param.thisObject)
                        }
                    })
                }
                XposedBridge.hookAllMethods(slideAnimClass, "initView", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        resetSlideTransformation()
                    }
                })
                MainHook.log("Hooked SlideContainerAnim successfully")
            } else {
                tracker.recordNotFound("sysui_slide_anim", "触底/拖拽拉伸 (SlideContainerAnim)", "SlideContainerAnim", "AnimListener.setDndY")
            }
        } catch (t: Throwable) {
            MainHook.log("Failed to hook SlideContainerAnim for app-volume entry", t)
        }
    }

    /**
     * 缓存 SlideContainerAnim 实例并补挂其 AnimListener 钩子（幂等）。
     */
    private fun cacheSlideAnimInstance(instance: Any?) {
        if (instance == null) return
        cachedSlideAnim = WeakReference(instance)
        val listener = runCatching {
            XposedHelpers.getObjectField(instance, "mAnimListener")
        }.getOrNull() ?: return
        hookSlideAnimListener(listener.javaClass)
    }

    private fun hookSlideAnimListener(clazz: Class<*>) {
        synchronized(this) {
            if (slideAnimListenerHookedClass == clazz.name) return
            slideAnimListenerHookedClass = clazz.name
        }
        try {
            val dndHooks = XposedBridge.hookAllMethods(clazz, "setDndY", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val value = (param.args.getOrNull(1) as? Number)?.toFloat()
                    applyEntrySlideOffset(value)
                }
            })
            val scaleHooks = XposedBridge.hookAllMethods(clazz, "setScale", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    (param.args.getOrNull(1) as? Number)?.toFloat()?.let {
                        latestSlideScale = it
                        slideScaleSeen = true
                    }
                    applyEntrySlideOffset(null)
                }
            })
            XposedBridge.hookAllMethods(clazz, "resetView", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    resetSlideTransformation()
                }
            })
            MainHook.log(
                "Hooked SlideContainerAnim.AnimListener (${clazz.name}): " +
                    "setDndY=${dndHooks.size}, setScale=${scaleHooks.size}"
            )
        } catch (t: Throwable) {
            MainHook.log("Could not hook SlideContainerAnim.AnimListener ${clazz.name}", t)
        }
    }

    private fun resetSlideTransformation() {
        latestSlideScale = 1f
        slideScaleSeen = false
        val entry = cachedEntryView?.get() ?: return
        entry.translationY = 0f
        entry.scaleX = 1f
        entry.scaleY = 1f
        blurViewOf(entry)?.let {
            it.scaleX = 1f
            it.scaleY = 1f
        }
    }

    /**
     * 官方 setDndY 应用后，把入口的 translationY 跟随 dnd 按钮同步偏移。
     * 入口相对 dnd 的额外位移 = (dnd高/2 + 间隔 + 入口高/2) * (1 - scale)。
     * 当处于静止状态时自动重置为零，确保按键松开后回弹完全到位。
     */
    private fun applyEntrySlideOffset(dndValue: Float? = null) {
        val entry = cachedEntryView?.get() ?: return
        if (entry.visibility != View.VISIBLE) return
        val dnd = cachedDndView?.get() ?: return
        val spacer = cachedDividerView?.get()
        if (dnd.height == 0 || entry.height == 0) return

        val dndTranslation = dndValue ?: dnd.translationY
        val scale = when {
            dnd.scaleX != 1f -> dnd.scaleX
            slideScaleSeen -> latestSlideScale
            else -> 1f
        }

        if (dndTranslation == 0f && scale == 1f) {
            resetSlideTransformation()
            return
        }

        val gapHeight = spacer?.let { if (it.height > 0) it.height else (it.layoutParams?.height ?: 0) } ?: 0
        val extraDistance = dnd.height / 2f + gapHeight + entry.height / 2f

        entry.scaleX = dnd.scaleX
        entry.scaleY = dnd.scaleY
        entry.translationY = dndTranslation + extraDistance * (1f - scale)

        val dndBlur = blurViewOf(dnd)
        val entryBlur = blurViewOf(entry)
        if (dndBlur != null && entryBlur != null) {
            entryBlur.scaleX = dndBlur.scaleX
            entryBlur.scaleY = dndBlur.scaleY
        }
    }

    private var cachedBlurViewId = 0

    private fun blurViewOf(button: View): View? {
        if (cachedBlurViewId == 0) {
            for (pkg in listOf(button.context.packageName, TARGET_PLUGIN_PACKAGE, MainHook.PKG_PLUGIN).distinct()) {
                val id = button.resources.getIdentifier("bg_blur", "id", pkg)
                if (id != 0) {
                    cachedBlurViewId = id
                    break
                }
            }
        }
        return if (cachedBlurViewId != 0) button.findViewById(cachedBlurViewId) else null
    }

    private fun flushDeferredVisibility() {
        if (!visibilityUpdateDeferred) return
        visibilityUpdateDeferred = false
        deferredVisibilityRetries = 0
        mainHandler.post {
            updateVisibility()
        }
    }

    private fun getFieldAny(clazz: Class<*>, vararg names: String): Field? {
        var c: Class<*>? = clazz
        while (c != null && c != Any::class.java) {
            for (name in names) {
                try {
                    return c.getDeclaredField(name).apply { isAccessible = true }
                } catch (_: NoSuchFieldException) {}
            }
            c = c.superclass
        }
        return null
    }

    private fun scheduleInsertion(view: View, trigger: String) {
        mainHandler.post {
            tryInsertEntry(view, trigger)
        }
    }

    private fun tryInsertEntry(targetView: View, trigger: String) {
        val cl = pluginClassLoader ?: targetView.javaClass.classLoader ?: return

        var ringerLayout: View? = null
        var dialogView: ViewGroup? = null

        if (targetView.javaClass.name.contains("MiuiRingerModeLayout")) {
            ringerLayout = targetView
            var p: ViewParent? = targetView.parent
            while (p != null) {
                if (p.javaClass.name.contains("MiuiVolumeDialogView")) {
                    dialogView = p as? ViewGroup
                    break
                }
                p = p.parent
            }
        } else if (targetView.javaClass.name.contains("MiuiVolumeDialogView")) {
            dialogView = targetView as? ViewGroup
            // 方式 A: 调用 getRingerModeLayout()
            try {
                val getMethod = targetView.javaClass.getMethod("getRingerModeLayout")
                ringerLayout = getMethod.invoke(targetView) as? View
            } catch (_: Throwable) {}

            // 方式 B: 按资源 ID 查找
            if (ringerLayout == null && dialogView != null) {
                val ringerId = dialogView.resources.getIdentifier("miui_volume_ringer_layout", "id", dialogView.context.packageName)
                if (ringerId != 0) {
                    ringerLayout = dialogView.findViewById(ringerId)
                }
            }

            // 方式 C: 递归子 View 类名匹配
            if (ringerLayout == null && dialogView != null) {
                ringerLayout = findChildByName(dialogView, "MiuiRingerModeLayout")
            }
        }

        if (ringerLayout == null) {
            MainHook.log("[$trigger] tryInsertEntry skipped: ringerLayout could not be found on ${targetView.javaClass.name}")
            return
        }

        // miui_volume_dialog_ringer_mode.xml 的真实层级是：
        // MiuiRingerModeLayout -> FrameLayout -> LinearLayout(ringer_layout, spacer, dnd_layout)。
        // 把新按钮插到 dnd_layout 同级，才能继承官方两个胶囊按钮所在的
        // LinearLayout、LayoutTransition 和间距；插到 MiuiRingerModeLayout 外层
        // 会绕过官方的交替/回弹布局动画。
        val dndButton = findViewByResourceName(ringerLayout, "dnd_layout")
        val buttonParent = dndButton?.parent as? ViewGroup
        val parentGroup = buttonParent ?: (ringerLayout.parent as? ViewGroup)
        val anchorView = dndButton ?: ringerLayout
        if (parentGroup == null) {
            MainHook.log("[$trigger] tryInsertEntry skipped: official button parent is null")
            return
        }
        if (dndButton == null) {
            MainHook.log("[$trigger] dnd_layout not found; using ringerLayout parent as compatibility fallback")
        }

        // 检查是否已经注入过
        val existingEntry = ringerLayout.findViewWithTag<View>(VolumeEntryLayout.TAG_ENTRY_ROOT)
            ?: parentGroup.findViewWithTag<View>(VolumeEntryLayout.TAG_ENTRY_ROOT)
            ?: dialogView?.findViewWithTag<View>(VolumeEntryLayout.TAG_ENTRY_ROOT)
        if (existingEntry != null) {
            cachedEntryView = WeakReference(existingEntry)
            val existingSpacer = ringerLayout.findViewWithTag<View>(VolumeEntryLayout.TAG_ENTRY_SPACER)
                ?: parentGroup.findViewWithTag<View>(VolumeEntryLayout.TAG_ENTRY_SPACER)
                ?: dialogView?.findViewWithTag<View>(VolumeEntryLayout.TAG_ENTRY_SPACER)
            if (existingSpacer != null) {
                cachedDividerView = WeakReference(existingSpacer)
                val collapsedHeight = resolveDimen(parentGroup.context, "miui_volume_footer_margin_top")
                val dividerHeight = if (collapsedHeight > 0) collapsedHeight else (10 * parentGroup.context.resources.displayMetrics.density).toInt()
                val lp = existingSpacer.layoutParams
                if (lp != null && lp.height != dividerHeight) {
                    lp.height = dividerHeight
                    existingSpacer.layoutParams = lp
                }
            }
            updateVisibility()
            return
        }

        val anchorIndex = parentGroup.indexOfChild(anchorView)
        if (anchorIndex < 0) {
            MainHook.log("[$trigger] tryInsertEntry skipped: anchor index in parent is $anchorIndex")
            return
        }

        var insertIndex = anchorIndex + 1
        val context = parentGroup.context
        // 官方 include 自己带有 ringer/dnd 胶囊所需的 layout_width、layout_height。
        val layoutParams = copyLayoutParams(anchorView.layoutParams)

        // 官方两个按钮之间不是依靠 margin，而是使用 miui_volume_ringer_divider。
        // 在折叠态下，官方 ViewStateGroup 会将其高度应用为 miui_volume_footer_margin_top (10dp)。
        // 我们直接设置 spacer 的高度为 10dp，完全等同于官方两按钮之间的实际折叠间距。
        if (dndButton != null && buttonParent != null) {
            val collapsedHeight = resolveDimen(context, "miui_volume_footer_margin_top")
            val dividerHeight = if (collapsedHeight > 0) collapsedHeight else (10 * context.resources.displayMetrics.density).toInt()
            val divider = View(context).apply {
                tag = VolumeEntryLayout.TAG_ENTRY_SPACER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                isClickable = false
                isFocusable = false
            }
            val dividerLp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dividerHeight
            )
            buttonParent.addView(
                divider,
                insertIndex,
                dividerLp
            )
            cachedDividerView = WeakReference(divider)
            insertIndex++
        }

        val entryView = VolumeEntryLayout.createEntryView(
            context = context,
            pluginClassLoader = cl,
            onDismissRequest = { dismissVolumeDialog() },
            officialRingerLayout = ringerLayout
        )

        parentGroup.addView(entryView, insertIndex, layoutParams)
        cachedEntryView = WeakReference(entryView)
        // 动画器可能已初始化（initView 先于本插入），立即把入口注册为第三颗按钮。
        cachedAnimatorRef?.get()?.let { appendEntryToRingerButtons(it) }
        val officialDnd = dndButton
        if (officialDnd != null) {
            cachedDndView = WeakReference(officialDnd)
            VolumeEntryLayout.matchAnimationBaseline(entryView, officialDnd)
        }
        VolumeEntryLayout.updateExpanded(entryView, isExpanded)
        tracker.attachContext(parentGroup.context)
        tracker.recordSuccess(
            "sysui_entry_injection",
            "音量增强入口 (VolumeEntryLayout)",
            "VolumeEntryLayout",
            "addView",
            "已注入父视图: ${parentGroup.javaClass.simpleName}"
        )
        MainHook.log("[$trigger] Successfully inserted official-clone volume entry after ${anchorView.javaClass.name} at index $insertIndex into ${parentGroup.javaClass.name}")

        updateVisibility()
    }

    /**
     * 把入口追加进官方 VolumeShowHideAnimator 的 mRingerBtnLayouts /
     * ringerBtnLayoutsX，使官方 expanded()、setViewX() 和 scale 回调把它当作
     * 第三颗官方按钮处理：飞入/飞出配置由官方自动生成，触底拉伸（scale 弹簧
     * 过冲的 translationY 补偿）与官方两颗按钮同源；点按缩小由按钮模板上的
     * RingerButtonHelper Folme touch 提供。
     *
     * initView 每次都会把这两个数组重建为 [ringer, dnd]，因此本方法必须幂等，
     * 且在每次 initView 之后都要重新调用。两个数组必须同时扩容，否则官方
     * setViewX 会按 mRingerBtnLayouts.length 去索引旧的 ringerBtnLayoutsX。
     */
    private fun appendEntryToRingerButtons(animator: Any) {
        val entry = cachedEntryView?.get() ?: return
        try {
            val buttons = XposedHelpers.getObjectField(animator, "mRingerBtnLayouts")
                as? Array<Any?> ?: return
            if (buttons.any { it === entry }) return
            val xs = XposedHelpers.getObjectField(animator, "ringerBtnLayoutsX")
                as? Array<Float> ?: return
            if (xs.size != buttons.size) {
                MainHook.log("Skip ringer button registration: array sizes mismatch (${buttons.size}/${xs.size})")
                return
            }
            val componentType = buttons.javaClass.componentType ?: return
            val newButtons = ReflectArray.newInstance(componentType, buttons.size + 1)
            for (index in buttons.indices) {
                ReflectArray.set(newButtons, index, buttons[index])
            }
            ReflectArray.set(newButtons, buttons.size, entry)
            val newXs = ReflectArray.newInstance(Float::class.javaObjectType, xs.size + 1) as Array<Float>
            for (index in xs.indices) {
                newXs[index] = xs[index]
            }
            newXs[xs.size] = 0f
            XposedHelpers.setObjectField(animator, "mRingerBtnLayouts", newButtons)
            XposedHelpers.setObjectField(animator, "ringerBtnLayoutsX", newXs)
            MainHook.log("App-volume entry registered as official ringer button #${buttons.size}")
        } catch (t: Throwable) {
            MainHook.log("Could not register app-volume entry into mRingerBtnLayouts", t)
        }
    }

    /** 视图中心在窗口坐标里的 Y，只累加 layout top，不含 translation，避免被动画中的位移污染。 */
    private fun untranslatedCenterY(view: View): Int {
        var y = 0
        var current: Any? = view
        while (current is View) {
            y += current.top
            current = current.parent
        }
        return y + view.height / 2
    }

    private fun findViewByResourceName(root: View, resourceName: String): View? {
        val packages = listOf(root.context.packageName, TARGET_PLUGIN_PACKAGE, MainHook.PKG_PLUGIN)
        for (packageName in packages.distinct()) {
            try {
                val id = root.resources.getIdentifier(resourceName, "id", packageName)
                if (id != 0) {
                    val found = root.findViewById<View>(id)
                    if (found != null) return found
                }
            } catch (_: Throwable) {}
        }
        return null
    }

    private fun copyLayoutParams(source: ViewGroup.LayoutParams?): ViewGroup.LayoutParams {
        if (source == null) {
            return ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        return when (source) {
            is LinearLayout.LayoutParams -> LinearLayout.LayoutParams(source.width, source.height).apply {
                weight = source.weight
                gravity = source.gravity
                leftMargin = source.leftMargin
                topMargin = source.topMargin
                rightMargin = source.rightMargin
                bottomMargin = source.bottomMargin
            }
            is FrameLayout.LayoutParams -> FrameLayout.LayoutParams(source.width, source.height).apply {
                gravity = source.gravity
                leftMargin = source.leftMargin
                topMargin = source.topMargin
                rightMargin = source.rightMargin
                bottomMargin = source.bottomMargin
            }
            is ViewGroup.MarginLayoutParams -> ViewGroup.MarginLayoutParams(source.width, source.height).apply {
                leftMargin = source.leftMargin
                topMargin = source.topMargin
                rightMargin = source.rightMargin
                bottomMargin = source.bottomMargin
            }
            else -> ViewGroup.LayoutParams(source.width, source.height)
        }
    }

    private fun findChildByName(parent: ViewGroup, classNameKeyword: String): View? {
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (child.javaClass.name.contains(classNameKeyword)) {
                return child
            }
            if (child is ViewGroup) {
                val found = findChildByName(child, classNameKeyword)
                if (found != null) return found
            }
        }
        return null
    }

    private fun onExpandedChanged(expanded: Boolean) {
        isExpanded = expanded
        cachedEntryView?.get()?.let { VolumeEntryLayout.updateExpanded(it, expanded) }
        MainHook.log("onExpandedChanged: forwarded official expanded state=$expanded")
        updateVisibility()
    }

    private fun onVolumeShowH() {
        // 必须同步执行：showH/showVolumePanelH 都在官方入场动画（OnPreDraw -> startAnim）之前调用，
        // 在这里同步应用入口可见性能保证动画开始时 footer 高度已经定型；
        // 若 post 到主线程，回调会落在动画进行中，一旦切换可见性就会打断动画。
        updateVisibility()
    }

    private fun isShowHideAnimRunning(): Boolean {
        val animator = cachedAnimatorRef?.get() ?: return false
        return try {
            XposedHelpers.getBooleanField(animator, "mIsAnimating")
        } catch (_: Throwable) {
            false
        }
    }

    private fun scheduleDeferredVisibilityUpdate() {
        if (visibilityUpdateDeferred) return
        visibilityUpdateDeferred = true
        mainHandler.postDelayed({
            visibilityUpdateDeferred = false
            if (isShowHideAnimRunning() && deferredVisibilityRetries < VISIBILITY_MAX_RETRIES) {
                deferredVisibilityRetries++
                scheduleDeferredVisibilityUpdate()
            } else {
                deferredVisibilityRetries = 0
                updateVisibility()
            }
        }, VISIBILITY_RETRY_DELAY_MS)
    }

    private fun updateVisibility() {
        val entryView = cachedEntryView?.get() ?: return
        val divider = cachedDividerView?.get()

        // 官方 show/hide 动画进行中严禁改动 footer 布局：入口/间隔条可见性变化会让
        // ringer 区高度中途变化，入场动画被打断，看起来像"播一点再从头重播"。
        // 推迟到动画结束（onAnimComplete/cancel 或重试超时）后再应用，最终状态不变。
        if (isShowHideAnimRunning()) {
            MainHook.log("updateVisibility: official show/hide animation running -> deferred")
            scheduleDeferredVisibilityUpdate()
            return
        }
        visibilityUpdateDeferred = false
        val context = entryView.context
        ensurePlaybackCallbackRegistered(context)

        // 动态校验真实展开状态（避免在收起或多次切屏时丢失状态）
        var realExpanded = isExpanded
        var dynamicExpandedRead = false
        var p = entryView.parent
        while (p != null) {
            if (p.javaClass.name.contains("MiuiVolumeDialogView")) {
                try {
                    val isExp = p.javaClass.getMethod("isExpanded").invoke(p) as? Boolean
                    if (isExp != null) {
                        realExpanded = isExp
                        isExpanded = isExp
                        dynamicExpandedRead = true
                    }
                } catch (_: Throwable) {}
                break
            }
            p = p.parent
        }
        // HyperOS 4 内置版本（16.03.251211.r）的 MiuiVolumeDialogView 没有
        // isExpanded()；此时改读官方 VolumeShowHideAnimator.mExpanded 兜底，
        // 避免展开态判定退化为仅依赖钩子跟踪的缓存值。
        if (!dynamicExpandedRead) {
            cachedAnimatorRef?.get()?.let { animator ->
                runCatching {
                    val expanded = XposedHelpers.getBooleanField(animator, "mExpanded")
                    realExpanded = expanded
                    isExpanded = expanded
                }
            }
        }

        // 展开态入口不参与音量条上方的官方静音/勿扰按钮动画，直接隐藏；
        // 且对应的 divider 间隔也要同步隐藏，避免在下方留下空白
        if (realExpanded) {
            MainHook.log("updateVisibility: expanded=true -> GONE")
            divider?.visibility = View.GONE
            entryView.visibility = View.GONE
            return
        }

        val hasActivePlayback = ActiveAudioDetector.hasActiveMediaPlayback(context)
        tracker.attachContext(context)
        tracker.recordSuccess(
            "sysui_audio_detector",
            "活跃音频检测 (ActiveAudioDetector)",
            "ActiveAudioDetector",
            "hasActiveMediaPlayback",
            "当前活跃媒体播放: $hasActivePlayback"
        )
        tracker.recordInvoke("sysui_audio_detector")
        val targetVis = if (hasActivePlayback) View.VISIBLE else View.GONE
        MainHook.log("updateVisibility: expanded=false, hasActivePlayback=$hasActivePlayback -> ${if (hasActivePlayback) "VISIBLE" else "GONE"}")
        divider?.visibility = targetVis
        entryView.visibility = targetVis
    }

    /**
     * 注册系统播放状态回调：现有 updateVisibility 触发链（showH/footer/expand/
     * dismiss）在“面板已显示、音频才开始播放”的场景没有任何触发点，导致入口
     * 慢半拍；播放配置一变化立刻补一次可见性判定即可闭合该缺口。
     */
    private fun ensurePlaybackCallbackRegistered(context: Context) {
        if (playbackCallbackRegistered) return
        playbackCallbackRegistered = true
        runCatching {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            val handler = Handler(Looper.getMainLooper())
            audioManager?.registerAudioPlaybackCallback(
                object : android.media.AudioManager.AudioPlaybackCallback() {
                    override fun onPlaybackConfigChanged(configs: MutableList<android.media.AudioPlaybackConfiguration>) {
                        mainHandler.post {
                            if (cachedEntryView?.get() != null) {
                                updateVisibility()
                            }
                        }
                    }
                },
                handler
            )
            MainHook.log("Registered AudioPlaybackCallback for entry visibility")
        }.onFailure {
            playbackCallbackRegistered = false
            MainHook.log("Could not register AudioPlaybackCallback", it)
        }
    }

    private fun resolveDimen(context: Context, resName: String): Int {
        val packages = listOf(context.packageName, TARGET_PLUGIN_PACKAGE, MainHook.PKG_PLUGIN).distinct()
        for (pkg in packages) {
            try {
                val id = context.resources.getIdentifier(resName, "dimen", pkg)
                if (id != 0) {
                    return context.resources.getDimensionPixelSize(id)
                }
            } catch (_: Throwable) {}
        }
        return 0
    }

    private fun dismissVolumeDialog() {
        val controller = cachedController?.get()
        if (controller != null) {
            try {
                val dismissMethod = controller.javaClass.getDeclaredMethod("dismissH", Int::class.javaPrimitiveType)
                dismissMethod.isAccessible = true
                dismissMethod.invoke(controller, 8)
                MainHook.log("Dismissed volume dialog via controller.dismissH(8)")
                return
            } catch (t: Throwable) {
                MainHook.log("dismissH(8) on controller failed", t)
            }
        }

        val entryView = cachedEntryView?.get()
        var p: ViewParent? = entryView?.parent
        while (p != null) {
            if (p.javaClass.name.contains("MiuiVolumeDialogView")) {
                try {
                    val dismissMethod = p.javaClass.getDeclaredMethod("dismissH", Boolean::class.javaPrimitiveType, Runnable::class.java)
                    dismissMethod.isAccessible = true
                    dismissMethod.invoke(p, true, null)
                    MainHook.log("Dismissed volume dialog via dialogView.dismissH(true, null)")
                    return
                } catch (t: Throwable) {
                    MainHook.log("dismissH(true, null) on dialogView failed", t)
                }
            }
            p = p.parent
        }
    }
}
