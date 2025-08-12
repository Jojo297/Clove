package com.google.mediapipe.examples.objectdetection.fragments

import android.app.AlertDialog
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.hardware.usb.UsbDevice
import android.os.Bundle
import android.provider.MediaStore.Images.Media.getBitmap
import android.util.Log
import android.view.LayoutInflater
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import android.widget.Toolbar
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.mediapipe.examples.objectdetection.ObjectDetectorHelper
import com.google.mediapipe.examples.objectdetection.OverlayView
import com.google.mediapipe.examples.objectdetection.R
import com.google.mediapipe.examples.objectdetection.databinding.FragmentWebcamBinding
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.callback.IDeviceConnectCallBack
import com.jiangdg.ausbc.utils.ToastUtils.show
import com.jiangdg.ausbc.widget.AspectRatioTextureView
import com.jiangdg.ausbc.widget.IAspectRatio
import com.jiangdg.usb.USBMonitor
import com.jiangdg.uvc.UVCCamera
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File


class WebcamFragment : Fragment(), ObjectDetectorHelper.DetectorListener {
    private lateinit var mUVCCameraView: AspectRatioTextureView
    private lateinit var mUSBMonitor: USBMonitor
    private var mUVCCamera: UVCCamera? = null

    private lateinit var overlay: OverlayView
    private lateinit var objectDetectorHelper: ObjectDetectorHelper

    private val DEFAULT_PREVIEW_WIDTH = 640
    private val DEFAULT_PREVIEW_HEIGHT = 480

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_webcam, container, false)

        overlay = view.findViewById(R.id.overlay)
        mUVCCameraView = view.findViewById(R.id.textureView)

        return view
    }

    private val detectionInterval = 150L
    private var detectionJob: Job? = null

    private fun startRealTimeDetection() {
//        Toast.makeText(context, "startRealTimeDetection dipanggil", Toast.LENGTH_SHORT).show()

        detectionJob?.cancel()
        detectionJob = viewLifecycleOwner.lifecycleScope.launch  {
            while (isActive) {
                detectFromWebcam()
                delay(detectionInterval)
            }
        }
    }

    private fun detectFromWebcam() {
//        Toast.makeText(context, "detectFromWebcam dipanggil", Toast.LENGTH_SHORT).show()

        val bitmap = try {
            mUVCCameraView.getBitmap()?.let { frame ->
                Bitmap.createScaledBitmap(frame, 384, 384, true)
            }
        } catch (e: Exception) {
            context?.let {
                Toast.makeText(
                    context,
                    "Error di detectFromWebcam: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
                null
            }
        }

        bitmap?.let {
            objectDetectorHelper.detectFromBitmap(it, getDeviceRotation())
        }
    }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // DIUBAH: Logika untuk mencari model dan menginisialisasi ObjectDetectorHelper

        // 1. Cari model yang tersedia di penyimpanan internal
        val modelDir = File(requireContext().filesDir, "models")
        val modelFile = modelDir.listFiles { _, name -> name.endsWith(".tflite") }?.firstOrNull()

        // 2. Jika model tidak ditemukan, tampilkan pesan dan hentikan proses
        if (modelFile == null) {
            Toast.makeText(context, "Model tidak ditemukan di penyimpanan internal.", Toast.LENGTH_LONG).show()
            Log.e("WebcamFragment", "No .tflite model found in ${modelDir.absolutePath}")
            return
        }
        context?.let { safeContext ->
            objectDetectorHelper = ObjectDetectorHelper(
                context = requireContext(),
                runningMode = RunningMode.LIVE_STREAM,
                objectDetectorListener = this,
                modelPath = modelFile.absolutePath // Berikan path model yang valid
            )
            overlay.setRunningMode(RunningMode.LIVE_STREAM)
        }

        // Inisialisasi view
        val textureView = view.findViewById<AspectRatioTextureView>(R.id.textureView)
        val cameraContainer = view.findViewById<FrameLayout>(R.id.webcam_container)

        val bottomNavigationView = requireActivity().findViewById<BottomNavigationView>(R.id.navigation)
        val toolbarView = requireActivity().findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)

        // Deteksi perubahan orientasi
        view.viewTreeObserver.addOnGlobalLayoutListener {
            context?.let { safeContext ->
//            val orientation = resources.configuration.orientation
                val orientation = context?.resources?.configuration?.orientation
                val displayMetrics = resources.displayMetrics

                if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    // Landscape mode - lebarkan 70% dari lebar layar
                    val targetWidth = (displayMetrics.widthPixels * 0.8).toInt()
                    val targetHeight =
                        (targetWidth * DEFAULT_PREVIEW_HEIGHT / DEFAULT_PREVIEW_WIDTH)

                    val params = cameraContainer.layoutParams as ConstraintLayout.LayoutParams
                    params.dimensionRatio = null
                    params.width = targetWidth
                    params.height = targetHeight
                    params.matchConstraintMaxWidth = displayMetrics.widthPixels // Batas maksimum
                    cameraContainer.layoutParams = params

                    textureView.setAspectRatio(DEFAULT_PREVIEW_WIDTH, DEFAULT_PREVIEW_HEIGHT)

                    // Sembunyikan bottom nav
                    bottomNavigationView.visibility = View.GONE
                    toolbarView.visibility = View.GONE

                } else {
                    // Portrait mode - 4:3 aspect ratio di tengah
                    val params = cameraContainer.layoutParams as ConstraintLayout.LayoutParams
                    params.dimensionRatio = "H,4:3" // Set aspect ratio 4:3 (height:width)
                    params.width = 0
                    params.height = 0
                    cameraContainer.layoutParams = params

                    textureView.setAspectRatio(DEFAULT_PREVIEW_WIDTH, DEFAULT_PREVIEW_HEIGHT)
                }
            }
        }

        context?.let { ctx ->
        mUSBMonitor = USBMonitor(requireContext(), object : USBMonitor.OnDeviceConnectListener {

            override fun onAttach(device: UsbDevice) {
                context?.let {
                    Toast.makeText(
                        context,
                        "Device attached: ${device.deviceName}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                    mUSBMonitor.requestPermission(device)
            }

            override fun onConnect(
                device: UsbDevice?,
                controlBlock: USBMonitor.UsbControlBlock?,
                createNew: Boolean
            ) {
                activity?.runOnUiThread {
                    try {
//                        Toast.makeText(context, "onConnect: mencoba membuka kamera", Toast.LENGTH_SHORT).show()
                        mUVCCamera = UVCCamera().apply {
                            open(controlBlock)
                            setPreviewSize(DEFAULT_PREVIEW_WIDTH, DEFAULT_PREVIEW_HEIGHT, UVCCamera.FRAME_FORMAT_MJPEG)
                            setPreviewTexture(mUVCCameraView.surfaceTexture)
                            startPreview()
                        }

//                        Toast.makeText(context, "Preview dimulai", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        context?.let {
                            Toast.makeText(
                                context,
                                "Gagal membuka kamera: ${e.message}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }

            override fun onDisconnect(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
                context?.let {
                    Toast.makeText(context, "Camera disconnected", Toast.LENGTH_SHORT).show()
                }
                    mUVCCamera?.stopPreview()
                mUVCCamera?.destroy()
                mUVCCamera = null
            }

            override fun onDetach(device: UsbDevice?) {
                context?.let {
                Toast.makeText(context, "Webcam dicabut", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onCancel(device: UsbDevice?) {
                context?.let {
                    Toast.makeText(context, "Izin USB ditolak", Toast.LENGTH_SHORT).show()
                }
            }
        })
        }
    }

    // Get device rotation to pass to the detector
    private fun getDeviceRotation(): Int {
        return when (resources.configuration.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> 90
            else -> 0
        }
    }

    override fun onResults(resultBundle: ObjectDetectorHelper.ResultBundle) {
        activity?.runOnUiThread {
            try {
                val result = resultBundle.results.firstOrNull() ?: return@runOnUiThread

                overlay.setResults(
                    result,
                    resultBundle.inputImageHeight,
                    resultBundle.inputImageWidth,
                    resultBundle.inputImageRotation
                )
            } catch (e: Exception) {
                context?.let {
                    Toast.makeText(it, "Error saat proses hasil deteksi: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }



    override fun onError(error: String, errorCode: Int) {
        context?.let {
            Toast.makeText(context, "Terjadi kesalahan: $error ($errorCode)", Toast.LENGTH_SHORT)
                .show()
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
        startRealTimeDetection()
    }

    override fun onStop() {
        super.onStop()
        mUSBMonitor.unregister()
        mUVCCamera?.stopPreview()
        mUVCCamera?.destroy()
        detectionJob?.cancel()
        mUVCCamera = null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        mUSBMonitor.unregister()
        mUVCCamera?.stopPreview()
        mUVCCamera?.destroy()
        detectionJob?.cancel()
        mUVCCamera = null
    }


}


