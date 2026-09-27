package com.paradox.snapask

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.paradox.snapask.net.LlmClient
import com.paradox.snapask.ocr.OcrAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SnapAskApp() }
    }
}

sealed interface Screen {
    data object Camera : Screen
    data class Result(val ocrText: String) : Screen
    data object Settings : Screen
}

@Composable
fun SnapAskApp() {
    val ctx = LocalContext.current
    val settings = remember { SettingsStore(ctx) }
    var screen by remember { mutableStateOf<Screen>(Screen.Camera) }
    var sceneId by remember { mutableStateOf(Scenes.all.first().id) }

    MaterialTheme {
        when (val s = screen) {
            Screen.Camera -> CameraScreen(
                sceneId = sceneId,
                onSceneChange = { sceneId = it },
                onCaptured = { text -> screen = Screen.Result(text) },
                onOpenSettings = { screen = Screen.Settings },
            )
            is Screen.Result -> ResultScreen(
                ocrText = s.ocrText,
                scene = Scenes.byId(sceneId),
                settings = settings,
                onRetake = { screen = Screen.Camera },
            )
            Screen.Settings -> SettingsScreen(
                settings = settings,
                onBack = { screen = Screen.Camera },
            )
        }
    }
}

// ---------------------------------------------------------------- 相机页

@Composable
fun CameraScreen(
    sceneId: String,
    onSceneChange: (String) -> Unit,
    onCaptured: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasPermission = it
    }
    LaunchedEffect(Unit) {
        if (!hasPermission) permLauncher.launch(Manifest.permission.CAMERA)
    }

    if (!hasPermission) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("需要相机权限才能拍摄识别")
                Spacer(Modifier.height(12.dp))
                Button(onClick = { permLauncher.launch(Manifest.permission.CAMERA) }) { Text("去授权") }
            }
        }
        return
    }

    val analyzer = remember { OcrAnalyzer() }
    val executor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }
    SideEffect { analyzer.onText = onCaptured }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { c ->
                val previewView = PreviewView(c)
                val future = ProcessCameraProvider.getInstance(c)
                future.addListener({
                    val provider = future.get()
                    val preview = Preview.Builder().build()
                        .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { it.setAnalyzer(executor, analyzer) }
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                }, ContextCompat.getMainExecutor(c))
                previewView
            },
            modifier = Modifier.fillMaxSize(),
        )

        // 顶部：场景切换（泛化入口）
        LazyRow(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 20.dp, start = 12.dp, end = 80.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(Scenes.all) { s ->
                FilterChip(
                    selected = s.id == sceneId,
                    onClick = { onSceneChange(s.id) },
                    label = { Text(s.label) },
                )
            }
        }

        TextButton(
            onClick = onOpenSettings,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 16.dp, end = 4.dp),
        ) { Text("设置", color = Color.White) }

        // 底部：快门（按下才触发一次 OCR）
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 36.dp)
                .size(72.dp)
                .background(Color.White, CircleShape)
                .clickable { analyzer.armed = true },
        )
    }
}

// ---------------------------------------------------------------- 结果页

@Composable
fun ResultScreen(
    ocrText: String,
    scene: Scene,
    settings: SettingsStore,
    onRetake: () -> Unit,
) {
    var answer by remember(ocrText, scene.id) { mutableStateOf<String?>(null) }
    var loading by remember(ocrText, scene.id) { mutableStateOf(true) }
    var error by remember(ocrText, scene.id) { mutableStateOf<String?>(null) }
    var showSource by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    val history = remember(ocrText, scene.id) { mutableStateListOf<Pair<String, String>>() }
    val scope = rememberCoroutineScope()

    suspend fun ask(content: String) {
        loading = true
        error = null
        history.add("user" to content)
        try {
            val reply = withContext(Dispatchers.IO) {
                LlmClient.chat(settings.baseUrl, settings.apiKey, settings.model, scene.systemPrompt, history.toList())
            }
            history.add("assistant" to reply)
            answer = reply
        } catch (e: Exception) {
            history.removeAt(history.lastIndex)
            error = e.message ?: "请求失败"
        }
        loading = false
    }

    LaunchedEffect(ocrText, scene.id) {
        ask("以下是照片中识别到的文字，请按要求讲解：\n$ocrText")
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(scene.label, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onRetake) { Text("重新拍摄") }
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            when {
                loading -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 24.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("分析中…")
                }
                error != null -> Column {
                    Text(
                        "出错了：$error",
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = {
                        scope.launch { ask(history.lastOrNull()?.second ?: "以下是照片中识别到的文字，请按要求讲解：\n$ocrText") }
                    }) { Text("重试") }
                }
                answer != null -> Text(
                    answer!!,
                    fontSize = 15.sp,
                    lineHeight = 24.sp,
                )
            }

            Spacer(Modifier.height(16.dp))
            Text(
                if (showSource) "收起原文 ▲" else "查看原文 ▼",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { showSource = !showSource },
            )
            if (showSource) {
                Text(
                    ocrText.ifBlank { "（未识别到文字）" },
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                        .padding(12.dp),
                )
            }
            Spacer(Modifier.height(80.dp))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("继续追问…") },
                maxLines = 3,
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    if (input.isNotBlank() && !loading) {
                        val q = input
                        input = ""
                        scope.launch { ask(q) }
                    }
                },
                enabled = !loading && input.isNotBlank(),
            ) { Text("发送") }
        }
    }
}

// ---------------------------------------------------------------- 设置页

@Composable
fun SettingsScreen(settings: SettingsStore, onBack: () -> Unit) {
    var baseUrl by remember { mutableStateOf(settings.baseUrl) }
    var apiKey by remember { mutableStateOf(settings.apiKey) }
    var model by remember { mutableStateOf(settings.model) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("设置", fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("返回") }
        }
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("API 地址（OpenAI 兼容，不含 /chat/completions）") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("API Key") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            label = { Text("模型名") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(16.dp))

        Button(
            onClick = {
                settings.baseUrl = baseUrl
                settings.apiKey = apiKey
                settings.model = model
                onBack()
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("保存") }

        Spacer(Modifier.height(8.dp))
        Text(
            "密钥仅保存在本机，不会上传，也不会写入代码仓库。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
