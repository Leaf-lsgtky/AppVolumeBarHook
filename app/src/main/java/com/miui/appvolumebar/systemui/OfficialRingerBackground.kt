package com.miui.appvolumebar.systemui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import com.miui.appvolumebar.MainHook
import java.lang.reflect.Method

/**
 * Reuses the ringer button material from the loaded SystemUI plugin.
 *
 * HyperOS 4 has two material paths.  The new path is copied from
 * RingerButtonHelper.applyCollapsedStyle(): it uses the volume-dialog
 * background token as the base, the ringer token as the progress/foreground,
 * and both closed/open glass tokens.  The old path uses the plugin drawable.
 */
internal object OfficialRingerBackground {

    private val backgroundNames = listOf(
        "o3_miui_volume_ringer_btn_first_bg_blur",
        "o3_miui_volume_ringer_btn_first_bg_collapsed",
        "o3_miui_volume_ringer_btn_first_bg",
        "o3_miui_volume_ringer_btn_first_bg_cc"
    )

    private val blurBackgroundNames = listOf(
        "o3_miui_volume_ringer_bg_blur",
        "o3_miui_volume_ringer_bg_blur_cc"
    )

    private val resourcePackages = listOf(
        "miui.systemui.plugin",
        "com.android.systemui"
    )

    /**
     * 创建官方 RingerButtonHelper 使用的 bg_blur 层。
     *
     * JADX 中官方 helper 并不是只给 miui_standard_btn 设置背景，而是同时创建
     * AbstractC9052a(bg_blur)，设置官方 blur drawable，并在折叠态打开 blur。
     * 展开按钮如果没有这一层，即使前景 material 反射调用成功，视觉上仍会像“无背景”。
     */
    fun createBlurLayer(
        context: Context,
        pluginClassLoader: ClassLoader,
        radius: Float
    ): View? {
        val blurDrawable = resolveBackground(context, pluginClassLoader, blurBackgroundNames)
        val blurCandidates = listOf(
            "com.android.systemui.miui.volume.widget.VolumeBlurFrameLayout",
            "com.android.systemui.miui.volume.widget.ExpandBlurFrameLayout",
            "com.miui.blur.sdk.backdrop.a"
        )

        for (blurClassName in blurCandidates) {
            val blurClass = loadClass(blurClassName, pluginClassLoader) ?: continue
            if (java.lang.reflect.Modifier.isAbstract(blurClass.modifiers)) {
                continue
            }
            try {
                val layer = blurClass.getConstructor(Context::class.java)
                    .newInstance(context) as? View
                if (layer != null) {
                    invoke(blurClass, layer, "setBlurEnabled", true)
                    invoke(blurClass, layer, "setCornerRadius", radius)
                    if (blurDrawable != null) {
                        layer.background = blurDrawable.mutate()
                    }
                    MainHook.log("Created official ringer bg_blur layer ($blurClassName): ${layer.javaClass.name}")
                    return layer
                }
            } catch (t: Throwable) {
                MainHook.log("Could not construct official ringer bg_blur layer ($blurClassName)", t)
            }
        }

        // 即使 backdrop SDK 被裁剪，也保留官方静态底图作为可见回退。
        if (blurDrawable != null) {
            return View(context).apply {
                background = blurDrawable.mutate()
            }
        }
        return View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(55, 255, 255, 255))
                cornerRadius = radius
            }
        }
    }

    fun apply(
        context: Context,
        view: View,
        pluginClassLoader: ClassLoader,
        radius: Float
    ) {
        val utilClass = loadClass("com.android.systemui.miui.volume.Util", pluginClassLoader)
        val blendToken = resolveRingerBlendToken(context, pluginClassLoader, utilClass)
        var liveMaterialApplied = false
        var staticBackgroundResolved = false
        val advanced = if (utilClass != null) {
            invoke(utilClass, receiverOf(utilClass), "isAdvancedMaterialEffective", context)
                .value as? Boolean
        } else {
            null
        }

        // HyperOS 4 has MiBackgroundStyle (glass material); HyperOS 3 does not.
        val backgroundStyle = loadClass("miui.systemui.util.MiBackgroundStyle", pluginClassLoader)
        if (backgroundStyle != null && utilClass != null && blendToken != null && advanced != false) {
            liveMaterialApplied = applyNewMaterial(
                view,
                radius,
                blendToken,
                resolvePanelBackgroundToken(pluginClassLoader),
                utilClass,
                pluginClassLoader
            )
            if (liveMaterialApplied) {
                // 官方 applyCollapsedStyle() 先清空 standard button 的静态背景；
                // 真正可见的 backdrop 由 createBlurLayer() 提供。
                view.background = null
                MainHook.log("Applied official ringer collapsed progress material (glass)")
            }
        }

        // HyperOS 3 or fallback on HyperOS 4:
        // HyperOS 3 has no glass tokens; it preserves static drawable on view.background
        // and applies legacy Util.setRoundRect + Util.setMiViewBlurAndBlendColor.
        if (!liveMaterialApplied) {
            val staticBackground = resolveBackground(context, pluginClassLoader)
            if (staticBackground != null) {
                staticBackgroundResolved = true
                view.background = staticBackground.mutate()
                MainHook.log("Applied official collapsed ringer background drawable")
            }
            if (utilClass != null && blendToken != null) {
                liveMaterialApplied = applyLegacyMaterial(view, radius, blendToken, utilClass)
            }
        }

        // Keep a deterministic visible fallback even if the plugin changes its
        // private material implementation or the resource table is stripped.
        if (!liveMaterialApplied && view.background == null) {
            view.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(55, 255, 255, 255))
                cornerRadius = radius
            }
            MainHook.log("Applied fallback translucent ringer background")
        }

        MainHook.log("Ringer background ready: static=$staticBackgroundResolved, live=$liveMaterialApplied")
    }

    private fun resolveBackground(
        context: Context,
        classLoader: ClassLoader,
        names: List<String> = backgroundNames
    ): Drawable? {
        val packages = buildList {
            addAll(resourcePackages)
            add(context.packageName)
        }.distinct()

        val resourceContexts = buildList {
            add(context)
            for (packageName in packages) {
                try {
                    add(context.createPackageContext(packageName, Context.CONTEXT_IGNORE_SECURITY))
                } catch (_: Throwable) {
                    // The package may be a resource namespace rather than an installed package.
                }
            }
        }.distinctBy { it.packageName }

        for (resourceContext in resourceContexts) {
            for (packageName in packages) {
                for (name in names) {
                    try {
                        val id = resourceContext.resources.getIdentifier(name, "drawable", packageName)
                        if (id != 0) {
                            return resourceContext.resources.getDrawable(id, resourceContext.theme)
                        }
                    } catch (_: Throwable) {
                        // Try the next resource context/name variant.
                    }
                }
            }
        }
        return null
    }

    private fun resolvePanelBackgroundToken(classLoader: ClassLoader): Any? {
        val resClass = loadClass("com.android.systemui.miui.volume.MiuiVolumeDialogRes", classLoader)
            ?: return null
        val result = invoke(
            resClass,
            receiverOf(resClass),
            "getBgBlandColor",
            true
        )
        return result.value.takeIf { result.success }
    }

    private fun resolveRingerBlendToken(
        context: Context,
        classLoader: ClassLoader,
        utilClass: Class<*>?
    ): Any? {
        val ringerButtonRes = loadClass("com.android.systemui.miui.volume.RingerButtonRes", classLoader)
        if (ringerButtonRes != null) {
            val bionics = invoke(
                utilClass,
                utilClass?.let(::receiverOf),
                "isBionicsAdvancedMaterialEnabled",
                context
            ).value as? Boolean ?: false

            val receiver = receiverOf(ringerButtonRes)
            val method4 = allMethods(ringerButtonRes)
                .firstOrNull { it.name == "getButtonBgBlendColor" && it.parameterTypes.size == 4 }
            if (method4 != null) {
                try {
                    method4.isAccessible = true
                    val token = method4.invoke(receiver, false, true, false, bionics)
                    if (token != null) return token
                } catch (_: Throwable) {
                }
            }

            val method3 = allMethods(ringerButtonRes)
                .firstOrNull { it.name == "getButtonBgBlendColor" && it.parameterTypes.size == 3 }
            if (method3 != null) {
                try {
                    method3.isAccessible = true
                    val token = method3.invoke(receiver, false, true, false)
                    if (token != null) return token
                } catch (_: Throwable) {
                }
            }
        }

        for (tokenClassName in listOf(
            "miui.systemui.util.MiuiColorBlendToken",
            "miuix.theme.token.MiuiColorBlendToken"
        )) {
            val tokenClass = loadClass(tokenClassName, classLoader)
            if (tokenClass != null) {
                val receiver = receiverOf(tokenClass)
                val result = invoke(tokenClass, receiver, "getRINGER_BG_OFF")
                if (result.success && result.value != null) return result.value
            }
        }
        return null
    }

    private fun applyNewMaterial(
        view: View,
        radius: Float,
        blendToken: Any,
        panelBackgroundToken: Any?,
        utilClass: Class<*>,
        classLoader: ClassLoader
    ): Boolean {
        val blurCompat = loadClass("miui.systemui.util.MiBlurCompat", classLoader) ?: return false
        val outlined = invoke(blurCompat, receiverOf(blurCompat), "setBlurOutlineRoundRect", view, radius).success ||
            invoke(blurCompat, receiverOf(blurCompat), "setOutlineRoundRect", view, radius, true).success
        if (!outlined) return false

        val backgroundStyle = loadClass("miui.systemui.util.MiBackgroundStyle", classLoader) ?: return false
        val styleReceiver = receiverOf(backgroundStyle)
        val closedGlassResult = invoke(
            backgroundStyle,
            styleReceiver,
            "getVOLUMPANEL_COLLAPSED_CLOSED_GLASS_TOKEN"
        )
        val openGlassResult = invoke(
            backgroundStyle,
            styleReceiver,
            "getVOLUMPANEL_COLLAPSED_OPEN_GLASS_TOKEN"
        )
        if (!closedGlassResult.success || closedGlassResult.value == null) return false

        // This is the exact four-token path used by the stock ringer button.
        if (panelBackgroundToken != null && openGlassResult.success && openGlassResult.value != null) {
            val progressResult = invoke(
                utilClass,
                receiverOf(utilClass),
                "setMiViewBackgroundStyleWithProgress",
                view,
                1,
                panelBackgroundToken,
                blendToken,
                0.0f,
                closedGlassResult.value,
                openGlassResult.value
            )
            if (progressResult.success) {
                return true
            }
        }

        val simpleResult = invoke(
            utilClass,
            receiverOf(utilClass),
            "setMiViewBackgroundStyle",
            view,
            1,
            blendToken,
            closedGlassResult.value
        )
        return simpleResult.success
    }

    private fun applyLegacyMaterial(
        view: View,
        radius: Float,
        blendToken: Any,
        utilClass: Class<*>
    ): Boolean {
        invoke(utilClass, receiverOf(utilClass), "setRoundRect", view, radius)
        return invoke(
            utilClass,
            receiverOf(utilClass),
            "setMiViewBlurAndBlendColor",
            view,
            1,
            blendToken
        ).success
    }

    private data class InvocationResult(val success: Boolean, val value: Any? = null)

    private fun invoke(clazz: Class<*>?, receiver: Any?, name: String, vararg args: Any?): InvocationResult {
        if (clazz == null) return InvocationResult(false)
        val method = allMethods(clazz).firstOrNull { it.name == name && parametersMatch(it, args) }
            ?: return InvocationResult(false)
        return try {
            method.isAccessible = true
            InvocationResult(true, method.invoke(receiver, *args))
        } catch (_: Throwable) {
            InvocationResult(false)
        }
    }

    private fun allMethods(clazz: Class<*>): Sequence<Method> = sequence {
        yieldAll(clazz.methods.asSequence())
        yieldAll(clazz.declaredMethods.asSequence())
    }

    private fun parametersMatch(method: Method, args: Array<out Any?>): Boolean {
        val types = method.parameterTypes
        if (types.size != args.size) return false
        return types.indices.all { index ->
            val type = types[index]
            val arg = args[index] ?: return@all !type.isPrimitive
            when {
                !type.isPrimitive -> type.isAssignableFrom(arg.javaClass)
                type == Boolean::class.javaPrimitiveType -> arg is Boolean
                type == Int::class.javaPrimitiveType -> arg is Number
                type == Float::class.javaPrimitiveType -> arg is Number
                type == Long::class.javaPrimitiveType -> arg is Number
                type == Double::class.javaPrimitiveType -> arg is Number
                type == Short::class.javaPrimitiveType -> arg is Number
                type == Byte::class.javaPrimitiveType -> arg is Number
                type == Char::class.javaPrimitiveType -> arg is Char
                else -> false
            }
        }
    }

    private fun receiverOf(clazz: Class<*>): Any? {
        return try {
            clazz.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
        } catch (_: Throwable) {
            null
        }
    }

    private fun loadClass(name: String, classLoader: ClassLoader): Class<*>? {
        return try {
            Class.forName(name, false, classLoader)
        } catch (_: Throwable) {
            null
        }
    }
}
