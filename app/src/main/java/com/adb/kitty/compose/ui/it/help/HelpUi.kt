package com.adb.kitty.compose.ui.it.help

import com.adb.kitty.compose.ui.theme.*
import com.adb.kitty.compose.ui.viewmodel.*
import com.adb.kitty.compose.data.*
import com.adb.kitty.compose.data.help.*
import com.adb.kitty.compose.*
import com.adb.kitty.compose.R

import androidx.annotation.Keep
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@Keep
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandHelpBottomSheet(
    isVisible: Boolean,
    onDismissRequest: () -> Unit
) {
    if (!isVisible) return

    val commandList = remember {
        listOf(
            CommandHelp(
                title = "解析 APK 签名",
                command = "sig-parse <apk_path>",
                description = "分析指定 APK 的签名方案（V1-V4）与证书属性。",
                options = listOf(
                    CommandOption(
                        flag = "-l, --lineage",
                        description = "提取包含 0x3ba06f8c 属性的 V3/V3.1 密钥轮转链卡"
                    ),
                    CommandOption(
                        flag = "-v, --verbose",
                        description = "输出算法公钥明细、证书 DER 原始十六进制数据"
                    ),
                    CommandOption(
                        flag = "-j, --json",
                        description = "将解析结果格式化为 JSON 文本输出"
                    )
                )
            ),
            CommandHelp(
                title = "导出证书文件",
                command = "dump-cert <apk_path>",
                description = "从 APK 签名块中提取原始 X.509 数字证书。",
                options = listOf(
                    CommandOption(
                        flag = "-o, --out <dir>",
                        description = "指定导出证书保存的目标文件夹路径"
                    )
                )
            ),
            CommandHelp(
                title = "清空控制台与缓存",
                command = "clear-log",
                description = "重置日志面板，并清理系统缓存目录下的临时解析文件。",
                options = emptyList()
            )
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = Modifier.fillMaxHeight(0.85f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Terminal,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(
                    text = "指令集使用说明",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(commandList) { help ->
                    CommandHelpItem(help = help)
                }

                item {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                }

                item {
                    SupportAndDependencySection()
                }
            }
        }
    }
}

@Keep
@Composable
private fun CommandHelpItem(help: CommandHelp) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Text(
                text = help.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(6.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = help.command,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = help.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (help.options.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = "可选参数选项：",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )

                Spacer(modifier = Modifier.height(4.dp))

                help.options.forEachIndexed { index, option ->
                    val isLast = index == help.options.lastIndex
                    val prefixBranch = if (isLast) "└─ " else "├─ "

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 4.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = prefixBranch,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = option.flag,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.secondary
                            )
                            Text(
                                text = option.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Keep
@Composable
private fun SupportAndDependencySection() {
    OutlinedCard(
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.secondary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "依赖信息与支持范围",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            InfoRow(label = "系统支持", value = "Android 7.0 - Android 17")
            InfoRow(label = "架构支持", value = "arm64-v8a, armeabi-v7a, x86, x86_64, riscv64")
            InfoRow(label = "签名方案", value = "V2 + V3 + V3.1 + V3.2")
            InfoRow(label = "核心依赖", value = "Jetpack Compose, Material3, OpenSSL")
            InfoRow(label = "文件格式", value = ".apk, .apks")
        }
    )
}

@Keep
@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = FontFamily.Monospace
        )
    }
}
