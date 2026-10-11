package com.kyronix.files

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Centralises all storage-permission logic across API levels.
 *
 * Permission matrix
 * ─────────────────
 * API < 23   — granted at install; no runtime request needed
 * API 23–28  — READ + WRITE_EXTERNAL_STORAGE at runtime
 * API 29     — WRITE no longer grants general access; READ still works
 * API 30–32  — MANAGE_EXTERNAL_STORAGE via Settings (scoped-storage enforced)
 * API 33     — READ_MEDIA_IMAGES / VIDEO / AUDIO individually
 * API 34+    — also READ_MEDIA_VISUAL_USER_SELECTED for partial photo access
 *
 * MIUI HyperOS (Redmi Note 14) quirk
 * ────────────────────────────────────
 * ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION can throw ActivityNotFoundException
 * on certain MIUI builds if no resolver handles the package-specific Uri.
 * [requestManageExternalStorage] catches this and falls through to safer intents.
 */
object StorageHelper {

    const val RC_LEGACY_STORAGE        = 1001
    const val RC_MEDIA_PERMS           = 1002
    const val RC_MANAGE_STORAGE_SETTINGS = 1003

    // ── Has access ───────────────────────────────────────────────────────────

    /** True if the app can read external storage on the current API level. */
    fun hasStorageAccess(ctx: Context): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
            Environment.isExternalStorageManager()

        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
            ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED

        else -> true // API < 23
    }

    /** True if full filesystem manage-access is available (API 30+). */
    fun hasFullStorageAccess(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            Environment.isExternalStorageManager()
        else
            hasStorageAccess(ctx)

    /** True if a granular media permission is granted (API 33+). */
    fun hasMediaPermission(ctx: Context, permission: String): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED
        else
            true // covered by READ_EXTERNAL_STORAGE on older APIs

    // ── Request helpers ──────────────────────────────────────────────────────

    /**
     * Initiates the correct permission-request flow for the running API level.
     * The Activity must forward [onRequestPermissionsResult] and [onActivityResult].
     */
    fun requestStorageAccess(activity: Activity) {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                requestManageExternalStorage(activity)

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
                ActivityCompat.requestPermissions(
                    activity,
                    arrayOf(
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ),
                    RC_LEGACY_STORAGE
                )
            // else API < 23 — nothing to do
        }
    }

    /** Requests granular media perms (API 33+); falls back to [requestStorageAccess] on older. */
    fun requestMediaPermissions(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val perms = buildList {
                add(Manifest.permission.READ_MEDIA_IMAGES)
                add(Manifest.permission.READ_MEDIA_VIDEO)
                add(Manifest.permission.READ_MEDIA_AUDIO)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                }
            }.toTypedArray()
            ActivityCompat.requestPermissions(activity, perms, RC_MEDIA_PERMS)
        } else {
            requestStorageAccess(activity)
        }
    }

    // ── MIUI-safe MANAGE_EXTERNAL_STORAGE intent ─────────────────────────────

    /**
     * Opens the All-files-access settings page.
     *
     * Fallback chain (handles MIUI HyperOS quirks):
     * 1. ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION + package Uri  (preferred)
     * 2. ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION (general list)
     * 3. ACTION_APPLICATION_DETAILS_SETTINGS (last resort)
     */
    fun requestManageExternalStorage(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        val pkg = activity.packageName

        fun tryStart(intent: Intent): Boolean = runCatching {
            // resolveActivity == null means no handler — don't fire blind
            if (intent.resolveActivity(activity.packageManager) == null) return false
            activity.startActivityForResult(intent, RC_MANAGE_STORAGE_SETTINGS)
            true
        }.getOrDefault(false)

        val specific = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:$pkg")
        )
        val general = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        val appInfo = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$pkg")
        )

        if (!tryStart(specific) && !tryStart(general)) {
            tryStart(appInfo) // always available
        }
    }

    // ── UI text ──────────────────────────────────────────────────────────────

    fun permissionRationale(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            "Kyronix Files needs \"All files access\" to browse your entire storage. " +
            "Tap Grant to open Settings."
        else
            "Kyronix Files needs storage permission to read and display your files."

    /** True if WRITE_EXTERNAL_STORAGE is actually needed (API 23–28 only). */
    val needsLegacyWrite: Boolean
        get() = Build.VERSION.SDK_INT in
                Build.VERSION_CODES.M..Build.VERSION_CODES.P
}
