package com.adb.kitty.compose.ui.it

import android.*
import android.util.*
import android.content.pm.*
import android.graphics.*
import android.animation.*
import android.provider.*
import android.app.PendingIntent

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
import androidx.lifecycle.viewmodel.*
import androidx.lifecycle.viewmodel.compose.*
import androidx.compose.foundation.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.interaction.*
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
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.semantics.*
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
    
    val logs = viewModel.logs

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
                    // 2. 使用 Box 包裹图标和菜单
                    Box {
                        IconButton(onClick = { showMenu = !showMenu }) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = "More options"
                            )
                        }
                        
                        // 3. 定义下拉菜单
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
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
            
            if (activity.connectedDevices.isEmpty()) {
                Text(
                    text = "💡 暂无可用并网通道",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            } else {
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        activity.connectedDevices.forEach { rawDeviceId ->
                            val isCurrentActive = activity.activeDeviceId == rawDeviceId
                            
                            val isUsb = rawDeviceId.startsWith("USB_")
                            val cleanName = rawDeviceId.substringAfter("_")
                            val displayName = if (isUsb) "🔌 USB: $cleanName" else "🌐 Wi-Fi: $cleanName"

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(vertical = 4.dp)
                            ) {
                                Checkbox(
                                    checked = isCurrentActive,
                                    onCheckedChange = { checked ->
                                        if (checked) {
                                            activity.adbService?.currentDeviceId = rawDeviceId
                                            activity.syncDeviceList()
                                            activity.appendLog("[系统] 主控权已动态切流至 -> $displayName")
                                        }
                                    }
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = displayName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isCurrentActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
            
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
                logs = logs,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(top = 16.dp, bottom = 71.dp)
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
    val globalHorizontalScrollState = rememberScrollState()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            val lastIndex = logs.size - 1
            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            
            if (lastIndex - lastVisibleIndex < 5) {
                listState.scrollToItem(lastIndex) 
            } else {
                if (lastIndex - lastVisibleIndex < 20) {
                    listState.animateScrollToItem(lastIndex)
                } else {
                    listState.scrollToItem(lastIndex)
                }
            }
        }
    }

    Box(
        modifier = modifier
            .border(1.dp, MaterialTheme.colorScheme.outline)
            .horizontalScroll(globalHorizontalScrollState)
            .padding(8.dp)
    ) {
        SelectionContainer {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxHeight()
                    .wrapContentWidth() 
            ) {
                items(logs) { log ->
                    Text(
                        text = log,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyMedium,
                        softWrap = false,
                        modifier = Modifier.padding(vertical = 1.dp)
                    )
                }
            }
        }
    }
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
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState()
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
                        headlineContent = { Text("📺 IP: ${dev.ip}:${dev.port}") },
                        supportingContent = { Text("上次并网时间: ${getRelativeTime(dev.lastConnectedTime)}") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onDeviceSelected(dev) }
                    )
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
