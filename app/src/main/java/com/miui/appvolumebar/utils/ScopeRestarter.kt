package com.miui.appvolumebar.utils

import com.miui.appvolumebar.MainHook

/**
 * 「重启作用域」：用 root 结束静态作用域里的进程，让它们带着 Hook 重新拉起，
 * 等同于 LSPosed 管理器里的同名操作，区别只是范围固定为本模块声明的静态作用域
 * （AndroidManifest 的 xposedscope），用户不用手动勾选。
 *
 * 杀掉 com.android.systemui 后系统会自行重新拉起（和 pkill systemui 的效果一致）；
 * com.miui.misound 是普通应用，被杀后在下次需要播放音频时由系统重新拉起。
 */
object ScopeRestarter {

    /** 与 AndroidManifest 中 @array/xposed_scope 保持一致 */
    val SCOPE_PACKAGES: List<String> = listOf(
        MainHook.PKG_SYSTEMUI,
        MainHook.PKG_MISOUND,
    )

    private const val DONE_TOKEN = "__APP_VOLUME_BAR_SCOPE_DONE__"

    sealed interface Result {
        /** 命令已在 root shell 中执行完毕 */
        data object Success : Result

        /** 没拿到 root（无 su / 授权被拒绝） */
        data object RootNotGranted : Result

        /** 拿到了 root 但没能正常执行完 */
        data class Error(val detail: String) : Result
    }

    /** 同步执行，请在 IO 线程调用（root shell 可能卡住若干秒）。 */
    fun restart(): Result {
        if (!RootShell.isRootGranted()) return Result.RootNotGranted

        val script = buildString {
            appendLine("# 结束作用域进程，系统会自动重新拉起")
            SCOPE_PACKAGES.forEach { pkg ->
                // pidof 比 killall 通用（toybox 自带），$() 保证没匹配到进程时不报错
                appendLine("pids=\$(pidof $pkg)")
                appendLine("if [ -n \"\$pids\" ]; then kill -9 \$pids; fi")
            }
            appendLine("echo $DONE_TOKEN")
        }

        val executed = RootShell.run(script)
        if (executed.output.contains(DONE_TOKEN) || executed.success) return Result.Success
        return Result.Error(executed.output.trim().ifBlank { "root shell 未返回结果" })
    }
}
