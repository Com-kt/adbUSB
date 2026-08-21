package com.adb.kitty.compose.activities

import android.os.*
import android.graphics.*
import androidx.annotation.*
import androidx.activity.*
import androidx.activity.compose.*
import androidx.activity.result.*
import androidx.activity.result.contract.*
import androidx.lifecycle.*
import androidx.lifecycle.compose.*
import androidx.lifecycle.viewmodel.*
import androidx.lifecycle.viewmodel.internal.*
import androidx.lifecycle.viewmodel.compose.*
import androidx.compose.foundation.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.text.*
import androidx.compose.foundation.text.selection.*
import androidx.compose.material.icons.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.*
import androidx.compose.ui.*
import androidx.compose.ui.res.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.viewinterop.*
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.input.*

import com.adb.kitty.compose.ui.theme.*
import com.adb.kitty.compose.ui.viewmodel.*
import com.adb.kitty.compose.ui.it.*
import com.adb.kitty.compose.ui.it.help.*
import com.adb.kitty.compose.data.*
import com.adb.kitty.compose.data.help.*
import com.adb.kitty.compose.R
import com.adb.kitty.compose.*

class DesktopActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                Color.Transparent.toArgb(), 
                Color.Transparent.toArgb()
            ),
            navigationBarStyle = SystemBarStyle.auto(
                Color.Transparent.toArgb(), 
                Color.Transparent.toArgb()
            )
        )
        setContent {
            val useDynamicColor by viewModel.useDynamicColor.collectAsStateWithLifecycle()
            NekoTheme(dynamicColor = useDynamicColor) {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    
                }
            }
        }
    }
}