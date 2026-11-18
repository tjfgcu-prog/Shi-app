@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.lianyu.ai.feature.settings.ui.screen

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lianyu.ai.common.AppLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 实时日志：App 里所有日志（崩溃、本地模型、语音、记忆、网络、聊天流程、原生库输出…）都汇在这里，
 * 数据来自 [AppLog]，每 0.3 秒检查一次有没有新日志。
 *
 * 操作：级别筛选、关键字搜索、自动滚动（手动上滑会暂停，滑到底部恢复）、
 * 右上角「导出」把当前显示的日志打成 zip 保存到手机「下载」文件夹、「清空」清掉内存和文件里的日志。
 * 手机系统自己的 logcat 噪音（标签以 sys/ 开头）默认隐藏，点「系统日志」才显示。
 */
@Composable
fun LiveLogScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme

    val scope = rememberCoroutineScope()
    var showSystem by remember { mutableStateOf(false) }
    var entries by remember { mutableStateOf(AppLog.snapshot()) }
    var appCount by remember { mutableStateOf(AppLog.appCount()) }
    var systemCount by remember { mutableStateOf(AppLog.systemCount()) }
    LaunchedEffect(showSystem) {
        var seen = -1L
        while (true) {
            val v = AppLog.version
            if (v != seen) {
                seen = v
                entries = AppLog.snapshot(includeSystem = showSystem)
                appCount = AppLog.appCount()
                systemCount = AppLog.systemCount()
            }
            delay(300)
        }
    }

    var minLevel by remember { mutableStateOf(AppLog.Level.VERBOSE) }
    var query by remember { mutableStateOf("") }
    var autoScroll by remember { mutableStateOf(true) }

    val filtered = remember(entries, minLevel, query) {
        val q = query.trim()
        entries.filter { e ->
            e.level.priority >= minLevel.priority &&
                (q.isEmpty() || e.tag.contains(q, ignoreCase = true) || e.message.contains(q, ignoreCase = true))
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(filtered.lastOrNull()?.id, autoScroll) {
        if (autoScroll && filtered.isNotEmpty()) listState.scrollToItem(filtered.lastIndex)
    }
    // 用户手动滚动结束后：停在底部 = 继续自动滚动；停在别处 = 暂停自动滚动
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .drop(1)
            .collect { scrolling ->
                if (!scrolling) autoScroll = !listState.canScrollForward
            }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().background(colorScheme.background).windowInsetsPadding(WindowInsets.statusBars),
        containerColor = colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("实时日志", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = colorScheme.onSurface)
                    }
                },
                actions = {
                    TextButton(onClick = {
                        if (filtered.isEmpty()) {
                            Toast.makeText(context, "当前没有可导出的日志", Toast.LENGTH_SHORT).show()
                        } else {
                            val shown = filtered
                            scope.launch {
                                val result = withContext(Dispatchers.IO) { runCatching { exportLogZip(context, shown) } }
                                val msg = result.fold(
                                    onSuccess = { "已导出 ${shown.size} 条，保存到 $it" },
                                    onFailure = { "导出失败：${it.message ?: it.javaClass.simpleName}" }
                                )
                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                            }
                        }
                    }) { Text("导出") }
                    TextButton(onClick = { AppLog.clear() }) { Text("清空") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colorScheme.background)
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LevelChip("全部", minLevel == AppLog.Level.VERBOSE) { minLevel = AppLog.Level.VERBOSE }
                LevelChip("警告+", minLevel == AppLog.Level.WARN) { minLevel = AppLog.Level.WARN }
                LevelChip("错误", minLevel == AppLog.Level.ERROR) { minLevel = AppLog.Level.ERROR }
                LevelChip("系统日志", showSystem) { showSystem = !showSystem }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("搜索标签或内容", fontSize = 13.sp) },
                textStyle = TextStyle(fontSize = 13.sp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            )
            Text(
                text = buildString {
                    append("应用日志 $appCount 条，显示 ${filtered.size} 条")
                    if (!showSystem && systemCount > 0) append("；系统日志 $systemCount 条已隐藏")
                    if (!AppLog.isVerbose) append("\nrelease 包为保护聊天隐私，只记录崩溃、上次退出原因和不含聊天内容的诊断信息；完整日志请用 debug 包。")
                },
                fontSize = 11.sp,
                color = colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )

            Box(modifier = Modifier.fillMaxSize()) {
                if (filtered.isEmpty()) {
                    Text(
                        text = if (entries.isEmpty()) "还没有日志" else "没有符合筛选条件的日志",
                        fontSize = 13.sp,
                        color = colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center)
                    )
                } else {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        items(filtered, key = { it.id }) { entry ->
                            LogRow(entry)
                        }
                    }
                }
                if (!autoScroll && filtered.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 16.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(colorScheme.primary)
                            .combinedClickable(onClick = { autoScroll = true })
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text("回到底部", fontSize = 13.sp, color = colorScheme.onPrimary)
                    }
                }
            }
        }
    }
}

@Composable
private fun LevelChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label, fontSize = 12.sp) })
}

@Composable
private fun LogRow(entry: AppLog.Entry) {
    val colorScheme = MaterialTheme.colorScheme
    val color = when (entry.level) {
        AppLog.Level.ERROR -> colorScheme.error
        AppLog.Level.WARN -> Color(0xFFFF9500)
        AppLog.Level.INFO -> colorScheme.onSurface
        else -> colorScheme.onSurfaceVariant
    }
    Text(
        text = entry.toLine(),
        fontSize = 11.sp,
        lineHeight = 15.sp,
        fontFamily = FontFamily.Monospace,
        color = color,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
    )
}

/** 把界面上当前显示的日志打成 zip 保存到手机「下载」文件夹（Android 10 以下保存到应用的外部文件目录），返回给用户看的位置。 */
private fun exportLogZip(context: Context, entries: List<AppLog.Entry>): String {
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    val name = "lianyu-log-$stamp.zip"
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("无法在下载文件夹创建文件")
        resolver.openOutputStream(uri)?.use { AppLog.writeZip(it, entries) } ?: error("无法写入文件")
        return "下载/$name"
    }
    val dir = context.getExternalFilesDir(null) ?: context.filesDir
    val file = File(dir, name)
    file.outputStream().use { AppLog.writeZip(it, entries) }
    return file.absolutePath
}
