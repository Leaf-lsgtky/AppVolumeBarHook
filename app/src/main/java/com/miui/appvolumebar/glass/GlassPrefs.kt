package com.miui.appvolumebar.glass

import android.content.Context
import android.content.Intent
import org.json.JSONObject
import java.io.File

/** 一次完整的玻璃设置快照：材质配置 + 对所有玻璃类型生效的全局开关。 */
data class GlassSettings(
    val config: GlassMaterialConfig = GlassMaterialConfig(),
    val options: GlassOptions = GlassOptions(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put(FIELD_MATERIAL, config.toJson())
        put(FIELD_GYROSCOPE, options.gyroscope)
        put(FIELD_HDR, options.hdrHighlight)
        put(FIELD_CAPTURE_FPS, options.captureFps)
        put(FIELD_CAPTURE_QUALITY, options.captureQuality)
    }

    companion object {
        const val FIELD_MATERIAL = "material"
        const val FIELD_GYROSCOPE = "gyroscope"
        const val FIELD_HDR = "hdrHighlight"
        const val FIELD_CAPTURE_FPS = "captureFps"
        const val FIELD_CAPTURE_QUALITY = "captureQuality"

        fun fromJson(json: JSONObject): GlassSettings = GlassSettings(
            config = runCatching { GlassMaterialConfig.fromJson(json.getJSONObject(FIELD_MATERIAL)) }
                .getOrDefault(GlassMaterialConfig()),
            options = GlassOptions(
                gyroscope = json.optBoolean(FIELD_GYROSCOPE, true),
                hdrHighlight = json.optBoolean(FIELD_HDR, false),
                captureFps = json.optInt(FIELD_CAPTURE_FPS, 20).coerceIn(1, 90),
                captureQuality = json.optInt(FIELD_CAPTURE_QUALITY, 30).coerceIn(10, 100),
            ),
        )

        fun decode(raw: String): GlassSettings = runCatching {
            fromJson(JSONObject(raw))
        }.getOrDefault(GlassSettings())
    }
}

/**
 * 模块 App 进程侧的玻璃设置读写。
 *
 * 存储使用独立 SharedPreferences（app_volume_glass），SystemUI 侧通过
 * XSharedPreferences 读取；每次写入后额外广播一次完整快照，让已经注入的
 * 音量面板立刻生效，无需重启系统界面。
 */
object GlassPrefs {

    const val PREFS_NAME = "app_volume_glass"
    const val ACTION_CONFIG_CHANGED = "com.miui.appvolumebar.ACTION_GLASS_CONFIG_CHANGED"
    const val EXTRA_SETTINGS_JSON = "extra_glass_settings_json"

    const val KEY_MATERIAL = "pref_volume_material_config"
    const val KEY_GYROSCOPE = "pref_volume_glass_gyroscope"
    const val KEY_HDR = "pref_volume_glass_hdr_highlight"
    const val KEY_CAPTURE_FPS = "pref_volume_glass_capture_fps"
    const val KEY_CAPTURE_QUALITY = "pref_volume_glass_capture_quality"

    // 剪贴板导入导出
    private const val CLIPBOARD_TYPE = "app_volume_glass_config"
    private const val CLIPBOARD_VERSION = 1
    private const val HYPERISLAND_CLIPBOARD_TYPE = "hyperisland_material_config"

    /** 导入失败原因：配置里含柔光玻璃，但当前系统（HyperOS 3）不支持。 */
    const val SOFT_GLASS_UNSUPPORTED = "soft_glass_unsupported"

    fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(context: Context): GlassSettings {
        // 老版本保存下来的文件可能还是 600，读之前补一次权限，让 XSP 通道能用上。
        makeWorldReadable(context)
        return GlassSettings(
            config = GlassMaterialConfig.decode(
                prefs(context).getString(KEY_MATERIAL, null).orEmpty()
            ),
            options = GlassOptions(
                gyroscope = prefs(context).getBoolean(KEY_GYROSCOPE, true),
                hdrHighlight = prefs(context).getBoolean(KEY_HDR, false),
                captureFps = prefs(context).getInt(KEY_CAPTURE_FPS, 20).coerceIn(1, 90),
                captureQuality = prefs(context).getInt(KEY_CAPTURE_QUALITY, 30).coerceIn(10, 100),
            ),
        )
    }

    private fun commit(context: Context, settings: GlassSettings) {
        // 用 commit() 而不是 apply()：必须同步落盘后紧接着 chmod，
        // 否则 SharedPreferences 的「写临时文件再 rename」会把权限重置回 600，
        // SystemUI 侧的 XSharedPreferences 就永远读不到。
        prefs(context).edit()
            .putString(KEY_MATERIAL, settings.config.toJson().toString())
            .putBoolean(KEY_GYROSCOPE, settings.options.gyroscope)
            .putBoolean(KEY_HDR, settings.options.hdrHighlight)
            .putInt(KEY_CAPTURE_FPS, settings.options.captureFps)
            .putInt(KEY_CAPTURE_QUALITY, settings.options.captureQuality)
            .commit()
        makeWorldReadable(context)
        notifyChanged(context, settings)
    }

    /**
     * 让 XSharedPreferences 能读到本模块的配置文件。
     *
     * Android 7+ 起 MODE_WORLD_READABLE 被废弃，SharedPreferences 固定按 600 写文件，
     * 其他进程（SystemUI）读不到；这里在每次写入后手动把文件补成全局可读。
     * 失败不影响功能 —— 还有 ContentProvider 兜底通道。
     */
    private fun makeWorldReadable(context: Context) {
        runCatching {
            val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
            prefsDir.setReadable(true, false)
            prefsDir.setExecutable(true, false)
            File(prefsDir, "$PREFS_NAME.xml").setReadable(true, false)
        }.onFailure {
            android.util.Log.w("AppVolumeBarHook", "Failed to make glass prefs world readable", it)
        }
    }

    fun saveMaterial(context: Context, settings: GlassSettings, config: GlassMaterialConfig): GlassSettings {
        val updated = settings.copy(config = config)
        commit(context, updated)
        return updated
    }

    fun saveOptions(context: Context, settings: GlassSettings, options: GlassOptions): GlassSettings {
        val updated = settings.copy(options = options)
        commit(context, updated)
        return updated
    }

    fun reset(context: Context): GlassSettings {
        val defaults = GlassSettings()
        commit(context, defaults)
        return defaults
    }

    /** 通知 SystemUI 侧配置已变更（前台广播，绕过后台广播队列延迟）。 */
    private fun notifyChanged(context: Context, settings: GlassSettings) {
        runCatching {
            val intent = Intent(ACTION_CONFIG_CHANGED).apply {
                setPackage(context.packageName)
                putExtra(EXTRA_SETTINGS_JSON, settings.toJson().toString())
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            }
            context.sendBroadcast(intent)
        }
    }

    fun export(settings: GlassSettings): String = JSONObject().apply {
        put("type", CLIPBOARD_TYPE)
        put("version", CLIPBOARD_VERSION)
        put(GlassSettings.FIELD_MATERIAL, settings.config.toJson())
        put(GlassSettings.FIELD_GYROSCOPE, settings.options.gyroscope)
        put(GlassSettings.FIELD_HDR, settings.options.hdrHighlight)
        put(GlassSettings.FIELD_CAPTURE_FPS, settings.options.captureFps)
        put(GlassSettings.FIELD_CAPTURE_QUALITY, settings.options.captureQuality)
    }.toString(2)

    /**
     * 导入剪贴板配置。除本模块格式外，也接受 HyperIsland 导出的
     * hyperisland_material_config（取其「大岛」一份），方便直接复用同款参数。
     *
     * @param allowSoftGlass false 时，若待导入配置是柔光玻璃则抛
     * [SOFT_GLASS_UNSUPPORTED]（HyperOS 3 上由调用方按 HyperOsVersionUtil 结果传入）。
     */
    fun import(context: Context, raw: String, allowSoftGlass: Boolean = true): GlassSettings {
        val root = JSONObject(raw)
        val type = root.optString("type")
        if (type == HYPERISLAND_CLIPBOARD_TYPE) {
            require(root.optInt("version") == 1) { "unsupported_hyperisland_version" }
            val big = root.getJSONObject("big")
            val settings = GlassSettings(
                config = GlassMaterialConfig.fromJson(big),
                options = GlassOptions(
                    gyroscope = root.optBoolean("gyroscope", true),
                    hdrHighlight = root.optBoolean("hdrHighlight", false),
                    captureFps = root.optInt("captureFps", 20).coerceIn(1, 90),
                    captureQuality = root.optInt("captureQuality", 30).coerceIn(10, 100),
                ),
            )
            checkSoftGlass(settings, allowSoftGlass)
            commit(context, settings)
            return settings
        }
        require(type == CLIPBOARD_TYPE && root.optInt("version") == CLIPBOARD_VERSION) {
            "unknown_format"
        }
        val settings = GlassSettings.fromJson(root)
        checkSoftGlass(settings, allowSoftGlass)
        commit(context, settings)
        return settings
    }

    private fun checkSoftGlass(settings: GlassSettings, allowSoftGlass: Boolean) {
        require(allowSoftGlass || !settings.config.isSoftGlass) { SOFT_GLASS_UNSUPPORTED }
    }
}
