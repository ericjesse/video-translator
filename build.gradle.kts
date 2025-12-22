import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.io.ByteArrayOutputStream
import java.time.Instant

plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
}

group = "com.ericjesse.videotranslator"

// =============================================================================
// Version Management - Inject from git tags or use default
// =============================================================================

val gitVersion: String by lazy {
    try {
        val stdout = ByteArrayOutputStream()
        exec {
            commandLine("git", "describe", "--tags", "--always", "--dirty")
            standardOutput = stdout
            isIgnoreExitValue = true
        }
        val described = stdout.toString().trim()

        // Parse version from tag (e.g., "v1.2.3" -> "1.2.3", "v1.2.3-5-gabc123" -> "1.2.3.5")
        when {
            described.startsWith("v") -> {
                val parts = described.removePrefix("v").split("-")
                if (parts.size >= 2 && parts[1].all { it.isDigit() }) {
                    // Has commits since tag: v1.2.3-5-gabc123 -> 1.2.3.5
                    "${parts[0]}.${parts[1]}"
                } else {
                    // Exactly on tag: v1.2.3 -> 1.2.3
                    parts[0]
                }
            }
            described.isNotEmpty() -> "1.0.0-$described"
            else -> "1.0.0-SNAPSHOT"
        }
    } catch (e: Exception) {
        "1.0.0-SNAPSHOT"
    }
}

version = System.getenv("VERSION") ?: gitVersion

val appVersion = version.toString()
val appVersionCode = appVersion.split(".").take(3).mapIndexed { i, s ->
    (s.filter { it.isDigit() }.toIntOrNull() ?: 0) * when(i) { 0 -> 10000; 1 -> 100; else -> 1 }
}.sum()

println("Building Video Translator version: $appVersion (code: $appVersionCode)")

// =============================================================================
// Build Info Generation
// =============================================================================

val generateBuildInfo by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/resources/main")
    outputs.dir(outputDir)

    doLast {
        val buildInfoFile = outputDir.get().file("build-info.properties").asFile
        buildInfoFile.parentFile.mkdirs()
        buildInfoFile.writeText("""
            |app.version=$appVersion
            |app.version.code=$appVersionCode
            |app.build.time=${Instant.now()}
            |app.build.jdk=${System.getProperty("java.version")}
            |app.update.url=https://api.github.com/repos/ericjesse/video-translator/releases/latest
            |app.releases.url=https://github.com/ericjesse/video-translator/releases
        """.trimMargin())
    }
}

sourceSets {
    main {
        resources {
            srcDir(layout.buildDirectory.dir("generated/resources/main"))
        }
    }
}

tasks.named("processResources") {
    dependsOn(generateBuildInfo)
}

// =============================================================================
// JVM Configuration
// =============================================================================

kotlin {
    jvmToolchain(21)
}

// =============================================================================
// Dependencies
// =============================================================================

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
    implementation("org.codehaus.janino:janino:3.1.12")

    // Archive extraction (TAR.XZ, 7z support - ZIP uses JDK built-in)
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.10")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("io.mockk:mockk:1.13.13")
    testImplementation("io.ktor:ktor-client-mock:3.0.1")

    // Compose UI Testing
    @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
    testImplementation(compose.uiTest)
}

// =============================================================================
// Desktop Application Configuration
// =============================================================================

compose.desktop {
    application {
        mainClass = "com.ericjesse.videotranslator.MainKt"

        // JVM arguments for the application
        jvmArgs += listOf(
            "-Xmx2g",
            "-Dfile.encoding=UTF-8",
            "-Dapp.version=$appVersion"
        )

        // Arguments passed to the application
        args += listOf()

        nativeDistributions {
            // Target formats per platform
            // Note: Each format is only built on its native platform
            targetFormats(
                TargetFormat.Dmg,      // macOS
                TargetFormat.Msi,      // Windows
                TargetFormat.Deb       // Linux
            )

            // Basic package information
            packageName = "VideoTranslator"
            packageVersion = appVersion.split("-").first().let {
                // Ensure version is in X.Y.Z format for native packages
                val parts = it.split(".")
                when {
                    parts.size >= 3 -> "${parts[0]}.${parts[1]}.${parts[2]}"
                    parts.size == 2 -> "${parts[0]}.${parts[1]}.0"
                    else -> "${parts[0]}.0.0"
                }
            }
            description = "Translate YouTube videos into any language with AI-powered transcription and translation"
            copyright = "© 2024 Eric Jesse. Apache License 2.0"
            vendor = "Eric Jesse"
            licenseFile.set(project.file("LICENSE"))

            // Include additional files in the distribution
            appResourcesRootDir.set(project.layout.projectDirectory.dir("src/main/distribution"))

            // Module configuration for Java 21+
            modules(
                "java.base",
                "java.desktop",
                "java.logging",
                "java.naming",
                "java.net.http",
                "java.security.jgss",
                "java.sql",
                "jdk.unsupported",
                "jdk.crypto.ec"
            )

            // =================================================================
            // Linux Configuration
            // =================================================================
            linux {
                iconFile.set(project.file("src/main/resources/icons/icon.png"))

                // Package metadata
                packageName = "videotranslator"
                debMaintainer = "eric@example.com"
                menuGroup = "AudioVideo"
                appCategory = "AudioVideo"
                appRelease = "1"

                // Desktop entry
                shortcut = true

                // RPM specific
                rpmLicenseType = "Apache-2.0"

                // Installation directories
                installationPath = "/opt/videotranslator"
            }

            // =================================================================
            // Windows Configuration
            // =================================================================
            windows {
                iconFile.set(project.file("src/main/resources/icons/icon.ico"))

                // Installer configuration
                packageName = "VideoTranslator"
                menuGroup = "Video Translator"
                upgradeUuid = "8f7e6d5c-4b3a-2a1b-9c8d-7e6f5a4b3c2d"

                // Installation options
                dirChooser = true
                perUserInstall = false  // Install to Program Files for all users
                shortcut = true
                menu = true

                // Console settings (hide console window)
                console = false

                // File associations
                fileAssociation(
                    extension = "srt",
                    description = "SubRip Subtitle File",
                    mimeType = "application/x-subrip"
                )
                fileAssociation(
                    extension = "vtt",
                    description = "WebVTT Subtitle File",
                    mimeType = "text/vtt"
                )

                // MSI specific options
                // msiPackageVersion is set from packageVersion
            }

            // =================================================================
            // macOS Configuration
            // =================================================================
            macOS {
                iconFile.set(project.file("src/main/resources/icons/icon.icns"))

                // Bundle configuration
                bundleID = "com.ericjesse.videotranslator"
                appCategory = "public.app-category.video"
                dockName = "Video Translator"

                // Code signing (use '-' for ad-hoc signing in development)
                signing {
                    sign.set(false)  // Enable for production with proper certificates
                    // identity.set("Developer ID Application: Your Name")
                }

                // Notarization (requires Apple Developer account)
                notarization {
                    // appleID.set("your@apple.id")
                    // password.set("@keychain:AC_PASSWORD")
                    // teamID.set("TEAM_ID")
                }

                // DMG configuration
                dmgPackageVersion = packageVersion
                pkgPackageVersion = packageVersion
                dmgPackageBuildVersion = appVersionCode.toString()
                pkgPackageBuildVersion = appVersionCode.toString()

                // Entitlements for sandboxing (optional)
                // entitlementsFile.set(project.file("src/main/distribution/macos/entitlements.plist"))
                // runtimeEntitlementsFile.set(project.file("src/main/distribution/macos/runtime-entitlements.plist"))

                // Info.plist customization
                infoPlist {
                    extraKeysRawXml = """
                        <key>CFBundleDocumentTypes</key>
                        <array>
                            <dict>
                                <key>CFBundleTypeName</key>
                                <string>SubRip Subtitle File</string>
                                <key>CFBundleTypeRole</key>
                                <string>Viewer</string>
                                <key>LSItemContentTypes</key>
                                <array>
                                    <string>com.ericjesse.videotranslator.srt</string>
                                </array>
                                <key>CFBundleTypeExtensions</key>
                                <array>
                                    <string>srt</string>
                                </array>
                            </dict>
                            <dict>
                                <key>CFBundleTypeName</key>
                                <string>WebVTT Subtitle File</string>
                                <key>CFBundleTypeRole</key>
                                <string>Viewer</string>
                                <key>LSItemContentTypes</key>
                                <array>
                                    <string>org.w3.webvtt</string>
                                </array>
                                <key>CFBundleTypeExtensions</key>
                                <array>
                                    <string>vtt</string>
                                </array>
                            </dict>
                        </array>
                        <key>UTExportedTypeDeclarations</key>
                        <array>
                            <dict>
                                <key>UTTypeIdentifier</key>
                                <string>com.ericjesse.videotranslator.srt</string>
                                <key>UTTypeDescription</key>
                                <string>SubRip Subtitle File</string>
                                <key>UTTypeConformsTo</key>
                                <array>
                                    <string>public.plain-text</string>
                                </array>
                                <key>UTTypeTagSpecification</key>
                                <dict>
                                    <key>public.filename-extension</key>
                                    <array>
                                        <string>srt</string>
                                    </array>
                                    <key>public.mime-type</key>
                                    <string>application/x-subrip</string>
                                </dict>
                            </dict>
                        </array>
                        <key>NSHighResolutionCapable</key>
                        <true/>
                        <key>NSSupportsAutomaticGraphicsSwitching</key>
                        <true/>
                        <key>LSMinimumSystemVersion</key>
                        <string>10.15</string>
                    """
                }
            }
        }

        // =================================================================
        // Build Types
        // =================================================================

        buildTypes.release {
            proguard {
                // Enable ProGuard for release builds (significant size reduction)
                isEnabled.set(true)

                // ProGuard configuration files
                configurationFiles.from(project.file("proguard-rules.pro"))

                // Optimize aggressively
                obfuscate.set(false)  // Keep class names readable for debugging
            }
        }
    }
}

// =============================================================================
// Custom Tasks
// =============================================================================

tasks.test {
    useJUnitPlatform()

    // Test JVM args
    jvmArgs = listOf(
        "-Xmx1g",
        "-Dfile.encoding=UTF-8"
    )

    // Test reporting
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
        showExceptions = true
        showCauses = true
    }
}

// Task to generate all icon sizes from a source image
tasks.register("generateIcons") {
    group = "build"
    description = "Generate app icons at all required sizes (requires ImageMagick)"

    doLast {
        val sourceIcon = file("src/main/resources/icons/icon-source.png")
        val iconsDir = file("src/main/resources/icons")

        if (!sourceIcon.exists()) {
            println("Source icon not found at: $sourceIcon")
            println("Please provide a high-resolution source icon (1024x1024 recommended)")
            return@doLast
        }

        // Generate PNG icons for Linux at various sizes
        listOf(16, 32, 48, 64, 128, 256, 512, 1024).forEach { size ->
            exec {
                commandLine("magick", sourceIcon.absolutePath,
                    "-resize", "${size}x${size}",
                    file("$iconsDir/icon-${size}.png").absolutePath)
                isIgnoreExitValue = true
            }
        }

        // Copy the 256px version as the main icon.png
        exec {
            commandLine("cp", file("$iconsDir/icon-256.png").absolutePath,
                file("$iconsDir/icon.png").absolutePath)
            isIgnoreExitValue = true
        }

        // Generate Windows ICO (multi-resolution)
        exec {
            commandLine("magick", sourceIcon.absolutePath,
                "-define", "icon:auto-resize=256,128,64,48,32,16",
                file("$iconsDir/icon.ico").absolutePath)
            isIgnoreExitValue = true
        }

        // Generate macOS ICNS
        val iconsetDir = file("$iconsDir/icon.iconset")
        iconsetDir.mkdirs()

        mapOf(
            "icon_16x16.png" to 16,
            "icon_16x16@2x.png" to 32,
            "icon_32x32.png" to 32,
            "icon_32x32@2x.png" to 64,
            "icon_128x128.png" to 128,
            "icon_128x128@2x.png" to 256,
            "icon_256x256.png" to 256,
            "icon_256x256@2x.png" to 512,
            "icon_512x512.png" to 512,
            "icon_512x512@2x.png" to 1024
        ).forEach { (name, size) ->
            exec {
                commandLine("magick", sourceIcon.absolutePath,
                    "-resize", "${size}x${size}",
                    file("$iconsetDir/$name").absolutePath)
                isIgnoreExitValue = true
            }
        }

        exec {
            commandLine("iconutil", "-c", "icns", iconsetDir.absolutePath,
                "-o", file("$iconsDir/icon.icns").absolutePath)
            isIgnoreExitValue = true
        }

        iconsetDir.deleteRecursively()

        println("Icons generated successfully!")
    }
}

// Task to create distribution resources directory structure
tasks.register("setupDistribution") {
    group = "build"
    description = "Set up the distribution resources directory structure"

    doLast {
        val distDir = file("src/main/distribution")

        // Create directory structure
        listOf(
            "common",
            "linux",
            "macos",
            "windows"
        ).forEach {
            file("$distDir/$it").mkdirs()
        }

        // Copy LICENSE to common if it exists
        val license = file("LICENSE")
        if (license.exists()) {
            license.copyTo(file("$distDir/common/LICENSE"), overwrite = true)
        }

        println("Distribution directory structure created at: $distDir")
    }
}

// Task to print version information
tasks.register("printVersion") {
    group = "help"
    description = "Print the current version"

    doLast {
        println("Version: $appVersion")
        println("Version Code: $appVersionCode")
    }
}

// Ensure distribution directory exists before packaging
tasks.matching { it.name.startsWith("package") }.configureEach {
    dependsOn("setupDistribution")
}
