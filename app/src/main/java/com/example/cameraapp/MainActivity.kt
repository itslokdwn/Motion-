package com.example.cameraapp

// Core Android and Permission imports
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log

// Jetpack Compose and UI imports
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView

// CameraX imports
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.example.cameraapp.ui.theme.CameraAppTheme

// ML Kit imports
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions

// Utility imports
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Main Activity class that handles camera permissions and initialization
 */
class MainActivity : ComponentActivity() {
    // Executor for running camera operations in background
    private lateinit var cameraExecutor: ExecutorService

    // ML Kit pose detector instance
    private lateinit var poseDetector: PoseDetector

    // Permission launcher for requesting camera access
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startCamera()
        } else {
            showPermissionDeniedMessage()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize background thread for camera operations
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Configure and create the pose detector
        val options = PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE) // Optimized for video/streaming
            .build()
        poseDetector = PoseDetection.getClient(options)

        // Set up the UI using Jetpack Compose
        setContent {
            CameraAppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    CameraScreen(
                        onCameraClick = { checkCameraPermission() },
                        cameraExecutor = cameraExecutor,
                        poseDetector = poseDetector
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up resources
        cameraExecutor.shutdown()
        poseDetector.close()
    }

    /**
     * Checks if we have camera permission and requests it if needed
     */
    private fun checkCameraPermission() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> {
                startCamera() // Permission already granted
            }
            else -> {
                requestPermissionLauncher.launch(Manifest.permission.CAMERA) // Request permission
            }
        }
    }

    private fun startCamera() {
        // Camera initialization is handled in the Composable
    }

    private fun showPermissionDeniedMessage() {
        // Implement permission denied message handling
    }
}

/**
 * Main UI composable that handles camera preview and motion detection
 */
@Composable
fun CameraScreen(
    onCameraClick: () -> Unit,
    cameraExecutor: ExecutorService,
    poseDetector: PoseDetector
) {
    // Get the current context and lifecycle owner for CameraX
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // State to track if camera is running
    var isCameraStarted by remember { mutableStateOf(false) }

    // Timestamp tracker for 2-second intervals
    val lastAnalysisTimestamp = remember { AtomicLong(0L) }

    // Time formatter for logging
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    // Main container
    Box(modifier = Modifier.fillMaxSize()) {
        if (isCameraStarted) {
            // Camera Preview
            AndroidView(
                factory = { context ->
                    // Create the PreviewView for camera feed
                    PreviewView(context).also { previewView ->
                        previewView.implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                    }
                },
                modifier = Modifier.fillMaxSize(),
                update = { previewView ->
                    // Set up CameraX
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()

                        // Configure camera preview
                        val preview = Preview.Builder()
                            .build()
                            .apply {
                                setSurfaceProvider(previewView.surfaceProvider)
                            }

                        // Configure image analysis for motion detection
                        val imageAnalyzer = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                            .apply {
                                setAnalyzer(cameraExecutor) { imageProxy ->
                                    val currentTime = System.currentTimeMillis()
                                    val lastAnalysis = lastAnalysisTimestamp.get()

                                    // Check if 2 seconds have passed since last analysis
                                    if (currentTime - lastAnalysis >= 2000) {
                                        Log.d(TAG, "🔍 Scanning for motion...")
                                        processImage(imageProxy, poseDetector, timeFormat)
                                        lastAnalysisTimestamp.set(currentTime)
                                    } else {
                                        imageProxy.close() // Release the image if we're not processing it
                                    }
                                }
                            }

                        try {
                            // Bind camera use cases to lifecycle
                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                imageAnalyzer
                            )
                        } catch (exc: Exception) {
                            Log.e(TAG, "Use case binding failed", exc)
                        }
                    }, ContextCompat.getMainExecutor(context))
                }
            )
        } else {
            // Start Camera Button
            Button(
                onClick = {
                    isCameraStarted = true
                    onCameraClick()
                },
                modifier = Modifier.align(Alignment.Center)
            ) {
                Text("Start Motion Detection")
            }
        }
    }
}

/**
 * Processes camera images and detects motion using ML Kit Pose Detection
 */
@OptIn(ExperimentalGetImage::class)
private fun processImage(
    imageProxy: ImageProxy,
    poseDetector: PoseDetector,
    timeFormat: SimpleDateFormat
) {
    // Get the image from the camera feed
    val mediaImage = imageProxy.image
    if (mediaImage != null) {
        // Convert the image to ML Kit's required format
        val inputImage = InputImage.fromMediaImage(
            mediaImage,
            imageProxy.imageInfo.rotationDegrees
        )

        // Process the image with ML Kit pose detector
        poseDetector.process(inputImage)
            .addOnSuccessListener { pose ->
                val currentTime = timeFormat.format(Date())
                if (pose.allPoseLandmarks.isNotEmpty()) {
                    // Calculate the center position of detected pose
                    val xCoordinates = pose.allPoseLandmarks.map { it.position3D.x }
                    val yCoordinates = pose.allPoseLandmarks.map { it.position3D.y }
                    val centerX = xCoordinates.average()
                    val centerY = yCoordinates.average()

                    // Log detection results
                    Log.d(TAG, """
                        🚨 Motion Detected at $currentTime
                        Position: (${String.format("%.1f", centerX)}, ${String.format("%.1f", centerY)})
                        Landmarks: ${pose.allPoseLandmarks.size}
                        ----------------------------------------
                    """.trimIndent())
                } else {
                    // Log when no motion is detected
                    Log.d(TAG, "No motion detected at $currentTime")
                }
            }
            .addOnFailureListener { exception ->
                // Log any errors that occur during detection
                Log.e(TAG, "❌ Detection failed: ${exception.message}", exception)
            }
            .addOnCompleteListener {
                // Always close the imageProxy to release resources
                imageProxy.close()
            }
    } else {
        // Close the imageProxy if no image is available
        imageProxy.close()
    }
}

// Tag for logging
private const val TAG = "MotionDetection"