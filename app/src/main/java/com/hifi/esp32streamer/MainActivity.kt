package com.hifi.esp32streamer

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.Typeface
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.*
import android.os.*
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

// Shared Live State between Background Service and UI
object StreamState {
    @Volatile var isRunning = false
    @Volatile var statusText = "IDLE - Ready for Pre-DAC Test"
    @Volatile var sampleRate = 48000
    @Volatile var outputBitDepth = 24 // 24-bit or 16-bit output to ESP32
    @Volatile var detectedBitActivity = "Waiting for audio..."
    @Volatile var activeAppStreams = "None detected"
    @Volatile var leftLevel = 0
    @Volatile var rightLevel = 0
    @Volatile var dbFs = -100.0
    @Volatile var dataRateKbps = 0.0
    @Volatile var hexPreview = "00 00 00 00 00 00 00 00"
    @Volatile var streamToEsp32 = false
    @Volatile var espIp = "192.168.4.1"
    @Volatile var espPort = 8888
    @Volatile var muteSpeaker = false
}

class MainActivity : AppCompatActivity() {

    private lateinit var projectionManager: MediaProjectionManager
    private val uiHandler = Handler(Looper.getMainLooper())

    private lateinit var tvStatus: TextView
    private lateinit var tvFormatInfo: TextView
    private lateinit var tvBitAnalysis: TextView
    private lateinit var tvHex: TextView
    private lateinit var tvDb: TextView
    private lateinit var pbLeft: ProgressBar
    private lateinit var pbRight: ProgressBar
    private lateinit var btnStartStop: Button
    private lateinit var swEspMode: Switch
    private lateinit var swMute: Switch
    private lateinit var rgBitDepth: RadioGroup
    private lateinit var etIp: EditText

    private val uiUpdater = object : Runnable {
        override fun run() {
            tvStatus.text = "STATUS: ${StreamState.statusText}"
            tvFormatInfo.text = "Native Mixer: ${StreamState.sampleRate} Hz | Stereo | Target DAC: ${StreamState.outputBitDepth}-Bit PCM\n" +
                    "Throughput: ${String.format("%.1f", StreamState.dataRateKbps)} KB/s | Active Streams: ${StreamState.activeAppStreams}"
            tvBitAnalysis.text = "LIVE BIT INSPECTOR: ${StreamState.detectedBitActivity}"
            tvHex.text = "PRE-DAC HEX: [ ${StreamState.hexPreview} ]"
            tvDb.text = "Signal Level: ${String.format("%.1f", StreamState.dbFs)} dBFS"
            pbLeft.progress = StreamState.leftLevel
            pbRight.progress = StreamState.rightLevel

            if (StreamState.isRunning) {
                btnStartStop.text = "STOP CAPTURE & STREAM"
                btnStartStop.setBackgroundColor(Color.parseColor("#D32F2F"))
            } else {
                btnStartStop.text = "START PRE-DAC CAPTURE"
                btnStartStop.setBackgroundColor(Color.parseColor("#2E7D32"))
            }
            uiHandler.postDelayed(this, 80) // 12.5 FPS smooth UI update
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // Detect Phone's Native Hardware Sample Rate
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val nativeRate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48000
        StreamState.sampleRate = nativeRate

        buildDashboardUi()
        requestNeededPermissions()
    }

    private fun buildDashboardUi() {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#121212")) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 50, 40, 50)
        }

        val title = TextView(this).apply {
            text = "ESP32-S3 + PCM5102A\nPre-DAC Digital Audio Analyzer"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#00E676"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 30)
        }
        root.addView(title)

        tvStatus = makeCardTextView("STATUS: Ready", "#FFFFFF", 15f, true)
        root.addView(tvStatus)

        // Visualizer Section
        val vizLabel = makeLabel("LIVE PRE-DAC DIGITAL VU METER (L / R)")
        root.addView(vizLabel)

        pbLeft = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressDrawable.setColorFilter(Color.parseColor("#00E676"), android.graphics.PorterDuff.Mode.SRC_IN)
            setPadding(0, 8, 0, 8)
        }
        pbRight = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressDrawable.setColorFilter(Color.parseColor("#29B6F6"), android.graphics.PorterDuff.Mode.SRC_IN)
            setPadding(0, 8, 0, 16)
        }
        root.addView(pbLeft)
        root.addView(pbRight)

        tvDb = makeCardTextView("Signal Level: -100.0 dBFS", "#B0BEC5", 13f, false)
        root.addView(tvDb)

        // Digital Inspector Card
        tvFormatInfo = makeCardTextView("Format Info...", "#80CBC4", 13f, false)
        root.addView(tvFormatInfo)

        tvBitAnalysis = makeCardTextView("Bit Depth Inspector...", "#FFD54F", 14f, true)
        root.addView(tvBitAnalysis)

        tvHex = TextView(this).apply {
            text = "PRE-DAC HEX: [ 00 00 00 00 ]"
            setTextColor(Color.parseColor("#76FF03"))
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            setPadding(24, 24, 24, 24)
        }
        root.addView(tvHex)

        // Bit Depth Selector for PCM5102A output
        root.addView(makeLabel("PCM5102A OUTPUT PRECISION"))
        rgBitDepth = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            val rb24 = RadioButton(context).apply {
                id = 24
                text = "24-Bit Hi-Res PCM"
                setTextColor(Color.WHITE)
                isChecked = true
            }
            val rb16 = RadioButton(context).apply {
                id = 16
                text = "16-Bit CD PCM"
                setTextColor(Color.WHITE)
            }
            addView(rb24)
            addView(rb16)
            setOnCheckedChangeListener { _, checkedId ->
                StreamState.outputBitDepth = checkedId
            }
        }
        root.addView(rgBitDepth)

        // Switches
        swMute = Switch(this).apply {
            text = "Mute Phone Built-in Speaker While Capturing"
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(0, 20, 0, 20)
            setOnCheckedChangeListener { _, isChecked ->
                StreamState.muteSpeaker = isChecked
                val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    if (isChecked) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
                    0
                )
            }
        }
        root.addView(swMute)

        swEspMode = Switch(this).apply {
            text = "Enable Wi-Fi TCP Stream to ESP32 (Keep OFF for Local Test)"
            setTextColor(Color.parseColor("#81D4FA"))
            textSize = 14f
            setPadding(0, 10, 0, 20)
            setOnCheckedChangeListener { _, isChecked ->
                StreamState.streamToEsp32 = isChecked
            }
        }
        root.addView(swEspMode)

        root.addView(makeLabel("ESP32 HOTSPOT IP ADDRESS (Port 8888)"))
        etIp = EditText(this).apply {
            setText("192.168.4.1")
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#263238"))
            setPadding(24, 20, 24, 20)
        }
        root.addView(etIp)

        btnStartStop = Button(this).apply {
            text = "START PRE-DAC CAPTURE"
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 35, 0, 35)
            setOnClickListener {
                if (StreamState.isRunning) {
                    stopCaptureService()
                } else {
                    StreamState.espIp = etIp.text.toString().trim()
                    startMediaProjectionRequest()
                }
            }
        }
        val btnParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 40 }
        root.addView(btnStartStop, btnParams)

        scroll.addView(root)
        setContentView(scroll)
    }

    private fun makeCardTextView(txt: String, hexColor: String, size: Float, bold: Boolean): TextView {
        return TextView(this).apply {
            text = txt
            setTextColor(Color.parseColor(hexColor))
            textSize = size
            if (bold) setTypeface(null, Typeface.BOLD)
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            setPadding(24, 20, 24, 20)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16 }
            layoutParams = lp
        }
    }

    private fun makeLabel(txt: String): TextView {
        return TextView(this).apply {
            text = txt
            setTextColor(Color.parseColor("#90A4AE"))
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 20, 0, 8)
        }
    }

    private fun requestNeededPermissions() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
        }
    }

    private fun startMediaProjectionRequest() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            requestNeededPermissions()
            Toast.makeText(this, "Please grant Audio permission first!", Toast.LENGTH_SHORT).show()
            return
        }
        startActivityForResult(projectionManager.createScreenCaptureIntent(), 202)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 202 && resultCode == Activity.RESULT_OK && data != null) {
            val svcIntent = Intent(this, AudioCaptureService::class.java).apply {
                action = "ACTION_START"
                putExtra("RESULT_CODE", resultCode)
                putExtra("DATA_INTENT", data)
            }
            ContextCompat.startForegroundService(this, svcIntent)
        }
    }

    private fun stopCaptureService() {
        val svcIntent = Intent(this, AudioCaptureService::class.java).apply {
            action = "ACTION_STOP"
        }
        startService(svcIntent)
    }

    override fun onResume() {
        super.onResume()
        uiHandler.post(uiUpdater)
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(uiUpdater)
    }
}

// =========================================================================
// BACKGROUND FOREGROUND SERVICE: Pre-DAC Intercept + Bit Analysis + Wi-Fi
// =========================================================================
class AudioCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    private lateinit var audioManager: AudioManager

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            val count = configs?.size ?: 0
            StreamState.activeAppStreams = if (count > 0) "$count Active Audio Stream(s)" else "No App Playing"
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.registerAudioPlaybackCallback(playbackCallback, null)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "ACTION_STOP") {
            cleanupAndStop()
            return START_NOT_STICKY
        }

        if (intent?.action == "ACTION_START" && !StreamState.isRunning) {
            startForegroundNotification()
            val resultCode = intent.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED)
            val dataIntent: Intent? = intent.getParcelableExtra("DATA_INTENT")
            if (dataIntent != null) {
                startPreDacCapture(resultCode, dataIntent)
            }
        }
        return START_STICKY
    }

    private fun startForegroundNotification() {
        val channelId = "HiFiStreamChannel"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(channelId, "HiFi Audio Streamer", NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(channel)

        val stopIntent = Intent(this, AudioCaptureService::class.java).apply { action = "ACTION_STOP" }
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this, 2, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = Notification.Builder(this, channelId)
            .setContentTitle("Pre-DAC Digital Capture Active")
            .setContentText("Intercepting lossless PCM audio in background")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_media_pause, "STOP", stopPending)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1001, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1001, notif)
        }
    }

    private fun startPreDacCapture(resultCode: Int, dataIntent: Intent) {
        val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = pm.getMediaProjection(resultCode, dataIntent)

        val sampleRate = StreamState.sampleRate
        val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        // Capture in 32-Bit Floating Point directly from Android AudioFlinger for full 24-bit precision
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()

        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_FLOAT
        )
        val floatBufferSize = maxOf(minBuf, 4096 * 4)

        try {
            audioRecord = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(floatBufferSize)
                .setAudioPlaybackCaptureConfig(config)
                .build()
        } catch (e: SecurityException) {
            StreamState.statusText = "ERROR: Audio Capture Permission Denied"
            return
        }

        if (StreamState.muteSpeaker) {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
        }

        audioRecord?.startRecording()
        StreamState.isRunning = true
        StreamState.statusText = "RUNNING - Capturing Pre-DAC Audio (${sampleRate}Hz)"

        captureThread = thread(start = true, name = "PreDacAudioThread") {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val floatSamples = FloatArray(2048) // 1024 Stereo frames
            var socket: Socket? = null
            var outStream: OutputStream? = null

            while (StreamState.isRunning) {
                val readCount = audioRecord?.read(floatSamples, 0, floatSamples.size, AudioRecord.READ_BLOCKING) ?: 0
                if (readCount <= 0) continue

                // 1. Analyze Audio Signal & True Bit Depth Precision
                var sumL = 0.0
                var sumR = 0.0
                var hasNonZero = false
                var hasLowBits24 = false

                val targetBits = StreamState.outputBitDepth
                val bytesPerSample = if (targetBits == 24) 3 else 2
                val pcmOutBytes = ByteArray(readCount * bytesPerSample)
                var byteIdx = 0

                var i = 0
                while (i < readCount) {
                    val fL = floatSamples[i].coerceIn(-1.0f, 1.0f)
                    val fR = if (i + 1 < readCount) floatSamples[i + 1].coerceIn(-1.0f, 1.0f) else 0f

                    sumL += (fL * fL)
                    sumR += (fR * fR)

                    // Convert Float32 [-1.0, 1.0] to 24-bit Signed Integer [-8388608, 8388607]
                    val int24L = (fL * 8388607.0f).toInt()
                    val int24R = (fR * 8388607.0f).toInt()

                    if (int24L != 0 || int24R != 0) hasNonZero = true
                    // Check if bits [0..7] of 24-bit sample contain active resolution beyond 16-bit
                    if ((int24L and 0xFF) != 0 || (int24R and 0xFF) != 0) hasLowBits24 = true

                    if (targetBits == 24) {
                        // Little-Endian 24-bit PCM packing for PCM5102A
                        pcmOutBytes[byteIdx++] = (int24L and 0xFF).toByte()
                        pcmOutBytes[byteIdx++] = ((int24L shr 8) and 0xFF).toByte()
                        pcmOutBytes[byteIdx++] = ((int24L shr 16) and 0xFF).toByte()

                        pcmOutBytes[byteIdx++] = (int24R and 0xFF).toByte()
                        pcmOutBytes[byteIdx++] = ((int24R shr 8) and 0xFF).toByte()
                        pcmOutBytes[byteIdx++] = ((int24R shr 16) and 0xFF).toByte()
                    } else {
                        // Little-Endian 16-bit PCM packing
                        val int16L = (int24L shr 8)
                        val int16R = (int24R shr 8)
                        pcmOutBytes[byteIdx++] = (int16L and 0xFF).toByte()
                        pcmOutBytes[byteIdx++] = ((int16L shr 8) and 0xFF).toByte()

                        pcmOutBytes[byteIdx++] = (int16R and 0xFF).toByte()
                        pcmOutBytes[byteIdx++] = ((int16R shr 8) and 0xFF).toByte()
                    }
                    i += 2
                }

                // Update Live VU Meter & Bit Inspector
                val frames = (readCount / 2).coerceAtLeast(1)
                val rmsL = sqrt(sumL / frames)
                val rmsR = sqrt(sumR / frames)
                val maxRms = maxOf(rmsL, rmsR)
                val db = if (maxRms > 0.000001) 20.0 * log10(maxRms) else -100.0

                StreamState.dbFs = db
                StreamState.leftLevel = ((rmsL * 140).toInt()).coerceIn(0, 100)
                StreamState.rightLevel = ((rmsR * 140).toInt()).coerceIn(0, 100)
                StreamState.dataRateKbps = (StreamState.sampleRate * 2 * bytesPerSample) / 1000.0

                StreamState.detectedBitActivity = when {
                    !hasNonZero -> "SILENCE (00 00) - Play audio in Brave / Check DRM"
                    hasLowBits24 -> "ACTIVE AUDIO - Full 24-Bit Hi-Res Precision Active!"
                    else -> "ACTIVE AUDIO - 16-Bit Standard PCM Precision"
                }

                // Build Hex Preview from first 8 non-zero or starting bytes
                val sb = StringBuilder()
                for (b in 0 until minOf(8, pcmOutBytes.size)) {
                    sb.append(String.format("%02X ", pcmOutBytes[b]))
                }
                StreamState.hexPreview = sb.toString().trim()

                // 2. Optional Wi-Fi TCP Streaming to ESP32 (while keeping 5G active!)
                if (StreamState.streamToEsp32) {
                    try {
                        if (socket == null || socket.isClosed || !socket.isConnected) {
                            StreamState.statusText = "Connecting to ESP32 (${StreamState.espIp}:${StreamState.espPort})..."
                            socket = createWifiBoundSocket()
                            socket.tcpNoDelay = true
                            socket.connect(InetSocketAddress(StreamState.espIp, StreamState.espPort), 2000)
                            outStream = socket.getOutputStream()
                            StreamState.statusText = "STREAMING TO ESP32 WIFI (${StreamState.sampleRate}Hz / ${targetBits}-Bit)"
                        }
                        outStream?.write(pcmOutBytes)
                    } catch (e: Exception) {
                        StreamState.statusText = "ESP32 Wi-Fi Waiting (${e.javaClass.simpleName}) - Local Analyzer Active"
                        try { socket?.close() } catch (_: Exception) {}
                        socket = null
                        outStream = null
                    }
                } else {
                    if (socket != null) {
                        try { socket.close() } catch (_: Exception) {}
                        socket = null
                        outStream = null
                    }
                    StreamState.statusText = "LOCAL TEST MODE - Capturing Pre-DAC (${StreamState.sampleRate}Hz)"
                }
            }

            try { socket?.close() } catch (_: Exception) {}
        }
    }

    // Dual-Network Magic: Forces the ESP32 Socket over Wi-Fi while Brave uses 5G Mobile Data!
    private fun createWifiBoundSocket(): Socket {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        for (net in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(net)
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return net.socketFactory.createSocket()
            }
        }
        return Socket()
    }

    private fun cleanupAndStop() {
        StreamState.isRunning = false
        try { captureThread?.join(500) } catch (_: Exception) {}
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null

        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null

        // Always restore phone speaker volume on stop
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)

        StreamState.leftLevel = 0
        StreamState.rightLevel = 0
        StreamState.dbFs = -100.0
        StreamState.statusText = "STOPPED - Ready"
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        audioManager.unregisterAudioPlaybackCallback(playbackCallback)
        cleanupAndStop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}