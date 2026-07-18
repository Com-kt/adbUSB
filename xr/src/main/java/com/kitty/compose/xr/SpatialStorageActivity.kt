package com.kitty.compose.xr

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.sceneview.Scene
import io.github.sceneview.node.ModelNode
import java.io.File

class SpatialStorageActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            StorageModelScreen()
        }
    }
}

@Composable
fun StorageModelScreen() {
    val context = LocalContext.current
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // 1. Point to your local file path inside the app sandbox
    val targetFile = remember { File(context.filesDir, "models/car.glb") }

    // Lifecycle check to make sure the binary model exists before feeding it to the view
    LaunchedEffect(targetFile) {
        if (!targetFile.exists()) {
            errorMessage = "❌ File not found at:\n${targetFile.absolutePath}"
        }
        // Sceneview loads the file directly asynchronously inside the composable tree
        isLoading = false 
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (errorMessage == null) {
            // 2. Clear out manual engine setup. Scene handles everything implicitly in 4.x
            Scene(
                modifier = Modifier.fillMaxSize()
            ) {
                // 3. True 4.22.0 Syntax: Nodes are declarative child components!
                if (!isLoading) {
                    ModelNode(
                        modelFileLocation = targetFile.absolutePath, // Pass the path string directly
                        scaleToUnits = 1.0f // Auto-normalizes size mapping
                    )
                }
            }
        }

        // Overlay status indicators over the 3D canvas viewport
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }

        errorMessage?.let { error ->
            Text(
                text = error,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                color = androidx.compose.ui.graphics.Color.Red
            )
        }
    }
}
