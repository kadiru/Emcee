package com.example.temihelloworld

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Size
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.robotemi.sdk.Robot
import com.robotemi.sdk.TtsRequest
import com.robotemi.sdk.listeners.OnRobotReadyListener
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), OnRobotReadyListener {

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 42
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }

    private lateinit var robot: Robot

    // Camera preview view (matches @+id/viewFinder in activity_main.xml)
    private lateinit var viewFinder: PreviewView

    private lateinit var cameraExecutor: ExecutorService

    // Overlays
    private lateinit var funnyText: TextView
    private lateinit var bananaImage: ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Bind views
        viewFinder = findViewById(R.id.viewFinder)
        funnyText = findViewById(R.id.funnyText)
        bananaImage = findViewById(R.id.bananaImage)

        // Preview configuration
        viewFinder.implementationMode = PreviewView.ImplementationMode.PERFORMANCE
        viewFinder.scaleType = PreviewView.ScaleType.FIT_CENTER

        // Overlay content
        funnyText.text = "Totally normal meeting,\nplease ignore the banana 🍌"
        bananaImage.alpha = 0.9f

        cameraExecutor = Executors.newSingleThreadExecutor()
        robot = Robot.getInstance()

        // Permissions + start camera
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
                "Camera preview with banana overlay is running.",
                false
            )
        )
    }

    // === Permission helpers ===
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
                .setTargetResolution(Size(1280, 720))   // deprecation is OK for now
                .build()
                .also {
                    it.setSurfaceProvider(viewFinder.surfaceProvider)
                }

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview
                )
            } catch (exc: Exception) {
                exc.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(this))
    }
}