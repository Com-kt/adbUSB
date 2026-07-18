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
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberModelInstance
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
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine = engine)

    val targetFile = remember { File(context.filesDir, "models/car.glb") }

    val modelInstance by rememberModelInstance(
        modelLoader = modelLoader,
        fileLocation = targetFile.absolutePath,
        onError = { exception ->
            errorMessage = "❌ Model parse failed:\n${exception.localizedMessage}"
        }
    )

    LaunchedEffect(targetFile) {
        if (!targetFile.exists()) {
            errorMessage = "❌ File not found at:\n${targetFile.absolutePath}"
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (errorMessage == null) {
            Scene(
                modifier = Modifier.fillMaxSize(),
                engine = engine
            ) {
                modelInstance?.let { instance ->
                    ModelNode(
                        modelInstance = instance,
                        scaleToUnits = 1.0f
                    )
                }
            }
        }

        if (modelInstance == null && errorMessage == null) {
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
