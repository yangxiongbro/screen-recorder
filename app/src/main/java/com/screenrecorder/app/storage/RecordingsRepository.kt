package com.screenrecorder.app.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.screenrecorder.app.core.RecorderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** One finished recording, wherever it lives. */
data class RecordingItem(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val dateMillis: Long,
    val durationMillis: Long,
    val source: Source,
) {
    enum class Source { MEDIA_STORE, SAF, PRIVATE }
}

/** Lists and deletes recordings created by the app. */
class RecordingsRepository(private val context: Context) {

    suspend fun load(settings: RecorderSettings): List<RecordingItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<RecordingItem>()
        runCatching { items += queryMediaStore() }
        settings.customFolderUri?.takeIf { it.isNotBlank() }?.let { tree ->
            runCatching { items += queryTree(Uri.parse(tree)) }
        }
        runCatching { items += queryPrivateDirectory() }
        items.sortedByDescending { it.dateMillis }
    }

    suspend fun delete(item: RecordingItem): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            when (item.source) {
                RecordingItem.Source.MEDIA_STORE ->
                    context.contentResolver.delete(item.uri, null, null) > 0

                RecordingItem.Source.SAF ->
                    DocumentsContract.deleteDocument(context.contentResolver, item.uri)

                RecordingItem.Source.PRIVATE ->
                    item.uri.path?.let { File(it).delete() } ?: false
            }
        }.getOrDefault(false)
    }

    fun playIntent(item: RecordingItem): Intent =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(item.uri, "video/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    fun shareIntent(item: RecordingItem): Intent =
        Intent(Intent.ACTION_SEND)
            .setType("video/mp4")
            .putExtra(Intent.EXTRA_STREAM, item.uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    private fun queryMediaStore(): List<RecordingItem> {
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.RELATIVE_PATH,
        )
        val selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("%$RELATIVE_PATH%")
        val items = mutableListOf<RecordingItem>()
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            args,
            "${MediaStore.Video.Media.DATE_ADDED} DESC",
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                items += RecordingItem(
                    uri = Uri.withAppendedPath(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        id.toString(),
                    ),
                    name = cursor.getString(nameColumn) ?: "video_$id.mp4",
                    sizeBytes = cursor.getLong(sizeColumn),
                    dateMillis = cursor.getLong(dateColumn) * 1000L,
                    durationMillis = cursor.getLong(durationColumn),
                    source = RecordingItem.Source.MEDIA_STORE,
                )
            }
        }
        return items
    }

    private fun queryTree(treeUri: Uri): List<RecordingItem> {
        val tree = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        return tree.listFiles()
            .filter { it.isFile && it.name?.endsWith(".mp4", ignoreCase = true) == true }
            .map { document ->
                RecordingItem(
                    uri = document.uri,
                    name = document.name ?: "video.mp4",
                    sizeBytes = document.length(),
                    dateMillis = document.lastModified(),
                    durationMillis = 0L,
                    source = RecordingItem.Source.SAF,
                )
            }
    }

    private fun queryPrivateDirectory(): List<RecordingItem> {
        val directory = File(
            context.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES),
            "ScreenRecorder",
        )
        if (!directory.isDirectory) return emptyList()
        return directory.listFiles()
            ?.filter { it.isFile && it.extension.equals("mp4", ignoreCase = true) }
            ?.map { file ->
                RecordingItem(
                    uri = Uri.fromFile(file),
                    name = file.name,
                    sizeBytes = file.length(),
                    dateMillis = file.lastModified(),
                    durationMillis = 0L,
                    source = RecordingItem.Source.PRIVATE,
                )
            }
            ?: emptyList()
    }

    private companion object {
        const val RELATIVE_PATH = "Movies/ScreenRecorder"
    }
}
