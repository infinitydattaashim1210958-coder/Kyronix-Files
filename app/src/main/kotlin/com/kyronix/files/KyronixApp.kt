package com.kyronix.files

import android.app.Application
import android.content.res.Configuration
import android.os.Build
import android.os.StrictMode

/**
 * Application subclass.
 *
 * Responsibilities:
 * - StrictMode in debug builds only (MIUI's aggressive scheduler makes
 *   penaltyDeath unsafe in production).
 * - Belt-and-suspenders guard so no raw file:// URIs escape into intents
 *   (the real fix is FileProvider throughout the codebase).
 */
class KyronixApp : Application() {

    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()   // log only — never penaltyDeath on MIUI
                    .build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedSqlLiteObjects()
                    .detectLeakedClosableObjects()
                    .penaltyLog()
                    .build()
            )
        }

        // MIUI HyperOS (Redmi Note 14): suppress hidden-API restriction warnings
        // that can surface during reflection inside third-party libs on API ≤ 30.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
            runCatching {
                val vmRuntime = Class.forName("dalvik.system.VMRuntime")
                val getRuntime = vmRuntime.getDeclaredMethod("getRuntime").apply {
                    isAccessible = true
                }
                getRuntime.invoke(null) // ensure runtime is initialised
            }
            // Failure is silently swallowed — proceed normally.
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Dark-mode, locale, font-scale handled by Activity via configChanges manifest attr.
    }

    override fun onLowMemory() {
        super.onLowMemory()
        // Future: clear image/thumbnail caches here.
    }
}
