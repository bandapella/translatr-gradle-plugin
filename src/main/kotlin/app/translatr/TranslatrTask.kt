package app.translatr

import com.squareup.moshi.Json
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.security.MessageDigest

@JsonClass(generateAdapter = true)
data class TranslatrCache(
    @Json(name = "source_hash") val sourceHash: String,
    @Json(name = "timestamp") val timestamp: Long,
    @Json(name = "languages") val languages: Set<String>,
    @Json(name = "string_hashes") val stringHashes: Map<String, String> = emptyMap(),
    @Json(name = "failed") val failed: Boolean = false
)

abstract class TranslatrTask : DefaultTask() {
    @get:Input
    abstract val apiKey: Property<String>
    
    @get:Input
    abstract val serverUrl: Property<String>
    
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceFile: RegularFileProperty
    
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty
    
    @get:Input
    abstract val timeoutSeconds: Property<Int>
    
    @get:Input
    abstract val maxRetries: Property<Int>
    
    @get:Input
    abstract val failOnError: Property<Boolean>

    @get:Input
    abstract val dryRun: Property<Boolean>
    
    @get:Internal
    abstract val buildDirectory: DirectoryProperty
    
    // Cache state as input to invalidate Gradle's UP-TO-DATE check when translation fails
    @Input
    fun getCacheState(): String {
        val cacheFile = getCacheFile()
        if (!cacheFile.exists()) return "no-cache"
        
        val cache = readCache(cacheFile)
        return when {
            cache == null -> "no-cache"
            cache.failed -> "failed-${cache.timestamp}"
            else -> "success-${cache.sourceHash}"
        }
    }
    
    private fun createCacheAdapter(): JsonAdapter<TranslatrCache> {
        val moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
        return moshi.adapter(TranslatrCache::class.java)
    }
    
    private fun computeFileHash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = file.readBytes()
        val hash = digest.digest(bytes)
        return hash.joinToString("") { "%02x".format(it) }
    }
    
    private fun getCacheFile(): File {
        val buildDir = buildDirectory.asFile.get()
        val cacheDir = File(buildDir, "translatr")
        cacheDir.mkdirs()
        return File(cacheDir, "cache.json")
    }
    
    private fun readCache(cacheFile: File): TranslatrCache? {
        if (!cacheFile.exists()) return null
        return try {
            createCacheAdapter().fromJson(cacheFile.readText())
        } catch (e: Exception) {
            logger.debug("Translatr: Failed to read cache file: ${e.message}")
            null
        }
    }
    
    private fun writeCache(cacheFile: File, cache: TranslatrCache) {
        cacheFile.writeText(createCacheAdapter().toJson(cache))
    }
    
    private fun computeStringHash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(value.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }
    
    @TaskAction
    fun translate() {
        // Check for version updates (non-blocking)
        try {
            val checker = VersionChecker(serverUrl.get())
            val updateMessage = checker.checkForUpdates()
            if (updateMessage != null) {
                logger.lifecycle(updateMessage)
            }
        } catch (e: Exception) {
            // Silently ignore version check failures
        }
        
        // Validate inputs
        val apiKeyValue = apiKey.orNull
            ?: throw IllegalStateException("translatr.apiKey is required")
        
        val sourceFileObj = sourceFile.orNull?.asFile
            ?: throw IllegalStateException("translatr.sourceFile is required")
        
        val outputDirObj = outputDir.orNull?.asFile
            ?: throw IllegalStateException("translatr.outputDir is required")
        
        val serverUrlValue = serverUrl.get()
        
        logger.lifecycle("Translatr: Parsing source strings from ${sourceFileObj.path}")
        
        // Parse source strings
        val strings = XmlParser.parseStringsXml(sourceFileObj)
        
        if (strings.isEmpty()) {
            logger.warn("No strings found in source file")
            return
        }
        
        // Prepare output directory
        outputDirObj.mkdirs()
        
        val cacheFile = getCacheFile()
        val currentHash = computeFileHash(sourceFileObj)
        
        // Compute hashes for each string
        val currentStringHashes = strings.mapValues { (_, value) ->
            computeStringHash(value)
        }
        
        // Read cache to detect what changed
        val cache = readCache(cacheFile)
        val previousStringHashes = if (cache?.failed == true) emptyMap() else cache?.stringHashes ?: emptyMap()
        
        // Determine which strings need translation
        val stringsToTranslate = mutableMapOf<String, Map<String, String>>()
        val newKeys = mutableListOf<String>()
        val modifiedKeys = mutableListOf<String>()
        val removedKeys = if (cache?.failed == true) emptySet() else previousStringHashes.keys - currentStringHashes.keys
        
        for ((key, value) in strings) {
            val currentHashValue = currentStringHashes[key]!!
            val previousHash = previousStringHashes[key]
            
            when {
                cache?.failed == true -> {
                    // If last run failed, treat everything as needing translation
                    // to ensure we retry everything. The server will handle actual caching.
                    stringsToTranslate[key] = mapOf("value" to value, "hash" to currentHashValue)
                }
                previousHash == null -> {
                    // New string
                    newKeys.add(key)
                    stringsToTranslate[key] = mapOf("value" to value, "hash" to currentHashValue)
                }
                previousHash != currentHashValue -> {
                    // Modified string
                    modifiedKeys.add(key)
                    stringsToTranslate[key] = mapOf("value" to value, "hash" to currentHashValue)
                }
                // else: unchanged, skip
            }
        }
        
        // Log what changed
        if (newKeys.isNotEmpty()) {
            logger.lifecycle("Translatr: Found ${newKeys.size} new string(s)")
        }
        if (modifiedKeys.isNotEmpty()) {
            logger.lifecycle("Translatr: Found ${modifiedKeys.size} modified string(s)")
        }
        if (removedKeys.isNotEmpty()) {
            logger.lifecycle("Translatr: Found ${removedKeys.size} removed string(s)")
        }
        
        if (dryRun.get()) {
            logger.lifecycle("Translatr: Dry run - no changes will be made")
            logger.lifecycle("  Total strings: ${strings.size}")
            logger.lifecycle("  New strings: ${newKeys.size}")
            logger.lifecycle("  Modified strings: ${modifiedKeys.size}")
            logger.lifecycle("  Removed strings: ${removedKeys.size}")
            logger.lifecycle("  Strings needing translation: ${stringsToTranslate.size}")
            
            val languages = cache?.languages ?: emptySet()
            if (languages.isEmpty()) {
                logger.lifecycle("  Target languages: None configured or cached.")
            } else {
                logger.lifecycle("  Target languages: ${languages.joinToString(", ")}")
            }
            return
        }
        
        // If nothing changed and outputs exist, we're done
        if (stringsToTranslate.isEmpty() && removedKeys.isEmpty() && cache?.failed != true) {
            logger.lifecycle("Translatr: No changes detected, checking output files...")
            
            // Fetch current languages from server to detect new languages
            val client = TranslatrClient(
                serverUrl.get(), 
                apiKey.get(),
                timeoutSeconds.get(),
                maxRetries.get()
            )
            
            try {
                // Get all translations to detect actual target languages
                val response = client.getCachedTranslations()
                val serverLanguages = response.translations.values
                    .flatMap { it.keys }
                    .toSet()
                
                if (serverLanguages.isNotEmpty()) {
                    val allOutputsExist = serverLanguages.all { lang ->
                        File(outputDirObj, "values-$lang/strings.xml").exists()
                    }
                    if (allOutputsExist) {
                        // Update cache with current languages
                        writeCache(cacheFile, TranslatrCache(currentHash, System.currentTimeMillis(), serverLanguages, currentStringHashes, failed = false))
                        logger.lifecycle("Translatr: All outputs up-to-date (UP-TO-DATE)")
                        return
                    }
                    logger.lifecycle("Translatr: New languages detected or missing outputs, regenerating...")
                }
            } catch (e: Exception) {
                logger.debug("Translatr: Could not check server languages: ${e.message}")
            }
            // If outputs don't exist or error, fall through to regenerate from cache
        }
        
        // Create client
        val client = TranslatrClient(
            serverUrlValue, 
            apiKeyValue,
            timeoutSeconds.get(),
            maxRetries.get()
        )
        
        // Get translations (either from new/modified strings or from cache)
        var allTranslations: Map<String, Map<String, String>>
        var languages: Set<String>
        var translationFailed = false  // Track if we're using fallback due to failure
        var finalWarning: String? = null
        var finalTokensUsed: Int? = null
        var finalTokensRemaining: Int? = null
        
        if (stringsToTranslate.isNotEmpty()) {
            logger.lifecycle("Translatr: Requesting translations for ${stringsToTranslate.size} string(s)...")
            logger.lifecycle("Translatr: Large translations may take 5+ minutes. Progress will be shown below.")
            
            try {
                val response = client.translate(stringsToTranslate) { processedCount, totalCount, cachedCount, translatedCount ->
                    if (cachedCount > 0 && translatedCount == 0 && processedCount == cachedCount) {
                        logger.lifecycle("Translatr: $cachedCount/$totalCount strings served from cache immediately")
                        logger.lifecycle("Translatr: Translating remaining ${totalCount - cachedCount} strings...")
                    } else if (cachedCount > 0) {
                        logger.lifecycle("Translatr: Processing... $processedCount/$totalCount strings ($cachedCount cached, $translatedCount translated)")
                    } else {
                        logger.lifecycle("Translatr: Processing... $processedCount/$totalCount strings")
                    }
                }
                val newTranslations = response.translations
                
                // Capture warning/token usage for end-of-run summary (so it doesn't scroll off)
                finalWarning = response.warning
                if (finalWarning != null) {
                    // Partial completion - ensure we retry next run once tokens are replenished
                    translationFailed = true
                }
                val tokensUsed = response.tokensUsed ?: 0
                if (tokensUsed > 0) {
                    finalTokensUsed = tokensUsed
                    finalTokensRemaining = response.tokensRemaining ?: 0
                }
                
                // Get cached translations for unchanged strings
                logger.lifecycle("Translatr: Fetching cached translations for unchanged strings...")
                val cachedResponse = client.getCachedTranslations()
                
                // If new translations are empty but there's a warning (partial completion),
                // fall back to using only cached translations
                if (newTranslations.isEmpty()) {
                    if (response.warning != null && cachedResponse.translations.isNotEmpty()) {
                        logger.lifecycle("Translatr: Using cached translations as fallback")
                        // Mark cache as failed so we retry once tokens are replenished
                        translationFailed = true
                        allTranslations = cachedResponse.translations
                        languages = cachedResponse.translations.values
                            .flatMap { it.keys }
                            .toSet()
                    } else {
                        logger.warn("No translations returned")
                        logger.warn("Please configure target languages in the UI at ${serverUrlValue.replace("/api", "")}")
                        return
                    }
                } else {
                    // Extract target languages from both new and cached translations
                    // to preserve all configured languages even if translate response is partial
                    languages = (newTranslations.values + cachedResponse.translations.values)
                        .flatMap { it.keys }
                        .toSet()
                    
                    if (languages.isEmpty()) {
                        logger.error("Translation failed: No translations were returned from the server")
                        logger.error("This may indicate an issue with your account or the translation service")
                        return
                    }
                    
                    logger.lifecycle("Translatr: Received translations for languages: ${languages.joinToString(", ")}")
                    
                    // Log cache statistics if available
                    response.meta?.let { meta ->
                        val actuallyTranslated = meta.translated ?: 0
                        if (actuallyTranslated > 0) {
                            logger.lifecycle("Translatr: Translated ${actuallyTranslated} string(s) (${meta.cached ?: 0} from cache)")
                        } else {
                            logger.lifecycle("Translatr: All ${meta.cached ?: 0} string(s) retrieved from cache (no AI translation needed)")
                        }
                    }
                    
                    // Merge new and cached translations
                    allTranslations = cachedResponse.translations + newTranslations
                }
                
            } catch (e: Exception) {
                if (e is TranslatrApiException) {
                    logger.error("Translatr: ${e.userMessage}")
                    e.debugMessage?.let { logger.debug("Translatr: Debug details: $it") }
                    if (failOnError.get()) {
                        throw GradleException(e.userMessage)
                    }
                } else {
                    logger.error("Translatr: Translation failed: ${e.message}", e)
                    if (failOnError.get()) {
                        throw e
                    }
                }
                
                // Try to fall back to cached translations
                logger.warn("Translatr: Attempting to use cached translations as fallback...")
                translationFailed = true
                try {
                    val cachedResponse = client.getCachedTranslations()
                    if (cachedResponse.translations.isNotEmpty()) {
                        allTranslations = cachedResponse.translations
                        languages = cachedResponse.translations.values
                            .flatMap { it.keys }
                            .toSet()
                        logger.lifecycle("Translatr: Using ${allTranslations.size} cached translation(s) for ${languages.size} language(s)")
                    } else {
                        logger.warn("Translatr: No cached translations available")
                        logger.warn("Translatr: Continuing build despite translation failure (failOnError=false)")
                        // Write failed cache to prevent Gradle UP-TO-DATE on next run
                        writeCache(cacheFile, TranslatrCache(currentHash, System.currentTimeMillis(), emptySet(), currentStringHashes, failed = true))
                        return
                    }
                } catch (cacheError: Exception) {
                    logger.error("Translatr: Failed to fetch cached translations: ${cacheError.message}")
                    logger.warn("Translatr: Continuing build despite translation failure (failOnError=false)")
                    // Write failed cache to prevent Gradle UP-TO-DATE on next run
                    writeCache(cacheFile, TranslatrCache(currentHash, System.currentTimeMillis(), emptySet(), currentStringHashes, failed = true))
                    return
                }
            }
        } else {
            // Only removals or regenerating outputs, get all from cache
            logger.lifecycle("Translatr: Fetching all cached translations...")
            
            try {
                val response = client.getCachedTranslations()
                allTranslations = response.translations
                
                if (allTranslations.isEmpty()) {
                    logger.warn("No translations in cache")
                    return
                }
                
                languages = allTranslations.values
                    .flatMap { it.keys }
                    .toSet()
                
            } catch (e: Exception) {
                logger.error("Translatr: Failed to fetch cached translations: ${e.message}", e)
                if (failOnError.get()) {
                    throw e
                }
                return
            }
        }
        
        // Filter out removed strings from translations
        val filteredTranslations = allTranslations.filterKeys { key ->
            key in currentStringHashes
        }
        
        val orderedKeys = strings.keys.toList()
        // Write output files
        languages.forEach { lang ->
            val langTranslations = filteredTranslations
                .filter { it.value.containsKey(lang) }
                .mapValues { it.value[lang]!! }
            val existingFile = File(outputDirObj, "values-$lang/strings.xml")
            val existingTranslations = if (existingFile.exists()) {
                XmlParser.parseStringsXml(existingFile)
            } else {
                emptyMap()
            }
            val mergedTranslations = existingTranslations + langTranslations
            val filteredMergedTranslations = orderedKeys
                .mapNotNull { key -> mergedTranslations[key]?.let { key to it } }
                .toMap()

            val existingOrder = XmlParser.readStringKeyOrder(existingFile)
            val mergedOrder = XmlParser.mergeKeyOrder(existingOrder, orderedKeys)

            XmlParser.writeStringsXml(outputDirObj, lang, filteredMergedTranslations, mergedOrder)
        }
        
        languages.forEach { lang ->
            logger.lifecycle("Translatr: ✓ Generated values-$lang/strings.xml")
        }
        
        // Update cache file only if translation succeeded
        // If we fell back to cached translations due to failure, don't update cache
        // to ensure new strings are retried on next run
        if (!translationFailed) {
            writeCache(cacheFile, TranslatrCache(currentHash, System.currentTimeMillis(), languages, currentStringHashes, failed = false))
        } else {
            // Mark cache as failed so Gradle knows to re-run even if inputs haven't changed
            writeCache(cacheFile, TranslatrCache(currentHash, System.currentTimeMillis(), languages, currentStringHashes, failed = true))
            logger.lifecycle("Translatr: Cache marked as failed - task will retry on next run")
        }
        
        // End-of-run summary (print after file output so it's not obscured)
        if (finalWarning != null) {
            logger.lifecycle("Translatr: Translation partially completed — see warning below.")
        } else {
            logger.lifecycle("Translatr: Translation complete!")
        }
        finalTokensUsed
            ?.takeIf { it > 0 }
            ?.let { tokensUsed ->
                val tokensRemaining = finalTokensRemaining ?: 0
                logger.lifecycle("Translatr: Used $tokensUsed tokens, $tokensRemaining tokens remaining")
            }
        finalWarning?.let { warning ->
            logger.warn("⚠️  Translatr: $warning")
        }
    }
}
