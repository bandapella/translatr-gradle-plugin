package app.translatr

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

@JsonClass(generateAdapter = true)
data class StringWithHash(
    val value: String,
    val hash: String
)

@JsonClass(generateAdapter = true)
data class TranslateRequest(
    val strings: Map<String, Any> // Can be String or StringWithHash
)

@JsonClass(generateAdapter = true)
data class CreateJobResponse(
    @Json(name = "job_id") val jobId: String,
    val status: String,
    @Json(name = "created_at") val createdAt: String
)

@JsonClass(generateAdapter = true)
data class JobStatusResponse(
    val id: String,
    val status: String,
    val progress: Int,
    @Json(name = "strings_count") val stringsCount: Int? = null,
    @Json(name = "created_at") val createdAt: String,
    @Json(name = "updated_at") val updatedAt: String,
    @Json(name = "completed_at") val completedAt: String? = null,
    val translations: Map<String, Map<String, String>>? = null,
    @Json(name = "error_message") val errorMessage: String? = null,
    val meta: TranslateResponseMeta? = null
)

@JsonClass(generateAdapter = true)
data class TranslateResponseMeta(
    val cached: Int? = null,
    val translated: Int? = null,
    val total: Int? = null
)

@JsonClass(generateAdapter = true)
data class TranslateResponse(
    val translations: Map<String, Map<String, String>>,
    val meta: TranslateResponseMeta? = null
)

@JsonClass(generateAdapter = true)
data class ApiErrorResponse(
    val error: String? = null,
    val message: String? = null,
    val details: String? = null
)

@JsonClass(generateAdapter = true)
data class AnthropicErrorEnvelope(
    val type: String? = null,
    val error: AnthropicError? = null,
    @Json(name = "request_id") val requestId: String? = null
)

@JsonClass(generateAdapter = true)
data class AnthropicError(
    val type: String? = null,
    val message: String? = null
)

class TranslatrApiException(
    val userMessage: String,
    val debugMessage: String? = null
) : IOException(userMessage)

class TranslatrClient(
    private val serverUrl: String,
    private val apiKey: String,
    private val timeoutSeconds: Int = 30,
    private val maxRetries: Int = 3
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .readTimeout(6L, TimeUnit.MINUTES) // Increased for polling
        .writeTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .build()
    
    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
    
    private val translateRequestAdapter = moshi.adapter(TranslateRequest::class.java)
    private val translateResponseAdapter = moshi.adapter(TranslateResponse::class.java)
    private val createJobResponseAdapter = moshi.adapter(CreateJobResponse::class.java)
    private val jobStatusResponseAdapter = moshi.adapter(JobStatusResponse::class.java)
    private val apiErrorAdapter = moshi.adapter(ApiErrorResponse::class.java)
    private val anthropicErrorAdapter = moshi.adapter(AnthropicErrorEnvelope::class.java)

    private fun parseApiError(body: String?): ApiErrorResponse? {
        val trimmed = body?.trim()
        if (trimmed.isNullOrEmpty()) return null
        return try {
            apiErrorAdapter.fromJson(trimmed)
        } catch (_: Exception) {
            null
        }
    }

    private fun extractAnthropicErrorMessage(message: String?): String? {
        val trimmed = message?.trim().orEmpty()
        if (trimmed.isEmpty()) return null

        val jsonPayload = when {
            trimmed.startsWith("{") -> trimmed
            else -> {
                val match = Regex("^\\s*\\d{3}\\s+(\\{.*\\})\\s*$").matchEntire(trimmed)
                match?.groupValues?.getOrNull(1)
            }
        } ?: return null

        return try {
            anthropicErrorAdapter.fromJson(jsonPayload)?.error?.message
        } catch (_: Exception) {
            null
        }
    }

    private fun buildUserFacingError(
        responseCode: Int,
        responseMessage: String,
        body: String?
    ): TranslatrApiException {
        val apiError = parseApiError(body)
        val apiMessage = apiError?.message
        val anthropicMessage = extractAnthropicErrorMessage(apiMessage)

        val combinedMessage = listOfNotNull(apiMessage, anthropicMessage)
            .joinToString(" ")
            .lowercase()

        val userMessage = when {
            combinedMessage.contains("credit balance is too low") ||
                combinedMessage.contains("insufficient credit") ||
                combinedMessage.contains("insufficient funds") ->
                "Translation failed: your Translatr credits are exhausted. Add credits in the dashboard and retry."
            responseCode == 401 || responseCode == 403 ||
                combinedMessage.contains("api key") ->
                "Translation failed: invalid API key. Check translatr.apiKey and retry."
            responseCode == 404 ->
                "Translation failed: project not found. Verify the API key and project configuration."
            responseCode == 429 || combinedMessage.contains("rate_limit") ->
                "Translation failed: rate limit exceeded. Please retry in a moment."
            responseCode >= 500 ->
                "Translation failed: server error. Please retry in a moment."
            else ->
                "Translation failed: request was rejected. Please check your configuration and retry."
        }

        val debugMessage = buildString {
            append("HTTP ")
            append(responseCode)
            append(" ")
            append(responseMessage)
            if (!body.isNullOrBlank()) {
                append(" | ")
                append(body.trim())
            }
        }

        return TranslatrApiException(userMessage, debugMessage)
    }
    
    fun translate(
        strings: Map<String, Map<String, String>>,
        onProgress: ((processedCount: Int, totalCount: Int, cachedCount: Int, translatedCount: Int) -> Unit)? = null
    ): TranslateResponse {
        // Convert format: Map<String, Map<String, String>> where inner map has "value" and "hash"
        // to backend format: Map<String, Any> (String or StringWithHash)
        val backendStrings = strings.mapValues { (_, valueMap) ->
            when {
                valueMap.containsKey("value") && valueMap.containsKey("hash") -> {
                    StringWithHash(
                        value = valueMap["value"]!!,
                        hash = valueMap["hash"]!!
                    )
                }
                valueMap.containsKey("value") -> valueMap["value"]!!
                else -> throw IllegalArgumentException("Invalid string format: missing 'value'")
            }
        }
        
        // Create job
        val jobId = createJob(backendStrings)
        
        // Poll for completion
        val result = pollJob(jobId, maxWaitSeconds = 180, onProgress = onProgress)
        
        return TranslateResponse(
            translations = result.translations ?: emptyMap(),
            meta = result.meta
        )
    }
    
    private fun createJob(strings: Map<String, Any>): String {
        val requestBody = TranslateRequest(strings)
        val json = translateRequestAdapter.toJson(requestBody)
        
        val request = Request.Builder()
            .url("$serverUrl/translate")
            .addHeader("Content-Type", "application/json")
            .addHeader("X-API-Key", apiKey)
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()
        
        var lastException: Exception? = null
        repeat(maxRetries) { attempt ->
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val errorBody = response.body?.string()
                        throw buildUserFacingError(response.code, response.message, errorBody)
                    }
                    
                    val responseBody = response.body?.string()
                        ?: throw IOException("Empty response body")
                    
                    val jobResponse = createJobResponseAdapter.fromJson(responseBody)
                        ?: throw IOException("Failed to parse job creation response")
                    
                    return jobResponse.jobId
                }
            } catch (e: IOException) {
                lastException = e
                if (attempt < maxRetries - 1) {
                    Thread.sleep(1000L * (attempt + 1))
                }
            } catch (e: TranslatrApiException) {
                throw e
            }
        }
        
        throw lastException ?: IOException("Failed to create translation job")
    }
    
    private fun pollJob(
        jobId: String,
        maxWaitSeconds: Int,
        onProgress: ((processedCount: Int, totalCount: Int, cachedCount: Int, translatedCount: Int) -> Unit)? = null
    ): JobStatusResponse {
        var lastActivityTime = System.currentTimeMillis()
        var lastUpdatedAt: String? = null
        var lastReportedProcessed = -1
        var lastReportedTotal = -1
        var delayMs = 1000L // Start with 1 second
        val maxDelayMs = 10000L // Cap at 10 seconds
        
        while (true) {
            // Timeout based on time since updated_at changed, not start time
            val timeSinceActivity = (System.currentTimeMillis() - lastActivityTime) / 1000
            if (timeSinceActivity > maxWaitSeconds) {
                throw TranslatrApiException(
                    "Translation job timed out (no progress for ${maxWaitSeconds}s)",
                    "Job ID: $jobId"
                )
            }
            
            // Poll job status
            val status = getJobStatus(jobId)
            
            // Reset timeout if updated_at changed (job is still making progress)
            if (status.updatedAt != lastUpdatedAt) {
                lastUpdatedAt = status.updatedAt
                lastActivityTime = System.currentTimeMillis()
            }
            
            // Report progress only if changed
            status.stringsCount?.let { total ->
                val processedCount = (total * status.progress) / 100
                val cachedCount = status.meta?.cached ?: 0
                val translatedCount = status.meta?.translated ?: 0
                
                if (processedCount != lastReportedProcessed || total != lastReportedTotal) {
                    onProgress?.invoke(processedCount, total, cachedCount, translatedCount)
                    lastReportedProcessed = processedCount
                    lastReportedTotal = total
                }
            }
            
            when (status.status) {
                "completed" -> {
                    if (status.translations == null) {
                        throw IOException("Job completed but translations are missing")
                    }
                    return status
                }
                "failed" -> {
                    val errorMessage = status.errorMessage ?: "Translation job failed"
                    throw TranslatrApiException(errorMessage, "Job ID: $jobId")
                }
                "pending", "processing" -> {
                    // Wait before next poll with exponential backoff
                    Thread.sleep(delayMs)
                    delayMs = minOf((delayMs * 1.5).toLong(), maxDelayMs)
                    continue
                }
                else -> {
                    throw IOException("Unknown job status: ${status.status}")
                }
            }
        }
    }
    
    private fun getJobStatus(jobId: String): JobStatusResponse {
        val url = "$serverUrl/translate/jobs/$jobId"
        
        val request = Request.Builder()
            .url(url)
            .addHeader("X-API-Key", apiKey)
            .get()
            .build()
        
        client.newCall(request).execute().use { response ->
            if (response.code == 404) {
                throw TranslatrApiException("Job not found", "Job ID: $jobId")
            }
            
            if (!response.isSuccessful) {
                val errorBody = response.body?.string()
                throw buildUserFacingError(response.code, response.message, errorBody)
            }
            
            val responseBody = response.body?.string()
                ?: throw IOException("Empty response body")
            
            val jobStatus = jobStatusResponseAdapter.fromJson(responseBody)
                ?: throw IOException("Failed to parse job status response")
            
            return jobStatus
        }
    }
    
    fun getCachedTranslations(): TranslateResponse {
        val url = "$serverUrl/translate"
        
        val request = Request.Builder()
            .url(url)
            .addHeader("X-API-Key", apiKey)
            .get()
            .build()
        
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string()
                throw buildUserFacingError(response.code, response.message, errorBody)
            }
            
            val responseBody = response.body?.string()
                ?: throw IOException("Empty response body")
            
            val translateResponse = translateResponseAdapter.fromJson(responseBody)
                ?: throw IOException("Failed to parse response")
            
            return translateResponse
        }
    }
}
