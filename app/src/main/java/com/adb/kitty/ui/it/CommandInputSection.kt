package com.adb.kitty.ui.it

import com.adb.kitty.ui.theme.*
import com.adb.kitty.ui.viewmodel.*
import com.adb.kitty.data.*
import com.adb.kitty.ui.it.help.*
import com.adb.kitty.*
import com.adb.kitty.service.*
import com.adb.kitty.R

import android.annotation.SuppressLint
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.text.method.ScrollingMovementMethod
import android.view.MotionEvent
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

@SuppressLint("ClickableViewAccessibility")
@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
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

    val searchChannel = remember { Channel<String>(Channel.UNLIMITED) }

    LaunchedEffect(searchChannel) {
        searchChannel.receiveAsFlow()
            .debounce(150)
            .collect { latestText ->
                onExpandedChange(latestText.isNotEmpty())
            }
    }

    val displayItems = remember(filteredItems) { filteredItems.take(20) }

    Box(modifier = modifier.wrapContentHeight()) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = onExpandedChange
        ) {
            OutlinedTextFieldDefaults.DecorationBox(
                value = query.text,
                innerTextField = {
                    AndroidView(
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable, enabled = false),
                        factory = { context ->
                            EditText(context).apply {
                                background = null
                                setPadding(0, 0, 0, 0)

                                setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
                                maxLines = 3
                                isSingleLine = false
                                textSize = 16f
                                
                                overScrollMode = android.view.View.OVER_SCROLL_NEVER

                                // 32KB 缓冲区（16384 个 UTF-16 字符）
                                filters = arrayOf(InputFilter.LengthFilter(16384))

                                // 准确锁定 3 行最大像素高度
                                post {
                                    if (lineHeight > 0) {
                                        maxHeight = lineHeight * 3 + compoundPaddingTop + compoundPaddingBottom
                                    }
                                }

                                movementMethod = ScrollingMovementMethod.getInstance()
                                isVerticalScrollBarEnabled = false
                                setHorizontallyScrolling(false)

                                isLongClickable = true
                                setTextIsSelectable(true)

                                setOnTouchListener { view, event ->
                                    when (event.actionMasked) {
                                        MotionEvent.ACTION_DOWN -> {
                                            view.parent?.requestDisallowInterceptTouchEvent(true)
                                        }
                                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                            view.parent?.requestDisallowInterceptTouchEvent(false)
                                        }
                                    }
                                    false
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

                                    override fun afterTextChanged(s: Editable?) {
                                        // 核心修复：回车或新增行时，立即计算光标位置并强行将视口滚动到当前光标所在行
                                        post {
                                            layout?.let { l ->
                                                val sel = selectionStart
                                                if (sel >= 0) {
                                                    val line = l.getLineForOffset(sel)
                                                    val lineBottom = l.getLineBottom(line)
                                                    val visibleBottom = scrollY + (height - paddingTop - paddingBottom)
                                                    
                                                    // 如果光标在可视区域下方（如切到第 4 行），精准滚动对齐
                                                    if (lineBottom > visibleBottom) {
                                                        scrollTo(0, lineBottom - (height - paddingTop - paddingBottom))
                                                    }
                                                }
                                            }
                                        }
                                    }
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
                expanded = expanded && query.text.isNotEmpty() && displayItems.isNotEmpty(),
                onDismissRequest = { onExpandedChange(false) }
            ) {
                displayItems.forEach { item ->
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
