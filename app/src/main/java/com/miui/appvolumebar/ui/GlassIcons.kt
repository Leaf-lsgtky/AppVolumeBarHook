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
