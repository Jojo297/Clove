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


object ModelManager {
    private const val TAG = "ModelManager"
    private const val MODEL_DIR = "models"
    private const val API_URL = "https://pegasus-accepted-surely.ngrok-free.app/api/models-all"

    /**
     * Sinkronkan semua model dari API ke storage lokal, lalu kembalikan path model utama.
     */
    suspend fun getModelFilePath(context: Context): String {
        val modelDir = File(context.filesDir, MODEL_DIR)
        if (!modelDir.exists()) modelDir.mkdirs()

        try {
            // Langkah 1: Ambil SEMUA URL model dari API.
            val modelUrls = fetchAllModelUrls(API_URL)
            if (modelUrls.isEmpty()) {
                throw Exception("Tidak ada model yang ditemukan dari API.")
            }

            // --- BARU: Logika untuk menghapus file lama ---
            // 1. Dapatkan daftar NAMA FILE dari API untuk perbandingan yang efisien.
            val remoteFileNames = modelUrls.map { it.substringAfterLast("/") }.toSet()

            // 2. Dapatkan daftar file yang ada di direktori lokal.
            val localFiles = modelDir.listFiles() ?: emptyArray()

            // 3. Loop melalui file lokal dan hapus jika namanya tidak ada di daftar dari API.
            localFiles.forEach { localFile ->
                if (localFile.name !in remoteFileNames && localFile.name != "default_model.tflite") {
                    Log.w(TAG, "🗑️ File lokal tidak ada di API, menghapus: ${localFile.name}")
                    localFile.delete()
                }
            }
            // --- AKHIR DARI LOGIKA BARU ---

            // Langkah 2 (sebelumnya): Loop melalui setiap URL untuk dibandingkan dengan file lokal.
            for (modelUrl in modelUrls) {
                val fileName = modelUrl.substringAfterLast("/")
                val localFile = File(modelDir, fileName)

                // Langkah 3 (sebelumnya): Jika file TIDAK ADA di storage, maka download.
                if (!localFile.exists()) {
                    Log.i(TAG, "📥 Model belum ada, download: $fileName")
                    downloadFile(modelUrl, localFile)
                    Log.i(TAG, "✅ Model berhasil di-download: ${localFile.absolutePath}")
                } else {
                    // Jika SUDAH ADA, lewati.
                    Log.i(TAG, "ℹ️ Model sudah ada, lewati: $fileName")
                }
            }

            // Panggil fungsi list file SETELAH proses sinkronisasi selesai.
            listFilesInModelDir(context)

            // Langkah 4: Kembalikan path dari model PERTAMA dalam daftar untuk digunakan.
            val firstModelFileName = modelUrls.first().substringAfterLast("/")
            return File(modelDir, firstModelFileName).absolutePath

        } catch (e: Exception) {
            Log.e(TAG, "❌ Gagal cek/download model: ${e.message}", e)
            return copyAssetModelToInternal(context, "default_model.tflite")
        }
    }

    /**
     * Mengambil SEMUA URL model dari respons JSON API.
     */
    private suspend fun fetchAllModelUrls(apiUrl: String): List<String> = withContext(Dispatchers.IO) {
        val conn = URL(apiUrl).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"

        if (conn.responseCode != 200) {
            throw IOException("HTTP error code: ${conn.responseCode}")
        }

        val response = conn.inputStream.bufferedReader().use { it.readText() }
        Log.i(TAG, "📦 Response JSON: $response")

        val jsonArray = JSONArray(response)
        val urls = mutableListOf<String>()
        // Loop melalui SEMUA objek di dalam array JSON
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            urls.add(obj.getString("path"))
        }
        return@withContext urls // Kembalikan list berisi semua URL
    }

    /**
     * Download file model dari URL dan simpan ke file lokal.
     */
    private suspend fun downloadFile(urlString: String, localFile: File) = withContext(Dispatchers.IO) {
        URL(urlString).openStream().use { input ->
            FileOutputStream(localFile).use { output ->
                input.copyTo(output)
            }
        }
    }

    /**
     * Salin model dari folder assets sebagai fallback.
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
     * Fungsi untuk debugging: Menampilkan semua file di dalam folder model ke Logcat.
     */
    private fun listFilesInModelDir(context: Context) {
        val modelDir = File(context.filesDir, MODEL_DIR)
        val logTag = "ListMyFiles" // Tag khusus agar mudah dicari di Logcat

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