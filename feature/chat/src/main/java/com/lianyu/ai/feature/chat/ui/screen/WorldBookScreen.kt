package com.lianyu.ai.feature.chat.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lianyu.ai.feature.chat.roleplay.HonghuangStarterPack
import com.lianyu.ai.feature.chat.roleplay.WorldBookEntry
import com.lianyu.ai.feature.chat.roleplay.WorldBookStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 世界书管理页：查看、添加、修改、启用/停用、删除世界书条目，导入洪荒起始条目，
 * 以及通过剪贴板导入/导出 JSON。
 *
 * @param companionId 当前角色编号：新建条目时可选"仅对本角色生效"
 */
@Composable
fun WorldBookScreen(companionId: Long, onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { WorldBookStore.init(context) } }
    val entries by WorldBookStore.entries.collectAsState()
    var editing by remember { mutableStateOf<WorldBookEntry?>(null) }
    var showHelp by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onNavigateBack, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = MaterialTheme.colorScheme.onBackground)
                }
                Text(
                    text = "世界书（${entries.size} 条）",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { showHelp = true }) { Text("说明") }
            }

            // 操作区
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TextButton(onClick = {
                    editing = WorldBookEntry(id = WorldBookStore.newId(), name = "", keywords = emptyList(), content = "")
                }) { Text("＋添加") }
                TextButton(onClick = {
                    val n = WorldBookStore.importEntries(HonghuangStarterPack.entries)
                    Toast.makeText(context, "已导入 $n 条洪荒起始条目（同名条目已覆盖）", Toast.LENGTH_SHORT).show()
                }) { Text("导入洪荒") }
                TextButton(onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("world_book", WorldBookStore.exportJson()))
                    Toast.makeText(context, "已复制全部条目（JSON）到剪贴板", Toast.LENGTH_SHORT).show()
                }) { Text("复制") }
                TextButton(onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString().orEmpty()
                    val n = WorldBookStore.importJson(text)
                    Toast.makeText(
                        context,
                        if (n >= 0) "已从剪贴板导入 $n 条" else "剪贴板里不是有效的世界书 JSON",
                        Toast.LENGTH_SHORT
                    ).show()
                }) { Text("粘贴导入") }
            }

            if (entries.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "还没有条目。\n点「导入洪荒」可以先放入一套可修改的洪荒起始设定，或点「＋添加」自己写。",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(entries, key = { it.id }) { entry ->
                        WorldBookEntryCard(
                            entry = entry,
                            onClick = { editing = entry },
                            onToggle = { WorldBookStore.setEnabled(entry.id, it) }
                        )
                    }
                }
            }
        }
    }

    editing?.let { current ->
        WorldBookEditDialog(
            initial = current,
            companionId = companionId,
            isNew = entries.none { it.id == current.id },
            onDismiss = { editing = null },
            onSave = {
                WorldBookStore.upsert(it)
                editing = null
            },
            onDelete = {
                WorldBookStore.delete(current.id)
                editing = null
            }
        )
    }

    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            title = { Text("世界书怎么用") },
            text = {
                Text(
                    "世界书是一本设定集。每条写一小段设定，并给它几个「触发词」：" +
                        "最近的对话里一旦出现触发词，这条设定才会临时塞给 AI，所以设定再多，每轮多出来的字也很少。\n\n" +
                        "· 常驻条目：不需要触发词，每轮都会带上，只放最核心、最短的规则。\n" +
                        "· 每轮世界书最多带入约 ${WorldBookStore.BUDGET_CHARS} 字，条目写得短而密最好。\n" +
                        "· 触发词用逗号分隔，例如：鸿钧，道祖，紫霄宫。\n" +
                        "· 需要在聊天详情里打开「角色扮演模式」，世界书才会生效。",
                    fontSize = 13.sp
                )
            },
            confirmButton = { TextButton(onClick = { showHelp = false }) { Text("知道了") } }
        )
    }
}

@Composable
private fun WorldBookEntryCard(entry: WorldBookEntry, onClick: () -> Unit, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            val tags = buildString {
                if (entry.constant) append("常驻  ")
                if (entry.companionId != 0L) append("仅本角色  ")
            }
            Text(
                text = (entry.name.ifBlank { "（未命名）" }) + if (tags.isNotEmpty()) "   $tags" else "",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = if (entry.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!entry.constant && entry.keywords.isNotEmpty()) {
                Text(
                    text = "触发词：" + entry.keywords.joinToString("，"),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = entry.content,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.size(8.dp))
        Switch(
            checked = entry.enabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.primary,
                checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            )
        )
    }
}

@Composable
private fun WorldBookEditDialog(
    initial: WorldBookEntry,
    companionId: Long,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (WorldBookEntry) -> Unit,
    onDelete: () -> Unit
) {
    var name by remember(initial.id) { mutableStateOf(initial.name) }
    var keywords by remember(initial.id) { mutableStateOf(initial.keywords.joinToString("，")) }
    var content by remember(initial.id) { mutableStateOf(initial.content) }
    var constant by remember(initial.id) { mutableStateOf(initial.constant) }
    var onlyThisCompanion by remember(initial.id) { mutableStateOf(initial.companionId != 0L) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "添加条目" else "编辑条目") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称（例如：鸿钧道祖）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (!constant) {
                    OutlinedTextField(
                        value = keywords,
                        onValueChange = { keywords = it },
                        label = { Text("触发词（逗号分隔）") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("设定内容（尽量短）") },
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("常驻（每轮都带上）", fontSize = 13.sp)
                    Switch(checked = constant, onCheckedChange = { constant = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("仅对当前角色生效", fontSize = 13.sp)
                    Switch(checked = onlyThisCompanion, onCheckedChange = { onlyThisCompanion = it })
                }
                Spacer(modifier = Modifier.height(2.dp))
            }
        },
        confirmButton = {
            TextButton(
                enabled = content.isNotBlank(),
                onClick = {
                    onSave(
                        initial.copy(
                            name = name.trim(),
                            keywords = keywords.split(',', '，', '、', '\n').map { it.trim() }.filter { it.isNotEmpty() },
                            content = content.trim(),
                            constant = constant,
                            companionId = if (onlyThisCompanion) companionId else 0L
                        )
                    )
                }
            ) { Text("保存") }
        },
        dismissButton = {
            Row {
                if (!isNew) TextButton(onClick = onDelete) { Text("删除") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}
