package com.kyronix.files

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Loads directory contents on [Dispatchers.IO] and delivers results on the
 * caller's scope (expected to be [Dispatchers.Main]).
 *
 * Why coroutines instead of AsyncTask:
 * - AsyncTask was deprecated in API 30 and exhibits unreliable behaviour on
 *   MIUI's custom thread scheduler (tasks can be silently dropped when the
 *   device is under memory pressure).
 * - A [SupervisorJob] scoped to the ViewModel/Activity lifetime means
 *   in-flight work is automatically cancelled on destroy with no leak.
 *
 * Usage
 * ─────
 *   private val loader = FileLoader()          // create once per Activity/Fragment
 *   loader.load(dir, showHidden, sort) { result ->
 *       result.onSuccess { items -> … }
 *         .onFailure { err -> … }
 *   }
 *   loader.destroy()   // call from onDestroy()
 */
class FileLoader {

    enum class SortBy    { NAME, SIZE, DATE, TYPE }
    enum class SortOrder { ASC, DESC }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var currentJob: Job? = null

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Cancel any in-flight load and start loading [directory].
     *
     * @param directory   target directory
     * @param showHidden  include files starting with '.'
     * @param sortBy      primary sort key
     * @param sortOrder   ascending or descending
     * @param onResult    called on the main thread with [Result]<[List]<[FileItem]>>
     */
    fun load(
        directory: File,
        showHidden: Boolean   = false,
        sortBy: SortBy        = SortBy.NAME,
        sortOrder: SortOrder  = SortOrder.ASC,
        onResult: (Result<List<FileItem>>) -> Unit,
    ) {
        currentJob?.cancel()
        currentJob = scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { readDirectory(directory, showHidden, sortBy, sortOrder) }
            }
            // Deliver on Main — check active so we don't touch a recycled adapter
            if (isActive) onResult(result)
        }
    }

    /** Cancel the in-flight load without starting a new one. */
    fun cancel() {
        currentJob?.cancel()
    }

    /** Shut down the coroutine scope. Call from Activity.onDestroy(). */
    fun destroy() {
        scope.cancel()
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    /**
     * Blocking directory read — runs on [Dispatchers.IO].
     *
     * [File.listFiles] returns null on I/O errors *and* when the directory
     * is unreadable. On MIUI this happens even with MANAGE_EXTERNAL_STORAGE
     * for restricted partitions (e.g. /data/, some /proc/ paths). We treat
     * null as an empty result rather than an error to match Samsung My Files
     * behaviour (silently skip inaccessible dirs).
     */
    @Throws(SecurityException::class, IllegalArgumentException::class)
    private fun readDirectory(
        dir: File,
        showHidden: Boolean,
        sortBy: SortBy,
        sortOrder: SortOrder,
    ): List<FileItem> {
        require(dir.exists())    { "Path does not exist: ${dir.absolutePath}" }
        require(dir.isDirectory) { "Not a directory: ${dir.absolutePath}" }

        val raw: Array<File> = dir.listFiles() ?: return emptyList()

        val items = raw
            .filterNot { !showHidden && it.name.startsWith('.') }
            .map { FileItem(it) }

        // Sort: folders always first, then by the selected key
        val comparator: Comparator<FileItem> = compareBy<FileItem> { !it.isDirectory }
            .then(buildComparator(sortBy, sortOrder))

        return items.sortedWith(comparator)
    }

    private fun buildComparator(by: SortBy, order: SortOrder): Comparator<FileItem> {
        val base: Comparator<FileItem> = when (by) {
            SortBy.NAME -> compareBy { it.name.lowercase() }
            SortBy.SIZE -> compareBy { it.size }
            SortBy.DATE -> compareBy { it.lastModified }
            SortBy.TYPE -> compareBy { it.fileType.ordinal }
        }
        return if (order == SortOrder.DESC) base.reversed() else base
    }
}
