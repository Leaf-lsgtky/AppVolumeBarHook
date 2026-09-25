package com.miui.appvolumebar.utils

import java.util.concurrent.TimeUnit

/**
 * 读取 Android 系统属性，对调用方隐藏隐藏 API。
 *
 * 与 HyperIsland 的 io.github.hyperisland.utils.SystemPropertyReader 一致：
 * 优先反射 android.os.SystemProperties.get，失败再退到 /system/bin/getprop。
 */
object SystemPropertyReader {

    @JvmStatic
    fun get(key: String, default: String = ""): String {
        if (key.isBlank()) return default
        val reflected = runCatching {
            val systemProperties = Class.forName("android.os.SystemProperties")
            val method = systemProperties.getMethod(
                "get",
                String::class.java,
                String::class.java,
            )
            (method.invoke(null, key, default) as? String).orEmpty().trim()
        }.getOrDefault("")
        if (reflected.isNotEmpty()) return reflected

        return runCatching {
            val process = ProcessBuilder("/system/bin/getprop", key)
                .redirectErrorStream(true)
                .start()
            if (!process.waitFor(1, TimeUnit.SECONDS)) {
                process.destroy()
                return@runCatching default
            }
            process.inputStream.bufferedReader().use { it.readText().trim() }
                .ifBlank { default }
        }.getOrDefault(default)
    }
}
