package com.lianyu.ai.feature.chat.roleplay

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 世界书条目：一小段设定文字，只有最近的对话里出现了触发词才会被塞进提示词
 * （常驻条目除外）。这样世界观再大，每轮实际多出来的文字也很少。
 */
@Serializable
data class WorldBookEntry(
    val id: String,
    val name: String = "",
    /** 触发词：最近的对话里出现任意一个就触发 */
    val keywords: List<String> = emptyList(),
    val content: String = "",
    val enabled: Boolean = true,
    /** 常驻：不需要触发词，每轮都带上。只放最核心、最短的规则 */
    val constant: Boolean = false,
    /** 0 = 全局（所有角色）；否则只对这个角色编号的角色生效 */
    val companionId: Long = 0L
)

/**
 * 世界书存储：内存里一份 + 落盘到 filesDir/world_book.json。
 * 提示词拼装时只读内存，不做 IO。
 */
object WorldBookStore {
    /** 世界书每轮最多塞进提示词的字数（本地大模型处理提示词很慢，控制总量） */
    const val BUDGET_CHARS = 500

    private const val FILE_NAME = "world_book.json"
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _entries = MutableStateFlow<List<WorldBookEntry>>(emptyList())
    val entries: StateFlow<List<WorldBookEntry>> = _entries.asStateFlow()

    @Volatile private var file: File? = null

    /** 可以重复调用，只会加载一次 */
    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.applicationContext.filesDir, FILE_NAME)
        file = f
        if (f.exists()) {
            runCatching { json.decodeFromString<List<WorldBookEntry>>(f.readText()) }
                .onSuccess { _entries.value = it }
        }
    }

    private fun persist() {
        val f = file ?: return
        val snapshot = _entries.value
        scope.launch {
            runCatching { f.writeText(json.encodeToString(snapshot)) }
        }
    }

    fun upsert(entry: WorldBookEntry) {
        _entries.update { list ->
            if (list.any { it.id == entry.id }) list.map { if (it.id == entry.id) entry else it } else list + entry
        }
        persist()
    }

    fun delete(id: String) {
        _entries.update { list -> list.filterNot { it.id == id } }
        persist()
    }

    fun setEnabled(id: String, enabled: Boolean) {
        _entries.update { list -> list.map { if (it.id == id) it.copy(enabled = enabled) else it } }
        persist()
    }

    fun newId(): String = "wb_${System.currentTimeMillis()}_${(0..9999).random()}"

    /** 导入起始条目：id 相同的覆盖，其他保留。返回导入条数。 */
    fun importEntries(entries: List<WorldBookEntry>): Int {
        _entries.update { list ->
            val incoming = entries.associateBy { it.id }
            list.map { incoming[it.id] ?: it } + entries.filter { e -> list.none { it.id == e.id } }
        }
        persist()
        return entries.size
    }

    fun exportJson(): String = json.encodeToString(_entries.value)

    /** 从 JSON 文本导入；格式不对返回 -1。 */
    fun importJson(text: String): Int {
        val parsed = runCatching { json.decodeFromString<List<WorldBookEntry>>(text.trim()) }.getOrNull() ?: return -1
        val fixed = parsed.map { if (it.id.isBlank()) it.copy(id = newId()) else it }
        return importEntries(fixed)
    }

    /**
     * 按最近对话挑出要塞进提示词的条目，渲染成文字。
     * 顺序：常驻条目在前，其次按命中的触发词个数从多到少；总字数不超过 [budgetChars]。
     */
    fun match(companionId: Long, recentText: String, budgetChars: Int = BUDGET_CHARS): String {
        val text = recentText.lowercase()
        val picked = ArrayList<Pair<WorldBookEntry, Int>>()
        for (e in _entries.value) {
            if (!e.enabled || e.content.isBlank()) continue
            if (e.companionId != 0L && e.companionId != companionId) continue
            if (e.constant) {
                picked.add(e to Int.MAX_VALUE)
            } else {
                val hits = e.keywords.count { k -> k.isNotBlank() && text.contains(k.trim().lowercase()) }
                if (hits > 0) picked.add(e to hits)
            }
        }
        if (picked.isEmpty()) return ""
        val sorted = picked.sortedByDescending { it.second }   // 稳定排序：同分保持条目原顺序
        val sb = StringBuilder()
        var used = 0
        for ((e, _) in sorted) {
            val remain = budgetChars - used
            if (remain <= 20) break
            val line = "【${e.name}】${e.content.trim()}"
            val piece = if (line.length > remain) line.take(remain - 1) + "…" else line
            sb.append(piece).append('\n')
            used += piece.length + 1
        }
        return sb.toString().trim()
    }
}
