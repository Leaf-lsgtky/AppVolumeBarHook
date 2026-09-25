package com.miui.appvolumebar.glass

import android.graphics.drawable.Drawable
import android.view.View
import com.miui.appvolumebar.MainHook
import java.lang.reflect.Method

/**
 * 玻璃渲染共用的反射工具。
 *
 * SystemUI（入口按钮）与 Misound（分应用音量面板）两侧都要用同一套调用，
 * 因此抽出来共享，避免两份实现走偏。
 */
internal object GlassReflection {

    /**
     * 创建框架 BackgroundBlurDrawable。
     *
     * 必须等 View 挂到窗口之后调用（依赖 ViewRootImpl），否则返回 null 并通过
     * [onFailure] 回传具体原因，便于诊断。
     */
    fun createBackgroundBlurDrawable(view: View, onFailure: (String) -> Unit): Drawable? {
        val viewRoot = runCatching {
            findMethod(view.javaClass, "getViewRootImpl", emptyArray())?.invoke(view)
        }.onFailure {
            onFailure("getViewRootImpl反射异常:${it.javaClass.simpleName}")
        }.getOrNull()
        if (viewRoot == null) {
            onFailure("getViewRootImpl为空(未挂窗口)")
            return null
        }
        return runCatching {
            val method = findMethod(viewRoot.javaClass, "createBackgroundBlurDrawable", emptyArray())
                ?: run { onFailure("ViewRootImpl无createBackgroundBlurDrawable"); return@runCatching null }
            val drawable = method.invoke(viewRoot) as? Drawable
            if (drawable == null) onFailure("createBackgroundBlurDrawable返回null")
            drawable
        }.onFailure {
            onFailure("createBackgroundBlurDrawable异常:${it.javaClass.simpleName}")
            MainHook.log("GlassReflection: createBackgroundBlurDrawable failed: ${it.message}")
        }.getOrNull()
    }

    /** 按「方法名 + 参数个数 + 参数类型（含基本类型装箱匹配）」定位并调用。 */
    fun invoke(receiver: Any?, name: String, vararg args: Any?): Boolean {
        val target = receiver ?: return false
        val method = findMethod(target.javaClass, name, args) ?: return false
        return runCatching {
            method.invoke(target, *args)
            true
        }.onFailure {
            MainHook.log("GlassReflection: invoke $name failed: ${it.message}")
        }.getOrDefault(false)
    }

    fun findMethod(clazz: Class<*>, name: String, args: Array<out Any?>): Method? {
        var current: Class<*>? = clazz
        while (current != null) {
            val candidates = buildList {
                addAll(current.methods)
                addAll(current.declaredMethods)
            }
            val match = candidates.firstOrNull { method ->
                method.name == name && parametersMatch(method.parameterTypes, args)
            }
            if (match != null) return match.apply { isAccessible = true }
            current = current.superclass
        }
        return null
    }

    private fun parametersMatch(types: Array<Class<*>>, args: Array<out Any?>): Boolean {
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
}
