package com.miui.appvolumebar.status

/**
 * 模块自激活状态检测类。
 * 当模块在 LSPosed 中激活并在作用域勾选了本模块时，Xposed 会 Hook 此方法并返回 true。
 */
object ModuleStatus {
    fun isModuleActive(): Boolean = false
}
