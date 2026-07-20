package com.adb.kitty.compose.ui.it

import com.adb.kitty.compose.ui.theme.*
import com.adb.kitty.compose.ui.viewmodel.*
import com.adb.kitty.compose.data.*
import com.adb.kitty.compose.*
import com.adb.kitty.compose.R

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun NekoIntentDialog(
    onDismiss: () -> Unit,
    onCommandSubmit: (String) -> Unit
) {
    var shareContent by remember { mutableStateOf("") }
    var targetPackage by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Intent 控制台") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = shareContent,
                    onValueChange = { shareContent = it },
                    label = { Text("输入要分享的文本内容") },
                    modifier = Modifier.fillMaxWidth()
                )
                
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
                    if (shareContent.isNotBlank()) {
                        val commandText = if (targetPackage.isNotBlank()) {
                            "neko-intent ${shareContent.trim()}|${targetPackage.trim()}"
                        } else {
                            "neko-intent ${shareContent.trim()}"
                        }
                        
                        onCommandSubmit(commandText)
                        onDismiss()
                    }
                }
            ) {
                Text("分享")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
