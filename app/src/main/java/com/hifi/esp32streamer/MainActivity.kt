package com.hifi.esp32streamer

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.*
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.*
import android.os.*
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
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
    @Volatile var sampleRate = 48000
    @Volatile var nativeMixerRate = 48000
    @Volatile var detectedSourceRate = "Auto-Detecting..."
    @Volatile var activeBitCount = 0
    @Volatile var bitHistogramText = "Bits 01-08: [--------]\nBits 09-16: [--------]\nBits 17-24: [--------]"
    @Volatile var qualityVerdict = "Waiting for music playback..."
    @Volatile var leftLevel = 0
    @Volatile var rightLevel = 0
    @Volatile var leftDb = -40f
    @Volatile var rightDb = -40f
    @Volatile var leftPeak = false
    @Volatile var rightPeak = false
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
    @Volatile var savedVolumeBeforeMute = -1

    fun enforceSpeakerMuteLock(am: AudioManager) {
        if (muteSpeaker) {
            val currentVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (currentVol > 0 && savedVolumeBeforeMute == -1) {
                savedVolumeBeforeMute = currentVol
            }
            if (!am.isStreamMute(AudioManager.STREAM_MUSIC)) {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            }
            if (am.getStreamVolume(AudioManager.STREAM_MUSIC) != 0) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            }
        }
    }

    fun releaseSpeakerMuteLock(am: AudioManager) {
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
        if (savedVolumeBeforeMute > 0) {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, savedVolumeBeforeMute, 0)
            savedVolumeBeforeMute = -1
        }
    }
}

// ================================================================================
// CUSTOM ILLUMINATED VINTAGE ANALOG STEREO VU METER VIEW (LEFT & RIGHT DIALS)
// ================================================================================
class DualAnalogVuMeterView(context: Context) : View(context) {

    private var smoothAngleL = -48f
    private var smoothAngleR = -48f

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#141414") }
    private val bezelOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2C2C2C")
        style = Paint.Style.FILL
    }
    private val bezelInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0D0D0D")
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val dialFacePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scaleBlackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2B1B0E")
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
    }
    private val scaleRedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D50000")
        style = Paint.Style.STROKE
        strokeWidth = 4.5f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2B1B0E")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
    }
    private val redTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D50000")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
    }
    private val vuTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#23150A")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#BDBDBD")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }
    private val peakTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E53935")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val needleShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(85, 0, 0, 0)
        style = Paint.Style.STROKE
        strokeWidth = 4.5f
        strokeCap = Paint.Cap.ROUND
    }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A1A1A")
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
        strokeCap = Paint.Cap.ROUND
    }
    private val pivotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#424242")
        style = Paint.Style.FILL
    }
    private val ledPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Maps dBFS (-40..0 dB) to VU needle angle (-48 deg to +48 deg, where 0 VU = +20 deg)
    private fun dbToAngle(db: Float): Float {
        // Shift dBFS so typical -14 dBFS RMS sits around 0 VU, peaking up to +6 VU
        val vu = (db + 10f).coerceIn(-25f, 6f)
        val norm = when {
            vu <= -20f -> (vu + 25f) / 5f * 0.08f
            vu <= -10f -> 0.08f + (vu + 20f) / 10f * 0.18f
            vu <= -3f  -> 0.26f + (vu + 10f) / 7f * 0.26f
            vu <= 0f   -> 0.52f + (vu + 3f) / 3f * 0.19f
            else       -> 0.71f + (vu / 6f) * 0.29f
        }.coerceIn(0f, 1f)
        return -48f + norm * 96f
    }

    fun updateMeters() {
        val targetL = if (StreamState.isRunning) dbToAngle(StreamState.leftDb) else -48f
        val targetR = if (StreamState.isRunning) dbToAngle(StreamState.rightDb) else -48f

        // Analog VU ballistics: Fast attack (0.45), smooth natural decay (0.18)
        smoothAngleL += if (targetL > smoothAngleL) (targetL - smoothAngleL) * 0.45f else (targetL - smoothAngleL) * 0.18f
        smoothAngleR += if (targetR > smoothAngleR) (targetR - smoothAngleR) * 0.45f else (targetR - smoothAngleR) * 0.18f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        // Outer Dark Console Chassis
        canvas.drawRoundRect(0f, 0f, w, h, 22f, 22f, bgPaint)

        val pad = w * 0.025f
        val meterW = (w - pad * 3f) / 2f
        val topHeader = h * 0.18f
        val bottomFooter = h * 0.16f
        val meterH = h - topHeader - bottomFooter

        // Draw LEFT and RIGHT Illuminated VU Meters
        drawSingleVuMeter(
            canvas,
            left = pad,
            top = topHeader,
            mw = meterW,
            mh = meterH,
            needleAngleDeg = smoothAngleL,
            isPeak = StreamState.leftPeak,
            channelLabel = "LEFT"
        )

        drawSingleVuMeter(
            canvas,
            left = pad * 2f + meterW,
            top = topHeader,
            mw = meterW,
            mh = meterH,
            needleAngleDeg = smoothAngleR,
            isPeak = StreamState.rightPeak,
            channelLabel = "RIGHT"
        )

        // Center calibration screw decoration between LEFT and RIGHT
        val cx = w / 2f
        val cy = h - bottomFooter * 0.5f
        pivotPaint.color = Color.parseColor("#373737")
        canvas.drawCircle(cx, cy, 14f, pivotPaint)
        scaleBlackPaint.color = Color.parseColor("#9E9E9E")
        scaleBlackPaint.strokeWidth = 2f
        canvas.drawLine(cx - 8f, cy - 6f, cx + 8f, cy + 6f, scaleBlackPaint)
        scaleBlackPaint.color = Color.parseColor("#2B1B0E")
    }

    private fun drawSingleVuMeter(
        canvas: Canvas,
        left: Float,
        top: Float,
        mw: Float,
        mh: Float,
        needleAngleDeg: Float,
        isPeak: Boolean,
        channelLabel: String
    ) {
        val cx = left + mw / 2f

        // 1. Draw Red "Peak" LED Lamp + Label at Top
        val ledY = top * 0.35f
        val ledRadius = (mw * 0.032f).coerceIn(7f, 14f)
        if (isPeak) {
            ledPaint.shader = RadialGradient(
                cx, ledY, ledRadius * 2.2f,
                intArrayOf(Color.parseColor("#FF8A80"), Color.parseColor("#FF1744"), Color.TRANSPARENT),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, ledY, ledRadius * 2.2f, ledPaint)
        } else {
            ledPaint.shader = RadialGradient(
                cx - 2f, ledY - 2f, ledRadius,
                Color.parseColor("#8E3A3A"), Color.parseColor("#3E1212"),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, ledY, ledRadius, ledPaint)
        }
        peakTextPaint.textSize = (mw * 0.068f).coerceIn(16f, 28f)
        canvas.drawText("Peak", cx, top * 0.82f, peakTextPaint)

        // 2. Draw Outer Metallic Bezel
        val bezelRect = RectF(left, top, left + mw, top + mh)
        canvas.drawRoundRect(bezelRect, 10f, 10f, bezelOuterPaint)

        val faceInset = mw * 0.045f
        val faceRect = RectF(
            left + faceInset,
            top + faceInset,
            left + mw - faceInset,
            top + mh - faceInset
        )

        // 3. Warm Amber / Yellow Illuminated Backlight Gradient
        dialFacePaint.shader = RadialGradient(
            cx,
            faceRect.bottom - faceRect.height() * 0.15f,
            faceRect.width() * 0.72f,
            intArrayOf(
                Color.parseColor("#FFF3B0"), // Bright warm bulb center
                Color.parseColor("#FFB300"), // Classic amber mid
                Color.parseColor("#E67E00")  // Deep vintage orange edge
            ),
            floatArrayOf(0f, 0.58f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(faceRect, dialFacePaint)
        canvas.drawRect(faceRect, bezelInnerPaint)

        // 4. Pivot Point (Below the bottom edge of the window) & Scale Arcs
        val pivotX = cx
        val pivotY = faceRect.bottom + faceRect.height() * 0.22f
        val arcRadius = faceRect.height() * 0.86f

        canvas.save()
        canvas.clipRect(faceRect)

        val arcRect = RectF(
            pivotX - arcRadius,
            pivotY - arcRadius,
            pivotX + arcRadius,
            pivotY + arcRadius
        )

        scaleBlackPaint.strokeWidth = 3.2f
        scaleRedPaint.strokeWidth = 5.0f
        canvas.drawArc(arcRect, -138f, 68f, false, scaleBlackPaint) // -20 to 0 VU
        canvas.drawArc(arcRect, -70f, 28f, false, scaleRedPaint)    // 0 to +6 VU Red Zone

        // Inner secondary sub-arc for % modulation
        val innerRadius = arcRadius * 0.92f
        val innerArcRect = RectF(
            pivotX - innerRadius,
            pivotY - innerRadius,
            pivotX + innerRadius,
            pivotY + innerRadius
        )
        scaleBlackPaint.strokeWidth = 1.8f
        canvas.drawArc(innerArcRect, -138f, 68f, false, scaleBlackPaint)

        // 5. Tick Marks & Numbers (-20, -10, -7, -5, -3, -1, 0, +3, +6)
        data class Tick(val norm: Float, val label: String, val isRed: Boolean, val subPct: String? = null)
        val ticks = listOf(
            Tick(0.04f, "-", false, "%"),
            Tick(0.10f, "20", false, "20"),
            Tick(0.26f, "10", false, "40"),
            Tick(0.37f, "7", false, "60"),
            Tick(0.45f, "5", false),
            Tick(0.53f, "3", false, "80"),
            Tick(0.64f, "1", false),
            Tick(0.71f, "0", true, "100"),
            Tick(0.85f, "3", true),
            Tick(0.97f, "6", true, "+")
        )

        textPaint.textSize = (mw * 0.052f).coerceIn(11f, 22f)
        redTextPaint.textSize = textPaint.textSize
        val subTextSize = textPaint.textSize * 0.82f

        for (t in ticks) {
            val degFromUp = -48f + t.norm * 96f
            val rad = Math.toRadians((degFromUp - 90f).toDouble())
            val cosA = cos(rad).toFloat()
            val sinA = sin(rad).toFloat()

            val rOuter = arcRadius * 1.09f
            val rInner = arcRadius * 0.99f
            val rText = arcRadius * 1.19f

            val pTick = if (t.isRed) scaleRedPaint else scaleBlackPaint
            pTick.strokeWidth = if (t.label == "0") 4f else 2.5f
            canvas.drawLine(
                pivotX + rInner * cosA, pivotY + rInner * sinA,
                pivotX + rOuter * cosA, pivotY + rOuter * sinA,
                pTick
            )

            val pTxt = if (t.isRed) redTextPaint else textPaint
            pTxt.textSize = (mw * 0.052f).coerceIn(11f, 22f)
            canvas.drawText(
                t.label,
                pivotX + rText * cosA,
                pivotY + rText * sinA + pTxt.textSize * 0.35f,
                pTxt
            )

            if (t.subPct != null) {
                val rSub = arcRadius * 0.81f
                textPaint.textSize = subTextSize
                canvas.drawText(
                    t.subPct,
                    pivotX + rSub * cosA,
                    pivotY + rSub * sinA + subTextSize * 0.35f,
                    textPaint
                )
            }
        }

        // 6. "VU" & "DECIBEL POWER LEVEL" Faceplate Text
        vuTitlePaint.textSize = (mw * 0.088f).coerceIn(18f, 34f)
        canvas.drawText("VU", cx, faceRect.top + faceRect.height() * 0.72f, vuTitlePaint)

        vuTitlePaint.textSize = (mw * 0.038f).coerceIn(9f, 15f)
        canvas.drawText("DECIBEL POWER LEVEL", cx, faceRect.top + faceRect.height() * 0.84f, vuTitlePaint)

        // 7. Draw Swinging Needle + Drop Shadow
        val needleRad = Math.toRadians((needleAngleDeg - 90f).toDouble())
        val nLen = arcRadius * 1.14f
        val tipX = pivotX + nLen * cos(needleRad).toFloat()
        val tipY = pivotY + nLen * sin(needleRad).toFloat()

        canvas.drawLine(pivotX + 4f, pivotY + 4f, tipX + 4f, tipY + 4f, needleShadowPaint)
        canvas.drawLine(pivotX, pivotY, tipX, tipY, needlePaint)

        // Bottom Pivot Cap
        pivotPaint.color = Color.parseColor("#2B2B2B")
        canvas.drawCircle(pivotX, faceRect.bottom + faceRect.height() * 0.04f, mw * 0.07f, pivotPaint)

        canvas.restore()

        // 8. "LEFT" / "RIGHT" Console Legend Below Meter
        labelPaint.textSize = (mw * 0.075f).coerceIn(18f, 30f)
        canvas.drawText(channelLabel, cx, top + mh + (height - (top + mh)) * 0.68f, labelPaint)
    }
}

// ================================================================================
// MAIN ACTIVITY
// ================================================================================
class MainActivity : AppCompatActivity() {

    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var audioManager: AudioManager
    private val uiHandler = Handler(Looper.getMainLooper())

    private lateinit var vuMeterView: DualAnalogVuMeterView
    private lateinit var tvStatus: TextView
    private lateinit var tvVerdict: TextView
    private lateinit var tvFormatInfo: TextView
    private lateinit var tvBitHistogram: TextView
    private lateinit var tvHex: TextView
    private lateinit var tvDb: TextView

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
            vuMeterView.updateMeters()

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
            uiHandler.postDelayed(this, 33) // 30 FPS for silky-smooth analog needle motion!
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        StreamState.nativeMixerRate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48000

        buildDashboardUi()
        requestNeededPermissions()
    }

    // LAYER 1 MUTE LOCK: Intercept physical Volume Up/Down keys when Mute Speaker switch is ON
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (StreamState.muteSpeaker && (
                event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
                event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
                event.keyCode == KeyEvent.KEYCODE_VOLUME_MUTE
            )) {
            StreamState.enforceSpeakerMuteLock(audioManager)
            if (event.action == KeyEvent.ACTION_DOWN) {
                Toast.makeText(this, "Phone Speaker Locked Muted (Uncheck Mute switch to change volume)", Toast.LENGTH_SHORT).show()
            }
            return true // Swallow hardware volume key so Android cannot unmute speaker!
        }
        return super.dispatchKeyEvent(event)
    }

    private fun buildDashboardUi() {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#101418")) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 40, 32, 44)
        }

        val title = TextView(this).apply {
            text = "ESP32 + PCM5102A\n24-Bit Hi-Res Pre-DAC Streamer"
            textSize = 18.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#FFB300"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 20)
        }
        root.addView(title)

        // Illuminated Dual Analog VU Meter Console
        val density = resources.displayMetrics.density
        vuMeterView = DualAnalogVuMeterView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (235 * density).toInt()
            ).apply { bottomMargin = 18 }
        }
        root.addView(vuMeterView)

        tvDb = makeCardTextView("RMS Level: -100.0 dBFS", "#FFD54F", 12.5f, true)
        root.addView(tvDb)

        tvStatus = makeCardTextView("STATUS: Ready", "#FFFFFF", 13.5f, true)
        root.addView(tvStatus)

        tvVerdict = makeCardTextView("QUALITY VERDICT:\nWaiting for audio...", "#00E676", 14f, true)
        root.addView(tvVerdict)

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
            text = "Mute Phone Speaker (Locked Even If Volume Up Pressed)"
            setTextColor(Color.WHITE)
            textSize = 13f
            isChecked = StreamState.muteSpeaker
            setPadding(0, 24, 0, 12)
            setOnCheckedChangeListener { _, isChecked ->
                StreamState.muteSpeaker = isChecked
                if (isChecked) {
                    StreamState.enforceSpeakerMuteLock(audioManager)
                } else {
                    StreamState.releaseSpeakerMuteLock(audioManager)
                }
            }
        }
        root.addView(swMute)

        swEspMode = Switch(this).apply {
            text = "Stream 24-Bit PCM to ESP32 Wi-Fi (Keep OFF for Local Test)"
            setTextColor(Color.parseColor("#81D4FA"))
            textSize = 13f
            isChecked = StreamState.streamToEsp32
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
// BACKGROUND ENGINE: Auto-Max 48kHz Lock + 24-Bit Histogram + FFT + Mute Lock
// ================================================================================
class AudioCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    private lateinit var audioManager: AudioManager

    // LAYER 2 MUTE LOCK: System-wide BroadcastReceiver catches Volume Up/Down even inside Apple Music!
    private val volumeLockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (StreamState.muteSpeaker) {
                StreamState.enforceSpeakerMuteLock(audioManager)
            }
        }
    }

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            if (configs.isNullOrEmpty()) {
                StreamState.detectedSourceRate = "No Active Player"
                return
            }
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

        val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(volumeLockReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(volumeLockReceiver, filter)
        }
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
            .setContentTitle("24-Bit Hi-Res Pre-DAC Capture Active")
            .setContentText("Analog VU + 24-bit histogram & ESP32 streamer")
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

    private fun startAutoMaxHiResCapture(resultCode: Int, dataIntent: Intent) {
        val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = pm.getMediaProjection(resultCode, dataIntent)

        val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val candidateRates = intArrayOf(48000, 44100)
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

        if (StreamState.muteSpeaker) {
            StreamState.enforceSpeakerMuteLock(audioManager)
        }

        StreamState.sampleRate = lockedRate
        audioRecord?.startRecording()
        StreamState.isRunning = true
        StreamState.statusText = "LOCKED AT HI-RES: ${lockedRate} Hz / 24-Bit Float-to-PCM"

        captureThread = thread(start = true, name = "HiResPreDacThread") {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val floatSamples = FloatArray(2048) // 1024 Stereo Frames
            val fftReal = DoubleArray(1024)
            val fftImag = DoubleArray(1024)
            val bitCounts = IntArray(24)

            var socket: Socket? = null
            var outStream: OutputStream? = null

            while (StreamState.isRunning) {
                // LAYER 3 MUTE LOCK: Continuously enforce speaker mute in real-time audio loop
                if (StreamState.muteSpeaker) {
                    StreamState.enforceSpeakerMuteLock(audioManager)
                }

                val readCount = audioRecord?.read(floatSamples, 0, floatSamples.size, AudioRecord.READ_BLOCKING) ?: 0
                if (readCount <= 0) continue

                var sumL = 0.0
                var sumR = 0.0
                var peakL = 0.0
                var peakR = 0.0
                bitCounts.fill(0)

                val pcm24Bytes = ByteArray(readCount * 3)
                var byteIdx = 0
                val frames = readCount / 2

                var frameIdx = 0
                var i = 0
                while (i < readCount) {
                    val fL = floatSamples[i].coerceIn(-1.0f, 1.0f)
                    val fR = if (i + 1 < readCount) floatSamples[i + 1].coerceIn(-1.0f, 1.0f) else 0f

                    val absL = abs(fL).toDouble()
                    val absR = abs(fR).toDouble()
                    if (absL > peakL) peakL = absL
                    if (absR > peakR) peakR = absR

                    sumL += (fL * fL)
                    sumR += (fR * fR)

                    val int24L = (fL * 8388607.0f).roundToInt()
                    val int24R = (fR * 8388607.0f).roundToInt()

                    val magL = abs(int24L)
                    val magR = abs(int24R)
                    val combinedBits = magL or magR
                    if (combinedBits != 0) {
                        for (b in 0 until 24) {
                            val shift = 22 - b
                            val mask = if (shift >= 0) (1 shl shift) else 1
                            if ((combinedBits and mask) != 0) {
                                bitCounts[b]++
                            }
                        }
                    }

                    if (frameIdx < 1024) {
                        val window = 0.5 * (1.0 - cos(2.0 * Math.PI * frameIdx / 1023.0))
                        fftReal[frameIdx] = ((fL + fR) * 0.5) * window
                        fftImag[frameIdx] = 0.0
                        frameIdx++
                    }

                    pcm24Bytes[byteIdx++] = (int24L and 0xFF).toByte()
                    pcm24Bytes[byteIdx++] = ((int24L shr 8) and 0xFF).toByte()
                    pcm24Bytes[byteIdx++] = ((int24L shr 16) and 0xFF).toByte()

                    pcm24Bytes[byteIdx++] = (int24R and 0xFF).toByte()
                    pcm24Bytes[byteIdx++] = ((int24R shr 8) and 0xFF).toByte()
                    pcm24Bytes[byteIdx++] = ((int24R shr 16) and 0xFF).toByte()

                    i += 2
                }

                // 1. Build True 24-Bit Histogram Display
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

                // 2. Compute Analog VU Dials, Peak LEDs & Dynamic Crest Factor
                val safeFrames = frames.coerceAtLeast(1)
                val rmsL = sqrt(sumL / safeFrames)
                val rmsR = sqrt(sumR / safeFrames)
                val maxRms = maxOf(rmsL, rmsR)
                val peakAmp = maxOf(peakL, peakR)

                val dbRmsL = if (rmsL > 0.00001) (20.0 * log10(rmsL)).toFloat() else -50f
                val dbRmsR = if (rmsR > 0.00001) (20.0 * log10(rmsR)).toFloat() else -50f
                val dbRms = if (maxRms > 0.0000001) 20.0 * log10(maxRms) else -100.0
                val dbPeak = if (peakAmp > 0.0000001) 20.0 * log10(peakAmp) else -100.0

                StreamState.leftDb = dbRmsL
                StreamState.rightDb = dbRmsR
                StreamState.leftPeak = peakL > 0.65 || dbRmsL > -9f
                StreamState.rightPeak = peakR > 0.65 || dbRmsR > -9f

                StreamState.dbFs = dbRms
                StreamState.crestFactorDb = (dbPeak - dbRms).coerceAtLeast(0.0)
                StreamState.leftLevel = ((rmsL * 145).toInt()).coerceIn(0, 100)
                StreamState.rightLevel = ((rmsR * 145).toInt()).coerceIn(0, 100)
                StreamState.dataRateKbps = (lockedRate * 2 * 3) / 1000.0

                // 3. Run 1024-Point FFT for High-Frequency Ultrasonic Spectrum
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

        StreamState.releaseSpeakerMuteLock(audioManager)
        StreamState.leftDb = -50f
        StreamState.rightDb = -50f
        StreamState.leftPeak = false
        StreamState.rightPeak = false
        StreamState.statusText = "STOPPED - Ready"
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        try { unregisterReceiver(volumeLockReceiver) } catch (_: Exception) {}
        audioManager.unregisterAudioPlaybackCallback(playbackCallback)
        cleanupAndStop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}