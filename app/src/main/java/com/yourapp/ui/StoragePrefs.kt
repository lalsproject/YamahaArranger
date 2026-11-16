package com.yourapp.yamahaarranger.ui

import android.content.Context
import android.content.pm.PackageManager
import android.Manifest
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Snapshot of where the app currently reads Style and SoundFont files from. */
data class StorageSettings(
    val styleDir: String,
    val sf2Dir: String,
    val styleIsCustom: Boolean,
    val sf2IsCustom: Boolean,
    val styleDirReadable: Boolean,
    val sf2DirReadable: Boolean,
    /** MANAGE_EXTERNAL_STORAGE (Android 11+) or legacy storage permission (Android 8-10). */
    val hasPublicStorageAccess: Boolean,
    /** True when no custom folder is set and the app had to fall back to its private external folder. */
    val usingAppFolderFallback: Boolean
)

/**
 * User-chosen folders for Style files and SoundFonts.
 *
 * Resolution order for each folder:
 *  1. the folder the user picked in Settings (custom);
 *  2. /storage/emulated/0/YamahaArranger/{Styles,SF2} when the app has storage access;
 *  3. the app's own external folder (Android/data/<package>/files/...), which always
 *     works without any permission, so the app never ends up with "no folder at all".
 */
@Singleton
class StoragePrefs @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val defaultRoot: File
        get() = File(Environment.getExternalStorageDirectory(), "YamahaArranger")

    private val appFolderRoot: File
        get() = context.getExternalFilesDir(null) ?: context.filesDir

    fun customStyleDir(): String? = prefs.getString(KEY_STYLE_DIR, null)?.takeIf { it.isNotBlank() }
    fun customSf2Dir(): String? = prefs.getString(KEY_SF2_DIR, null)?.takeIf { it.isNotBlank() }

    fun setCustomStyleDir(path: String?) = put(KEY_STYLE_DIR, path)
    fun setCustomSf2Dir(path: String?) = put(KEY_SF2_DIR, path)

    private fun put(key: String, path: String?) {
        prefs.edit().apply {
            if (path.isNullOrBlank()) remove(key) else putString(key, path.trimEnd('/').ifEmpty { "/" })
        }.apply()
    }

    fun hasPublicStorageAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    fun styleDir(): File = resolve(customStyleDir(), "Styles")
    fun sf2Dir(): File = resolve(customSf2Dir(), "SF2")

    private fun resolve(custom: String?, sub: String): File {
        if (custom != null) return File(custom)
        return if (hasPublicStorageAccess()) File(defaultRoot, sub) else File(appFolderRoot, sub)
    }

    fun snapshot(): StorageSettings {
        val style = styleDir()
        val sf2 = sf2Dir()
        val access = hasPublicStorageAccess()
        return StorageSettings(
            styleDir = style.absolutePath,
            sf2Dir = sf2.absolutePath,
            styleIsCustom = customStyleDir() != null,
            sf2IsCustom = customSf2Dir() != null,
            styleDirReadable = style.isDirectory && style.canRead(),
            sf2DirReadable = sf2.isDirectory && sf2.canRead(),
            hasPublicStorageAccess = access,
            usingAppFolderFallback = !access && (customStyleDir() == null || customSf2Dir() == null)
        )
    }

    companion object {
        private const val PREFS_NAME = "yamaha_arranger_storage"
        private const val KEY_STYLE_DIR = "style_dir"
        private const val KEY_SF2_DIR = "sf2_dir"

        /**
         * Converts a folder picked with ACTION_OPEN_DOCUMENT_TREE into a real
         * file-system path. The app reads files with java.io.File (needed by the
         * native BASSMIDI loader), so a content:// URI alone is not enough.
         * Returns null for providers that have no plain path (cloud, "Documents" home...).
         */
        fun treeUriToPath(treeUri: Uri): String? = try {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val volume = docId.substringBefore(':')
            val relative = docId.substringAfter(':', "")
            when {
                volume.equals("primary", ignoreCase = true) ->
                    File(Environment.getExternalStorageDirectory(), relative).absolutePath
                volume.equals("raw", ignoreCase = true) && relative.startsWith("/") -> relative
                volume.equals("home", ignoreCase = true) -> null
                volume.matches(Regex("[A-Za-z0-9-]+")) -> File("/storage/$volume", relative).absolutePath
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }
}
