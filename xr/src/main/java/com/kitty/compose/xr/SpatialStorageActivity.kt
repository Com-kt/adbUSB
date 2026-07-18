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
    
    val modelUri = remember {
        val targetFile = File(context.filesDir, "models/car.glb")
        if (!targetFile.exists()) {
            Toast.makeText(context, "3D file (.glb) not found in storage!", Toast.LENGTH_LONG).show()
        }
        Uri.fromFile(targetFile)
    }

    val modelState = rememberSpatialGltfModelState(
        source = SpatialGltfModelSource.fromUri(modelUri)
    )

    SpatialBox(modifier = SubspaceModifier) {
        SpatialGltfModel(
            state = modelState,
            modifier = SubspaceModifier
        )

        when (modelState.status) {
            is SpatialGltfModelStatus.Loading -> {
                SpatialPanel(modifier = SubspaceModifier) {
                    Box(modifier = Modifier.padding(16.dp)) {
                        CircularProgressIndicator()
                    }
                }
            }
            is SpatialGltfModelStatus.Failed -> {
                SpatialPanel(modifier = SubspaceModifier) {
                    Text(
                        text = "Failed to load 3D Model file from storage.",
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
            else -> { /* 加载成功后，模型将自动根据骨骼数据渲染到空间中 */ }
        }
    }
}
