package com.adb.kitty.compose

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
import androidx.xr.compose.spatial.SpatialActivitySpace
import androidx.xr.compose.subspace.SpatialGltfModel
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.Subspace
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.position
import androidx.xr.compose.subspace.layout.scale
import androidx.xr.compose.subspace.rememberSpatialGltfModelState
import androidx.xr.compose.subspace.SpatialGltfModelState
import androidx.xr.core.SpatialVector3D
import java.io.File

class SpatialStorageActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // 1. Initialize the XR Spatial Container
            SpatialActivitySpace {
                StorageModelScreen()
            }
        }
    }
}

@Composable
fun StorageModelScreen() {
    val context = LocalContext.current
    
    // 2. Locate the file in internal storage (e.g., app sandbox files directory)
    // Absolute path example: /data/user/0/your.package/files/models/car.glb
    val modelUri = remember {
        val targetFile = File(context.filesDir, "models/car.glb")
        
        if (!targetFile.exists()) {
            Toast.makeText(context, "Target 3D file not found!", Toast.LENGTH_LONG).show()
        }
        
        // Convert java.io.File directly into an Android Uri
        Uri.fromFile(targetFile)
    }

    // 3. Pass the Uri to the Jetpack XR specialized state manager
    val modelState = rememberSpatialGltfModelState(source = modelUri)

    // 4. Render within the 3D Subspace
    Subspace {
        // Place the 3D model 1.5 meters directly in front of the user
        SpatialGltfModel(
            modelState = modelState,
            modifier = SubspaceModifier
                .position(SpatialVector3D(0f, 0f, -1.5f))
                .scale(SpatialVector3D(1f, 1f, 1f))
        )

        // 5. Handle Async States (Loading/Error overlays using an anchor panel)
        when (modelState.loadingState) {
            is SpatialGltfModelState.LoadingState.Loading -> {
                SpatialPanel(modifier = SubspaceModifier.position(SpatialVector3D(0f, 0f, -1.4f))) {
                    Box(modifier = Modifier.padding(16.dp)) {
                        CircularProgressIndicator()
                    }
                }
            }
            is SpatialGltfModelState.LoadingState.Error -> {
                SpatialPanel(modifier = SubspaceModifier.position(SpatialVector3D(0f, 0f, -1.4f))) {
                    Text(
                        text = "Failed to load 3D Model file.",
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
            else -> { /* Render normally when success */ }
        }
    }
}
