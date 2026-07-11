package com.adb.kitty.compose.ui.it

import android.*
import android.util.*
import android.content.pm.*
import android.graphics.*
import android.animation.*
import android.provider.*
import android.app.PendingIntent

import android.speech.tts.TextToSpeech

import android.os.*
import android.view.*
import android.widget.*
import android.content.*
import android.hardware.usb.*

import android.net.*
import android.net.wifi.*
import android.net.nsd.*
import android.text.method.*

import androidx.core.view.*
import androidx.core.content.*
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
/*******************************
*        kotlinx 协程         *
*    suspend 都给我挂起     *
********************************/
import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.*
import androidx.lifecycle.viewmodel.internal.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.flow.*

import kotlin.*
import kotlin.coroutines.*
import kotlin.math.*
import kotlin.system.*

import java.io.*
import java.nio.*
import java.security.*
import java.text.*
import java.net.*
import java.util.*
import java.util.zip.*
import java.time.*
import java.time.format.*
import javax.crypto.*
import javax.net.ssl.*
import okio.*
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.shell.*
import org.json.*

import android.os.*
import androidx.annotation.*
import androidx.activity.*
import androidx.activity.compose.*
import androidx.activity.result.contract.*
import androidx.lifecycle.*
import androidx.lifecycle.compose.*
import androidx.lifecycle.viewmodel.*
import androidx.lifecycle.viewmodel.compose.*
import androidx.compose.foundation.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.text.selection.*
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
import androidx.compose.ui.draw.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.layout.*
import androidx.compose.ui.window.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.input.*
import com.adb.kitty.compose.ui.theme.*
import com.adb.kitty.compose.ui.viewmodel.*
import com.adb.kitty.compose.data.*
import com.adb.kitty.compose.*
import com.adb.kitty.compose.R

@Keep
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CenterAlignedTopAppBarExample(
    viewModel: MainActivityViewModel,
    activity: MainActivity,
    onExecuteCommand: (String) -> Unit
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    var showMenu by remember { mutableStateOf(false) }
    
    val devicesState by viewModel.deviceListState.collectAsStateWithLifecycle()
    
    var query by rememberSaveable(stateSaver = TextFieldValue.Saver) { 
        mutableStateOf(TextFieldValue("")) 
    }
    val filteredItems by remember(query.text) {
        derivedStateOf {
            if (query.text.isEmpty()) emptyList() 
            else {
                viewModel.items.filter { 
                    it.command.contains(query.text, ignoreCase = true) || 
                    it.description.contains(query.text, ignoreCase = true)
                }
            }
        }
    }
    var expanded by remember { mutableStateOf(false) }
    
    val useDynamicColor by viewModel.useDynamicColor.collectAsStateWithLifecycle()
    
    val logCount = viewModel.logCount
    val getLogLineAt = { index: Int -> viewModel.getLogLineAt(index) }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    if (query.text.isNotBlank()) {
                        onExecuteCommand(query.text)
                        query = TextFieldValue("")
                        expanded = false
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
                    Box {
                        IconButton(onClick = { showMenu = !showMenu }) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = "More options"
                            )
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.action_menu_color)
                                    )
                                },
                                leadingIcon = { 
                                    Icon(Icons.Outlined.Palette, null) 
                                },
                                onClick = {
                                    viewModel.setDynamicColorEnabled(!useDynamicColor)
                                    showMenu = false
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.action_menu_dellog)
                                    )
                                },
                                leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                                onClick = { 
                                    viewModel.clearLogs()
                                    showMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.action_menu_help)
                                    )
                                },
                                leadingIcon = { Icon(Icons.Outlined.Info, null) },
                                onClick = {
                                    viewModel.appendLog("\n" +viewModel.warnMessage)
                                    showMenu = false
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.action_menu_log_export)
                                    )
                                },
                                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Send, null) },
                                onClick = {
                                    onExecuteCommand("userkitty-log-export")
                                    showMenu = false 
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.action_menu_usb)
                                    )
                                },
                                leadingIcon = { Icon(Icons.Outlined.Hub, null) },
                                onClick = {
                                    activity.findHostDevice()
                                    showMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.action_menu_wifi)
                                    )
                                },
                                leadingIcon = { Icon(Icons.Outlined.Refresh, null) },
                                onClick = {
                                    showMenu = false
                                    activity.handleWifiConnectionFlow()
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.action_menu_iptest)
                                    )
                                },
                                leadingIcon = { Icon(Icons.Outlined.Language, null) },
                                onClick = {
                                    activity.startIpNetworkTest()
                                    showMenu = false
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.action_menu_selinux)
                                    )
                                },
                                leadingIcon = { Icon(Icons.Outlined.LockOpen, null) },
                                onClick = {
                                    showMenu = false
                                    activity.FbSeLinuxCmd()
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.action_menu_rate)
                                    )
                                },
                                leadingIcon = { Icon(Icons.Outlined.PlayArrow, null) },
                                onClick = {
                                    showMenu = false
                                    activity.inspector.bindRootService { isConnected ->
                                        if (isConnected) {
                                            activity.inspector.start()
                                        } else {
                                            activity.appendLog("[错误] Root 特权服务绑定失败！请确认设备已获得 Magisk/Apatch/KernelSU 完整授权！")
                                        }
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.action_menu_rate_stop)
                                    )
                                },
                                leadingIcon = { Icon(Icons.Outlined.Stop, null) },
                                onClick = {
                                    showMenu = false
                                    activity.inspector.stop()
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.action_menu_turbo)
                                    )
                                },
                                leadingIcon = { Icon(Icons.Outlined.Speed, null) },
                                onClick = {
                                    showMenu = false
                                    activity.lifecycleScope.launch(Dispatchers.IO) {
                                        activity.turbo.enterTurboMode()
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.action_menu_turbo_stop)
                                    )
                                },
                                leadingIcon = { Icon(Icons.Outlined.SettingsBackupRestore, null) },
                                onClick = {
                                    showMenu = false
                                    activity.lifecycleScope.launch(Dispatchers.IO) {
                                        activity.turbo.exitTurboMode()
                                    }
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.action_menu_shell_stop)
                                    )
                                },
                                leadingIcon = { Icon(Icons.Outlined.Cancel, null) },
                                onClick = {
                                    showMenu = false
                                    activity.stopCurrentCommand()
                                }
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).padding(16.dp).fillMaxSize()) {
            
            DeviceSelectionSection(
                devices = devicesState,
                onDeviceSelected = { selectedDevice ->
                    viewModel.switchActiveDevice(selectedDevice)
                }
            )
            
            Box(modifier = Modifier.wrapContentHeight()) {
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded }
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { 
                            query = it
                            expanded = it.text.isNotEmpty()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable, enabled = true),
                        label = {
                            Text(
                                stringResource(R.string.action_menu_sospl)
                            )
                        },
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
                                            text = item.command,
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                        
                                    Spacer(modifier = Modifier.height(2.dp))
                        
                                        Text(
                                            text = item.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                        Text(
                                            text = when {
                                                item.isApp -> "[APP]"
                                                item.isAdb -> "[ADB]"
                                                else -> "[Fastboot]"
                                            },
                                            style = MaterialTheme.typography.labelMedium,
                                            color = when {
                                                item.isApp -> MaterialTheme.colorScheme.secondary
                                                item.isAdb -> MaterialTheme.colorScheme.primary
                                                else -> MaterialTheme.colorScheme.error
                                            },
                                            modifier = Modifier.wrapContentWidth()
                                        )
                                    }
                                },
                                onClick = {
                                    val targetCommand = item.command
                                    query = TextFieldValue(
                                        text = targetCommand,
                                        selection = TextRange(targetCommand.length) 
                                    )
                                    expanded = false // 点击后收起菜单
                                },
                                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                            )
                        }
                    }
                }
            }
            LogSection(
                logCount = logCount,
                getLogLineAt = getLogLineAt,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(top = 16.dp, bottom = 71.dp)
            )
        }
    }
}

@Keep
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DeviceSelectionSection(
    devices: List<DeviceUiState>,
    onDeviceSelected: (DeviceUiState) -> Unit,
    modifier: Modifier = Modifier
) {
    if (devices.isEmpty()) {
        Text(
            text = "💡 暂无可用并网通道",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(vertical = 12.dp)
        )
    } else {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
            FlowRow(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                devices.forEach { device ->
                    FilterChip(
                        selected = device.isActive,
                        onClick = { onDeviceSelected(device) }, 
                        label = {
                            Text(
                                text = device.displayName,
                                style = MaterialTheme.typography.bodySmall
                            )
                        },
                        leadingIcon = {
                            if (device.isActive) {
                                Icon(
                                    imageVector = Icons.Filled.Done,
                                    contentDescription = "Selected",
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.primary,
                            selectedLeadingIconColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            }
        }
    }
}

@Keep
@Composable
fun LogSection(
    logCount: Int,
    getLogLineAt: (index: Int) -> String,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val globalHorizontalScrollState = rememberScrollState()
    
    val customTextSelectionColors = TextSelectionColors(
        handleColor = MaterialTheme.colorScheme.primary,
        backgroundColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
    )
    
    val colorScheme = MaterialTheme.colorScheme
    val debugColor = colorScheme.secondary
    val infoColor = colorScheme.primary
    val warnColor = Color(0xFFFF9800)
    val errorColor = colorScheme.error
    val traceColor = colorScheme.outline
    val successColor = Color(0xFF4CAF50)

    // React to the total line count size changes
    LaunchedEffect(logCount) {
        if (logCount > 0) {
            val lastIndex = logCount - 1
            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            if (lastIndex - lastVisibleIndex < 5) {
                listState.scrollToItem(lastIndex) 
            } else if (lastIndex - lastVisibleIndex < 20) {
                listState.animateScrollToItem(lastIndex)
            } else {
                listState.scrollToItem(lastIndex)
            }
        }
    }

    Box(
        modifier = modifier
            .border(1.dp, MaterialTheme.colorScheme.outline)
            .horizontalScroll(globalHorizontalScrollState)
            .padding(8.dp)
    ) {
        CompositionLocalProvider(LocalTextSelectionColors provides customTextSelectionColors) {
            SelectionContainer {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth() 
                ) {
                    items(
                        count = logCount,
                        key = { index -> 
                            // Generates a lightweight structural structural item hash signature 
                            // derived from the line mapping to ensure smooth list state retention
                            index
                        }
                    ) { index ->
                        val logLineStr = remember(index) { 
                            getLogLineAt(index) 
                        }
                        val rowTextColor = remember(index, debugColor, infoColor, warnColor, errorColor, traceColor, successColor) {
                            determineLogColor(logLineStr, debugColor, infoColor, warnColor, errorColor, traceColor, successColor)
                        }
                        Text(
                            text = logLineStr,
                            color = rowTextColor,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodyMedium,
                            softWrap = false,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 1.dp)
                        )
                    }
                }
            }
        }
    }
}

@Keep
private fun determineLogColor(
    line: String,
    debugColor: Color,
    infoColor: Color,
    warnColor: Color,
    errorColor: Color,
    traceColor: Color,
    successColor: Color
): Color {
    if (line.isEmpty()) return Color.Unspecified

    // 优先级最高：最高危的【错误 / 异常 / Fatal】
    // 无论是标准 Logcat 头部标记，还是文本中包含“错误”“异常”，整行直接爆红
    val maxScanLen = minOf(line.length, 80)
    if (line.indexOf(" E/", 0) in 0 until maxScanLen || 
        line.indexOf(" F/", 0) in 0 until maxScanLen || 
        line.contains("错误") || 
        line.contains("异常") || 
        line.contains("Exception") || 
        line.contains("Error") || 
        line.contains("Fatal")
    ) {
        return errorColor
    }

    //. 优先级第二：【警告 / 超时】
    if (line.indexOf(" W/", 0) in 0 until maxScanLen || 
        line.contains("警告") || 
        line.contains("超时") || 
        line.contains("Warning") || 
        line.contains("Timeout")
    ) {
        return warnColor
    }

    // 优先级第三：【成功】
    // 绿色在日志中极其显眼，通常用于核心流程跑通的打点
    if (line.contains("成功") || 
        line.contains("Success") || 
        line.contains("OK")
    ) {
        return successColor
    }

    // 优先级第四：【提示 / 信息 / Info】
    if (line.indexOf(" I/", 0) in 0 until maxScanLen || 
        line.contains("提示") || 
        line.contains("信息") || 
        line.contains("Info") || 
        line.contains("Hint")
    ) {
        return infoColor
    }

    // 优先级第五：【调试 / Debug】
    if (line.indexOf(" D/", 0) in 0 until maxScanLen || 
        line.contains("Debug")
    ) {
        return debugColor
    }

    // 优先级第六：【追踪 / Verbose / Trace】
    if (line.indexOf(" V/", 0) in 0 until maxScanLen || 
        line.contains("Trace") || 
        line.contains("Verbose")
    ) {
        return traceColor
    }

    // 兜底兼容：针对新版 AS 格式的孤立字母（如 "  E  "）进行前缀快速扫描
    for (i in 0 until maxScanLen - 2) {
        if (line[i] == ' ' && line[i + 2] == ' ') {
            when (line[i + 1]) {
                'E', 'F' -> return errorColor
                'W' -> return warnColor
                'I' -> return infoColor
                'D' -> return debugColor
                'V' -> return traceColor
            }
        }
    }

    return Color.Unspecified
}

@Keep
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceSelectionBottomSheet(
    wifiName: String,
    devices: List<AdbDevice>,
    onDeviceSelected: (AdbDevice) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "📡 检测到当前 WiFi [$wifiName] 下有多个历史设备",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(devices) { dev ->
                    ListItem(
                        supportingContent = { Text("上次并网时间: ${getRelativeTime(dev.lastConnectedTime)}") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onDeviceSelected(dev) }
                    ) {
                        Text("📺 IP: ${dev.ip}:${dev.port}")
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Keep
private fun getRelativeTime(timeMs: Long): String {
    val diff = System.currentTimeMillis() - timeMs
    val minutes = diff / 1000 / 60
    val hours = minutes / 60
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "${minutes}分钟前"
        else -> "${hours}小时前"
    }
}
