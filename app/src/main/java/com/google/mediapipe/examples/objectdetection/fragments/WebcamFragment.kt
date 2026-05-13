package com.google.mediapipe.examples.objectdetection.fragments

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.mediapipe.examples.objectdetection.MainViewModel
import com.google.mediapipe.examples.objectdetection.ObjectDetectorHelper
import com.google.mediapipe.examples.objectdetection.OverlayViewWebcam
import com.google.mediapipe.examples.objectdetection.R
import com.google.mediapipe.examples.objectdetection.databinding.FragmentWebcamBinding
import com.google.mediapipe.examples.objectdetection.utils.ModelManager
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.base.CameraFragment
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.widget.IAspectRatio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class WebcamFragment : CameraFragment(), ObjectDetectorHelper.DetectorListener {
    private val TAG = "ObjectDetection"
    private var _binding: FragmentWebcamBinding? = null
    private val binding get() = _binding!!

    private lateinit var overlay: OverlayViewWebcam
    private lateinit var objectDetectorHelper: ObjectDetectorHelper
    private val viewModel: MainViewModel by activityViewModels()

    private var initialModelPath: String = ""
    private val modelMap = mutableMapOf<String, String>()

    private val DEFAULT_PREVIEW_WIDTH = 640
    private val DEFAULT_PREVIEW_HEIGHT = 480
    private val detectionInterval = 150L
    private var detectionJob: Job? = null

    // Replace your existing onCreateView with this:
    override fun getRootView(inflater: LayoutInflater, container: ViewGroup?): View? {
        _binding = FragmentWebcamBinding.inflate(inflater, container, false)
        return binding.root
    }
    override fun onCameraState(
        self: MultiCameraClient.ICamera,
        code: ICameraStateCallBack.State,
        msg: String?
    ) {
        when (code) {
            ICameraStateCallBack.State.OPENED -> {
                Log.d(TAG, "USB Camera Opened")
            }
            ICameraStateCallBack.State.CLOSED -> {
                Log.d(TAG, "USB Camera Closed")
            }
            ICameraStateCallBack.State.ERROR -> {
                Log.e(TAG, "USB Camera Error: $msg")
                Toast.makeText(context, "Camera Error: $msg", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        overlay = binding.overlay

        // Setup MediaPipe Object Detector
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            loadModelAndStartDetection()
        }

        initBottomSheetControls()
        setupSyncButton()
        setupRotationLayout()
    }

    private suspend fun loadModelAndStartDetection() {
        try {
            val defaultModel = "efficientdet-lite0.tflite"
            copyModelFromAssets(defaultModel)

            val modelDir = File(requireContext().filesDir, "models")
            val modelFile = modelDir.listFiles { _, name -> name.endsWith(".tflite") }?.firstOrNull()
            initialModelPath = modelFile?.absolutePath ?: ""

            if (initialModelPath.isNotEmpty()) {
                objectDetectorHelper = withContext(Dispatchers.IO) {
                    ObjectDetectorHelper(
                        context = requireContext(),
                        threshold = viewModel.currentThreshold,
                        currentDelegate = viewModel.currentDelegate,
                        modelPath = initialModelPath,
                        maxResults = viewModel.currentMaxResults,
                        objectDetectorListener = this@WebcamFragment,
                        runningMode = RunningMode.LIVE_STREAM
                    )
                }

                populateModelSpinner()
                startRealTimeDetection()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gagal load model", e)
            Toast.makeText(requireContext(), "Gagal memuat model: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private suspend fun copyModelFromAssets(fileName: String) {
        val outputDir = File(requireContext().filesDir, "models")
        if (!outputDir.exists()) outputDir.mkdirs()

        val outputFile = File(outputDir, fileName)
        if (outputFile.exists()) return

        withContext(Dispatchers.IO) {
            requireContext().assets.open(fileName).use { input ->
                FileOutputStream(outputFile).use { output ->
                    input.copyTo(output)
                }
            }
        }
    }

    // ==================== CameraFragment Override ====================

    override fun getCameraView(): IAspectRatio? {
        return binding.textureView
    }

    override fun getCameraViewContainer(): ViewGroup? {
        return binding.webcamContainer
    }

    override fun getCameraRequest(): CameraRequest {
        return CameraRequest.Builder()
            .setPreviewWidth(DEFAULT_PREVIEW_WIDTH)
            .setPreviewHeight(DEFAULT_PREVIEW_HEIGHT)
            .setRenderMode(CameraRequest.RenderMode.OPENGL)
            .setPreviewFormat(CameraRequest.PreviewFormat.FORMAT_MJPEG)
            .setAspectRatioShow(true)
            .create()
    }

    // ==================== Detection ====================

    private fun startRealTimeDetection() {
        detectionJob?.cancel()
        detectionJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                detectFromWebcam()
                delay(detectionInterval)
            }
        }
    }

    private fun detectFromWebcam() {
        if (!isAdded || binding.textureView.bitmap == null) return

        try {
            val frameBitmap = binding.textureView.getBitmap() ?: return
            val cropped = cropBitmapToSquare(frameBitmap)
            val inputBitmap = Bitmap.createScaledBitmap(cropped, 384, 384, true)

            objectDetectorHelper.detectFromBitmap(inputBitmap, getDeviceRotation())
        } catch (e: Exception) {
            Log.e(TAG, "Error detectFromWebcam", e)
        }
    }

    private fun cropBitmapToSquare(bitmap: Bitmap): Bitmap {
        val side = minOf(bitmap.width, bitmap.height)
        val x = (bitmap.width - side) / 2
        val y = (bitmap.height - side) / 2
        return Bitmap.createBitmap(bitmap, x, y, side, side)
    }

    private fun getDeviceRotation(): Int {
        return if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 90 else 0
    }

    // ==================== UI & Model Spinner ====================

    private fun setupRotationLayout() {
        view?.viewTreeObserver?.addOnGlobalLayoutListener {
            // Rotation handling code (bisa kamu sesuaikan lagi)
        }
    }

    private fun populateModelSpinner() {
        val modelDir = File(requireContext().filesDir, "models")
        modelMap.clear()

        modelDir.listFiles { _, name -> name.endsWith(".tflite") }?.forEach { file ->
            val displayName = file.name.removeSuffix(".tflite").replace("_", " ")
            modelMap[displayName] = file.name
        }

        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, modelMap.keys.toList())
        binding.bottomSheetLayout.spinnerModel.adapter = adapter
    }

    private fun initBottomSheetControls() {
        binding.bottomSheetLayout.spinnerModel.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedName = parent?.getItemAtPosition(position).toString()
                val fileName = modelMap[selectedName] ?: return
                val newPath = File(requireContext().filesDir, "models/$fileName").absolutePath

                if (newPath != objectDetectorHelper.modelPath) {
                    detectionJob?.cancel()
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            objectDetectorHelper.changeModel(newPath)
                        }
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Model diubah ke $selectedName", Toast.LENGTH_SHORT).show()
                            startRealTimeDetection()
                        }
                    }
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupSyncButton() {
        binding.bottomSheetLayout.syncButton.setOnClickListener {
            syncModels()
        }
    }

    private fun syncModels() {
        val btn = binding.bottomSheetLayout.syncButton
        btn.isEnabled = false
        btn.text = "Syncing..."

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    ModelManager.getModelFilePath(requireContext())
                }
                Toast.makeText(context, "Sync berhasil!", Toast.LENGTH_SHORT).show()
                loadModelAndStartDetection()
            } catch (e: Exception) {
                Toast.makeText(context, "Sync gagal: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                btn.text = "Sync Models"
                btn.isEnabled = true
            }
        }
    }

    // ==================== Detector Listener ====================

    override fun onResults(resultBundle: ObjectDetectorHelper.ResultBundle) {
        activity?.runOnUiThread {
            val detections = resultBundle.results.firstOrNull()?.detections() ?: emptyList()
            if (detections.isNotEmpty()) {
                binding.overlay.setResults(
                    resultBundle.results.first(),
                    DEFAULT_PREVIEW_HEIGHT,
                    DEFAULT_PREVIEW_WIDTH,
                    resultBundle.inputImageRotation
                )
            } else {
                binding.overlay.clear()
            }
            binding.overlay.invalidate()
        }
    }

    override fun onError(error: String, errorCode: Int) {
        activity?.runOnUiThread {
            Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
        }
    }

    // ==================== Lifecycle ====================

    override fun onDestroyView() {
        detectionJob?.cancel()
        _binding = null
        super.onDestroyView()
    }

    override fun onStart() {
        super.onStart()
        // Dialog panduan
        AlertDialog.Builder(requireContext())
            .setTitle("Webcam USB")
            .setMessage("Pastikan webcam terhubung dan izinkan izin USB.")
            .setPositiveButton("Mengerti") { dialog, _ -> dialog.dismiss() }
            .show()
    }
}