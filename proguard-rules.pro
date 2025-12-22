# =============================================================================
# ProGuard Rules for Video Translator
# =============================================================================

# General optimization settings
-optimizationpasses 5
-allowaccessmodification
-mergeinterfacesaggressively

# Keep debugging info for crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# =============================================================================
# Kotlin
# =============================================================================

# Keep Kotlin metadata for reflection
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations

# Keep Kotlin coroutines
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# Keep data classes
-keepclassmembers class * {
    public <init>(...);
}

# Keep serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep `Companion` object fields of serializable classes
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

# Keep `serializer()` on companion objects of serializable classes
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep `INSTANCE.serializer()` of serializable objects
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# =============================================================================
# Compose
# =============================================================================

# Keep Compose runtime
-keep class androidx.compose.** { *; }
-keep class org.jetbrains.skia.** { *; }
-keep class org.jetbrains.skiko.** { *; }

# Keep Compose Multiplatform desktop
-keep class androidx.compose.ui.awt.** { *; }
-keep class androidx.compose.desktop.** { *; }

# =============================================================================
# Ktor
# =============================================================================

# Keep Ktor engine
-keep class io.ktor.** { *; }
-keepclassmembers class io.ktor.** { volatile <fields>; }
-keepclassmembernames class io.ktor.** { *; }

# Keep CIO engine
-keep class io.ktor.client.engine.cio.** { *; }

# =============================================================================
# Logging
# =============================================================================

# Keep logback
-keep class ch.qos.logback.** { *; }
-keep class org.slf4j.** { *; }

# Keep kotlin-logging
-keep class io.github.oshai.kotlinlogging.** { *; }

# =============================================================================
# Application Classes
# =============================================================================

# Keep main entry point
-keep class com.ericjesse.videotranslator.MainKt {
    public static void main(java.lang.String[]);
}

# Keep all domain models (for serialization)
-keep class com.ericjesse.videotranslator.domain.model.** { *; }

# Keep all config classes (for serialization)
-keep class com.ericjesse.videotranslator.infrastructure.config.** { *; }

# Keep ViewModels
-keep class com.ericjesse.videotranslator.ui.screens.**.ViewModel { *; }
-keep class com.ericjesse.videotranslator.ui.screens.**.*ViewModel { *; }

# Keep state classes
-keep class com.ericjesse.videotranslator.ui.screens.**.*State { *; }

# =============================================================================
# Service Loader
# =============================================================================

-keepnames class * implements java.sql.Driver
-keepnames class javax.** { *; }

# =============================================================================
# Apache Commons
# =============================================================================

-keep class org.apache.commons.compress.** { *; }
-keep class org.tukaani.xz.** { *; }

# =============================================================================
# Reflection
# =============================================================================

# Keep enums
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# =============================================================================
# Suppress Warnings
# =============================================================================

-dontwarn kotlinx.**
-dontwarn org.jetbrains.**
-dontwarn io.ktor.**
-dontwarn org.slf4j.**
-dontwarn ch.qos.logback.**
-dontwarn javax.**
-dontwarn java.awt.**
-dontwarn sun.misc.**
-dontwarn com.sun.**
