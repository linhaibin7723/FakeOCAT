package com.example.fakeocat.network

import java.util.concurrent.ConcurrentHashMap

/**
 * 模型列表的内存缓存。
 *
 * 每个 Provider 独立缓存，30 分钟过期后自动失效。
 * 过期数据仍可被读取（返回带 stale 标记），供网络失败时降级使用。
 */
object ModelCache {

    private const val MAX_CACHE_AGE_MS = 30 * 60 * 1000L // 30 分钟

    data class CacheEntry(
        val models: List<AiModel>,
        val timestamp: Long
    )

    /** 缓存是否已过期 */
    fun CacheEntry.isStale(): Boolean =
        System.currentTimeMillis() - timestamp > MAX_CACHE_AGE_MS

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    /**
     * 获取缓存中的模型列表。
     * @return 未过期的缓存数据，过期或不存在则返回 null。
     */
    fun get(providerId: String): List<AiModel>? {
        val entry = cache[providerId] ?: return null
        if (entry.isStale()) {
            // 过期时不删除条目，保留供 getOrNull() 降级使用
            return null
        }
        return entry.models
    }

    /**
     * 获取可能过期的缓存数据（供降级使用）。
     * @return 缓存数据（可能已过期），不存在则返回 null。
     */
    fun getOrNull(providerId: String): List<AiModel>? {
        return cache[providerId]?.models
    }

    /**
     * 写入缓存。
     */
    fun put(providerId: String, models: List<AiModel>) {
        cache[providerId] = CacheEntry(models, System.currentTimeMillis())
    }

    /**
     * 清除指定 Provider 或全部缓存。
     */
    fun clear(providerId: String? = null) {
        if (providerId != null) cache.remove(providerId)
        else cache.clear()
    }

    /**
     * 获取缓存的最后更新时间戳（仅未过期时返回）。
     */
    fun getLastUpdateTime(providerId: String): Long? {
        val entry = cache[providerId] ?: return null
        return if (entry.isStale()) null else entry.timestamp
    }
}
