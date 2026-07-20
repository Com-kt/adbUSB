package com.web.view.kitty

import com.web.view.kitty.ui.theme.*

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NekoCrawlerActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        setContent {
            NekoTheme {
                var targetUrl by remember { mutableStateOf("https://m.baidu.com") }
                var spiderScript by remember {
                    mutableStateOf(
                        """
                        (function() {
                            let results = [];
                            let elements = document.querySelectorAll('a');
                            elements.forEach(el => {
                                let text = el.innerText ? el.innerText.trim() : "";
                                if (text.length > 0) {
                                    results.push({ "标题": text, "链接": el.href });
                                }
                            });
                            // 将数据回传给 Android 本地
                            window.NekoSpider.postData(JSON.stringify(results.slice(0, 10), null, 2)); 
                            return "成功抓取到 " + results.length + " 条数据项！";
                        })();
                        """.trimIndent()
                    )
                }
                
                val logs = remember { mutableStateListOf<String>() }
                val coroutineScope = rememberCoroutineScope()
                val listState = rememberLazyListState()

                fun appendLog(msg: String) {
                    val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                    logs.add("[$time] $msg")
                    coroutineScope.launch {
                        if (logs.isNotEmpty()) {
                            listState.animateScrollToItem(logs.size - 1)
                        }
                    }
                }

                var webViewInstance by remember { mutableStateOf<WebView?>(null) }
                var isPageLoading by remember { mutableStateOf(false) }

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("🕷️ 网页自动化爬虫控制台") },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                                }
                            }
                        )
                    }
                ) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = targetUrl,
                                onValueChange = { targetUrl = it },
                                label = { Text("目标 URL") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodySmall
                            )
                            Button(
                                onClick = { webViewInstance?.loadUrl(targetUrl.trim()) }
                            ) {
                                Text("前往")
                            }
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            AndroidView(
                                factory = { context ->
                                    WebView(context).apply {
                                        settings.javaScriptEnabled = true
                                        settings.domStorageEnabled = true
                                        settings.userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                                        
                                        addJavascriptInterface(object {
                                            @JavascriptInterface
                                            fun postData(json: String) {
                                                runOnUiThread {
                                                    appendLog("【爬虫收获数据】:\n$json")
                                                }
                                            }
                                            @JavascriptInterface
                                            fun log(msg: String) {
                                                runOnUiThread { appendLog("【网页内部】$msg") }
                                            }
                                        }, "NekoSpider")

                                        webViewClient = object : WebViewClient() {
                                            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                                isPageLoading = true
                                                appendLog("开始加载: $url")
                                            }

                                            override fun onPageFinished(view: WebView?, url: String?) {
                                                isPageLoading = false
                                                appendLog("🚀 页面 DOM 就绪，随时可注入探针。")
                                            }
                                        }
                                        loadUrl(targetUrl)
                                        webViewInstance = this
                                    }
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        OutlinedTextField(
                            value = spiderScript,
                            onValueChange = { spiderScript = it },
                            label = { Text("要注入的自动化抽取 JavaScript 脚本") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp),
                            textStyle = MaterialTheme.typography.bodySmall
                        )

                        Button(
                            enabled = !isPageLoading,
                            onClick = {
                                if (webViewInstance != null && spiderScript.isNotBlank()) {
                                    appendLog("正在向当前页面灌入自动化脚本...")
                                    webViewInstance?.evaluateJavascript(spiderScript) { evalResult ->
                                        appendLog("脚本注入回执 >> $evalResult")
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("立即向网页注入脚本并抓取")
                        }

                        HorizontalDivider()

                        Text("控制台输出日志：", style = MaterialTheme.typography.titleSmall)
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .background(Color(0xFF1E1E1E))
                                .padding(8.dp)
                        ) {
                            items(logs) { logItem ->
                                Text(
                                    text = logItem,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF00FF00)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
