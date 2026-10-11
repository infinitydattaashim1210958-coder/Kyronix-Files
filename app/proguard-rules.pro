# ── Kyronix Files ProGuard/R8 rules ─────────────────────────────────────────

# Keep our own classes in full (R8 will still shrink unused members)
-keep class com.kyronix.files.** { *; }

# FileProvider — must survive shrinking; referenced by manifest
-keep class androidx.core.content.FileProvider { *; }

# Kotlin coroutines — keep internal machinery that R8 can misidentify as dead code
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# Kotlin stdlib reflection used by coroutines
-dontwarn kotlin.reflect.jvm.internal.**

# Material3 / AppCompat — keep theme-related classes
-keep class com.google.android.material.** { *; }
-keep class androidx.appcompat.widget.** { *; }

# Prevent R8 from removing enum entries (used in FileType, SortBy, SortOrder)
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# DiffUtil callbacks — anonymous classes inside FileAdapter
-keep class * extends androidx.recyclerview.widget.DiffUtil$Callback { *; }

# Suppress warnings for dalvik.system.VMRuntime (reflection in KyronixApp)
-dontwarn dalvik.system.VMRuntime
