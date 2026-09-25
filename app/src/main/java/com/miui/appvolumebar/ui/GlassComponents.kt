package com.miui.appvolumebar.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.miui.appvolumebar.R
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.ColorPalette
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import java.util.Locale

/**
 * 复刻 HyperIsland「设置页组件」的最小集合
 * （compose/component/PreferenceComponents.kt + MiuixComponents.kt + AppearanceDialogs.kt
 * + ColorPaletteDialog.kt），去掉了与超级岛业务耦合的部分。
 */
internal val SettingsItemMargin = PaddingValues(horizontal = 18.dp, vertical = 14.dp)

@Composable
internal fun SectionTitle(title: String) {
    SmallTitle(
        text = title,
        modifier = Modifier.padding(top = 4.dp),
        insideMargin = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
    )
}

@Composable
internal fun SettingsAction(
    title: String,
    summary: String? = null,
    endIcon: ImageVector? = null,
    endIconSize: androidx.compose.ui.unit.Dp = 20.dp,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    BasicComponent(
        title = title,
        summary = summary,
        endActions = {
            if (endIcon != null) {
                Icon(
                    imageVector = endIcon,
                    contentDescription = null,
                    modifier = Modifier.size(endIconSize),
                    tint = if (enabled) {
                        MiuixTheme.colorScheme.onSurfaceVariantActions
                    } else {
                        MiuixTheme.colorScheme.disabledOnSurface
                    },
                )
            }
        },
        insideMargin = SettingsItemMargin,
        enabled = enabled,
        onClick = onClick,
    )
}

@Composable
internal fun PreferenceSwitch(
    title: String,
    summary: String?,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    SwitchPreference(
        title = title,
        summary = summary,
        checked = checked,
        enabled = enabled,
        insideMargin = SettingsItemMargin,
        onCheckedChange = onCheckedChange,
    )
}

@Composable
internal fun PreferenceDropdown(
    title: String,
    summary: String?,
    items: List<String>,
    selectedIndex: Int,
    enabled: Boolean = true,
    onSelectedIndexChange: (Int) -> Unit,
) {
    WindowDropdownPreference(
        title = title,
        summary = summary,
        items = items,
        selectedIndex = selectedIndex,
        enabled = enabled,
        insideMargin = SettingsItemMargin,
        onSelectedIndexChange = onSelectedIndexChange,
    )
}

@Composable
internal fun PreferenceSlider(
    title: String,
    value: Float,
    valueText: String,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    SliderPreference(
        title = title,
        value = value,
        valueText = valueText,
        valueRange = valueRange,
        steps = steps,
        insideMargin = SettingsItemMargin,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
    )
}

@Composable
internal fun IntegerMaterialSlider(
    title: String,
    value: Int,
    range: IntRange,
    onChangeFinished: (Int) -> Unit,
) {
    var draft by remember(value) { mutableFloatStateOf(value.toFloat()) }
    PreferenceSlider(
        title = title,
        value = draft,
        valueText = draft.toInt().toString(),
        valueRange = range.first.toFloat()..range.last.toFloat(),
        steps = (range.last - range.first - 1).coerceAtLeast(0),
        onValueChange = { draft = it.toInt().toFloat() },
        onValueChangeFinished = { onChangeFinished(draft.toInt()) },
    )
}

@Composable
internal fun DecimalMaterialSlider(
    title: String,
    value: Double,
    onChangeFinished: (Double) -> Unit,
) {
    var draft by remember(value) { mutableFloatStateOf(value.toFloat()) }
    PreferenceSlider(
        title = title,
        value = draft,
        valueText = decimalDisplay(draft),
        valueRange = -50f..50f,
        steps = 999,
        onValueChange = { draft = (it * 10).toInt() / 10f },
        onValueChangeFinished = { onChangeFinished((draft * 10).toInt() / 10.0) },
    )
}

@Composable
internal fun ColorPaletteDialog(
    show: Boolean,
    title: String,
    initialColor: Color,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
    onSave: (Color) -> Unit,
) {
    var selectedColor by remember(show, initialColor) { androidx.compose.runtime.mutableStateOf(initialColor) }
    var colorCode by remember(show, initialColor) {
        androidx.compose.runtime.mutableStateOf(initialColor.toArgbHex())
    }
    WindowDialog(show = show, title = title, onDismissRequest = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ColorPalette(
                color = selectedColor,
                onColorChanged = { newColor ->
                    selectedColor = newColor
                    colorCode = newColor.toArgbHex()
                },
                modifier = Modifier.fillMaxWidth(),
            )
            TextField(
                value = colorCode,
                onValueChange = { value ->
                    colorCode = value
                    if (HEX_COLOR.matches(value)) {
                        selectedColor = parseHexColor(value, selectedColor)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                label = stringResource(R.string.color_code),
                useLabelAsPlaceholder = true,
                singleLine = true,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(
                    text = stringResource(R.string.cancel),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                if (onDelete != null) {
                    TextButton(
                        text = stringResource(R.string.delete),
                        onClick = onDelete,
                        modifier = Modifier.weight(1f),
                    )
                }
                Button(
                    onClick = { onSave(selectedColor) },
                    modifier = Modifier.weight(1f),
                    enabled = HEX_COLOR.matches(colorCode),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text(stringResource(R.string.save))
                }
            }
        }
    }
}

@Composable
internal fun GlassSamplingDialog(
    show: Boolean,
    initialFps: Int,
    initialQuality: Int,
    onDismiss: () -> Unit,
    onSave: (fps: Int, quality: Int) -> Unit,
) {
    var fps by remember(show, initialFps) { androidx.compose.runtime.mutableIntStateOf(initialFps) }
    var quality by remember(show, initialQuality) {
        androidx.compose.runtime.mutableIntStateOf(initialQuality)
    }
    WindowDialog(
        show = show,
        title = stringResource(R.string.glass_sampling_settings),
        onDismissRequest = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            SamplingSlider(
                title = stringResource(R.string.glass_sampling_fps),
                valueText = stringResource(R.string.fps_value, fps),
                value = fps.toFloat(),
                range = 1f..90f,
                steps = 88,
                onValueChange = { fps = it.toInt() },
            )
            SamplingSlider(
                title = stringResource(R.string.glass_sampling_quality),
                valueText = stringResource(R.string.percent_value, quality),
                value = quality.toFloat(),
                range = 10f..100f,
                steps = 17,
                onValueChange = { quality = it.toInt() },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(
                    text = stringResource(R.string.cancel),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = { onSave(fps, quality) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text(stringResource(R.string.save))
                }
            }
        }
    }
}

@Composable
private fun SamplingSlider(
    title: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(text = title, modifier = Modifier.weight(1f))
            Text(text = valueText, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

internal fun parseHexColor(value: String, fallback: Color = Color.Red): Color = runCatching {
    if (!HEX_COLOR.matches(value)) return@runCatching fallback
    Color(android.graphics.Color.parseColor(value))
}.getOrDefault(fallback)

internal fun Color.toRgbHex(): String = "#%06X".format(toArgb() and 0xFFFFFF)

internal fun Color.toArgbHex(): String = "#%08X".format(toArgb())

private fun decimalDisplay(value: Float): String {
    val hundredths = String.format(Locale.ROOT, "%.2f", value)
    return if (hundredths.endsWith("0")) String.format(Locale.ROOT, "%.1f", value) else hundredths
}

private val HEX_COLOR = Regex("^#(?:[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")
