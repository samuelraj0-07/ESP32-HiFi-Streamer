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
import android.view.HapticFeedbackConstants
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
import java.util.Random
import kotlin.concurrent.thread
import kotlin.math.*

// ================================================================================
// SHARED LIVE STATE
// ================================================================================
object StreamState {
    @Volatile var isRunning = false
    @Volatile var sampleRate = 48000
    @Volatile var nativeMixerRate = 48000
    @Volatile var activeBitCount = 0
    val bitActiveFlags = BooleanArray(24)
    @Volatile var qualityVerdict = "STBY / NO SIGNAL"
    @Volatile var isLosslessLocked = false

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

    @Volatile var bandBass = 0
    @Volatile var bandMid = 0
    @Volatile var bandTreble = 0
    @Volatile var bandAir = 0
    @Volatile var bandLossless = 0
    @Volatile var bandUltrasonic = 0

    // Interactive Console Controls
    @Volatile var gainDb = 0.0f          // -40.0 dB (MUTE) .. 0.0 dB (UNITY BIT-PERFECT MAX)
    @Volatile var warmAmberLamp = false  // [LAMP]: false = Studio White (Mockup), true = Vintage Amber
    @Volatile var dimEnabled = false     // [DIM]: -15 dB instant pad
    @Volatile var monoMode = false       // [MONO]: L+R mono sum
    @Volatile var peakHoldEnabled = true // [PEAK]: Holds peak bars & VU peak LED

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
// PROCEDURAL METAL TEXTURES (Cast Iron Grit, Brushed Steel, Aged Patina)
// ================================================================================
object MetalTextures {
    lateinit var castIronShader: BitmapShader
    lateinit var brushedSteelShader: BitmapShader
    lateinit var darkSteelShader: BitmapShader
    private var initialized = false

    fun ensureInit() {
        if (initialized) return
        val rng = Random(42L)

        // 1. Dark Cast-Iron Grit (256x256)
        val castBmp = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val castPixels = IntArray(256 * 256)
        for (i in castPixels.indices) {
            val base = 31 + rng.nextInt(14) - 7
            val speck = if (rng.nextInt(30) == 0) -8 else 0
            val v = (base + speck).coerceIn(14, 52)
            castPixels[i] = Color.rgb(v, v + 1, v + 2)
        }
        castBmp.setPixels(castPixels, 0, 256, 0, 0, 256, 256)
        castIronShader = BitmapShader(castBmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)

        // 2. Brushed Aluminum / Steel Grain (256x256)
        val steelBmp = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val steelPixels = IntArray(256 * 256)
        for (y in 0 until 256) {
            val rowStreak = rng.nextInt(16) - 8
            for (x in 0 until 256) {
                val micro = rng.nextInt(8) - 4
                val v = (176 + rowStreak + micro).coerceIn(140, 210)
                steelPixels[y * 256 + x] = Color.rgb(v, v + 1, v - 1)
            }
        }
        steelBmp.setPixels(steelPixels, 0, 256, 0, 0, 256, 256)
        brushedSteelShader = BitmapShader(steelBmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)

        // 3. Weathered Dark Gunmetal Steel (256x256)
        val darkBmp = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val darkPixels = IntArray(256 * 256)
        for (y in 0 until 256) {
            val rowStreak = rng.nextInt(12) - 6
            for (x in 0 until 256) {
                val micro = rng.nextInt(8) - 4
                val rust = if (rng.nextInt(90) == 0) 8 else 0
                val v = (66 + rowStreak + micro).coerceIn(42, 92)
                darkPixels[y * 256 + x] = Color.rgb(v + rust, v + 1, v)
            }
        }
        darkBmp.setPixels(darkPixels, 0, 256, 0, 0, 256, 256)
        darkSteelShader = BitmapShader(darkBmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)

        initialized = true
    }
}

// ================================================================================
// HARDWARE DRAWING PRIMITIVES (Screws, Rivets, Domed LEDs, Beveled Frames)
// ================================================================================
object HardwareDraw {
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    fun drawScrew(c: Canvas, cx: Float, cy: Float, r: Float) {
        pFill.shader = null
        pFill.color = Color.argb(130, 0, 0, 0)
        c.drawCircle(cx + 1.5f, cy + 1.5f, r * 1.05f, pFill)

        pFill.shader = RadialGradient(
            cx - r * 0.25f, cy - r * 0.25f, r * 1.2f,
            Color.parseColor("#B8B8B8"), Color.parseColor("#2A2A2A"), Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, cy, r, pFill)
        pFill.shader = null
        pStroke.color = Color.parseColor("#111111")
        pStroke.strokeWidth = 1.4f
        c.drawCircle(cx, cy, r, pStroke)

        pStroke.color = Color.parseColor("#1D1D1D")
        pStroke.strokeWidth = (r * 0.28f).coerceAtLeast(1.8f)
        c.drawLine(cx - r * 0.55f, cy - r * 0.18f, cx + r * 0.55f, cy + r * 0.18f, pStroke)
        c.drawLine(cx - r * 0.18f, cy + r * 0.55f, cx + r * 0.18f, cy - r * 0.55f, pStroke)
    }

    fun drawRivet(c: Canvas, cx: Float, cy: Float, r: Float) {
        pFill.shader = RadialGradient(
            cx - r * 0.3f, cy - r * 0.3f, r * 1.2f,
            Color.parseColor("#6D7074"), Color.parseColor("#1A1B1D"), Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, cy, r, pFill)
        pFill.shader = null
    }

    fun drawLedLamp(c: Canvas, cx: Float, cy: Float, r: Float, isOn: Boolean, onColor: Int, offColor: Int) {
        pFill.shader = null
        // Metallic outer collar
        pFill.color = Color.parseColor("#141517")
        c.drawCircle(cx, cy, r * 1.32f, pFill)
        pStroke.color = Color.parseColor("#54575A")
        pStroke.strokeWidth = 1.2f
        c.drawCircle(cx, cy, r * 1.25f, pStroke)

        if (isOn) {
            pFill.shader = RadialGradient(
                cx, cy, r * 2.5f,
                intArrayOf(Color.WHITE, onColor, Color.TRANSPARENT),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
            )
            c.drawCircle(cx, cy, r * 2.5f, pFill)
        } else {
            pFill.shader = RadialGradient(
                cx - r * 0.25f, cy - r * 0.25f, r * 1.1f,
                offColor, Color.parseColor("#0D0D0D"), Shader.TileMode.CLAMP
            )
            c.drawCircle(cx, cy, r, pFill)
        }
        pFill.shader = null
    }

    fun drawTexturedSteelPlate(c: Canvas, rect: RectF, radius: Float, shader: Shader) {
        // Outer shadow
        pFill.shader = null
        pFill.color = Color.argb(140, 0, 0, 0)
        c.drawRoundRect(RectF(rect.left, rect.top + 3f, rect.right, rect.bottom + 4f), radius, radius, pFill)

        // Metal texture base
        pFill.shader = shader
        c.drawRoundRect(rect, radius, radius, pFill)

        // Vertical lighting highlight-to-shadow sheen
        pFill.shader = LinearGradient(
            0f, rect.top, 0f, rect.bottom,
            intArrayOf(Color.argb(55, 255, 255, 255), Color.argb(0, 0, 0, 0), Color.argb(70, 0, 0, 0)),
            floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP
        )
        c.drawRoundRect(rect, radius, radius, pFill)
        pFill.shader = null

        // Specular top edge + dark border
        pStroke.color = Color.parseColor("#252729")
        pStroke.strokeWidth = 2.2f
        c.drawRoundRect(rect, radius, radius, pStroke)
        pStroke.color = Color.argb(120, 255, 255, 255)
        pStroke.strokeWidth = 1.4f
        c.drawLine(rect.left + radius, rect.top + 1.5f, rect.right - radius, rect.top + 1.5f, pStroke)
    }
}

// ================================================================================
// OUTER BOLTED CAST-IRON RACK CHASSIS CONTAINER
// ================================================================================
class BoltedRackLayout(context: Context) : LinearLayout(context) {
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val railPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#101112")
        strokeWidth = 3f
    }

    init {
        orientation = VERTICAL
        setWillNotDraw(false)
        MetalTextures.ensureInit()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        bgPaint.shader = MetalTextures.castIronShader
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // Left & Right Cast-Iron Bolted Rack Rails
        val railW = w * 0.032f
        railPaint.shader = LinearGradient(0f, 0f, railW, 0f, Color.parseColor("#3B3D40"), Color.parseColor("#1A1B1D"), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, railW, h, railPaint)

        railPaint.shader = LinearGradient(w - railW, 0f, w, 0f, Color.parseColor("#1A1B1D"), Color.parseColor("#3B3D40"), Shader.TileMode.CLAMP)
        canvas.drawRect(w - railW, 0f, w, h, railPaint)

        canvas.drawLine(railW, 0f, railW, h, linePaint)
        canvas.drawLine(w - railW, 0f, w - railW, h, linePaint)

        // Evenly spaced rivets down both rails
        val rivetStep = h / 14f
        val r = (railW * 0.28f).coerceIn(3f, 6f)
        var y = rivetStep * 0.6f
        while (y < h) {
            HardwareDraw.drawRivet(canvas, railW * 0.5f, y, r)
            HardwareDraw.drawRivet(canvas, w - railW * 0.5f, y, r)
            y += rivetStep
        }
        super.onDraw(canvas)
    }
}

// ================================================================================
// 1. TOP NAMEPLATE ("TRANSMITTER #2 / ESP32 + PCM5102A")
// ================================================================================
class TransmitterHeaderView(context: Context) : View(context) {
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#151515")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#242424")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val rect = RectF(2f, 2f, w - 2f, h - 2f)

        HardwareDraw.drawTexturedSteelPlate(canvas, rect, 7f, MetalTextures.brushedSteelShader)

        val sr = (h * 0.12f).coerceIn(4f, 8f)
        HardwareDraw.drawScrew(canvas, rect.left + sr * 2.1f, rect.top + sr * 2.1f, sr)
        HardwareDraw.drawScrew(canvas, rect.right - sr * 2.1f, rect.top + sr * 2.1f, sr)
        HardwareDraw.drawScrew(canvas, rect.left + sr * 2.1f, rect.bottom - sr * 2.1f, sr)
        HardwareDraw.drawScrew(canvas, rect.right - sr * 2.1f, rect.bottom - sr * 2.1f, sr)

        titlePaint.textSize = (h * 0.37f).coerceIn(17f, 36f)
        canvas.drawText("TRANSMITTER #2 / ESP32 + PCM5102A", w / 2f, h * 0.50f, titlePaint)

        subPaint.textSize = (h * 0.22f).coerceIn(11f, 22f)
        canvas.drawText("24-BIT HI-RES PRE-DAC STREAMER", w / 2f, h * 0.80f, subPaint)
    }
}

// ================================================================================
// 2. DUAL RECESSED STUDIO VU METERS (Matches AI Mockup Proportions + Glass Shadow)
// ================================================================================
class DualAnalogVuMeterView(context: Context) : View(context) {
    private var smoothAngleL = -38f
    private var smoothAngleR = -38f
    private var peakHoldUntilL = 0L
    private var peakHoldUntilR = 0L

    private val bezelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1D1E20") }
    private val bezelBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        color = Color.parseColor("#54575A")
    }
    private val dialPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glassShadow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arcBlackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#202020")
    }
    private val arcRedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#C62828")
    }
    private val textDark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1F1F1F")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
    private val textRed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#C62828")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D6D6D6")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
    private val needleShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(75, 0, 0, 0)
        style = Paint.Style.STROKE
        strokeWidth = 4.5f
        strokeCap = Paint.Cap.ROUND
    }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#181818")
        style = Paint.Style.STROKE
        strokeWidth = 3.2f
        strokeCap = Paint.Cap.ROUND
    }

    private fun dbToAngle(db: Float): Float {
        val vu = (db + 10f).coerceIn(-22f, 3f)
        val norm = when {
            vu <= -20f -> (vu + 22f) / 2f * 0.08f
            vu <= -10f -> 0.08f + (vu + 20f) / 10f * 0.17f
            vu <= -3f  -> 0.25f + (vu + 10f) / 7f * 0.29f
            vu <= 0f   -> 0.54f + (vu + 3f) / 3f * 0.19f
            else       -> 0.73f + (vu / 3f) * 0.27f
        }.coerceIn(0f, 1f)
        return -38f + norm * 76f
    }

    fun updateMeters() {
        val targetL = if (StreamState.isRunning) dbToAngle(StreamState.leftDb) else -38f
        val targetR = if (StreamState.isRunning) dbToAngle(StreamState.rightDb) else -38f

        smoothAngleL += if (targetL > smoothAngleL) (targetL - smoothAngleL) * 0.45f else (targetL - smoothAngleL) * 0.18f
        smoothAngleR += if (targetR > smoothAngleR) (targetR - smoothAngleR) * 0.45f else (targetR - smoothAngleR) * 0.18f

        val now = SystemClock.elapsedRealtime()
        if (StreamState.leftPeak) peakHoldUntilL = now + (if (StreamState.peakHoldEnabled) 600L else 100L)
        if (StreamState.rightPeak) peakHoldUntilR = now + (if (StreamState.peakHoldEnabled) 600L else 100L)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val now = SystemClock.elapsedRealtime()

        val chassis = RectF(2f, 2f, w - 2f, h - 2f)
        HardwareDraw.drawTexturedSteelPlate(canvas, chassis, 12f, MetalTextures.darkSteelShader)

        val pad = w * 0.028f
        val mw = (w - pad * 3f) / 2f
        val topHeader = h * 0.07f
        val bottomFooter = h * 0.16f
        val mh = h - topHeader - bottomFooter

        drawSingleMeter(canvas, pad, topHeader, mw, mh, smoothAngleL, now < peakHoldUntilL, "LEFT")
        drawSingleMeter(canvas, pad * 2f + mw, topHeader, mw, mh, smoothAngleR, now < peakHoldUntilR, "RIGHT")
    }

    private fun drawSingleMeter(
        canvas: Canvas, left: Float, top: Float, mw: Float, mh: Float,
        angleDeg: Float, isPeak: Boolean, label: String
    ) {
        val cx = left + mw / 2f

        val bezel = RectF(left, top, left + mw, top + mh)
        canvas.drawRoundRect(bezel, 8f, 8f, bezelPaint)
        canvas.drawRoundRect(bezel, 8f, 8f, bezelBorder)

        val inset = mw * 0.045f
        val face = RectF(left + inset, top + inset, left + mw - inset, top + mh - inset)

        // [LAMP] Toggle: Mockup Warm-Ivory Studio White vs Amber Bulb
        dialPaint.shader = if (StreamState.warmAmberLamp) {
            RadialGradient(
                cx, face.bottom - face.height() * 0.10f, face.width() * 0.72f,
                intArrayOf(Color.parseColor("#FFF4B8"), Color.parseColor("#FFB300"), Color.parseColor("#D97300")),
                floatArrayOf(0f, 0.58f, 1f), Shader.TileMode.CLAMP
            )
        } else {
            RadialGradient(
                cx, face.centerY(), face.width() * 0.75f,
                intArrayOf(Color.parseColor("#EFECE4"), Color.parseColor("#D4D1C8"), Color.parseColor("#A5A39B")),
                floatArrayOf(0f, 0.65f, 1f), Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(face, 5f, 5f, dialPaint)

        // Recessed top glass drop-shadow (like real recessed analog glass!)
        glassShadow.shader = LinearGradient(
            0f, face.top, 0f, face.top + face.height() * 0.25f,
            Color.argb(70, 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(face, 5f, 5f, glassShadow)

        // Red PEAK LED inside upper-right corner
        val ledX = face.right - face.width() * 0.10f
        val ledY = face.top + face.height() * 0.18f
        HardwareDraw.drawLedLamp(
            canvas, ledX, ledY, (mw * 0.025f).coerceIn(4f, 8f),
            isPeak, Color.parseColor("#FF1744"), Color.parseColor("#5E1717")
        )
        textDark.textSize = (mw * 0.036f).coerceIn(9f, 13f)
        canvas.drawText("PEAK", ledX, ledY + textDark.textSize * 1.45f, textDark)

        val pivotX = cx
        val pivotY = face.bottom + face.height() * 0.34f
        val arcR = face.height() * 0.90f

        canvas.save()
        canvas.clipRect(face)

        val arcRect = RectF(pivotX - arcR, pivotY - arcR, pivotX + arcR, pivotY + arcR)
        arcBlackPaint.strokeWidth = 2.4f
        arcRedPaint.strokeWidth = 3.8f
        canvas.drawArc(arcRect, -128f, 56f, false, arcBlackPaint)
        canvas.drawArc(arcRect, -72f, 20f, false, arcRedPaint)

        data class Tick(val norm: Float, val txt: String, val isRed: Boolean)
        val ticks = listOf(
            Tick(0.06f, "-20", false),
            Tick(0.24f, "-10", false),
            Tick(0.35f, "-7", false),
            Tick(0.45f, "-5", false),
            Tick(0.55f, "-3", false),
            Tick(0.66f, "-1", false),
            Tick(0.74f, "0", true),
            Tick(0.83f, "+1", true),
            Tick(0.92f, "+2", true),
            Tick(0.99f, "+3", true)
        )

        textDark.textSize = (mw * 0.048f).coerceIn(10f, 17f)
        textRed.textSize = textDark.textSize

        for (t in ticks) {
            val deg = -38f + t.norm * 76f
            val rad = Math.toRadians((deg - 90f).toDouble())
            val cosA = cos(rad).toFloat()
            val sinA = sin(rad).toFloat()

            val rIn = arcR
            val rOut = arcR * 1.11f
            val pLine = if (t.isRed) arcRedPaint else arcBlackPaint
            pLine.strokeWidth = if (t.txt == "0") 3.2f else 2.0f
            canvas.drawLine(pivotX + rIn * cosA, pivotY + rIn * sinA, pivotX + rOut * cosA, pivotY + rOut * sinA, pLine)

            val pTxt = if (t.isRed) textRed else textDark
            val rTxt = arcR * 1.24f
            canvas.drawText(t.txt, pivotX + rTxt * cosA, pivotY + rTxt * sinA + pTxt.textSize * 0.35f, pTxt)
        }

        textDark.textSize = (mw * 0.088f).coerceIn(16f, 28f)
        canvas.drawText("VU", cx, face.bottom - face.height() * 0.14f, textDark)

        val nRad = Math.toRadians((angleDeg - 90f).toDouble())
        val nLen = arcR * 1.16f
        val tipX = pivotX + nLen * cos(nRad).toFloat()
        val tipY = pivotY + nLen * sin(nRad).toFloat()

        canvas.drawLine(pivotX + 3.5f, pivotY + 3.5f, tipX + 3.5f, tipY + 3.5f, needleShadow)
        canvas.drawLine(pivotX, pivotY, tipX, tipY, needlePaint)
        canvas.restore()

        labelPaint.textSize = (mw * 0.068f).coerceIn(12f, 22f)
        canvas.drawText(label, cx, top + mh + (height - (top + mh)) * 0.72f, labelPaint)
    }
}

// ================================================================================
// 3. LINK STATUS & RMS / CREST (Beveled Metal Frames + Dog-Eared Paper Tags)
// ================================================================================
class StatusAndRmsPanelView(context: Context) : View(context) {
    private var animTick = 0
    private val paperPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paperShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(95, 0, 0, 0) }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E6E6E6")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
    private val paperTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#181818")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
    private val paperPath = Path()

    fun stepAnimation() {
        animTick = (animTick + 1) % 16
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val gap = w * 0.02f
        val cw = (w - gap) / 2f

        val linkStr = when {
            !StreamState.isRunning -> "STBY: IDLE"
            StreamState.isEspConnected -> "TX: ACTIVE"
            StreamState.streamToEsp32 -> "WIFI: WAIT"
            else -> "LOCAL: ${StreamState.sampleRate / 1000}K"
        }
        drawCard(canvas, 2f, 2f, cw - 2f, h - 4f, "LINK STATUS", linkStr, isLinkCard = true)

        val rmsStr = if (!StreamState.isRunning || StreamState.dbFs <= -99.0) {
            "-100.0 dBFS"
        } else {
            "${String.format("%.1f", StreamState.dbFs)} dBFS"
        }
        drawCard(canvas, cw + gap, 2f, cw - 2f, h - 4f, "RMS / CREST", rmsStr, isLinkCard = false)
    }

    private fun drawCard(
        canvas: Canvas, x: Float, y: Float, cw: Float, ch: Float,
        title: String, paperTxt: String, isLinkCard: Boolean
    ) {
        val rect = RectF(x, y, x + cw, y + ch)
        HardwareDraw.drawTexturedSteelPlate(canvas, rect, 14f, MetalTextures.darkSteelShader)

        val pad = cw * 0.05f
        val leftZoneW = cw * 0.56f
        titlePaint.textSize = (ch * 0.19f).coerceIn(12f, 22f)
        canvas.drawText(title, x + leftZoneW / 2f + pad * 0.3f, y + ch * 0.30f, titlePaint)

        // Vintage Paper Tag with Dog-Eared Bottom-Right Corner + Rivet
        val pL = x + pad
        val pT = y + ch * 0.38f
        val pR = x + leftZoneW
        val pB = y + ch * 0.85f
        val dogEar = (pB - pT) * 0.25f

        paperPath.reset()
        paperPath.moveTo(pL, pT)
        paperPath.lineTo(pR, pT)
        paperPath.lineTo(pR, pB - dogEar)
        paperPath.lineTo(pR - dogEar, pB)
        paperPath.lineTo(pL, pB)
        paperPath.close()

        canvas.save()
        canvas.translate(2f, 2f)
        canvas.drawPath(paperPath, paperShadow)
        canvas.restore()

        paperPaint.shader = LinearGradient(pL, pT, pR, pB, Color.parseColor("#ECE1C6"), Color.parseColor("#C4B391"), Shader.TileMode.CLAMP)
        canvas.drawPath(paperPath, paperPaint)

        HardwareDraw.drawScrew(canvas, pL + 10f, (pT + pB) / 2f, 4f)

        paperTextPaint.textSize = (ch * 0.19f).coerceIn(11f, 20f)
        canvas.drawText(paperTxt, (pL + pR) / 2f + 5f, (pT + pB) / 2f + paperTextPaint.textSize * 0.35f, paperTextPaint)

        // 4x4 LED Grid on Right
        val gridLeft = pR + cw * 0.04f
        val gridW = (x + cw - pad) - gridLeft
        val gridTop = y + ch * 0.14f
        val gridH = ch * 0.72f
        val ledR = (minOf(gridW / 4f, gridH / 4f) * 0.31f).coerceIn(3.5f, 7f)

        val rmsNorm = ((StreamState.dbFs + 55.0) / 55.0).coerceIn(0.0, 1.0)
        val crestNorm = (StreamState.crestFactorDb / 20.0).coerceIn(0.0, 1.0)

        for (r in 0 until 4) {
            for (c in 0 until 4) {
                val lx = gridLeft + (c + 0.5f) * (gridW / 4f)
                val ly = gridTop + (r + 0.5f) * (gridH / 4f)
                val invRow = 3 - r

                val isOn: Boolean
                if (isLinkCard) {
                    val idx = r * 4 + c
                    isOn = when {
                        StreamState.isEspConnected && StreamState.dbFs > -90 -> ((idx + animTick / 2) % 3 == 0) || (c < 2)
                        StreamState.isRunning -> (c == 0 && r >= 2)
                        else -> (c == 0 && r == 3)
                    }
                } else {
                    val colThreshold = if (c < 2) rmsNorm * 4.0 else crestNorm * 4.0
                    isOn = StreamState.isRunning && (invRow < colThreshold)
                }
                val onCol = if (c < 2) Color.parseColor("#00E676") else Color.parseColor("#FFB300")
                val offCol = if (c < 2) Color.parseColor("#183822") else Color.parseColor("#473612")
                HardwareDraw.drawLedLamp(canvas, lx, ly, ledR, isOn, onCol, offCol)
            }
        }
    }
}

// ================================================================================
// 4. SIGNAL LOCK + 3D RECESSED KNURLED THUMBWHEEL GAIN DECK
// ================================================================================
class SignalLockAndThumbwheelDeckView(
    context: Context,
    private val onGainChanged: () -> Unit
) : View(context) {

    private var lastTouchX = 0f
    private var lastHapticDb = 0f

    private val slotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#CFC7B5") }
    private val slotBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#545049")
    }
    private val wheelHousingPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wheelCavityPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#070809") }
    private val wheelBasePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ridgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val digitalBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0E1713") }
    private val digitalText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val darkBold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#181818")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
    private val seamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#484A47")
        strokeWidth = 3f
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val inWheelZone = event.x > width * 0.45f
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (!inWheelZone) return false
                parent.requestDisallowInterceptTouchEvent(true)
                lastTouchX = event.x
                lastHapticDb = StreamState.gainDb
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                parent.requestDisallowInterceptTouchEvent(true)
                val dx = event.x - lastTouchX
                lastTouchX = event.x

                // Slide Right -> Louder up to 0.0 dB UNITY; Slide Left -> Quieter down to -40 dB
                var nextDb = (StreamState.gainDb + dx * 0.16f).coerceIn(-40.0f, 0.0f)
                if (nextDb > -0.6f && dx > 0) nextDb = 0.0f

                StreamState.gainDb = nextDb
                if (abs(nextDb - lastHapticDb) >= 2.5f || (nextDb == 0.0f && lastHapticDb != 0.0f)) {
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    lastHapticDb = nextDb
                }
                onGainChanged()
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent.requestDisallowInterceptTouchEvent(false)
                if (StreamState.gainDb > -0.8f) {
                    StreamState.gainDb = 0.0f
                    performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    onGainChanged()
                    invalidate()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val deck = RectF(2f, 2f, w - 2f, h - 2f)

        HardwareDraw.drawTexturedSteelPlate(canvas, deck, 10f, MetalTextures.brushedSteelShader)

        val midX = w * 0.45f
        canvas.drawLine(midX, deck.top + 6f, midX, deck.bottom - 6f, seamPaint)

        val sr = (h * 0.068f).coerceIn(4f, 7f)
        HardwareDraw.drawScrew(canvas, deck.left + sr * 2f, deck.top + sr * 2f, sr)
        HardwareDraw.drawScrew(canvas, midX - sr * 2f, deck.top + sr * 2f, sr)
        HardwareDraw.drawScrew(canvas, deck.left + sr * 2f, deck.bottom - sr * 2f, sr)
        HardwareDraw.drawScrew(canvas, midX - sr * 2f, deck.bottom - sr * 2f, sr)

        HardwareDraw.drawScrew(canvas, midX + sr * 2f, deck.top + sr * 2f, sr)
        HardwareDraw.drawScrew(canvas, deck.right - sr * 2f, deck.top + sr * 2f, sr)
        HardwareDraw.drawScrew(canvas, midX + sr * 2f, deck.bottom - sr * 2f, sr)
        HardwareDraw.drawScrew(canvas, deck.right - sr * 2f, deck.bottom - sr * 2f, sr)

        // ---------------- LEFT HALF: SIGNAL LOCK ----------------
        val lCx = midX / 2f
        darkBold.textAlign = Paint.Align.CENTER
        darkBold.textSize = (h * 0.17f).coerceIn(12f, 22f)
        canvas.drawText("SIGNAL LOCK", lCx, h * 0.26f, darkBold)

        val ledR = (h * 0.065f).coerceIn(4f, 8f)
        val ledY = h * 0.46f
        darkBold.textSize = (h * 0.115f).coerceIn(9f, 16f)

        HardwareDraw.drawLedLamp(canvas, w * 0.065f, ledY, ledR, StreamState.isRunning, Color.parseColor("#00E676"), Color.parseColor("#8E1C1C"))
        canvas.drawText("OFF/ON", w * 0.145f, ledY + darkBold.textSize * 0.35f, darkBold)

        val isLocked = StreamState.isRunning && StreamState.isLosslessLocked
        HardwareDraw.drawLedLamp(canvas, w * 0.245f, ledY, ledR, isLocked, Color.parseColor("#FFB300"), Color.parseColor("#4E3810"))
        canvas.drawText("STBY/LOCK", w * 0.345f, ledY + darkBold.textSize * 0.35f, darkBold)

        val slot = RectF(deck.left + w * 0.035f, h * 0.61f, midX - w * 0.035f, h * 0.87f)
        canvas.drawRoundRect(slot, 5f, 5f, slotPaint)
        canvas.drawRoundRect(slot, 5f, 5f, slotBorder)

        darkBold.textSize = (slot.height() * 0.48f).coerceIn(11f, 19f)
        canvas.drawText(StreamState.qualityVerdict, slot.centerX(), slot.centerY() + darkBold.textSize * 0.35f, darkBold)

        // ---------------- RIGHT HALF: 3D KNURLED THUMBWHEEL ----------------
        darkBold.textAlign = Paint.Align.CENTER
        darkBold.textSize = (h * 0.14f).coerceIn(11f, 18f)
        canvas.drawText("OUTPUT GAIN", midX + (w - midX) * 0.35f, h * 0.23f, darkBold)

        // Thumbwheel Housing (Recessed Dark Steel Frame matching user's thumbwheel reference!)
        val wL = midX + w * 0.035f
        val wR = w * 0.79f
        val wT = h * 0.33f
        val wB = h * 0.72f
        val housingRect = RectF(wL, wT, wR, wB)

        wheelHousingPaint.shader = LinearGradient(0f, wT, 0f, wB, Color.parseColor("#546E7A"), Color.parseColor("#263238"), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(housingRect, 6f, 6f, wheelHousingPaint)

        val cavInsetX = housingRect.width() * 0.08f
        val cavInsetY = housingRect.height() * 0.14f
        val cavRect = RectF(wL + cavInsetX, wT + cavInsetY, wR - cavInsetX, wB - cavInsetY)
        canvas.drawRoundRect(cavRect, 3f, 3f, wheelCavityPaint)

        // Draw 3D Cylindrical Knurled Thumbwheel Inside Cavity
        val wheelRect = RectF(cavRect.left + 4f, cavRect.top + 3f, cavRect.right - 4f, cavRect.bottom - 3f)
        canvas.save()
        canvas.clipRect(wheelRect)

        wheelBasePaint.shader = LinearGradient(
            wheelRect.left, 0f, wheelRect.right, 0f,
            intArrayOf(Color.parseColor("#0D0F11"), Color.parseColor("#373B40"), Color.parseColor("#4D5258"), Color.parseColor("#25282B"), Color.parseColor("#0C0D0F")),
            floatArrayOf(0f, 0.28f, 0.50f, 0.75f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawRect(wheelRect, wheelBasePaint)

        // Rolling cylindrical perspective knurled ridges!
        val phase = ((StreamState.gainDb + 40f) / 40f) * 3.2f
        val ridgeCount = 26
        for (i in 0..ridgeCount) {
            // Shift ridges smoothly across [-PI/2 .. +PI/2]
            val t = ((i.toFloat() / ridgeCount) + (phase % (1f / ridgeCount)))
            val theta = (t * Math.PI) - Math.PI / 2.0
            if (theta < -Math.PI / 2.0 || theta > Math.PI / 2.0) continue

            val xNorm = sin(theta).toFloat() // Perspective compression on left/right edges!
            val rx = wheelRect.centerX() + xNorm * (wheelRect.width() * 0.5f)
            val shade = cos(theta).toFloat().coerceIn(0.1f, 1f)

            ridgePaint.color = Color.argb((180 * shade).toInt(), 210, 220, 230)
            ridgePaint.strokeWidth = (2.6f * shade).coerceAtLeast(1f)
            canvas.drawLine(rx, wheelRect.top, rx, wheelRect.bottom, ridgePaint)

            ridgePaint.color = Color.argb((210 * shade).toInt(), 8, 9, 10)
            canvas.drawLine(rx + 2f * shade, wheelRect.top, rx + 2f * shade, wheelRect.bottom, ridgePaint)
        }

        // Top Specular Lip & Bottom Drop Shadow on Wheel
        wheelBasePaint.shader = LinearGradient(
            0f, wheelRect.top, 0f, wheelRect.bottom,
            intArrayOf(Color.argb(110, 255, 255, 255), Color.TRANSPARENT, Color.argb(170, 0, 0, 0)),
            floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawRect(wheelRect, wheelBasePaint)
        canvas.restore()

        // Scale bar below wheel (-40 .. -20 .. -12 .. -6 .. 0dB)
        darkBold.textSize = (h * 0.10f).coerceIn(8.5f, 14f)
        val scaleLabels = arrayOf("-40", "-20", "-12", "-6", "0dB")
        for (s in scaleLabels.indices) {
            val sx = wheelRect.left + (s / 4f) * wheelRect.width()
            canvas.drawText(scaleLabels[s], sx, h * 0.87f, darkBold)
        }

        // UNITY Green LED + Digital dB Window on Far Right
        val rColX = w * 0.885f
        darkBold.textSize = (h * 0.115f).coerceIn(9f, 16f)
        canvas.drawText("UNITY", rColX, h * 0.25f, darkBold)

        val isUnity = (StreamState.gainDb == 0.0f) && !StreamState.dimEnabled
        HardwareDraw.drawLedLamp(canvas, rColX, h * 0.43f, ledR * 1.15f, isUnity, Color.parseColor("#00E676"), Color.parseColor("#183A22"))

        val digBox = RectF(w * 0.805f, h * 0.61f, w * 0.96f, h * 0.86f)
        canvas.drawRoundRect(digBox, 4f, 4f, digitalBg)

        val effDb = when {
            StreamState.gainDb <= -39.5f -> "MUTE"
            StreamState.dimEnabled -> "${String.format("%.0f", StreamState.gainDb - 15f)}dB"
            else -> "${String.format("%.1f", StreamState.gainDb)}dB"
        }
        digitalText.textSize = (digBox.height() * 0.50f).coerceIn(10f, 18f)
        digitalText.color = if (isUnity) Color.parseColor("#76FF03") else Color.parseColor("#FFB300")
        canvas.drawText(effDb, digBox.centerX(), digBox.centerY() + digitalText.textSize * 0.35f, digitalText)
    }
}

// ================================================================================
// 5. 24-BIT WORD MATRIX (3x8 Warm Amber Neon Blocks with Outer Glow Halo)
// ================================================================================
class Histogram24BitMatrixView(context: Context) : View(context) {
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E8E8E8")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
    private val rowLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#DCDCDC")
        textAlign = Paint.Align.RIGHT
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
    private val rulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6D7074")
        strokeWidth = 2f
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blockPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blockFrame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#111111")
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val outer = RectF(2f, 2f, w - 2f, h - 2f)

        HardwareDraw.drawTexturedSteelPlate(canvas, outer, 10f, MetalTextures.darkSteelShader)

        val sr = (h * 0.055f).coerceIn(3.5f, 6f)
        HardwareDraw.drawScrew(canvas, outer.left + sr * 2.2f, outer.top + sr * 2.2f, sr)
        HardwareDraw.drawScrew(canvas, outer.right - sr * 2.2f, outer.top + sr * 2.2f, sr)
        HardwareDraw.drawScrew(canvas, outer.left + sr * 2.2f, outer.bottom - sr * 2.2f, sr)
        HardwareDraw.drawScrew(canvas, outer.right - sr * 2.2f, outer.bottom - sr * 2.2f, sr)

        // Centered Decorative Header Line: — 24-BIT WORD MATRIX [XX XX XX] —
        titlePaint.textSize = (h * 0.145f).coerceIn(11f, 20f)
        val headerTxt = "24-BIT WORD MATRIX  [ ${StreamState.hexPreview} ]"
        canvas.drawText(headerTxt, w / 2f, h * 0.21f, titlePaint)
        val txtW = titlePaint.measureText(headerTxt)
        val ruleY = h * 0.16f
        canvas.drawLine(w * 0.06f, ruleY, (w - txtW) / 2f - 10f, ruleY, rulePaint)
        canvas.drawLine((w + txtW) / 2f + 10f, ruleY, w * 0.94f, ruleY, rulePaint)

        val labels = arrayOf("01-08:", "09-16:", "17-24:")
        val startY = h * 0.27f
        val rowH = (h * 0.66f) / 3f
        val gridLeft = w * 0.17f
        val gridRight = w * 0.94f
        val cellW = (gridRight - gridLeft) / 8f

        rowLabelPaint.textSize = (rowH * 0.56f).coerceIn(11f, 19f)

        for (r in 0 until 3) {
            val ry = startY + r * rowH
            canvas.drawText(labels[r], gridLeft - 10f, ry + rowH * 0.68f, rowLabelPaint)

            for (c in 0 until 8) {
                val bitIdx = r * 8 + c
                val isLit = StreamState.isRunning && StreamState.bitActiveFlags[bitIdx]

                val bx = gridLeft + c * cellW + 3f
                val rect = RectF(bx, ry + 3.5f, bx + cellW - 6f, ry + rowH - 3.5f)

                if (isLit) {
                    // Warm Neon Amber Outer Halo
                    glowPaint.shader = RadialGradient(
                        rect.centerX(), rect.centerY(), rect.width() * 0.85f,
                        intArrayOf(Color.argb(120, 255, 179, 0), Color.TRANSPARENT),
                        floatArrayOf(0.3f, 1f), Shader.TileMode.CLAMP
                    )
                    canvas.drawRect(rect.left - 5f, rect.top - 4f, rect.right + 5f, rect.bottom + 4f, glowPaint)

                    blockPaint.shader = RadialGradient(
                        rect.centerX(), rect.centerY(), rect.width() * 0.72f,
                        intArrayOf(Color.parseColor("#FFF9C4"), Color.parseColor("#FFB300"), Color.parseColor("#E65100")),
                        floatArrayOf(0f, 0.52f, 1f), Shader.TileMode.CLAMP
                    )
                } else {
                    blockPaint.shader = LinearGradient(
                        rect.left, rect.top, rect.left, rect.bottom,
                        Color.parseColor("#382A18"), Color.parseColor("#1D150B"), Shader.TileMode.CLAMP
                    )
                }
                canvas.drawRoundRect(rect, 4f, 4f, blockPaint)
                canvas.drawRoundRect(rect, 4f, 4f, blockFrame)
            }
        }
    }
}

// ================================================================================
// 6. AF / ULTRASONIC SPECTRUM + 4 ILLUMINATED WORKING CONSOLE BUTTONS
// ================================================================================
class UltrasonicLedSpectrumView(
    context: Context,
    private val onConsoleButtonTap: (Int) -> Unit
) : View(context) {

    private val btnHitRects = Array(4) { RectF() }
    private val peakHoldBars = IntArray(6)
    private var lastPeakDecayMs = 0L

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EAEAEA")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
    private val rulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6D7074")
        strokeWidth = 2f
    }
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#DCDCDC")
        textAlign = Paint.Align.RIGHT
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
    private val btnLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EAEAEA")
        textAlign = Paint.Align.LEFT
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
    private val btnPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val btnStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#111111")
    }
    private val segPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            for (i in 0 until 4) {
                if (btnHitRects[i].contains(event.x, event.y)) {
                    performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    onConsoleButtonTap(i)
                    invalidate()
                    return true
                }
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val outer = RectF(2f, 2f, w - 2f, h - 2f)

        HardwareDraw.drawTexturedSteelPlate(canvas, outer, 10f, MetalTextures.darkSteelShader)

        val sr = (h * 0.04f).coerceIn(3.5f, 6f)
        HardwareDraw.drawScrew(canvas, outer.left + sr * 2.2f, outer.top + sr * 2.2f, sr)
        HardwareDraw.drawScrew(canvas, outer.right - sr * 2.2f, outer.top + sr * 2.2f, sr)
        HardwareDraw.drawScrew(canvas, outer.left + sr * 2.2f, outer.bottom - sr * 2.2f, sr)
        HardwareDraw.drawScrew(canvas, outer.right - sr * 2.2f, outer.bottom - sr * 2.2f, sr)

        titlePaint.textSize = (h * 0.088f).coerceIn(11f, 20f)
        val headerTxt = "AF / ULTRASONIC SPECTRUM (0-48 kHz)"
        canvas.drawText(headerTxt, w / 2f, h * 0.14f, titlePaint)
        val txtW = titlePaint.measureText(headerTxt)
        val ruleY = h * 0.105f
        canvas.drawLine(w * 0.06f, ruleY, (w - txtW) / 2f - 8f, ruleY, rulePaint)
        canvas.drawLine((w + txtW) / 2f + 8f, ruleY, w * 0.94f, ruleY, rulePaint)

        val bands = arrayOf(
            "SUB" to StreamState.bandBass,
            "MID" to StreamState.bandMid,
            "TREBLE" to StreamState.bandTreble,
            "AIR" to StreamState.bandAir,
            "LOSSLESS" to StreamState.bandLossless,
            "ULTRA" to StreamState.bandUltrasonic
        )

        val now = SystemClock.elapsedRealtime()
        val decayNow = (now - lastPeakDecayMs) > 120L
        if (decayNow) lastPeakDecayMs = now

        val topY = h * 0.20f
        val availH = h * 0.74f
        val rowH = availH / 6f

        // Vertical Master Sum Green Bar
        val vColX = w * 0.255f
        val vColW = w * 0.032f
        val vSegs = 18
        val vSegH = availH / vSegs
        val masterLvl = ((StreamState.leftLevel + StreamState.rightLevel) / 2).coerceIn(0, 100)
        val litMaster = if (StreamState.isRunning) (masterLvl * vSegs) / 100 else 0

        for (s in 0 until vSegs) {
            val invS = (vSegs - 1) - s
            val sy = topY + s * vSegH + 1.5f
            val isLit = invS < litMaster
            segPaint.color = when {
                !isLit -> Color.parseColor("#18281C")
                invS > 14 -> Color.parseColor("#FFB300")
                else -> Color.parseColor("#00E676")
            }
            canvas.drawRoundRect(vColX, sy, vColX + vColW, sy + vSegH - 3f, 2f, 2f, segPaint)
        }

        val barLeft = w * 0.305f
        val barRight = w * 0.72f
        val hSegs = 14
        val segW = (barRight - barLeft) / hSegs

        bandPaint.textSize = (rowH * 0.54f).coerceIn(10f, 18f)

        for (i in bands.indices) {
            val (label, level) = bands[i]
            val ry = topY + i * rowH
            val cy = ry + rowH * 0.5f

            canvas.drawText(label, w * 0.195f, cy + bandPaint.textSize * 0.35f, bandPaint)

            HardwareDraw.drawLedLamp(
                canvas, w * 0.225f, cy, (rowH * 0.18f).coerceIn(3.5f, 6.5f),
                StreamState.isRunning && level > 18,
                Color.parseColor("#00E676"), Color.parseColor("#2A2018")
            )

            val activeSegs = if (StreamState.isRunning) (level * hSegs) / 100 else 0
            if (StreamState.peakHoldEnabled && StreamState.isRunning) {
                if (activeSegs >= peakHoldBars[i]) peakHoldBars[i] = activeSegs
                else if (decayNow && peakHoldBars[i] > 0) peakHoldBars[i]--
            } else {
                peakHoldBars[i] = 0
            }

            for (s in 0 until hSegs) {
                val sx = barLeft + s * segW + 2f
                val sRect = RectF(sx, cy - rowH * 0.28f, sx + segW - 4f, cy + rowH * 0.28f)
                val isLit = (s < activeSegs) || (StreamState.peakHoldEnabled && StreamState.isRunning && s == peakHoldBars[i] && peakHoldBars[i] > 0)

                segPaint.color = when {
                    !isLit -> when {
                        s < 7 -> Color.parseColor("#18301E")
                        s < 11 -> Color.parseColor("#382C14")
                        else -> Color.parseColor("#381515")
                    }
                    s < 7 -> Color.parseColor("#00E676")
                    s < 11 -> Color.parseColor("#FFB300")
                    else -> Color.parseColor("#FF1744")
                }
                canvas.drawRoundRect(sRect, 2.5f, 2.5f, segPaint)
            }
        }

        // 4 Illuminated Hardware Buttons on Right ([LAMP], [DIM], [MONO], [PEAK])
        val btnNames = arrayOf("[LAMP]", "[DIM]", "[MONO]", "[PEAK]")
        val btnStates = booleanArrayOf(
            StreamState.warmAmberLamp,
            StreamState.dimEnabled,
            StreamState.monoMode,
            StreamState.peakHoldEnabled
        )

        val btnRowH = availH / 4f
        val bxLeft = w * 0.745f
        val bxSize = (btnRowH * 0.68f).coerceIn(18f, 32f)
        btnLabelPaint.textSize = (btnRowH * 0.42f).coerceIn(10f, 18f)

        for (b in 0 until 4) {
            val by = topY + b * btnRowH + btnRowH * 0.5f
            val bRect = RectF(bxLeft, by - bxSize / 2f, bxLeft + bxSize, by + bxSize / 2f)
            btnHitRects[b].set(bxLeft - 10f, by - btnRowH / 2f, w - 4f, by + btnRowH / 2f)

            if (btnStates[b]) {
                btnPaint.shader = RadialGradient(
                    bRect.centerX(), bRect.centerY(), bxSize * 0.75f,
                    intArrayOf(Color.parseColor("#FFF59D"), Color.parseColor("#FFB300"), Color.parseColor("#E65100")),
                    floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
                )
            } else {
                btnPaint.shader = LinearGradient(
                    bRect.left, bRect.top, bRect.left, bRect.bottom,
                    Color.parseColor("#44474B"), Color.parseColor("#1D1F21"), Shader.TileMode.CLAMP
                )
            }
            canvas.drawRoundRect(bRect, 4f, 4f, btnPaint)
            canvas.drawRoundRect(bRect, 4f, 4f, btnStroke)
            canvas.drawText(btnNames[b], bRect.right + w * 0.018f, by + btnLabelPaint.textSize * 0.35f, btnLabelPaint)
        }
    }
}

// ================================================================================
// 7. STEPPED 3D MECHANICAL ROCKER SWITCH (Matches Mockup Stepped Trapezoid Face)
// ================================================================================
class CompactRockerSwitchView(
    context: Context,
    private val line1: String,
    private val line2: String,
    private val line3: String,
    var isChecked: Boolean,
    private val onToggle: (Boolean) -> Unit
) : View(context) {

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E0E0E0")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
    private val housingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#101113") }
    private val housingBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#54575A")
    }
    private val planePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            isChecked = !isChecked
            performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            onToggle(isChecked)
            invalidate()
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        val txtCx = w * 0.25f
        labelPaint.textSize = (h * 0.23f).coerceIn(9f, 16f)
        canvas.drawText(line1, txtCx, h * 0.31f, labelPaint)
        canvas.drawText(line2, txtCx, h * 0.56f, labelPaint)
        canvas.drawText(line3, txtCx, h * 0.81f, labelPaint)

        val swLeft = w * 0.48f
        val swRight = w * 0.78f
        val swTop = h * 0.10f
        val swBottom = h * 0.90f
        val housing = RectF(swLeft, swTop, swRight, swBottom)
        canvas.drawRoundRect(housing, 6f, 6f, housingPaint)
        canvas.drawRoundRect(housing, 6f, 6f, housingBorder)

        // 3D Stepped Rocker Planes (Top bevel + Center ridge + Bottom bevel)
        val rL = swLeft + 4f
        val rR = swRight - 4f
        val rT = swTop + 4f
        val rB = swBottom - 4f
        val ridgeY = if (isChecked) rT + (rB - rT) * 0.35f else rT + (rB - rT) * 0.65f

        planePaint.shader = LinearGradient(0f, rT, 0f, ridgeY, Color.parseColor("#4D5156"), Color.parseColor("#2E3134"), Shader.TileMode.CLAMP)
        canvas.drawRect(rL, rT, rR, ridgeY, planePaint)

        planePaint.shader = LinearGradient(0f, ridgeY, 0f, rB, Color.parseColor("#1E2022"), Color.parseColor("#111214"), Shader.TileMode.CLAMP)
        canvas.drawRect(rL, ridgeY, rR, rB, planePaint)

        planePaint.shader = null
        planePaint.color = Color.argb(90, 255, 255, 255)
        planePaint.strokeWidth = 1.5f
        canvas.drawLine(rL, ridgeY, rR, ridgeY, planePaint)

        val ledX = w * 0.89f
        val ledR = (h * 0.085f).coerceIn(3.5f, 7f)
        HardwareDraw.drawLedLamp(canvas, ledX, h * 0.28f, ledR, isChecked, Color.parseColor("#00E676"), Color.parseColor("#173620"))
        HardwareDraw.drawLedLamp(canvas, ledX, h * 0.72f, ledR, !isChecked, Color.parseColor("#FFB300"), Color.parseColor("#3D2C10"))
    }
}

// ================================================================================
// 8. "TARGET NODE IP [8888]" MECHANICAL ROLLING DRUMS (With Peeking Digits!)
// ================================================================================
class TargetIpOdometerView(context: Context, private val onTapEdit: () -> Unit) : View(context) {
    private val recessPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0B0C0E") }
    private val drumPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#181818")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
    private val digitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F5F5F5")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val peekDigitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(75, 200, 200, 200)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            onTapEdit()
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val plate = RectF(2f, 2f, w - 2f, h - 2f)

        HardwareDraw.drawTexturedSteelPlate(canvas, plate, 8f, MetalTextures.brushedSteelShader)

        val sr = (h * 0.085f).coerceIn(3.5f, 6f)
        HardwareDraw.drawScrew(canvas, plate.left + sr * 2f, plate.top + sr * 2f, sr)
        HardwareDraw.drawScrew(canvas, plate.right - sr * 2f, plate.top + sr * 2f, sr)
        HardwareDraw.drawScrew(canvas, plate.left + sr * 2f, plate.bottom - sr * 2f, sr)
        HardwareDraw.drawScrew(canvas, plate.right - sr * 2f, plate.bottom - sr * 2f, sr)

        headerPaint.textSize = (h * 0.22f).coerceIn(10f, 18f)
        canvas.drawText("TARGET NODE IP [${StreamState.espPort}]", w / 2f, plate.top + h * 0.27f, headerPaint)

        val winLeft = plate.left + sr * 3.2f
        val winRight = plate.right - sr * 3.2f
        val winTop = plate.top + h * 0.35f
        val winBottom = plate.bottom - h * 0.13f
        val winRect = RectF(winLeft, winTop, winRight, winBottom)
        canvas.drawRoundRect(winRect, 5f, 5f, recessPaint)

        val chars = StreamState.espIp.toCharArray()
        val count = chars.size.coerceAtLeast(1)
        val cellW = winRect.width() / count
        digitPaint.textSize = (winRect.height() * 0.56f).coerceIn(12f, 24f)
        peekDigitPaint.textSize = digitPaint.textSize * 0.65f

        canvas.save()
        canvas.clipRect(winRect)
        for (i in chars.indices) {
            val dLeft = winLeft + i * cellW + 1.5f
            val dRect = RectF(dLeft, winTop + 2f, dLeft + cellW - 3f, winBottom - 2f)
            drumPaint.shader = LinearGradient(
                0f, dRect.top, 0f, dRect.bottom,
                intArrayOf(Color.parseColor("#0E0F11"), Color.parseColor("#35383C"), Color.parseColor("#0D0E10")),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(dRect, 3f, 3f, drumPaint)

            val ch = chars[i]
            canvas.drawText(ch.toString(), dRect.centerX(), dRect.centerY() + digitPaint.textSize * 0.35f, digitPaint)

            if (ch.isDigit()) {
                val prevD = ((ch.digitToInt() + 9) % 10).toString()
                val nextD = ((ch.digitToInt() + 1) % 10).toString()
                canvas.drawText(prevD, dRect.centerX(), dRect.top + peekDigitPaint.textSize * 0.85f, peekDigitPaint)
                canvas.drawText(nextD, dRect.centerX(), dRect.bottom - 2f, peekDigitPaint)
            }
        }
        canvas.restore()
    }
}

// ================================================================================
// 9. HEAVY-DUTY BACKLIT GLASS "ENGAGE TRANSMITTER" PUSHBUTTON
// ================================================================================
class EngageTransmitterButtonView(context: Context, private val onClick: () -> Unit) : View(context) {
    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFF8E1")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onClick()
            invalidate()
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val outer = RectF(2f, 2f, w - 2f, h - 2f)

        HardwareDraw.drawTexturedSteelPlate(canvas, outer, 8f, MetalTextures.brushedSteelShader)

        val sr = (h * 0.085f).coerceIn(3.5f, 6f)
        HardwareDraw.drawScrew(canvas, outer.left + sr * 2f, outer.top + sr * 2f, sr)
        HardwareDraw.drawScrew(canvas, outer.right - sr * 2f, outer.top + sr * 2f, sr)
        HardwareDraw.drawScrew(canvas, outer.left + sr * 2f, outer.bottom - sr * 2f, sr)
        HardwareDraw.drawScrew(canvas, outer.right - sr * 2f, outer.bottom - sr * 2f, sr)

        val inner = RectF(outer.left + sr * 3.5f, outer.top + 6f, outer.right - sr * 3.5f, outer.bottom - 6f)
        glassPaint.shader = LinearGradient(0f, inner.top, 0f, inner.bottom, Color.parseColor("#1D2520"), Color.parseColor("#0D120F"), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(inner, 6f, 6f, glassPaint)

        glowPaint.shader = if (StreamState.isRunning) {
            RadialGradient(
                inner.centerX(), inner.centerY(), inner.width() * 0.6f,
                intArrayOf(Color.parseColor("#BBFF1744"), Color.parseColor("#55D50000"), Color.TRANSPARENT),
                floatArrayOf(0f, 0.65f, 1f), Shader.TileMode.CLAMP
            )
        } else {
            RadialGradient(
                inner.centerX(), inner.centerY(), inner.width() * 0.65f,
                intArrayOf(Color.parseColor("#BB00E676"), Color.parseColor("#44FFB300"), Color.TRANSPARENT),
                floatArrayOf(0f, 0.65f, 1f), Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(inner, 6f, 6f, glowPaint)

        // Top glass lens highlight
        glossPaint.shader = LinearGradient(
            0f, inner.top, 0f, inner.centerY(),
            Color.argb(65, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(inner, 6f, 6f, glossPaint)

        textPaint.textSize = (inner.height() * 0.45f).coerceIn(11f, 22f)
        val label = if (StreamState.isRunning) "DISENGAGE TRANSMITTER" else "ENGAGE TRANSMITTER"
        canvas.drawText(label, w / 2f, inner.centerY() + textPaint.textSize * 0.35f, textPaint)
    }
}

// ================================================================================
// MAIN ACTIVITY: 100% SINGLE-SCREEN PROPORTIONAL RACK (ZERO SCROLLING!)
// ================================================================================
class MainActivity : AppCompatActivity() {

    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var audioManager: AudioManager
    private val uiHandler = Handler(Looper.getMainLooper())

    private lateinit var vuMeterView: DualAnalogVuMeterView
    private lateinit var statusRmsView: StatusAndRmsPanelView
    private lateinit var lockThumbwheelView: SignalLockAndThumbwheelDeckView
    private lateinit var histogramView: Histogram24BitMatrixView
    private lateinit var spectrumView: UltrasonicLedSpectrumView
    private lateinit var muteSwitchView: CompactRockerSwitchView
    private lateinit var txSwitchView: CompactRockerSwitchView
    private lateinit var ipOdometerView: TargetIpOdometerView
    private lateinit var engageButtonView: EngageTransmitterButtonView

    private val uiUpdater = object : Runnable {
        override fun run() {
            vuMeterView.updateMeters()
            statusRmsView.stepAnimation()
            lockThumbwheelView.invalidate()
            histogramView.invalidate()
            spectrumView.invalidate()
            engageButtonView.invalidate()
            uiHandler.postDelayed(this, 33)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        StreamState.nativeMixerRate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48000

        buildSingleScreenTransmitterUi()
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
                if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                    var next = (StreamState.gainDb + 2.0f).coerceAtMost(0.0f)
                    if (next > -0.5f) next = 0.0f
                    StreamState.gainDb = next
                } else if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                    StreamState.gainDb = (StreamState.gainDb - 2.0f).coerceAtLeast(-40.0f)
                }
                lockThumbwheelView.invalidate()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun buildSingleScreenTransmitterUi() {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        // Root Bolted Cast-Iron Rack (NO ScrollView -> Fits 100% of screen height!)
        val root = BoltedRackLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setPadding(dp(14), dp(8), dp(14), dp(8))
            weightSum = 100f
        }

        // 1. Nameplate Header (7%)
        root.addView(TransmitterHeaderView(this), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 7f).apply {
            bottomMargin = dp(4)
        })

        // 2. Dual Analog VU Meters (21%)
        vuMeterView = DualAnalogVuMeterView(this)
        root.addView(vuMeterView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 21f).apply {
            bottomMargin = dp(4)
        })

        // 3. Link Status & RMS / Crest (11%)
        statusRmsView = StatusAndRmsPanelView(this)
        root.addView(statusRmsView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 11f).apply {
            bottomMargin = dp(4)
        })

        // 4. Signal Lock & 3D Knurled Thumbwheel Gain Deck (13%)
        lockThumbwheelView = SignalLockAndThumbwheelDeckView(this) {}
        root.addView(lockThumbwheelView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 13f).apply {
            bottomMargin = dp(4)
        })

        // 5. 24-Bit Word Matrix (13%)
        histogramView = Histogram24BitMatrixView(this)
        root.addView(histogramView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 13f).apply {
            bottomMargin = dp(4)
        })

        // 6. AF / Ultrasonic Spectrum + [LAMP] [DIM] [MONO] [PEAK] (20%)
        spectrumView = UltrasonicLedSpectrumView(this) { btnIndex ->
            when (btnIndex) {
                0 -> { StreamState.warmAmberLamp = !StreamState.warmAmberLamp; vuMeterView.invalidate() }
                1 -> { StreamState.dimEnabled = !StreamState.dimEnabled; lockThumbwheelView.invalidate() }
                2 -> { StreamState.monoMode = !StreamState.monoMode }
                3 -> { StreamState.peakHoldEnabled = !StreamState.peakHoldEnabled }
            }
        }
        root.addView(spectrumView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 20f).apply {
            bottomMargin = dp(5)
        })

        // 7. Bottom Split Console (15%)
        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 100f
        }

        val leftSwitches = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            weightSum = 2f
        }
        muteSwitchView = CompactRockerSwitchView(
            this, "LOCAL", "SPKR", "MUTE", StreamState.muteSpeaker
        ) { checked ->
            StreamState.muteSpeaker = checked
            if (checked) StreamState.enforceSpeakerMuteLock(audioManager)
            else StreamState.releaseSpeakerMuteLock(audioManager)
        }
        txSwitchView = CompactRockerSwitchView(
            this, "RF", "CARRIER", "TX", StreamState.streamToEsp32
        ) { checked ->
            StreamState.streamToEsp32 = checked
        }
        leftSwitches.addView(muteSwitchView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            bottomMargin = dp(3)
        })
        leftSwitches.addView(txSwitchView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val rightDeck = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            weightSum = 2f
        }
        ipOdometerView = TargetIpOdometerView(this) { showEditIpDialog() }
        engageButtonView = EngageTransmitterButtonView(this) {
            if (StreamState.isRunning) stopCaptureService() else startMediaProjectionRequest()
        }
        rightDeck.addView(ipOdometerView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.02f).apply {
            bottomMargin = dp(3)
        })
        rightDeck.addView(engageButtonView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.98f))

        bottomRow.addView(leftSwitches, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 39f).apply {
            rightMargin = dp(5)
        })
        bottomRow.addView(rightDeck, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 61f))

        root.addView(bottomRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 15f))
        setContentView(root)
    }

    private fun showEditIpDialog() {
        val input = EditText(this).apply {
            setText(StreamState.espIp)
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("Target Node IP Address (Port ${StreamState.espPort})")
            .setView(input)
            .setPositiveButton("SAVE") { _, _ ->
                val v = input.text.toString().trim()
                if (v.isNotEmpty()) {
                    StreamState.espIp = v
                    ipOdometerView.invalidate()
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
// BACKGROUND ENGINE: Auto-Recovering 24-Bit Pre-DAC Pipe + Digital Gain + Wi-Fi
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
            StreamState.qualityVerdict = "PIPE INIT ERROR"
            return
        }

        if (StreamState.muteSpeaker) {
            StreamState.enforceSpeakerMuteLock(audioManager)
        }

        StreamState.sampleRate = lockedRate
        StreamState.isRunning = true

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
                        StreamState.isLosslessLocked = false
                        for (b in 0 until 24) StreamState.bitActiveFlags[b] = false
                        StreamState.hexPreview = "00 00 00 00 00 00"
                        StreamState.qualityVerdict = "PAUSED / NO AUDIO"
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

                val gainLinear: Float = when {
                    StreamState.gainDb <= -39.5f -> 0.0f
                    StreamState.gainDb == 0.0f && !StreamState.dimEnabled -> 1.0f
                    else -> {
                        val effDb = StreamState.gainDb + (if (StreamState.dimEnabled) -15.0f else 0.0f)
                        10.0.pow(effDb / 20.0).toFloat()
                    }
                }

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
                    var fL = floatSamples[i].coerceIn(-1.0f, 1.0f)
                    var fR = floatSamples[i + 1].coerceIn(-1.0f, 1.0f)

                    if (StreamState.monoMode) {
                        val mono = (fL + fR) * 0.5f
                        fL = mono
                        fR = mono
                    }

                    if (gainLinear != 1.0f) {
                        fL *= gainLinear
                        fR *= gainLinear
                    }

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

                // Synced bit threshold (> 4 active samples) so Bits 17-24 glow in sync with 24-BIT LOSSLESS!
                var activeBitsTotal = 0
                for (b in 0 until 24) {
                    val active = bitCounts[b] > 4
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

                    val has24BitDetail = bitCounts[18] > 4 || bitCounts[20] > 4 || bitCounts[22] > 4
                    StreamState.isLosslessLocked = (maxRms >= 0.0001) && (has24BitDetail || StreamState.bandLossless > 12)
                    StreamState.qualityVerdict = when {
                        gainLinear == 0.0f -> "OUTPUT ATTENUATED (MUTE)"
                        maxRms < 0.0001 -> "SILENCE / PLAY TRACK"
                        StreamState.bandLossless > 15 && has24BitDetail -> "24-BIT LOSSLESS"
                        has24BitDetail -> "24-BIT PCM ACTIVE"
                        else -> "16-BIT STANDARD"
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
        StreamState.isLosslessLocked = false
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
        StreamState.qualityVerdict = "STBY / NO SIGNAL"
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