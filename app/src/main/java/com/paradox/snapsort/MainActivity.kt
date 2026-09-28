package com.paradox.snapsort

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.snapshotFlow
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
    data object CategoryManage : Screen
    data object Trash : Screen
    data object Settings : Screen
}

@Composable
fun SnapSortApp() {
    val ctx = LocalContext.current
    val store = remember { RecordStore(ctx) }
    val settings = remember { SettingsStore(ctx) }
    var screen by remember { mutableStateOf<Screen>(Screen.Capture) }

    // 用户自建分类（内置分类之外可无限扩展）
    var customCats by remember { mutableStateOf(settings.customCategoryLabels()) }
    val allCats = listOf(Categories.UNCATEGORIZED) + Categories.builtin + customCats.map { Categories.custom(it) }

    val addCategory: (String) -> Unit = { label ->
        if (settings.addCustomCategory(label)) customCats = settings.customCategoryLabels()
    }
    val renameCategory: (String, String) -> Unit = { old, new ->
        if (settings.renameCustomCategory(old, new)) {
            store.retargetCategory(Categories.CUSTOM_PREFIX + old, Categories.CUSTOM_PREFIX + new)
            customCats = settings.customCategoryLabels()
        }
    }
    val deleteCategory: (String) -> Unit = { label ->
        settings.removeCustomCategory(label)
        // 分类删除后，其下记录回到「未分类」，图片本体不受影响
        store.retargetCategory(Categories.CUSTOM_PREFIX + label, Categories.UNCATEGORIZED.id)
        customCats = settings.customCategoryLabels()
    }

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
                allCats = allCats,
                onAddCategory = addCategory,
                onOpenManage = { screen = Screen.CategoryManage },
                onOpenTrash = { screen = Screen.Trash },
                onOpen = { screen = Screen.Detail(it) },
                onBack = { screen = Screen.Capture },
            )
            is Screen.Detail -> DetailScreen(
                store = store,
                settings = settings,
                recordId = s.recordId,
                allCats = allCats,
                onAddCategory = addCategory,
                onNavigate = { id -> screen = Screen.Detail(id) },
                onBack = { screen = Screen.Library },
            )
            Screen.CategoryManage -> CategoryManageScreen(
                store = store,
                allCats = allCats,
                onAddCategory = addCategory,
                onRenameCategory = renameCategory,
                onDeleteCategory = deleteCategory,
                onBack = { screen = Screen.Library },
            )
            Screen.Trash -> TrashScreen(
                store = store,
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
        status = "识别中：提取文字与标签…"
        try {
            val result = Classifier.classify(ctx, source)
            // 默认不分类：只提取标签与文字（供搜索/AI 讲解），归类由用户在详情页完成
            val record = store.save(source.readBytes(), labels = result.labels, ocrText = result.ocrText)
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
fun LibraryScreen(
    store: RecordStore,
    allCats: List<Category>,
    onAddCategory: (String) -> Unit,
    onOpenManage: () -> Unit,
    onOpenTrash: () -> Unit,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
) {
    var filter by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableStateOf(0) }

    // 批量选择模式
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showBatchMove by remember { mutableStateOf(false) }
    var showBatchDelete by remember { mutableStateOf(false) }

    // 被删掉的分类 id 自愈为「全部」，避免筛选悬空
    val effFilter = if (filter != null && allCats.any { it.id == filter }) filter else null
    val records = remember(effFilter, refreshKey, allCats) { store.list(effFilter) }
    val counts = remember(records) { records.groupingBy { it.categoryId }.eachCount() }

    // 搜索：匹配 OCR 文字 / 识别标签 / 笔记 / 分类名
    val q = query.trim()
    val shown = if (q.isEmpty()) records else records.filter { r ->
        r.ocrText.contains(q, ignoreCase = true) ||
            r.labels.any { it.contains(q, ignoreCase = true) } ||
            (r.note?.contains(q, ignoreCase = true) == true) ||
            (allCats.firstOrNull { it.id == r.categoryId }?.label?.contains(q, ignoreCase = true) == true)
    }

    fun exitSelection() {
        selecting = false
        selected = emptySet()
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        // 顶栏：浏览态 / 选择态
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selecting) {
                TextButton(onClick = { exitSelection() }) { Text("取消") }
                Text(
                    "已选 ${selected.size} 张",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { selected = shown.map { it.id }.toSet() }) { Text("全选") }
            } else {
                Text("图库", fontSize = 18.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onOpenManage) { Text("管理分类", fontSize = 13.sp) }
                TextButton(onClick = onOpenTrash) { Text("回收站", fontSize = 13.sp) }
                TextButton(onClick = onBack) { Text("返回拍摄", fontSize = 13.sp) }
            }
        }

        if (!selecting) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("搜索：文字内容 / 标签 / 分类") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val filters: List<String?> = listOf(null) + allCats.map { it.id }
                items(filters) { f ->
                    FilterChip(
                        selected = effFilter == f,
                        onClick = { filter = f },
                        label = {
                            Text(
                                (if (f == null) "全部" else allCats.first { it.id == f }.label) +
                                    " " + (if (f == null) records.size else counts[f] ?: 0),
                            )
                        },
                    )
                }
                item {
                    FilterChip(
                        selected = false,
                        onClick = { showAddDialog = true },
                        label = { Text("＋ 新分类") },
                    )
                }
            }
        }

        if (shown.isEmpty()) {
            Box(
                Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) { Text(if (q.isEmpty()) "还没有收藏，去拍一张吧" else "没有匹配「$q」的结果") }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(96.dp),
                modifier = Modifier.weight(1f).padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                gridItems(shown, key = { it.id }) { r ->
                    RecordThumb(
                        path = store.photoFile(r.id).absolutePath,
                        categoryLabel = (allCats.firstOrNull { it.id == r.categoryId } ?: Categories.UNCATEGORIZED).label,
                        timeLabel = formatTime(r.createdAt, "MM-dd HH:mm"),
                        selecting = selecting,
                        selected = r.id in selected,
                        onClick = {
                            if (selecting) {
                                selected = if (r.id in selected) selected - r.id else selected + r.id
                            } else {
                                onOpen(r.id)
                            }
                        },
                        onLongClick = {
                            if (!selecting) {
                                selecting = true
                                selected = setOf(r.id)
                            }
                        },
                    )
                }
            }
        }

        // 批量操作栏
        if (selecting) {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { showBatchMove = true },
                    enabled = selected.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) { Text("归类") }
                Button(
                    onClick = { showBatchDelete = true },
                    enabled = selected.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) { Text("删除") }
            }
        }
    }

    if (showAddDialog) {
        NewCategoryDialog(
            onConfirm = onAddCategory,
            onDismiss = { showAddDialog = false },
        )
    }

    if (showBatchMove) {
        CategoryPickerDialog(
            allCats = allCats,
            onPick = { c ->
                selected.forEach { store.move(it, c.id) }
                showBatchMove = false
                refreshKey++
                exitSelection()
            },
            onDismiss = { showBatchMove = false },
        )
    }

    if (showBatchDelete) {
        ConfirmDeleteDialog(
            count = selected.size,
            onConfirm = {
                selected.forEach { store.trash(it) }
                refreshKey++
                exitSelection()
            },
            onDismiss = { showBatchDelete = false },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecordThumb(
    path: String,
    categoryLabel: String,
    timeLabel: String,
    selecting: Boolean = false,
    selected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
) {
    val bitmap by produceState<Bitmap?>(null, path) {
        value = withContext(Dispatchers.IO) { decodeSampled(path, 256) }
    }
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
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
        if (!selecting) {
            Text(
                timeLabel,
                fontSize = 10.sp,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
        // 选择态勾选圈
        if (selecting) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(20.dp)
                    .background(
                        if (selected) Color(0xFF1E88E5) else Color.Black.copy(alpha = 0.35f),
                        CircleShape,
                    )
                    .border(1.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Text("✓", color = Color.White, fontSize = 12.sp)
            }
        }
    }
}

// ---------------------------------------------------------------- 详情页

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(
    store: RecordStore,
    settings: SettingsStore,
    recordId: String,
    allCats: List<Category>,
    onAddCategory: (String) -> Unit,
    onNavigate: (String) -> Unit,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    var record by remember(recordId) { mutableStateOf(store.get(recordId)) }
    val scope = rememberCoroutineScope()
    var aiLoading by remember { mutableStateOf(false) }
    var aiError by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var showSource by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var saveMsg by remember { mutableStateOf<String?>(null) }
    var pendingSave by remember { mutableStateOf(false) }
    var awaitingPerm by remember { mutableStateOf(false) }

    val writePermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && awaitingPerm) {
            awaitingPerm = false
            pendingSave = true
        } else if (!granted) {
            saveMsg = "未授予存储权限，无法保存到相册"
        }
    }

    val r = record
    if (r == null) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            TextButton(onClick = onBack) { Text("返回") }
            Text("记录不存在")
        }
        return
    }

    // 同一排序（按时间倒序）下的相邻记录，供 HorizontalPager 翻页
    val siblings = remember(recordId) { store.list() }
    val pagerState = rememberPagerState(
        initialPage = siblings.indexOfFirst { it.id == recordId }.coerceAtLeast(0),
    ) { siblings.size }

    // 滑动翻页落定 → 切换当前记录
    LaunchedEffect(pagerState, siblings) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            siblings.getOrNull(page)?.let { if (it.id != recordId) onNavigate(it.id) }
        }
    }
    // 外部跳转（如删除后导航）→ pager 跟随
    LaunchedEffect(recordId, siblings) {
        val target = siblings.indexOfFirst { it.id == recordId }
        if (target >= 0 && pagerState.currentPage != target) pagerState.scrollToPage(target)
    }
    val page = pagerState.currentPage

    fun doSave() {
        scope.launch {
            val msg = withContext(Dispatchers.IO) {
                ImageExporter.saveToGallery(ctx, store.photoFile(r.id))
            }
            saveMsg = msg.fold({ "已保存到$it" }, { "保存失败：${it.message}" })
        }
    }
    LaunchedEffect(pendingSave) {
        if (pendingSave) {
            pendingSave = false
            doSave()
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← 图库") }
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = {
                    if (Build.VERSION.SDK_INT >= 29 ||
                        ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        doSave()
                    } else {
                        awaitingPerm = true
                        writePermLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    }
                },
            ) { Text("保存") }
            TextButton(onClick = { showDeleteDialog = true }) { Text("删除") }
            Box {
                TextButton(onClick = { menuOpen = true }) {
                    val uncategorized = r.categoryId == Categories.UNCATEGORIZED.id
                    Text(
                        "分类：${(allCats.firstOrNull { it.id == r.categoryId } ?: Categories.UNCATEGORIZED).label} ▾",
                        color = if (uncategorized) MaterialTheme.colorScheme.primary else Color.Unspecified,
                        fontWeight = if (uncategorized) FontWeight.Medium else null,
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    allCats.forEach { c ->
                        DropdownMenuItem(
                            text = { Text(c.label) },
                            onClick = {
                                store.move(r.id, c.id)
                                record = r.copy(categoryId = c.id)
                                menuOpen = false
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("＋ 新建分类") },
                        onClick = {
                            menuOpen = false
                            showAddDialog = true
                        },
                    )
                }
            }
        }

        // 未分类引导
        if (r.categoryId == Categories.UNCATEGORIZED.id) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(10.dp))
                    .clickable { menuOpen = true }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "这张图片还未分类",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "去归类 ▾",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            if (siblings.size > 1) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        enabled = page > 0,
                        onClick = { scope.launch { pagerState.animateScrollToPage(page - 1) } },
                    ) { Text("← 上一张") }
                    Text(
                        "${page + 1} / ${siblings.size}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        enabled = page < siblings.size - 1,
                        onClick = { scope.launch { pagerState.animateScrollToPage(page + 1) } },
                    ) { Text("下一张 →") }
                }
            }
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth(),
                pageSpacing = 12.dp,
            ) { pageIdx ->
                val item = siblings.getOrNull(pageIdx)
                if (item != null) PageImage(store, item.id)
            }
            if (siblings.size > 1) {
                Text(
                    "左右滑动图片也可切换",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            saveMsg?.let {
                Text(
                    it,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            Spacer(Modifier.height(4.dp))

            // 拍摄 / 上传时间
            Text(
                "拍摄/上传时间：" + formatTime(r.createdAt, "yyyy-MM-dd HH:mm"),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
                                val category = allCats.firstOrNull { it.id == r.categoryId } ?: Categories.UNCATEGORIZED
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

    if (showAddDialog) {
        NewCategoryDialog(
            onConfirm = onAddCategory,
            onDismiss = { showAddDialog = false },
        )
    }

    if (showDeleteDialog) {
        ConfirmDeleteDialog(
            onConfirm = {
                // 删除（入回收站）前先算好相邻记录：优先跳到下一张，没有则上一张，再没有回图库
                val i = siblings.indexOfFirst { it.id == r.id }
                store.trash(r.id)
                showDeleteDialog = false
                val nextId = siblings.getOrNull(i + 1)?.id ?: siblings.getOrNull(i - 1)?.id
                if (nextId != null) onNavigate(nextId) else onBack()
            },
            onDismiss = { showDeleteDialog = false },
        )
    }
}

@Composable
fun PageImage(store: RecordStore, recordId: String) {
    val bitmap by produceState<Bitmap?>(null, recordId) {
        value = withContext(Dispatchers.IO) { decodeSampled(store.photoFile(recordId).absolutePath, 1080) }
    }
    Box(
        Modifier.fillMaxWidth().height(320.dp),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } ?: CircularProgressIndicator(Modifier.size(24.dp))
    }
}

// ---------------------------------------------------------------- 分类管理页

@Composable
fun CategoryManageScreen(
    store: RecordStore,
    allCats: List<Category>,
    onAddCategory: (String) -> Unit,
    onRenameCategory: (String, String) -> Unit,
    onDeleteCategory: (String) -> Unit,
    onBack: () -> Unit,
) {
    var refreshKey by remember { mutableStateOf(0) }
    var showAdd by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }

    val records = remember(refreshKey, allCats) { store.list() }
    val counts = remember(records) { records.groupingBy { it.categoryId }.eachCount() }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("管理分类", fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("返回") }
        }
        Spacer(Modifier.height(8.dp))

        Button(onClick = { showAdd = true }, modifier = Modifier.fillMaxWidth()) { Text("＋ 新建分类") }
        Spacer(Modifier.height(12.dp))

        LazyColumn(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(allCats, key = { it.id }) { c ->
                val isCustom = Categories.isCustom(c.id)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(c.label, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text(
                            "${counts[c.id] ?: 0} 张图片" + if (!isCustom) " · 内置" else "",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (isCustom) {
                        TextButton(onClick = { renaming = c.label }) { Text("重命名") }
                        TextButton(onClick = { deleting = c.label }) { Text("删除") }
                    }
                }
            }
        }
        Text(
            "删除分类后，其中的图片会移入「未分类」。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (showAdd) {
        NewCategoryDialog(
            onConfirm = {
                onAddCategory(it)
                refreshKey++
            },
            onDismiss = { showAdd = false },
        )
    }
    renaming?.let { old ->
        CategoryNameDialog(
            title = "重命名分类",
            initial = old,
            confirmLabel = "重命名",
            onConfirm = { new ->
                onRenameCategory(old, new)
                refreshKey++
            },
            onDismiss = { renaming = null },
        )
    }
    deleting?.let { label ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除分类「$label」？") },
            text = {
                Text(
                    "该分类下的 ${counts[Categories.CUSTOM_PREFIX + label] ?: 0} 张图片将移入「未分类」，" +
                        "图片本体不受影响。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteCategory(label)
                    refreshKey++
                    deleting = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("取消") }
            },
        )
    }
}

// ---------------------------------------------------------------- 回收站

@Composable
fun TrashScreen(store: RecordStore, onBack: () -> Unit) {
    var refreshKey by remember { mutableStateOf(0) }
    var actionTarget by remember { mutableStateOf<RecordStore.Record?>(null) }
    var showEmpty by remember { mutableStateOf(false) }

    val items = remember(refreshKey) { store.listTrashed() }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("回收站", fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            if (items.isNotEmpty()) {
                TextButton(onClick = { showEmpty = true }) { Text("清空回收站") }
            }
            TextButton(onClick = onBack) { Text("返回") }
        }

        if (items.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("回收站是空的")
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(96.dp),
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                gridItems(items, key = { it.id }) { r ->
                    RecordThumb(
                        path = store.trashedPhotoFile(r.id).absolutePath,
                        categoryLabel = "回收站",
                        timeLabel = formatTime(
                            if (r.trashedAt > 0) r.trashedAt else r.createdAt,
                            "MM-dd HH:mm",
                        ),
                        onClick = { actionTarget = r },
                    )
                }
            }
        }
        Text(
            "删除的图片先进入回收站，可随时恢复；清空后彻底删除、不可恢复。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    actionTarget?.let { t ->
        AlertDialog(
            onDismissRequest = { actionTarget = null },
            title = { Text("回收站操作") },
            text = {
                Text(
                    "删除于 " + formatTime(
                        if (t.trashedAt > 0) t.trashedAt else t.createdAt,
                        "yyyy-MM-dd HH:mm",
                    ) + "。恢复后图片回到图库原分类。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    store.restore(t.id)
                    refreshKey++
                    actionTarget = null
                }) { Text("恢复") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        store.purge(t.id)
                        refreshKey++
                        actionTarget = null
                    }) { Text("彻底删除", color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { actionTarget = null }) { Text("取消") }
                }
            },
        )
    }

    if (showEmpty) {
        AlertDialog(
            onDismissRequest = { showEmpty = false },
            title = { Text("清空回收站？") },
            text = { Text("将彻底删除回收站中的 ${items.size} 张图片，不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    store.emptyTrash()
                    showEmpty = false
                    refreshKey++
                }) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showEmpty = false }) { Text("取消") }
            },
        )
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
    }
}

// ---------------------------------------------------------------- 通用组件与工具

@Composable
fun NewCategoryDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    CategoryNameDialog(
        title = "新建分类",
        confirmLabel = "创建",
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

@Composable
fun CategoryNameDialog(
    title: String,
    initial: String = "",
    confirmLabel: String = "确定",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("分类名称") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = {
                    onConfirm(text)
                    onDismiss()
                },
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
fun ConfirmDeleteDialog(count: Int = 1, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (count > 1) "删除这 $count 张图片？" else "删除这张图片？") },
        text = { Text("将移入回收站，可在「回收站」中恢复或彻底删除。") },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("删除") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
fun CategoryPickerDialog(
    allCats: List<Category>,
    onPick: (Category) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("移动到分类") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                allCats.forEach { c ->
                    Text(
                        c.label,
                        fontSize = 15.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(c) }
                            .padding(vertical = 10.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private fun formatTime(ms: Long, pattern: String): String =
    SimpleDateFormat(pattern, Locale.getDefault()).format(Date(ms))

private fun decodeSampled(path: String, req: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= req && bounds.outHeight / (sample * 2) >= req) sample *= 2
    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}
