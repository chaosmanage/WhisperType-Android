# WhisperType release rules.
# Compose and Kotlin metadata are already handled by their respective libraries.
-keepattributes *Annotation*
-keep class com.whispertype.android.security.** { *; }
-dontwarn okhttp3.**
