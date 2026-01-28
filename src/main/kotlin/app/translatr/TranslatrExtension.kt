package app.translatr

import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import javax.inject.Inject

enum class TriggerMode {
    MANUAL,  // Only via ./gradlew translatr
    AUTO     // Hooks into JetBrains resources plugin
}

abstract class TranslatrExtension @Inject constructor(
    private val project: Project
) {
    /**
     * Platform type (required: KMP or ANDROID)
     */
    abstract val platform: Property<Platform>
    
    /**
     * API key for translatr.app authentication
     */
    abstract val apiKey: Property<String>
    
    /**
     * Server URL (default: https://translatr.app/api)
     */
    abstract val serverUrl: Property<String>
    
    /**
     * Trigger mode for translations
     */
    abstract val triggerMode: Property<TriggerMode>
    
    /**
     * Source strings.xml file path
     * Default for KMP: src/commonMain/composeResources/values/strings.xml
     * Default for Android: src/main/res/values/strings.xml
     */
    abstract val sourceFile: RegularFileProperty
    
    /**
     * Output directory for translated files
     * Default for KMP: src/commonMain/composeResources
     * Default for Android: src/main/res
     */
    abstract val outputDir: DirectoryProperty
    
    /**
     * HTTP request timeout in seconds for all HTTP operations (connect, read, write)
     * Default: 60 seconds
     */
    abstract val timeoutSeconds: Property<Int>
    
    /**
     * Maximum number of retry attempts (default: 3)
     */
    abstract val maxRetries: Property<Int>
    
    /**
     * Whether to fail the build on API errors (default: false)
     */
    abstract val failOnError: Property<Boolean>

    /**
     * Whether to run in dry-run mode (default: false)
     */
    abstract val dryRun: Property<Boolean>
    
    init {
        serverUrl.convention("https://translatr.app/api")
        triggerMode.convention(TriggerMode.MANUAL)
        timeoutSeconds.convention(60)
        maxRetries.convention(3)
        failOnError.convention(false)
        dryRun.convention(false)
        
        // Infer platform from applied plugins if not explicitly set
        platform.convention(
            project.provider {
                when {
                    project.pluginManager.hasPlugin("org.jetbrains.kotlin.multiplatform") -> Platform.KMP
                    project.pluginManager.hasPlugin("com.android.application") ||
                    project.pluginManager.hasPlugin("com.android.library") -> Platform.ANDROID
                    else -> null
                }
            }
        )
        
        // Set platform-specific defaults based on configured platform
        platform.finalizeValueOnRead()
        sourceFile.convention(
            platform.flatMap { p ->
                project.provider {
                    when (p) {
                        Platform.KMP -> project.layout.projectDirectory.file(
                            "src/commonMain/composeResources/values/strings.xml"
                        )
                        Platform.ANDROID -> project.layout.projectDirectory.file(
                            "src/main/res/values/strings.xml"
                        )
                        null -> throw IllegalStateException("Platform must be set")
                    }
                }
            }
        )
        outputDir.convention(
            platform.flatMap { p ->
                project.provider {
                    when (p) {
                        Platform.KMP -> project.layout.projectDirectory.dir(
                            "src/commonMain/composeResources"
                        )
                        Platform.ANDROID -> project.layout.projectDirectory.dir(
                            "src/main/res"
                        )
                        null -> throw IllegalStateException("Platform must be set")
                    }
                }
            }
        )
    }
    
    fun apiKey(provider: Provider<String>) {
        apiKey.set(provider)
    }
}
