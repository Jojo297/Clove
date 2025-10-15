package com.google.mediapipe.examples.objectdetection.fragments

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.res.Configuration
import android.graphics.Bitmap
import android.hardware.usb.UsbDevice
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
import androidx.fragment.app.Fragment
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
import com.jiangdg.ausbc.widget.AspectRatioTextureView
import com.jiangdg.usb.USBMonitor
import com.jiangdg.uvc.UVCCamera
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException


class WebcamFragment : Fragment(), ObjectDetectorHelper.DetectorListener {

    private val TAG = "ObjectDetection"
    private var _fragmentWebcamBinding: FragmentWebcamBinding? = null
    private val fragmentWebcamBinding get() = _fragmentWebcamBinding!!

    private lateinit var mUVCCameraView: AspectRatioTextureView
    private lateinit var mUSBMonitor: USBMonitor
    private var mUVCCamera: UVCCamera? = null

    private lateinit var overlay: OverlayViewWebcam
    private lateinit var objectDetectorHelper: ObjectDetectorHelper
    private val viewModel: MainViewModel by activityViewModels()
    private var initialModelPath: String = ""
    private val modelMap = mutableMapOf<String, String>()

    private val DEFAULT_PREVIEW_WIDTH = 640
    private val DEFAULT_PREVIEW_HEIGHT = 480

    private val detectionInterval = 150L
    private var detectionJob: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _fragmentWebcamBinding = FragmentWebcamBinding.inflate(inflater, container, false)
        return fragmentWebcamBinding.root
    }

    override fun onResume() {
        super.onResume()
        if (!PermissionsFragment.hasPermissions(requireContext())) {
        }
    }

    override fun onPause() {
        super.onPause()
        if (this::objectDetectorHelper.isInitialized) {
            viewModel.setDelegate(objectDetectorHelper.currentDelegate)
            viewModel.setThreshold(objectDetectorHelper.threshold)
            viewModel.setMaxResults(objectDetectorHelper.maxResults)
            objectDetectorHelper.clearObjectDetector()
        }
    }

    override fun onDestroyView() {
        _fragmentWebcamBinding = null
        super.onDestroyView()

        mUVCCamera?.stopPreview()
        mUVCCamera?.destroy()
        mUVCCamera = null

        mUSBMonitor.unregister()

        detectionJob?.cancel()

    }

    private suspend fun copyModelFromAssets(fileName: String) {
        val assetManager = requireContext().assets
        val outputDir = File(requireContext().filesDir, "models")
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }
        val outputFile = File(outputDir, fileName)

        if (!outputFile.exists()) {
            withContext(Dispatchers.IO) {
                try {
                    assetManager.open(fileName).use { inputStream ->
                        FileOutputStream(outputFile).use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                    Log.i(TAG, "✅ Model '$fileName' berhasil disalin dari assets.")
                } catch (e: IOException) {
                    Log.e(TAG, "❌ Gagal menyalin model dari assets: ${e.message}", e)
                    throw e
                }
            }
        }
    }

    private fun reloadModelsAndUpdateUI() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            try {
                val defaultModelFileName = "efficientdet-lite0.tflite"

                copyModelFromAssets(defaultModelFileName)

                val modelDir = File(requireContext().filesDir, "models")
                val modelFile = modelDir.listFiles { _, name -> name.endsWith(".tflite") }?.firstOrNull()
                initialModelPath = modelFile?.absolutePath ?: throw IllegalStateException("Tidak ada model ditemukan setelah copy.")

                objectDetectorHelper =
                    withContext(Dispatchers.IO) {
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

            } catch (e: Exception) {
                Log.e(TAG, "Gagal memuat ulang model setelah refresh", e)
                Toast.makeText(context, "Gagal update UI: ${e.message}", Toast.LENGTH_LONG).show()
                startRealTimeDetection()
            } finally {
            }
        }
    }

    @SuppressLint("MissingPermission", "ResourceType")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        overlay = fragmentWebcamBinding.overlay
        mUVCCameraView = fragmentWebcamBinding.textureView

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            val modelDir = File(requireContext().filesDir, "models")
            if (!modelDir.exists() || modelDir.listFiles { _, name -> name.endsWith(".tflite") }.isNullOrEmpty()) {
                Toast.makeText(requireContext(), "Tidak ada model .tflite yang ditemukan. Silakan sinkronkan di tab lain.", Toast.LENGTH_LONG).show()
                setupSyncButton()
                return@launch
            }

            val modelFile = modelDir.listFiles { _, name -> name.endsWith(".tflite") }?.firstOrNull()
            initialModelPath = modelFile?.absolutePath ?: ""

            if (initialModelPath.isNotEmpty()) {
                objectDetectorHelper =
                    withContext(Dispatchers.IO) {
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
                setupSyncButton()
            }
        }
        initBottomSheetControls()

        val bottomNavigationView = requireActivity().findViewById<BottomNavigationView>(R.id.navigation)
        val toolbarView = requireActivity().findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)

        // Manage rotation
        view.viewTreeObserver.addOnGlobalLayoutListener {
            context?.let { safeContext ->
                val orientation = safeContext.resources.configuration.orientation
                val displayMetrics = resources.displayMetrics

                val cameraContainer = fragmentWebcamBinding.webcamContainer
                val textureView = fragmentWebcamBinding.textureView
                val overlayView = fragmentWebcamBinding.overlay

                val params = cameraContainer.layoutParams as CoordinatorLayout.LayoutParams

                // if rotation landscape
                if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    val targetWidth = (displayMetrics.widthPixels * 0.8).toInt()
                    val targetHeight = (targetWidth * DEFAULT_PREVIEW_HEIGHT / DEFAULT_PREVIEW_WIDTH)
                    val offsetLeft = (displayMetrics.widthPixels - targetWidth) / 2

                    params.width = targetWidth
                    params.height = targetHeight
                    params.gravity = Gravity.CENTER

                    cameraContainer.layoutParams = params
                    textureView.setAspectRatio(DEFAULT_PREVIEW_WIDTH, DEFAULT_PREVIEW_HEIGHT)

                    overlayView.setPreviewLayout(
                        offsetLeft,
                        0,
                        targetWidth,
                        targetHeight
                    )

                    // bottom nav and navabar hidden
                    bottomNavigationView.visibility = View.GONE
                    toolbarView.visibility = View.GONE

                } else {
                    // if rotation potrait
                    val targetWidth = displayMetrics.widthPixels
                    val targetHeight = (targetWidth * 3) / 4

                    params.width = targetWidth
                    params.height = targetHeight
                    params.gravity = Gravity.CENTER_VERTICAL

                    cameraContainer.layoutParams = params
                    textureView.setAspectRatio(DEFAULT_PREVIEW_WIDTH, DEFAULT_PREVIEW_HEIGHT)

                    overlayView.setPreviewLayout(0, 0, targetWidth, targetHeight)

                    bottomNavigationView.visibility = View.VISIBLE
                    toolbarView.visibility = View.VISIBLE
                }
            }
        }

        context?.let { ctx ->
            mUSBMonitor = USBMonitor(requireContext(), object : USBMonitor.OnDeviceConnectListener {
                override fun onAttach(device: UsbDevice) {
                    Toast.makeText(context, "Device attached: ${device.deviceName}", Toast.LENGTH_SHORT).show()
                    mUSBMonitor.requestPermission(device)
                }

                override fun onConnect(device: UsbDevice?, controlBlock: USBMonitor.UsbControlBlock?, createNew: Boolean) {
                    activity?.runOnUiThread {
                        try {
                            mUVCCamera = UVCCamera().apply {
                                open(controlBlock)
                                setPreviewSize(DEFAULT_PREVIEW_WIDTH, DEFAULT_PREVIEW_HEIGHT, UVCCamera.FRAME_FORMAT_MJPEG)
                                setPreviewTexture(mUVCCameraView.surfaceTexture)
                                startPreview()
                            }
                        } catch (e: Exception) {
                            Toast.makeText(context, "Gagal membuka kamera: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }

                override fun onDisconnect(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
                    Toast.makeText(context, "Camera disconnected", Toast.LENGTH_SHORT).show()
                    mUVCCamera?.stopPreview()
                    mUVCCamera?.destroy()
                    mUVCCamera = null
                }

                override fun onDetach(device: UsbDevice?) {
                    Toast.makeText(context, "Webcam dicabut", Toast.LENGTH_SHORT).show()
                }

                override fun onCancel(device: UsbDevice?) {
                    Toast.makeText(context, "Izin USB ditolak", Toast.LENGTH_SHORT).show()
                }
            })
        }
    }
    private fun formatModelNameForDisplay(filename: String): String {
        return filename
            .substringAfter('_')
            .removeSuffix(".tflite")
            .replace('_', ' ')
            .split(' ')
            .joinToString(" ") { it.replaceFirstChar(Char::titlecase) }
    }

    // Sync models from server
    private fun syncModels() {
        val syncButton = fragmentWebcamBinding.bottomSheetLayout.syncButton

        Log.d(TAG, "Sync button triggered.")
        Toast.makeText(context, "Syncing models...", Toast.LENGTH_SHORT).show()

        // loading
        syncButton.isEnabled = false
        syncButton.text = "Loading.."

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    ModelManager.getModelFilePath(requireContext())
                }
                Toast.makeText(context, "Sync successful!", Toast.LENGTH_SHORT).show()
                reloadModelsAndUpdateUI()
            } catch (e: Exception) {
                Toast.makeText(context, "Sync failed: ${e.message}", Toast.LENGTH_LONG).show()
                Log.e(TAG, "Sync failed", e)
            } finally {
                // end loading state
                syncButton.text = "Sync Models"
                syncButton.isEnabled = true
            }
        }
    }

    // button sync
    private fun setupSyncButton() {
        val syncButton = fragmentWebcamBinding.bottomSheetLayout.syncButton // Asumsi Anda menggunakan ViewBinding atau findViewById

        syncButton.setOnClickListener {
            syncModels()
        }
    }

    private fun populateModelSpinner() {
        val modelDir = File(requireContext().filesDir, "models")
        if (!modelDir.exists()) {
            Log.e(TAG, "Models directory does not exist.")
            return
        }
        modelMap.clear()
        modelDir.listFiles { _, name -> name.endsWith(".tflite") }?.forEach { file ->
            val cleanName = formatModelNameForDisplay(file.name)
            modelMap[cleanName] = file.name
        }

        if (modelMap.isEmpty()) {
            Log.e(TAG, "No .tflite models found.")
            return
        }

        val displayNames = modelMap.keys.toList()
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, displayNames)
        fragmentWebcamBinding.bottomSheetLayout.spinnerModel.adapter = adapter

        val currentModelName = File(initialModelPath).name
        val currentDisplayName = formatModelNameForDisplay(currentModelName)
        val currentModelPosition = displayNames.indexOf(currentDisplayName)
        if (currentModelPosition != -1) {
            fragmentWebcamBinding.bottomSheetLayout.spinnerModel.setSelection(currentModelPosition, false)
        }
    }

    private fun initBottomSheetControls() {
        fragmentWebcamBinding.bottomSheetLayout.spinnerModel.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val selectedDisplayName = parent?.getItemAtPosition(position).toString()
                    val originalFileName = modelMap[selectedDisplayName] ?: return
                    val newModelPath = File(requireContext().filesDir, "models/$originalFileName").absolutePath
                    if (isAdded && this@WebcamFragment::objectDetectorHelper.isInitialized && newModelPath != objectDetectorHelper.modelPath) {
                        detectionJob?.cancel()
                        viewLifecycleOwner.lifecycleScope.launch {
                            withContext(Dispatchers.IO) {
                                objectDetectorHelper.changeModel(newModelPath)
                            }
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "Model changed to ${selectedDisplayName}.", Toast.LENGTH_SHORT).show()
                                startRealTimeDetection()
                            }
                        }
                    }
                }
                override fun onNothingSelected(p0: AdapterView<*>?) {}
            }
    }

    private fun getDeviceRotation(): Int {
        return when (resources.configuration.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> 90
            else -> 0
        }
    }

    override fun onResults(resultBundle: ObjectDetectorHelper.ResultBundle) {
        activity?.runOnUiThread {
            if (isAdded) {
                val detectionResult = resultBundle.results.firstOrNull()
                if (detectionResult != null && detectionResult.detections().isNotEmpty()) {

                    fragmentWebcamBinding.overlay.setResults(
                        detectionResult,
                        DEFAULT_PREVIEW_HEIGHT,
                        DEFAULT_PREVIEW_WIDTH,
                        resultBundle.inputImageRotation
                    )

                } else {
                    fragmentWebcamBinding.overlay.clear()
                }
                fragmentWebcamBinding.overlay.invalidate()
            }
        }
    }
    override fun onError(error: String, errorCode: Int) {
        activity?.runOnUiThread {
            Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
        }
    }
    override fun onStart() {
        super.onStart()
        context?.let { ctx ->
            AlertDialog.Builder(ctx)
                .setTitle("Gunakan Webcam")
                .setMessage(
                    "1. Pastikan webcam sudah tersambung\n\n" +
                            "2. Pastikan memilih transfer file\n\n" +
                            "3. Jika muncul dialog izin, tekan oke"
                )
                .setIcon(R.drawable.webcam)
                .setPositiveButton("Saya Mengerti") { dialog, _ ->
                    dialog.dismiss()
                }
                .setCancelable(false)
                .create()
                .apply {
                    show()
                    getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(
                        ContextCompat.getColor(ctx, R.color.mp_primary)
                    )
                }
        }
        mUSBMonitor.register()
    }
    override fun onStop() {
        super.onStop()

        detectionJob?.cancel()
    }
    private fun startRealTimeDetection() {
        detectionJob?.cancel()
        detectionJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                detectFromWebcam()
                delay(detectionInterval)
            }
        }
    }

    private fun cropBitmapToSquare(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val newDimension = minOf(width, height)

        val x = (width - newDimension) / 2
        val y = (height - newDimension) / 2

        return Bitmap.createBitmap(bitmap, x, y, newDimension, newDimension)
    }

    private fun detectFromWebcam() {
        if (!isAdded || mUVCCameraView.bitmap == null) return

        try {
            val frameBitmap = mUVCCameraView.getBitmap() ?: return

            val croppedBitmap = cropBitmapToSquare(frameBitmap)

            val modelInputBitmap = Bitmap.createScaledBitmap(croppedBitmap, 384, 384, true)

            objectDetectorHelper.detectFromBitmap(modelInputBitmap, getDeviceRotation())

        } catch (e: Exception) {
            Log.e(TAG, "Error in detectFromWebcam: ${e.message}", e)
        }
    }
}