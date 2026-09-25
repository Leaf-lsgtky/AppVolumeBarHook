package com.miui.appvolumebar.glass

import android.graphics.Color

/**
 * 面板液态玻璃的渲染配置。
 *
 * 参数集与默认值承袭 HyperIsland 超级岛液态玻璃（IslandBlurHook / LiqudGlass）的调校，
 * 并已在 SoundMan 的内置面板上实装验证：
 * - [enabled] 对应「启用玻璃效果」：在官方背景模糊之上叠加方向性边缘高光、透镜带与棱镜色散；
 * - [trueRefraction] 对应「液态玻璃」：捕获面板后方画面并用 AGSL 折射着色器实时渲染。
 *
 * 边缘带与折射位移按面板较短边计算——HyperIsland 的岛是横向 pill（高度即短边），
 * 音量面板更高瘦，沿用短边语义才能得到同量级的视觉效果。
 */
data class PanelGlassConfig(
    val enabled: Boolean,
    val trueRefraction: Boolean,
    val edgeWidth: Float = EDGE_WIDTH,
    val refraction: Float = REFRACTION,
    val highlight: Float = HIGHLIGHT,
    val shadow: Float = SHADOW,
    val lightDirection: Int = LIGHT_DIRECTION,
    val dispersion: Float = DISPERSION,
    val gyroscope: Boolean = GYROSCOPE,
    val captureFps: Int = CAPTURE_FPS,
    val captureScale: Float = CAPTURE_SCALE,
    val captureBlurRadius: Float = CAPTURE_BLUR_RADIUS,
    val blendColor: Int = BLEND_COLOR,
) {
    companion object {
        const val EDGE_WIDTH = 0.16f
        const val REFRACTION = 0.16f
        const val HIGHLIGHT = 0.42f
        const val SHADOW = 0.14f
        const val LIGHT_DIRECTION = 243
        const val DISPERSION = 0.18f
        const val GYROSCOPE = true
        const val CAPTURE_FPS = 20
        const val CAPTURE_SCALE = 0.3f
        const val CAPTURE_BLUR_RADIUS = 20f
        const val BLEND_COLOR = 0x20FFFFFF

        /** 把通用的玻璃材质配置换算成本面板渲染器的参数。 */
        fun from(config: GlassMaterialConfig, options: GlassOptions): PanelGlassConfig {
            val rgb = runCatching { Color.parseColor(config.blendColor.trim()) }
                .getOrDefault(Color.WHITE)
            return PanelGlassConfig(
                enabled = config.isEdgeGlass,
                trueRefraction = config.isTrueRefraction,
                edgeWidth = config.edgeThickness.coerceIn(4, 40) / 100f,
                refraction = config.refraction.coerceIn(0, 40) / 100f,
                highlight = config.reflectionStrength.coerceIn(0, 100) / 100f,
                shadow = config.darker.coerceIn(0, 100) / 100f,
                lightDirection = config.lightDirection.coerceIn(0, 359),
                dispersion = config.dispersion.coerceIn(0, 100) / 100f,
                gyroscope = options.gyroscope,
                captureFps = options.captureFps,
                captureScale = options.captureQuality.coerceIn(10, 100) / 100f,
                // 折射层底图自带模糊，半径上限与 SoundMan 一致（RenderEffect 超过 20 收益递减）
                captureBlurRadius = config.blur.toFloat().coerceIn(0f, 20f),
                blendColor = Color.argb(
                    config.blendOpacity.coerceIn(0, 100) * 255 / 100,
                    Color.red(rgb),
                    Color.green(rgb),
                    Color.blue(rgb),
                ),
            )
        }
    }
}
