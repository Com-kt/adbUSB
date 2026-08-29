package com.adb.kitty.compose.ui.it

import com.adb.kitty.compose.ui.theme.*
import com.adb.kitty.compose.ui.viewmodel.*
import com.adb.kitty.compose.data.*
import com.adb.kitty.compose.ui.it.help.*
import com.adb.kitty.compose.*
import com.adb.kitty.compose.service.*
import com.adb.kitty.compose.R

import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SettingsBackupRestore
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FabPosition
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.annotation.Keep
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> CommandInputSection(
    query: TextFieldValue,
    onQueryChange: (TextFieldValue) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    filteredItems: List<T>,
    getItemCommand: (T) -> String,
    getItemDescription: (T) -> String,
    isAppItem: (T) -> Boolean,
    isAdbItem: (T) -> Boolean,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.wrapContentHeight()) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = onExpandedChange
        ) {
            val interactionSource = remember { MutableInteractionSource() }

            OutlinedTextFieldDefaults.DecorationBox(
                value = query.text,
                innerTextField = {
                    AndroidView(
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable, enabled = true),
                        factory = { context ->
                            EditText(context).apply {
                                background = null
                                setPadding(0, 0, 0, 0)
                                
                                // 1. 明确设置多行文本输入类型，使得 maxLines 生效
                                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                                maxLines = 3
                                isSingleLine = false
                                textSize = 16f
                                
                                // 2. 启用内部垂直滚动支持
                                movementMethod = ScrollingMovementMethod.getInstance()
                                setHorizontallyScrolling(false)

                                addTextChangedListener(object : TextWatcher {
                                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                                        val newText = s?.toString() ?: ""
                                        if (newText != query.text) {
                                            // 保持当前光标位置，避免强制重置到末尾引起的卡顿与输入异常
                                            val selectionStart = selectionStart
                                            val selectionEnd = selectionEnd
                                            onQueryChange(
                                                TextFieldValue(
                                                    text = newText,
                                                    selection = TextRange(selectionStart, selectionEnd)
                                                )
                                            )
                                            onExpandedChange(newText.isNotEmpty())
                                        }
                                    }
                                    override fun afterTextChanged(s: Editable?) {}
                                })
                            }
                        },
                        update = { editText ->
                            if (editText.text.toString() != query.text) {
                                editText.setText(query.text)
                                editText.setSelection(query.text.length.coerceAtMost(editText.text?.length ?: 0))
                            }
                        }
                    )
                },
                enabled = true,
                singleLine = false,
                visualTransformation = VisualTransformation.None,
                interactionSource = interactionSource,
                isError = false,
                label = {
                    Text(stringResource(R.string.action_menu_sospl))
                },
                colors = OutlinedTextFieldDefaults.colors(),
                contentPadding = OutlinedTextFieldDefaults.contentPaddingWithLabel()
            )

            ExposedDropdownMenu(
                expanded = expanded && filteredItems.isNotEmpty(),
                onDismissRequest = { onExpandedChange(false) }
            ) {
                filteredItems.forEach { item ->
                    val command = getItemCommand(item)
                    val description = getItemDescription(item)
                    val isApp = isAppItem(item)
                    val isAdb = isAdbItem(item)

                    DropdownMenuItem(
                        text = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(vertical = 4.dp)
                                        .padding(end = 8.dp)
                                ) {
                                    Text(
                                        text = command,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )

                                    Spacer(modifier = Modifier.height(2.dp))

                                    Text(
                                        text = description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    text = when {
                                        isApp -> "[APP]"
                                        isAdb -> "[ADB]"
                                        else -> "[Fastboot]"
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = when {
                                        isApp -> MaterialTheme.colorScheme.secondary
                                        isAdb -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.error
                                    },
                                    modifier = Modifier.wrapContentWidth()
                                )
                            }
                        },
                        onClick = {
                            onQueryChange(
                                TextFieldValue(
                                    text = command,
                                    selection = TextRange(command.length)
                                )
                            )
                            onExpandedChange(false)
                        },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                    )
                }
            }
        }
    }
}
