package com.onyx.browser.ui.qr

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Size
import android.view.MotionEvent
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.onyx.browser.R
import com.onyx.browser.databinding.ActivityQrScannerBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class QrScannerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityQrScannerBinding
    private var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var camera: Camera? = null
    private var isScanned = false
    private var isTorchOn = false
    private var laserAnimator: ObjectAnimator? = null

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission required for QR scanning", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { processImageFromUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge transparent system bars
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        binding = ActivityQrScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Apply WindowInsets to avoid collision with status bar, camera hole, and nav bar
        ViewCompat.setOnApplyWindowInsetsListener(binding.qrScannerRoot) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.topBarContainer.updatePadding(top = systemBars.top)
            binding.bottomControlsContainer.updatePadding(bottom = systemBars.bottom)
            insets
        }

        binding.btnQrClose.setOnClickListener { finish() }

        binding.btnToggleTorch.setOnClickListener {
            toggleTorch()
        }

        binding.btnScanFromGallery.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        setupTapToFocus()
        startLaserAnimation()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startLaserAnimation() {
        binding.viewfinderBox.post {
            val totalDistance = (binding.viewfinderBox.height - binding.viewLaserLine.height - 24).coerceAtLeast(100).toFloat()
            laserAnimator = ObjectAnimator.ofFloat(binding.viewLaserLine, "translationY", 8f, totalDistance).apply {
                duration = 2000L
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTapToFocus() {
        binding.previewView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                val cam = camera ?: return@setOnTouchListener true
                val factory = binding.previewView.meteringPointFactory
                val point = factory.createPoint(event.x, event.y)
                val action = FocusMeteringAction.Builder(point).build()
                cam.cameraControl.startFocusAndMetering(action)
            }
            true
        }
    }

    private fun toggleTorch() {
        val cam = camera
        if (cam == null) {
            Toast.makeText(this, "Camera not ready", Toast.LENGTH_SHORT).show()
            return
        }
        if (!cam.cameraInfo.hasFlashUnit()) {
            Toast.makeText(this, "Flashlight not available", Toast.LENGTH_SHORT).show()
            return
        }

        isTorchOn = !isTorchOn
        cam.cameraControl.enableTorch(isTorchOn)
        binding.btnToggleTorch.setImageResource(
            if (isTorchOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off
        )
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }
            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        processImage(imageProxy)
                    }
                }
            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun processImage(imageProxy: ImageProxy) {
        if (isScanned) {
            imageProxy.close()
            return
        }
        val mediaImage = imageProxy.image ?: run {
            imageProxy.close()
            return
        }
        val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        val scanner = BarcodeScanning.getClient()
        scanner.process(inputImage)
            .addOnSuccessListener { barcodes ->
                barcodes.firstOrNull { it.rawValue != null }?.let { barcode ->
                    handleScannedValue(barcode.rawValue ?: "")
                }
            }
            .addOnCompleteListener { imageProxy.close() }
    }

    private fun processImageFromUri(uri: Uri) {
        try {
            val inputImage = InputImage.fromFilePath(this, uri)
            val scanner = BarcodeScanning.getClient()
            scanner.process(inputImage)
                .addOnSuccessListener { barcodes ->
                    val barcode = barcodes.firstOrNull { it.rawValue != null }
                    if (barcode != null) {
                        handleScannedValue(barcode.rawValue ?: "")
                    } else {
                        Toast.makeText(this, "No QR code found in selected image", Toast.LENGTH_SHORT).show()
                    }
                }
                .addOnFailureListener {
                    Toast.makeText(this, "Failed to read selected image", Toast.LENGTH_SHORT).show()
                }
        } catch (e: Exception) {
            Toast.makeText(this, "Error processing image", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleScannedValue(value: String) {
        if (isScanned) return
        isScanned = true

        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(100)
            }
        } catch (_: Exception) {}

        val intent = Intent().apply {
            putExtra(RESULT_QR_VALUE, value)
        }
        setResult(RESULT_OK, intent)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        laserAnimator?.cancel()
        laserAnimator = null
        cameraExecutor.shutdown()
    }

    companion object {
        const val RESULT_QR_VALUE = "qr_value"
    }
}
