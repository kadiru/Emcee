package com.example.temihelloworld

import android.os.Handler
import android.os.Looper
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.SurfaceHolder
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.pedro.rtsp.utils.ConnectCheckerRtsp
import com.pedro.rtspserver.RtspServerCamera2
import com.pedro.rtplibrary.view.OpenGlView
import com.robotemi.sdk.Robot
import com.robotemi.sdk.TtsRequest
import com.robotemi.sdk.listeners.OnRobotReadyListener
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

class MainActivity : AppCompatActivity(),
    OnRobotReadyListener,
    ConnectCheckerRtsp {

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 42
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )

        private const val SERVER_PORT = 8554
    }

    private lateinit var serverTimeText: TextView
    private val uiHandler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var timeUpdaterRunning = false

    private lateinit var robot: Robot
    private lateinit var openGlView: OpenGlView
    private lateinit var rtspServerCamera: RtspServerCamera2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        robot = Robot.getInstance()
        openGlView = findViewById(R.id.surfaceView)
        serverTimeText = findViewById(R.id.serverTimeText)
        startServerTimeUpdates()
        openGlView.keepScreenOn = true

        rtspServerCamera = RtspServerCamera2(
            openGlView,
            this,          // ConnectCheckerRtsp
            SERVER_PORT    // e.g. 8554
        )

        if (allPermissionsGranted()) {
            startStreamingSafe()
        } else {
            ActivityCompat.requestPermissions(
                this,
                REQUIRED_PERMISSIONS,
                REQUEST_CODE_PERMISSIONS
            )
        }
    }

    private fun startServerTimeUpdates() {
        if (timeUpdaterRunning) return
        timeUpdaterRunning = true

        uiHandler.post(object : Runnable {
            override fun run() {
                val now = Date()
                serverTimeText.text = "Server: ${timeFormat.format(now)}"
                if (timeUpdaterRunning) {
                    uiHandler.postDelayed(this, 50L) // update ~20 fps
                }
            }
        })
    }

    private fun stopServerTimeUpdates() {
        timeUpdaterRunning = false
        uiHandler.removeCallbacksAndMessages(null)
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
        stopServerTimeUpdates()
        stopStreaming()
    }

    // === Temi SDK callback ===
    override fun onRobotReady(isReady: Boolean) {
        if (!isReady) return

        try {
            val activityInfo = packageManager.getActivityInfo(
                componentName,
                PackageManager.GET_META_DATA
            )
            robot.onStart(activityInfo)
        } catch (e: PackageManager.NameNotFoundException) {
            e.printStackTrace()
        }

        robot.speak(TtsRequest.create("RTSP streaming app ready.", false))
    }

    // === Permissions ===
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
                startStreamingSafe()
            } else {
                Toast.makeText(this, "Camera + mic permission denied", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    // === RTSP streaming control ===
    /**
     * Make sure the OpenGlView actually has a valid Surface
     * before we start preview/stream to avoid EGL14 crash.
     */
    private fun startStreamingSafe() {
        val holder = openGlView.holder
        val surface = holder.surface

        if (surface != null && surface.isValid) {
            doStartStreaming()
        } else {
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    openGlView.holder.removeCallback(this)
                    doStartStreaming()
                }

                override fun surfaceChanged(
                    holder: SurfaceHolder,
                    format: Int,
                    width: Int,
                    height: Int
                ) {
                    // no-op
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    // no-op
                }
            })
        }
    }

    /**
     * Actual streaming logic – only called when the surface is valid.
     * Tuned for lower latency.
     */
    private fun doStartStreaming() {
        if (rtspServerCamera.isStreaming) return

        // ---------- TUNED ENCODER SETTINGS ----------

        // Video
        val videoWidth = 640
        val videoHeight = 360
        val fps = 24                 // slightly lower load than 30
        val videoBitrate = 1_200_000 // ~1.2 Mbps
        val iFrameInterval = 1       // keyframe every 1 second
        val rotation = 0             // adjust if your image is rotated

        // Audio (you can comment this whole block out if you want video-only)
        val audioBitrate = 64 * 1024 // 64 kbps
        val sampleRate = 32_000      // 32 kHz
        val isStereo = false         // mono
        val echoCanceler = true
        val noiseSuppressor = true

        val preparedVideo = rtspServerCamera.prepareVideo(
            videoWidth,
            videoHeight,
            fps,
            videoBitrate,
            iFrameInterval,
            rotation
        )

        val preparedAudio = rtspServerCamera.prepareAudio(
            audioBitrate,
            sampleRate,
            isStereo,
            echoCanceler,
            noiseSuppressor
        )

        // If you want VIDEO ONLY: change this to "val prepared = preparedVideo"
        val prepared = preparedVideo && preparedAudio

        if (!prepared) {
            Toast.makeText(this, "Error preparing RTSP stream", Toast.LENGTH_LONG).show()
            return
        }

        // Start camera preview on Temi
        if (!rtspServerCamera.isOnPreview) {
            rtspServerCamera.startPreview()
        }

        // Start RTSP stream
        rtspServerCamera.startStream()

        val ip = getDeviceIpAddress()
        val endpoint = "rtsp://$ip:$SERVER_PORT/live"

        Toast.makeText(this, "RTSP server: $endpoint", Toast.LENGTH_LONG).show()

        robot.speak(
            TtsRequest.create(
                "Streaming started on port $SERVER_PORT.",
                false
            )
        )
    }

    private fun stopStreaming() {
        try {
            if (rtspServerCamera.isStreaming) {
                rtspServerCamera.stopStream()
            }
            rtspServerCamera.stopPreview()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Get the IPv4 address of the device on the local network
     * (non-loopback). This avoids needing WifiManager permissions.
     */
    private fun getDeviceIpAddress(): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: "0.0.0.0"
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "0.0.0.0"
    }

    // === ConnectCheckerRtsp callbacks ===
    override fun onConnectionStartedRtsp(rtspUrl: String) {
        // You can log rtspUrl if you want
    }

    override fun onConnectionSuccessRtsp() {
        runOnUiThread {
            Toast.makeText(this, "RTSP client connected", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onConnectionFailedRtsp(reason: String) {
        runOnUiThread {
            Toast.makeText(this, "RTSP connection failed: $reason", Toast.LENGTH_LONG).show()
        }
        rtspServerCamera.stopStream()
    }

    override fun onNewBitrateRtsp(bitrate: Long) {
        // Optional: monitor bitrate
    }

    override fun onDisconnectRtsp() {
        runOnUiThread {
            Toast.makeText(this, "RTSP client disconnected", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onAuthErrorRtsp() {
        runOnUiThread {
            Toast.makeText(this, "RTSP auth error", Toast.LENGTH_LONG).show()
        }
    }

    override fun onAuthSuccessRtsp() {
        runOnUiThread {
            Toast.makeText(this, "RTSP auth success", Toast.LENGTH_SHORT).show()
        }
    }
}