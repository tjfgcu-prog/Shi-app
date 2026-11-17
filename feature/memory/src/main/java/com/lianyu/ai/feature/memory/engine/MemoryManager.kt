package com.lianyu.ai.feature.memory.engine

import android.content.Context
import android.util.Log
import com.lianyu.ai.common.AppLog
import com.lianyu.ai.common.DeviceIdProvider
import com.lianyu.ai.common.UserSelfProfile
import com.lianyu.ai.database.repository.UserRepository
import com.lianyu.ai.common.TimeoutBudgets
import com.lianyu.ai.domain.MemoryProvider
import com.lianyu.ai.domain.AiServiceProvider
import com.lianyu.ai.domain.LocalModelProvider
import com.lianyu.ai.domain.ServiceRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 记忆管理器（单例）
 *
 * 核心职责：
  * 1. 统一管理角色/群聊两种作用域的记忆（互相独立）
 * 2. 分层存储：短期(内存)→中期(内存+文件)→长期(文件)
  * 3. 剧情摘要：按角色保存，每 5 轮自动生成
 * 4. 倒排索引检索 + LRU缓存 + TTL过期清理
 *
 * 完全独立于 Room 数据库，使用 JSON 文件持久化
 *
 * 实现 [MemoryProvider] 接口，通过 ServiceRegistry 向 core:network 和 feature:groupchat 提供服务。
 */
class MemoryManager private constructor(
    private val context: Context,
    private val deviceId: String
) : MemoryProvider {
    companion object {
        private const val TAG = "MemoryManager"
        private const val SHORT_TERM_TTL_MS = 5L * 60 * 1000 // 5分钟
        private const val SHORT_TERM_MAX_PER_SCOPE = 50
        private const val MID_TERM_MAX_MEMORY = 500
        private const val CLEANUP_INTERVAL_MS = 5L * 60 * 1000 // 5分钟清理一次
        private const val DEDUP_SIMILARITY_THRESHOLD = 0.6f
        private const val STORY_SUMMARY_EVERY_TURNS = 5
        private const val STORY_PENDING_MAX = 30
        private const val STORY_SUMMARY_MAX_CHARS = 400
        private const val STORY_SUMMARY_RETRY_COOLDOWN_MS = 5L * 60 * 1000
        private const val STORY_SUMMARY_SYSTEM = "你是剧情记录员，只输出摘要正文，不要解释。"
        private val STORY_SUMMARY_BAD_MARKERS = listOf("揭示", "暗示", "探索", "还没解决", "第三人称")
        
        @Volatile
        private var instance: MemoryManager? = null

        fun getInstance(context: Context): MemoryManager {
            return instance ?: synchronized(this) {
                instance ?: run {
                    val deviceId = DeviceIdProvider.getDeviceId(context)
                    MemoryManager(context.applicationContext, deviceId).also {
                        instance = it
                    }
                }
            }
        }
        private val _memoriesChanged = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 8)
        val memoriesChanged: kotlinx.coroutines.flow.SharedFlow<String> = _memoriesChanged.asSharedFlow()
    }

    private val store = MemoryStore(context, deviceId)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val embeddingProvider: EmbeddingProvider by lazy { OnnxEmbeddingProvider(context) }
    
    // 短期记忆：内存缓存，按作用域隔离
    private val shortTermCache = ConcurrentHashMap<String, MutableList<MemoryItem>>()

    // 中期记忆：内存 LRU 缓存
    private val midTermCache = ConcurrentHashMap<String, MutableList<MemoryItem>>()

    // 长期记忆：仅索引在内存，内容按需加载
    private val longTermIndex = ConcurrentHashMap<String, MemoryIndex>()

    // 索引缓存
    private val indexCache = ConcurrentHashMap<String, MemoryIndex>()

    // 查询缓存（LRU）
    private val queryCache = LinkedHashMap<String, List<MemoryItem>>(32, 0.75f, true)

    // 写入锁（按作用域）
    private val writeLocks = ConcurrentHashMap<String, Mutex>()

    // 剧情状态缓存（按 companionId）
    private val storyCache = ConcurrentHashMap<Long, StoryState>()

    // 正在做滚动摘要的角色（同一角色不并发摘要）
    private val summarizing = ConcurrentHashMap.newKeySet<Long>()

    // 摘要失败后的冷却截止时间
    private val summaryRetryAt = ConcurrentHashMap<Long, Long>()

    // 汇入 AppLog，设置 → 实时日志 里能看到记忆/剧情的运行情况。
    // dbg：任何包都记录，只写状态、数量，不能带聊天/摘要内容；
    // dbgContent：带摘要原文、异常文字等内容的日志，只有 debug 包记录（release 不落盘）。
    private fun dbg(msg: String) {
        AppLog.diag("MemoryManager", msg)
    }

    private fun dbgContent(msg: String) {
        AppLog.d("MemoryManager", msg)
    }

    private fun logE(msg: String, t: Throwable) {
        Log.e(TAG, msg, t)
        dbg("ERROR $msg: ${t.javaClass.simpleName}")
        dbgContent("ERROR $msg 详情: ${t.message}")
    }

    
    // 是否已初始化
    @Volatile
    private var initialized = false
    // [R5 FIX] 初始化原子化锁：原 check-then-set（@Volatile 但无锁）在 AiService.init 和
    // GroupChatViewModel 冷启动并发调用时可同时通过判断，启动两个永久 while(true) 清理协程 + 重复磁盘加载。
    private val initLock = Any()

    /**
     * 初始化：加载持久化记忆到内存
     */
    override fun initialize() {
        // [R5 FIX] 用 synchronized 保证 check-then-set 原子性，避免并发重复初始化
        synchronized(initLock) {
            if (initialized) return
            initialized = true
        }
        ioScope.launch {
            runCatching {
                loadPersistedMemories()
                startCleanupTask()
            }.onFailure { logE("初始化失败", it) }
        }
    }

    /**
     * 作用域键
     */
    private fun scopeKey(scope: MemoryScope, id: Long): String {
        return "${scope.name}_$id"
    }

    /**
     * 获取写入锁
     */
    private fun getLock(scope: MemoryScope, id: Long): Mutex {
        return writeLocks.computeIfAbsent(scopeKey(scope, id)) { Mutex() }
    }

    /**
     * 加载持久化记忆
     */
    private fun loadPersistedMemories() {
        // 清理已废弃的全局记忆池目录（不迁移，直接删除）
        java.io.File(context.filesDir, "memory/$deviceId/global").deleteRecursively()

        // 加载所有角色记忆（通过扫描文件系统）
        val globalDir = java.io.File(context.filesDir, "memory/$deviceId")
        globalDir.listFiles()?.forEach { dir ->
            if (dir.name.startsWith("companion_")) {
                val id = dir.name.removePrefix("companion_").toLongOrNull()
                if (id != null) loadScopeFromDisk(MemoryScope.COMPANION, id)
            } else if (dir.name.startsWith("group_")) {
                val id = dir.name.removePrefix("group_").toLongOrNull()
                if (id != null) loadScopeFromDisk(MemoryScope.GROUP, id)
            }
        }
    }

    /**
     * 从磁盘加载单个作用域
     */
    private fun loadScopeFromDisk(scope: MemoryScope, id: Long) {
        val key = scopeKey(scope, id)
        runCatching {
            // 先读长期层，用于剔除历史上被错误写入中期层的重复项
            val longItems = store.loadTier(scope, id, MemoryTier.LONG)
            val longIds = longItems.mapTo(HashSet()) { it.id }
            val midItems = store.loadTier(scope, id, MemoryTier.MID).filter { it.id !in longIds }
            if (midItems.isNotEmpty()) {
                midTermCache[key] = midItems.toMutableList()
            }
            if (longItems.isNotEmpty()) {
                val index = MemoryIndex()
                longItems.forEach { index.add(it) }
                longTermIndex[key] = index
            }

            // 加载索引
            store.loadIndex(scope, id)?.let { serialized ->
                val index = MemoryIndex()
                val allItems = (longItems + midItems).associateBy { it.id }
                index.deserialize(serialized, allItems)
                indexCache[key] = index
            }
        }.onFailure { logE("加载作用域 $key 失败", it) }
    }

    /**
     * 启动定时清理任务
     */
    private fun startCleanupTask() {
        ioScope.launch {
            while (true) {
                delay(CLEANUP_INTERVAL_MS)
                runCatching { cleanupExpiredMemories() }
                    .onFailure { logE("清理任务失败", it) }
            }
        }
    }

    /**
     * 清理过期短期记忆 + LRU淘汰
     */
    private fun cleanupExpiredMemories() {
        val now = System.currentTimeMillis()
        shortTermCache.forEach { (key, items) ->
            synchronized(items) {
                items.removeAll { it.isExpired(now) }
                // LRU淘汰：超过容量时移除最旧的
                if (items.size > SHORT_TERM_MAX_PER_SCOPE) {
                    items.sortBy { it.lastAccessed }
                    val toRemove = items.size - SHORT_TERM_MAX_PER_SCOPE
                    repeat(toRemove) { items.removeAt(0) }
                }
            }
        }

        // 中期记忆全局LRU淘汰
        // [R10 FIX] 用快照遍历 + synchronized 保护，避免与 touchMemory/saveMemory 并发时 CME
        val midSnapshot = midTermCache.entries.toList()
        val totalMid = midSnapshot.sumOf { it.value.size }
        if (totalMid > MID_TERM_MAX_MEMORY) {
            // 按最后访问时间排序，淘汰最久未访问的
            val allMid = midSnapshot
                .flatMap { (key, items) ->
                    synchronized(items) { items.toList() }.map { key to it }
                }
                .sortedBy { it.second.lastAccessed }
            val toRemoveCount = totalMid - MID_TERM_MAX_MEMORY
            allMid.take(toRemoveCount).forEach { (key, item) ->
                midTermCache[key]?.let { items ->
                    synchronized(items) { items.removeAll { it.id == item.id } }
                }
            }
        }
    }

    /**
     * 获取记忆上下文（注入AI对话）
     *
     * @param companionId 角色ID（单聊时传入，群聊时为null）
     * @param groupId 群聊ID（群聊时传入，单聊时为null）
     * @param query 查询文本（当前用户消息）
     * @param limit 返回记忆数量限制
     * @return 格式化的记忆上下文字符串
     */
    override suspend fun getMemoryContext(
        companionId: Long?,
        groupId: Long?,
        query: String,
        limit: Int
    ): String {
        return runCatching {
            val memories = mutableListOf<MemoryItem>()


            // 2. 查询角色/群聊记忆
            when {
                companionId != null -> {
                    memories.addAll(searchMemories(MemoryScope.COMPANION, companionId, query, limit))
                }
                groupId != null -> {
                    memories.addAll(searchMemories(MemoryScope.GROUP, groupId, query, limit))
                }
            }

            // 去重 + 按重要度排序
            val deduped = memories.distinctBy { it.id }
                .sortedByDescending { it.importance }
                .take(limit)

            // 更新访问信息
            deduped.forEach { touchMemory(it) }

            formatMemoryContext(deduped)
        }.onFailure { logE("获取记忆上下文失败", it) }
            .getOrElse { "" }
    }

    /**
     * 搜索记忆（分层查询：短期→中期→长期）
     */
    private suspend fun searchMemories(
        scope: MemoryScope,
        id: Long,
        query: String,
        limit: Int
    ): List<MemoryItem> {
        val key = scopeKey(scope, id)
        val result = mutableListOf<MemoryItem>()
        val resultIds = mutableSetOf<String>()

        // 查询缓存
        val cacheKey = "$key:$query:$limit"
        synchronized(queryCache) {
            queryCache[cacheKey]?.let { return it }
        }

        val queryEmbedding = runCatching { embeddingProvider.embed(query) }.getOrNull()?.toList()

        // 1. 搜索短期记忆
        shortTermCache[key]?.let { items ->
            synchronized(items) {
                val matched = MemoryIndex().apply {
                    items.forEach { add(it) }
                }.search(query, queryEmbedding, limit = limit)
                items.filter { it.id in matched }.forEach {
                    if (it.id !in resultIds) {
                        result.add(it)
                        resultIds.add(it.id)
                    }
                }
            }
        }

        // 2. 搜索中期+长期记忆（通过索引）
        val index = indexCache[key] ?: MemoryIndex()
        if (result.size < limit) {
            val matchedIds = index.search(query, queryEmbedding, limit = limit - result.size)
            // 从内存缓存加载
            midTermCache[key]?.let { cache ->
                cache.filter { it.id in matchedIds }.forEach {
                    if (it.id !in resultIds) {
                        result.add(it)
                        resultIds.add(it.id)
                    }
                }
            }
            // 如果内存缓存未命中，从磁盘加载长期记忆
            if (result.size < limit) {
                val stillNeeded = matchedIds.filter { it !in resultIds }
                if (stillNeeded.isNotEmpty()) {
                    val longItems = store.loadTier(scope, id, MemoryTier.LONG)
                    longItems.filter { it.id in stillNeeded }.forEach {
                        if (it.id !in resultIds) {
                            result.add(it)
                            resultIds.add(it.id)
                        }
                    }
                }
            }
        }

        // 更新查询缓存
        synchronized(queryCache) {
            if (queryCache.size > 32) {
                queryCache.remove(queryCache.keys.first())
            }
            queryCache[cacheKey] = result.toList()
        }

        return result
    }

    /**
     * 格式化记忆上下文
     */
    private fun formatMemoryContext(memories: List<MemoryItem>): String {
        if (memories.isEmpty()) return ""
        return buildString {
            append("\n=== 关于用户的记忆 ===\n")
            memories.forEach { item ->
                val categoryLabel = when (item.category) {
                    MemoryCategory.FACT -> "事实"
                    MemoryCategory.EMOTION -> "情感"
                    MemoryCategory.PREFERENCE -> "偏好"
                    MemoryCategory.EVENT -> "事件"
                    MemoryCategory.HABIT -> "习惯"
                    MemoryCategory.RELATIONSHIP -> "关系"
                }
                append("【$categoryLabel】${item.content}\n")
            }
        }
    }

    /**
     * 更新记忆访问信息
     */
    private fun touchMemory(item: MemoryItem) {
        val now = System.currentTimeMillis()
        val touched = item.touch(now)

        // 更新短期记忆缓存
        val key = scopeKey(item.scope, item.sourceId)
        shortTermCache[key]?.let { items ->
            synchronized(items) {
                val idx = items.indexOfFirst { it.id == item.id }
                if (idx >= 0) items[idx] = touched
            }
        }

        // 更新中期记忆缓存
        midTermCache[key]?.let { items ->
            synchronized(items) {
                val idx = items.indexOfFirst { it.id == item.id }
                if (idx >= 0) items[idx] = touched
            }
        }

        // 更新索引
        indexCache[key]?.touch(item.id)
    }

    /**
     * 保存记忆
     *
     * @param content 记忆内容
     * @param category 记忆类别
     * @param importance 重要度 [0.0, 1.0]
     * @param source 来源
     * @param sourceId 来源ID（companionId 或 groupId）
     * @param scope 作用域
     * @return 记忆ID（null表示保存失败或被去重合并）
     */
    suspend fun saveMemory(
        content: String,
        category: MemoryCategory,
        importance: Float,
        source: MemorySource,
        sourceId: Long,
        scope: MemoryScope
    ): String? {
        val key = scopeKey(scope, sourceId)
        return getLock(scope, sourceId).withLock {
            runCatching {
                // 去重检查
                val existing = findSimilar(key, content)
                if (existing != null) {
                    // 合并：更新重要度和访问次数
                    val merged = existing.copy(
                        importance = maxOf(existing.importance, importance),
                        accessCount = existing.accessCount + 1,
                        lastAccessed = System.currentTimeMillis()
                    )
                    updateMemoryInternal(scope, sourceId, merged)
                    _memoriesChanged.tryEmit(key)
                    return@runCatching existing.id
                }

                // 创建新记忆
                val now = System.currentTimeMillis()
                val computedEmbedding = runCatching { embeddingProvider.embed(content) }.getOrNull()
                // 事实/偏好/关系类，或重要度较高的记忆，属于"应该被长期记住"的核心信息
                // （比如"我是女生"），不该只给 5 分钟寿命就被清理任务删掉；
                // 直接跳过短期层，进中期存储且不设过期时间。
                val isDurable = category in setOf(MemoryCategory.FACT, MemoryCategory.PREFERENCE, MemoryCategory.RELATIONSHIP) ||
                    importance >= 0.7f
                val item = MemoryItem(
                    id = UUID.randomUUID().toString(),
                    content = content,
                    category = category,
                    importance = importance.coerceIn(0f, 1f),
                    timestamp = now,
                    lastAccessed = now,
                    source = source,
                    sourceId = sourceId,
                    scope = scope,
                    tags = MemoryTokenizer.extractKeywords(content),
                    expireAt = if (isDurable) null else now + SHORT_TERM_TTL_MS,
                    tier = if (isDurable) MemoryTier.MID else MemoryTier.SHORT,
                    embedding = computedEmbedding?.toList()
                )

                // 永久/中期记忆直接进 midTermCache，不经过 shortTermCache：
                // cleanupExpiredMemories() 里的"超容量淘汰最老一条"只看 lastAccessed，
                // 不检查 tier/expireAt，如果先放进 shortTermCache、等它自然溢出才晋级，
                // 这样核心记忆会在晋级前被淘汰，从界面上消失。
                if (isDurable) {
                    midTermCache.computeIfAbsent(key) { mutableListOf() }
                        .let { items -> synchronized(items) { items.add(item) } }
                } else {
                    shortTermCache.computeIfAbsent(key) { mutableListOf() }
                        .let { items ->
                            synchronized(items) {
                                items.add(item)
                                // 超容量时晋级最旧的到中期
                                if (items.size > SHORT_TERM_MAX_PER_SCOPE) {
                                    val toPromote = items.removeAt(0)
                                    promoteToMid(scope, sourceId, toPromote)
                                }
                            }
                        }
                }

                // 更新索引
                indexCache.computeIfAbsent(key) { MemoryIndex() }.add(item)

                // 立即同步持久化（不依赖任何后台协程/生命周期回调是否来得及执行，
                // 从根本上消除"划掉后台瞬间进程被杀、写盘还没发生"的竞态）
                persistNow(scope, sourceId)

                // [R9 FIX] 写入后清除查询缓存：原 queryCache 写入后不清除，saveMemory 后同 key
                // 查询返回旧快照，新记忆最多被 32 条查询掩盖。
                invalidateQueryCache()

                item.id
            }.onFailure { logE("保存记忆失败", it) }
                .getOrNull()
        }
    }

    /**
     * [R9 FIX] 清除查询缓存：在任何写入操作后调用，保证后续查询读到最新数据。
     */
    private fun invalidateQueryCache() {
        synchronized(queryCache) {
            queryCache.clear()
        }
    }

    /**
     * 查找相似记忆（用于去重）
     */
    private fun findSimilar(key: String, content: String): MemoryItem? {
        val allItems = mutableListOf<MemoryItem>()
        shortTermCache[key]?.let { allItems.addAll(it) }
        midTermCache[key]?.let { allItems.addAll(it) }

        return allItems.firstOrNull { existing ->
            MemoryTokenizer.similarity(existing.content, content) > DEDUP_SIMILARITY_THRESHOLD
        }
    }

    

    /**
     * 短期记忆晋级到中期
     */
    private fun promoteToMid(scope: MemoryScope, id: Long, item: MemoryItem) {
        val key = scopeKey(scope, id)
        val promoted = item.copy(
            tier = MemoryTier.MID,
            expireAt = null
        )
        midTermCache.computeIfAbsent(key) { mutableListOf() }
            .let { items ->
                synchronized(items) { items.add(promoted) }
            }
        schedulePersist(scope, id)
    }

    /**
     * 中期记忆晋级到长期
     */
    private suspend fun promoteToLong(scope: MemoryScope, id: Long, item: MemoryItem) {
        val key = scopeKey(scope, id)
        val promoted = item.copy(tier = MemoryTier.LONG)

        // 从中期缓存移除
        midTermCache[key]?.let { items ->
            synchronized(items) { items.removeAll { it.id == item.id } }
        }

        // 写入长期存储
        val longItems = store.loadTier(scope, id, MemoryTier.LONG).toMutableList()
        longItems.add(promoted)
        store.saveTier(scope, id, MemoryTier.LONG, longItems)

        // 更新长期索引
        longTermIndex.computeIfAbsent(key) { MemoryIndex() }.add(promoted)
        indexCache[key]?.add(promoted)

        schedulePersist(scope, id)
    }

    /**
     * 更新记忆（内部）
     */
    private fun updateMemoryInternal(scope: MemoryScope, sourceId: Long, item: MemoryItem) {
        _memoriesChanged.tryEmit(scopeKey(scope, sourceId))
        val key = scopeKey(scope, sourceId)

        // 更新短期记忆
        shortTermCache[key]?.let { items ->
            synchronized(items) {
                val idx = items.indexOfFirst { it.id == item.id }
                if (idx >= 0) items[idx] = item
            }
        }
        // 更新中期记忆
        midTermCache[key]?.let { items ->
            synchronized(items) {
                val idx = items.indexOfFirst { it.id == item.id }
                if (idx >= 0) items[idx] = item
            }
        }

        // 检查是否应晋级到长期
        if (item.shouldPromoteToLong() && item.tier != MemoryTier.LONG) {
            ioScope.launch { promoteToLong(scope, sourceId, item) }
        }

        schedulePersist(scope, sourceId)
        // [R9 FIX] 更新后也清除查询缓存
        invalidateQueryCache()
    }

/**
     * 立即（同步）把所有当前在内存里但还没写盘的记忆刷到磁盘。
     * schedulePersist() 只是把落盘任务丢进 ioScope 异步执行，如果 App 在任务
     * 真正执行前就被系统杀掉进程（比如用户划掉后台），这次写入就彻底丢了——
     * 这也是"核心记忆"关闭 App 后消失的根因。这个方法在 App 进入后台时
     * （LianYuApplication.onTrimMemory）同步调用一次，确保杀进程前已经落盘。
     */
    fun flushAllPending() {
        val keys = shortTermCache.keys + midTermCache.keys + indexCache.keys
        keys.toSet().forEach { key ->
            runCatching {
                val id = key.substringAfterLast("_").toLongOrNull() ?: return@forEach
                val scope = MemoryScope.valueOf(key.substringBeforeLast("_"))
                val shortItems = shortTermCache[key]?.toList() ?: emptyList()
                val midItems = midTermCache[key]?.toList() ?: emptyList()
                store.saveTier(scope, id, MemoryTier.SHORT, shortItems)
                store.saveTier(scope, id, MemoryTier.MID, midItems)
                indexCache[key]?.let { index -> store.saveIndex(scope, id, index.serialize()) }
            }.onFailure { logE("flushAllPending 失败 key=$key", it) }
        }
    }

    /**
     * 同步持久化：调用方 suspend 挂起直到真正写盘完成再返回。
     * 用在"新建记忆"这种不能接受丢失的关键路径上，避免依赖 ioScope 异步任务
     * 是否来得及在进程被杀之前跑完。
     */
    private suspend fun persistNow(scope: MemoryScope, id: Long) = withContext(Dispatchers.IO) {
        val key = scopeKey(scope, id)
        runCatching {
            val shortItems = shortTermCache[key]?.toList() ?: emptyList()
            val midItems = midTermCache[key]?.toList() ?: emptyList()

            store.saveTier(scope, id, MemoryTier.SHORT, shortItems)
            store.saveTier(scope, id, MemoryTier.MID, midItems)

            indexCache[key]?.let { index ->
                store.saveIndex(scope, id, index.serialize())
            }
        }.onFailure { logE("持久化失败 scope=$key", it) }
    }

    /**
     * 调度异步持久化
     */
    private fun schedulePersist(scope: MemoryScope, id: Long) {
        val key = scopeKey(scope, id)
        ioScope.launch {
            runCatching {
                val shortItems = shortTermCache[key]?.toList() ?: emptyList()
                val midItems = midTermCache[key]?.toList() ?: emptyList()

                store.saveTier(scope, id, MemoryTier.SHORT, shortItems)
                store.saveTier(scope, id, MemoryTier.MID, midItems)

                indexCache[key]?.let { index ->
                    store.saveIndex(scope, id, index.serialize())
                }
            }.onFailure { logE("持久化失败 scope=$key", it) }
        }
    }

    /**
     * 获取作用域下所有记忆（用于UI展示）
     */
    suspend fun getMemories(scope: MemoryScope, id: Long): List<MemoryItem> {
        val key = scopeKey(scope, id)
        val result = mutableListOf<MemoryItem>()

        shortTermCache[key]?.let { result.addAll(it) }
        midTermCache[key]?.let { result.addAll(it) }

        // 加载长期记忆
        result.addAll(store.loadTier(scope, id, MemoryTier.LONG))

        return result.sortedByDescending { it.timestamp }
    }

    /**
     * 删除记忆
     */
    suspend fun deleteMemory(scope: MemoryScope, sourceId: Long, id: String) {
        deleteMemoryFromScope(scope, sourceId, id)
    }

    suspend fun updateMemory(scope: MemoryScope, sourceId: Long, oldId: String, newContent: String, newCategory: MemoryCategory, newImportance: Float): String? {
        deleteMemoryFromScope(scope, sourceId, oldId)
        return saveMemory(newContent, newCategory, newImportance, MemorySource.MANUAL, sourceId, scope)
    }

    /**
     * 从指定作用域删除记忆
     */
    private suspend fun deleteMemoryFromScope(scope: MemoryScope, sourceId: Long, id: String) {
        val key = scopeKey(scope, sourceId)
        var deleted = false

        shortTermCache[key]?.let { items ->
            synchronized(items) { deleted = items.removeAll { it.id == id } || deleted }
        }
        midTermCache[key]?.let { items ->
            synchronized(items) { deleted = items.removeAll { it.id == id } || deleted }
        }
        indexCache[key]?.remove(id)

        if (deleted) {
            _memoriesChanged.tryEmit(key)
            schedulePersist(scope, sourceId)
            // [R9 FIX] 删除后也清除查询缓存
            invalidateQueryCache()
        }

        // 也检查长期记忆文件
        val longItems = store.loadTier(scope, sourceId, MemoryTier.LONG).toMutableList()
        if (longItems.removeAll { it.id == id }) {
            store.saveTier(scope, sourceId, MemoryTier.LONG, longItems)
            longTermIndex[key]?.remove(id)
        }
    }

    /**
     * 删除整个作用域的所有记忆
     */
    suspend fun deleteAllMemories(scope: MemoryScope, id: Long) {
        val key = scopeKey(scope, id)
        shortTermCache.remove(key)
        midTermCache.remove(key)
        indexCache.remove(key)
        longTermIndex.remove(key)
        store.deleteScope(scope, id)
        if (scope == MemoryScope.COMPANION) storyCache.remove(id)
    }

// ── 剧情状态 ──

    override suspend fun deleteCompanionMemories(companionId: Long) =
        deleteAllMemories(MemoryScope.COMPANION, companionId)

    override suspend fun deleteGroupMemories(groupId: Long) =
        deleteAllMemories(MemoryScope.GROUP, groupId)

    private fun loadStory(id: Long): StoryState? =
        storyCache[id] ?: store.loadStoryState(MemoryScope.COMPANION, id)?.also { storyCache[id] = it }

    override suspend fun getStoryContext(companionId: Long): String = withContext(Dispatchers.IO) {
        val state = loadStory(companionId) ?: return@withContext ""
        if (state.summary.isBlank()) "" else "【剧情摘要】\n${state.summary}"
    }

    override suspend fun recordStoryTurn(
        companionId: Long,
        companionName: String,
        userInput: String,
        aiResponse: String
    ) {
        runCatching {
            val turn = "用户：${userInput.take(150)}\n$companionName：${aiResponse.take(200)}"
            val shouldSummarize = getLock(MemoryScope.COMPANION, companionId).withLock {
                withContext(Dispatchers.IO) {
                    val old = loadStory(companionId) ?: StoryState(companionId)
                    val next = old.copy(pendingTurns = (old.pendingTurns + turn).takeLast(STORY_PENDING_MAX))
                    storyCache[companionId] = next
                    store.saveStoryState(MemoryScope.COMPANION, companionId, next)
                    dbg("[剧情] 记录一轮 待摘要=${next.pendingTurns.size}/$STORY_SUMMARY_EVERY_TURNS")
                    next.pendingTurns.size >= STORY_SUMMARY_EVERY_TURNS
                }
            }
            if (shouldSummarize) launchStorySummary(companionId, companionName)
        }.onFailure { logE("记录剧情轮数失败", it) }
    }

    private fun launchStorySummary(companionId: Long, companionName: String) {
        if (System.currentTimeMillis() < (summaryRetryAt[companionId] ?: 0L)) {
            dbg("[剧情摘要] 冷却中，跳过")
            return
        }
        if (!summarizing.add(companionId)) {
            dbg("[剧情摘要] 已有摘要在进行，跳过")
            return
        }
        ioScope.launch {
            try {
                summarizeStory(companionId, companionName)
            } finally {
                summarizing.remove(companionId)
            }
        }
    }

    private suspend fun summarizeStory(companionId: Long, companionName: String) {
        val snapshot = loadStory(companionId) ?: return
        val batch = snapshot.pendingTurns
        if (batch.isEmpty()) return

        val prompt = buildString {
            appendLine("把新对话并入旧摘要，写出更新后的摘要。只记录发生了什么：谁说了什么、做了什么、约定了什么。")
            appendLine("不要评价，不要分析，不要猜测心理，200字以内。")
            appendLine("不要照抄原话，不要使用引号，用自己的话概括。")
            appendLine()
            appendLine("示例：")
            appendLine("旧摘要：用户第一次来找${companionName}聊天。")
            appendLine("新对话：")
            appendLine("用户：我今天考试没考好。")
            appendLine("${companionName}：别难过，我陪你。")
            appendLine("更新后的摘要：用户第一次来找${companionName}聊天，说自己今天考试没考好，${companionName}安慰了用户，说会陪着用户。")
            appendLine()
            appendLine("旧摘要：${snapshot.summary.ifBlank { "（无）" }}")
            appendLine("新对话：")
            batch.forEach { appendLine(it) }
            append("更新后的摘要：")
        }

        val local = ServiceRegistry.get(LocalModelProvider::class.java)?.takeIf { it.isAvailable() }
        dbg("[剧情摘要] 开始 companion=$companionId 待摘要=${batch.size}轮 提示词=${prompt.length}字 通道=${if (local != null) "本地模型" else "云端API"}")
        val raw = runCatching {
            if (local != null) {
                withTimeoutOrNull(TimeoutBudgets.STORY_SUMMARY_LOCAL_MS) {
                    local.generateResponse(prompt, STORY_SUMMARY_SYSTEM)
                }
            } else {
                val api = ServiceRegistry.get(AiServiceProvider::class.java)
                withTimeoutOrNull(TimeoutBudgets.STORY_SUMMARY_API_MS) { api?.callSummary(prompt) }
            }
        }.onFailure { logE("[剧情摘要] 调用异常", it) }.getOrNull()
        if (raw == null) dbg("[剧情摘要] 无返回（超时、异常或接口不可用）")

        val cleaned = raw.orEmpty()
            .replace(Regex("(?is)<think[^>]*>[\\s\\S]*?</think\\s*>"), "")
            .trim()
            .removePrefix("更新后的摘要：").removePrefix("更新后的摘要:")
            .replace(Regex("[\"“”]"), "")
            .substringBefore("新对话").substringBefore("旧摘要").substringBefore("\n\n")
            .trim()
            .take(STORY_SUMMARY_MAX_CHARS)
        val badMarker = STORY_SUMMARY_BAD_MARKERS.firstOrNull { cleaned.contains(it) }
        if (cleaned.length < 20 || badMarker != null) {
            summaryRetryAt[companionId] = System.currentTimeMillis() + STORY_SUMMARY_RETRY_COOLDOWN_MS
            val why = if (cleaned.length < 20) "过短(${cleaned.length}字)" else "含禁用词「$badMarker」"
            dbg("[剧情摘要] 被拒绝：$why，冷却5分钟")
            dbgContent("[剧情摘要] 被拒绝的原始输出=${raw.orEmpty().take(300)}")
            return
        }

        getLock(MemoryScope.COMPANION, companionId).withLock {
            withContext(Dispatchers.IO) {
                val current = loadStory(companionId) ?: return@withContext
                // 摘要期间如果剧情被清空/重新开始，待摘要轮次已变，丢弃这份过期摘要
                if (current.pendingTurns.take(batch.size) != batch) {
                    dbg("[剧情摘要] 丢弃：摘要期间剧情已被清空或变化")
                    return@withContext
                }
                val next = current.copy(
                    summary = cleaned,
                    pendingTurns = current.pendingTurns.drop(batch.size)
                )
                storyCache[companionId] = next
                store.saveStoryState(MemoryScope.COMPANION, companionId, next)
                dbg("[剧情摘要] 已保存 ${cleaned.length}字，剩余待摘要=${next.pendingTurns.size}轮")
                dbgContent("[剧情摘要] 摘要内容：$cleaned")
            }
        }
    }

    override suspend fun clearStoryContext(companionId: Long) {
        runCatching {
            getLock(MemoryScope.COMPANION, companionId).withLock {
                storyCache.remove(companionId)
                withContext(Dispatchers.IO) { store.deleteStoryState(MemoryScope.COMPANION, companionId) }
            }
        }.onFailure { logE("清除剧情状态失败", it) }
    }

        /** 记忆管理页用：读取剧情摘要原文，没有则返回空串。 */
        override suspend fun getStorySummary(companionId: Long): String = withContext(Dispatchers.IO) {
        loadStory(companionId)?.summary.orEmpty()
    }

    /** 记忆管理页用：手动修改剧情摘要，保留还没摘要的待处理对话。 */
    override suspend fun setStorySummary(companionId: Long, summary: String) {
        runCatching {
            getLock(MemoryScope.COMPANION, companionId).withLock {
                withContext(Dispatchers.IO) {
                    val old = loadStory(companionId) ?: StoryState(companionId)
                    val next = old.copy(summary = summary.trim())
                    storyCache[companionId] = next
                    store.saveStoryState(MemoryScope.COMPANION, companionId, next)
                }
            }
        }.onFailure { logE("修改剧情摘要失败", it) }
    }
    
    /**
     * 从对话中提取并保存记忆
     *
     * @param userInput 用户输入
     * @param aiResponse AI回复
     * @param companionId 角色ID
     * @param groupId 群聊ID（群聊时传入）
     */
    override suspend fun extractAndSaveFromConversation(
        userInput: String,
        aiResponse: String,
        companionId: Long,
        groupId: Long?
    ) {
        runCatching {
            val scope = if (groupId != null) MemoryScope.GROUP else MemoryScope.COMPANION
            val sourceId = if (groupId != null) groupId else companionId
            val source = if (groupId != null) MemorySource.GROUP_CHAT else MemorySource.CHAT

                        // 单聊用"我的信息"+该角色专属设定合并后的结果；群聊只用"我的信息"
            val profile = UserRepository(context).getEffectiveSelfProfile(if (groupId != null) null else companionId)
            val extracted = extractMemories(userInput).filterNot { isCoveredByProfile(it.first, profile) }
            extracted.forEach { (content, category, importance) ->
                saveMemory(content, category, importance, source, sourceId, scope)
            }
        }.onFailure { logE("提取记忆失败", it) }
    }

    /**
     * 基于关键词的记忆提取
     * 改进版：支持中文，覆盖更多类别
     */
    /**
 * 简单判断是否为疑问句，避免把"我是男生还是女生"这类提问误存为事实
 */
private fun isLikelyQuestion(text: String): Boolean {
    val t = text.trim()
    if (t.endsWith("?") || t.endsWith("？")) return true
    if (t.endsWith("吗") || t.endsWith("呢")) return true
    val markers = listOf(
        "还是", "多少", "怎么样", "怎么", "为什么", "什么时候", "什么",
        "谁", "哪", "如何", "几点", "几岁", "是不是", "有没有", "对不对", "好不好"
    )
    return markers.any { t.contains(it) }
}
    private fun extractMemories(text: String): List<Triple<String, MemoryCategory, Float>> {
        val result = mutableListOf<Triple<String, MemoryCategory, Float>>()

        // 事实类
        val factPatterns = listOf("我叫", "我是", "我来自", "我在", "我的名字", "我住", "我工作", "我学", "我的职业")
        factPatterns.forEach { pattern ->
            if (text.contains(pattern)) {
                extractAfterPattern(text, pattern)?.let {
                    if (!isLikelyQuestion(it)) result.add(Triple(it, MemoryCategory.FACT, 0.8f))
                }
            }
        }

        // 偏好类
        val preferencePatterns = listOf("我喜欢", "我爱好", "我偏爱", "我讨厌", "我不喜欢", "我反感", "我爱", "我恨")
        preferencePatterns.forEach { pattern ->
            if (text.contains(pattern)) {
                extractAfterPattern(text, pattern)?.let {
                    if (!isLikelyQuestion(it)) result.add(Triple(it, MemoryCategory.PREFERENCE, 0.75f))
                }
            }
        }

        // 习惯类
        val habitPatterns = listOf("我每天", "我经常", "我总是", "我通常", "我习惯", "我一般")
        habitPatterns.forEach { pattern ->
            if (text.contains(pattern)) {
                extractAfterPattern(text, pattern)?.let {
                    if (!isLikelyQuestion(it)) result.add(Triple(it, MemoryCategory.HABIT, 0.7f))
                }
            }
        }

        // 关系类
        val relationshipPatterns = listOf("我的朋友", "我的家人", "我的父母", "我的同学", "我的同事", "我的男朋友", "我的女朋友")
        relationshipPatterns.forEach { pattern ->
            if (text.contains(pattern)) {
                extractAfterPattern(text, pattern)?.let {
                    if (!isLikelyQuestion(it)) result.add(Triple(it, MemoryCategory.RELATIONSHIP, 0.8f))
                }
            }
        }

        return result.distinctBy { it.first }
    }

    /**
     * 从模式后提取内容（到句号或换行）
     */
    private fun extractAfterPattern(text: String, pattern: String): String? {
        val idx = text.indexOf(pattern)
        if (idx < 0) return null
        val start = idx + pattern.length
        val endText = text.substring(start)
        val endIdx = endText.indexOfFirst { it in "。，！？；\n" }
        val content = if (endIdx > 0) endText.substring(0, endIdx) else endText.take(50)
        val full = "$pattern$content".trim()
        return if (full.length > pattern.length + 1) full else null
    }
    /**
     * "自己设定"里已填的项，不再从聊天里提取对应内容。
     * fact 的格式是"触发词+内容"，如"我叫小明""我是女生""我是18岁"。
     */
    private fun isCoveredByProfile(fact: String, p: UserSelfProfile): Boolean {
        if (p.name.isNotBlank() && (fact.startsWith("我叫") || fact.startsWith("我的名字"))) return true
        if (p.occupation.isNotBlank() && (fact.startsWith("我的职业") || fact.startsWith("我工作"))) return true
        if (p.gender.isNotBlank() && fact.startsWith("我是") &&
            listOf("女生", "男生", "女孩", "男孩", "女的", "男的", "女性", "男性", "女人", "男人").any { fact.contains(it) }
        ) return true
        if (p.age.isNotBlank() && fact.contains("岁")) return true
        return false
    }
}
