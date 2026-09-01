package com.adb.kitty.ui.it

import com.adb.kitty.ui.theme.*
import com.adb.kitty.ui.viewmodel.*
import com.adb.kitty.data.*
import com.adb.kitty.*
import com.adb.kitty.R

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.Keep
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap

@Keep
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppListBottomSheet(
    appList: List<AppInfo>,
    isVisible: Boolean,
    onDismissRequest: () -> Unit,
    onAppSelected: (AppInfo) -> Unit,
    onStorageApkSelected: (Uri) -> Unit
) {
    if (!isVisible) return

    var searchQuery by remember { mutableStateOf("") }
    var hideSystemApps by remember { mutableStateOf(true) }

    val apkPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            onStorageApkSelected(it)
            onDismissRequest()
        }
    }

    val filteredApps = remember(appList, searchQuery, hideSystemApps) {
        appList.filter { app ->
            val matchesSystemFilter = if (hideSystemApps) !app.isSystemApp else true
            val matchesSearch = searchQuery.isBlank() ||
                    app.appName.contains(searchQuery, ignoreCase = true) ||
                    app.packageName.contains(searchQuery, ignoreCase = true)
            matchesSystemFilter && matchesSearch
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = Modifier.fillMaxHeight(1f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            Text(
                text = "选择应用提取签名",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("搜索应用或包名...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = MaterialTheme.shapes.medium
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                FilterChip(
                    selected = hideSystemApps,
                    onClick = { hideSystemApps = !hideSystemApps },
                    label = { Text("隐藏系统应用") },
                    leadingIcon = if (hideSystemApps) {
                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    } else null
                )

                AssistChip(
                    onClick = {
                        apkPickerLauncher.launch("application/vnd.android.package-archive")
                    },
                    label = { Text("本地选取 APK") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                )

                Spacer(modifier = Modifier.weight(1f))

                Text(
                    text = "共 ${filteredApps.size} 项",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(
                    items = filteredApps,
                    key = { it.packageName }
                ) { app ->
                    AppListItem(
                        appInfo = app,
                        onClick = {
                            onAppSelected(app)
                        }
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 64.dp))
                }
            }
        }
    }
}

@Keep
@Composable
private fun AppListItem(
    appInfo: AppInfo,
    onClick: () -> Unit
) {
    val context = LocalContext.current

    val appIcon = remember(appInfo.packageName) {
        runCatching {
            context.packageManager.getApplicationIcon(appInfo.packageName)
                .toBitmap(width = 112, height = 112)
                .asImageBitmap()
        }.getOrNull()
    }

    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            if (appIcon != null) {
                Image(
                    bitmap = appIcon,
                    contentDescription = appInfo.appName,
                    modifier = Modifier.size(44.dp)
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Android,
                    contentDescription = null,
                    modifier = Modifier.size(44.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        },
        supportingContent = {
            Column {
                Text(
                    text = "v${appInfo.versionName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = appInfo.packageName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    ) {
        Text(
            text = appInfo.appName,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}
