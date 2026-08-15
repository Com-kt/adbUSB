package com.adb.kitty.compose.ui.it

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.annotation.Keep
import com.adb.kitty.compose.ui.theme.*
import com.adb.kitty.compose.ui.viewmodel.*
import com.adb.kitty.compose.data.*
import com.adb.kitty.compose.*
import com.adb.kitty.compose.R

@Keep
@Composable
fun SignatureResultDialog(
    signatureInfo: String?,
    onDismiss: () -> Unit
) {
    if (signatureInfo != null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("APK 签名详情") },
            text = {
                SelectionContainer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = signatureInfo,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) {
                    Text("确定")
                }
            }
        )
    }
}
