package com.yourapp.yamahaarranger.ui

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import java.io.InputStream
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** [isRoot] marks the configured style folder itself, shown when .STY files sit directly in it. */
data class StyleFolder(val uri: Uri, val name: String, val styleCount: Int, val isRoot: Boolean = false)

@Singleton
class ContentResolverProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val storagePrefs: StoragePrefs
) {
    // Folders are user-configurable (Settings > Storage). Resolved on every
    // access so a changed setting or a newly granted permission takes effect
    // immediately.
    private val sf2RootDir: File
        get() = storagePrefs.sf2Dir()

    private val styleRootDir: File
        get() = storagePrefs.styleDir()

    fun storageSettings(): StorageSettings = storagePrefs.snapshot()

    fun hasPublicStorageAccess(): Boolean = storagePrefs.hasPublicStorageAccess()

    /** Pass null to go back to the default folder. Returns false if the folder is unusable. */
    fun setStyleDir(path: String?): Boolean {
        if (!path.isNullOrBlank() && !isUsableDir(File(path))) return false
        storagePrefs.setCustomStyleDir(path)
        ensureStyleFolder()
        return true
    }

    fun setSf2Dir(path: String?): Boolean {
        if (!path.isNullOrBlank() && !isUsableDir(File(path))) return false
        storagePrefs.setCustomSf2Dir(path)
        ensureSoundFontFolder()
        return true
    }

    private fun isUsableDir(dir: File): Boolean = try {
        (dir.isDirectory || dir.mkdirs()) && dir.canRead()
    } catch (_: Exception) {
        false
    }

    fun ensureStyleFolder() {
        try {
            val dir = styleRootDir
            if (!dir.isDirectory && !dir.mkdirs()) {
                Timber.w("Could not create style folder: ${dir.absolutePath}")
            }
        } catch (e: Exception) {
            Timber.w(e, "Could not create style folder")
        }
    }

    /**
     * Scan YamahaArranger/Styles without FileTreeWalk's silent SKIP behavior.
     *
     * On Android 11+ a directory can be visible while child enumeration is
     * blocked by scoped-storage permissions. FileTreeWalk silently skips an
     * unreadable directory, which made the UI report "0" with no useful clue.
     * An explicit stack lets us log exactly where enumeration stops.
     */
    /** Yamaha style collections may use .STY, .PRS and .SST containers. */
    private fun isStyleFile(file: File): Boolean {
        if (!file.isFile) return false
        return when (file.extension.lowercase(Locale.ROOT)) {
            "sty", "prs", "sst" -> true
            else -> false
        }
    }

    private fun styleFilesUnder(dir: File): List<File> {
        if (!dir.isDirectory) {
            Timber.w("🎼 Style scan: not a directory: \u0024{dir.absolutePath}")
            return emptyList()
        }

        val result = mutableListOf<File>()
        val pending = ArrayDeque<File>()
        pending.addLast(dir)

        while (pending.isNotEmpty()) {
            val current = pending.removeLast()
            val children = try {
                current.listFiles()
            } catch (e: SecurityException) {
                Timber.e(e, "🎼 Style scan denied: \u0024{current.absolutePath}")
                null
            }

            if (children == null) {
                Timber.w(
                    "🎼 Style scan cannot enumerate: path=\u0024{current.absolutePath} " +
                        "exists=\u0024{current.exists()} dir=\u0024{current.isDirectory} canRead=\u0024{current.canRead()}"
                )
                continue
            }

            for (child in children) {
                if (child.isDirectory) {
                    pending.addLast(child)
                } else if (child.isFile && isStyleFile(child)) {
                    result += child
                }
            }
        }

        if (result.isNotEmpty()) return result

        // Fallback for devices where direct File.listFiles() cannot enumerate
        // shared-storage children reliably. MANAGE_EXTERNAL_STORAGE also
        // permits access to MediaStore.Files.
        // NOTE: Environment.isExternalStorageManager() only exists on Android 11+.
        // Calling it on Android 10 (API 29) threw NoSuchMethodError, so the
        // access check goes through StoragePrefs which is API-level safe.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            storagePrefs.hasPublicStorageAccess()
        ) {
            val prefix = styleRootDir.absolutePath.trimEnd('/') + "/"
            val mediaUri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
            val projection = arrayOf(
                MediaStore.Files.FileColumns.DATA,
                MediaStore.Files.FileColumns.DISPLAY_NAME
            )
            val mediaResult = mutableListOf<File>()

            try {
                context.contentResolver.query(
                    mediaUri,
                    projection,
                    "(${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ? OR " +
                        "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ? OR " +
                        "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?)",
                    arrayOf("%.sty", "%.prs", "%.sst"),
                    null
                )?.use { cursor ->
                    val dataIndex = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA)
                    while (cursor.moveToNext()) {
                        if (dataIndex < 0 || cursor.isNull(dataIndex)) continue
                        val path = cursor.getString(dataIndex)
                        val file = File(path)
                        if (path.startsWith(prefix) &&
                            file.absolutePath.startsWith(dir.absolutePath.trimEnd('/') + "/") &&
                            file.isFile &&
                            isStyleFile(file)
                        ) {
                            mediaResult += file
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "🎼 MediaStore Style fallback failed for \u0024{dir.absolutePath}")
            }

            if (mediaResult.isNotEmpty()) {
                Timber.i(
                    "🎼 MediaStore Style fallback found \u0024{mediaResult.size} files in \u0024{dir.absolutePath}"
                )
                return mediaResult.distinctBy { it.absolutePath }
            }
        }

        return result
    }

    fun listStyleFolders(): List<StyleFolder> {
        ensureStyleFolder()
        val allFiles = storagePrefs.hasPublicStorageAccess()
        val children = styleRootDir.listFiles()
        Timber.i(
            "🎼 Style root: path=\u0024{styleRootDir.absolutePath} exists=\u0024{styleRootDir.exists()} " +
                "dir=\u0024{styleRootDir.isDirectory} canRead=\u0024{styleRootDir.canRead()} " +
                "allFiles=\u0024allFiles children=\u0024{children?.size ?: -1}"
        )
        val folders = children
            ?.filter { it.isDirectory }
            ?.sortedBy { it.name.lowercase() }
            ?.map { dir ->
                val count = styleFilesUnder(dir).size
                Timber.i(
                    "🎼 Style category: \u0024{dir.name} path=\u0024{dir.absolutePath} " +
                        "canRead=\u0024{dir.canRead()} styCount=\u0024count"
                )
                StyleFolder(
                    uri = Uri.fromFile(dir),
                    name = dir.name,
                    styleCount = count
                )
            }
            ?: emptyList()

        // Styles that sit directly in the configured folder (no sub-folders) would
        // otherwise never be shown by the folder-first Style Select page.
        val rootStyles = styleRootDir.listFiles { f -> isStyleFile(f) }?.size ?: 0
        return if (rootStyles > 0) {
            listOf(
                StyleFolder(
                    uri = Uri.fromFile(styleRootDir),
                    name = styleRootDir.name.ifBlank { "STYLES" },
                    styleCount = rootStyles,
                    isRoot = true
                )
            ) + folders
        } else {
            folders
        }
    }

    fun listAllStylesInFolders(): List<Pair<Uri, String>> {
        ensureStyleFolder()
        val rootFiles = styleRootDir.listFiles { f -> isStyleFile(f) }?.toList() ?: emptyList()
        val nested = styleRootDir.listFiles()
            ?.filter { it.isDirectory }
            ?.flatMap { styleFilesUnder(it) }
            ?: emptyList()
        return (rootFiles + nested)
            .sortedBy { it.absolutePath.lowercase() }
            .map { Uri.fromFile(it) to it.name }
    }

    fun listStyles(): List<Pair<Uri, String>> {
        ensureStyleFolder()
        return styleRootDir.listFiles { f -> f.isFile && isStyleFile(f) }
            ?.sortedBy { it.name.lowercase() }
            ?.map { Uri.fromFile(it) to it.name }
            ?: emptyList()
    }

    fun listStylesInFolder(folderUri: Uri): List<Pair<Uri, String>> {
        return try {
            val rawPath = folderUri.path ?: return emptyList()
            val path = Uri.decode(rawPath)
            var dir = File(path)

            // The configured root itself: only the styles directly inside it
            // (sub-folders are listed as their own folders).
            if (dir.canonicalPath == styleRootDir.canonicalPath) {
                val direct = dir.listFiles { f -> isStyleFile(f) }
                    ?.sortedBy { it.name.lowercase() }
                    ?: emptyList()
                return direct.map { Uri.fromFile(it) to it.name }
            }

            // Uri paths can be encoded differently by Android/Compose. If the
            // URI-derived File does not enumerate, resolve the folder again
            // from the known YamahaArranger/Styles root by canonical path/name.
            var files = styleFilesUnder(dir)
            if (files.isEmpty()) {
                val requestedName = dir.name
                styleRootDir.listFiles()
                    ?.firstOrNull { it.isDirectory && it.name == requestedName }
                    ?.let {
                        dir = it
                        files = styleFilesUnder(it)
                    }
            }
            files = files.sortedBy { it.name.lowercase() }
            Timber.i(
                "🎼 Style folder open: uri=\u0024folderUri path=\u0024{dir.absolutePath} " +
                    "exists=\u0024{dir.exists()} dir=\u0024{dir.isDirectory} canRead=\u0024{dir.canRead()} " +
                    "styCount=\u0024{files.size}"
            )
            files.map { Uri.fromFile(it) to it.name }
        } catch (e: Exception) {
            Timber.e(e, "Failed listing styles in $folderUri")
            emptyList()
        }
    }

    fun readBytes(uri: Uri): ByteArray? = try {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    } catch (e: Exception) {
        Timber.e(e, "Failed reading $uri")
        null
    }

    fun fileName(uri: Uri): String? = try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else null
        }
    } catch (e: Exception) {
        Timber.e(e, "Failed querying name for $uri")
        null
    }

    fun openInputStream(uri: Uri): InputStream? =
        context.contentResolver.openInputStream(uri)

    fun getFilesDir(): File = context.filesDir

    fun getSoundFontLocation(): String = sf2RootDir.absolutePath

    fun getStyleLocation(): String = styleRootDir.absolutePath

    /** Creates the SoundFont folder (default or user-chosen). Never throws. */
    fun ensureSoundFontFolder() {
        try {
            val dir = sf2RootDir
            if (!dir.isDirectory && !dir.mkdirs()) {
                Timber.w("Could not create SF2 folder: ${dir.absolutePath}")
                return
            }
            // Only drop the hint file into the app's own default folder, never
            // into a folder the user pointed us at (it may be their SF2 library).
            if (storagePrefs.customSf2Dir() == null) {
                File(dir, "PUT_SF2_FILES_HERE.txt").takeIf { !it.exists() }?.writeText(
                    "Place your SoundFont .sf2 files in this folder.\n"
                )
            }
        } catch (e: Exception) {
            Timber.w(e, "Could not create SF2 folder")
        }
    }

    /** True when the URI is a file directly inside the configured SoundFont folder. */
    fun isSoundFontInManagedFolder(uri: Uri): Boolean {
        return try {
            if (uri.scheme != "file") return false
            val parent = File(uri.path ?: return false).canonicalFile.parentFile?.canonicalPath
            parent != null && parent == sf2RootDir.canonicalPath
        } catch (_: Exception) { false }
    }

    private fun querySize(uri: Uri): Long = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L } ?: -1L
    } catch (_: Exception) {
        -1L
    }

    /**
     * Copies a picked SoundFont into the SF2 folder. The copy goes to a ".part"
     * file first and is only renamed when complete, so a full disk or an
     * interrupted copy can never leave a truncated .sf2 that later fails to load.
     */
    @Synchronized
    fun saveSoundFont(uri: Uri, displayName: String): Uri? {
        val safeName = displayName.substringAfterLast('/').ifBlank { "font.sf2" }
            .let { if (it.lowercase().endsWith(".sf2")) it else it + ".sf2" }
        ensureSoundFontFolder()
        val dir = sf2RootDir
        if (!dir.isDirectory) {
            DebugLog.add("❌ SF2 folder unavailable: ${dir.absolutePath} - open SETTINGS and grant storage access or choose another folder")
            return null
        }
        val dest = File(dir, safeName)
        val part = File(dir, "$safeName.part")
        return try {
            val size = querySize(uri)
            if (size > 0 && dir.usableSpace < size + 8L * 1024 * 1024) {
                DebugLog.add("❌ Not enough free space in ${dir.absolutePath}: need ${(size shr 20) + 8} MB, free ${dir.usableSpace shr 20} MB")
                return null
            }
            val input = context.contentResolver.openInputStream(uri)
            if (input == null) {
                DebugLog.add("❌ Cannot open the picked SF2 file")
                return null
            }
            input.use { src -> part.outputStream().use { out -> src.copyTo(out, 1 shl 20) } }
            if (part.length() == 0L || (size > 0 && part.length() != size)) {
                DebugLog.add("❌ SF2 copy incomplete (${part.length()} of $size bytes)")
                part.delete()
                return null
            }
            if (dest.exists()) dest.delete()
            if (!part.renameTo(dest)) {
                DebugLog.add("❌ Cannot finalize SF2 file in ${dir.absolutePath}")
                part.delete()
                return null
            }
            Uri.fromFile(dest)
        } catch (e: Exception) {
            part.delete()
            DebugLog.add("❌ Failed storing SF2: ${e.javaClass.simpleName}: ${e.message}")
            Timber.e(e, "Failed storing SF2 in ${dir.absolutePath}")
            null
        }
    }

    private fun isSoundFontFile(f: File): Boolean =
        f.isFile && f.extension.equals("sf2", true) &&
            !f.name.endsWith(NORMALIZED_SUFFIX, ignoreCase = true)

    /**
     * Finds an SF2 in the configured SoundFont folder.
     * Returns the Uri and display name.
     */
    fun findSoundFont(displayName: String? = null): Pair<Uri, String>? {
        ensureSoundFontFolder()
        val wantedName = displayName?.trim()
        val file = if (wantedName != null) File(sf2RootDir, wantedName)
        else sf2RootDir.listFiles { f -> isSoundFontFile(f) }
            ?.sortedByDescending { it.lastModified() }?.firstOrNull()
        return file?.takeIf { it.exists() && it.length() > 0 }?.let { Uri.fromFile(it) to it.name }
    }

    fun listSoundFonts(): List<Pair<Uri, String>> {
        ensureSoundFontFolder()
        val dir = sf2RootDir
        val files = dir.listFiles { f -> isSoundFontFile(f) }
        if (files == null) {
            DebugLog.add("❌ Cannot list SF2 folder ${dir.absolutePath} (exists=${dir.exists()} canRead=${dir.canRead()}) - check storage access in SETTINGS")
        }
        return files
            ?.filter { it.length() > 0 }
            ?.sortedBy { it.name.lowercase() }
            ?.map { Uri.fromFile(it) to it.name }
            ?: emptyList()
    }

    /** Directory the native loader uses for generated (bank-normalized) SF2 working copies. */
    fun soundFontWorkDir(): File = File(context.cacheDir, "sf2work").apply { mkdirs() }

    /**
     * Older builds copied every SF2 into cache/sf2 on each load (plus a second
     * normalized copy), which could fill the device storage and make later
     * loads fail. Remove that leftover data once.
     */
    fun cleanupLegacySoundFontCache() {
        try {
            val legacy = File(context.cacheDir, "sf2")
            if (legacy.isDirectory) {
                val freed = legacy.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
                legacy.deleteRecursively()
                DebugLog.add("🧹 Removed legacy SF2 cache (${freed shr 20} MB)")
            }
        } catch (e: Exception) {
            Timber.w(e, "Legacy SF2 cache cleanup failed")
        }
    }

    /**
     * Returns a readable file for the SoundFont. Files that already live on
     * storage are loaded in place (no copy). Only non-file URIs (content://)
     * are streamed into cache, with a free-space check and without leaving a
     * truncated copy behind.
     */
    fun prepareSoundFontFile(uri: Uri, displayName: String): File? {
        if (uri.scheme == "file") {
            val f = File(uri.path ?: return null)
            if (f.isFile && f.canRead() && f.length() > 0) return f
            DebugLog.add("❌ SF2 not readable: ${f.absolutePath} (exists=${f.exists()} canRead=${f.canRead()} size=${f.length()}) - check storage access in SETTINGS")
            return null
        }
        val dir = File(context.cacheDir, "sf2-import").apply { mkdirs() }
        val safeName = displayName.substringAfterLast('/').ifBlank { "font.sf2" }
        val dest = File(dir, safeName)
        val part = File(dir, "$safeName.part")
        return try {
            val size = querySize(uri)
            if (size > 0 && dir.usableSpace < size + 8L * 1024 * 1024) {
                DebugLog.add("❌ Not enough cache space for $safeName: need ${(size shr 20) + 8} MB, free ${dir.usableSpace shr 20} MB")
                return null
            }
            val input = openInputStream(uri) ?: return null
            input.use { src -> part.outputStream().use { out -> src.copyTo(out, 1 shl 20) } }
            if (part.length() == 0L || (size > 0 && part.length() != size)) {
                part.delete()
                return null
            }
            if (dest.exists()) dest.delete()
            if (part.renameTo(dest)) dest else null
        } catch (e: Exception) {
            part.delete()
            DebugLog.add("❌ Failed caching SF2: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private companion object {
        const val NORMALIZED_SUFFIX = ".bassmidi-normalized.sf2"
    }
}
