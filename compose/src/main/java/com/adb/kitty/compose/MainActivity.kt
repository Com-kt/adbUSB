package com.adb.kitty.compose

import android.os.*
import androidx.annotation.*
import androidx.activity.*
import androidx.activity.compose.*
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
import androidx.compose.ui.unit.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.text.font.*
import com.adb.kitty.compose.ui.theme.*
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
            // 获取 ViewModel
            val viewModel: LogViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
            var query by remember { mutableStateOf("") }

            Scaffold(
                floatingActionButton = {
                    ExtendedFloatingActionButton(
                        onClick = { viewModel.appendLog("新命令已推送") }, // 触发业务逻辑
                        text = { Text("推送命令") },
                        icon = { Icon(Icons.Default.Add, null) }
                    )
                }
            ) { innerPadding ->
                Column(modifier = Modifier.padding(innerPadding).padding(16.dp)) {
                    
                    SearchBar(
                        query = query,
                        onQueryChange = { query = it },
                        items = viewModel.items,
                        onItemSelected = { query = it }
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // 传入日志数据，UI 自动响应变化
                    LogSection(
                        logs = viewModel.logs,
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    )
                }
            }
        }
    }
}

/*

@Keep
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CenterAlignedTopAppBarExample() {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    var showMenu by remember { mutableStateOf(false) }
    
    var query by rememberSaveable { mutableStateOf("") }
    val items = remember { listOf("Cupcake", "Donut", "Eclair", "Froyo", "Gingerbread", "Honeycomb", "Ice Cream Sandwich", "Jelly Bean", "KitKat", "Lollipop", "Marshmallow", "Nougat", "Oreo", "Pie") }
    
    // 过滤建议列表
    val filteredItems by remember(query) {
        derivedStateOf {
            if (query.isEmpty()) emptyList() 
            else items.filter { it.contains(query, ignoreCase = true) }
        }
    }
    var expanded by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                  /* 处理你的点击事件 *
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
                         /* do something *
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
                                text = { Text("Profile") },
                                leadingIcon = { Icon(Icons.Outlined.Person, null) },
                                onClick = { showMenu = false }
                            )
                            DropdownMenuItem(
                                text = { Text("Settings") },
                                leadingIcon = { Icon(Icons.Outlined.Settings, null) },
                                onClick = { showMenu = false }
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
                                text = { Text(item) },
                                onClick = {
                                    query = item
                                    expanded = false // 点击后收起菜单，键盘依然保持显示
                                },
                                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                            )
                        }
                    }
                }
            }
            val vScrollState = rememberScrollState()
            val hScrollState = rememberScrollState()
            Column(
                modifier = Modifier
                .weight(1f) // 核心：占据除搜索框外剩余的所有空间 (实现你的1:1需求)
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 75.dp)
                .border(1.dp, MaterialTheme.colorScheme.outline) // 给日志区域加个边框
                .verticalScroll(vScrollState) // 类似 ScrollView
            ) {
                Row(
                    modifier = Modifier
                        .horizontalScroll(hScrollState) // 类似 HorizontalScrollView
                        .padding(8.dp)
                ) {
                    Text(
                        text = "这是你的日志内容...\n你可以尝试输入很长很长的文本看看横向滚动效果。\n日志通常使用等宽字体显示效果更好。\n...\n(更多行数)\n...",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}
*/