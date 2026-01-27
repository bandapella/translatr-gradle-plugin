package app.translatr

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TranslatrClientTest {
    private lateinit var mockServer: MockWebServer
    private lateinit var client: TranslatrClient
    
    @BeforeEach
    fun setup() {
        mockServer = MockWebServer()
        mockServer.start()
        
        client = TranslatrClient(
            serverUrl = mockServer.url("/api").toString().removeSuffix("/"),
            apiKey = "test-api-key",
            timeoutSeconds = 5,
            maxRetries = 1
        )
    }
    
    @AfterEach
    fun teardown() {
        mockServer.shutdown()
    }
    
    @Test
    fun `translate sends correct request format`() {
        // Mock job creation response
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"job_id": "test-job-123", "status": "pending", "created_at": "2024-01-01T00:00:00Z"}""")
        )
        // Mock job status response
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"id": "test-job-123", "status": "completed", "progress": 100, "created_at": "2024-01-01T00:00:00Z", "updated_at": "2024-01-01T00:00:01Z", "completed_at": "2024-01-01T00:00:01Z", "translations": {"hello": {"es": "hola"}}, "meta": {"cached": 0, "translated": 1}}""")
        )
        
        val request = mapOf("hello" to mapOf("value" to "Hello", "hash" to "abc123"))
        val response = client.translate(request)
        
        assertEquals(1, response.translations.size)
        assertEquals("hola", response.translations["hello"]?.get("es"))
        
        val recordedRequest = mockServer.takeRequest()
        assertEquals("POST", recordedRequest.method)
        assertTrue(recordedRequest.path!!.contains("/translate"))
        assertEquals("test-api-key", recordedRequest.getHeader("X-API-Key"))
    }
    
    @Test
    fun `translate throws on 401 unauthorized`() {
        mockServer.enqueue(MockResponse()
            .setResponseCode(401)
            .setBody("""{"error": "Unauthorized", "message": "Invalid API key"}""")
        )
        
        val request = mapOf("hello" to mapOf("value" to "Hello"))
        
        val exception = assertFailsWith<TranslatrApiException> {
            client.translate(request)
        }
        
        assertTrue(exception.userMessage.contains("invalid API key"), "Expected API key error, got: ${exception.userMessage}")
    }
    
    @Test
    fun `translate throws on 403 forbidden`() {
        mockServer.enqueue(MockResponse()
            .setResponseCode(403)
            .setBody("""{"error": "Forbidden"}""")
        )
        
        val request = mapOf("hello" to mapOf("value" to "Hello"))
        
        val exception = assertFailsWith<TranslatrApiException> {
            client.translate(request)
        }
        
        assertTrue(exception.userMessage.contains("invalid API key") || exception.userMessage.contains("rejected"))
    }
    
    @Test
    fun `translate throws on insufficient credits`() {
        mockServer.enqueue(MockResponse()
            .setResponseCode(402)
            .setBody("""{"error": "Payment Required", "message": "Credit balance is too low"}""")
        )
        
        val request = mapOf("hello" to mapOf("value" to "Hello"))
        
        val exception = assertFailsWith<TranslatrApiException> {
            client.translate(request)
        }
        
        assertTrue(exception.userMessage.contains("credits are exhausted"), "Expected credits error, got: ${exception.userMessage}")
    }
    
    @Test
    fun `translate throws on 429 rate limit`() {
        mockServer.enqueue(MockResponse()
            .setResponseCode(429)
            .setBody("""{"error": "Too Many Requests"}""")
        )
        
        val request = mapOf("hello" to mapOf("value" to "Hello"))
        
        val exception = assertFailsWith<TranslatrApiException> {
            client.translate(request)
        }
        
        assertTrue(exception.userMessage.contains("rate limit exceeded"))
    }
    
    @Test
    fun `translate throws on 500 server error`() {
        mockServer.enqueue(MockResponse()
            .setResponseCode(500)
            .setBody("""{"error": "Internal Server Error"}""")
        )
        
        val request = mapOf("hello" to mapOf("value" to "Hello"))
        
        val exception = assertFailsWith<TranslatrApiException> {
            client.translate(request)
        }
        
        assertTrue(exception.userMessage.contains("server error"))
    }
    
    @Test
    fun `getCachedTranslations sends GET request`() {
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"translations": {"world": {"fr": "monde"}}}""")
        )
        
        val response = client.getCachedTranslations()
        
        assertEquals(1, response.translations.size)
        assertEquals("monde", response.translations["world"]?.get("fr"))
        
        val recordedRequest = mockServer.takeRequest()
        assertEquals("GET", recordedRequest.method)
        assertEquals("test-api-key", recordedRequest.getHeader("X-API-Key"))
    }
    
    @Test
    fun `translate retries on network error`() {
        // First attempt fails
        mockServer.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST))
        // Second attempt succeeds - job creation
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"job_id": "test-job-456", "status": "pending", "created_at": "2024-01-01T00:00:00Z"}""")
        )
        // Job status response
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"id": "test-job-456", "status": "completed", "progress": 100, "created_at": "2024-01-01T00:00:00Z", "updated_at": "2024-01-01T00:00:01Z", "completed_at": "2024-01-01T00:00:01Z", "translations": {"test": {"de": "Test"}}, "meta": {"cached": 0, "translated": 1}}""")
        )
        
        val clientWithRetries = TranslatrClient(
            serverUrl = mockServer.url("/api").toString().removeSuffix("/"),
            apiKey = "test-api-key",
            timeoutSeconds = 5,
            maxRetries = 2
        )
        
        val request = mapOf("test" to mapOf("value" to "Test"))
        val response = clientWithRetries.translate(request)
        
        assertEquals(1, response.translations.size)
        assertEquals(3, mockServer.requestCount)
    }
}
