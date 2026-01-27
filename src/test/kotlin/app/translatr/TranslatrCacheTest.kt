package app.translatr

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

class TranslatrCacheTest {
    @TempDir
    lateinit var tempDir: File
    
    private fun computeHash(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(content.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }
    
    @Test
    fun `cache serialization and deserialization`() {
        val cache = TranslatrCache(
            sourceHash = "abc123",
            timestamp = System.currentTimeMillis(),
            languages = setOf("es", "fr", "de"),
            stringHashes = mapOf(
                "hello" to "hash1",
                "world" to "hash2"
            )
        )
        
        val cacheFile = File(tempDir, "cache.json")
        val moshi = com.squareup.moshi.Moshi.Builder()
            .add(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
            .build()
        val adapter = moshi.adapter(TranslatrCache::class.java)
        
        cacheFile.writeText(adapter.toJson(cache))
        
        val loaded = adapter.fromJson(cacheFile.readText())
        
        assertNotNull(loaded)
        assertEquals(cache.sourceHash, loaded.sourceHash)
        assertEquals(cache.languages, loaded.languages)
        assertEquals(cache.stringHashes, loaded.stringHashes)
    }
    
    @Test
    fun `hash computation is deterministic`() {
        val content = "Hello, World!"
        
        val hash1 = computeHash(content)
        val hash2 = computeHash(content)
        
        assertEquals(hash1, hash2)
    }
    
    @Test
    fun `hash computation detects changes`() {
        val hash1 = computeHash("Hello")
        val hash2 = computeHash("Hello!")
        
        assertNotEquals(hash1, hash2)
    }
    
    @Test
    fun `cache invalidation on string modification`() {
        val original = mapOf(
            "key1" to "value1",
            "key2" to "value2"
        )
        
        val modified = mapOf(
            "key1" to "value1",
            "key2" to "value2_modified"
        )
        
        val originalHashes = original.mapValues { computeHash(it.value) }
        val modifiedHashes = modified.mapValues { computeHash(it.value) }
        
        assertEquals(originalHashes["key1"], modifiedHashes["key1"])
        assertNotEquals(originalHashes["key2"], modifiedHashes["key2"])
    }
    
    @Test
    fun `cache detects new keys`() {
        val oldHashes = mapOf("key1" to "hash1")
        val newHashes = mapOf("key1" to "hash1", "key2" to "hash2")
        
        val newKeys = newHashes.keys - oldHashes.keys
        
        assertEquals(setOf("key2"), newKeys)
    }
    
    @Test
    fun `cache detects removed keys`() {
        val oldHashes = mapOf("key1" to "hash1", "key2" to "hash2")
        val newHashes = mapOf("key1" to "hash1")
        
        val removedKeys = oldHashes.keys - newHashes.keys
        
        assertEquals(setOf("key2"), removedKeys)
    }
}
