package com.miui.appvolumebar.glass

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.miui.appvolumebar.MainHook
import com.miui.appvolumebar.status.HookStatusProvider
import de.robv.android.xposed.XSharedPreferences
import org.json.JSONObject

/**
 * SystemUI 侧的玻璃配置读取器（对应 HyperIsland 的 ConfigManager）。
 *
 * 三条互补通道，任一可用即可：
 * 1. XSharedPreferences：直接读模块 SharedPreferences 文件，无 IPC，优先使用；
 * 2. 变更广播：App 每次写入后前台广播完整快照，已注入的面板即时刷新；
 * 3. ContentProvider：XSharedPreferences 不可读时的兜底（会拉起模块进程，仅失败时走一次）。
 */
object GlassConfigStore {

    @Volatile
    private var settings: GlassSettings = GlassSettings()

    @Volatile
    private var revision: Int = 0
        private set

    @Volatile
    private var receiverRegistered = false

    @Volatile
    private var xspUnavailable = false

    /** 最近一次配置的来源，供诊断使用：xsp / broadcast / provider / default。 */
    @Volatile
    private var lastSource: String = "default"

    /** 最近一次读取的可读摘要（类型 + 模糊），供诊断使用。 */
    @Volatile
    private var lastSummary: String = "未读取"

    /** 未经「cached()」包装的真实来源，避免节流时字符串无限叠加。 */
    @Volatile
    private var baseSource: String = "default"

    private var lastProviderFetchMs = 0L

    private const val PROVIDER_THROTTLE_MS = 30_000L

    fun current(): GlassSettings = settings

    fun currentRevision(): Int = revision

    /** 诊断用：配置来源与内容摘要。 */
    fun diagnosis(): String = "source=$lastSource, ${lastSummary}"

    /** 在 SystemUI / 音量插件进程里绑定一次上下文，注册变更广播并首次读取。 */
    fun attach(context: Context?) {
        val ctx = context?.applicationContext ?: context ?: return
        reload(ctx)
        if (receiverRegistered) return
        receiverRegistered = true
        runCatching {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    if (intent?.action != GlassPrefs.ACTION_CONFIG_CHANGED) return
                    val raw = intent.getStringExtra(GlassPrefs.EXTRA_SETTINGS_JSON) ?: return
                    runCatching {
                        settings = GlassSettings.fromJson(JSONObject(raw))
                        revision++
                        lastSource = "broadcast"; baseSource = "broadcast"
                        lastSummary = summarize(settings)
                        MainHook.log("GlassConfigStore: config updated by broadcast -> ${settings.config.type}")
                    }
                }
            }
            val filter = IntentFilter(GlassPrefs.ACTION_CONFIG_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                ctx.registerReceiver(receiver, filter)
            }
            MainHook.log("GlassConfigStore: change receiver registered")
        }.onFailure {
            receiverRegistered = false
            MainHook.log("GlassConfigStore: failed to register change receiver", it)
        }
    }

    /** 重新读取配置；每次音量面板重建入口时调用即可拿到最新值。 */
    fun reload(context: Context? = null) {
        val fromXsp = readFromXSharedPreferences()
        if (fromXsp != null) {
            settings = fromXsp
            revision++
            lastSource = "xsp"; baseSource = "xsp"
            lastSummary = summarize(settings)
            return
        }
        // XSharedPreferences 在文件不可读时**不会抛异常**，只是 contains() 恒为 false。
        // 因此「读不到」和「用户还没配置过」在 SystemUI 侧无法区分，两种情况都必须
        // 再试一次 ContentProvider，否则一旦 XSP 失效就会静默退回默认材质。
        val ctx = context ?: run {
            lastSource = "default"; baseSource = "default"
            lastSummary = summarize(settings)
            return
        }
        // ContentProvider 会拉起模块进程，因此加节流。
        // 平时靠 App 侧的变更广播保持同步，这里只在冷启动/长时间未刷新时兜底。
        val now = System.currentTimeMillis()
        if (now - lastProviderFetchMs > PROVIDER_THROTTLE_MS) {
            lastProviderFetchMs = now
            val fromProvider = readFromProvider(ctx)
            if (fromProvider != null) {
                settings = fromProvider
                revision++
                lastSource = "provider"; baseSource = "provider"
            } else {
                lastSource = "default"; baseSource = "default"
            }
        } else {
            // 节流命中，保持上一次的真实来源，不要再往字符串上叠加。
            lastSource = "cached($baseSource)"
        }
        lastSummary = summarize(settings)
        MainHook.log("GlassConfigStore: reload -> $lastSource, ${lastSummary}")
    }

    private fun summarize(value: GlassSettings): String =
        "type=${value.config.type.value}, blur=${value.config.blur}, custom=${value.config.isCustom}"

    /**
     * @return 读到的配置；**null 表示没能确定配置**（文件不可读或缺少材质字段），
     * 调用方必须继续走 ContentProvider 兜底。
     *
     * 注意：XSharedPreferences 在文件不可读时不会抛异常，只会让 contains() 恒为 false，
     * 与「用户还没配置过」完全无法区分 —— 因此这里一律返回 null 交给 Provider 裁决，
     * 绝不能就地返回默认值，否则 XSP 一失效整个功能就静默退化（实测踩过）。
     */
    private fun readFromXSharedPreferences(): GlassSettings? {
        if (xspUnavailable) return null
        return runCatching {
            val prefs = XSharedPreferences(MainHook.PKG_SELF, GlassPrefs.PREFS_NAME)
            prefs.reload()
            if (!prefs.contains(GlassPrefs.KEY_MATERIAL)) {
                MainHook.log("GlassConfigStore: XSharedPreferences has no ${GlassPrefs.KEY_MATERIAL}, trying provider")
                return@runCatching null
            }
            GlassSettings(
                config = GlassMaterialConfig.decode(prefs.getString(GlassPrefs.KEY_MATERIAL, null).orEmpty()),
                options = GlassOptions(
                    gyroscope = prefs.getBoolean(GlassPrefs.KEY_GYROSCOPE, true),
                    hdrHighlight = prefs.getBoolean(GlassPrefs.KEY_HDR, false),
                    captureFps = prefs.getInt(GlassPrefs.KEY_CAPTURE_FPS, 20).coerceIn(1, 90),
                    captureQuality = prefs.getInt(GlassPrefs.KEY_CAPTURE_QUALITY, 30).coerceIn(10, 100),
                ),
            )
        }.onFailure {
            xspUnavailable = true
            MainHook.log("GlassConfigStore: XSharedPreferences unreadable, using provider", it)
        }.getOrNull()
    }

    private fun readFromProvider(context: Context): GlassSettings? {
        return runCatching {
            val bundle = context.contentResolver.call(
                HookStatusProvider.URI,
                HookStatusProvider.METHOD_GET_GLASS,
                null,
                null,
            ) ?: return null
            val raw = bundle.getString(HookStatusProvider.EXTRA_SETTINGS_JSON) ?: return null
            GlassSettings.fromJson(JSONObject(raw))
        }.onFailure {
            MainHook.log("GlassConfigStore: provider fallback failed", it)
        }.getOrNull()
    }
}
