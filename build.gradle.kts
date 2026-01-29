plugins {
    kotlin("jvm") version "1.9.22"
    id("com.gradle.plugin-publish") version "2.0.0"
    `java-gradle-plugin`
    `maven-publish`
}

group = "app.translatr"
version = "1.0.3"

repositories {
    mavenCentral()
}

dependencies {
    implementation(gradleApi())
    implementation(kotlin("stdlib"))
    
    // HTTP client
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    
    // JSON parsing
    implementation("com.squareup.moshi:moshi:1.15.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.0")
    
    // XML parsing
    implementation("org.dom4j:dom4j:2.1.4")
    
    // Testing
    testImplementation(kotlin("test"))
    testImplementation("io.mockk:mockk:1.13.8")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

gradlePlugin {
    website = "https://translatr.app"
    vcsUrl = "https://github.com/bandapella/translatr-gradle-plugin"

    plugins {
        create("translatr") {
            id = "app.translatr"
            implementationClass = "app.translatr.TranslatrPlugin"
            displayName = "Translatr Plugin"
            description = "AI-powered translation plugin for Android/KMP projects"
            tags = listOf("i18n", "localization", "translation", "android", "kotlin", "kotlin-multiplatform")
        }
    }
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}
