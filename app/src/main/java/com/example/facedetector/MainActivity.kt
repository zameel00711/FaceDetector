package com.example.facedetector

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.Executors
import kotlin.math.max

private val Navy = Color(0xFF0D2B5C)
private val Blue = Color(0xFF2F6FED)
private val Bg = Color(0xFFF4F7FC)

/** Detected face boxes in (rotated) image coordinates plus the image size. */
class FaceResult(val boxes: List<Rect>, val width: Int, val height: Int, val mirrored: Boolean)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Blue, onPrimary = Color.White)) {
                FaceDetectorScreen()
            }
        }
    }
}

@Composable
fun FaceDetectorScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    fun granted() = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

    var hasPermission by remember { mutableStateOf(granted()) }
    var permissionDenied by remember { mutableStateOf(false) }
    var cameraOn by remember { mutableStateOf(true) }
    var cameraKey by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<FaceResult?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasPermission = it
        permissionDenied = !it
    }
    // Re-check permission when returning from system settings.
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) hasPermission = granted()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(Unit) { if (!hasPermission) launcher.launch(Manifest.permission.CAMERA) }

    val count = if (cameraOn) result?.boxes?.size ?: 0 else 0

    Column(
        Modifier.fillMaxSize().background(Bg).systemBarsPadding().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Face Detector", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Navy)
        Text("Real-time face detection", fontSize = 15.sp, color = Blue)
        Spacer(Modifier.height(16.dp))

        Box(
            Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Navy),
            contentAlignment = Alignment.Center
        ) {
            when {
                !hasPermission -> Message(
                    if (permissionDenied) "Camera permission was denied. Face detection needs the camera to work."
                    else "Waiting for camera permission…"
                ) {
                    Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("Grant permission") }
                    if (permissionDenied) {
                        TextButton(onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                            )
                        }) { Text("Open settings", color = Color.White) }
                    }
                }
                error != null -> Message(error!!) {
                    Button(onClick = { error = null; cameraKey++ }) { Text("Retry") }
                }
                !cameraOn -> Message("Camera is stopped.") {}
                else -> {
                    key(cameraKey) {
                        CameraView(
                            onResult = { result = it },
                            onError = { error = it; result = null }
                        )
                    }
                    result?.let { r -> BoxesOverlay(r) }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Card(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(Modifier.padding(16.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    when {
                        !hasPermission -> "Camera permission needed"
                        error != null -> "Camera error"
                        !cameraOn -> "Camera stopped"
                        count > 0 -> "Face detected"
                        else -> "No face detected"
                    },
                    fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    color = if (cameraOn && count > 0) Blue else Navy
                )
                Text("Faces detected: $count", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Navy)
            }
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { cameraOn = !cameraOn; result = null; error = null },
            enabled = hasPermission,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(16.dp)
        ) { Text(if (cameraOn) "Stop Camera" else "Start Camera") }
        Spacer(Modifier.height(10.dp))
        Text(
            "Detects faces on your device only. It does not identify people, and no images are saved or uploaded.",
            fontSize = 12.sp, color = Color.Gray, textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun Message(text: String, actions: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, color = Color.White, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        actions()
    }
}

@Composable
private fun BoxesOverlay(r: FaceResult) {
    Canvas(Modifier.fillMaxSize()) {
        // PreviewView uses FILL_CENTER: scale to cover, crop the overflow equally.
        val scale = max(size.width / r.width, size.height / r.height)
        val dx = (size.width - r.width * scale) / 2f
        val dy = (size.height - r.height * scale) / 2f
        r.boxes.forEach { b ->
            var left = b.left * scale + dx
            var right = b.right * scale + dx
            if (r.mirrored) { val l = size.width - right; right = size.width - left; left = l }
            val top = b.top * scale + dy
            val bottom = b.bottom * scale + dy
            drawRect(Color(0xFF4FC3F7), Offset(left, top), Size(right - left, bottom - top), style = Stroke(4.dp.toPx()))
        }
    }
}

@Composable
fun CameraView(onResult: (FaceResult) -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }

    DisposableEffect(lifecycleOwner) {
        val executor = Executors.newSingleThreadExecutor()
        val detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .build()
        )
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var disposed = false

        future.addListener({
            if (disposed) return@addListener
            try {
                val p = future.get()
                provider = p
                val selector = when {
                    p.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                    p.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                    else -> { onError("No camera available on this device."); return@addListener }
                }
                val front = selector == CameraSelector.DEFAULT_FRONT_CAMERA
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                var last = 0L
                analysis.setAnalyzer(executor) { proxy ->
                    val now = System.currentTimeMillis()
                    val media = proxy.image
                    if (media == null || now - last < 80 || disposed) { proxy.close(); return@setAnalyzer }
                    last = now
                    val rot = proxy.imageInfo.rotationDegrees
                    val w = if (rot % 180 == 0) proxy.width else proxy.height
                    val h = if (rot % 180 == 0) proxy.height else proxy.width
                    try {
                        detector.process(InputImage.fromMediaImage(media, rot))
                            .addOnSuccessListener { faces ->
                                if (!disposed) onResult(FaceResult(faces.map { it.boundingBox }, w, h, front))
                            }
                            .addOnCompleteListener { proxy.close() } // always release the frame
                    } catch (e: Exception) {
                        proxy.close()
                    }
                }
                p.unbindAll()
                p.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
            } catch (e: Exception) {
                onError("Could not start the camera. It may be in use by another app.")
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            disposed = true
            provider?.unbindAll() // releases the camera
            executor.shutdown()
            detector.close()
        }
    }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}
