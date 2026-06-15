package com.adb.kitty.compose

import android.os.*
import androidx.annotation.*
import androidx.activity.*
import androidx.activity.compose.*
import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.*
import androidx.lifecycle.viewmodel.compose.*
import androidx.compose.foundation.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.interaction.*
import androidx.compose.material.icons.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import androidx.compose.ui.*
import androidx.compose.ui.res.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.text.font.*
import com.adb.kitty.compose.ui.theme.*
import com.adb.kitty.compose.ui.viewmodel.*
import com.adb.kitty.compose.data.*
import com.adb.kitty.compose.R

@Keep
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                Color.Transparent.toArgb(), 
                Color.Transparent.toArgb()
            ),
            navigationBarStyle = SystemBarStyle.auto(
                Color.Transparent.toArgb(), 
                Color.Transparent.toArgb()
            )
        )
        setContent {
            val viewModel: MainActivityViewModel = viewModel()
            ComposeEmptyActivityTheme {
                CenterAlignedTopAppBarExample(viewModel = viewModel)
            }
        }
    }
}

@Keep
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CenterAlignedTopAppBarExample(
    viewModel: MainActivityViewModel
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    var showMenu by remember { mutableStateOf(false) }
    
    var query by rememberSaveable { mutableStateOf("") }
    val filteredItems by remember(query) {
        derivedStateOf {
            if (query.isEmpty()) emptyList() 
            else {
                viewModel.items.filter { 
                    it.command.contains(query, ignoreCase = true) || 
                    it.description.contains(query, ignoreCase = true)
                }
            }
        }
    }
    var expanded by remember { mutableStateOf(false) }
    
    val logs = viewModel.logs

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    if (query.isNotBlank()) {
                        viewModel.appendLog("执行命令: $query")
                        viewModel.runCommand(query)
                    } else {
                        viewModel.appendLog("请先在上方输入或选择命令")
                    }
                },
                icon = { 
                    Icon(
                        imageVector = wand_stars,
                        contentDescription = "Wand Stars"
                    )
                },
                text = { 
                    Text(
                        text = stringResource(R.string.push_cmd_name)
                    ) 
                },
            )
        },
        floatingActionButtonPosition = FabPosition.End,
        
        topBar = {
            CenterAlignedTopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                ),
                title = {
                    Text(
                        text = stringResource(R.string.app_bar_name),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                         /* do something */ 
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Localized description"
                        )
                    }
                },
                actions = {
                    // 2. 使用 Box 包裹图标和菜单
                    Box {
                        IconButton(onClick = { showMenu = !showMenu }) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert, // 建议使用 MoreVert (三个点)
                                contentDescription = "More options"
                            )
                        }
                        
                        // 3. 定义下拉菜单
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("清空日志") },
                                leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                                onClick = { 
                                    viewModel.clearLogs()
                                    showMenu = false 
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("使用说明") },
                                leadingIcon = { Icon(Icons.Outlined.Info, null) },
                                onClick = {
                                    viewModel.appendLog("\n" +viewModel.warnMessage)
                                    showMenu = false
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Send Feedback") },
                                leadingIcon = { Icon(Icons.Outlined.Feedback, null) },
                                trailingIcon = { Icon(Icons.AutoMirrored.Outlined.Send, null) },
                                onClick = { showMenu = false }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("About") },
                                leadingIcon = { Icon(Icons.Outlined.Info, null) },
                                onClick = { showMenu = false }
                            )
                            DropdownMenuItem(
                                text = { Text("Help") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Help, null) },
                                trailingIcon = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, null) },
                                onClick = { showMenu = false }
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).padding(16.dp).fillMaxSize()) {
            
            Box(modifier = Modifier.wrapContentHeight()) {
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded }
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { 
                            query = it
                            expanded = it.isNotEmpty()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable, enabled = true),
                        label = { Text("搜索甜点") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
                    )
                
                    ExposedDropdownMenu(
                        expanded = expanded && filteredItems.isNotEmpty(), 
                        onDismissRequest = { expanded = false }
                    ) {
                        filteredItems.forEach { item ->
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
                                            text = item.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                        
                                    Spacer(modifier = Modifier.height(2.dp))
                        
                                        Text(
                                            text = item.command,
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                        Text(
                                            text = if (item.isAdb) "[ADB]" else "[Fastboot]",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (item.isAdb) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                            modifier = Modifier.wrapContentWidth() // 确保按文本真实宽度包裹
                                        )
                                    }
                                },
                                onClick = {
                                    query = item.command
                                    expanded = false // 点击后收起菜单，键盘依然保持显示
                                },
                                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                            )
                        }
                    }
                }
            }
            LogSection(
                logs = logs,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(top = 16.dp, bottom = 75.dp)
            )
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

    // 优化后的滚动逻辑：防卡顿
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            val lastIndex = logs.size - 1
            
            // 获取当前可见的最后一行的 Index
            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            
            // 【核心防卡顿策略】
            // 如果当前已经接近底部（距离最新日志不到 5 行），直接无视动画瞬间吸附，防止连续日志触发动画导致卡顿
            if (lastIndex - lastVisibleIndex < 5) {
                listState.scrollToItem(lastIndex) 
            } else {
                // 如果距离稍微有一点点远（比如触发了单次手动操作），用流畅的动画滑过去
                // 限制只有在差距不大时才动画，太远了动画也会卡
                if (lastIndex - lastVisibleIndex < 20) {
                    listState.animateScrollToItem(lastIndex)
                } else {
                    listState.scrollToItem(lastIndex)
                }
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .border(1.dp, MaterialTheme.colorScheme.outline)
            .padding(8.dp)
    ) {
        items(logs) { log ->
            val hScrollState = rememberScrollState()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(hScrollState)
            ) {
                Text(
                    text = log,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                    softWrap = false // 禁用自动折行：遇到屏幕边缘不强制换行，只有遇到 \n 才会换行
                )
            }
        }
    }
}
