package com.afitech.sosmedtoolkit.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.afitech.sosmedtoolkit.data.database.DownloadHistoryDao
import com.afitech.sosmedtoolkit.data.model.DownloadHistory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

object Downloader {

    // Mengunduh dari URL dan simpan ke MediaStore, source bisa tiktok/instagram/youtube/whatsapp
    suspend fun downloadFile(
        context: Context,
        fileUrl: String,
        fileName: String,
        mimeType: String,
        onProgressUpdate: (progress: Int, downloadedBytes: Long, totalBytes: Long) -> Unit,
        downloadHistoryDao: DownloadHistoryDao,
        source: String = "tiktok"
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL(fileUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.doInput = true
            connection.connect()

            val fileSize = connection.contentLengthLong.takeIf { it > 0 } ?: -1L
            val inputStream = connection.inputStream

            val uniqueFileName = generateUniqueFileName(context, fileName, mimeType, source)

            val uri = saveToMediaStore(
                context = context,
                inputStream = inputStream,
                fileName = uniqueFileName,
                mimeType = mimeType,
                fileSize = fileSize,
                onProgressUpdate = { progress, downloadedBytes, totalBytes ->
                    onProgressUpdate(progress, downloadedBytes, totalBytes)
                },
                source = source
            )

            inputStream.close()
            connection.disconnect()

            if (uri != null) {
                val downloadHistory = DownloadHistory(
                    fileName = uniqueFileName,
                    filePath = uri.toString(),
                    fileType = when {
                        mimeType.startsWith("video") -> "Video"
                        mimeType.startsWith("audio") -> "Audio"
                        mimeType.startsWith("image") -> "Image"
                        else -> "Other"
                    },
                    downloadDate = System.currentTimeMillis()
                )
                downloadHistoryDao.insertDownload(downloadHistory)
                Log.d("Downloader", "Riwayat unduhan berhasil disimpan: $downloadHistory")
                true
            } else {
                Log.e("Downloader", "Gagal menyimpan file ke MediaStore.")
                false
            }
        } catch (e: Exception) {
            Log.e("Downloader", "Gagal mengunduh file: ${e.message}", e)
            false
        }
    }


    // Fungsi umum untuk menyimpan dari InputStream ke MediaStore sesuai source
    suspend fun saveToMediaStoreFromStream(
        context: Context,
        inputStream: InputStream,
        fileName: String,
        mimeType: String,
        fileSize: Long = -1,
        onProgressUpdate: (progress: Int, downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _, _ -> },
        source: String = "tiktok"
    ): Uri? = withContext(Dispatchers.IO) {
        val mediaStoreResult = when (source.lowercase()) {
            "youtube" -> getMediaStoreOutputStreamForYouTube(context, fileName, mimeType)
            "instagram" -> getMediaStoreOutputStreamForInstagram(context, fileName, mimeType)
            "whatsapp" -> getMediaStoreOutputStreamForWhatsapp(context, fileName, mimeType)
            else -> getMediaStoreOutputStream(context, fileName, mimeType)
        }

        mediaStoreResult?.let { (uri, outputStream) ->
            copyStreamWithProgress(inputStream, outputStream, fileSize, onProgressUpdate)
            outputStream.flush()
            outputStream.close()
            uri
        }
    }


    private suspend fun saveToMediaStore(
        context: Context,
        inputStream: InputStream,
        fileName: String,
        mimeType: String,
        fileSize: Long,
        onProgressUpdate: (progress: Int, downloadedBytes: Long, totalBytes: Long) -> Unit,
        source: String
    ): Uri? {
        return runCatching {
            saveToMediaStoreFromStream(
                context, inputStream, fileName, mimeType, fileSize, onProgressUpdate, source
            )
        }.getOrNull()
    }

    // Fungsi khusus buat WhatsApp Story folder dan URI
    private fun getMediaStoreOutputStreamForWhatsapp(
        context: Context,
        fileName: String,
        mimeType: String
    ): Pair<Uri, OutputStream>? = try {
        val relativePath = when {
            mimeType.startsWith("video") -> Environment.DIRECTORY_MOVIES + "/Afitech-Whatsapp"
            mimeType.startsWith("image") -> Environment.DIRECTORY_PICTURES + "/Afitech-Whatsapp"
            else -> Environment.DIRECTORY_DOWNLOADS + "/WhatsappDownloads"
        }

        val mediaUri = when {
            mimeType.startsWith("video") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            mimeType.startsWith("image") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Files.getContentUri("external")
        }

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = context.contentResolver.insert(mediaUri, contentValues)
        val outputStream = uri?.let { context.contentResolver.openOutputStream(it) }

        if (uri != null && outputStream != null) {
            contentValues.clear()
            contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
            context.contentResolver.update(uri, contentValues, null, null)
            Pair(uri, outputStream)
        } else null
    } catch (e: Exception) {
        Log.e("Downloader", "Gagal MediaStore Whatsapp: ${e.message}", e)
        null
    }

    private fun getMediaStoreOutputStreamForYouTube(
        context: Context,
        fileName: String,
        mimeType: String
    ): Pair<Uri, OutputStream>? = try {
        val relativePath = if (mimeType.startsWith("video"))
            Environment.DIRECTORY_MOVIES + "/Afitech-Youtube"
        else
            Environment.DIRECTORY_MUSIC + "/Afitech-Youtube"

        val mediaUri = if (mimeType.startsWith("video"))
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = context.contentResolver.insert(mediaUri, contentValues)
        val outputStream = uri?.let { context.contentResolver.openOutputStream(it) }

        if (uri != null && outputStream != null) {
            contentValues.clear()
            contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
            context.contentResolver.update(uri, contentValues, null, null)
            Pair(uri, outputStream)
        } else null
    } catch (e: Exception) {
        Log.e("Downloader", "Gagal MediaStore YouTube: ${e.message}", e)
        null
    }

    private fun getMediaStoreOutputStreamForInstagram(
        context: Context,
        fileName: String,
        mimeType: String
    ): Pair<Uri, OutputStream>? = try {
        val relativePath = when {
            mimeType.startsWith("video") -> Environment.DIRECTORY_MOVIES + "/Afitech-Instagram"
            mimeType.startsWith("image") -> Environment.DIRECTORY_PICTURES + "/Afitech-Instagram"
            else -> Environment.DIRECTORY_DOWNLOADS + "/InstagramDownloads"
        }

        val mediaUri = when {
            mimeType.startsWith("video") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            mimeType.startsWith("image") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Files.getContentUri("external")
        }

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = context.contentResolver.insert(mediaUri, contentValues)
        val outputStream = uri?.let { context.contentResolver.openOutputStream(it) }

        if (uri != null && outputStream != null) {
            contentValues.clear()
            contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
            context.contentResolver.update(uri, contentValues, null, null)
            Pair(uri, outputStream)
        } else null
    } catch (e: Exception) {
        Log.e("Downloader", "Gagal MediaStore Instagram: ${e.message}", e)
        null
    }

    private fun getMediaStoreOutputStream(
        context: Context,
        fileName: String,
        mimeType: String
    ): Pair<Uri, OutputStream>? = try {
        val relativePath = when {
            mimeType.startsWith("video") -> Environment.DIRECTORY_MOVIES + "/Afitech-Tiktok"
            mimeType.startsWith("audio") -> Environment.DIRECTORY_MUSIC + "/Afitech-Tiktok"
            mimeType.startsWith("image") -> Environment.DIRECTORY_PICTURES + "/Afitech-Tiktok"
            else -> Environment.DIRECTORY_DOWNLOADS + "/TikTokDownloads"
        }

        val mediaUri = when {
            mimeType.startsWith("video") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            mimeType.startsWith("audio") -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            mimeType.startsWith("image") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Files.getContentUri("external")
        }

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = context.contentResolver.insert(mediaUri, contentValues)
        val outputStream = uri?.let { context.contentResolver.openOutputStream(it) }

        if (uri != null && outputStream != null) {
            contentValues.clear()
            contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
            context.contentResolver.update(uri, contentValues, null, null)
            Pair(uri, outputStream)
        } else null
    } catch (e: Exception) {
        Log.e("Downloader", "Gagal MediaStore TikTok: ${e.message}", e)
        null
    }

    fun generateUniqueFileName(
        context: Context,
        fileName: String,
        mimeType: String,
        source: String = "tiktok"
    ): String {
        val baseName = fileName.substringBeforeLast(".")
        val extension = fileName.substringAfterLast(".", "")
        val relativeFolder = when (source.lowercase()) {
            "instagram" -> when {
                mimeType.startsWith("video") -> Environment.DIRECTORY_MOVIES + "/Afitech-Instagram"
                mimeType.startsWith("image") -> Environment.DIRECTORY_PICTURES + "/Afitech-Instagram"
                else -> Environment.DIRECTORY_DOWNLOADS + "/InstagramDownloads"
            }
            "youtube" -> if (mimeType.startsWith("video"))
                Environment.DIRECTORY_MOVIES + "/Afitech-Youtube"
            else
                Environment.DIRECTORY_MUSIC + "/Afitech-Youtube"
            "whatsapp" -> when {
                mimeType.startsWith("video") -> Environment.DIRECTORY_MOVIES + "/Afitech-Whatsapp"
                mimeType.startsWith("image") -> Environment.DIRECTORY_PICTURES + "/Afitech-Whatsapp"
                else -> Environment.DIRECTORY_DOWNLOADS + "/WhatsappDownloads"
            }
            else -> when {
                mimeType.startsWith("video") -> Environment.DIRECTORY_MOVIES + "/Afitech-Tiktok"
                mimeType.startsWith("audio") -> Environment.DIRECTORY_MUSIC + "/Afitech-Tiktok"
                mimeType.startsWith("image") -> Environment.DIRECTORY_PICTURES + "/Afitech-Tiktok"
                else -> Environment.DIRECTORY_DOWNLOADS + "/TikTokDownloads"
            }
        }

        val dir = File(context.getExternalFilesDir(null), relativeFolder)
        if (!dir.exists()) dir.mkdirs()

        var newFileName = "$baseName.$extension"
        var counter = 1
        // Loop cek apakah file sudah ada, kalau iya tambahkan (1), (2), dst
        while (File(dir, newFileName).exists()) {
            newFileName = "$baseName($counter).$extension"
            counter++
        }

        return newFileName
    }


    private fun copyStreamWithProgress(
        input: InputStream,
        output: OutputStream,
        fileSize: Long,
        onProgressUpdate: (progress: Int, downloadedBytes: Long, totalBytes: Long) -> Unit
    ) {
        val buffer = ByteArray(8 * 1024)
        var bytesRead: Int
        var totalRead = 0L
        var lastProgress = -1

        while (input.read(buffer).also { bytesRead = it } != -1) {
            output.write(buffer, 0, bytesRead)
            totalRead += bytesRead

            val progress = if (fileSize > 0) {
                ((totalRead * 100L) / fileSize).toInt().coerceAtMost(100)
            } else {
                (totalRead / (512 * 1024)).toInt().coerceAtMost(100)
            }

            if (progress != lastProgress) {
                onProgressUpdate(progress, totalRead, fileSize)
                lastProgress = progress
            }
        }

        if (lastProgress < 100) {
            onProgressUpdate(100, totalRead, fileSize)
        }
    }

}
