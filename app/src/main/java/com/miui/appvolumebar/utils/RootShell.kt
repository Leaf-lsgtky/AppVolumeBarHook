package com.miui.appvolumebar.utils

import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit

/**
 * 极简 root shell 封装，只提供两件事：判断是否真的拿到 root、执行一段 shell 脚本。
 *
 * 采用交互式 su（写命令 -> exit）而不是 `su -c "..."`，对 Magisk / KernelSU / APatch
 * 三种实现的兼容性最好；stdout 与 stderr 合并读取，避免缓冲区写满把进程卡死。
 */
object RootShell {

    private const val TAG = "RootShell"
    private const val DEFAULT_TIMEOUT_MS = 8_000L
    private const val PROBE_TIMEOUT_MS = 4_000L

    /**
     * 各家 root 方案把 su 放在不同的地方，且不一定都进了 App 进程的 PATH
     * （KernelSU 尤其如此），所以按顺序探测，成功一次后记住路径。
     */
    private val SU_CANDIDATES = listOf(
        "su",                       // PATH 里能找到就用它
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/data/adb/ksu/bin/su",     // KernelSU
        "/data/adb/ap/bin/su",      // APatch
        "/data/adb/magisk/su",      // Magisk
    )

    @Volatile
    private var grantedSuPath: String? = null

    /** @param success su 进程正常结束且退出码为 0；@param output 合并后的 stdout + stderr */
    data class Result(val success: Boolean, val output: String)

    /**
     * 尝试提权后回读 uid，判断是否真的拿到了 root。
     * 没有 su、授权弹窗被拒绝、su 报错等情况一律返回 false。
     */
    fun isRootGranted(): Boolean {
        grantedSuPath?.let { if (probe(it)) return true }
        for (path in SU_CANDIDATES) {
            if (probe(path)) {
                grantedSuPath = path
                return true
            }
        }
        grantedSuPath = null
        return false
    }

    /** 在 root shell 里执行 [script]（可多行），最多等待 [timeoutMs] 毫秒。 */
    fun run(script: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Result {
        val suPath = grantedSuPath
            ?: SU_CANDIDATES.firstOrNull { probe(it) }
                ?.also { grantedSuPath = it }
            ?: return Result(false, "su not found")
        return runWith(suPath, script, timeoutMs)
    }

    private fun probe(suPath: String): Boolean {
        val result = runWith(suPath, "id -u", PROBE_TIMEOUT_MS)
        return result.output.contains("uid=0") ||
            result.output.lineSequence().any { it.trim() == "0" }
    }

    private fun runWith(suPath: String, script: String, timeoutMs: Long): Result {
        var process: Process? = null
        return try {
            process = ProcessBuilder(suPath)
                .redirectErrorStream(true)
                .start()

            val output = StringBuilder()
            val reader = Thread({
                runCatching {
                    process.inputStream.bufferedReader().forEachLine { line ->
                        output.append(line).append('\n')
                    }
                }
            }, "$TAG-output").apply {
                isDaemon = true
                start()
            }

            OutputStreamWriter(process.outputStream).use { writer ->
                writer.write(script)
                if (!script.endsWith('\n')) writer.write("\n")
                writer.write("exit\n")
                writer.flush()
            }

            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            reader.join(1_000L)
            if (!finished) {
                process.destroy()
                return Result(false, "timeout")
            }
            Result(process.exitValue() == 0, output.toString())
        } catch (t: Throwable) {
            // 没有 su、SELinux 拒绝、进程被杀等都走这里，统一当成「没拿到 root」
            Result(false, t.message.orEmpty())
        } finally {
            runCatching { process?.destroy() }
        }
    }
}
