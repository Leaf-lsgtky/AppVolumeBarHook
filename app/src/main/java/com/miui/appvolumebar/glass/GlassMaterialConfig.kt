package com.miui.appvolumebar.glass

import org.json.JSONObject

/**
 * 音量面板玻璃材质配置。
 *
 * 字段与取值域完全对齐 HyperIsland「外观 - 背景 - 玻璃效果 - 材质自定义」
 * （compose/data/IslandMaterialConfig.kt + xposed/.../Blur/model/MaterialConfig），
 * 只是把「大岛 / 小岛 / 展开态」三份配置收敛为音量面板一份。
 *
 * JSON 中的 softSchema 沿用 HyperIsland 的版本号含义：
 *  - 0/1：柔光玻璃参数为早代绝对值，导入时统一回落到默认值；
 *  - 2  ：柔光参数改为相对官方 token 的百分比；
 *  - 3  ：新增 saturation / brightness / softDarker 三项。
 */
enum class GlassMaterialType(val value: String) {
    Default("default"),
    Gaussian("gaussian"),
    HighlightGlass("highlight_glass"),
    LiquidGlass("liquid_glass"),
    SoftGlass("soft_glass");

    companion object {
        fun fromValue(value: String?): GlassMaterialType =
            entries.firstOrNull { it.value == value } ?: Default
    }
}

/** 与配置一起存放、对所有玻璃类型生效的全局开关。 */
data class GlassOptions(
    val gyroscope: Boolean = true,
    val hdrHighlight: Boolean = false,
    val captureFps: Int = 20,
    val captureQuality: Int = 30,
)

data class GlassMaterialConfig(
    // ── 类型与模糊 ────────────────────────────────────────────────────────────
    val type: GlassMaterialType = GlassMaterialType.Default,
    val blur: Int = 35,
    // ── 高光玻璃 / 液态玻璃 ───────────────────────────────────────────────────
    val refraction: Int = 16,
    val edgeThickness: Int = 16,
    val reflectionStrength: Int = 42,
    val darker: Int = 14,
    val lightDirection: Int = 243,
    val dispersion: Int = 18,
    val highlight: Boolean = true,
    // ── 柔光玻璃（HyperOS 4 Bionics）────────────────────────────────────────
    val softLight: Double = -1.0,
    val saturation: Double = 0.0,
    val brightness: Double = 0.0,
    val softDarker: Double = 0.0,
    val transparency: Double = -0.57,
    val burn: Double = 0.0,
    val softRefraction: Double = 0.0,
    val softEdgeThickness: Double = 0.8,
    val softReflection: Double = 0.0,
    val directionalLightIntensity: Double = 1.0,
    val backgroundSaturation: Double = 0.0,
    val backgroundBrightness: Double = 0.04,
    // ── 混色 ──────────────────────────────────────────────────────────────────
    val blendColor: String = "#FFFFFF",
    val blendOpacity: Int = 0,
) {

    val isCustom: Boolean get() = type != GlassMaterialType.Default

    /** 是否启用 HyperIsland 的边缘光照渲染（高光玻璃 / 液态玻璃）。 */
    val isEdgeGlass: Boolean
        get() = type == GlassMaterialType.HighlightGlass || type == GlassMaterialType.LiquidGlass

    /** 液态玻璃 = 高光玻璃 + 真实折射采样。 */
    val isTrueRefraction: Boolean get() = type == GlassMaterialType.LiquidGlass

    val isSoftGlass: Boolean get() = type == GlassMaterialType.SoftGlass

    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type.value)
        put("blur", blur)
        put("softSchema", SOFT_SCHEMA)
        put("softLight", softLight)
        put("saturation", saturation)
        put("brightness", brightness)
        put("softDarker", softDarker)
        put("transparency", transparency)
        put("burn", burn)
        put("softRefraction", softRefraction)
        put("softEdgeThickness", softEdgeThickness)
        put("softReflection", softReflection)
        put("directionalLightIntensity", directionalLightIntensity)
        put("backgroundSaturation", backgroundSaturation)
        put("backgroundBrightness", backgroundBrightness)
        put("refraction", refraction)
        put("edgeThickness", edgeThickness)
        put("reflectionStrength", reflectionStrength)
        put("darker", darker)
        put("lightDirection", lightDirection)
        put("dispersion", dispersion)
        put("blendColor", blendColor)
        put("blendOpacity", blendOpacity)
        put("highlight", highlight)
    }

    /** 切换类型时沿用 HyperIsland 的默认值迁移规则。 */
    fun withType(next: GlassMaterialType): GlassMaterialConfig {
        if (next == type) return this
        if (next == GlassMaterialType.SoftGlass) return GlassMaterialConfig(type = next)
        if (type == GlassMaterialType.SoftGlass || type == GlassMaterialType.Default) {
            return copy(type = next, blur = 80, blendColor = "#FFFFFF", blendOpacity = 13)
        }
        return copy(type = next)
    }

    companion object {
        const val SOFT_SCHEMA = 3

        fun fromJson(json: JSONObject): GlassMaterialConfig {
            fun integer(key: String, fallback: Int, range: IntRange): Int =
                json.optInt(key, fallback).coerceIn(range)

            fun decimal(key: String, fallback: Double): Double =
                json.optDouble(key, fallback).coerceIn(-50.0, 50.0)

            val softSchema = json.optInt("softSchema", 0)
            val type = GlassMaterialType.fromValue(json.optString("type", "default"))
            fun soft(key: String, fallback: Double): Double =
                if (softSchema >= 2) decimal(key, fallback) else fallback

            return GlassMaterialConfig(
                type = type,
                blur = integer("blur", 35, 0..100),
                softLight = soft("softLight", -1.0),
                saturation = if (softSchema >= 3) soft("saturation", 0.0) else 0.0,
                brightness = if (softSchema >= 3) soft("brightness", 0.0) else 0.0,
                softDarker = if (softSchema >= 3) soft("softDarker", 0.0) else 0.0,
                transparency = soft("transparency", -0.57),
                burn = soft("burn", 0.0),
                softRefraction = soft("softRefraction", 0.0),
                softEdgeThickness = soft("softEdgeThickness", 0.8),
                softReflection = soft("softReflection", 0.0),
                directionalLightIntensity = soft("directionalLightIntensity", 1.0),
                backgroundSaturation = soft("backgroundSaturation", 0.0),
                backgroundBrightness = soft("backgroundBrightness", 0.04),
                refraction = integer("refraction", 16, 0..40),
                edgeThickness = integer("edgeThickness", 16, 4..40),
                reflectionStrength = integer("reflectionStrength", 42, 0..100),
                darker = integer("darker", 14, 0..100),
                lightDirection = integer("lightDirection", 243, 0..359),
                dispersion = integer("dispersion", 18, 0..100),
                blendColor = json.optString("blendColor", "#FFFFFF").trim(),
                blendOpacity = if (softSchema < 2 && type == GlassMaterialType.SoftGlass) {
                    0
                } else {
                    integer("blendOpacity", 0, 0..100)
                },
                highlight = json.optBoolean("highlight", true),
            )
        }

        fun decode(raw: String): GlassMaterialConfig = runCatching {
            fromJson(JSONObject(raw))
        }.getOrDefault(GlassMaterialConfig())
    }
}
