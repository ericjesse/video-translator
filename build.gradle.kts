import java.io.ByteArrayOutputStream
import java.time.Instant
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// Batik is loaded into the buildscript classpath only — used by the
// `generateIcons` task to rasterise icon.svg into PNG/ICO/ICNS without
// requiring ImageMagick on the developer's machine.
buildscript {
    repositories { mavenCentral() }
    dependencies {
        classpath("org.apache.xmlgraphics:batik-transcoder:1.17")
        classpath("org.apache.xmlgraphics:batik-codec:1.17")
    }
}

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

println("Building Linguini version: $appVersion (code: $appVersionCode)")

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

    // Dependency Injection
    val koinVersion = "4.0.0"
    implementation(platform("io.insert-koin:koin-bom:$koinVersion"))
    implementation("io.insert-koin:koin-core")
    implementation("io.insert-koin:koin-compose")
    testImplementation("io.insert-koin:koin-test")

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
            packageName = "Linguini"
            packageVersion = appVersion.split("-").first().let {
                // Ensure version is in X.Y.Z format for native packages
                val parts = it.split(".")
                when {
                    parts.size >= 3 -> "${parts[0]}.${parts[1]}.${parts[2]}"
                    parts.size == 2 -> "${parts[0]}.${parts[1]}.0"
                    else -> "${parts[0]}.0.0"
                }
            }
            description = "Linguini — translate YouTube videos into any language with AI-powered transcription and translation"
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
                "java.management",
                "jdk.management",  // Contains com.sun.management.OperatingSystemMXBean
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
                packageName = "linguini"
                debMaintainer = "eric@example.com"
                menuGroup = "AudioVideo"
                appCategory = "AudioVideo"
                appRelease = "1"

                // Desktop entry
                shortcut = true

                // RPM specific
                rpmLicenseType = "Apache-2.0"

                // Installation directories
                installationPath = "/opt/linguini"
            }

            // =================================================================
            // Windows Configuration
            // =================================================================
            windows {
                iconFile.set(project.file("src/main/resources/icons/icon.ico"))

                // Installer configuration
                packageName = "Linguini"
                menuGroup = "Linguini"
                // New upgradeUuid for the renamed product. Linguini is a fresh
                // identity in the MSI registry — installing it does not "upgrade"
                // an existing VideoTranslator install (the two can coexist until
                // the user uninstalls the old product manually).
                upgradeUuid = "5c1c4d11-7e0a-4f6b-9c5d-2a8b3e7f1d22"

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
                dockName = "Linguini"

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

// Generates icon.png / icon.ico / icon.icns from icon.svg using Apache Batik
// (loaded into the buildscript classpath above). This is pure-JVM — no
// ImageMagick / iconutil / Inkscape required, so it runs identically on
// Windows, macOS, Linux, and CI.
tasks.register("generateIcons") {
    group = "build"
    description = "Render icon.png/ico/icns from icon.svg (pure JVM, uses Apache Batik)"

    doLast {
        val iconsDir = file("src/main/resources/icons")
        val svg = file("$iconsDir/icon.svg")
        if (!svg.exists()) {
            throw GradleException("Source SVG not found at $svg. Create it first.")
        }
        println("Rendering from ${svg.absolutePath}")

        // Little-endian and big-endian byte writers used for the binary
        // container formats (ICO is LE, ICNS is BE).
        fun le16(v: Int) =
            byteArrayOf((v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte())
        fun le32(v: Int) =
            byteArrayOf(
                (v and 0xFF).toByte(),
                ((v ushr 8) and 0xFF).toByte(),
                ((v ushr 16) and 0xFF).toByte(),
                ((v ushr 24) and 0xFF).toByte()
            )
        fun be32(v: Int) =
            byteArrayOf(
                ((v ushr 24) and 0xFF).toByte(),
                ((v ushr 16) and 0xFF).toByte(),
                ((v ushr 8) and 0xFF).toByte(),
                (v and 0xFF).toByte()
            )

        // Rasterise the SVG once per target size. Batik does the entire
        // SVG → PNG pipeline in-memory; we keep each result as bytes so we
        // can re-use them for the ICO/ICNS containers without reading from disk.
        fun renderPng(size: Int): ByteArray {
            val transcoder = org.apache.batik.transcoder.image.PNGTranscoder()
            transcoder.addTranscodingHint(
                org.apache.batik.transcoder.image.PNGTranscoder.KEY_WIDTH,
                size.toFloat()
            )
            transcoder.addTranscodingHint(
                org.apache.batik.transcoder.image.PNGTranscoder.KEY_HEIGHT,
                size.toFloat()
            )
            val out = ByteArrayOutputStream()
            transcoder.transcode(
                org.apache.batik.transcoder.TranscoderInput(svg.toURI().toString()),
                org.apache.batik.transcoder.TranscoderOutput(out)
            )
            return out.toByteArray()
        }

        val sizes = listOf(16, 32, 48, 64, 128, 256, 512, 1024)
        val pngs = sizes.associateWith { renderPng(it) }

        // Per-size PNGs (kept for inspection / Linux desktop entries) plus the
        // canonical icon.png used by the Linux native distribution.
        sizes.forEach { size ->
            file("$iconsDir/icon-$size.png").writeBytes(pngs[size]!!)
        }
        file("$iconsDir/icon.png").writeBytes(pngs[256]!!)
        println("  icon.png        ${pngs[256]!!.size} bytes (256×256)")

        // -------- Windows ICO (PNG-embedded variant; supported on Vista+) --------
        // Format: 6-byte ICONDIR header + 16-byte ICONDIRENTRY per image +
        // PNG payloads concatenated. All multi-byte fields are little-endian.
        val icoSizes = listOf(16, 32, 48, 64, 128, 256)
        val ico = ByteArrayOutputStream()
        ico.write(le16(0))                  // reserved
        ico.write(le16(1))                  // type = icon
        ico.write(le16(icoSizes.size))      // image count
        var offset = 6 + 16 * icoSizes.size
        icoSizes.forEach { size ->
            val data = pngs[size]!!
            // Width/height are 1 byte; the value 0 is interpreted as 256.
            ico.write(if (size == 256) 0 else size)
            ico.write(if (size == 256) 0 else size)
            ico.write(0)                    // colorCount (0 = 32-bit)
            ico.write(0)                    // reserved
            ico.write(le16(1))              // planes
            ico.write(le16(32))             // bitCount
            ico.write(le32(data.size))      // bytes in resource
            ico.write(le32(offset))         // offset to PNG data
            offset += data.size
        }
        icoSizes.forEach { size -> ico.write(pngs[size]!!) }
        file("$iconsDir/icon.ico").writeBytes(ico.toByteArray())
        println("  icon.ico        ${ico.size()} bytes (sizes ${icoSizes.joinToString()})")

        // -------- macOS ICNS (PNG-embedded variant; supported on 10.7+) --------
        // Format: 8-byte "icns" + total-size header, followed by typed blocks.
        // Each block is a 4-byte type code + 4-byte BE size (header + data) + data.
        val icnsTypes = linkedMapOf(
            "icp4" to 16,
            "icp5" to 32,
            "icp6" to 64,
            "ic07" to 128,
            "ic08" to 256,
            "ic09" to 512,
            "ic10" to 1024
        )
        val blocks = ByteArrayOutputStream()
        icnsTypes.forEach { (type, size) ->
            val data = pngs[size]!!
            blocks.write(type.toByteArray(Charsets.US_ASCII))
            blocks.write(be32(8 + data.size))
            blocks.write(data)
        }
        val blockBytes = blocks.toByteArray()
        val icns = ByteArrayOutputStream()
        icns.write("icns".toByteArray(Charsets.US_ASCII))
        icns.write(be32(8 + blockBytes.size))
        icns.write(blockBytes)
        file("$iconsDir/icon.icns").writeBytes(icns.toByteArray())
        println("  icon.icns       ${icns.size()} bytes (sizes ${icnsTypes.values.joinToString()})")
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
