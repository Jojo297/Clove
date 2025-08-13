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

    // Resumes camera setup and detector initialization when the app is foregrounded.
    override fun onResume() {
        super.onResume()
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

    // Pauses the detection and releases detector resources to save power.
    override fun onPause() {
        super.onPause()

        if(this::objectDetectorHelper.isInitialized) {
            viewModel.setDelegate(objectDetectorHelper.currentDelegate)
            viewModel.setThreshold(objectDetectorHelper.threshold)
            viewModel.setMaxResults(objectDetectorHelper.maxResults)

            backgroundExecutor.execute { objectDetectorHelper.clearObjectDetector() }
        }
    }

    // Cleans up the view binding and shuts down the background executor.
    override fun onDestroyView() {
        _fragmentCameraBinding = null
        super.onDestroyView()

        backgroundExecutor.shutdown()
        backgroundExecutor.awaitTermination(
            Long.MAX_VALUE,
            TimeUnit.NANOSECONDS
        )
    }

    // Inflates the fragment's view and initializes the view binding.
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _fragmentCameraBinding =
            FragmentCameraBinding.inflate(inflater, container, false)

        return fragmentCameraBinding.root
    }

    // Sets up the swipe-to-refresh listener to sync models from the server.
    private fun setupPullToRefresh() {
        val swipeLayout = fragmentCameraBinding.swipeRefreshLayout

        swipeLayout.setOnRefreshListener {
            Log.d(TAG, "Pull-to-refresh triggered.")
            Toast.makeText(context, "Syncing models...", Toast.LENGTH_SHORT).show()

            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        ModelManager.getModelFilePath(requireContext())
                    }
                    Toast.makeText(context, "Sync successful!", Toast.LENGTH_SHORT).show()
                    reloadModelsAndUpdateUI()
                } catch (e: Exception) {
                    Toast.makeText(context, "Sync failed: ${e.message}", Toast.LENGTH_LONG).show()
                    Log.e(TAG, "Pull-to-refresh sync failed", e)
                } finally {
                    swipeLayout.isRefreshing = false
                }
            }
        }
    }

    // Initializes the object detector, camera, and UI controls after the view is created.
    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        backgroundExecutor = Executors.newSingleThreadExecutor()

        backgroundExecutor.execute {
            val modelDir = File(requireContext().filesDir, "models")
            val modelFiles = modelDir.listFiles { _, name -> name.endsWith(".tflite") }

            if (modelFiles.isNullOrEmpty()) {
                activity?.runOnUiThread {
                    Toast.makeText(requireContext(), "Tidak ada model .tflite yang ditemukan.", Toast.LENGTH_LONG).show()
                }
                return@execute
            }

            val modelNames = modelFiles.map { it.name }

            initialModelPath = modelFiles.first().absolutePath

            objectDetectorHelper =
                ObjectDetectorHelper(
                    context = requireContext(),
                    threshold = viewModel.currentThreshold,
                    currentDelegate = viewModel.currentDelegate,
                    modelPath = initialModelPath,
                    maxResults = viewModel.currentMaxResults,
                    objectDetectorListener = this,
                    runningMode = RunningMode.LIVE_STREAM
                )

            fragmentCameraBinding.viewFinder.post {
                setUpCamera()
            }

            activity?.runOnUiThread {
                populateModelSpinner(modelNames, initialModelPath)
                setupPullToRefresh()
            }
        }
        initBottomSheetControls()

        fragmentCameraBinding.overlay.setRunningMode(RunningMode.LIVE_STREAM)
    }


    // Formats the model filename into a human-readable string for the spinner.
    private fun formatModelNameForDisplay(filename: String): String {
        return filename
            .substringAfter('_')
            .removeSuffix(".tflite")
            .replace('_', ' ')
            .split(' ')
            .joinToString(" ") { it.replaceFirstChar(Char::titlecase) }
    }



    // Populates the model selection spinner with available models and sets the current selection.
    private fun populateModelSpinner(modelDisplayNames: List<String>,  modelToSelectPath: String) {
        val modelDir = File(requireContext().filesDir, "models")
        if (!modelDir.exists()) {
            Log.e("ModelSetup", "Models directory does not exist.")
            return
        }

        modelMap.clear()

        modelDir.listFiles { _, name -> name.endsWith(".tflite") }?.forEach { file ->
            val cleanName = formatModelNameForDisplay(file.name)
            modelMap[cleanName] = file.name
        }

        if (modelMap.isEmpty()) {
            Log.e("ModelSetup", "No .tflite models found.")
            return
        }

        val displayNames = modelMap.keys.toList()

        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, displayNames)
        fragmentCameraBinding.bottomSheetLayout.spinnerModel.adapter = adapter

        val currentModelName = File(modelToSelectPath).name
        val currentDisplayName = formatModelNameForDisplay(currentModelName)
        val currentModelPosition = displayNames.indexOf(currentDisplayName)
        if (currentModelPosition != -1) {
            fragmentCameraBinding.bottomSheetLayout.spinnerModel.setSelection(currentModelPosition, false)
        }
    }

    // Initializes listeners for the UI controls in the bottom sheet, like the model spinner.
    private fun initBottomSheetControls() {
        fragmentCameraBinding.bottomSheetLayout.spinnerModel.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    val selectedDisplayName = parent?.getItemAtPosition(position).toString()
                    val originalFileName = modelMap[selectedDisplayName] ?: return
                    val newModelPath = File(requireContext().filesDir, "models/$originalFileName").absolutePath

                    if (this@CameraFragment::objectDetectorHelper.isInitialized && newModelPath != objectDetectorHelper.modelPath) {

                        initialModelPath = newModelPath

                        backgroundExecutor.execute {
                            objectDetectorHelper.changeModel(newModelPath)
                        }
                    }
                }

                override fun onNothingSelected(p0: AdapterView<*>?) { /* no operation */ }
            }
    }

    // Reloads the list of models from storage, updates the UI spinner, and reinitializes the detector.
    private fun reloadModelsAndUpdateUI() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val modelFiles = withContext(Dispatchers.IO) {
                    val modelDir = File(requireContext().filesDir, "models")
                    modelDir.listFiles { _, name -> name.endsWith(".tflite") } ?: emptyArray()
                }

                if (modelFiles.isEmpty()) {
                    Toast.makeText(context, "Tidak ada model ditemukan setelah sync.", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                initialModelPath = modelFiles.first().absolutePath

                withContext(Dispatchers.Main) {
                    populateModelSpinner(modelFiles.map { it.name }, initialModelPath)

                    backgroundExecutor.execute {
                        objectDetectorHelper.clearObjectDetector()
                        objectDetectorHelper.modelPath = initialModelPath
                        objectDetectorHelper.setupObjectDetector()
                    }

                    Toast.makeText(context, "UI updated with new model!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Gagal memuat ulang model setelah refresh", e)
                Toast.makeText(context, "Gagal update UI: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Resets the object detector and clears the overlay view.
    private fun updateControlsUi() {
        backgroundExecutor.execute {
            objectDetectorHelper.clearObjectDetector()
            objectDetectorHelper.setupObjectDetector()
        }

        fragmentCameraBinding.overlay.clear()
    }

    // Initializes CameraX and prepares it for binding camera use cases.
    private fun setUpCamera() {
        val cameraProviderFuture =
            ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener(
            {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
            },
            ContextCompat.getMainExecutor(requireContext())
        )
    }

    // Configures and binds the camera's preview and image analysis use cases.
    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCameraUseCases() {
        val cameraProvider =
            cameraProvider
                ?: throw IllegalStateException("Camera initialization failed.")

        val cameraSelector =
            CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK).build()

        preview =
            Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
                .build()

        imageAnalyzer =
            ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also {
                    it.setAnalyzer(
                        backgroundExecutor,
                        objectDetectorHelper::detectLivestreamFrame
                    )
                }

        cameraProvider.unbindAll()

        try {
            camera = cameraProvider.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageAnalyzer
            )

            preview?.setSurfaceProvider(fragmentCameraBinding.viewFinder.surfaceProvider)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    // Handles configuration changes, such as screen rotation, to update the image analyzer.
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        imageAnalyzer?.targetRotation =
            fragmentCameraBinding.viewFinder.display.rotation
    }

    // A callback to receive and display the detection results from the ObjectDetectorHelper.
    override fun onResults(resultBundle: ObjectDetectorHelper.ResultBundle) {
        activity?.runOnUiThread {
            if (_fragmentCameraBinding != null && isAdded) {
                Log.d(TAG, "onResults dipanggil dengan ${resultBundle.results.size} hasil.")

                if (resultBundle.results.isNotEmpty()) {
                    val detectionResult = resultBundle.results[0]

                    if (detectionResult.detections().isNotEmpty()) {
                        Log.d(TAG, "Objek terdeteksi: ${detectionResult.detections().size} buah.")
                        fragmentCameraBinding.overlay.setResults(
                            detectionResult,
                            resultBundle.inputImageHeight,
                            resultBundle.inputImageWidth,
                            resultBundle.inputImageRotation
                        )
                    } else {
                        Log.d(TAG, "Hasil ada, tapi tidak ada objek terdeteksi (detections list is empty).")
                        fragmentCameraBinding.overlay.clear()
                    }
                } else {
                    Log.d(TAG, "Daftar hasil utama kosong (results list is empty).")
                    fragmentCameraBinding.overlay.clear()
                }

                fragmentCameraBinding.overlay.invalidate()
            }
        }
    }

    // A callback to handle and display errors from the ObjectDetectorHelper.
    override fun onError(error: String, errorCode: Int) {
        activity?.runOnUiThread {
            Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
        }
    }
}