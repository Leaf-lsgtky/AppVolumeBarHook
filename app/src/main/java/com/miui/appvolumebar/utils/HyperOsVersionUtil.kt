package com.miui.appvolumebar.utils

/**
 * HyperOS 版本检测工具（对齐 HyperIsland 的 HyperOsVersionUtil）。
 *
 * 只对业务需要的 HyperOS 3 / 4 返回对应主版本；无法识别或其他版本返回 0，
 * 避免在非小米设备或系统属性变化时误判。
 *
 * 注意：返回 0（识别不出）时**不**当作 OS3 处理，柔光玻璃仍然可见，
 * 由 SoftGlassController 在运行时按 Bionics 可用性自行决定是否降级。
 */
object HyperOsVersionUtil {

    private val supportedMajorVersions = setOf(3, 4)
    private val versionPattern = Regex("(?i)(?:hyperos|os)\\s*[-_]?\\s*([34])(?:\\.\\d+)?")

    @JvmStatic
    fun getMajorVersion(): Int {
        val versionName = SystemPropertyReader.get("ro.mi.os.version.name")
        parseMajorVersion(versionName)?.let { return it }

        val versionCode = SystemPropertyReader.get("ro.mi.os.version.code")
        return versionCode.toIntOrNull()?.takeIf { it in supportedMajorVersions } ?: 0
    }

    internal fun parseMajorVersion(value: String): Int? {
        return versionPattern.find(value.trim())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.takeIf { it in supportedMajorVersions }
    }
}
