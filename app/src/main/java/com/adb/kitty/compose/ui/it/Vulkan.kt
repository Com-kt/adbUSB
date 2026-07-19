package com.adb.kitty.compose.ui.it

import com.adb.kitty.compose.ui.theme.*
import com.adb.kitty.compose.ui.viewmodel.*
import com.adb.kitty.compose.data.*
import com.adb.kitty.compose.*
import com.adb.kitty.compose.R

import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import androidx.annotation.Keep

@Keep
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VulkanSharpenScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var bitmapState by remember { mutableStateOf<Bitmap?>(null) }
    var isProcessed by remember { mutableStateOf(false) }
    var showBottomSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()

    val pickMediaLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                val bmp = withContext(Dispatchers.IO) {
                    loadBitmapFromUri(context, uri)
                }
                if (bmp != null) {
                    bitmapState = bmp
                    isProcessed = false
                }
            }
        }
    }

    Scaffold(
        floatingActionButton = {
            if (bitmapState != null) {
                ExtendedFloatingActionButton(
                    text = { Text("画质魔改") },
                    icon = { Text("✨") },
                    onClick = { showBottomSheet = true }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            if (bitmapState == null) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.clickable {
                        pickMediaLauncher.launch(
                            ActivityResultContracts.PickVisualMedia.Request(
                                ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        )
                    }
                ) {
                    Text("🖼️", style = MaterialTheme.typography.displayLarge)
                    Text("点击选择一张模糊的图片", style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                bitmapState?.let { bmp ->
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "当前图片",
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }

    if (showBottomSheet) {
        ModalBottomSheet(
            onDismissRequest = { showBottomSheet = false },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, bottom = 48.dp, top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (isProcessed) "GPU 锐化完成！" else "图片画质优化选项",
                    style = MaterialTheme.typography.titleMedium
                )

                Button(
                    onClick = {
                        val currentBmp = bitmapState ?: return@Button
                        coroutineScope.launch {
                            val sharpBmp = Bitmap.createBitmap(
                                currentBmp.width, currentBmp.height, Bitmap.Config.ARGB_8888
                            )
                            withContext(Dispatchers.Default) {
                                NativeLibs.sharpenBitmapNative(currentBmp, sharpBmp)
                            }
                            bitmapState = sharpBmp
                            isProcessed = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isProcessed 
                ) {
                    Text(if (isProcessed) "⚡ 骁龙 GPU 已完成锐化" else "🚀 启动 Vulkan 硬件锐化")
                }

                Button(
                    onClick = {
                        bitmapState?.let { bmp ->
                            saveBitmapToGallery(context, bmp)
                            coroutineScope.launch { sheetState.hide() }.invokeOnCompletion {
                                if (!sheetState.isVisible) showBottomSheet = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    ),
                    enabled = isProcessed 
                ) {
                    Text("💾 保存清晰图到相册")
                }

                TextButton(
                    onClick = {
                        coroutineScope.launch { sheetState.hide() }.invokeOnCompletion {
                            showBottomSheet = false
                            pickMediaLauncher.launch(
                                ActivityResultContracts.PickVisualMedia.Request(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        }
                    }
                ) {
                    Text("🔄 重新选择其他图片")
                }
            }
        }
    }
}

@Keep
fun loadBitmapFromUri(context: Context, uri: Uri): Bitmap? {
    return runCatching {
        context.contentResolver.openInputStream(uri).use { inputStream ->
            val original = BitmapFactory.decodeStream(inputStream)
            original?.copy(Bitmap.Config.ARGB_8888, true)
        }
    }.getOrNull()
}

@Keep
fun saveBitmapToGallery(context: Context, bitmap: Bitmap) {
    val filename = "Sharpen_${System.currentTimeMillis()}.jpg"
    
    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/NekoSharpen")
    }

    val resolver = context.contentResolver
    val imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

    if (imageUri != null) {
        runCatching {
            resolver.openOutputStream(imageUri).use { outputStream ->
                if (outputStream != null) {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
                    Toast.makeText(context, "图片已保存至相册！", Toast.LENGTH_SHORT).show()
                }
            }
        }.onFailure {
            Toast.makeText(context, "保存失败: ${it.message}", Toast.LENGTH_SHORT).show()
        }
    } else {
        Toast.makeText(context, "无法创建媒体文件", Toast.LENGTH_SHORT).show()
    }
}
