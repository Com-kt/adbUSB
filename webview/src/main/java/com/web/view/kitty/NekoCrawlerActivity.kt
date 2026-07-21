package com.web.view.kitty

import com.web.view.kitty.ui.theme.*

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Bundle
import android.os.Environment
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.net.toUri
import coil.compose.AsyncImage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.InetAddress
import java.net.URL
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
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        )
        setContent {
            NekoTheme {
                var targetUrl by mutableStateOf("https://baidu.com")
                
                var spiderScript by mutableStateOf(
                    """
                    (async function() {
                        // 1. 强力隐藏自动化特征（过 navigator.webdriver 检测）
                        Object.defineProperty(navigator, 'webdriver', { get: () => undefined });
                        window.navigator.chrome = { runtime: {} };
                        Object.defineProperty(navigator, 'languages', { get: () => ['zh-CN', 'zh', 'en-US', 'en'] });
                        Object.defineProperty(navigator, 'plugins', { get: () => [1, 2, 3, 4, 5] });

                        // 2. 模拟人类浏览停顿，防并发机器特征
                        await new Promise(r => setTimeout(r, Math.floor(Math.random() * 1200) + 800));

                        let assets = {
                            "图片列表": [],
                            "视频及流媒体": [],
                            "下载文件": []
                        };

                        document.querySelectorAll('img').forEach(img => {
                            let src = img.src || img.getAttribute('data-src') || img.getAttribute('data-original');
                            if (src && src.startsWith('http') && !assets["图片列表"].includes(src)) {
                                assets["图片列表"].push(src);
                            }
                        });

                        document.querySelectorAll('video, video source').forEach(vid => {
                            let src = vid.src;
                            if (src && src.startsWith('http') && !assets["视频及流媒体"].includes(src)) {
                                assets["视频及流媒体"].push(src);
                            }
                        });

                        let fileSuffixes = ['.pdf', '.zip', '.rar', '.apk', '.docx', '.xlsx', '.mp3'];
                        document.querySelectorAll('a').forEach(a => {
                            let href = a.href;
                            if (href && fileSuffixes.some(s => href.toLowerCase().includes(s))) {
                                if (!assets["下载文件"].includes(href)) {
                                    assets["下载文件"].push(href);
                                }
                            }
                        });

                        // 3. 使用无痕控制台通道回传数据（不留任何全局变量 window 痕迹）
                        console.log("NEKO_DATA_BRIDGE:" + JSON.stringify(assets));
                        return "隐身嗅探脚本触发成功！";
                    })();
                    """.trimIndent()
                )
                
                val logs = remember { mutableStateListOf<LogEntry>() }
                val coroutineScope = rememberCoroutineScope()
                val listState = rememberLazyListState()

                var currentAgent by remember { mutableStateOf(WebAgentMode.MOBILE) }
                var agentMenuExpanded by remember { mutableStateOf(false) }

                var selectedTab by remember { mutableIntStateOf(0) }
                val tabs = listOf("网页", "脚本", "日志看板")

                fun getCurrentTime() = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

                fun appendTextLog(msg: String, color: Color = Color(0xFF00FF00)) {
                    if (logs.size > 150) {
                        logs.removeAt(0)
                    }
                    logs.add(LogEntry.Text(getCurrentTime(), msg, color))
                    coroutineScope.launch { if (logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1) }
                }

                val androidContext = LocalContext.current
                fun downloadMediaAsset(url: String, prefix: String) {
                    try {
                        val cleanFileName = url.substringAfterLast("/").substringBefore("?").ifBlank { "neko_${System.currentTimeMillis()}" }
                        val request = DownloadManager.Request(url.toUri()).apply {
                            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                            setTitle("$prefix: $cleanFileName")
                            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, cleanFileName)
                        }
                        val dm = androidContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                        dm.enqueue(request)
                        Toast.makeText(androidContext, "已加入后台下载队列", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(androidContext, "下载触发失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }

                var webViewInstance by remember { mutableStateOf<WebView?>(null) }
                var isPageLoading by remember { mutableStateOf(false) }
                var pageTimeoutJob by remember { mutableStateOf<Job?>(null) }

                DisposableEffect(Unit) {
                    onDispose {
                        pageTimeoutJob?.cancel()
                        webViewInstance?.let { wv ->
                            (wv.parent as? ViewGroup)?.removeView(wv)
                            wv.stopLoading()
                            wv.clearHistory()
                            wv.clearCache(true)
                            wv.destroy()
                        }
                        webViewInstance = null
                    }
                }

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("🕷️ 隐身多媒体爬虫 (Anti-Bot)") },
                            navigationIcon = {
                                IconButton(onClick = {
                                    if (webViewInstance?.canGoBack() == true) {
                                        webViewInstance?.goBack()
                                    } else {
                                        finish()
                                    }
                                }) {
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
                    ) {
                        PrimaryTabRow(selectedTabIndex = selectedTab) {
                            tabs.forEachIndexed { index, title ->
                                Tab(
                                    selected = selectedTab == index,
                                    onClick = { selectedTab = index },
                                    text = { Text(title, style = MaterialTheme.typography.bodyMedium) }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            val isTab0Active = (selectedTab == 0)
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .zIndex(if (isTab0Active) 1f else 0f)
                                    .graphicsLayer { alpha = if (isTab0Active) 1f else 0f },
                                contentAlignment = Alignment.TopCenter
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(modifier = Modifier.fillMaxWidth()) {
                                        Row(
                                            modifier = Modifier.clickable { agentMenuExpanded = true }.padding(vertical = 2.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("🌐 网页标识识别: ${currentAgent.title} ▾", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                                        }
                                        DropdownMenu(expanded = agentMenuExpanded, onDismissRequest = { agentMenuExpanded = false }) {
                                            WebAgentMode.entries.forEach { mode ->
                                                DropdownMenuItem(
                                                    text = { Text(mode.title) },
                                                    onClick = {
                                                        currentAgent = mode
                                                        agentMenuExpanded = false
                                                        webViewInstance?.let { wv ->
                                                            val currentWebUrl = wv.url.takeIf { !it.isNullOrBlank() && it != "about:blank" } ?: targetUrl
                                                            wv.settings.userAgentString = mode.ua
                                                            wv.loadUrl(currentWebUrl)
                                                        }
                                                        appendTextLog("【系统】已切换为 [${mode.title}] 并重载网页...")
                                                    }
                                                )
                                            }
                                        }
                                    }

                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        OutlinedTextField(
                                            value = targetUrl,
                                            onValueChange = { targetUrl = it },
                                            label = { Text("目标 URL") },
                                            modifier = Modifier.weight(1f),
                                            singleLine = true,
                                            textStyle = MaterialTheme.typography.bodySmall
                                        )
                                        Button(onClick = { 
                                            webViewInstance?.let { wv ->
                                                wv.loadUrl("about:blank")
                                                wv.loadUrl(targetUrl.trim()) 
                                            }
                                        }) { Text("前往") }
                                    }

                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .weight(1f)
                                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
                                    ) {
                                        AndroidView(
                                            factory = { context ->
                                                WebView(context).apply {
                                                    settings.javaScriptEnabled = true
                                                    settings.domStorageEnabled = true
                                                    settings.userAgentString = currentAgent.ua
                                                    settings.useWideViewPort = true
                                                    settings.loadWithOverviewMode = true
                                                    
                                                    settings.setSupportZoom(true)
                                                    settings.builtInZoomControls = true
                                                    settings.displayZoomControls = false 

                                                    settings.javaScriptCanOpenWindowsAutomatically = false
                                                    settings.setSupportMultipleWindows(false)
                                                    
                                                    webChromeClient = object : WebChromeClient() {
                                                        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                                                            val msg = consoleMessage?.message() ?: ""
                                                            if (msg.startsWith("NEKO_DATA_BRIDGE:")) {
                                                                val json = msg.removePrefix("NEKO_DATA_BRIDGE:")
                                                                runOnUiThread {
                                                                    try {
                                                                        val obj = JSONObject(json)
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

                                                                        appendTextLog("【系统】隐身嗅探资产清洗完毕，已渲染进看板面板。", Color.Yellow)
                                                                    } catch (e: Exception) {
                                                                        appendTextLog("【解构异常】数据格式错误: ${e.message}", Color.Red)
                                                                    }
                                                                }
                                                                return true
                                                            }
                                                            return super.onConsoleMessage(consoleMessage)
                                                        }
                                                    }

                                                    webViewClient = object : WebViewClient() {
                                                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                                            isPageLoading = true
                                                            appendTextLog("开始加载: $url")

                                                            pageTimeoutJob?.cancel()
                                                            pageTimeoutJob = coroutineScope.launch {
                                                                delay(12000)
                                                                if (isPageLoading) {
                                                                    runOnUiThread {
                                                                        view?.stopLoading()
                                                                        isPageLoading = false
                                                                        appendTextLog("【⚠️ 超时中断】单页加载超过12秒，强制停止以防 OOM！", Color.Yellow)
                                                                    }
                                                                }
                                                            }
                                                        }

                                                        override fun onPageFinished(view: WebView?, url: String?) {
                                                            isPageLoading = false
                                                            pageTimeoutJob?.cancel()
                                                            appendTextLog("🚀 隐身 DOM 就绪，环境指纹已伪装。")
                                                            
                                                            if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                                                                thread {
                                                                    try {
                                                                        val host = url.toUri().host ?: return@thread
                                                                        val ips = InetAddress.getAllByName(host).joinToString(", ") { it.hostAddress ?: "" }
                                                                        
                                                                        val publicIp = try {
                                                                            URL("https://api.ipify.org").readText(Charsets.UTF_8)
                                                                        } catch (e: Exception) {
                                                                            "探测失败"
                                                                        }

                                                                        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                                                                        val dns = cm.getLinkProperties(cm.activeNetwork)?.dnsServers?.joinToString(", ") { it.hostAddress ?: "" } ?: "未知"
                                                                        
                                                                        runOnUiThread {
                                                                            appendTextLog("【网络层】本机公网出口 IP: [$publicIp]", Color(0xFF00BFFF))
                                                                            appendTextLog("【网络DNS】主机: $host -> 实际服务器IP: [$ips]")
                                                                            appendTextLog("【网络DNS】本地系统 DNS 服务器: [$dns]")
                                                                        }
                                                                    } catch (e: Exception) {
                                                                        runOnUiThread { appendTextLog("【网络分析失败】: ${e.message}", Color.Red) }
                                                                    }
                                                                }
                                                            }
                                                        }

                                                        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                                                            runOnUiThread {
                                                                appendTextLog("【💥 渲染熔断】当前网页占用内存超标已被系统熔断！已释放内存。", Color.Red)
                                                            }
                                                            view?.let {
                                                                (it.parent as? ViewGroup)?.removeView(it)
                                                                it.destroy()
                                                            }
                                                            webViewInstance = null
                                                            return true
                                                        }

                                                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                                            val url = request?.url?.toString() ?: return false
                                                            if (url.startsWith("http://") || url.startsWith("https://")) {
                                                                return false 
                                                            }
                                                            runOnUiThread { 
                                                                appendTextLog("【🚨 强力拦截】已成功阻止网页拉起外部 App（协议: ${url.substringBefore(":")}）！", Color(0xFFFF4500)) 
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
                                }
                            }

                            val isTab1Active = (selectedTab == 1)
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .zIndex(if (isTab1Active) 1f else 0f)
                                    .graphicsLayer { alpha = if (isTab1Active) 1f else 0f }
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text("编写或修改隐身自动化多媒体嗅探脚本 (JS)：", style = MaterialTheme.typography.titleSmall)
                                    
                                    OutlinedTextField(
                                        value = spiderScript,
                                        onValueChange = { spiderScript = it },
                                        label = { Text("JavaScript 源码") },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .weight(1f),
                                        textStyle = MaterialTheme.typography.bodySmall
                                    )

                                    Button(
                                        enabled = !isPageLoading,
                                        onClick = {
                                            if (webViewInstance != null && spiderScript.isNotBlank()) {
                                                appendTextLog("正在向当前页面灌入隐身探针...")
                                                webViewInstance?.evaluateJavascript(spiderScript) { res -> appendTextLog("执行状态 >> $res") }
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text("立即隐身嗅探图片/视频/文件") }
                                }
                            }

                            val isTab2Active = (selectedTab == 2)
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .zIndex(if (isTab2Active) 1f else 0f)
                                    .graphicsLayer { alpha = if (isTab2Active) 1f else 0f }
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("实时多模态资产看板：", style = MaterialTheme.typography.titleSmall)
                                        TextButton(onClick = { logs.clear() }) {
                                            Text("清空日志", style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                    
                                    LazyColumn(
                                        state = listState,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .weight(1f)
                                            .background(Color(0xFF1E1E1E))
                                            .padding(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        items(logs) { item ->
                                            when (item) {
                                                is LogEntry.Text -> {
                                                    Text(text = "${item.time} ${item.message}", style = MaterialTheme.typography.bodySmall, color = item.color)
                                                }
                                                is LogEntry.ImageGallery -> {
                                                    Column {
                                                        Text(text = "${item.time} 📸 抓取到图片阵列 (${item.urls.size}张) [点击图片直接保存]:", style = MaterialTheme.typography.bodySmall, color = Color.Yellow)
                                                        Spacer(modifier = Modifier.height(4.dp))
                                                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                            items(item.urls) { url ->
                                                                Box(
                                                                    modifier = Modifier
                                                                        .size(85.dp)
                                                                        .border(1.dp, Color.Gray)
                                                                        .clickable {
                                                                            downloadMediaAsset(url, "保存抓取图片")
                                                                        }
                                                                ) {
                                                                    AsyncImage(
                                                                        model = url,
                                                                        contentDescription = "爬虫图片",
                                                                        modifier = Modifier.fillMaxSize(),
                                                                        contentScale = ContentScale.Crop
                                                                    )
                                                                    Box(
                                                                        modifier = Modifier
                                                                            .align(Alignment.BottomEnd)
                                                                            .background(Color.Black.copy(alpha = 0.7f))
                                                                            .padding(horizontal = 4.dp, vertical = 2.dp)
                                                                    ) {
                                                                        Text("💾保存", style = MaterialTheme.typography.labelSmall, color = Color.White)
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                                is LogEntry.VideoList -> {
                                                    Column {
                                                        Text(text = "${item.time} 🎬 嗅探到在线流媒体/视频 (${item.urls.size}个):", style = MaterialTheme.typography.bodySmall, color = Color(0xFFFF69B4))
                                                        item.urls.forEach { url ->
                                                            Card(
                                                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                                                colors = CardDefaults.cardColors(containerColor = Color(0xFF2D2D2D))
                                                            ) {
                                                                Row(
                                                                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                                    verticalAlignment = Alignment.CenterVertically
                                                                ) {
                                                                    Text(
                                                                        text = "▶️ ${url.substringAfterLast("/").substringBefore("?")}",
                                                                        style = MaterialTheme.typography.bodySmall,
                                                                        color = Color(0xFFFFB6C1),
                                                                        modifier = Modifier.weight(1f),
                                                                        maxLines = 1
                                                                    )
                                                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                                        Button(
                                                                            onClick = {
                                                                                try {
                                                                                    val intent = Intent(Intent.ACTION_VIEW).apply { setDataAndType(url.toUri(), "video/*") }
                                                                                    androidContext.startActivity(intent)
                                                                                } catch (e: Exception) {
                                                                                    Toast.makeText(androidContext, "找不到合适的视频播放器", Toast.LENGTH_SHORT).show()
                                                                                }
                                                                            },
                                                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                                            modifier = Modifier.height(30.dp)
                                                                        ) {
                                                                            Text("播放", style = MaterialTheme.typography.labelSmall)
                                                                        }
                                                                        Button(
                                                                            onClick = {
                                                                                downloadMediaAsset(url, "保存视频")
                                                                            },
                                                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                                            modifier = Modifier.height(30.dp),
                                                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                                                                        ) {
                                                                            Text("保存", style = MaterialTheme.typography.labelSmall)
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                                is LogEntry.FileList -> {
                                                    Column {
                                                        Text(text = "${item.time} 📁 截获敏感后缀可下载文件 (${item.urls.size}个):", style = MaterialTheme.typography.bodySmall, color = Color(0xFF00FFFF))
                                                        item.urls.forEach { url ->
                                                            Card(
                                                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                                                colors = CardDefaults.cardColors(containerColor = Color(0xFF2D2D2D))
                                                            ) {
                                                                Row(
                                                                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                                    verticalAlignment = Alignment.CenterVertically
                                                                ) {
                                                                    Text(
                                                                        text = "💾 ${url.substringAfterLast("/").substringBefore("?")}",
                                                                        style = MaterialTheme.typography.bodySmall,
                                                                        color = Color(0xFF00FFFF),
                                                                        modifier = Modifier.weight(1f),
                                                                        maxLines = 1
                                                                    )
                                                                    Button(
                                                                        onClick = {
                                                                            downloadMediaAsset(url, "保存文件")
                                                                        },
                                                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                                        modifier = Modifier.height(30.dp)
                                                                    ) {
                                                                        Text("直接保存", style = MaterialTheme.typography.labelSmall)
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
                }
            }
        }
    }
}
