package com.miui.appvolumebar.ui

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.miui.appvolumebar.MainHook
import com.miui.appvolumebar.status.HookItem
import com.miui.appvolumebar.status.HookState
import com.miui.appvolumebar.status.HookStatusProvider
import com.miui.appvolumebar.status.ModuleStatus
import com.miui.appvolumebar.status.PackageStatusReport
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val controller = remember { ThemeController(ColorSchemeMode.System) }
            MiuixTheme(controller = controller) {
                MainScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        queryHookStatus(this)
    }

    companion object {
        fun queryHookStatus(context: Context) {
            try {
                val queryIntent = Intent(MainHook.ACTION_QUERY_HOOK_STATUS)
                context.sendBroadcast(queryIntent)
            } catch (_: Throwable) {}
        }
    }
}

@Composable
fun MainScreen() {
    val context = LocalContext.current
    var isModuleActive by remember { mutableStateOf(ModuleStatus.isModuleActive()) }
    var systemUiReport by remember { mutableStateOf(HookStatusProvider.getReport(context, MainHook.PKG_SYSTEMUI)) }
    var misoundReport by remember { mutableStateOf(HookStatusProvider.getReport(context, MainHook.PKG_MISOUND)) }

    // 注册跨进程广播与 Provider 状态监听器
    DisposableEffect(Unit) {
        val listener = { report: PackageStatusReport ->
            when (report.packageName) {
                MainHook.PKG_SYSTEMUI -> systemUiReport = report
                MainHook.PKG_MISOUND -> misoundReport = report
            }
        }
        HookStatusProvider.addListener(listener)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == MainHook.ACTION_REPORT_HOOK_STATUS) {
                    val pkg = intent.getStringExtra(HookStatusProvider.EXTRA_PACKAGE_NAME)
                    val jsonStr = intent.getStringExtra(HookStatusProvider.EXTRA_REPORT_JSON)
                    if (jsonStr != null) {
                        try {
                            val report = PackageStatusReport.fromJson(JSONObject(jsonStr))
                            when (pkg) {
                                MainHook.PKG_SYSTEMUI -> systemUiReport = report
                                MainHook.PKG_MISOUND -> misoundReport = report
                            }
                        } catch (_: Throwable) {}
                    }
                }
            }
        }

        val filter = IntentFilter(MainHook.ACTION_REPORT_HOOK_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }

        onDispose {
            HookStatusProvider.removeListener(listener)
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Throwable) {}
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = "分应用音量增强"
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            // 1. 模块激活状态总览与快速操作卡片
            ModuleActivationCard(
                isModuleActive = isModuleActive,
                onRefresh = {
                    isModuleActive = ModuleStatus.isModuleActive()
                    systemUiReport = HookStatusProvider.getReport(context, MainHook.PKG_SYSTEMUI)
                    misoundReport = HookStatusProvider.getReport(context, MainHook.PKG_MISOUND)
                    MainActivity.queryHookStatus(context)
                    Toast.makeText(context, "已发送状态刷新请求", Toast.LENGTH_SHORT).show()
                },
                onCopyDiagnostic = {
                    val text = generateDiagnosticReport(isModuleActive, systemUiReport, misoundReport)
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("VolumeHook_Diagnostic", text))
                    Toast.makeText(context, "诊断信息已复制到剪贴板", Toast.LENGTH_SHORT).show()
                }
            )

            Spacer(modifier = Modifier.height(20.dp))

            // 2. 系统界面 Hook 状态卡片
            SmallTitle(text = "系统界面 (com.android.systemui)")
            PackageHookCard(
                packageName = MainHook.PKG_SYSTEMUI,
                displayName = "系统界面",
                report = systemUiReport,
                notReportedHint = "未收到 SystemUI Hook 状态上报。\n请确保在 LSPosed 中勾选了「系统界面」，并在按一次音量键唤醒音量条后点击「刷新状态」。"
            )

            Spacer(modifier = Modifier.height(20.dp))

            // 3. 声音助手 Hook 状态卡片
            SmallTitle(text = "声音助手 (com.miui.misound)")
            PackageHookCard(
                packageName = MainHook.PKG_MISOUND,
                displayName = "声音助手",
                report = misoundReport,
                notReportedHint = "未收到声音助手 Hook 状态上报。\n请确保在 LSPosed 中勾选了「声音助手」，并播放媒体音频触发分应用服务后点击「刷新状态」。"
            )

            Spacer(modifier = Modifier.height(20.dp))

            // 4. 原模块信息与运行机制卡片
            SmallTitle(text = "模块信息")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "作用域",
                        style = MiuixTheme.textStyles.title4
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "系统界面 (com.android.systemui)\n声音助手 (com.miui.misound)",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "运行机制",
                        style = MiuixTheme.textStyles.title4
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "1. 拦截声音助手在屏幕左侧弹出的蓝色悬浮球\n2. 在右侧音量条（静音/勿扰下方）动态插入毛玻璃圆形入口\n3. 仅在有第三方应用活跃播放媒体音频时显示该入口\n4. 点击入口平滑调起多应用音量调节面板",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 5. 使用说明卡片
            SmallTitle(text = "使用说明")
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "激活方式",
                        style = MiuixTheme.textStyles.title4
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "请在 LSPosed 管理器中启用本模块，勾选「系统界面」与「声音助手」两个作用域，然后重启系统界面或手机生效。",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }
            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}

@Composable
fun ModuleActivationCard(
    isModuleActive: Boolean,
    onRefresh: () -> Unit,
    onCopyDiagnostic: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val dotColor = if (isModuleActive) Color(0xFF4CAF50) else Color(0xFFE53935)
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isModuleActive) "LSPosed 模块已激活" else "模块未在 LSPosed 中激活",
                    style = MiuixTheme.textStyles.title4
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (isModuleActive) {
                    "模块运行正常，下面展示当前系统界面与声音助手的具体 Hook 状态与版本兼容情况。"
                } else {
                    "未检测到模块激活。请在 LSPosed 作用域中勾选「分应用音量增强」，并在重启系统界面后测试。"
                },
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onRefresh,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary()
                ) {
                    Text("刷新状态")
                }
                Button(
                    onClick = onCopyDiagnostic,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors()
                ) {
                    Text("复制诊断信息")
                }
            }
        }
    }
}

@Composable
fun PackageHookCard(
    packageName: String,
    displayName: String,
    report: PackageStatusReport?,
    notReportedHint: String
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (report == null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFFB8C00))
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "未收到上报",
                        style = MiuixTheme.textStyles.title4,
                        color = Color(0xFFFB8C00)
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = notReportedHint,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "版本: ${report.appVersionName ?: "未知"} (${report.appVersionCode})",
                            style = MiuixTheme.textStyles.title4
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "PID: ${report.pid} · 上报时间: ${formatTime(report.reportTime)}",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }

                    val successCount = report.items.count { it.status == HookState.SUCCESS }
                    val totalCount = report.items.size
                    val summaryColor = if (successCount == totalCount) Color(0xFF4CAF50) else Color(0xFFFB8C00)

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(summaryColor.copy(alpha = 0.15f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "$successCount / $totalCount 正常",
                            color = summaryColor,
                            style = MiuixTheme.textStyles.footnote1
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(4.dp))

                report.items.forEachIndexed { index, item ->
                    HookItemRow(item = item)
                    if (index < report.items.size - 1) {
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
fun HookItemRow(item: HookItem) {
    val (statusText, statusColor, bgColor) = when (item.status) {
        HookState.SUCCESS -> Triple("成功", Color(0xFF4CAF50), Color(0x1F4CAF50))
        HookState.NOT_FOUND -> Triple("未找到", Color(0xFFFB8C00), Color(0x1FFB8C00))
        HookState.FAILED -> Triple("失败", Color(0xFFE53935), Color(0x1FE53935))
        HookState.WAITING -> Triple("等待", Color(0xFF9E9E9E), Color(0x1F9E9E9E))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = item.name,
                style = MiuixTheme.textStyles.title4,
                modifier = Modifier.weight(1f, fill = false)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(bgColor)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = statusText,
                    color = statusColor,
                    style = MiuixTheme.textStyles.footnote1
                )
            }
        }
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = "${item.targetClass.substringAfterLast('.')}#${item.targetMethod}",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
        if (item.invokeCount > 0) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "已触发 ${item.invokeCount} 次 · 最近调用: ${formatTime(item.lastInvokeTime)}",
                style = MiuixTheme.textStyles.footnote2,
                color = Color(0xFF4CAF50)
            )
        }
        if (!item.detail.isNullOrEmpty()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = item.detail,
                style = MiuixTheme.textStyles.footnote2,
                color = if (item.status == HookState.FAILED || item.status == HookState.NOT_FOUND) statusColor else MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
    }
}

fun formatTime(timestamp: Long): String {
    if (timestamp <= 0L) return "从未"
    val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

fun generateDiagnosticReport(
    isModuleActive: Boolean,
    systemUiReport: PackageStatusReport?,
    misoundReport: PackageStatusReport?
): String {
    val sb = StringBuilder()
    sb.appendLine("=== 分应用音量增强 诊断日志 ===")
    sb.appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
    sb.appendLine("系统版本: Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
    sb.appendLine("LSPosed 模块自激活检测: ${if (isModuleActive) "正常 (Active)" else "未激活 (Inactive)"}")
    sb.appendLine()
    sb.appendLine("--- 系统界面 (SystemUI) ---")
    if (systemUiReport == null) {
        sb.appendLine("状态: 未收到上报数据 (请确认作用域勾选并按音量键唤醒音量条)")
    } else {
        sb.appendLine("应用版本: ${systemUiReport.appVersionName ?: "未知"} (Code: ${systemUiReport.appVersionCode})")
        sb.appendLine("PID: ${systemUiReport.pid} | 上报时间: ${formatTime(systemUiReport.reportTime)}")
        sb.appendLine("Hook 详情 (${systemUiReport.items.size} 项):")
        for (item in systemUiReport.items) {
            val statusStr = when (item.status) {
                HookState.SUCCESS -> "[成功]"
                HookState.FAILED -> "[失败]"
                HookState.NOT_FOUND -> "[未找到]"
                HookState.WAITING -> "[等待中]"
            }
            sb.appendLine("  $statusStr ${item.name} (${item.targetClass}#${item.targetMethod})")
            if (item.invokeCount > 0) {
                sb.appendLine("    调用: ${item.invokeCount} 次 (上次: ${formatTime(item.lastInvokeTime)})")
            }
            if (!item.detail.isNullOrEmpty()) {
                sb.appendLine("    详情: ${item.detail}")
            }
        }
    }
    sb.appendLine()
    sb.appendLine("--- 声音助手 (MiSound) ---")
    if (misoundReport == null) {
        sb.appendLine("状态: 未收到上报数据 (请确认作用域勾选并播放媒体音频)")
    } else {
        sb.appendLine("应用版本: ${misoundReport.appVersionName ?: "未知"} (Code: ${misoundReport.appVersionCode})")
        sb.appendLine("PID: ${misoundReport.pid} | 上报时间: ${formatTime(misoundReport.reportTime)}")
        sb.appendLine("Hook 详情 (${misoundReport.items.size} 项):")
        for (item in misoundReport.items) {
            val statusStr = when (item.status) {
                HookState.SUCCESS -> "[成功]"
                HookState.FAILED -> "[失败]"
                HookState.NOT_FOUND -> "[未找到]"
                HookState.WAITING -> "[等待中]"
            }
            sb.appendLine("  $statusStr ${item.name} (${item.targetClass}#${item.targetMethod})")
            if (item.invokeCount > 0) {
                sb.appendLine("    调用: ${item.invokeCount} 次 (上次: ${formatTime(item.lastInvokeTime)})")
            }
            if (!item.detail.isNullOrEmpty()) {
                sb.appendLine("    详情: ${item.detail}")
            }
        }
    }
    return sb.toString()
}
