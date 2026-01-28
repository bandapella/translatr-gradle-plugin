# Translatr Gradle Plugin

AI-powered translation plugin for Android and Kotlin Multiplatform projects.

## Installation

### Option 1: Local Development

Build and publish to Maven Local:

```bash
./gradlew publishToMavenLocal
```

Then in your project's `settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
```

And in your module's `build.gradle.kts`:

```kotlin
plugins {
    id("app.translatr") version "1.0.0"
}
```

### Option 2: Via Gradle Plugin Portal

```kotlin
plugins {
    id("app.translatr") version "1.0.0"
}
```

## Configuration

Add to your `build.gradle.kts`:

```kotlin
translatr {
    platform = Platform.KMP  // Required: Platform.KMP or Platform.ANDROID
    apiKey = providers.gradleProperty("translatrApiKey")
    serverUrl = "https://translatr.app/api"  // Optional, default shown
    triggerMode = TriggerMode.MANUAL  // MANUAL, AUTO, or BOTH
    
    // Optional: Override platform defaults
    // KMP defaults: src/commonMain/composeResources/values/strings.xml
    // Android defaults: src/main/res/values/strings.xml
    sourceFile.set(layout.projectDirectory.file("composeApp/src/commonMain/composeResources/values/strings.xml"))
    outputDir.set(layout.projectDirectory.dir("composeApp/src/commonMain/composeResources"))
    
    // Optional retry and timeout settings
    timeoutSeconds = 60  // Default: 60 seconds (HTTP request timeout)
    maxRetries = 3       // Default: 3 attempts
    failOnError = false  // Default: false (won't fail build on API errors)
}
```

Add your API key to `gradle.properties`:

```properties
translatrApiKey=your-api-key-here
```

## Trigger Modes

### MANUAL (Default)
Run translations manually:
```bash
./gradlew translatr
```

### AUTO
Automatically translates on every build (hooks into `generateComposeResClass`):
```kotlin
translatr {
    triggerMode = TriggerMode.AUTO
}
```

### BOTH
Fetches translations automatically on builds, or run manually for full control:
```kotlin
translatr {
    triggerMode = TriggerMode.BOTH
}
```

Then use:
- `./gradlew translatr` - Full translation with AI (manual, for new strings)

## Configuration Options

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `platform` | Platform | (required) | Platform type: `Platform.KMP` or `Platform.ANDROID` |
| `apiKey` | String | (required) | Your Translatr API key |
| `serverUrl` | String | `https://translatr.app/api` | API server URL |
| `triggerMode` | TriggerMode | `MANUAL` | When to run translations (MANUAL or AUTO) |
| `sourceFile` | String | Platform-specific* | Path to source strings.xml file |
| `outputDir` | String | Platform-specific* | Directory for translated output files |
| `timeoutSeconds` | Int | `60` | HTTP request timeout in seconds (connect, read, write operations) |
| `maxRetries` | Int | `3` | Maximum retry attempts on network failures |
| `failOnError` | Boolean | `false` | Whether to fail the build on API errors |

*Platform-specific defaults:
- **KMP**: `sourceFile` = `src/commonMain/composeResources/values/strings.xml`, `outputDir` = `src/commonMain/composeResources`
- **Android**: `sourceFile` = `src/main/res/values/strings.xml`, `outputDir` = `src/main/res`

### Resilience Configuration

By default, the plugin will **not fail your build** if it cannot connect to the API. This ensures your builds continue even if the translation service is temporarily unavailable.

The plugin has two types of timeouts:
- **HTTP Request Timeout** (`timeoutSeconds`): Maximum time for individual HTTP requests (connect, read, write). Default: 60 seconds
- **Client Polling Timeout**: Maximum time the client waits for job progress updates before giving up. Default: 10 minutes (client-side only, not configurable)

```kotlin
translatr {
    // Increase timeout for slower connections or large translation jobs
    timeoutSeconds = 90  // HTTP request timeout
    
    // Retry more aggressively
    maxRetries = 5
    
    // Fail the build if translations cannot be fetched (not recommended)
    failOnError = true
}
```

The plugin automatically implements exponential backoff when retrying failed requests.

## Tasks

### `translatr`
Translate all strings using AI and update output files. Cached translations are retrieved automatically and do not consume credits.

```bash
./gradlew translatr
```

## Source File Format

Standard Android `strings.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">My App</string>
    <string name="welcome_message">Welcome to our app!</string>
    <string name="back_button">Back</string>
</resources>
```

## Output

Generates translated files:

```
composeResources/
├── values/
│   └── strings.xml (source)
├── values-es/
│   └── strings.xml (Spanish)
├── values-zh/
│   └── strings.xml (Chinese)
└── values-fr/
    └── strings.xml (French)
```



## Example Project Setup

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

// build.gradle.kts (module level)
plugins {
    id("app.translatr") version "1.0.0"
}

translatr {
    platform = Platform.KMP
    apiKey = providers.gradleProperty("translatrApiKey")
    sourceFile = "composeApp/src/commonMain/composeResources/values/strings.xml"
    outputDir = "composeApp/src/commonMain/composeResources"
}

// gradle.properties
translatrApiKey=tsk_xxx...
```

## Development

Build the plugin:

```bash
./gradlew build
```

Run tests:

```bash
./gradlew test
```

Publish to Maven Local:

```bash
./gradlew publishToMavenLocal
```

## License

Proprietary - See LICENSE file for details
