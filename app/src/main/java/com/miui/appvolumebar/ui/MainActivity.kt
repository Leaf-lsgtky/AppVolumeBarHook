package com.miui.appvolumebar.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val controller = remember { ThemeController(ColorSchemeMode.System) }
            MiuixTheme(controller = controller) {
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
                                    text = "1. 拦截声音助手在屏幕左侧弹出的蓝色悬浮球\n2. 在右侧音量条（静音/勿扰下方）动态插入毛玻璃圆形入口\n3. 仅在有第三方应用活跃播放媒体音频时显示该入口",
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))
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
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}
