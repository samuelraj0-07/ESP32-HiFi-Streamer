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
import android.view.KeyEvent
import android.view.MotionEvent
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

// ================================================================================
// SHARED LIVE STATE BETWEEN BACKGROUND SERVICE AND INDUSTRIAL CONSOLE UI
// ================================================================================
object StreamState {
    @Volatile var isRunning = false
    @Volatile var statusText = "IDLE"
    @Volatile var sampleRate = 48000
    @Volatile var nativeMixerRate = 48000
    @Volatile var detectedSourceRate = "48000Hz"
    @Volatile var activeBitCount = 0
    val bitActiveFlags = BooleanArray(24)
    @Volatile var qualityVerdict = "WAITING FOR MUSIC PLAYBACK..."
    @Volatile var leftLevel = 0
    @Volatile var rightLevel = 0
    @Volatile var leftDb = -40f
    @Volatile var rightDb = -40f
    @Volatile var leftPeak = false
    @Volatile var rightPeak = false
    @Volatile var dbFs = -100.0
    @Volatile var crestFactorDb = 0.0
    @Volatile var dataRateKbps = 0.0
    @Volatile var hexPreview = "00 00 00 00 00 00"

    // 6-Band FFT Spectrum Analyzer Levels (0..100)
    @Volatile var bandBass = 0
    @Volatile var bandMid = 0
    @Volatile var bandTreble = 0
    @Volatile var bandAir = 0
    @Volatile var bandLossless = 0
    @Volatile var bandUltrasonic = 0

    @Volatile var streamToEsp32 = false
    @Volatile var isEspConnected = false
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
// HELPER: Draws Realistic 3D Brushed Metal Screws & LEDs on Canvas
// ================================================================================
object HardwareDraw {
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    fun drawScrew(c: Canvas, cx: Float, cy: Float, r: Float) {
        pFill.shader = RadialGradient(
            cx - r * 0.25f, cy - r * 0.25f, r * 1.2f,
            Color.parseColor("#9E9E9E"), Color.parseColor("#2B2B2B"), Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, cy, r, pFill)
        pFill.shader = null
        pStroke.color = Color.parseColor("#141414")
        pStroke.strokeWidth = 1.5f
        c.drawCircle(cx, cy, r, pStroke)

        // Phillips cross slot
        pStroke.color = Color.parseColor("#1E1E1E")
        pStroke.strokeWidth = (r * 0.28f).coerceAtLeast(2f)
        c.drawLine(cx - r * 0.55f, cy - r * 0.15f, cx + r * 0.55f, cy + r * 0.15f, pStroke)
        c.drawLine(cx - r * 0.15f, cy + r * 0.55f, cx + r * 0.15f, cy - r * 0.55f, pStroke)
    }

    fun drawLedLamp(c: Canvas, cx: Float, cy: Float, r: Float, isOn: Boolean, onColor: Int, offColor: Int) {
        // Dark metallic socket ring
        pFill.shader = null
        pFill.color = Color.parseColor("#161616")
        c.drawCircle(cx, cy, r * 1.28f, pFill)

        if (isOn) {
            // Outer halo glow
            pFill.shader = RadialGradient(
                cx, cy, r * 2.2f,
                intArrayOf(Color.WHITE, onColor, Color.TRANSPARENT),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP
            )
            c.drawCircle(cx, cy, r * 2.2f, pFill)
        } else {
            pFill.shader = RadialGradient(
                cx - r * 0.2f, cy - r * 0.2f, r,
                offColor, Color.parseColor("#121212"), Shader.TileMode.CLAMP
            )
            c.drawCircle(cx, cy, r, pFill)
        }
        pFill.shader = null
    }
}

// ================================================================================
// 1. TOP INDUSTRIAL NAMEPLATE ("TRANSMITTER #2 / ESP32 + PCM5102A")
// ================================================================================
class TransmitterHeaderView(context: Context) : View(context) {
    private val plaquePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#555555")
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#181818")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D0D0D0")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        // Left & Right Chassis Screws
        val screwR = h * 0.16f
        HardwareDraw.drawScrew(canvas, screwR * 1.5f, h * 0.36f, screwR)
        HardwareDraw.drawScrew(canvas, w - screwR * 1.5f, h * 0.36f, screwR)

        // White/Silver Enamel Nameplate
        val pLeft = screwR * 3.1f
        val pRight = w - screwR * 3.1f
        val pBottom = h * 0.62f
        val rect = RectF(pLeft, h * 0.08f, pRight, pBottom)

        plaquePaint.shader = LinearGradient(
            0f, rect.top, 0f, rect.bottom,
            intArrayOf(Color.parseColor("#F5F5F5"), Color.parseColor("#D6D6D6"), Color.parseColor("#BDBDBD")),
            floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rect, 6f, 6f, plaquePaint)
        canvas.drawRoundRect(rect, 6f, 6f, borderPaint)

        titlePaint.textSize = (rect.height() * 0.58f).coerceIn(24f, 46f)
        canvas.drawText(
            "TRANSMITTER #2 / ESP32 + PCM5102A",
            w / 2f,
            rect.centerY() + titlePaint.textSize * 0.36f,
            titlePaint
        )

        subPaint.textSize = (h * 0.22f).coerceIn(18f, 32f)
        canvas.drawText("24-BIT HI-RES PRE-DAC STREAMER", w / 2f, h * 0.91f, subPaint)
    }
}

// ================================================================================
// 2. WHITE-FACE RECESSED STUDIO VU METERS (LEFT & RIGHT)
// ================================================================================
class DualAnalogVuMeterView(context: Context) : View(context) {
    private var smoothAngleL = -36f
    private var smoothAngleR = -36f

    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val panelBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#757575")
    }
    private val bezelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#222222") }
    private val innerShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Color.parseColor("#111111")
    }
    private val dialPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#212121")
        strokeWidth = 3f
    }
    private val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#212121")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
    private val vuPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1B1B1B")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E0E0E0")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
    private val needleShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 0, 0, 0)
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
    }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#262626")
        style = Paint.Style.STROKE
        strokeWidth = 3.8f
        strokeCap = Paint.Cap.ROUND
    }

    private fun dbToAngle(db: Float): Float {
        val vu = (db + 10f).coerceIn(-22f, 3f)
        val norm = ((vu + 22f) / 25f).coerceIn(0f, 1f)
        return -36f + norm * 72f // Fits 100% cleanly inside the bezel!
    }

    fun updateMeters() {
        val targetL = if (StreamState.isRunning) dbToAngle(StreamState.leftDb) else -36f
        val targetR = if (StreamState.isRunning) dbToAngle(StreamState.rightDb) else -36f

        smoothAngleL += if (targetL > smoothAngleL) (targetL - smoothAngleL) * 0.45f else (targetL - smoothAngleL) * 0.18f
        smoothAngleR += if (targetR > smoothAngleR) (targetR - smoothAngleR) * 0.45f else (targetR - smoothAngleR) * 0.18f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        val chassisRect = RectF(4f, 4f, w - 4f, h - 4f)
        panelPaint.shader = LinearGradient(
            0f, 0f, 0f, h,
            Color.parseColor("#35373A"), Color.parseColor("#242628"), Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(chassisRect, 18f, 18f, panelPaint)
        canvas.drawRoundRect(chassisRect, 18f, 18f, panelBorderPaint)

        val pad = w * 0.035f
        val meterW = (w - pad * 3f) / 2f
        val topHeader = h * 0.21f
        val bottomFooter = h * 0.16f
        val meterH = h - topHeader - bottomFooter

        drawSingleMeter(canvas, pad, topHeader, meterW, meterH, smoothAngleL, StreamState.leftPeak, "LEFT")
        drawSingleMeter(canvas, pad * 2f + meterW, topHeader, meterW, meterH, smoothAngleR, StreamState.rightPeak, "RIGHT")
    }

    private fun drawSingleMeter(
        canvas: Canvas, left: Float, top: Float, mw: Float, mh: Float,
        angleDeg: Float, isPeak: Boolean, label: String
    ) {
        val cx = left + mw / 2f

        // Red PEAK Lamp & Label
        HardwareDraw.drawLedLamp(
            canvas, cx, top * 0.36f, (mw * 0.028f).coerceIn(7f, 12f),
            isPeak, Color.parseColor("#FF1744"), Color.parseColor("#5D1919")
        )
        labelPaint.textSize = (mw * 0.058f).coerceIn(14f, 24f)
        canvas.drawText("PEAK", cx, top * 0.84f, labelPaint)

        // Outer Dark Bezel
        val bezelRect = RectF(left, top, left + mw, top + mh)
        canvas.drawRoundRect(bezelRect, 12f, 12f, bezelPaint)
        panelBorderPaint.strokeWidth = 2f
        canvas.drawRoundRect(bezelRect, 12f, 12f, panelBorderPaint)

        // White/Silver Recessed Dial Window
        val inset = mw * 0.055f
        val faceRect = RectF(left + inset, top + inset, left + mw - inset, top + mh - inset)
        dialPaint.shader = RadialGradient(
            cx, faceRect.centerY(), faceRect.width() * 0.7f,
            intArrayOf(Color.parseColor("#F5F7F8"), Color.parseColor("#D8DCDF"), Color.parseColor("#ABB0B5")),
            floatArrayOf(0f, 0.65f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(faceRect, 6f, 6f, dialPaint)
        canvas.drawRoundRect(faceRect, 6f, 6f, innerShadowPaint)

        val pivotX = cx
        val pivotY = faceRect.bottom + faceRect.height() * 0.28f
        val arcR = faceRect.height() * 0.84f

        canvas.save()
        canvas.clipRect(faceRect)

        // Scale Arc (-36 deg to +36 deg around -90 deg UP = -126 deg to -54 deg)
        val arcRect = RectF(pivotX - arcR, pivotY - arcR, pivotX + arcR, pivotY + arcR)
        arcPaint.strokeWidth = 2.8f
        canvas.drawArc(arcRect, -126f, 72f, false, arcPaint)

        // Major & Minor Ticks (10, 7, 5, 3, 1, 0, 1) - 100% inside window!
        data class VuTick(val norm: Float, val txt: String, val major: Boolean)
        val ticks = listOf(
            VuTick(0.00f, "10", true),
            VuTick(0.10f, "", false),
            VuTick(0.20f, "7", true),
            VuTick(0.30f, "", false),
            VuTick(0.40f, "5", true),
            VuTick(0.50f, "", false),
            VuTick(0.60f, "3", true),
            VuTick(0.70f, "", false),
            VuTick(0.80f, "1", true),
            VuTick(0.90f, "0", true),
            VuTick(1.00f, "1", true)
        )

        numPaint.textSize = (mw * 0.062f).coerceIn(13f, 24f)
        for (t in ticks) {
            val deg = -36f + t.norm * 72f
            val rad = Math.toRadians((deg - 90f).toDouble())
            val cosA = cos(rad).toFloat()
            val sinA = sin(rad).toFloat()

            val rIn = arcR
            val rOut = if (t.major) arcR * 1.11f else arcR * 1.06f
            arcPaint.strokeWidth = if (t.major) 3f else 2f
            canvas.drawLine(pivotX + rIn * cosA, pivotY + rIn * sinA, pivotX + rOut * cosA, pivotY + rOut * sinA, arcPaint)

            if (t.txt.isNotEmpty()) {
                val rTxt = arcR * 1.24f
                canvas.drawText(t.txt, pivotX + rTxt * cosA, pivotY + rTxt * sinA + numPaint.textSize * 0.35f, numPaint)
            }
        }

        // "VU" Legend
        vuPaint.textSize = (mw * 0.095f).coerceIn(20f, 36f)
        canvas.drawText("VU", cx, faceRect.bottom - faceRect.height() * 0.16f, vuPaint)

        // Swinging Needle + Drop Shadow
        val nRad = Math.toRadians((angleDeg - 90f).toDouble())
        val nLen = arcR * 1.15f
        val tipX = pivotX + nLen * cos(nRad).toFloat()
        val tipY = pivotY + nLen * sin(nRad).toFloat()

        canvas.drawLine(pivotX + 4f, pivotY + 4f, tipX + 4f, tipY + 4f, needleShadowPaint)
        canvas.drawLine(pivotX, pivotY, tipX, tipY, needlePaint)
        canvas.restore()

        // LEFT / RIGHT Footer
        labelPaint.textSize = (mw * 0.07f).coerceIn(16f, 28f)
        canvas.drawText(label, cx, top + mh + (height - (top + mh)) * 0.68f, labelPaint)
    }
}

// ================================================================================
// 3. SIDE-BY-SIDE STATUS & RMS LEVELS CONSOLE (Vintage Paper Tags + 4x3 LED Grid)
// ================================================================================
class StatusAndRmsPanelView(context: Context) : View(context) {
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#8D8D8D")
    }
    private val paperPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E0E0E0")
        typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    }
    private val paperTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1B1B1B")
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
    private val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CCCCCC")
        typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val gap = w * 0.02f
        val cardW = (w - gap) / 2f

        val shortStatus = when {
            !StreamState.isRunning -> "STATUS: IDLE"
            StreamState.isEspConnected -> "WIFI: ACTIVE"
            StreamState.streamToEsp32 -> "WIFI: WAIT"
            else -> "LOCAL: ${StreamState.sampleRate / 1000}kHz"
        }
        val subStatus = if (StreamState.isRunning) {
            "${StreamState.sampleRate}Hz | ${String.format("%.0f", StreamState.dataRateKbps)} KB/s"
        } else {
            "STATUS: IDLE (${StreamState.nativeMixerRate}Hz)"
        }

        drawModuleCard(
            canvas, 4f, 4f, cardW - 4f, h - 8f,
            title = "STATUS",
            paperTxt = shortStatus,
            footerTxt = subStatus,
            amberActive = StreamState.isRunning,
            greenActive = StreamState.isEspConnected || (StreamState.isRunning && !StreamState.streamToEsp32)
        )

        val rmsStr = if (StreamState.dbFs <= -99.0) "RMS: -100.0 dBFS" else "RMS: ${String.format("%.1f", StreamState.dbFs)} dBFS"
        val crestStr = "RMS: ${String.format("%.1f", StreamState.dbFs)} / CREST: ${String.format("%.1f", StreamState.crestFactorDb)} dB"

        drawModuleCard(
            canvas, cardW + gap, 4f, cardW - 4f, h - 8f,
            title = "RMS Levels",
            paperTxt = rmsStr,
            footerTxt = crestStr,
            amberActive = StreamState.dbFs > -45.0,
            greenActive = StreamState.dbFs > -25.0
        )
    }

    private fun drawModuleCard(
        canvas: Canvas, x: Float, y: Float, cw: Float, ch: Float,
        title: String, paperTxt: String, footerTxt: String,
        amberActive: Boolean, greenActive: Boolean
    ) {
        val rect = RectF(x, y, x + cw, y + ch)
        boxPaint.shader = LinearGradient(
            x, y, x, y + ch,
            Color.parseColor("#383A3D"), Color.parseColor("#26282A"), Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rect, 18f, 18f, boxPaint)
        canvas.drawRoundRect(rect, 18f, 18f, borderPaint)

        val pad = cw * 0.06f
        titlePaint.textSize = (ch * 0.16f).coerceIn(18f, 30f)
        canvas.drawText(title, x + pad, y + ch * 0.23f, titlePaint)

        // Vintage Aged-Paper Tag
        val pTop = y + ch * 0.31f
        val pBottom = y + ch * 0.72f
        val pRight = x + cw * 0.63f
        val paperRect = RectF(x + pad, pTop, pRight, pBottom)
        paperPaint.shader = LinearGradient(
            paperRect.left, pTop, paperRect.right, pBottom,
            Color.parseColor("#E8DEC8"), Color.parseColor("#C9B99B"), Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(paperRect, 4f, 4f, paperPaint)

        paperTextPaint.textSize = (ch * 0.145f).coerceIn(15f, 26f)
        canvas.drawText(paperTxt, paperRect.left + 12f, paperRect.centerY() + paperTextPaint.textSize * 0.35f, paperTextPaint)

        // 4x3 Indicator LED Matrix on Right of Paper Tag
        val gridLeft = pRight + cw * 0.05f
        val gridW = (x + cw - pad) - gridLeft
        val gridH = pBottom - pTop
        val cols = 4
        val rows = 3
        val ledR = (minOf(gridW / cols, gridH / rows) * 0.32f).coerceIn(4f, 9f)

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val lx = gridLeft + (c + 0.5f) * (gridW / cols)
                val ly = pTop + (r + 0.5f) * (gridH / rows)
                val isAmberSpot = (c == 0 && r < 2)
                val isGreenSpot = (r == 2 && c >= 2)
                val isOn = (isAmberSpot && amberActive) || (isGreenSpot && greenActive)
                val onCol = if (isAmberSpot) Color.parseColor("#FFB300") else Color.parseColor("#00E676")
                val offCol = if (isAmberSpot) Color.parseColor("#4E3810") else Color.parseColor("#193D24")
                HardwareDraw.drawLedLamp(canvas, lx, ly, ledR, isOn, onCol, offCol)
            }
        }

        footerPaint.textSize = (ch * 0.115f).coerceIn(13f, 21f)
        canvas.drawText(footerTxt, x + pad, y + ch * 0.90f, footerPaint)
    }
}

// ================================================================================
// 4. BRUSHED STEEL "QUALITY VERDICT" PLATE WITH SCREWS & LAMPS
// ================================================================================
class QualityVerdictPlateView(context: Context) : View(context) {
    private val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#8D8D8D")
    }
    private val platePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val slotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#CFC7B8") }
    private val slotBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#5D5951")
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E0E0E0")
        typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    }
    private val darkLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#181818")
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        val outer = RectF(4f, 4f, w - 4f, h - 4f)
        outerPaint.shader = LinearGradient(0f, 0f, 0f, h, Color.parseColor("#383A3D"), Color.parseColor("#26282A"), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(outer, 18f, 18f, outerPaint)
        canvas.drawRoundRect(outer, 18f, 18f, borderPaint)

        val pad = w * 0.035f
        titlePaint.textSize = (h * 0.16f).coerceIn(18f, 28f)
        canvas.drawText("QUALITY VERDICT", pad, h * 0.22f, titlePaint)

        // Brushed Metal Inner Plate
        val plate = RectF(pad, h * 0.28f, w - pad, h * 0.90f)
        platePaint.shader = LinearGradient(
            plate.left, plate.top, plate.left, plate.bottom,
            intArrayOf(Color.parseColor("#B8B9B4"), Color.parseColor("#92948F"), Color.parseColor("#767874")),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(plate, 10f, 10f, platePaint)

        val sr = plate.height() * 0.08f
        HardwareDraw.drawScrew(canvas, plate.left + sr * 2f, plate.top + sr * 2f, sr)
        HardwareDraw.drawScrew(canvas, plate.right - sr * 2f, plate.top + sr * 2f, sr)
        HardwareDraw.drawScrew(canvas, plate.left + sr * 2f, plate.bottom - sr * 2f, sr)
        HardwareDraw.drawScrew(canvas, plate.right - sr * 2f, plate.bottom - sr * 2f, sr)

        darkLabel.textSize = (plate.height() * 0.23f).coerceIn(16f, 26f)
        canvas.drawText("VERDICT", plate.left + plate.width() * 0.13f, plate.top + plate.height() * 0.30f, darkLabel)

        // OFF / ON & OFF / READY Lamps
        val rLed = (plate.height() * 0.095f).coerceIn(6f, 12f)
        val row1Y = plate.top + plate.height() * 0.52f
        val row2Y = plate.top + plate.height() * 0.78f
        darkLabel.textSize = (plate.height() * 0.19f).coerceIn(13f, 22f)

        canvas.drawText("OFF", plate.left + plate.width() * 0.08f, row1Y + darkLabel.textSize * 0.35f, darkLabel)
        HardwareDraw.drawLedLamp(canvas, plate.left + plate.width() * 0.17f, row1Y, rLed, !StreamState.isRunning, Color.parseColor("#FF5252"), Color.parseColor("#4A1C1C"))
        HardwareDraw.drawLedLamp(canvas, plate.left + plate.width() * 0.22f, row1Y, rLed, StreamState.isRunning, Color.parseColor("#FFB300"), Color.parseColor("#4E3810"))
        canvas.drawText("ON", plate.left + plate.width() * 0.26f, row1Y + darkLabel.textSize * 0.35f, darkLabel)

        val isReadyLossless = StreamState.isRunning && StreamState.activeBitCount >= 16
        canvas.drawText("OFF", plate.left + plate.width() * 0.08f, row2Y + darkLabel.textSize * 0.35f, darkLabel)
        HardwareDraw.drawLedLamp(canvas, plate.left + plate.width() * 0.17f, row2Y, rLed, !isReadyLossless, Color.parseColor("#8D6E63"), Color.parseColor("#3E2723"))
        HardwareDraw.drawLedLamp(canvas, plate.left + plate.width() * 0.22f, row2Y, rLed, isReadyLossless, Color.parseColor("#00E676"), Color.parseColor("#193D24"))
        canvas.drawText("READY", plate.left + plate.width() * 0.26f, row2Y + darkLabel.textSize * 0.35f, darkLabel)

        // Recessed Paper/Stencil Slot on Right
        val slotRect = RectF(plate.left + plate.width() * 0.39f, plate.top + plate.height() * 0.32f, plate.right - plate.width() * 0.05f, plate.bottom - plate.height() * 0.22f)
        canvas.drawRoundRect(slotRect, 6f, 6f, slotPaint)
        canvas.drawRoundRect(slotRect, 6f, 6f, slotBorder)

        darkLabel.textSize = (slotRect.height() * 0.44f).coerceIn(13f, 22f)
        val verdictShort = StreamState.qualityVerdict.take(32)
        canvas.drawText(verdictShort, slotRect.left + 12f, slotRect.centerY() + darkLabel.textSize * 0.35f, darkLabel)
    }
}

// ================================================================================
// 5. TRUE 24-BIT HISTOGRAM (3x8 Glowing Amber Nixie/Vacuum Segment Blocks)
// ================================================================================
class Histogram24BitMatrixView(context: Context) : View(context) {
    private val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#8D8D8D")
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E0E0E0")
        typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    }
    private val rowLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D6D6D6")
        typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    }
    private val blockPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blockFramePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        color = Color.parseColor("#141414")
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        val outer = RectF(4f, 4f, w - 4f, h - 4f)
        outerPaint.shader = LinearGradient(0f, 0f, 0f, h, Color.parseColor("#35373A"), Color.parseColor("#222426"), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(outer, 18f, 18f, outerPaint)
        canvas.drawRoundRect(outer, 18f, 18f, borderPaint)

        val pad = w * 0.035f
        titlePaint.textSize = (h * 0.15f).coerceIn(18f, 28f)
        canvas.drawText(
            "TRUE 24-BIT HISTOGRAM (${StreamState.activeBitCount}/24 BITS)   HEX: ${StreamState.hexPreview}",
            pad, h * 0.21f, titlePaint
        )

        val labels = arrayOf("Bits 01-08:", "Bits 09-16:", "Bits 17-24:")
        val startY = h * 0.28f
        val availH = h * 0.65f
        val rowH = availH / 3f
        val gridLeft = w * 0.26f
        val gridW = (w - pad) - gridLeft
        val cellW = gridW / 8f

        rowLabelPaint.textSize = (rowH * 0.56f).coerceIn(15f, 26f)

        for (r in 0 until 3) {
            val ry = startY + r * rowH
            canvas.drawText(labels[r], pad, ry + rowH * 0.68f, rowLabelPaint)

            for (c in 0 until 8) {
                val bitIdx = r * 8 + c
                val isLit = StreamState.isRunning && StreamState.bitActiveFlags[bitIdx]

                val bx = gridLeft + c * cellW + 4f
                val by = ry + 5f
                val rect = RectF(bx, by, bx + cellW - 8f, ry + rowH - 5f)

                if (isLit) {
                    blockPaint.shader = RadialGradient(
                        rect.centerX(), rect.centerY(), rect.width() * 0.7f,
                        intArrayOf(Color.parseColor("#FFF59D"), Color.parseColor("#FFB300"), Color.parseColor("#E65100")),
                        floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
                    )
                } else {
                    blockPaint.shader = LinearGradient(
                        rect.left, rect.top, rect.left, rect.bottom,
                        Color.parseColor("#3E2F1B"), Color.parseColor("#241B0F"), Shader.TileMode.CLAMP
                    )
                }
                canvas.drawRoundRect(rect, 5f, 5f, blockPaint)
                canvas.drawRoundRect(rect, 5f, 5f, blockFramePaint)
            }
        }
    }
}

// ================================================================================
// 6. HIGH-FREQUENCY ULTRASONIC SPECTRUM (Segmented LED Bar-Graph Console)
// ================================================================================
class UltrasonicLedSpectrumView(context: Context) : View(context) {
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EAEAEA")
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
    private val subHeaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CCCCCC")
        typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    }
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#DCDCDC")
        typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    }
    private val btnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2E3033") }
    private val btnStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#111111")
    }
    private val segPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = w * 0.025f

        titlePaint.textSize = (h * 0.068f).coerceIn(16f, 28f)
        canvas.drawText("HIGH-FREQUENCY ULTRASONIC SPECTRUM (0 kHz - 48 kHz)", pad, h * 0.08f, titlePaint)

        subHeaderPaint.textSize = (h * 0.055f).coerceIn(13f, 22f)
        canvas.drawText("${StreamState.sampleRate}Hz / 24-Bit Pipe", pad, h * 0.15f, subHeaderPaint)
        canvas.drawText("Sub/Bass", w * 0.49f, h * 0.15f, subHeaderPaint)
        canvas.drawText("Calibration", w * 0.83f, h * 0.15f, subHeaderPaint)

        val bands = arrayOf(
            "Sub/Bass (20Hz - 250Hz)" to StreamState.bandBass,
            "Midrange (250Hz - 4kHz)" to StreamState.bandMid,
            "Treble (4kHz - 12kHz)" to StreamState.bandTreble,
            "Air Band (12kHz - 16kHz)" to StreamState.bandAir,
            "Lossless Proof (16kHz - 20kHz)" to StreamState.bandLossless,
            "Hi-Res Ultrasonic (20kHz - 48kHz)" to StreamState.bandUltrasonic
        )

        val topY = h * 0.18f
        val availH = h * 0.79f
        val rowH = availH / 6f

        // Vertical Master Green Ladder Column
        val vColX = w * 0.575f
        val vColW = w * 0.032f
        val vSegs = 24
        val vSegH = availH / vSegs
        val masterLvl = ((StreamState.leftLevel + StreamState.rightLevel) / 2).coerceIn(0, 100)
        val litMasterSegs = (masterLvl * vSegs) / 100

        for (s in 0 until vSegs) {
            val invS = (vSegs - 1) - s
            val sy = topY + s * vSegH + 2f
            val isLit = StreamState.isRunning && (invS < litMasterSegs)
            segPaint.color = when {
                !isLit -> Color.parseColor("#1B281E")
                invS > 19 -> Color.parseColor("#FFB300")
                else -> Color.parseColor("#00E676")
            }
            canvas.drawRoundRect(vColX, sy, vColX + vColW, sy + vSegH - 3f, 2f, 2f, segPaint)
        }

        // 6 Horizontal Frequency Rows
        val barLeft = w * 0.63f
        val barRight = w * 0.855f
        val barW = barRight - barLeft
        val hSegs = 10
        val segW = barW / hSegs

        bandPaint.textSize = (rowH * 0.42f).coerceIn(13f, 22f)

        for (i in bands.indices) {
            val (label, level) = bands[i]
            val ry = topY + i * rowH
            val cy = ry + rowH * 0.5f

            // Band Title on Left
            canvas.drawText(label, pad, cy + bandPaint.textSize * 0.35f, bandPaint)

            // Left Red Activity Lamp + Tactile Button
            HardwareDraw.drawLedLamp(
                canvas, w * 0.525f, cy, (rowH * 0.14f).coerceIn(4f, 8f),
                StreamState.isRunning && level > 65,
                Color.parseColor("#FF1744"), Color.parseColor("#3E1616")
            )
            val lBtn = RectF(w * 0.495f, cy - rowH * 0.25f, w * 0.55f, cy + rowH * 0.25f)
            if (i % 2 == 1) {
                canvas.drawRoundRect(lBtn, 5f, 5f, btnPaint)
                canvas.drawRoundRect(lBtn, 5f, 5f, btnStroke)
            }

            // 10-Segment Horizontal LED Bar (Green -> Yellow -> Orange -> Red)
            val activeSegs = if (StreamState.isRunning) (level * hSegs) / 100 else 0
            for (s in 0 until hSegs) {
                val sx = barLeft + s * segW + 2f
                val sRect = RectF(sx, cy - rowH * 0.22f, sx + segW - 4f, cy + rowH * 0.22f)
                val isLit = s < activeSegs
                segPaint.color = when {
                    !isLit -> when {
                        s < 4 -> Color.parseColor("#193320")
                        s < 7 -> Color.parseColor("#3D3014")
                        else -> Color.parseColor("#3D1616")
                    }
                    s < 4 -> Color.parseColor("#00E676")
                    s < 7 -> Color.parseColor("#FFB300")
                    s < 9 -> Color.parseColor("#FF6D00")
                    else -> Color.parseColor("#FF1744")
                }
                canvas.drawRoundRect(sRect, 3f, 3f, segPaint)
            }

            // Right Calibration Amber Lamp + Tactile Button
            HardwareDraw.drawLedLamp(
                canvas, w * 0.885f, cy, (rowH * 0.15f).coerceIn(5f, 9f),
                StreamState.isRunning && level > 15,
                Color.parseColor("#FFB300"), Color.parseColor("#3E2D12")
            )
            val rBtn = RectF(w * 0.91f, cy - rowH * 0.26f, w * 0.965f, cy + rowH * 0.26f)
            canvas.drawRoundRect(rBtn, 5f, 5f, btnPaint)
            canvas.drawRoundRect(rBtn, 5f, 5f, btnStroke)
        }
    }
}

// ================================================================================
// 7. 3D INDUSTRIAL MECHANICAL ROCKER SWITCH ROW
// ================================================================================
class IndustrialRockerSwitchRow(
    context: Context,
    private val label: String,
    var isChecked: Boolean,
    private val onToggle: (Boolean) -> Unit
) : View(context) {

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E4E4E4")
        typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    }
    private val housingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#141517") }
    private val housingBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#45484C")
    }
    private val rockerPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            isChecked = !isChecked
            onToggle(isChecked)
            invalidate()
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = w * 0.03f

        textPaint.textSize = (h * 0.28f).coerceIn(15f, 26f)
        val lines = label.split("\n")
        if (lines.size == 1) {
            canvas.drawText(lines[0], pad, h * 0.58f, textPaint)
        } else {
            canvas.drawText(lines[0], pad, h * 0.42f, textPaint)
            canvas.drawText(lines[1], pad, h * 0.76f, textPaint)
        }

        // 3D Mechanical Rocker Switch on Right
        val swW = w * 0.17f
        val swH = h * 0.76f
        val swLeft = w - pad - swW
        val swTop = (h - swH) / 2f
        val housing = RectF(swLeft, swTop, swLeft + swW, swTop + swH)
        canvas.drawRoundRect(housing, 8f, 8f, housingPaint)
        canvas.drawRoundRect(housing, 8f, 8f, housingBorder)

        val inset = 6f
        val rocker = RectF(housing.left + inset, housing.top + inset, housing.right - inset, housing.bottom - inset)
        rockerPaint.shader = if (isChecked) {
            LinearGradient(
                rocker.left, rocker.top, rocker.right, rocker.top,
                intArrayOf(Color.parseColor("#1C1D1F"), Color.parseColor("#3E4246"), Color.parseColor("#5A6065")),
                floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP
            )
        } else {
            LinearGradient(
                rocker.left, rocker.top, rocker.right, rocker.top,
                intArrayOf(Color.parseColor("#505559"), Color.parseColor("#2E3134"), Color.parseColor("#18191B")),
                floatArrayOf(0f, 0.3f, 1f), Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(rocker, 5f, 5f, rockerPaint)

        // Status LED dot next to switch
        HardwareDraw.drawLedLamp(
            canvas, swLeft - 24f, h / 2f, 8f,
            isChecked, Color.parseColor("#00E676"), Color.parseColor("#263238")
        )
    }
}

// ================================================================================
// 8. "FILAMENT HOURS" MECHANICAL ODOMETER DRUM IP COUNTER (Tap to Edit IP)
// ================================================================================
class FilamentIpCounterView(context: Context, private val onTapEdit: () -> Unit) : View(context) {
    private val platePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val plateBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#8D8D8D")
    }
    private val recessPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#111214") }
    private val drumPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1B1B1B")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
    private val digitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F5F5F5")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            onTapEdit()
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = w * 0.03f

        val plate = RectF(pad, 6f, w - pad, h - 6f)
        platePaint.shader = LinearGradient(
            0f, plate.top, 0f, plate.bottom,
            intArrayOf(Color.parseColor("#A6A8A4"), Color.parseColor("#7D807C"), Color.parseColor("#5E615E")),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(plate, 14f, 14f, platePaint)
        canvas.drawRoundRect(plate, 14f, 14f, plateBorder)

        val sr = h * 0.09f
        HardwareDraw.drawScrew(canvas, plate.left + sr * 1.8f, h * 0.55f, sr)
        HardwareDraw.drawScrew(canvas, plate.right - sr * 1.8f, h * 0.55f, sr)

        headerPaint.textSize = (h * 0.17f).coerceIn(15f, 25f)
        canvas.drawText("FILAMENT HOURS / ESP32 IP (TAP TO EDIT)", w / 2f, plate.top + h * 0.23f, headerPaint)

        // Recessed Odometer Drum Window
        val winLeft = plate.left + sr * 3.6f
        val winRight = plate.right - sr * 3.6f
        val winTop = plate.top + h * 0.32f
        val winBottom = plate.bottom - h * 0.12f
        val winRect = RectF(winLeft, winTop, winRight, winBottom)
        canvas.drawRoundRect(winRect, 8f, 8f, recessPaint)

        val chars = StreamState.espIp.toCharArray()
        val count = chars.size.coerceAtLeast(1)
        val cellW = winRect.width() / count

        digitPaint.textSize = (winRect.height() * 0.68f).coerceIn(22f, 44f)

        for (i in chars.indices) {
            val dLeft = winLeft + i * cellW + 3f
            val dRect = RectF(dLeft, winTop + 4f, dLeft + cellW - 6f, winBottom - 4f)
            drumPaint.shader = LinearGradient(
                0f, dRect.top, 0f, dRect.bottom,
                intArrayOf(Color.parseColor("#161719"), Color.parseColor("#373A3E"), Color.parseColor("#141517")),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(dRect, 4f, 4f, drumPaint)
            canvas.drawText(
                chars[i].toString(),
                dRect.centerX(),
                dRect.centerY() + digitPaint.textSize * 0.35f,
                digitPaint
            )
        }
    }
}

// ================================================================================
// 9. HEAVY-DUTY BACKLIT GLASS-GLOW START / STOP PUSHBUTTON
// ================================================================================
class IlluminatedStartButtonView(context: Context, private val onClick: () -> Unit) : View(context) {
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#787878")
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFF8E1")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            onClick()
            invalidate()
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = w * 0.05f

        val outer = RectF(pad, 6f, w - pad, h - 6f)
        framePaint.shader = LinearGradient(0f, outer.top, 0f, outer.bottom, Color.parseColor("#6E7175"), Color.parseColor("#2B2D2F"), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(outer, 16f, 16f, framePaint)
        canvas.drawRoundRect(outer, 16f, 16f, borderPaint)

        val inner = RectF(outer.left + 12f, outer.top + 10f, outer.right - 12f, outer.bottom - 10f)
        glassPaint.shader = LinearGradient(0f, inner.top, 0f, inner.bottom, Color.parseColor("#1E2420"), Color.parseColor("#121513"), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(inner, 10f, 10f, glassPaint)

        if (StreamState.isRunning) {
            glowPaint.shader = RadialGradient(
                inner.centerX(), inner.centerY(), inner.width() * 0.55f,
                intArrayOf(Color.parseColor("#99FF1744"), Color.parseColor("#55D50000"), Color.TRANSPARENT),
                floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(inner, 10f, 10f, glowPaint)
        } else {
            // Dual Green (left) + Amber (right) underglow matching mockup!
            glowPaint.shader = RadialGradient(
                inner.left + inner.width() * 0.28f, inner.centerY(), inner.width() * 0.35f,
                intArrayOf(Color.parseColor("#8800E676"), Color.TRANSPARENT),
                floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(inner, 10f, 10f, glowPaint)

            glowPaint.shader = RadialGradient(
                inner.left + inner.width() * 0.68f, inner.centerY(), inner.width() * 0.38f,
                intArrayOf(Color.parseColor("#77FFB300"), Color.TRANSPARENT),
                floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(inner, 10f, 10f, glowPaint)
        }

        textPaint.textSize = (inner.height() * 0.42f).coerceIn(18f, 32f)
        val label = if (StreamState.isRunning) "STOP CAPTURE & STREAM" else "START AUTO-MAX HI-RES CAPTURE"
        canvas.drawText(label, w / 2f, inner.centerY() + textPaint.textSize * 0.35f, textPaint)
    }
}

// ================================================================================
// MAIN ACTIVITY: Assembles the "TRANSMITTER #2" Industrial Console
// ================================================================================
class MainActivity : AppCompatActivity() {

    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var audioManager: AudioManager
    private val uiHandler = Handler(Looper.getMainLooper())

    private lateinit var vuMeterView: DualAnalogVuMeterView
    private lateinit var statusRmsView: StatusAndRmsPanelView
    private lateinit var verdictView: QualityVerdictPlateView
    private lateinit var histogramView: Histogram24BitMatrixView
    private lateinit var spectrumView: UltrasonicLedSpectrumView
    private lateinit var muteSwitchRow: IndustrialRockerSwitchRow
    private lateinit var espSwitchRow: IndustrialRockerSwitchRow
    private lateinit var ipCounterView: FilamentIpCounterView
    private lateinit var startButtonView: IlluminatedStartButtonView

    private val uiUpdater = object : Runnable {
        override fun run() {
            vuMeterView.updateMeters()
            statusRmsView.invalidate()
            verdictView.invalidate()
            histogramView.invalidate()
            spectrumView.invalidate()
            startButtonView.invalidate()
            uiHandler.postDelayed(this, 33) // 30 FPS smooth animation
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        StreamState.nativeMixerRate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48000

        buildTransmitterRackUi()
        requestNeededPermissions()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (StreamState.muteSpeaker && (
                event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
                event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
                event.keyCode == KeyEvent.KEYCODE_VOLUME_MUTE
            )) {
            StreamState.enforceSpeakerMuteLock(audioManager)
            if (event.action == KeyEvent.ACTION_DOWN) {
                Toast.makeText(this, "Speaker Locked Muted", Toast.LENGTH_SHORT).show()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun buildTransmitterRackUi() {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#232528"))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(12), dp(10), dp(20))
        }

        // 1. Top Nameplate
        root.addView(TransmitterHeaderView(this), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(72)).apply {
            bottomMargin = dp(8)
        })

        // 2. White-Face Dual Studio VU Meters
        vuMeterView = DualAnalogVuMeterView(this)
        root.addView(vuMeterView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(215)).apply {
            bottomMargin = dp(10)
        })

        // 3. Side-by-Side STATUS & RMS Levels Cards
        statusRmsView = StatusAndRmsPanelView(this)
        root.addView(statusRmsView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(118)).apply {
            bottomMargin = dp(10)
        })

        // 4. Screw-Mounted Quality Verdict Plate
        verdictView = QualityVerdictPlateView(this)
        root.addView(verdictView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(115)).apply {
            bottomMargin = dp(10)
        })

        // 5. True 24-Bit Histogram (3x8 Glowing Amber Blocks)
        histogramView = Histogram24BitMatrixView(this)
        root.addView(histogramView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(138)).apply {
            bottomMargin = dp(12)
        })

        // 6. 6-Band Ultrasonic LED Spectrum Console
        spectrumView = UltrasonicLedSpectrumView(this)
        root.addView(spectrumView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(255)).apply {
            bottomMargin = dp(10)
        })

        // 7. Industrial Rocker Switches
        muteSwitchRow = IndustrialRockerSwitchRow(
            this,
            "Mute Phone Speaker (Locked Even if Volume Up\nPressed)",
            StreamState.muteSpeaker
        ) { checked ->
            StreamState.muteSpeaker = checked
            if (checked) StreamState.enforceSpeakerMuteLock(audioManager)
            else StreamState.releaseSpeakerMuteLock(audioManager)
        }
        root.addView(muteSwitchRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)).apply {
            bottomMargin = dp(6)
        })

        espSwitchRow = IndustrialRockerSwitchRow(
            this,
            "Stream 24-Bit PCM to ESP32 Wi-Fi (Keep Off for\nLocal Test)",
            StreamState.streamToEsp32
        ) { checked ->
            StreamState.streamToEsp32 = checked
        }
        root.addView(espSwitchRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)).apply {
            bottomMargin = dp(10)
        })

        // 8. "FILAMENT HOURS" Mechanical Odometer IP Counter
        ipCounterView = FilamentIpCounterView(this) {
            showEditIpDialog()
        }
        root.addView(ipCounterView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(96)).apply {
            bottomMargin = dp(12)
        })

        // 9. Illuminated Start / Stop Pushbutton
        startButtonView = IlluminatedStartButtonView(this) {
            if (StreamState.isRunning) {
                stopCaptureService()
            } else {
                startMediaProjectionRequest()
            }
        }
        root.addView(startButtonView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(76)))

        scroll.addView(root)
        setContentView(scroll)
    }

    private fun showEditIpDialog() {
        val input = EditText(this).apply {
            setText(StreamState.espIp)
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("ESP32 Hotspot IP Address (Port 8888)")
            .setView(input)
            .setPositiveButton("SAVE") { _, _ ->
                val v = input.text.toString().trim()
                if (v.isNotEmpty()) {
                    StreamState.espIp = v
                    ipCounterView.invalidate()
                }
            }
            .setNegativeButton("CANCEL", null)
            .show()
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
// BACKGROUND ENGINE: Auto-Recovering Non-Blocking Pipe + Mute Lock + Wi-Fi TCP
// ================================================================================
class AudioCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    private lateinit var audioManager: AudioManager
    @Volatile private var hasActivePlaybackStream = false

    private val volumeLockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (StreamState.muteSpeaker) {
                StreamState.enforceSpeakerMuteLock(audioManager)
            }
        }
    }

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            hasActivePlaybackStream = !configs.isNullOrEmpty()
            if (configs.isNullOrEmpty()) {
                StreamState.detectedSourceRate = "Idle"
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
            StreamState.detectedSourceRate = if (sb.isNotEmpty()) sb.toString().trim() else "${configs.size} Stream(s)"
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
            .setContentTitle("Transmitter #2 Pre-DAC Streamer Active")
            .setContentText("24-Bit PCM / ESP32 Wi-Fi Audio Engine")
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

    private fun buildAndStartAudioRecord(lockedRate: Int): AudioRecord? {
        val proj = mediaProjection ?: return null
        val config = AudioPlaybackCaptureConfiguration.Builder(proj)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val minBuf = AudioRecord.getMinBufferSize(
            lockedRate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_FLOAT
        )
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(lockedRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()

        val rec = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBuf * 2, 8192 * 4))
            .setAudioPlaybackCaptureConfig(config)
            .build()

        return if (rec.state == AudioRecord.STATE_INITIALIZED) {
            rec.startRecording()
            rec
        } else {
            rec.release()
            null
        }
    }

    private fun startAutoMaxHiResCapture(resultCode: Int, dataIntent: Intent) {
        val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = pm.getMediaProjection(resultCode, dataIntent)

        val candidateRates = intArrayOf(48000, 44100)
        var lockedRate = 48000
        for (rate in candidateRates) {
            try {
                val rec = buildAndStartAudioRecord(rate)
                if (rec != null) {
                    audioRecord = rec
                    lockedRate = rate
                    break
                }
            } catch (_: Exception) {}
        }

        if (audioRecord == null) {
            StreamState.statusText = "ERROR"
            return
        }

        if (StreamState.muteSpeaker) {
            StreamState.enforceSpeakerMuteLock(audioManager)
        }

        StreamState.sampleRate = lockedRate
        StreamState.isRunning = true
        StreamState.statusText = "ACTIVE"

        captureThread = thread(start = true, name = "HiResPreDacThread") {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val floatSamples = FloatArray(2048)
            val fftReal = DoubleArray(1024)
            val fftImag = DoubleArray(1024)
            val bitCounts = IntArray(24)

            var socket: Socket? = null
            var outStream: OutputStream? = null
            var zeroReadStreakMs = 0L
            var lastTickMs = SystemClock.elapsedRealtime()
            var lastKeepAliveMs = SystemClock.elapsedRealtime()

            while (StreamState.isRunning) {
                if (StreamState.muteSpeaker) {
                    StreamState.enforceSpeakerMuteLock(audioManager)
                }

                val rawRead = audioRecord?.read(floatSamples, 0, floatSamples.size, AudioRecord.READ_NON_BLOCKING) ?: -1
                val readCount = rawRead - (rawRead % 2)

                val nowMs = SystemClock.elapsedRealtime()
                val dt = nowMs - lastTickMs
                lastTickMs = nowMs

                if (readCount <= 0) {
                    zeroReadStreakMs += dt

                    if (zeroReadStreakMs > 250) {
                        StreamState.leftDb = -50f
                        StreamState.rightDb = -50f
                        StreamState.leftPeak = false
                        StreamState.rightPeak = false
                        StreamState.dbFs = -100.0
                        StreamState.activeBitCount = 0
                        for (b in 0 until 24) StreamState.bitActiveFlags[b] = false
                        StreamState.hexPreview = "00 00 00 00 00 00"
                        StreamState.qualityVerdict = "PAUSED / WAITING FOR MUSIC..."
                        StreamState.bandBass = 0; StreamState.bandMid = 0; StreamState.bandTreble = 0
                        StreamState.bandAir = 0; StreamState.bandLossless = 0; StreamState.bandUltrasonic = 0
                    }

                    if (hasActivePlaybackStream && zeroReadStreakMs > 1000) {
                        try {
                            audioRecord?.stop()
                            audioRecord?.release()
                        } catch (_: Exception) {}
                        audioRecord = buildAndStartAudioRecord(lockedRate)
                        zeroReadStreakMs = 0
                    }

                    if (!StreamState.streamToEsp32 && socket != null) {
                        try { socket.close() } catch (_: Exception) {}
                        socket = null; outStream = null
                        StreamState.isEspConnected = false
                    } else if (StreamState.streamToEsp32 && outStream != null && (nowMs - lastKeepAliveMs > 250)) {
                        try {
                            outStream.write(ByteArray(60))
                            lastKeepAliveMs = nowMs
                        } catch (_: Exception) {
                            try { socket?.close() } catch (_: Exception) {}
                            socket = null; outStream = null
                            StreamState.isEspConnected = false
                        }
                    }

                    Thread.sleep(6)
                    continue
                }

                zeroReadStreakMs = 0

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
                    val fR = floatSamples[i + 1].coerceIn(-1.0f, 1.0f)

                    val absL = abs(fL).toDouble()
                    val absR = abs(fR).toDouble()
                    if (absL > peakL) peakL = absL
                    if (absR > peakR) peakR = absR

                    sumL += (fL * fL)
                    sumR += (fR * fR)

                    val int24L = (fL * 8388607.0f).roundToInt()
                    val int24R = (fR * 8388607.0f).roundToInt()

                    val combinedBits = abs(int24L) or abs(int24R)
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

                var activeBitsTotal = 0
                for (b in 0 until 24) {
                    val active = bitCounts[b] > (frames / 32)
                    StreamState.bitActiveFlags[b] = active
                    if (active) activeBitsTotal++
                }
                StreamState.activeBitCount = activeBitsTotal

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

                if (frameIdx == 1024) {
                    computeFft1024(fftReal, fftImag)
                    val binHz = lockedRate / 1024.0
                    var eBass = 0.0; var eMid = 0.0; var eTreble = 0.0
                    var eAir = 0.0; var eLossless = 0.0; var eUltrasonic = 0.0

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
                        maxRms < 0.0001 -> "SILENCE / PLAY TRACK IN APPLE MUSIC"
                        StreamState.bandLossless > 15 && has24BitDetail -> "TRUE 24-BIT LOSSLESS VERIFIED"
                        has24BitDetail -> "24-BIT PCM HI-RES ACTIVE"
                        else -> "16-BIT STANDARD STREAM"
                    }
                }

                val sbHex = StringBuilder()
                for (b in 0 until minOf(6, pcm24Bytes.size)) {
                    sbHex.append(String.format("%02X ", pcm24Bytes[b]))
                }
                StreamState.hexPreview = sbHex.toString().trim()

                if (StreamState.streamToEsp32) {
                    try {
                        if (socket == null || socket.isClosed || !socket.isConnected) {
                            socket = createWifiBoundSocket()
                            socket.tcpNoDelay = true
                            socket.connect(InetSocketAddress(StreamState.espIp, StreamState.espPort), 2000)
                            outStream = socket.getOutputStream()
                        }
                        outStream?.write(pcm24Bytes)
                        StreamState.isEspConnected = true
                        lastKeepAliveMs = nowMs
                    } catch (_: Exception) {
                        StreamState.isEspConnected = false
                        try { socket?.close() } catch (_: Exception) {}
                        socket = null
                        outStream = null
                    }
                } else {
                    StreamState.isEspConnected = false
                    if (socket != null) {
                        try { socket.close() } catch (_: Exception) {}
                        socket = null
                        outStream = null
                    }
                }
            }
            try { socket?.close() } catch (_: Exception) {}
            StreamState.isEspConnected = false
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
        StreamState.isEspConnected = false
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
        StreamState.activeBitCount = 0
        for (b in 0 until 24) StreamState.bitActiveFlags[b] = false
        StreamState.statusText = "IDLE"
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