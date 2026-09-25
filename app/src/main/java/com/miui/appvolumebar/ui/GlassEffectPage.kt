package com.miui.appvolumebar.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.miui.appvolumebar.R
import com.miui.appvolumebar.glass.GlassMaterialConfig
import com.miui.appvolumebar.glass.GlassMaterialType
import com.miui.appvolumebar.glass.GlassOptions
import com.miui.appvolumebar.glass.GlassPrefs
import com.miui.appvolumebar.glass.GlassSettings
import com.miui.appvolumebar.utils.HyperOsVersionUtil
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import kotlin.math.roundToInt

/**
 * 「玻璃效果」二级设置页。
 *
 * 设置项与 HyperIsland「外观 - 背景 - 玻璃效果」一一对应：材质类型、模糊强度、
 * 玻璃效果自定义（边缘宽度 / 折射 / 高光 / 暗边 / 光源方向 / 色散）、柔光玻璃的
 * 光影 / 折射 / 背景、混色、陀螺仪光效、HDR 高光、采样设置，以及配置的
 * 恢复默认 / 导出 / 导入。区别只在于：超级岛有「大岛 / 小岛 / 展开态」三份配置，
 * 音量面板只有一份。
 */
@Composable
fun GlassEffectPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior()
    val snackbarState = remember { SnackbarHostState() }
    var settings by remember { mutableStateOf(GlassPrefs.load(context)) }
    var colorDialog by remember { mutableStateOf(false) }
    var samplingDialog by remember { mutableStateOf(false) }
    var resetDialog by remember { mutableStateOf(false) }

    // HyperOS 3 没有 Bionics 柔光玻璃运行时，Type 下拉里直接不给出「柔光玻璃」，
    // 并且把已经保存过的柔光玻璃配置回落成默认材质（与 HyperIsland 处理一致）。
    val hyperOsMajor = remember { HyperOsVersionUtil.getMajorVersion() }
    val softGlassAllowed = hyperOsMajor != 3
    LaunchedEffect(hyperOsMajor) {
        if (!softGlassAllowed && settings.config.isSoftGlass) {
            settings = GlassPrefs.reset(context)
        }
    }

    val config = settings.config
    val options = settings.options
    val hasGlass = config.isEdgeGlass || config.isSoftGlass
    val hasLiquid = config.isTrueRefraction
    val availableTypes = remember(softGlassAllowed) {
        GlassMaterialType.entries.filter { softGlassAllowed || it != GlassMaterialType.SoftGlass }
    }

    val copied = stringResource(R.string.material_config_copied)
    val clipboardEmpty = stringResource(R.string.import_empty_clipboard)
    val importSuccess = stringResource(R.string.material_import_success)
    val importFailed = stringResource(R.string.import_unknown_error)
    val softUnsupported = stringResource(R.string.material_soft_glass_os4_only)

    fun save(next: GlassMaterialConfig) {
        settings = GlassPrefs.saveMaterial(context, settings, next)
    }

    fun saveOptions(next: GlassOptions) {
        settings = GlassPrefs.saveOptions(context, settings, next)
    }

    fun showMessage(message: String) {
        scope.launch { snackbarState.showSnackbar(message) }
    }

    val clipboard = remember(context) {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = stringResource(R.string.glass_effect),
                    largeTitle = stringResource(R.string.glass_effect),
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(IconArrowBack, stringResource(R.string.back))
                        }
                    },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarState) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                end = 16.dp,
                bottom = 28.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionTitle(stringResource(R.string.glass_effect))
                Card(modifier = Modifier.fillMaxWidth()) {
                    PreferenceDropdown(
                        title = stringResource(R.string.material_type),
                        summary = null,
                        items = availableTypes.map { materialTypeLabel(it) },
                        selectedIndex = availableTypes.indexOf(config.type).coerceAtLeast(0),
                    ) { index ->
                        save(config.withType(availableTypes[index]))
                    }
                }
            }

            if (config.isCustom) {
                item {
                    SectionTitle(stringResource(R.string.material_blur_section))
                    Card(modifier = Modifier.fillMaxWidth()) {
                        IntegerMaterialSlider(
                            title = stringResource(R.string.material_blur),
                            value = config.blur,
                            range = 0..100,
                        ) { save(config.copy(blur = it)) }
                    }
                }
            }

            if (config.isEdgeGlass) {
                item {
                    SectionTitle(stringResource(R.string.glass_customize))
                    Card(modifier = Modifier.fillMaxWidth()) {
                        IntegerMaterialSlider(
                            stringResource(R.string.glass_edge_width),
                            config.edgeThickness,
                            4..40,
                        ) { save(config.copy(edgeThickness = it)) }
                        IntegerMaterialSlider(
                            stringResource(R.string.glass_refraction),
                            config.refraction,
                            0..40,
                        ) { save(config.copy(refraction = it)) }
                        IntegerMaterialSlider(
                            stringResource(R.string.glass_highlight),
                            config.reflectionStrength,
                            0..100,
                        ) { save(config.copy(reflectionStrength = it)) }
                        IntegerMaterialSlider(
                            stringResource(R.string.glass_shadow),
                            config.darker,
                            0..100,
                        ) { save(config.copy(darker = it)) }
                        IntegerMaterialSlider(
                            stringResource(R.string.glass_light_direction),
                            config.lightDirection,
                            0..359,
                        ) { save(config.copy(lightDirection = it)) }
                        IntegerMaterialSlider(
                            stringResource(R.string.glass_dispersion),
                            config.dispersion,
                            0..100,
                        ) { save(config.copy(dispersion = it)) }
                    }
                }
            }

            if (config.isSoftGlass) {
                softGlassSections(config, ::save)
            }

            if (config.isCustom) {
                item {
                    SectionTitle(stringResource(R.string.material_blend_section))
                    Card(modifier = Modifier.fillMaxWidth()) {
                        SettingsAction(
                            title = stringResource(R.string.material_blend_color),
                            summary = config.toPickerColor().toArgbHex(),
                            endIcon = IconChevronRight,
                        ) { colorDialog = true }
                        if (config.isSoftGlass) {
                            PreferenceSwitch(
                                title = stringResource(R.string.material_highlight_switch),
                                summary = null,
                                checked = config.highlight,
                            ) { save(config.copy(highlight = it)) }
                        }
                    }
                }
            }

            item {
                SectionTitle(stringResource(R.string.glass_effect))
                Card(modifier = Modifier.fillMaxWidth()) {
                    PreferenceSwitch(
                        title = stringResource(R.string.glass_gyroscope),
                        summary = stringResource(R.string.glass_gyroscope_summary),
                        checked = options.gyroscope,
                        enabled = hasGlass,
                    ) { saveOptions(options.copy(gyroscope = it)) }
                    PreferenceSwitch(
                        title = stringResource(R.string.glass_hdr),
                        summary = stringResource(R.string.glass_hdr_summary),
                        checked = options.hdrHighlight,
                        enabled = hasGlass,
                    ) { saveOptions(options.copy(hdrHighlight = it)) }
                    SettingsAction(
                        title = stringResource(R.string.glass_sampling_settings),
                        summary = stringResource(
                            if (hasLiquid) {
                                R.string.glass_sampling_summary
                            } else {
                                R.string.glass_enable_liquid_first
                            },
                        ),
                        endIcon = if (hasLiquid) IconChevronRight else null,
                        enabled = hasLiquid,
                    ) { if (hasLiquid) samplingDialog = true }
                }
            }

            item {
                SectionTitle(stringResource(R.string.glass_effect_scope))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                        Text(
                            text = stringResource(R.string.glass_effect_scope_summary),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }

            item {
                SectionTitle(stringResource(R.string.glass_effect_hint))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                        Text(
                            text = stringResource(R.string.glass_effect_hint),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }

            item {
                SectionTitle(stringResource(R.string.restore_default))
                Card(modifier = Modifier.fillMaxWidth()) {
                    SettingsAction(
                        title = stringResource(R.string.restore_default),
                        summary = null,
                    ) { resetDialog = true }
                    SettingsAction(
                        title = stringResource(R.string.export_to_clipboard),
                        summary = null,
                    ) {
                        clipboard?.setPrimaryClip(
                            ClipData.newPlainText(
                                context.getString(R.string.glass_effect),
                                GlassPrefs.export(settings),
                            ),
                        )
                        showMessage(copied)
                    }
                    SettingsAction(
                        title = stringResource(R.string.import_from_clipboard),
                        summary = null,
                    ) {
                        val raw = clipboard?.primaryClip
                            ?.getItemAt(0)
                            ?.coerceToText(context)
                            ?.toString()
                            ?.trim()
                            .orEmpty()
                        if (raw.isBlank()) {
                            showMessage(clipboardEmpty)
                        } else {
                            runCatching { GlassPrefs.import(context, raw, softGlassAllowed) }
                                .onSuccess { settings = it; showMessage(importSuccess) }
                                .onFailure {
                                    showMessage(
                                        if (it.message == GlassPrefs.SOFT_GLASS_UNSUPPORTED) {
                                            softUnsupported
                                        } else {
                                            importFailed
                                        },
                                    )
                                }
                        }
                    }
                }
            }
        }
    }

    if (colorDialog) {
        val defaults = GlassMaterialConfig()
        ColorPaletteDialog(
            show = true,
            title = stringResource(R.string.material_blend_color),
            initialColor = config.toPickerColor(),
            onDismiss = { colorDialog = false },
            onDelete = {
                save(
                    config.copy(
                        blendColor = defaults.blendColor,
                        blendOpacity = defaults.blendOpacity,
                    ),
                )
                colorDialog = false
            },
        ) { color ->
            save(
                config.copy(
                    blendColor = color.toRgbHex(),
                    blendOpacity = (color.alpha * 100f).roundToInt().coerceIn(0, 100),
                ),
            )
            colorDialog = false
        }
    }

    GlassSamplingDialog(
        show = samplingDialog,
        initialFps = options.captureFps,
        initialQuality = options.captureQuality,
        onDismiss = { samplingDialog = false },
    ) { fps, quality ->
        saveOptions(options.copy(captureFps = fps, captureQuality = quality))
        samplingDialog = false
    }

    WindowDialog(
        show = resetDialog,
        title = stringResource(R.string.restore_default),
        onDismissRequest = { resetDialog = false },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                text = stringResource(R.string.restore_default_config_question),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(
                    text = stringResource(R.string.cancel),
                    onClick = { resetDialog = false },
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = { settings = GlassPrefs.reset(context); resetDialog = false },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) { Text(stringResource(R.string.confirm)) }
            }
        }
    }
}

private fun LazyListScope.softGlassSections(
    config: GlassMaterialConfig,
    save: (GlassMaterialConfig) -> Unit,
) {
    item {
        SectionTitle(stringResource(R.string.material_lighting_section))
        Card(modifier = Modifier.fillMaxWidth()) {
            DecimalMaterialSlider(stringResource(R.string.material_soft_light), config.softLight) {
                save(config.copy(softLight = it))
            }
            DecimalMaterialSlider(stringResource(R.string.material_saturation), config.saturation) {
                save(config.copy(saturation = it))
            }
            DecimalMaterialSlider(stringResource(R.string.material_brightness), config.brightness) {
                save(config.copy(brightness = it))
            }
            DecimalMaterialSlider(stringResource(R.string.material_darker), config.softDarker) {
                save(config.copy(softDarker = it))
            }
            DecimalMaterialSlider(stringResource(R.string.material_transparency), config.transparency) {
                save(config.copy(transparency = it))
            }
            DecimalMaterialSlider(stringResource(R.string.material_burn), config.burn) {
                save(config.copy(burn = it))
            }
        }
    }
    item {
        SectionTitle(stringResource(R.string.material_refraction_section))
        Card(modifier = Modifier.fillMaxWidth()) {
            DecimalMaterialSlider(stringResource(R.string.material_refraction), config.softRefraction) {
                save(config.copy(softRefraction = it))
            }
            DecimalMaterialSlider(stringResource(R.string.material_edge_thickness), config.softEdgeThickness) {
                save(config.copy(softEdgeThickness = it))
            }
            DecimalMaterialSlider(stringResource(R.string.material_reflection_strength), config.softReflection) {
                save(config.copy(softReflection = it))
            }
            DecimalMaterialSlider(
                stringResource(R.string.material_directional_light),
                config.directionalLightIntensity,
            ) {
                save(config.copy(directionalLightIntensity = it))
            }
        }
    }
    item {
        SectionTitle(stringResource(R.string.material_background_section))
        Card(modifier = Modifier.fillMaxWidth()) {
            DecimalMaterialSlider(
                stringResource(R.string.material_background_saturation),
                config.backgroundSaturation,
            ) {
                save(config.copy(backgroundSaturation = it))
            }
            DecimalMaterialSlider(
                stringResource(R.string.material_background_brightness),
                config.backgroundBrightness,
            ) {
                save(config.copy(backgroundBrightness = it))
            }
        }
    }
}

/** LazyListScope 内没有 Composable 上下文，这里用 LocalContext 直接取字符串。 */
@Composable
private fun stringResource(id: Int): String = stringResource(id)

@Composable
private fun materialTypeLabel(type: GlassMaterialType): String = stringResource(
    when (type) {
        GlassMaterialType.Default -> R.string.material_default
        GlassMaterialType.Gaussian -> R.string.material_gaussian
        GlassMaterialType.HighlightGlass -> R.string.material_highlight_glass
        GlassMaterialType.LiquidGlass -> R.string.material_liquid_glass
        GlassMaterialType.SoftGlass -> R.string.material_soft_glass
    },
)

private fun GlassMaterialConfig.toPickerColor(): Color =
    parseHexColor(blendColor, Color.Black).copy(alpha = blendOpacity.coerceIn(0, 100) / 100f)
