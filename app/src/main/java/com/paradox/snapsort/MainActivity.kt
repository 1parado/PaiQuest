package com.paradox.snapsort

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.paradox.snapsort.net.LlmClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SnapSortApp() }
    }
}

sealed interface Screen {
    data object Capture : Screen
    data object Library : Screen
    data class Detail(val recordId: String) : Screen
    data object Settings : Screen
}

@Composable
fun SnapSortApp() {
    val ctx = LocalContext.current
    val store = remember { RecordStore(ctx) }
    val settings = remember { SettingsStore(ctx) }
    var screen by remember { mutableStateOf<Screen>(Screen.Capture) }

    MaterialTheme {
        when (val s = screen) {
            Screen.Capture -> CaptureScreen(
                store = store,
                onSaved = { screen = Screen.Detail(it) },
                onOpenLibrary = { screen = Screen.Library },
                onOpenSettings = { screen = Screen.Settings },
            )
            Screen.Library -> LibraryScreen(
                store = store,
                onOpen = { screen = Screen.Detail(it) },
                onBack = { screen = Screen.Capture },
            )
            is Screen.Detail -> DetailScreen(
                store = store,
                settings = settings,
                recordId = s.recordId,
                onBack = { screen = Screen.Library },
            )
            Screen.Settings -> SettingsScreen(
                settings = settings,
                onBack = { screen = Screen.Capture },
            )
        }
    }
}

// ---------------------------------------------------------------- 拍摄页

@Composable
fun CaptureScreen(
    store: RecordStore,
    onSaved: (String) -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

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

    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val imageCapture = remember {
        ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
    }
    val executor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }

    suspend fun process(source: File) = withContext(Dispatchers.IO) {
        status = "识别中：图像分析 + OCR…"
        try {
            val result = Classifier.classify(ctx, source)
            val record = store.save(source.readBytes(), result.categoryId, result.labels, result.ocrText)
            status = null
            withContext(Dispatchers.Main) { onSaved(record.id) }
        } catch (e: Exception) {
            status = null
            error = "处理失败：${e.message}"
        } finally {
            source.delete()
        }
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                val tmp = File(ctx.cacheDir, "pick_${System.currentTimeMillis()}.jpg")
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        tmp.outputStream().use { input.copyTo(it) }
                    }
                }
                process(tmp)
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (hasPermission) {
            AndroidView(
                factory = { c ->
                    val previewView = PreviewView(c)
                    val future = ProcessCameraProvider.getInstance(c)
                    future.addListener({
                        val provider = future.get()
                        val preview = Preview.Builder().build()
                            .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                        provider.unbindAll()
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageCapture,
                        )
                    }, ContextCompat.getMainExecutor(c))
                    previewView
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("需要相机权限才能拍摄", color = Color.White)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { permLauncher.launch(Manifest.permission.CAMERA) }) { Text("去授权") }
                }
            }
        }

        // 顶栏：图库 / 标题 / 设置
        Row(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(top = 20.dp, start = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onOpenLibrary) { Text("图库", color = Color.White) }
            Text(
                "拾集",
                color = Color.White,
                fontSize = 16.sp,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOpenSettings) { Text("设置", color = Color.White) }
        }

        // 底栏：相册 / 快门
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 36.dp, start = 48.dp, end = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(
                onClick = {
                    if (status == null) {
                        pickImage.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    }
                },
            ) { Text("相册", color = Color.White) }

            Box(
                Modifier
                    .size(72.dp)
                    .background(Color.White, CircleShape)
                    .clickable(enabled = status == null) {
                        val tmp = File(ctx.cacheDir, "capture_${System.currentTimeMillis()}.jpg")
                        imageCapture.takePicture(
                            ImageCapture.OutputFileOptions.Builder(tmp).build(),
                            executor,
                            object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                                    scope.launch { process(tmp) }
                                }
                                override fun onError(e: ImageCaptureException) {
                                    error = "拍摄失败：${e.message}"
                                }
                            },
                        )
                    },
            )

            Spacer(Modifier.width(64.dp))
        }

        // 识别中遮罩
        status?.let { st ->
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(Modifier.size(28.dp), color = Color.White)
                    Spacer(Modifier.height(12.dp))
                    Text(st, color = Color.White)
                }
            }
        }

        error?.let {
            Text(
                it,
                color = Color(0xFFFF8A80),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 130.dp),
            )
        }
    }
}

// ---------------------------------------------------------------- 图库页

@Composable
fun LibraryScreen(store: RecordStore, onOpen: (String) -> Unit, onBack: () -> Unit) {
    var filter by remember { mutableStateOf<String?>(null) }
    val records = remember(filter) { store.list(filter) }
    val counts = remember(records) { records.groupingBy { it.categoryId }.eachCount() }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("图库", fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("返回拍摄") }
        }

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val filters: List<String?> = listOf(null) + Categories.all.map { it.id }
            items(filters) { f ->
                FilterChip(
                    selected = filter == f,
                    onClick = { filter = f },
                    label = {
                        Text(
                            (if (f == null) "全部" else Categories.byId(f).label) +
                                " " + (if (f == null) records.size else counts[f] ?: 0),
                        )
                    },
                )
            }
        }

        if (records.isEmpty()) {
            Box(
                Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) { Text("还没有收藏，去拍一张吧") }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(96.dp),
                modifier = Modifier.weight(1f).padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                gridItems(records, key = { it.id }) { r ->
                    RecordThumb(
                        path = store.photoFile(r.id).absolutePath,
                        categoryLabel = Categories.byId(r.categoryId).label,
                        onClick = { onOpen(r.id) },
                    )
                }
            }
        }
    }
}

@Composable
fun RecordThumb(path: String, categoryLabel: String, onClick: () -> Unit) {
    val bitmap by produceState<Bitmap?>(null, path) {
        value = withContext(Dispatchers.IO) { decodeSampled(path, 256) }
    }
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
    ) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Text(
            categoryLabel,
            fontSize = 11.sp,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(4.dp)
                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                .padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}

// ---------------------------------------------------------------- 详情页

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(
    store: RecordStore,
    settings: SettingsStore,
    recordId: String,
    onBack: () -> Unit,
) {
    var record by remember(recordId) { mutableStateOf(store.get(recordId)) }
    val scope = rememberCoroutineScope()
    var aiLoading by remember { mutableStateOf(false) }
    var aiError by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var showSource by remember { mutableStateOf(false) }

    val r = record
    if (r == null) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            TextButton(onClick = onBack) { Text("返回") }
            Text("记录不存在")
        }
        return
    }

    val bitmap by produceState<Bitmap?>(null, r.id) {
        value = withContext(Dispatchers.IO) { decodeSampled(store.photoFile(r.id).absolutePath, 1080) }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← 图库") }
            Spacer(Modifier.weight(1f))
            Box {
                TextButton(onClick = { menuOpen = true }) {
                    Text("分类：${Categories.byId(r.categoryId).label} ▾")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    Categories.all.forEach { c ->
                        DropdownMenuItem(
                            text = { Text(c.label) },
                            onClick = {
                                store.move(r.id, c.id)
                                record = r.copy(categoryId = c.id)
                                menuOpen = false
                            },
                        )
                    }
                }
            }
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            bitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp)),
                )
            }
            Spacer(Modifier.height(12.dp))

            if (r.labels.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    r.labels.take(6).forEach { label ->
                        Text(
                            label,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            if (r.ocrText.isNotBlank()) {
                Text(
                    if (showSource) "收起识别文字 ▲" else "识别文字 ▼",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { showSource = !showSource },
                )
                if (showSource) {
                    Text(
                        r.ocrText,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                            .padding(12.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        scope.launch {
                            aiLoading = true
                            aiError = null
                            try {
                                val category = Categories.byId(r.categoryId)
                                val user =
                                    "图像识别标签：${if (r.labels.isEmpty()) "无" else r.labels.joinToString("、")}\n" +
                                        "图中识别文字：${r.ocrText.ifBlank { "无" }}"
                                val reply = withContext(Dispatchers.IO) {
                                    LlmClient.chat(
                                        settings.baseUrl,
                                        settings.apiKey,
                                        settings.model,
                                        category.aiPrompt,
                                        listOf("user" to user),
                                    )
                                }
                                store.saveNote(r.id, reply)
                                record = r.copy(note = reply)
                            } catch (e: Exception) {
                                aiError = e.message ?: "请求失败"
                            }
                            aiLoading = false
                        }
                    },
                    enabled = !aiLoading,
                ) { Text(if (r.note == null) "AI 讲解" else "重新讲解") }
                if (aiLoading) {
                    Spacer(Modifier.width(8.dp))
                    CircularProgressIndicator(Modifier.size(16.dp))
                }
            }
            aiError?.let {
                Text(
                    "出错：$it",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            r.note?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, fontSize = 15.sp, lineHeight = 24.sp)
            }
            Spacer(Modifier.height(24.dp))
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
            "密钥仅保存在本机，不会上传，也不会写入代码仓库。识别与分类全部在手机端离线完成，AI 讲解为可选功能。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------- 工具

private fun decodeSampled(path: String, req: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= req && bounds.outHeight / (sample * 2) >= req) sample *= 2
    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}
