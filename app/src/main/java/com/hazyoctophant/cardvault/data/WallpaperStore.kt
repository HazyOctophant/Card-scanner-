package com.hazyoctophant.cardvault.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/** Photos the user picked for the app background, copied into private storage so they stay available. */
class WallpaperStore(private val context: Context) {
    private val dir = File(context.filesDir, "wallpapers").apply { mkdirs() }
    private val _files = MutableStateFlow(list())
    val files: StateFlow<List<File>> = _files.asStateFlow()

    private fun list() = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()

    suspend fun add(uris: List<Uri>) = withContext(Dispatchers.IO) {
        uris.forEach { uri ->
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    File(dir, "wall_${System.currentTimeMillis()}_${(0..9999).random()}.jpg").outputStream().use { input.copyTo(it) }
                }
            }
        }
        _files.value = list()
    }

    suspend fun remove(file: File) = withContext(Dispatchers.IO) {
        file.delete()
        _files.value = list()
    }
}
