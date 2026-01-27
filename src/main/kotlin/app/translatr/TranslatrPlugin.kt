package app.translatr

import org.gradle.api.Plugin
import org.gradle.api.Project

class TranslatrPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        // Register extension
        val extension = project.extensions.create(
            "translatr",
            TranslatrExtension::class.java,
            project
        )
        
        // Register manual translation task
        project.tasks.register("translatr", TranslatrTask::class.java) { task ->
            task.group = "translatr"
            task.description = "Translate strings using translatr.app"
            
            task.apiKey.set(extension.apiKey)
            task.serverUrl.set(extension.serverUrl)
            task.sourceFile.set(extension.sourceFile)
            task.outputDir.set(extension.outputDir)
            task.timeoutSeconds.set(extension.timeoutSeconds)
            task.maxRetries.set(extension.maxRetries)
            task.failOnError.set(extension.failOnError)
            task.dryRun.set(extension.dryRun)
            task.buildDirectory.set(project.layout.buildDirectory)
        }
        
        // Hook into build lifecycle based on trigger mode
        project.afterEvaluate {
            when (extension.triggerMode.orNull) {
                TriggerMode.AUTO -> {
                    hookAutoMode(project, extension)
                }
                else -> {
                    // MANUAL - no hooks needed
                }
            }
        }
    }
    
    private fun hookAutoMode(project: Project, extension: TranslatrExtension) {
        val platform = extension.platform.orNull ?: inferPlatform(project) ?: return
        
        when (platform) {
            Platform.KMP -> {
                project.tasks.findByName("generateComposeResClass")?.let { resourceTask ->
                    val translatrTask = project.tasks.named("translatr")
                    resourceTask.dependsOn(translatrTask)
                }
            }
            Platform.ANDROID -> {
                // Hook all merge*Resources tasks to support all variants (flavors + build types)
                project.tasks.matching { it.name.matches(Regex("merge.*Resources")) }.configureEach { resourceTask ->
                    val translatrTask = project.tasks.named("translatr")
                    resourceTask.dependsOn(translatrTask)
                }
            }
        }
    }
    
    private fun inferPlatform(project: Project): Platform? {
        return when {
            project.pluginManager.hasPlugin("org.jetbrains.kotlin.multiplatform") -> Platform.KMP
            project.pluginManager.hasPlugin("com.android.application") ||
            project.pluginManager.hasPlugin("com.android.library") -> Platform.ANDROID
            else -> null
        }
    }
}

enum class Platform {
    KMP,
    ANDROID
}
