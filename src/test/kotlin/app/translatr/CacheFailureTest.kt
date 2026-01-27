package app.translatr

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for cache failure handling to ensure tasks retry after API failures
 */
class CacheFailureTest {
    @TempDir
    lateinit var tempDir: File
    
    private fun createCacheAdapter() = com.squareup.moshi.Moshi.Builder()
        .add(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
        .build()
        .adapter(TranslatrCache::class.java)
    
    @Test
    fun `cache can be marked as failed`() {
        val cache = TranslatrCache(
            sourceHash = "abc123",
            timestamp = System.currentTimeMillis(),
            languages = setOf("es", "fr"),
            stringHashes = mapOf("hello" to "hash1"),
            failed = true
        )
        
        val cacheFile = File(tempDir, "cache.json")
        val adapter = createCacheAdapter()
        
        cacheFile.writeText(adapter.toJson(cache))
        val loaded = adapter.fromJson(cacheFile.readText())!!
        
        assertTrue(loaded.failed, "Cache should be marked as failed")
    }
    
    @Test
    fun `cache defaults to not failed for backwards compatibility`() {
        // Old cache format without 'failed' field
        val oldCacheJson = """
            {
                "source_hash": "abc123",
                "timestamp": 1234567890,
                "languages": ["es", "fr"],
                "string_hashes": {"hello": "hash1"}
            }
        """.trimIndent()
        
        val cacheFile = File(tempDir, "cache.json")
        cacheFile.writeText(oldCacheJson)
        
        val adapter = createCacheAdapter()
        val loaded = adapter.fromJson(cacheFile.readText())!!
        
        assertFalse(loaded.failed, "Cache should default to not failed for backwards compatibility")
    }
    
    @Test
    fun `successful cache has failed set to false`() {
        val cache = TranslatrCache(
            sourceHash = "abc123",
            timestamp = System.currentTimeMillis(),
            languages = setOf("es", "fr"),
            stringHashes = mapOf("hello" to "hash1"),
            failed = false
        )
        
        val cacheFile = File(tempDir, "cache.json")
        val adapter = createCacheAdapter()
        
        cacheFile.writeText(adapter.toJson(cache))
        val loaded = adapter.fromJson(cacheFile.readText())!!
        
        assertFalse(loaded.failed, "Successful cache should have failed=false")
    }
    
    @Test
    fun `failed cache content changes when failed flag changes`() {
        val successCache = TranslatrCache(
            sourceHash = "abc123",
            timestamp = 1000L,
            languages = setOf("es"),
            stringHashes = mapOf("hello" to "hash1"),
            failed = false
        )
        
        val failedCache = successCache.copy(failed = true)
        
        val adapter = createCacheAdapter()
        val successJson = adapter.toJson(successCache)
        val failedJson = adapter.toJson(failedCache)
        
        // The JSON should be different
        assertTrue(failedJson.contains("\"failed\":true"), "Failed cache JSON should contain failed:true")
        assertTrue(successJson.contains("\"failed\":false"), "Success cache JSON should contain failed:false")
    }
}
