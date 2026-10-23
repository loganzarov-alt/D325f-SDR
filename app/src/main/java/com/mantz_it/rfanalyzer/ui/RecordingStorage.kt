package com.mantz_it.rfanalyzer.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Recording location policy for the old-device RF Decoder build.
 *
 * Preference order:
 * 1) visible /recordings on a mounted removable SD card (Android 7/8 with legacy permission),
 * 2) app-specific directory on removable SD,
 * 3) app-specific primary external storage,
 * 4) internal app storage.
 */
object RecordingStorage {
    private const val TAG = "RecordingStorage"
    const val OUTPUT_BUFFER_BYTES = 8 * 1024 * 1024

    data class OpenedRecording(
        val file: File,
        val output: BufferedOutputStream,
    )

    fun openRecording(context: Context, filename: String): OpenedRecording? {
        for (directory in candidateDirectories(context)) {
            try {
                if (!directory.exists() && !directory.mkdirs()) {
                    Log.w(TAG, "Cannot create ${directory.absolutePath}")
                    continue
                }
                if (!directory.isDirectory || !directory.canWrite()) {
                    Log.w(TAG, "Not writable: ${directory.absolutePath}")
                    continue
                }
                val file = File(directory, filename)
                val output = BufferedOutputStream(
                    FileOutputStream(file, false),
                    OUTPUT_BUFFER_BYTES
                )
                Log.i(TAG, "Recording target: ${file.absolutePath}")
                return OpenedRecording(file, output)
            } catch (e: Exception) {
                Log.w(TAG, "Cannot use ${directory.absolutePath}: ${e.message}")
            }
        }
        return null
    }

    fun preferredDirectory(context: Context): File =
        candidateDirectories(context).firstOrNull() ?: File(context.filesDir, RECORDINGS_DIRECTORY)

    fun candidateDirectories(context: Context): List<File> {
        val result = LinkedHashMap<String, File>()
        fun add(file: File?) {
            if (file == null) return
            val key = try { file.canonicalPath } catch (_: Exception) { file.absolutePath }
            result.putIfAbsent(key, file)
        }

        val externalDirs = ContextCompat.getExternalFilesDirs(context, null)
            .filterNotNull()
            .filter { Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED }
        val removableAppDir = externalDirs.firstOrNull {
            try { Environment.isExternalStorageRemovable(it) } catch (_: Exception) { false }
        }

        // Android 7/8 can still write directly to a removable SD root after the
        // legacy storage permission is granted. Put recordings in /recordings.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        ) {
            add(removableAppDir?.let(::findStorageRoot)?.let { File(it, RECORDINGS_DIRECTORY) })
        }

        add(removableAppDir?.let { File(it, RECORDINGS_DIRECTORY) })
        add(context.getExternalFilesDir(null)?.let { File(it, RECORDINGS_DIRECTORY) })
        add(File(context.filesDir, RECORDINGS_DIRECTORY))
        return result.values.toList()
    }

    private fun findStorageRoot(appExternalDir: File): File? {
        var current: File? = appExternalDir
        while (current != null && current.name != "Android") current = current.parentFile
        return current?.parentFile
    }
}
