package com.miui.appvolumebar.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 页面内用到的两个极简图标。
 *
 * 项目当前只依赖 miuix-ui / miuix-preference，而 Miuix 的 MiuixIcons 图标集
 * 位于独立的 miuix-icons 制品中，为避免新增依赖这里直接用 Path 手写，
 * 由调用方的 Icon(tint=...) 统一着色。
 */
private fun vectorIcon(name: String, block: androidx.compose.ui.graphics.vector.ImageVector.Builder.() -> Unit): ImageVector =
    androidx.compose.ui.graphics.vector.ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply(block).build()

val IconArrowBack: ImageVector by lazy {
    vectorIcon("ArrowBack") {
        path(fill = SolidColor(Color.Black)) {
            moveTo(15.41f, 7.41f)
            lineTo(14f, 6f)
            lineTo(8f, 12f)
            lineTo(14f, 18f)
            lineTo(15.41f, 16.59f)
            lineTo(10.83f, 12f)
            close()
        }
    }
}

/** 主页右上角「重启作用域」按钮用的刷新图标（Material refresh，24dp 网格）。 */
val IconRestart: ImageVector by lazy {
    vectorIcon("Restart") {
        path(fill = SolidColor(Color.Black)) {
            moveTo(17.65f, 6.35f)
            curveTo(16.2f, 4.9f, 14.21f, 4f, 12f, 4f)
            curveTo(7.58f, 4f, 4.01f, 7.58f, 4.01f, 12f)
            curveTo(4.01f, 16.42f, 7.58f, 20f, 12f, 20f)
            curveTo(15.73f, 20f, 18.84f, 17.45f, 19.73f, 14f)
            lineTo(17.65f, 14f)
            curveTo(16.83f, 16.33f, 14.61f, 18f, 12f, 18f)
            curveTo(8.69f, 18f, 6f, 15.31f, 6f, 12f)
            curveTo(6f, 8.69f, 8.69f, 6f, 12f, 6f)
            curveTo(13.66f, 6f, 15.14f, 6.69f, 16.22f, 7.78f)
            lineTo(13f, 11f)
            lineTo(20f, 11f)
            lineTo(20f, 4f)
            lineTo(17.65f, 6.35f)
            close()
        }
    }
}

val IconChevronRight: ImageVector by lazy {
    vectorIcon("ChevronRight") {
        path(fill = SolidColor(Color.Black)) {
            moveTo(8.59f, 16.59f)
            lineTo(10f, 18f)
            lineTo(16f, 12f)
            lineTo(10f, 6f)
            lineTo(8.59f, 7.41f)
            lineTo(13.17f, 12f)
            close()
        }
    }
}
