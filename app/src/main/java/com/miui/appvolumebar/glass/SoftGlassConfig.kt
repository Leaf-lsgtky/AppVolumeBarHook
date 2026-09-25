package com.miui.appvolumebar.glass

import android.graphics.Color

/** Parameters understood by HyperOS 4's native Bionics soft-glass renderer. */
internal data class SoftGlassConfig(
    val blurRadius: Int,
    val softLight: Double,
    val saturation: Double,
    val brightness: Double,
    val darker: Double,
    val transparency: Double,
    val burn: Double,
    val refraction: Double,
    val edgeThickness: Double,
    val reflection: Double,
    val directionalLightIntensity: Double,
    val backgroundSaturation: Double,
    val backgroundBrightness: Double,
    val tintColor: Int,
    val highlight: Boolean,
) {
    companion object {
        /** 从 HyperIsland 同款 JSON 结构生成，schema 规则与上游一致（默认已是第 3 版）。 */
        fun from(config: GlassMaterialConfig): SoftGlassConfig {
            val blend = runCatching { Color.parseColor(config.blendColor.trim()) }
                .getOrDefault(Color.WHITE)
            val tintColor = Color.argb(
                config.blendOpacity.coerceIn(0, 100) * 255 / 100,
                Color.red(blend),
                Color.green(blend),
                Color.blue(blend),
            )
            return SoftGlassConfig(
                blurRadius = config.blur.coerceIn(0, 100),
                softLight = config.softLight,
                saturation = config.saturation,
                brightness = config.brightness,
                darker = config.softDarker,
                transparency = config.transparency,
                burn = config.burn,
                refraction = config.softRefraction,
                edgeThickness = config.softEdgeThickness,
                reflection = config.softReflection,
                directionalLightIntensity = config.directionalLightIntensity,
                backgroundSaturation = config.backgroundSaturation,
                backgroundBrightness = config.backgroundBrightness,
                tintColor = tintColor,
                highlight = config.highlight,
            )
        }
    }
}
