package com.example.temihelloworld

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Bundle
import android.util.Size
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.robotemi.sdk.Robot
import com.robotemi.sdk.TtsRequest
import com.robotemi.sdk.listeners.OnRobotReadyListener
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), OnRobotReadyListener {

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 42
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }

    private lateinit var robot: Robot
    private lateinit var viewFinder: PreviewView
    private lateinit var cameraExecutor: ExecutorService

    @Volatile
    private var latestJpeg: ByteArray? = null

    private lateinit var httpServer: SimpleHttpServer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewFinder = findViewById(R.id.viewFinder)
        viewFinder.implementationMode = PreviewView.ImplementationMode.PERFORMANCE
        viewFinder.scaleType = PreviewView.ScaleType.FIT_CENTER

        cameraExecutor = Executors.newSingleThreadExecutor()
        robot = Robot.getInstance()

        // Start small HTTP server on port 8080
        httpServer = SimpleHttpServer(8080) { latestJpeg }
        try {
            httpServer.start()
            Toast.makeText(this, "HTTP server running on port 8080", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Failed to start HTTP server", Toast.LENGTH_SHORT).show()
        }

        // Camera permission + start camera
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this,
                REQUIRED_PERMISSIONS,
                REQUEST_CODE_PERMISSIONS
            )
        }
    }

    override fun onStart() {
        super.onStart()
        robot.addOnRobotReadyListener(this)
    }

    override fun onStop() {
        super.onStop()
        robot.removeOnRobotReadyListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        httpServer.stop()
    }

    // === Temi SDK callback ===
    override fun onRobotReady(isReady: Boolean) {
        if (!isReady) return

        try {
            val activityInfo: ActivityInfo = packageManager.getActivityInfo(
                componentName,
                PackageManager.GET_META_DATA
            )
            robot.onStart(activityInfo)
        } catch (e: PackageManager.NameNotFoundException) {
            e.printStackTrace()
        }

        robot.speak(
            TtsRequest.create(
                "Camera streaming server is running.",
                false
            )
        )
    }

    // === Camera permission helpers ===
    private fun allPermissionsGranted(): Boolean =
        REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(baseContext, it) ==
                    PackageManager.PERMISSION_GRANTED
        }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "Camera permission denied", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    // === CameraX setup ===
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .setTargetResolution(Size(1280, 720))
                .build()
                .also {
                    it.setSurfaceProvider(viewFinder.surfaceProvider)
                }

            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 360)) // smaller -> less bandwidth
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        val jpeg = yuvToJpegWithRotation(imageProxy)
                        if (jpeg != null) {
                            latestJpeg = jpeg
                        }
                        imageProxy.close()
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )
            } catch (exc: Exception) {
                exc.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // === YUV -> rotated JPEG helper ===
    private fun yuvToJpegWithRotation(imageProxy: ImageProxy): ByteArray? {
        if (imageProxy.format != ImageFormat.YUV_420_888) return null

        val nv21 = yuv420888ToNv21(imageProxy)
        val yuvImage = YuvImage(
            nv21,
            ImageFormat.NV21,
            imageProxy.width,
            imageProxy.height,
            null
        )

        val out = ByteArrayOutputStream()
        return try {
            // First compress raw frame
            yuvImage.compressToJpeg(
                Rect(0, 0, imageProxy.width, imageProxy.height),
                80,
                out
            )
            val jpegBytes = out.toByteArray()

            // Decode to bitmap, rotate according to sensor rotation
            val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                ?: return jpegBytes

            val rotation = imageProxy.imageInfo.rotationDegrees.toFloat()
            val matrix = Matrix().apply {
                // rotate to match what previewView shows
                postRotate(rotation)
                // If it still looks mirrored on Quest, we can also add:
                // postScale(-1f, 1f, bitmap.width / 2f, bitmap.height / 2f)
            }

            val rotated = Bitmap.createBitmap(
                bitmap,
                0, 0,
                bitmap.width,
                bitmap.height,
                matrix,
                true
            )

            val out2 = ByteArrayOutputStream()
            rotated.compress(Bitmap.CompressFormat.JPEG, 80, out2)
            out2.toByteArray()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun yuv420888ToNv21(image: ImageProxy): ByteArray {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        // Y
        yBuffer.get(nv21, 0, ySize)
        // V and U are swapped for NV21
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        return nv21
    }
}

/**
 * Very simple HTTP server:
 *   GET /         -> HTML page that JS-refreshes <img> every 100ms (smooth, no flicker)
 *   GET /snapshot -> latest JPEG frame
 */
class SimpleHttpServer(
    port: Int,
    private val getLatestJpeg: () -> ByteArray?
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        return when (session.uri) {
            "/snapshot" -> {
                val jpeg = getLatestJpeg()
                if (jpeg == null) {
                    newFixedLengthResponse(
                        Response.Status.SERVICE_UNAVAILABLE,
                        "text/plain",
                        "No frame yet"
                    )
                } else {
                    val resp = newFixedLengthResponse(
                        Response.Status.OK,
                        "image/jpeg",
                        jpeg.inputStream(),
                        jpeg.size.toLong()
                    )
                    resp.addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
                    resp
                }
            }
            else -> {
                val html = """
                    <html>
                    <head>
                        <style>
                            body {
                                margin: 0;
                                background: #000;
                                display: flex;
                                justify-content: center;
                                align-items: center;
                                height: 100vh;
                            }
                            img {
                                max-width: 100%;
                                max-height: 100%;
                            }
                        </style>
                    </head>
                    <body>
                        <img id="stream" src="/snapshot" />
                        <script>
                            const img = document.getElementById('stream');
                            setInterval(() => {
                                img.src = '/snapshot?ts=' + Date.now();
                            }, 100); // ~10 fps
                        </script>
                    </body>
                    </html>
                """.trimIndent()
                newFixedLengthResponse(Response.Status.OK, "text/html", html)
            }
        }
    }
}