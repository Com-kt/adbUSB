package com.kitty.compose.xr

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
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
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberNodes
import io.github.sceneview.Scene
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

    // Initialize Filament Engine and Asset Loaders
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val childNodes = rememberNodes()

    // 1. Locate the file in internal storage
    val targetFile = remember { File(context.filesDir, "models/car.glb") }

    LaunchedEffect(targetFile) {
        if (!targetFile.exists()) {
            errorMessage = "❌ File not found at:\n${targetFile.absolutePath}"
            isLoading = false
            return@LaunchedEffect
        }

        try {
            // 2. Load the 3D model into a scene node asynchronously
            val modelNode = ModelNode(
                modelInstance = modelLoader.createModelInstance(
                    assetFileLocation = targetFile.absolutePath
                ),
                scaleToUnits = 1.0f // Scales the model down/up to fit 1 unit size
            ).apply {
                isEditable = true // Enables user drag, rotation, and scaling gestures
            }
            
            childNodes.add(modelNode)
            isLoading = false
        } catch (e: Exception) {
            errorMessage = "❌ Failed to parse .glb structure: ${e.localizedMessage}"
            isLoading = false
        }
    }

    // 3. Render the 3D viewport canvas
    Box(modifier = Modifier.fillMaxSize()) {
        if (errorMessage == null) {
            Scene(
                modifier = Modifier.fillMaxSize(),
                engine = engine,
                childNodes = childNodes,
                isInteractive = true // Allows the user to swipe and spin the 3D asset
            )
        }

        // Overlay status states on top of the 3D Viewport
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
