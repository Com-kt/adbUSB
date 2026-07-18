package com.kitty.compose.xr

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.SpatialBox
import androidx.xr.compose.subspace.SpatialGltfModel
import androidx.xr.compose.subspace.SpatialGltfModelStatus
import androidx.xr.compose.subspace.SpatialGltfModelSource
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.rememberSpatialGltfModelState
import java.io.File

class SpatialStorageActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Subspace {
                StorageModelScreen()
            }
        }
    }
}

@Composable
fun StorageModelScreen() {
    val context = LocalContext.current
    
    val targetFile = remember { File(context.filesDir, "models/car.glb") }
    val fileExists = remember(targetFile) { targetFile.exists() }

    val isSpatialUiEnabled = androidx.xr.compose.spatial.LocalSpatialCapabilities.current.isSpatialUiEnabled

    val modelUri = remember(targetFile) { Uri.fromFile(targetFile) }
    val modelState = rememberSpatialGltfModelState(source = SpatialGltfModelSource.fromUri(modelUri))

    if (!isSpatialUiEnabled) {
        Box(modifier = Modifier.padding(32.dp)) {
            Text("⚠️ 设备不支持 3D 渲染，或者应用当前被系统强制处于 2D 平面兼容模式下。\n请检查 Manifest 中的 PROPERTY_XR_ACTIVITY_START_MODE 配置！")
        }
    } 
    else if (!fileExists) {
        SpatialPanel(modifier = SubspaceModifier.position(SpatialVector3D(0f, 0f, -1.0f))) {
            Text(
                text = "❌ 文件未找到！请确保您的模型已存入该路径：\n${targetFile.absolutePath}",
                modifier = Modifier.padding(16.dp),
                color = androidx.compose.ui.graphics.Color.Red
            )
        }
    } 
    else {
        SpatialBox(modifier = SubspaceModifier) {
            SpatialGltfModel(
                state = modelState,
                modifier = SubspaceModifier.position(SpatialVector3D(0f, 0f, -1.5f))
            )

            if (modelState.status is SpatialGltfModelStatus.Loading) {
                SpatialPanel(modifier = SubspaceModifier.position(SpatialVector3D(0f, 0f, -1.2f))) {
                    CircularProgressIndicator(modifier = Modifier.padding(16.dp))
                }
            } else if (modelState.status is SpatialGltfModelStatus.Failed) {
                SpatialPanel(modifier = SubspaceModifier.position(SpatialVector3D(0f, 0f, -1.2f))) {
                    Text("❌ 3D 文件存在，但 SDK 解析模型内部结构失败（请确保是标准.glb格式）。", modifier = Modifier.padding(16.dp))
                }
            }
        }
    }
}
