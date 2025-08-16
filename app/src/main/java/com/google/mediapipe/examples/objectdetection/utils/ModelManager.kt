package com.google.mediapipe.examples.objectdetection.utils

import android.content.Context
import android.util.Log
import com.jiangdg.utils.FileUtils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import com.google.mediapipe.examples.objectdetection.BuildConfig


object ModelManager {
    private const val TAG = "ModelManager"
    private const val MODEL_DIR = "models"

    private const val API_LIST_URL = "https://pegasus-accepted-surely.ngrok-free.app/api/models-all"
    private const val API_DOWNLOAD_BASE_URL = "https://pegasus-accepted-surely.ngrok-free.app/api/models/download/"

    /**
     * Sync all models from API to local storage, then return the main model path.
     */
    suspend fun getModelFilePath(context: Context): String {
        val modelDir = File(context.filesDir, MODEL_DIR)
        if (!modelDir.exists()) modelDir.mkdirs()

        try {
            val modelPaths = fetchAllModelPaths(API_LIST_URL)
            if (modelPaths.isEmpty()) {
                throw Exception("Tidak ada model yang ditemukan dari API.")
            }

            val remoteFileNames = modelPaths.map { it.substringAfterLast("/") }.toSet()
            val localFiles = modelDir.listFiles() ?: emptyArray()

            localFiles.forEach { localFile ->
                if (localFile.name !in remoteFileNames && localFile.name != "default_model.tflite") {
                    Log.w(TAG, "🗑️ File lokal tidak ada di API, menghapus: ${localFile.name}")
                    localFile.delete()
                }
            }

            for (fileName in remoteFileNames) {
                val localFile = File(modelDir, fileName)
                if (!localFile.exists()) {
                    Log.i(TAG, "📥 Model belum ada, download: $fileName")
                    // PERUBAHAN UTAMA: Buat URL unduhan yang aman
                    val downloadUrl = "$API_DOWNLOAD_BASE_URL$fileName"
                    downloadFile(downloadUrl, localFile)
                    Log.i(TAG, "✅ Model berhasil di-download: ${localFile.absolutePath}")
                } else {
                    Log.i(TAG, "ℹ️ Model sudah ada, lewati: $fileName")
                }
            }

            listFilesInModelDir(context)

            val firstModelFileName = remoteFileNames.first()
            return File(modelDir, firstModelFileName).absolutePath

        } catch (e: Exception) {
            Log.e(TAG, "❌ Gagal cek/download model: ${e.message}", e)
            return copyAssetModelToInternal(context, "default_model.tflite")
        }
    }

    /**
     * Retrieving ALL model paths from JSON API response, now with API Key.
     */
    private suspend fun fetchAllModelPaths(apiUrl: String): List<String> = withContext(Dispatchers.IO) {
        val conn = URL(apiUrl).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("X-API-KEY", BuildConfig.MODELS_API_KEY)

        if (conn.responseCode != 200) {
            throw IOException("HTTP error code: ${conn.responseCode}")
        }

        val response = conn.inputStream.bufferedReader().use { it.readText() }
        Log.i(TAG, "📦 Response JSON: $response")

        val jsonArray = JSONArray(response)
        val paths = mutableListOf<String>()
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            paths.add(obj.getString("path"))
        }
        return@withContext paths
    }

    /**
     * Download model file from a secure URL and save to the local storage, now with API Key.
     */
    private suspend fun downloadFile(urlString: String, localFile: File) = withContext(Dispatchers.IO) {
        val conn = URL(urlString).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("X-API-KEY", BuildConfig.MODELS_API_KEY)

        if (conn.responseCode != 200) {
            throw IOException("Download failed with HTTP error code: ${conn.responseCode}")
        }

        conn.inputStream.use { input ->
            FileOutputStream(localFile).use { output ->
                input.copyTo(output)
            }
        }
    }

    /**
     * Copy the model from the assets folder as a fallback..
     */
    private fun copyAssetModelToInternal(context: Context, assetFileName: String): String {
        val localFile = File(context.filesDir, "$MODEL_DIR/$assetFileName")
        if (!localFile.parentFile!!.exists()) localFile.parentFile!!.mkdirs()

        context.assets.open(assetFileName).use { input ->
            FileOutputStream(localFile).use { output ->
                input.copyTo(output)
            }
        }
        Log.i(TAG, "📂 Fallback ke model assets: ${localFile.absolutePath}")
        return localFile.absolutePath
    }

    /**
     * Function for debugging: Displays all files inside the model folder to Logcat.
     */
    private fun listFilesInModelDir(context: Context) {
        val modelDir = File(context.filesDir, MODEL_DIR)
        val logTag = "ListMyFiles"

        Log.d(logTag, "Mencari file di path: ${modelDir.absolutePath}")

        if (modelDir.exists() && modelDir.isDirectory) {
            val files = modelDir.listFiles()
            if (files.isNullOrEmpty()) {
                Log.w(logTag, "⚠️ Folder model kosong.")
            } else {
                Log.i(logTag, "✅ Ditemukan ${files.size} file di folder model:")
                files.forEach { file ->
                    Log.i(logTag, "-> ${file.name} (Ukuran: ${file.length()} bytes)")
                }
            }
        } else {
            Log.e(logTag, "❌ Folder model tidak ada atau bukan sebuah direktori.")
        }
    }
}