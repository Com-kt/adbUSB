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
import io.github.sceneview.Scene
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberNodes
import java.io.File

class SpatialStorageActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT, 
                android.graphics.Color.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT, 
                android.graphics.Color.TRANSPARENT
            )
        )
        super.onCreate(savedInstanceState)
        
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    // 获取私有文件路径：data/data/com.kitty.compose.game/files/models/character.glb
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
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val childNodes = rememberNodes()

    LaunchedEffect(modelFile) {
        if (!modelFile.exists()) {
            errorMessage = "❌ 文件不存在！\n路径: ${modelFile.absolutePath}"
            isLoading = false
            return@LaunchedEffect
        }

        try {
            isLoading = true
            errorMessage = null

            val modelNode = modelLoader.createModelNode(file = modelFile).apply {
                centerModel(scaleToUnits = 1.0f)
                
                isEditable = true
            }

            childNodes.add(modelNode)
            isLoading = false
        } catch (e: Exception) {
            e.printStackTrace()
            errorMessage = "❌ 模型解析失败: ${e.localizedMessage}"
            isLoading = false
        }
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (errorMessage == null) {
            Scene(
                modifier = Modifier.fillMaxSize(),
                engine = engine,
                modelLoader = modelLoader,
                childNodes = childNodes
            )
        }

        if (isLoading) {
            CircularProgressIndicator()
        }

        if (errorMessage != null) {
            Text(text = errorMessage!!)
        }
    }
}
