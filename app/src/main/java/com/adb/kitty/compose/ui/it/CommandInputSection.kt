package com.adb.kitty.compose.ui.it

import com.adb.kitty.compose.ui.theme.*
import com.adb.kitty.compose.ui.viewmodel.*
import com.adb.kitty.compose.data.*
import com.adb.kitty.compose.ui.it.help.*
import com.adb.kitty.compose.*
import com.adb.kitty.compose.service.*
import com.adb.kitty.compose.R

import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.method.ScrollingMovementMethod
import android.widget.EditText
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.receiveAsFlow
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
    val interactionSource = remember { MutableInteractionSource() }
    val coroutineScope = rememberCoroutineScope()
    var focusInteraction by remember { mutableStateOf<FocusInteraction.Focus?>(null) }

    // 1. 动态存储由 EditText 原生计算出来的 3 行最大高度 (Dp)
    var max3LinesHeightDp by remember { mutableStateOf<Dp?>(null) }

    // Channel.UNLIMITED 防抖通道
    val searchChannel = remember { Channel<String>(Channel.UNLIMITED) }

    LaunchedEffect(searchChannel) {
        searchChannel.receiveAsFlow()
            .debounce(150)
            .collect { latestText ->
                onExpandedChange(latestText.isNotEmpty())
            }
    }

    Box(modifier = modifier.wrapContentHeight()) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = onExpandedChange
        ) {
            OutlinedTextFieldDefaults.DecorationBox(
                value = query.text,
                innerTextField = {
                    // 2. 将动态计算出的最大 3 行高度作为约束应用在 Compose 容器上
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (max3LinesHeightDp != null) {
                                    Modifier.heightIn(max = max3LinesHeightDp!!)
                                } else {
                                    Modifier // 首次测量前保持自然 wrapContent
                                }
                            )
                    ) {
                        AndroidView(
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable, enabled = false),
                            factory = { context ->
                                EditText(context).apply {
                                    background = null
                                    setPadding(0, 0, 0, 0)
                                    
                                    setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
                                    isSingleLine = false
                                    textSize = 16f
                                    
                                    movementMethod = ScrollingMovementMethod.getInstance()
                                    setHorizontallyScrolling(false)

                                    isLongClickable = true
                                    setTextIsSelectable(true)

                                    // 3. 布局加载完成后，准确获取原生行高并计算 3 行对应 Compose 的 Dp 值
                                    post {
                                        val density = context.resources.displayMetrics.density
                                        // 3 行行高 + 内边距
                                        val totalMaxPx = (lineHeight * 3) + paddingTop + paddingBottom
                                        val calculatedDp = (totalMaxPx / density).dp
                                        if (max3LinesHeightDp != calculatedDp) {
                                            max3LinesHeightDp = calculatedDp
                                        }
                                    }

                                    setOnFocusChangeListener { _, hasFocus ->
                                        coroutineScope.launch {
                                            if (hasFocus) {
                                                if (focusInteraction == null) {
                                                    val focus = FocusInteraction.Focus()
                                                    focusInteraction = focus
                                                    interactionSource.emit(focus)
                                                }
                                            } else {
                                                focusInteraction?.let { focus ->
                                                    interactionSource.emit(FocusInteraction.Unfocus(focus))
                                                    focusInteraction = null
                                                }
                                            }
                                        }
                                    }

                                    addTextChangedListener(object : TextWatcher {
                                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                                            val newText = s?.toString() ?: ""
                                            if (newText != query.text) {
                                                val currentStart = selectionStart
                                                val currentEnd = selectionEnd
                                                
                                                onQueryChange(
                                                    TextFieldValue(
                                                        text = newText,
                                                        selection = TextRange(currentStart, currentEnd)
                                                    )
                                                )
                                                
                                                searchChannel.trySend(newText)
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
                    }
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
                expanded = expanded && query.text.isNotEmpty() && filteredItems.isNotEmpty(),
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
