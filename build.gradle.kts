import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
}

group = "com.ericjesse.videotranslator"
version = "1.0.0"

kotlin {
    jvmToolchain(21)
}

dependencies {
    // Compose Multiplatform
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    
    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    
    // HTTP Client
    implementation("io.ktor:ktor-client-core:3.0.1")
    implementation("io.ktor:ktor-client-cio:3.0.1")
    implementation("io.ktor:ktor-client-content-negotiation:3.0.1")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.1")
    implementation("io.ktor:ktor-client-logging:3.0.1")
    
    // Logging
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.0")
    implementation("ch.qos.logback:logback-classic:1.5.12")
    implementation("org.codehaus.janino:janino:3.1.12") // Required for logback conditional processing

    // Archive extraction (TAR.XZ, 7z support - ZIP uses JDK built-in)
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.10") // Required for XZ compression support
    
    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("io.mockk:mockk:1.13.13")
    testImplementation("io.ktor:ktor-client-mock:3.0.1")
}

compose.desktop {
    application {
        mainClass = "com.ericjesse.videotranslator.MainKt"
        
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            
            packageName = "VideoTranslator"
            packageVersion = version.toString()
            description = "Translate YouTube videos into any language"
            copyright = "© 2024 Eric Jesse. Apache License 2.0"
            vendor = "Eric Jesse"
            
            linux {
                iconFile.set(project.file("src/main/resources/icons/icon.png"))
                debMaintainer = "eric@example.com"
                menuGroup = "Video"
                appCategory = "Video"
            }
            
            windows {
                iconFile.set(project.file("src/main/resources/icons/icon.ico"))
                menuGroup = "Video Translator"
                upgradeUuid = "8f7e6d5c-4b3a-2a1b-9c8d-7e6f5a4b3c2d"
                dirChooser = true
                perUserInstall = true
            }
            
            macOS {
                iconFile.set(project.file("src/main/resources/icons/icon.icns"))
                bundleID = "com.ericjesse.videotranslator"
                appCategory = "public.app-category.video"
                dockName = "Video Translator"
            }
        }
        
        buildTypes.release {
            proguard {
                isEnabled.set(false)
            }
        }
    }
}

tasks.test {
    useJUnitPlatform()
}
