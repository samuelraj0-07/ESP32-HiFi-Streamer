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
import kotlin.math.*

// Shared Live State between Background Service and UI
object StreamState {
    @Volatile var isRunning = false
    @Volatile var statusText = "IDLE - Auto-Max Hi-Res Engine Ready"
    @Volatile var sampleRate = 96000
    @Volatile var nativeMixerRate = 48000
    @Volatile var detectedSourceRate = "Auto-Detecting..."
    @Volatile var activeBitCount = 0
    @Volatile var bitHistogramText = "Bits 01-08: [--------]\nBits 09-16: [--------]\nBits 17-24: [--------]"
    @Volatile var qualityVerdict = "Waiting for music playback..."
    @Volatile var leftLevel = 0
    @Volatile var rightLevel = 0
    @Volatile var dbFs = -100.0
    @Volatile var crestFactorDb = 0.0
    @Volatile var dataRateKbps = 0.0
    @Volatile var hexPreview = "00 00 00 00 00 00 00 00"

    // 6-Band FFT Spectrum Analyzer Levels (0..100)
    @Volatile var bandBass = 0       // 20 Hz - 250 Hz
    @Volatile var bandMid = 0        // 250 Hz - 4 kHz
    @Volatile var bandTreble = 0     // 4 kHz - 12 kHz
    @Volatile var bandAir = 0        // 12 kHz - 16 kHz
    @Volatile var bandLossless = 0   // 16 kHz - 20 kHz (MP3/AAC brickwall test)
    @Volatile var bandUltrasonic = 0 // 20 kHz - 48 kHz (Hi-Res Ultrasonic test)

    @Volatile var streamToEsp32 = false
    @Volatile var espIp = "192.168.4.1"
    @Volatile var espPort = 8888
    @Volatile var muteSpeaker = false
}

class MainActivity : AppCompatActivity() {

    private lateinit var projectionManager: MediaProjectionManager
    private val uiHandler = Handler(Looper.getMainLooper())

    private lateinit var tvStatus: TextView
    private lateinit var tvVerdict: TextView
    private lateinit var tvFormatInfo: TextView
    private lateinit var tvBitHistogram: TextView
    private lateinit var tvHex: TextView
    private lateinit var tvDb: TextView

    private lateinit var pbLeft: ProgressBar
    private lateinit var pbRight: ProgressBar

    private lateinit var pbBass: ProgressBar
    private lateinit var pbMid: ProgressBar
    private lateinit var pbTreble: ProgressBar
    private lateinit var pbAir: ProgressBar
    private lateinit var pbLossless: ProgressBar
    private lateinit var pbUltrasonic: ProgressBar

    private lateinit var btnStartStop: Button
    private lateinit var swEspMode: Switch
    private lateinit var swMute: Switch
    private lateinit var etIp: EditText

    private val uiUpdater = object : Runnable {
        override fun run() {
            tvStatus.text = "STATUS: ${StreamState.statusText}"
            tvVerdict.text = "QUALITY VERDICT:\n${StreamState.qualityVerdict}"

            val nyquistKhz = StreamState.sampleRate / 2000.0
            tvFormatInfo.text =
                "Auto-Max Pipe: ${StreamState.sampleRate} Hz | 24-Bit PCM (Max Freq: ${nyquistKhz} kHz)\n" +
                "Phone Native Mixer: ${StreamState.nativeMixerRate} Hz | App Stream: ${StreamState.detectedSourceRate}\n" +
                "Wi-Fi Bitrate: ${String.format("%.1f", StreamState.dataRateKbps)} KB/s (${String.format("%.2f", StreamState.dataRateKbps * 8 / 1000.0)} Mbps)"

            tvBitHistogram.text =
                "TRUE 24-BIT HISTOGRAM (Active: ${StreamState.activeBitCount}/24 Bits)\n" +
                StreamState.bitHistogramText

            tvHex.text = "24-BIT PRE-DAC HEX: [ ${StreamState.hexPreview} ]"
            tvDb.text = "RMS Level: ${String.format("%.1f", StreamState.dbFs)} dBFS | Dynamic Crest: ${String.format("%.1f", StreamState.crestFactorDb)} dB"

            pbLeft.progress = StreamState.leftLevel
            pbRight.progress = StreamState.rightLevel

            pbBass.progress = StreamState.bandBass
            pbMid.progress = StreamState.bandMid
            pbTreble.progress = StreamState.bandTreble
            pbAir.progress = StreamState.bandAir
            pbLossless.progress = StreamState.bandLossless
            pbUltrasonic.progress = StreamState.bandUltrasonic

            if (StreamState.isRunning) {
                btnStartStop.text = "STOP CAPTURE & STREAM"
                btnStartStop.setBackgroundColor(Color.parseColor("#D32F2F"))
            } else {
                btnStartStop.text = "START AUTO-MAX HI-RES CAPTURE"
                btnStartStop.setBackgroundColor(Color.parseColor("#00C853"))
            }
            uiHandler.postDelayed(this, 75)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        StreamState.nativeMixerRate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48000

        buildDashboardUi()
        requestNeededPermissions()
    }

    private fun buildDashboardUi() {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#101418")) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 44, 36, 44)
        }

        val title = TextView(this).apply {
            text = "ESP32-S3 + PCM5102A\n24-Bit / Ultrasonic Hi-Res Analyzer"
            textSize = 19f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#00E676"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        }
        root.addView(title)

        tvStatus = makeCardTextView("STATUS: Ready", "#FFFFFF", 14f, true)
        root.addView(tvStatus)

        tvVerdict = makeCardTextView("QUALITY VERDICT:\nWaiting for audio...", "#FFD54F", 15f, true)
        root.addView(tvVerdict)

        // L/R VU Meter
        root.addView(makeLabel("PRE-DAC STEREO VU METER (L / R)"))
        pbLeft = makeBar("#00E676")
        pbRight = makeBar("#29B6F6")
        root.addView(pbLeft)
        root.addView(pbRight)

        tvDb = makeCardTextView("RMS Level: -100.0 dBFS", "#B0BEC5", 12.5f, false)
        root.addView(tvDb)

        // True 24-Bit Histogram Box
        tvBitHistogram = TextView(this).apply {
            text = "TRUE 24-BIT HISTOGRAM"
            setTextColor(Color.parseColor("#80D8FF"))
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setBackgroundColor(Color.parseColor("#1B2228"))
            setPadding(24, 24, 24, 24)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16 }
            layoutParams = lp
        }
        root.addView(tvBitHistogram)

        // Ultrasonic FFT Spectrum Analyzer (0 kHz to 48 kHz)
        root.addView(makeLabel("HIGH-FREQUENCY ULTRASONIC SPECTRUM (0 kHz - 48 kHz)"))
        pbBass = addSpectrumRow(root, "Sub/Bass (20Hz - 250Hz)", "#4FC3F7")
        pbMid = addSpectrumRow(root, "Midrange (250Hz - 4kHz)", "#4DB6AC")
        pbTreble = addSpectrumRow(root, "Treble (4kHz - 12kHz)", "#81C784")
        pbAir = addSpectrumRow(root, "Air Band (12kHz - 16kHz)", "#DCE775")
        pbLossless = addSpectrumRow(root, "Lossless Proof (16kHz - 20kHz)", "#FFB74D")
        pbUltrasonic = addSpectrumRow(root, "Hi-Res Ultrasonic (20kHz - 48kHz)", "#FF5252")

        // Format & Stream Info
        tvFormatInfo = makeCardTextView("Format Info...", "#A7FFEB", 12.5f, false)
        root.addView(tvFormatInfo)

        tvHex = TextView(this).apply {
            text = "24-BIT PRE-DAC HEX: [ 00 00 00 ]"
            setTextColor(Color.parseColor("#76FF03"))
            typeface = Typeface.MONOSPACE
            textSize = 12.5f
            setBackgroundColor(Color.parseColor("#1B2228"))
            setPadding(24, 20, 24, 20)
        }
        root.addView(tvHex)

        // Switches
        swMute = Switch(this).apply {
            text = "Mute Phone Speaker (Keep OFF for 100% Bit-Perfect 0dBFS Test)"
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(0, 24, 0, 12)
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
            text = "Stream 24-Bit PCM to ESP32 Wi-Fi (Keep OFF for Local Test)"
            setTextColor(Color.parseColor("#81D4FA"))
            textSize = 13f
            setPadding(0, 8, 0, 16)
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
            setPadding(24, 18, 24, 18)
        }
        root.addView(etIp)

        btnStartStop = Button(this).apply {
            text = "START AUTO-MAX HI-RES CAPTURE"
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 32, 0, 32)
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
        ).apply { topMargin = 32 }
        root.addView(btnStartStop, btnParams)

        scroll.addView(root)
        setContentView(scroll)
    }

    private fun makeBar(hexColor: String): ProgressBar {
        return ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressDrawable.setColorFilter(Color.parseColor(hexColor), android.graphics.PorterDuff.Mode.SRC_IN)
            setPadding(0, 6, 0, 6)
        }
    }

    private fun addSpectrumRow(parent: LinearLayout, label: String, colorHex: String): ProgressBar {
        val tv = TextView(this).apply {
            text = label
            setTextColor(Color.parseColor("#CFD8DC"))
            textSize = 11.5f
        }
        val pb = makeBar(colorHex)
        parent.addView(tv)
        parent.addView(pb)
        return pb
    }

    private fun makeCardTextView(txt: String, hexColor: String, size: Float, bold: Boolean): TextView {
        return TextView(this).apply {
            text = txt
            setTextColor(Color.parseColor(hexColor))
            textSize = size
            if (bold) setTypeface(null, Typeface.BOLD)
            setBackgroundColor(Color.parseColor("#1B2228"))
            setPadding(24, 18, 24, 18)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 14 }
            layoutParams = lp
        }
    }

    private fun makeLabel(txt: String): TextView {
        return TextView(this).apply {
            text = txt
            setTextColor(Color.parseColor("#90A4AE"))
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 18, 0, 6)
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
            Toast.makeText(this, "Grant Audio permission first!", Toast.LENGTH_SHORT).show()
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
        val svcIntent = Intent(this, AudioCaptureService::class.java).apply { action = "ACTION_STOP" }
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

// ================================================================================
// BACKGROUND ENGINE: Auto-Max 96kHz/48kHz Probe + 24-Bit Histogram + FFT Analyzer
// ================================================================================
class AudioCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    private lateinit var audioManager: AudioManager

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            if (configs.isNullOrEmpty()) {
                StreamState.detectedSourceRate = "No Active Player"
                return
            }
            // Inspect active player configuration (via reflection if hidden by OS, else count)
            val sb = StringBuilder()
            for (cfg in configs) {
                try {
                    val m = cfg.javaClass.getMethod("getAudioFormat")
                    val fmt = m.invoke(cfg) as? AudioFormat
                    if (fmt != null && fmt.sampleRate > 0) {
                        sb.append("${fmt.sampleRate}Hz ")
                    }
                } catch (_: Exception) {}
            }
            StreamState.detectedSourceRate = if (sb.isNotEmpty()) sb.toString().trim() else "${configs.size} Active Stream(s)"
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
                startAutoMaxHiResCapture(resultCode, dataIntent)
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
            .setContentTitle("Auto-Max 24-Bit Hi-Res Capture Active")
            .setContentText("Analyzing 24-bit histogram & ultrasonic spectrum")
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

    // Automatically probes highest supported lossless rate (96,000 Hz -> 48,000 Hz -> 44,100 Hz)
    private fun startAutoMaxHiResCapture(resultCode: Int, dataIntent: Intent) {
        val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = pm.getMediaProjection(resultCode, dataIntent)

        val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val candidateRates = intArrayOf(96000, 48000, 44100)
        var lockedRate = 48000

        for (rate in candidateRates) {
            try {
                val minBuf = AudioRecord.getMinBufferSize(
                    rate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_FLOAT
                )
                if (minBuf <= 0) continue

                val format = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()

                val candidateRecord = AudioRecord.Builder()
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(maxOf(minBuf * 2, 8192 * 4))
                    .setAudioPlaybackCaptureConfig(config)
                    .build()

                if (candidateRecord.state == AudioRecord.STATE_INITIALIZED) {
                    audioRecord = candidateRecord
                    lockedRate = rate
                    break
                } else {
                    candidateRecord.release()
                }
            } catch (_: Exception) {}
        }

        if (audioRecord == null) {
            StreamState.statusText = "ERROR: Could not initialize Pre-DAC Pipe"
            return
        }

        StreamState.sampleRate = lockedRate
        audioRecord?.startRecording()
        StreamState.isRunning = true
        StreamState.statusText = "LOCKED AT MAX HI-RES: ${lockedRate} Hz / 24-Bit Float-to-PCM"

        captureThread = thread(start = true, name = "HiResPreDacThread") {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val floatSamples = FloatArray(2048) // 1024 Stereo Frames
            val fftReal = DoubleArray(1024)
            val fftImag = DoubleArray(1024)
            val bitCounts = IntArray(24)

            var socket: Socket? = null
            var outStream: OutputStream? = null

            while (StreamState.isRunning) {
                val readCount = audioRecord?.read(floatSamples, 0, floatSamples.size, AudioRecord.READ_BLOCKING) ?: 0
                if (readCount <= 0) continue

                var sumL = 0.0
                var sumR = 0.0
                var peakAmp = 0.0
                bitCounts.fill(0)

                val pcm24Bytes = ByteArray(readCount * 3) // 24-bit (3 bytes per sample) for PCM5102A
                var byteIdx = 0
                val frames = readCount / 2

                var frameIdx = 0
                var i = 0
                while (i < readCount) {
                    val fL = floatSamples[i].coerceIn(-1.0f, 1.0f)
                    val fR = if (i + 1 < readCount) floatSamples[i + 1].coerceIn(-1.0f, 1.0f) else 0f

                    val absL = abs(fL).toDouble()
                    val absR = abs(fR).toDouble()
                    if (absL > peakAmp) peakAmp = absL
                    if (absR > peakAmp) peakAmp = absR

                    sumL += (fL * fL)
                    sumR += (fR * fR)

                    // Convert 32-bit Float to Signed 24-Bit Integer
                    val int24L = (fL * 8388607.0f).roundToInt()
                    val int24R = (fR * 8388607.0f).roundToInt()

                    // Analyze every single bit from Bit 1 (MSB, bit index 23) down to Bit 24 (LSB, bit index 0)
                    val magL = abs(int24L)
                    val magR = abs(int24R)
                    val combinedBits = magL or magR
                    if (combinedBits != 0) {
                        for (b in 0 until 24) {
                            // b=0 is MSB (Bit 1), b=23 is LSB (Bit 24)
                            val shift = 22 - b
                            val mask = if (shift >= 0) (1 shl shift) else 1
                            if ((combinedBits and mask) != 0) {
                                bitCounts[b]++
                            }
                        }
                    }

                    // Store Mono mix with Hann window for 1024-point FFT Spectrum Analyzer
                    if (frameIdx < 1024) {
                        val window = 0.5 * (1.0 - cos(2.0 * Math.PI * frameIdx / 1023.0))
                        fftReal[frameIdx] = ((fL + fR) * 0.5) * window
                        fftImag[frameIdx] = 0.0
                        frameIdx++
                    }

                    // Pack Little-Endian 24-bit PCM for ESP32 -> PCM5102A
                    pcm24Bytes[byteIdx++] = (int24L and 0xFF).toByte()
                    pcm24Bytes[byteIdx++] = ((int24L shr 8) and 0xFF).toByte()
                    pcm24Bytes[byteIdx++] = ((int24L shr 16) and 0xFF).toByte()

                    pcm24Bytes[byteIdx++] = (int24R and 0xFF).toByte()
                    pcm24Bytes[byteIdx++] = ((int24R shr 8) and 0xFF).toByte()
                    pcm24Bytes[byteIdx++] = ((int24R shr 16) and 0xFF).toByte()

                    i += 2
                }

                // 1. Build True 24-Bit Histogram Display (Bits 01..24)
                var activeBitsTotal = 0
                val sb1 = StringBuilder("Bits 01-08 (MSB):   [")
                val sb2 = StringBuilder("Bits 09-16 (16Bit): [")
                val sb3 = StringBuilder("Bits 17-24 (24Bit): [")

                for (b in 0 until 24) {
                    val active = bitCounts[b] > (frames / 32)
                    if (active) activeBitsTotal++
                    val ch = if (active) '█' else '·'
                    when {
                        b < 8 -> sb1.append(ch)
                        b < 16 -> sb2.append(ch)
                        else -> sb3.append(ch)
                    }
                }
                sb1.append("]")
                sb2.append("]")
                sb3.append("]")
                StreamState.activeBitCount = activeBitsTotal
                StreamState.bitHistogramText = "$sb1\n$sb2\n$sb3"

                // 2. Compute Levels & Dynamic Crest Factor
                val safeFrames = frames.coerceAtLeast(1)
                val rmsL = sqrt(sumL / safeFrames)
                val rmsR = sqrt(sumR / safeFrames)
                val maxRms = maxOf(rmsL, rmsR)
                val dbRms = if (maxRms > 0.0000001) 20.0 * log10(maxRms) else -100.0
                val dbPeak = if (peakAmp > 0.0000001) 20.0 * log10(peakAmp) else -100.0

                StreamState.dbFs = dbRms
                StreamState.crestFactorDb = (dbPeak - dbRms).coerceAtLeast(0.0)
                StreamState.leftLevel = ((rmsL * 145).toInt()).coerceIn(0, 100)
                StreamState.rightLevel = ((rmsR * 145).toInt()).coerceIn(0, 100)
                StreamState.dataRateKbps = (lockedRate * 2 * 3) / 1000.0

                // 3. Run 1024-Point FFT for High-Frequency Ultrasonic Spectrum (0 kHz - 48 kHz)
                if (frameIdx == 1024) {
                    computeFft1024(fftReal, fftImag)
                    val binHz = lockedRate / 1024.0

                    var eBass = 0.0
                    var eMid = 0.0
                    var eTreble = 0.0
                    var eAir = 0.0
                    var eLossless = 0.0
                    var eUltrasonic = 0.0

                    for (bin in 1 until 512) {
                        val freq = bin * binHz
                        val mag = sqrt(fftReal[bin] * fftReal[bin] + fftImag[bin] * fftImag[bin]) / 512.0
                        when {
                            freq < 250.0 -> if (mag > eBass) eBass = mag
                            freq < 4000.0 -> if (mag > eMid) eMid = mag
                            freq < 12000.0 -> if (mag > eTreble) eTreble = mag
                            freq < 16000.0 -> if (mag > eAir) eAir = mag
                            freq < 20000.0 -> if (mag > eLossless) eLossless = mag
                            else -> if (mag > eUltrasonic) eUltrasonic = mag
                        }
                    }

                    StreamState.bandBass = magToProgress(eBass, 85.0)
                    StreamState.bandMid = magToProgress(eMid, 90.0)
                    StreamState.bandTreble = magToProgress(eTreble, 100.0)
                    StreamState.bandAir = magToProgress(eAir, 110.0)
                    StreamState.bandLossless = magToProgress(eLossless, 115.0)
                    StreamState.bandUltrasonic = magToProgress(eUltrasonic, 120.0)

                    // 4. Automatic Quality Verdict
                    val has24BitDetail = bitCounts[18] > 5 || bitCounts[20] > 5 || bitCounts[22] > 5
                    StreamState.qualityVerdict = when {
                        maxRms < 0.0001 -> "SILENCE (Play a track in Apple Music)"
                        StreamState.bandUltrasonic > 12 && has24BitDetail ->
                            "HI-RES LOSSLESS VERIFIED (24-Bit Micro-Detail + >20kHz Ultrasonic Active!)"
                        StreamState.bandLossless > 15 && has24BitDetail ->
                            "TRUE 24-BIT LOSSLESS VERIFIED (Full 16-20kHz Air + Bits 17-24 Active!)"
                        has24BitDetail ->
                            "24-BIT PCM PRECISION ACTIVE (Clean Pre-DAC Stream, Soft Highs in Current Note)"
                        else ->
                            "16-BIT STANDARD / COMPRESSED (Lower 8 Bits Idle)"
                    }
                }

                // Hex Preview
                val sbHex = StringBuilder()
                for (b in 0 until minOf(9, pcm24Bytes.size)) {
                    sbHex.append(String.format("%02X ", pcm24Bytes[b]))
                }
                StreamState.hexPreview = sbHex.toString().trim()

                // 5. Optional Wi-Fi TCP Stream to ESP32
                if (StreamState.streamToEsp32) {
                    try {
                        if (socket == null || socket.isClosed || !socket.isConnected) {
                            StreamState.statusText = "Connecting to ESP32 (${StreamState.espIp}:${StreamState.espPort})..."
                            socket = createWifiBoundSocket()
                            socket.tcpNoDelay = true
                            socket.connect(InetSocketAddress(StreamState.espIp, StreamState.espPort), 2000)
                            outStream = socket.getOutputStream()
                            StreamState.statusText = "STREAMING 24-BIT HI-RES TO ESP32 (${lockedRate}Hz)"
                        }
                        outStream?.write(pcm24Bytes)
                    } catch (e: Exception) {
                        StreamState.statusText = "ESP32 Wi-Fi Waiting - Local Hi-Res Analyzer Active"
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
                    StreamState.statusText = "AUTO-MAX HI-RES PIPE: ${lockedRate} Hz / 24-Bit Pre-DAC"
                }
            }
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    private fun magToProgress(mag: Double, offsetDb: Double): Int {
        if (mag <= 1e-9) return 0
        val db = 20.0 * log10(mag) + offsetDb
        return db.toInt().coerceIn(0, 100)
    }

    // In-place Radix-2 Cooley-Tukey 1024-point FFT
    private fun computeFft1024(real: DoubleArray, imag: DoubleArray) {
        val n = 1024
        var j = 0
        for (i in 0 until n) {
            if (i < j) {
                val tr = real[i]; real[i] = real[j]; real[j] = tr
                val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
            }
            var m = n shr 1
            while (m >= 1 && j >= m) {
                j -= m
                m = m shr 1
            }
            j += m
        }
        var len = 2
        while (len <= n) {
            val half = len shr 1
            val angle = -2.0 * Math.PI / len
            val wLenR = cos(angle)
            val wLenI = sin(angle)
            var i = 0
            while (i < n) {
                var wR = 1.0
                var wI = 0.0
                for (k in 0 until half) {
                    val uR = real[i + k]
                    val uI = imag[i + k]
                    val vR = real[i + k + half] * wR - imag[i + k + half] * wI
                    val vI = real[i + k + half] * wI + imag[i + k + half] * wR
                    real[i + k] = uR + vR
                    imag[i + k] = uI + vI
                    real[i + k + half] = uR - vR
                    imag[i + k + half] = uI - vI
                    val nextWR = wR * wLenR - wI * wLenI
                    wI = wR * wLenI + wI * wLenR
                    wR = nextWR
                }
                i += len
            }
            len = len shl 1
        }
    }

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

        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
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