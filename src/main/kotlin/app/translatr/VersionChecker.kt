package app.translatr

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

@JsonClass(generateAdapter = true)
data class VersionsResponse(
    @Json(name = "gradle") val gradle: VersionInfo?,
    @Json(name = "swift") val swift: VersionInfo?
)

@JsonClass(generateAdapter = true)
data class VersionInfo(
    @Json(name = "version") val version: String,
    @Json(name = "released_at") val releasedAt: String
)

class VersionChecker(private val serverUrl: String) {
    companion object {
        private const val CURRENT_VERSION = "1.0.2"
        private const val TIMEOUT_SECONDS = 5L
    }
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
    
    private val moshi = Moshi.Builder().build()
    private val adapter = moshi.adapter(VersionsResponse::class.java)
    
    fun checkForUpdates(): String? {
        try {
            val url = "$serverUrl/versions"
            val request = Request.Builder()
                .url(url)
                .get()
                .build()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return null
                }
                
                val body = response.body?.string() ?: return null
                val versionsResponse = adapter.fromJson(body) ?: return null
                val latestVersion = versionsResponse.gradle?.version ?: return null
                
                if (isNewerVersion(latestVersion, CURRENT_VERSION)) {
                    return "ℹ New version $latestVersion available"
                }
                
                return null
            }
        } catch (e: Exception) {
            // Fail silently - never block user's workflow
            return null
        }
    }
    
    private fun isNewerVersion(latest: String, current: String): Boolean {
        try {
            val latestParts = latest.split(".").map { it.toInt() }
            val currentParts = current.split(".").map { it.toInt() }
            
            for (i in 0 until maxOf(latestParts.size, currentParts.size)) {
                val latestPart = latestParts.getOrNull(i) ?: 0
                val currentPart = currentParts.getOrNull(i) ?: 0
                
                when {
                    latestPart > currentPart -> return true
                    latestPart < currentPart -> return false
                }
            }
            
            return false
        } catch (e: Exception) {
            return false
        }
    }
}
