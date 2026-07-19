package com.kitty.compose.game

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.sceneview.SceneView
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberModelInstance
import java.io.File

class SpatialStorageActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val targetGlbFile = remember {
                        File(applicationContext.filesDir, "models/character.glb")
                    }
                    LocalGlbModelViewerV4(modelFile = targetGlbFile)
                }
            }
        }
    }
}

@Composable
fun LocalGlbModelViewerV4(modelFile: File, modifier: Modifier = Modifier) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)

    if (!modelFile.exists()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text = "❌ 模型文件不存在！\n路径: ${modelFile.absolutePath}")
        }
        return
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        SceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            modelLoader = modelLoader
        ) {
            val modelInstance = rememberModelInstance(modelLoader, modelFile.absolutePath)

            if (modelInstance != null) {
                ModelNode(
                    modelInstance = modelInstance,
                    scaleToUnits = 1.0f,
                    autoAnimate = true
                )
            }
        }
    }
}
