package com.adb.kitty.ui.it

import com.adb.kitty.ui.theme.*
import com.adb.kitty.ui.viewmodel.*
import com.adb.kitty.data.*
import com.adb.kitty.*
import com.adb.kitty.R

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.annotation.Keep

@Keep
enum class IntentMode(val title: String) {
    GITHUB("GitHub"),
    CUSTOM("文本分享"),
    URL_DIRECT("链接直达"),
    TELEGRAM("Telegram"),
    X("X"),
    PLAY("Play")
}

@Keep
@Composable
fun NekoIntentDialog(
    onDismiss: () -> Unit,
    onCommandSubmit: (String) -> Unit
) {
    var inputFirst by remember { mutableStateOf("") }
    var inputSecond by remember { mutableStateOf("") }
    var inputThird by remember { mutableStateOf("") }
    var targetPackage by remember { mutableStateOf("") }
    
    var currentMode by remember { mutableStateOf(IntentMode.URL_DIRECT) }
    var menuExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Intent 控制台") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .clickable { menuExpanded = true }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "当前模式: ${currentMode.title} ▾", 
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        IntentMode.values().forEach { mode ->
                            DropdownMenuItem(
                                text = { Text(mode.title) },
                                onClick = {
                                    currentMode = mode
                                    menuExpanded = false
                                    inputFirst = ""
                                    inputSecond = ""
                                    inputThird = ""
                                    targetPackage = ""
                                }
                            )
                        }
                    }
                }

                if (currentMode == IntentMode.GITHUB) {
                    OutlinedTextField(
                        value = inputFirst,
                        onValueChange = { inputFirst = it },
                        label = { Text("1. 用户名 (Username) *必填") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = inputSecond,
                        onValueChange = { inputSecond = it },
                        label = { Text("2. 仓库名 (Repository - 可选)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = inputThird,
                        onValueChange = { inputThird = it },
                        label = { Text("3. 后续路径 (如: issues/1 - 可选)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                } else {
                    val label1 = when (currentMode) {
                        IntentMode.CUSTOM -> "输入要分享的文本内容"
                        IntentMode.URL_DIRECT -> "输入完整 URL (如: https://...)"
                        IntentMode.TELEGRAM -> "Telegram 用户名或频道"
                        IntentMode.X -> "X 用户名 / 主页 ID"
                        IntentMode.PLAY -> "应用包名 (如: com.tencent.mm)"
                        else -> "输入内容"
                    }
                    OutlinedTextField(
                        value = inputFirst,
                        onValueChange = { inputFirst = it },
                        label = { Text(label1) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

                OutlinedTextField(
                    value = targetPackage,
                    onValueChange = { targetPackage = it },
                    label = { Text("目标应用包名 (可选)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (inputFirst.isNotBlank()) {
                        val finalUrlOrContent = when (currentMode) {
                            IntentMode.CUSTOM, IntentMode.URL_DIRECT -> inputFirst.trim()
                            IntentMode.GITHUB -> buildString {
                                append("https://github.com/")
                                append(inputFirst.trim())
                                if (inputSecond.isNotBlank()) append("/").append(inputSecond.trim())
                                if (inputThird.isNotBlank()) {
                                    val cleanSub = inputThird.trim().removePrefix("/")
                                    append("/").append(cleanSub)
                                }
                            }
                            IntentMode.TELEGRAM -> "https://t.me/${inputFirst.trim()}"
                            IntentMode.X -> "https://x.com/${inputFirst.trim()}"
                            IntentMode.PLAY -> "https://play.google.com/store/apps/details?id=${inputFirst.trim()}"
                        }

                        val commandText = if (targetPackage.isNotBlank()) {
                            "neko-intent $finalUrlOrContent|${targetPackage.trim()}"
                        } else {
                            "neko-intent $finalUrlOrContent"
                        }
                        
                        onCommandSubmit(commandText)
                        onDismiss()
                    }
                }
            ) {
                Text(if (currentMode == IntentMode.CUSTOM) "分享" else "跳转")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
