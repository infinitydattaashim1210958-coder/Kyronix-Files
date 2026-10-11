package com.kyronix.files

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.snackbar.Snackbar
import java.io.File
import java.util.ArrayDeque
import java.util.Locale

/**
 * Kyronix Files — main (and only) Activity.
 *
 * ── MIUI HyperOS / Redmi Note 14 crash root-causes and fixes ─────────────────
 *
 * 1. NPE from Environment.getExternalStorageDirectory()
 *    Fix → null-safe call + existence check before navigation.
 *
 * 2. FileUriExposedException / silent MIUI security block on file:// URIs
 *    Fix → all Intent payloads use FileProvider (content:// URIs) exclusively.
 *
 * 3. ActivityNotFoundException on MANAGE_APP_ALL_FILES_ACCESS_PERMISSION intent
 *    Fix → StorageHelper.requestManageExternalStorage() try/fallback chain.
 *
 * 4. RecyclerView crash (IllegalStateException) when adapter.submitList() is
 *    called after Activity.onSaveInstanceState()
 *    Fix → pendingItems buffer; deliver on next onResume().
 *
 * 5. Toolbar crash on samsung:titleCentered attribute (not in MIUI's attr set)
 *    Fix → MaterialToolbar only; no vendor-specific XML attributes anywhere.
 *
 * 6. MIUI kills the process between permission request and result → onResume
 *    re-checks permission state unconditionally.
 */
class MainActivity : AppCompatActivity() {

    // ── Constants ─────────────────────────────────────────────────────────────

    private val authority get() = "${BuildConfig.APPLICATION_ID}.fileprovider"
    private val gridSpan  = 3

    // ── Views ─────────────────────────────────────────────────────────────────

    private lateinit var toolbar               : Toolbar
    private lateinit var recyclerView          : RecyclerView
    private lateinit var swipeRefresh          : SwipeRefreshLayout
    private lateinit var bottomNav             : BottomNavigationView
    private lateinit var breadcrumbGroup       : ChipGroup
    private lateinit var layoutPermission      : View
    private lateinit var tvPermissionMsg       : TextView
    private lateinit var layoutEmpty           : View
    private lateinit var loadingProgress       : View

    // ── State ─────────────────────────────────────────────────────────────────

    private lateinit var adapter    : FileAdapter
    private val loader              = FileLoader()
    private val backStack           = ArrayDeque<File>()
    private var currentDir          : File? = null
    private var isGridMode          = false
    private var isActivityResumed   = false
    private var pendingItems        : List<FileItem>? = null

    private var sortBy    = FileLoader.SortBy.NAME
    private var sortOrder = FileLoader.SortOrder.ASC
    private var showHidden = false

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        setupToolbar()
        setupBottomNav()
        setupRecyclerView()
        setupSwipeRefresh()

        // Restore rotation state
        savedInstanceState?.let { ss ->
            isGridMode    = ss.getBoolean(KEY_GRID, false)
            sortBy        = SortBy.values()[ss.getInt(KEY_SORT_BY, 0)]
            sortOrder     = SortOrder.values()[ss.getInt(KEY_SORT_ORDER, 0)]
            applyViewMode()
            ss.getString(KEY_DIR)?.let { currentDir = File(it) }
        }

        checkPermissionsAndLoad()
    }

    override fun onResume() {
        super.onResume()
        isActivityResumed = true

        // Deliver items buffered while paused
        pendingItems?.let {
            adapter.submitList(it)
            pendingItems = null
            updateEmptyState()
        }

        // Re-check permission — user may have just granted it in Settings
        when {
            !StorageHelper.hasStorageAccess(this) -> showPermissionScreen()
            currentDir != null -> {
                hidePermissionScreen()
                loadDirectory(currentDir!!)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        isActivityResumed = false
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        currentDir?.let { outState.putString(KEY_DIR, it.absolutePath) }
        outState.putBoolean(KEY_GRID, isGridMode)
        outState.putInt(KEY_SORT_BY, sortBy.ordinal)
        outState.putInt(KEY_SORT_ORDER, sortOrder.ordinal)
    }

    override fun onDestroy() {
        super.onDestroy()
        loader.destroy()
    }

    // ── Back navigation ───────────────────────────────────────────────────────

    @Deprecated("Required override")
    override fun onBackPressed() {
        when {
            adapter.selectionMode -> exitSelectionMode()
            backStack.size > 1    -> {
                backStack.pop()
                currentDir = backStack.peek()
                currentDir?.let {
                    refreshBreadcrumbs()
                    loadDirectory(it)
                }
            }
            else -> super.onBackPressed()
        }
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    private fun checkPermissionsAndLoad() {
        if (StorageHelper.hasStorageAccess(this)) {
            hidePermissionScreen()
            navigateToRoot()
        } else {
            showPermissionScreen()
            StorageHelper.requestStorageAccess(this)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == StorageHelper.RC_LEGACY_STORAGE
            || requestCode == StorageHelper.RC_MEDIA_PERMS
        ) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                hidePermissionScreen()
                navigateToRoot()
            } else {
                showPermissionDialog()
            }
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == StorageHelper.RC_MANAGE_STORAGE_SETTINGS) {
            if (StorageHelper.hasFullStorageAccess(this)) {
                hidePermissionScreen()
                navigateToRoot()
            } else {
                showPermissionScreen()
            }
        }
    }

    // ── UI setup ──────────────────────────────────────────────────────────────

    private fun bindViews() {
        toolbar           = findViewById(R.id.toolbar)
        recyclerView      = findViewById(R.id.recycler_view)
        swipeRefresh      = findViewById(R.id.swipe_refresh)
        bottomNav         = findViewById(R.id.bottom_nav)
        breadcrumbGroup   = findViewById(R.id.breadcrumb_group)
        layoutPermission  = findViewById(R.id.layout_permission)
        tvPermissionMsg   = findViewById(R.id.tv_permission_msg)
        layoutEmpty       = findViewById(R.id.layout_empty)
        loadingProgress   = findViewById(R.id.loading_progress)
    }

    private fun setupToolbar() {
        setSupportActionBar(toolbar)
        supportActionBar?.title = getString(R.string.app_name)
    }

    private fun setupBottomNav() {
        bottomNav.setOnItemSelectedListener { item ->
            exitSelectionMode()
            when (item.itemId) {
                R.id.nav_internal  -> { navigateToInternal(); true }
                R.id.nav_sd        -> { navigateToSdCard();   true }
                R.id.nav_downloads -> { navigateToDownloads();true }
                R.id.nav_recent    -> { navigateToRecent();   true }
                else               -> false
            }
        }
        bottomNav.selectedItemId = R.id.nav_internal
    }

    private fun setupRecyclerView() {
        adapter = FileAdapter(
            onClick     = ::onFileClick,
            onLongClick = ::onFileLongClick,
            onOverflow  = ::onFileOverflow,
        )
        recyclerView.adapter = adapter
        // Suppress change-animation flicker on MIUI's compositor
        recyclerView.itemAnimator?.changeDuration = 0
        applyViewMode()
    }

    private fun setupSwipeRefresh() {
        swipeRefresh.setColorSchemeResources(R.color.accent_primary)
        swipeRefresh.setOnRefreshListener {
            currentDir?.let { loadDirectory(it) } ?: run { swipeRefresh.isRefreshing = false }
        }
    }

    private fun applyViewMode() {
        recyclerView.layoutManager = if (isGridMode)
            GridLayoutManager(this, gridSpan)
        else
            LinearLayoutManager(this)
        adapter.setViewMode(if (isGridMode) FileAdapter.ViewMode.GRID else FileAdapter.ViewMode.LIST)
        invalidateOptionsMenu()
    }

    // ── Options menu ──────────────────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)

        // Search
        (menu.findItem(R.id.action_search)?.actionView as? SearchView)?.let { sv ->
            sv.queryHint = getString(R.string.search_hint)
            sv.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(q: String?) = true.also { filterList(q.orEmpty()) }
                override fun onQueryTextChange(q: String?) = true.also { filterList(q.orEmpty()) }
            })
            menu.findItem(R.id.action_search)
                ?.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
                    override fun onMenuItemActionExpand(item: MenuItem) = true
                    override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                        currentDir?.let { loadDirectory(it) }
                        return true
                    }
                })
        }

        // Toggle grid/list icon
        menu.findItem(R.id.action_toggle_view)?.setIcon(
            if (isGridMode) R.drawable.ic_view_list else R.drawable.ic_view_grid
        )

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_toggle_view -> {
            isGridMode = !isGridMode
            applyViewMode()
            true
        }
        R.id.action_sort       -> { showSortDialog(); true }
        R.id.action_hidden     -> { showHidden = !showHidden; item.isChecked = showHidden; currentDir?.let { loadDirectory(it) }; true }
        android.R.id.home      -> { onBackPressed(); true }
        else                   -> super.onOptionsItemSelected(item)
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    private fun navigateToRoot() {
        bottomNav.selectedItemId = R.id.nav_internal
    }

    private fun navigateToInternal() {
        // MIUI fix: getExternalStorageDirectory() can return null or non-existent path
        val dir = Environment.getExternalStorageDirectory()
            ?.takeIf { it.exists() }
            ?: run { showError(getString(R.string.error_storage_unavailable)); return }
        backStack.clear()
        openDirectory(dir, getString(R.string.label_internal))
    }

    private fun navigateToSdCard() {
        val sd = findSdCard() ?: run {
            Toast.makeText(this, R.string.label_no_sd, Toast.LENGTH_SHORT).show()
            return
        }
        backStack.clear()
        openDirectory(sd, getString(R.string.label_sd))
    }

    private fun navigateToDownloads() {
        val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            ?.takeIf { it.exists() }
            ?: run { showError(getString(R.string.error_storage_unavailable)); return }
        backStack.clear()
        openDirectory(dl, getString(R.string.label_downloads))
    }

    private fun navigateToRecent() {
        // Phase 1: show Downloads as a proxy for recency
        Toast.makeText(this, R.string.label_recent_coming_soon, Toast.LENGTH_SHORT).show()
    }

    private fun openDirectory(dir: File, displayName: String? = null) {
        backStack.push(dir)
        currentDir = dir
        supportActionBar?.apply {
            title = displayName ?: dir.name
            setDisplayHomeAsUpEnabled(backStack.size > 1)
        }
        refreshBreadcrumbs()
        loadDirectory(dir)
    }

    // ── Directory loading ─────────────────────────────────────────────────────

    private fun loadDirectory(dir: File) {
        showLoading(true)
        loader.load(dir, showHidden, sortBy, sortOrder) { result ->
            swipeRefresh.isRefreshing = false
            showLoading(false)
            result
                .onSuccess { items ->
                    if (isActivityResumed) {
                        adapter.submitList(items)
                        updateEmptyState()
                    } else {
                        pendingItems = items   // deliver on next onResume
                    }
                }
                .onFailure { err ->
                    if (isActivityResumed) showError(err.message ?: getString(R.string.error_generic))
                }
        }
    }

    // ── File interactions ─────────────────────────────────────────────────────

    private fun onFileClick(item: FileItem) {
        if (adapter.selectionMode) {
            adapter.toggleSelection(item)
            val count = adapter.selectedCount
            if (count == 0) exitSelectionMode()
            else toolbar.title = getString(R.string.selection_count, count)
            return
        }
        if (item.isDirectory) openDirectory(item.file)
        else openFile(item.file)
    }

    private fun onFileLongClick(item: FileItem): Boolean {
        if (!adapter.selectionMode) {
            adapter.enterSelectionMode()
            invalidateOptionsMenu()
        }
        adapter.toggleSelection(item)
        toolbar.title = getString(R.string.selection_count, adapter.selectedCount)
        return true
    }

    private fun onFileOverflow(item: FileItem, anchor: View) {
        PopupMenu(this, anchor).apply {
            menuInflater.inflate(R.menu.menu_file_action, menu)
            setOnMenuItemClickListener { mi ->
                when (mi.itemId) {
                    R.id.action_open_with  -> { openFileWith(item.file); true }
                    R.id.action_share      -> { shareFile(item.file);    true }
                    R.id.action_rename     -> { showRenameDialog(item);  true }
                    R.id.action_delete     -> { showDeleteDialog(item);  true }
                    R.id.action_properties -> { showProperties(item);    true }
                    else                   -> false
                }
            }
            show()
        }
    }

    // ── File operations ───────────────────────────────────────────────────────

    /**
     * Open file using content:// URI via FileProvider.
     *
     * MIUI HyperOS crash fix: never pass raw file:// URI to an Intent.
     * MIUI's intent resolver throws FileUriExposedException or silently
     * cancels the launch even with FLAG_GRANT_READ_URI_PERMISSION on file://.
     */
    private fun openFile(file: File) {
        val uri = contentUriFor(file) ?: return
        val mime = mimeTypeOf(file)

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            openFileWith(file)   // fall back to chooser
        }
    }

    private fun openFileWith(file: File) {
        val uri = contentUriFor(file) ?: return
        val chooser = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.let { Intent.createChooser(it, getString(R.string.action_open_with)) }
        try {
            startActivity(chooser)
        } catch (e: ActivityNotFoundException) {
            showError(getString(R.string.error_no_app))
        }
    }

    private fun shareFile(file: File) {
        val uri = contentUriFor(file) ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeTypeOf(file)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // ClipData required on API 26+ for implicit URI permission grant
            clipData = ClipData.newRawUri("", uri)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.action_share)))
    }

    private fun showRenameDialog(item: FileItem) {
        val input = EditText(this).apply {
            setText(item.name)
            selectAll()
            setSingleLine()
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = resources.getDimensionPixelSize(R.dimen.dialog_padding)
            setPadding(pad, 0, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.action_rename)
            .setView(container)
            .setPositiveButton(R.string.btn_rename) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isEmpty()) return@setPositiveButton
                val dest = File(item.file.parent, newName)
                when {
                    dest.exists()          -> showError(getString(R.string.error_file_exists))
                    !item.file.renameTo(dest) -> showError(getString(R.string.error_rename_failed))
                    else                   -> currentDir?.let { loadDirectory(it) }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showDeleteDialog(item: FileItem) {
        AlertDialog.Builder(this)
            .setTitle(R.string.action_delete)
            .setMessage(getString(R.string.confirm_delete, item.name))
            .setPositiveButton(R.string.btn_delete) { _, _ ->
                if (!deleteRecursive(item.file))
                    showError(getString(R.string.error_delete_failed))
                else
                    currentDir?.let { loadDirectory(it) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showProperties(item: FileItem) {
        val msg = buildString {
            appendLine("Name:     ${item.name}")
            appendLine("Path:     ${item.file.absolutePath}")
            if (!item.isDirectory) appendLine("Size:     ${item.displaySize}")
            appendLine("Modified: ${item.displayDate}")
            appendLine("Type:     ${if (item.isDirectory) "Folder" else mimeTypeOf(item.file)}")
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.action_properties)
            .setMessage(msg)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // ── Breadcrumbs ───────────────────────────────────────────────────────────

    private fun refreshBreadcrumbs() {
        breadcrumbGroup.removeAllViews()
        val stack = backStack.toList().reversed() // oldest first
        stack.forEachIndexed { idx, dir ->
            Chip(this).apply {
                text = if (idx == 0) getString(R.string.label_internal) else dir.name
                isClickable = true
                isCheckable = false
                setChipBackgroundColorResource(R.color.chip_bg)
                setOnClickListener {
                    repeat(stack.size - 1 - idx) { backStack.pop() }
                    currentDir = dir
                    refreshBreadcrumbs()
                    loadDirectory(dir)
                }
                breadcrumbGroup.addView(this)
            }
        }
    }

    // ── Sort ──────────────────────────────────────────────────────────────────

    private fun showSortDialog() {
        val options = arrayOf(
            getString(R.string.sort_name_asc),
            getString(R.string.sort_name_desc),
            getString(R.string.sort_date_desc),
            getString(R.string.sort_date_asc),
            getString(R.string.sort_size_asc),
            getString(R.string.sort_size_desc),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.action_sort)
            .setItems(options) { _, which ->
                val (by, ord) = when (which) {
                    0 -> FileLoader.SortBy.NAME to FileLoader.SortOrder.ASC
                    1 -> FileLoader.SortBy.NAME to FileLoader.SortOrder.DESC
                    2 -> FileLoader.SortBy.DATE to FileLoader.SortOrder.DESC
                    3 -> FileLoader.SortBy.DATE to FileLoader.SortOrder.ASC
                    4 -> FileLoader.SortBy.SIZE to FileLoader.SortOrder.ASC
                    5 -> FileLoader.SortBy.SIZE to FileLoader.SortOrder.DESC
                    else -> FileLoader.SortBy.NAME to FileLoader.SortOrder.ASC
                }
                sortBy    = by
                sortOrder = ord
                currentDir?.let { loadDirectory(it) }
            }
            .show()
    }

    // ── Search ────────────────────────────────────────────────────────────────

    private fun filterList(query: String) {
        val dir = currentDir ?: return
        if (query.isEmpty()) { loadDirectory(dir); return }
        val lower = query.lowercase(Locale.getDefault())
        loader.load(dir, showHidden, sortBy, sortOrder) { result ->
            result.onSuccess { all ->
                val filtered = all.filter { it.name.lowercase(Locale.getDefault()).contains(lower) }
                if (isActivityResumed) {
                    adapter.submitList(filtered)
                    updateEmptyState()
                }
            }
        }
    }

    // ── Selection mode ────────────────────────────────────────────────────────

    private fun exitSelectionMode() {
        adapter.exitSelectionMode()
        supportActionBar?.title =
            currentDir?.name ?: getString(R.string.app_name)
        invalidateOptionsMenu()
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private fun showLoading(show: Boolean) {
        loadingProgress.visibility = if (show) View.VISIBLE else View.GONE
        if (show) layoutEmpty.visibility = View.GONE
    }

    private fun updateEmptyState() {
        layoutEmpty.visibility =
            if (adapter.itemCount == 0) View.VISIBLE else View.GONE
    }

    private fun showPermissionScreen() {
        layoutPermission.visibility = View.VISIBLE
        recyclerView.visibility     = View.GONE
        breadcrumbGroup.visibility  = View.GONE
        tvPermissionMsg.text        = StorageHelper.permissionRationale()
        layoutPermission
            .findViewById<View>(R.id.btn_grant_permission)
            ?.setOnClickListener { StorageHelper.requestStorageAccess(this) }
    }

    private fun hidePermissionScreen() {
        layoutPermission.visibility = View.GONE
        recyclerView.visibility     = View.VISIBLE
        breadcrumbGroup.visibility  = View.VISIBLE
    }

    private fun showPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.permission_required_title)
            .setMessage(StorageHelper.permissionRationale())
            .setPositiveButton(R.string.btn_grant) { _, _ ->
                StorageHelper.requestStorageAccess(this)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showError(msg: String) {
        Snackbar.make(recyclerView, msg, Snackbar.LENGTH_LONG).show()
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    /** Build a content:// URI via FileProvider, or show an error and return null. */
    private fun contentUriFor(file: File): Uri? = runCatching {
        FileProvider.getUriForFile(this, authority, file)
    }.getOrElse {
        showError(getString(R.string.error_cannot_open))
        null
    }

    private fun mimeTypeOf(file: File): String {
        val ext = file.extension.lowercase(Locale.ROOT)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
    }

    private fun deleteRecursive(file: File): Boolean {
        if (file.isDirectory) {
            file.listFiles()?.forEach { if (!deleteRecursive(it)) return false }
        }
        return file.delete()
    }

    /** Walk getExternalFilesDirs() to find a secondary (SD card) volume. */
    private fun findSdCard(): File? =
        getExternalFilesDirs(null)
            .filterNotNull()
            .filterNot { it.absolutePath.contains("/emulated/") }
            .mapNotNull { dir ->
                var root: File = dir
                while (root.parentFile != null
                    && root.parentFile?.absolutePath != "/"
                ) root = root.parentFile!!
                root.takeIf { it.exists() && it.canRead() }
            }
            .firstOrNull()

    // ── Saved-state keys ──────────────────────────────────────────────────────

    companion object {
        private const val KEY_DIR        = "current_dir"
        private const val KEY_GRID       = "grid_mode"
        private const val KEY_SORT_BY    = "sort_by"
        private const val KEY_SORT_ORDER = "sort_order"
    }

    // Type alias to shorten repeated full qualification
    private typealias SortBy    = FileLoader.SortBy
    private typealias SortOrder = FileLoader.SortOrder
}
