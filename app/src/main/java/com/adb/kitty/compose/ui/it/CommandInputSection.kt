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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.receiveAsFlow

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
    val searchChannel = remember { Channel<String>(Channel.CONFLATED) }

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
                                
                                setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
                                maxLines = 3
                                isSingleLine = false
                                textSize = 16f
                                
                                movementMethod = ScrollingMovementMethod.getInstance()
                                setHorizontallyScrolling(false)

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
