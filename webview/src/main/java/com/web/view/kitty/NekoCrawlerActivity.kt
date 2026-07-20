package com.web.view.kitty

import com.web.view.kitty.ui.theme.*

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

sealed class LogEntry {
    abstract val time: String
    
    data class Text(override val time: String, val message: String, val color: Color = Color(0xFF00FF00)) : LogEntry()

    data class ImageGallery(override val time: String, val urls: List<String>) : LogEntry()

    data class VideoList(override val time: String, val urls: List<String>) : LogEntry()

    data class FileList(override val time: String, val urls: List<String>) : LogEntry()
}

enum class WebAgentMode(val title: String, val ua: String) {
    MOBILE("手机标准版", "Mozilla/5.0 (Linux; Android 16; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Mobile Safari/537.36"),
    PC_WINDOWS("电脑版 Windows", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36"),
    PC_MAC("电脑版 Mac", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36"),
    PC_LINUX("电脑版 Linux", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36")
}

class NekoCrawlerActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        )
        setContent {
            NekoTheme {
                var targetUrl by remember { mutableStateOf("https://m.baidu.com") }
                
                var spiderScript by remember {
                    mutableStateOf(
                        """
                        (async function() {
                            let assets = {
                                "图片列表": [],
                                "视频及流媒体": [],
                                "下载文件": [],
                                "嗅探当前公网IP": "正在探测..."
                            };

                            // 嗅探图片（兼容懒加载）
                            document.querySelectorAll('img').forEach(img => {
                                let src = img.src || img.getAttribute('data-src') || img.getAttribute('data-original');
                                if (src && src.startsWith('http') && !assets["图片列表"].includes(src)) {
                                    assets["图片列表"].push(src);
                                }
                            });

                            // 嗅探视频
                            document.querySelectorAll('video, video source').forEach(vid => {
                                let src = vid.src;
                                if (src && src.startsWith('http') && !assets["视频及流媒体"].includes(src)) {
                                    assets["视频及流媒体"].push(src);
                                }
                            });

                            // 过滤敏感后缀文件
                            let fileSuffixes = ['.pdf', '.zip', '.rar', '.apk', '.docx', '.xlsx', '.mp3'];
                            document.querySelectorAll('a').forEach(a => {
                                let href = a.href;
                                if (href && fileSuffixes.some(s => href.toLowerCase().includes(s))) {
                                    if (!assets["下载文件"].includes(href)) {
                                        assets["下载文件"].push(href);
                                    }
                                }
                            });

                            // 反查公网IP
                            try {
                                let res = await fetch('https://api.ipify.org?format=json');
                                let json = await res.json();
                                assets["嗅探当前公网IP"] = json.ip;
                            } catch (e) {
                                assets["嗅探当前公网IP"] = "检测失败";
                            }

                            window.NekoSpider.postData(JSON.stringify(assets)); 
                            return "嗅探脚本触发成功！";
                        })();
                        """.trimIndent()
                    )
                }
                
                val logs = remember { mutableStateListOf<LogEntry>() }
                val coroutineScope = rememberCoroutineScope()
                val listState = rememberLazyListState()

                var currentAgent by remember { mutableStateOf(WebAgentMode.MOBILE) }
                var agentMenuExpanded by remember { mutableStateOf(false) }

                fun getCurrentTime() = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

                fun appendTextLog(msg: String, color: Color = Color(0xFF00FF00)) {
                    logs.add(LogEntry.Text(getCurrentTime(), msg, color))
                    coroutineScope.launch { if (logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1) }
                }

                var webViewInstance by remember { mutableStateOf<WebView?>(null) }
                var isPageLoading by remember { mutableStateOf(false) }

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("🕷️ 网页自动化多媒体爬虫") },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                                }
                            }
                        )
                    }
                ) { innerPadding ->
                    Column(
                        modifier = Modifier.fillMaxSize().padding(innerPadding).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.clickable { agentMenuExpanded = true }.padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("🌐 网页标识识别: ${currentAgent.title} ▾", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                            }
                            DropdownMenu(expanded = agentMenuExpanded, onDismissRequest = { agentMenuExpanded = false }) {
                                WebAgentMode.values().forEach { mode ->
                                    DropdownMenuItem(
                                        text = { Text(mode.title) },
                                        onClick = {
                                            currentAgent = mode
                                            agentMenuExpanded = false
                                            webViewInstance?.settings?.userAgentString = mode.ua
                                            webViewInstance?.reload()
                                            appendTextLog("【系统】已切换为 Chrome 150 [${mode.title}] 并重载网页...")
                                        }
                                    )
                                }
                            }
                        }

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(value = targetUrl, onValueChange = { targetUrl = it }, label = { Text("目标 URL") }, modifier = Modifier.weight(1f), singleLine = true, textStyle = MaterialTheme.typography.bodySmall)
                            Button(onClick = { webViewInstance?.loadUrl(targetUrl.trim()) }) { Text("前往") }
                        }

                        Box(modifier = Modifier.fillMaxWidth().height(200.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                            AndroidView(
                                factory = { context ->
                                    WebView(context).apply {
                                        settings.javaScriptEnabled = true
                                        settings.domStorageEnabled = true
                                        settings.userAgentString = currentAgent.ua
                                        settings.useWideViewPort = true
                                        settings.loadWithOverviewMode = true
                                        
                                        settings.javaScriptCanOpenWindowsAutomatically = false
                                        settings.setSupportMultipleWindows(false)
                                        
                                        addJavascriptInterface(object {
                                            @JavascriptInterface
                                            fun postData(json: String) {
                                                runOnUiThread {
                                                    try {
                                                        val obj = JSONObject(json)
                                                        val ip = obj.optString("嗅探当前公网IP")
                                                        appendTextLog("【网络层】本地嗅探公网出口 IP: $ip", Color(0xFF00BFFF))

                                                        val imgArr = obj.optJSONArray("图片列表")
                                                        val imgList = mutableListOf<String>()
                                                        if (imgArr != null) {
                                                            for (i in 0 until imgArr.length()) imgList.add(imgArr.getString(i))
                                                        }
                                                        if (imgList.isNotEmpty()) {
                                                            logs.add(LogEntry.ImageGallery(getCurrentTime(), imgList))
                                                        }

                                                        val vidArr = obj.optJSONArray("视频及流媒体")
                                                        val vidList = mutableListOf<String>()
                                                        if (vidArr != null) {
                                                            for (i in 0 until vidArr.length()) vidList.add(vidArr.getString(i))
                                                        }
                                                        if (vidList.isNotEmpty()) {
                                                            logs.add(LogEntry.VideoList(getCurrentTime(), vidList))
                                                        }

                                                        val fileArr = obj.optJSONArray("下载文件")
                                                        val fileList = mutableListOf<String>()
                                                        if (fileArr != null) {
                                                            for (i in 0 until fileArr.length()) fileList.add(fileArr.getString(i))
                                                        }
                                                        if (fileList.isNotEmpty()) {
                                                            logs.add(LogEntry.FileList(getCurrentTime(), fileList))
                                                        }

                                                        appendTextLog("【系统】多媒体资产清洗完毕，已渲染进看板面板。", Color.Yellow)

                                                    } catch (e: Exception) {
                                                        appendTextLog("【解构异常】数据格式错误: ${e.message}", Color.Red)
                                                    }
                                                }
                                            }
                                        }, "NekoSpider")

                                        webViewClient = object : WebViewClient() {
                                            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                                isPageLoading = true
                                                appendTextLog("开始加载: $url")
                                            }

                                            override fun onPageFinished(view: WebView?, url: String?) {
                                                isPageLoading = false
                                                appendTextLog("🚀 页面 DOM 就绪，随时可注入探针。")
                                                
                                                if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                                                    thread {
                                                        try {
                                                            val host = url.toUri().host ?: return@thread
                                                            val ips = InetAddress.getAllByName(host).joinToString(", ") { it.hostAddress ?: "" }
                                                            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                                                            val dns = cm.getLinkProperties(cm.activeNetwork)?.dnsServers?.joinToString(", ") { it.hostAddress ?: "" } ?: "未知"
                                                            runOnUiThread {
                                                                appendTextLog("【网络DNS】主机: $host -> 实际服务器IP: [$ips]")
                                                                appendTextLog("【网络DNS】本地系统 DNS 服务器: [$dns]")
                                                            }
                                                        } catch (e: Exception) {
                                                            runOnUiThread { appendTextLog("【DNS分析失败】: ${e.message}", Color.Red) }
                                                        }
                                                    }
                                                }
                                            }

                                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                                val url = request?.url?.toString() ?: return false
                                                
                                                if (url.startsWith("http://") || url.startsWith("https://")) {
                                                    return false
                                                }
                                                
                                                runOnUiThread {
                                                    appendTextLog("【安全拦截】检测到网页试图拉起外部 App，已成功拦截！协议: ${url.substringBefore(":")}://", Color(0xFFFF4500))
                                                }
                                                
                                                return true
                                            }
                                        }
                                        loadUrl(targetUrl)
                                        webViewInstance = this
                                    }
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        OutlinedTextField(value = spiderScript, onValueChange = { spiderScript = it }, label = { Text("多媒体自动化抓取脚本 (JS)") }, modifier = Modifier.fillMaxWidth().height(110.dp), textStyle = MaterialTheme.typography.bodySmall)

                        Button(
                            enabled = !isPageLoading,
                            onClick = {
                                if (webViewInstance != null && spiderScript.isNotBlank()) {
                                    appendTextLog("正在向当前页面灌入多媒体探针...")
                                    webViewInstance?.evaluateJavascript(spiderScript) { res -> appendTextLog("执行状态 >> $res") }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("立即嗅探图片/视频/文件") }

                        HorizontalDivider()

                        Text("实时多模态资产看板：", style = MaterialTheme.typography.titleSmall)
                        
                        val androidContext = LocalContext.current
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxWidth().weight(1f).background(Color(0xFF1E1E1E)).padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(logs) { item ->
                                when (item) {
                                    is LogEntry.Text -> {
                                        Text(text = "${item.time} ${item.message}", style = MaterialTheme.typography.bodySmall, color = item.color)
                                    }
                                    is LogEntry.ImageGallery -> {
                                        Column {
                                            Text(text = "${item.time} 📸 抓取到图片阵列预览 (${item.urls.size}张):", style = MaterialTheme.typography.bodySmall, color = Color.Yellow)
                                            Spacer(modifier = Modifier.height(4.dp))
                                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                items(item.urls) { url ->
                                                    AsyncImage(
                                                        model = url,
                                                        contentDescription = "爬虫图片",
                                                        modifier = Modifier.size(80.dp).border(1.dp, Color.Gray).clickable {
                                                            val intent = Intent(Intent.ACTION_VIEW, url.toUri())
                                                            androidContext.startActivity(intent)
                                                        },
                                                        contentScale = ContentScale.Crop
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    is LogEntry.VideoList -> {
                                        Column {
                                            Text(text = "${item.time} 🎬 嗅探到在线流媒体/视频 (${item.urls.size}个):", style = MaterialTheme.typography.bodySmall, color = Color(0xFFFF69B4))
                                            item.urls.forEach { url ->
                                                Card(
                                                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).clickable {
                                                        try {
                                                            val intent = Intent(Intent.ACTION_VIEW).apply { setDataAndType(url.toUri(), "video/*") }
                                                            androidContext.startActivity(intent)
                                                        } catch (e: Exception) {
                                                            Toast.makeText(androidContext, "找不到合适的视频播放器", Toast.LENGTH_SHORT).show()
                                                        }
                                                    },
                                                    colors = CardDefaults.cardColors(containerColor = Color(0.dp.value.toInt()))
                                                ) {
                                                    Text(text = "▶️ 点击播放视频: $url", style = MaterialTheme.typography.bodySmall, color = Color(0xFFFFB6C1), modifier = Modifier.padding(6.dp))
                                                }
                                            }
                                        }
                                    }
                                    is LogEntry.FileList -> {
                                        Column {
                                            Text(text = "${item.time} 📁 截获敏感后缀可下载文件 (${item.urls.size}个):", style = MaterialTheme.typography.bodySmall, color = Color(0xFF00FFFF))
                                            item.urls.forEach { url ->
                                                Card(
                                                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).clickable {
                                                        try {
                                                            val request = DownloadManager.Request(url.toUri()).apply {
                                                                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                                                setTitle("爬虫截获文件下载")
                                                            }
                                                            val dm = androidContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                                                            dm.enqueue(request)
                                                            Toast.makeText(androidContext, "已触发后台下载机制", Toast.LENGTH_SHORT).show()
                                                        } catch (e: Exception) {
                                                            Toast.makeText(androidContext, "触发下载失败", Toast.LENGTH_SHORT).show()
                                                        }
                                                    },
                                                    colors = CardDefaults.cardColors(containerColor = Color(0.dp.value.toInt()))
                                                ) {
                                                    Text(text = "💾 点击下载附件: ${url.substringAfterLast("/")}", style = MaterialTheme.typography.bodySmall, color = Color(0xE000FFFF), modifier = Modifier.padding(6.dp))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
