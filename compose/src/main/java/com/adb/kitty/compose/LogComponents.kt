package com.adb.kitty.compose

import androidx.annotation.Keep
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Keep
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    items: List<String>,
    onItemSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val filteredItems = remember(query) {
        if (query.isEmpty()) emptyList()
        else items.filter { it.contains(query, ignoreCase = true) }
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { onQueryChange(it); expanded = it.isNotEmpty() },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
            label = { Text("搜索甜点") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }
        )
        ExposedDropdownMenu(
            expanded = expanded && filteredItems.isNotEmpty(),
            onDismissRequest = { expanded = false }
        ) {
            filteredItems.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item) },
                    onClick = { onItemSelected(item); expanded = false }
                )
            }
        }
    }
}

@Keep
@Composable
fun LogSection(
    logs: List<String>,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    // 自动滚动到底部
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .border(1.dp, MaterialTheme.colorScheme.outline)
            .padding(8.dp)
    ) {
        items(logs) { log ->
            Text(
                text = log,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
