package com.screenrecorder.app.storage

import android.content.ContentValues
import android.content.Context
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.screenrecorder.app.core.RecorderSettings
import com.screenrecorder.app.core.SaveLocation
import java.io.File
import java.io.IOException

/**
 * A single output file. Depending on the configured [SaveLocation] it is backed by MediaStore,
 * a SAF document or a plain file in the app specific external directory.
 */
class RecordingOutput internal constructor(
    private val context: Context,
    val displayName: String,
    val uri: Uri?,
    private val descriptor: ParcelFileDescriptor?,
    private val file: File?,
    private val kind: Kind,
) {

    enum class Kind { MEDIA_STORE, SAF, PRIVATE }

    val fileDescriptor: ParcelFileDescriptor?
        get() = descriptor

    val absolutePath: String?
        get() = file?.absolutePath

    fun createMuxer(): MediaMuxer = if (descriptor != null) {
        MediaMuxer(descriptor.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    } else {
        MediaMuxer(
            requireNotNull(file) { "Output must have a descriptor or a file" }.absolutePath,
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
        )
    }

    /** Publishes the file (clears `IS_PENDING` for MediaStore) after a successful recording. */
    fun commit() {
        closeDescriptor()
        val mediaUri = uri
        if (kind == Kind.MEDIA_STORE && mediaUri != null) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }
            runCatching { context.contentResolver.update(mediaUri, values, null, null) }
        }
    }

    /** Removes a broken or empty file. */
    fun discard() {
        closeDescriptor()
        runCatching {
            when (kind) {
                Kind.MEDIA_STORE -> uri?.let { context.contentResolver.delete(it, null, null) }
                Kind.SAF -> uri?.let { DocumentsContract.deleteDocument(context.contentResolver, it) }
                Kind.PRIVATE -> file?.delete()
            }
        }
    }

    private fun closeDescriptor() {
        runCatching { descriptor?.close() }
    }
}

/**
 * Creates [RecordingOutput] instances following [RecorderSettings.saveLocation].
 */
class RecordingStorage(
    private val context: Context,
    private val settings: RecorderSettings,
) {

    fun create(sessionStamp: String, segmentIndex: Int): RecordingOutput {
        val baseName = buildName(sessionStamp, segmentIndex)
        return when (settings.saveLocation) {
            SaveLocation.MEDIA_STORE -> createInMediaStore(baseName)

            SaveLocation.CUSTOM_FOLDER -> {
                val treeUri = settings.customFolderUri?.takeIf { it.isNotBlank() }?.let(Uri::parse)
                if (treeUri == null) {
                    createInMediaStore(baseName)
                } else {
                    runCatching { createInTree(treeUri, baseName) }
                        .getOrElse {
                            Log.w(TAG, "无法写入所选文件夹，回退到相册", it)
                            createInMediaStore(baseName)
                        }
                }
            }

            SaveLocation.APP_PRIVATE -> createInPrivateDirectory(baseName)
        }
    }

    private fun buildName(sessionStamp: String, segmentIndex: Int): String {
        val prefix = settings.fileNamePrefix
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .ifBlank { "ScreenRecorder" }
        val suffix = if (segmentIndex > 0) "_part${segmentIndex + 1}" else ""
        return "${prefix}_${sessionStamp}$suffix"
    }

    private fun createInMediaStore(baseName: String): RecordingOutput {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "$baseName.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("无法在相册中创建文件")
        val descriptor = resolver.openFileDescriptor(uri, "rw")
            ?: throw IOException("无法打开相册文件")
        return RecordingOutput(
            context = context,
            displayName = "$baseName.mp4",
            uri = uri,
            descriptor = descriptor,
            file = null,
            kind = RecordingOutput.Kind.MEDIA_STORE,
        )
    }

    private fun createInTree(treeUri: Uri, baseName: String): RecordingOutput {
        val resolver = context.contentResolver
        val tree = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IOException("无法访问所选文件夹")
        if (!tree.canWrite()) {
            throw IOException("所选文件夹不可写")
        }
        val document = tree.createFile(MIME_TYPE, baseName)
            ?: throw IOException("无法在所选文件夹中创建文件")
        val descriptor = resolver.openFileDescriptor(document.uri, "rw")
            ?: throw IOException("无法打开所选文件夹中的文件")
        return RecordingOutput(
            context = context,
            displayName = document.name ?: "$baseName.mp4",
            uri = document.uri,
            descriptor = descriptor,
            file = null,
            kind = RecordingOutput.Kind.SAF,
        )
    }

    private fun createInPrivateDirectory(baseName: String): RecordingOutput {
        val directory = File(
            context.getExternalFilesDir(Environment.DIRECTORY_MOVIES),
            "ScreenRecorder",
        )
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("无法创建应用专属目录")
        }
        val file = File(directory, "$baseName.mp4")
        return RecordingOutput(
            context = context,
            displayName = file.name,
            uri = null,
            descriptor = null,
            file = file,
            kind = RecordingOutput.Kind.PRIVATE,
        )
    }

    private companion object {
        const val TAG = "RecordingStorage"
        const val MIME_TYPE = "video/mp4"
        const val RELATIVE_PATH = "Movies/ScreenRecorder"
    }
}
