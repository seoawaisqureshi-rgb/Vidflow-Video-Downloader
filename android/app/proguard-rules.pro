# VidFlow ProGuard Rules

# Keep Kotlin metadata
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes SourceFile,LineNumberTable

# Keep Room entities and DAOs
-keep class com.vidflow.app.data.db.** { *; }

# Keep Kotlinx serialization
-keepattributes *Annotation*
-keep class kotlinx.serialization.** { *; }
-dontwarn kotlinx.serialization.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Kotlin coroutines
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**

# Coil
-dontwarn coil.**

# ExoPlayer / Media3
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Keep enum values (used in Room type converters and JSON parsing)
-keepclassmembers enum * { *; }

# Keep data model classes (used in serialization/deserialization)
-keep class com.vidflow.app.data.model.** { *; }
