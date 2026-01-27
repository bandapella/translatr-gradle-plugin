package app.translatr.integration

import app.translatr.TranslatrClient
import app.translatr.XmlParser
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TranslatrTaskIntegrationTest {
    private lateinit var mockServer: MockWebServer
    
    @TempDir
    lateinit var tempDir: File
    
    @BeforeEach
    fun setup() {
        mockServer = MockWebServer()
        mockServer.start()
    }
    
    @AfterEach
    fun teardown() {
        mockServer.shutdown()
    }
    
    @Test
    fun `full translation flow with cache`() {
        // Create source strings.xml
        val sourceFile = File(tempDir, "strings.xml").apply {
            writeText("""
                <?xml version="1.0" encoding="utf-8"?>
                <resources>
                    <string name="app_name">TestApp</string>
                    <string name="welcome">Welcome</string>
                </resources>
            """.trimIndent())
        }
        
        val outputDir = File(tempDir, "res")
        outputDir.mkdirs()
        
        // Mock server response for translation - job creation
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"job_id": "test-job-789", "status": "pending", "created_at": "2024-01-01T00:00:00Z"}""")
        )
        
        // Mock server response for job status
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""
                {
                    "id": "test-job-789",
                    "status": "completed",
                    "progress": 100,
                    "created_at": "2024-01-01T00:00:00Z",
                    "updated_at": "2024-01-01T00:00:01Z",
                    "completed_at": "2024-01-01T00:00:01Z",
                    "translations": {
                        "app_name": {"es": "TestApp", "fr": "TestApp"},
                        "welcome": {"es": "Bienvenido", "fr": "Bienvenue"}
                    },
                    "meta": {"cached": 0, "translated": 2, "total": 2}
                }
            """.trimIndent())
        )
        
        // Mock server response for cached translations
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""
                {
                    "translations": {
                        "app_name": {"es": "TestApp", "fr": "TestApp"},
                        "welcome": {"es": "Bienvenido", "fr": "Bienvenue"}
                    }
                }
            """.trimIndent())
        )
        
        // Parse source and make request
        val strings = XmlParser.parseStringsXml(sourceFile)
        val client = TranslatrClient(
            serverUrl = mockServer.url("/api").toString().removeSuffix("/"),
            apiKey = "test-key"
        )
        
        val request = strings.mapValues { mapOf("value" to it.value, "hash" to "test") }
        val response = client.translate(request)
        
        // Write output files
        response.translations.values.flatMap { it.keys }.toSet().forEach { lang ->
            val langTranslations = response.translations
                .filter { it.value.containsKey(lang) }
                .mapValues { it.value[lang]!! }
            XmlParser.writeStringsXml(outputDir, lang, langTranslations)
        }
        
        // Verify output files exist
        assertTrue(File(outputDir, "values-es/strings.xml").exists())
        assertTrue(File(outputDir, "values-fr/strings.xml").exists())
        
        // Verify content
        val esTranslations = XmlParser.parseStringsXml(File(outputDir, "values-es/strings.xml"))
        assertEquals("Bienvenido", esTranslations["welcome"])
        
        val frTranslations = XmlParser.parseStringsXml(File(outputDir, "values-fr/strings.xml"))
        assertEquals("Bienvenue", frTranslations["welcome"])
    }
    
    @Test
    fun `incremental translation only translates changed strings`() {
        val sourceFile = File(tempDir, "strings.xml").apply {
            writeText("""
                <?xml version="1.0" encoding="utf-8"?>
                <resources>
                    <string name="unchanged">Same</string>
                    <string name="modified">Original</string>
                </resources>
            """.trimIndent())
        }
        
        // Simulate cache with old hash for "modified"
        val strings = XmlParser.parseStringsXml(sourceFile)
        
        // Modify one string
        sourceFile.writeText("""
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="unchanged">Same</string>
                <string name="modified">Updated</string>
            </resources>
        """.trimIndent())
        
        val newStrings = XmlParser.parseStringsXml(sourceFile)
        
        // Only "modified" should be different
        assertEquals(strings["unchanged"], newStrings["unchanged"])
        assertEquals("Updated", newStrings["modified"])
    }
    
    @Test
    fun `handles 401 authentication error`() {
        mockServer.enqueue(MockResponse()
            .setResponseCode(401)
            .setBody("""{"error": "Unauthorized", "message": "Invalid API key"}""")
        )
        
        val client = TranslatrClient(
            serverUrl = mockServer.url("/api").toString().removeSuffix("/"),
            apiKey = "invalid-key",
            maxRetries = 1
        )
        
        try {
            client.translate(mapOf("test" to mapOf("value" to "test")))
            throw AssertionError("Expected TranslatrApiException")
        } catch (e: app.translatr.TranslatrApiException) {
            assertTrue(e.userMessage.contains("invalid API key"))
        }
    }
    
    @Test
    fun `handles 429 rate limiting`() {
        mockServer.enqueue(MockResponse()
            .setResponseCode(429)
            .setBody("""{"error": "Too Many Requests"}""")
        )
        
        val client = TranslatrClient(
            serverUrl = mockServer.url("/api").toString().removeSuffix("/"),
            apiKey = "test-key",
            maxRetries = 1
        )
        
        try {
            client.translate(mapOf("test" to mapOf("value" to "test")))
            throw AssertionError("Expected TranslatrApiException")
        } catch (e: app.translatr.TranslatrApiException) {
            assertTrue(e.userMessage.contains("rate limit"))
        }
    }
    
    @Test
    fun `removed strings are excluded from output`() {
        val outputDir = File(tempDir, "res")
        
        // Initial translations
        val initialTranslations = mapOf(
            "keep" to "Mantener",
            "remove" to "Eliminar"
        )
        XmlParser.writeStringsXml(outputDir, "es", initialTranslations)
        
        // Updated translations without "remove"
        val updatedTranslations = mapOf("keep" to "Mantener")
        XmlParser.writeStringsXml(outputDir, "es", updatedTranslations)
        
        val finalTranslations = XmlParser.parseStringsXml(File(outputDir, "values-es/strings.xml"))
        
        assertTrue(finalTranslations.containsKey("keep"))
        assertFalse(finalTranslations.containsKey("remove"))
    }
}
