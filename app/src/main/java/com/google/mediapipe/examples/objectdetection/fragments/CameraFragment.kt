/*
 * Copyright 2022 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *             http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.mediapipe.examples.objectdetection.fragments

import android.annotation.SuppressLint
import android.widget.ArrayAdapter
import java.io.File
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.Toast
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.Navigation
import com.google.mediapipe.examples.objectdetection.MainViewModel
import com.google.mediapipe.examples.objectdetection.ObjectDetectorHelper
import com.google.mediapipe.examples.objectdetection.R
import com.google.mediapipe.examples.objectdetection.databinding.FragmentCameraBinding
import com.google.mediapipe.examples.objectdetection.utils.ModelManager
import com.google.mediapipe.tasks.vision.core.RunningMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CameraFragment : Fragment(), ObjectDetectorHelper.DetectorListener {

    private val TAG = "ObjectDetection"

    private var _fragmentCameraBinding: FragmentCameraBinding? = null

    private val fragmentCameraBinding
        get() = _fragmentCameraBinding!!

    private lateinit var objectDetectorHelper: ObjectDetectorHelper
    private val viewModel: MainViewModel by activityViewModels()
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var initialModelPath: String = ""
    private val modelMap = mutableMapOf<String, String>()


    /** Blocking ML operations are performed using this executor */
    private lateinit var backgroundExecutor: ExecutorService

    override fun onResume() {
        super.onResume()
        // Make sure that all permissions are still present, since the
        // user could have removed them while the app was in paused state.
        if (!PermissionsFragment.hasPermissions(requireContext())) {
            Navigation.findNavController(
                requireActivity(),
                R.id.fragment_container
            )
                .navigate(CameraFragmentDirections.actionCameraToPermissions())
        }

        backgroundExecutor.execute {
            if (objectDetectorHelper.isClosed()) {
                objectDetectorHelper.setupObjectDetector()
            }
        }
    }

    override fun onPause() {
        super.onPause()

        if(this::objectDetectorHelper.isInitialized) {
            // DIUBAH: Simpan path model, bukan index integer.
            // Anda perlu menyesuaikan ViewModel Anda untuk menyimpan String.
//             viewModel.setModelPath(objectDetectorHelper.modelPath)
            viewModel.setDelegate(objectDetectorHelper.currentDelegate)
            viewModel.setThreshold(objectDetectorHelper.threshold)
            viewModel.setMaxResults(objectDetectorHelper.maxResults)

            backgroundExecutor.execute { objectDetectorHelper.clearObjectDetector() }
        }
    }

    override fun onDestroyView() {
        _fragmentCameraBinding = null
        super.onDestroyView()

        // Shut down our background executor.
        backgroundExecutor.shutdown()
        backgroundExecutor.awaitTermination(
            Long.MAX_VALUE,
            TimeUnit.NANOSECONDS
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _fragmentCameraBinding =
            FragmentCameraBinding.inflate(inflater, container, false)

        return fragmentCameraBinding.root
    }

    private fun setupPullToRefresh() {
        // DIUBAH: Path ke swipeRefreshLayout sekarang langsung dari fragmentCameraBinding
        val swipeLayout = fragmentCameraBinding.swipeRefreshLayout

        // Sisa dari fungsi ini tetap sama
        swipeLayout.setOnRefreshListener {
            Log.d(TAG, "Pull-to-refresh triggered.")
            Toast.makeText(context, "Syncing models...", Toast.LENGTH_SHORT).show()

            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        ModelManager.getModelFilePath(requireContext())
                    }
                    Toast.makeText(context, "Sync successful!", Toast.LENGTH_SHORT).show()
//                    reloadModelsAndDetector()
                } catch (e: Exception) {
                    Toast.makeText(context, "Sync failed: ${e.message}", Toast.LENGTH_LONG).show()
                    Log.e(TAG, "Pull-to-refresh sync failed", e)
                } finally {
                    swipeLayout.isRefreshing = false
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize our background executor
        backgroundExecutor = Executors.newSingleThreadExecutor()

        // Create the ObjectDetectionHelper that will handle the inference
        backgroundExecutor.execute {
            // --- LOGIKA BARU DIMULAI DI SINI ---

            // 1. Temukan semua model yang tersedia di penyimpanan internal
            val modelDir = File(requireContext().filesDir, "models")
            val modelFiles = modelDir.listFiles { _, name -> name.endsWith(".tflite") }

            if (modelFiles.isNullOrEmpty()) {
                // Jika tidak ada model, tampilkan error di UI thread
                activity?.runOnUiThread {
                    Toast.makeText(requireContext(), "Tidak ada model .tflite yang ditemukan.", Toast.LENGTH_LONG).show()
                }
                return@execute // Hentikan eksekusi jika tidak ada model
            }

            // Ambil daftar nama file untuk ditampilkan di Spinner
            val modelNames = modelFiles.map { it.name }

            // Tentukan model awal yang akan di-load (misalnya, model pertama dalam daftar)
            initialModelPath = modelFiles.first().absolutePath

            // --- LOGIKA BARU SELESAI ---
            objectDetectorHelper =
                ObjectDetectorHelper(
                    context = requireContext(),
                    threshold = viewModel.currentThreshold,
                    currentDelegate = viewModel.currentDelegate,
                    modelPath = initialModelPath, // Gunakan path model awal
                    maxResults = viewModel.currentMaxResults,
                    objectDetectorListener = this,
                    runningMode = RunningMode.LIVE_STREAM
                )

            // Setelah helper dibuat, setup kamera
            fragmentCameraBinding.viewFinder.post {
                setUpCamera()
            }

            // BARU: Panggil fungsi untuk mengisi Spinner di UI thread
            activity?.runOnUiThread {
                populateModelSpinner(modelNames)
                setupPullToRefresh()
            }
        }
        initBottomSheetControls()

        fragmentCameraBinding.overlay.setRunningMode(RunningMode.LIVE_STREAM)
    }


    // Letakkan fungsi ini di dalam class CameraFragment Anda
    private fun formatModelNameForDisplay(filename: String): String {
        return filename
            // 1. Hapus timestamp di depan (semua sebelum underscore pertama)
            .substringAfter('_')
            // 2. Hapus ekstensi .tflite
            .removeSuffix(".tflite")
            // 3. Ganti underscore dengan spasi
            .replace('_', ' ')
            // 4. Buat huruf pertama setiap kata menjadi kapital (opsional, tapi terlihat bagus)
            .split(' ')
            .joinToString(" ") { it.replaceFirstChar(Char::titlecase) }
    }



    // BARU: Fungsi untuk mengisi data ke dalam Spinner
    private fun populateModelSpinner(modelDisplayNames: List<String>) {
        val modelDir = File(requireContext().filesDir, "models")
        if (!modelDir.exists()) {
            Log.e("ModelSetup", "Models directory does not exist.")
            return
        }

        // Kosongkan map sebelum diisi ulang
        modelMap.clear()

        // Ambil semua file .tflite, lalu isi map
        modelDir.listFiles { _, name -> name.endsWith(".tflite") }?.forEach { file ->
            val cleanName = formatModelNameForDisplay(file.name)
            modelMap[cleanName] = file.name // Key: nama bagus, Value: nama file asli
        }

        if (modelMap.isEmpty()) {
            Log.e("ModelSetup", "No .tflite models found.")
            return
        }

        // Ambil daftar nama yang sudah bersih untuk ditampilkan di Spinner
        val displayNames = modelMap.keys.toList()

        // Buat adapter untuk Spinner menggunakan nama yang sudah bersih
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, displayNames)
        fragmentCameraBinding.bottomSheetLayout.spinnerModel.adapter = adapter

        // Set pilihan spinner ke model yang sedang aktif
        // (Anda bisa menyesuaikan logika ini jika perlu menyimpan pilihan terakhir)
        val currentModelName = File(initialModelPath).name
        val currentDisplayName = formatModelNameForDisplay(currentModelName)
        val currentModelPosition = displayNames.indexOf(currentDisplayName)
        if (currentModelPosition != -1) {
            fragmentCameraBinding.bottomSheetLayout.spinnerModel.setSelection(currentModelPosition)
        }
    }

    private fun initBottomSheetControls() {
        fragmentCameraBinding.bottomSheetLayout.spinnerModel.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    // 1. Ambil nama tampilan yang dipilih pengguna
                    val selectedDisplayName = parent?.getItemAtPosition(position).toString()

                    // 2. Gunakan Map untuk mendapatkan nama file asli
                    val originalFileName = modelMap[selectedDisplayName] ?: return

                    // 3. Buat path lengkap menggunakan nama file asli
                    val newModelPath = File(requireContext().filesDir, "models/$originalFileName").absolutePath

                    // Ganti model hanya jika path-nya berbeda
                    if (this@CameraFragment::objectDetectorHelper.isInitialized && newModelPath != objectDetectorHelper.modelPath) {
                        backgroundExecutor.execute {
                            objectDetectorHelper.changeModel(newModelPath)
                        }
                    }
                }


                override fun onNothingSelected(p0: AdapterView<*>?) {
                    /* tidak ada operasi */
                }
            }
    }

    // Perbarui UI dan reset object detector.
    private fun updateControlsUi() {
        // Baris untuk `maxResultsValue` dan `thresholdValue` dihapus karena elemennya tidak ada lagi.

        backgroundExecutor.execute {
            objectDetectorHelper.clearObjectDetector()
            objectDetectorHelper.setupObjectDetector()
        }

        fragmentCameraBinding.overlay.clear()
    }

    // Inisialisasi CameraX dan siapkan untuk mengikat use case kamera
    private fun setUpCamera() {
        val cameraProviderFuture =
            ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener(
            {
                // CameraProvider
                cameraProvider = cameraProviderFuture.get()

                // Bangun dan ikat use case kamera
                bindCameraUseCases()
            },
            ContextCompat.getMainExecutor(requireContext())
        )
    }
    // Declare and bind preview, capture and analysis use cases
    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCameraUseCases() {

        // CameraProvider
        val cameraProvider =
            cameraProvider
                ?: throw IllegalStateException("Camera initialization failed.")

        // CameraSelector - makes assumption that we're only using the back camera
        val cameraSelector =
            CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK).build()

        // Preview. Only using the 4:3 ratio because this is the closest to our models
        preview =
            Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
                .build()

        // ImageAnalysis. Using RGBA 8888 to match how our models work
        imageAnalyzer =
            ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                // The analyzer can then be assigned to the instance
                .also {
                    it.setAnalyzer(
                        backgroundExecutor,
                        objectDetectorHelper::detectLivestreamFrame
                    )
                }

        // Must unbind the use-cases before rebinding them
        cameraProvider.unbindAll()

        try {
            // A variable number of use-cases can be passed here -
            // camera provides access to CameraControl & CameraInfo
            camera = cameraProvider.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageAnalyzer
            )

            // Attach the viewfinder's surface provider to preview use case
            preview?.setSurfaceProvider(fragmentCameraBinding.viewFinder.surfaceProvider)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        imageAnalyzer?.targetRotation =
            fragmentCameraBinding.viewFinder.display.rotation
    }

    // Update UI setelah objek terdeteksi.
    override fun onResults(resultBundle: ObjectDetectorHelper.ResultBundle) {
        activity?.runOnUiThread {
            if (_fragmentCameraBinding != null) {
                // ... (baris yang dikomentari tetap sama) ...

                // Teruskan informasi yang diperlukan ke OverlayView untuk menggambar di kanvas
                val detectionResult = resultBundle.results[0]
                if (isAdded) {
                    fragmentCameraBinding.overlay.setResults(
                        detectionResult,
                        resultBundle.inputImageHeight,
                        // INI YANG DIPERBAIKI: 'result' menjadi 'resultBundle'
                        resultBundle.inputImageWidth,
                        resultBundle.inputImageRotation
                    )
                }

                // Paksa untuk menggambar ulang
                fragmentCameraBinding.overlay.invalidate()
            }
        }
    }

    override fun onError(error: String, errorCode: Int) {
        activity?.runOnUiThread {
            Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()

            // Blok if ini dihapus karena spinnerDelegate sudah tidak ada lagi di layout XML Anda
            // if (errorCode == ObjectDetectorHelper.GPU_ERROR) {
            //     fragmentCameraBinding.bottomSheetLayout.spinnerDelegate.setSelection(
            //         ObjectDetectorHelper.DELEGATE_CPU, false
            //     )
            // }
        }
    }
}
