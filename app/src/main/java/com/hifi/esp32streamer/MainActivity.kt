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
import android.net.wifi.WifiNetworkSpecifier
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
import kotlin.concurrent.thread
import kotlin.math.*

// ================================================================================
// SHARED LIVE STATE BETWEEN BACKGROUND SERVICE AND RIVETED RACK CONSOLE UI
// ================================================================================
object StreamState {
    @Volatile var isRunning = false
    @Volatile var appAlive = false
    @Volatile var lastDataTransferMs = 0L
    const val IDLE_TIMEOUT_MS = 15L * 60L * 1000L // 15 Minutes
    const val ACTION_CLOSE_APP = "com.hifi.esp32streamer.ACTION_CLOSE_APP"
    const val ACTION_REPOST_NOTIF = "com.hifi.esp32streamer.ACTION_REPOST_NOTIF"
    const val NOTIF_ID = 1001

    @Volatile var statusText = "IDLE"
    @Volatile var sampleRate = 48000
    @Volatile var nativeMixerRate = 48000
    @Volatile var detectedSourceRate = "48000Hz"
    @Volatile var activeBitCount = 0
    val bitActiveFlags = BooleanArray(24)
    @Volatile var qualityVerdict = "STANDBY / READY"

    @Volatile var leftLevel = 0
    @Volatile var rightLevel = 0
    @Volatile var leftDb = -40f
    @Volatile var rightDb = -40f
    @Volatile var leftPeak = false
    @Volatile var rightPeak = false
    @Volatile var dbFs = -100.0
    @Volatile var crestFactorDb = 0.0
    @Volatile var subBassPunch = 0
    @Volatile var spectralCutoffKhz = 14.0f // 14.0 kHz to 24.0 kHz live forensic cutoff
    @Volatile var dataRateKbps = 0.0
    @Volatile var hexPreview = "00 00 00 00 00 00"

    // 6-Band FFT Spectrum Analyzer Levels (0..100)
    @Volatile var bandBass = 0
    @Volatile var bandMid = 0
    @Volatile var bandTreble = 0
    @Volatile var bandAir = 0
    @Volatile var bandLossless = 0
    @Volatile var bandUltrasonic = 0

    // Hardware Rack Controls
    @Volatile var outputGainDb = 0.0f
    @Volatile var warmLampOn = true
    @Volatile var dimActive = false
    @Volatile var monoActive = false
    @Volatile var peakHoldActive = true

    @Volatile var streamToEsp32 = false
    @Volatile var isWifiReady = false
    @Volatile var isEspConnected = false
    @Volatile var espWifiNetwork: Network? = null
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
// PERSISTENT NON-CLOSEABLE NOTIFICATION HELPER
// ================================================================================
object RackNotificationHelper {
    const val CHANNEL_ID = "HiFiStreamChannel"

    fun buildNotification(context: Context, isTransmitting: Boolean): Notification {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "HiFi Audio Streamer",
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(channel)

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            context, 2, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val repostIntent = Intent(StreamState.ACTION_REPOST_NOTIF).setPackage(context.packageName)
        val repostPending = PendingIntent.getBroadcast(
            context, 99, repostIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(
                if (isTransmitting) "ESP32 Hi-Fi Transmitter Active"
                else "ESP32 Hi-Fi Console Running"
            )
            .setContentText(
                if (isTransmitting) "24-Bit Pre-DAC PCM & Wi-Fi Link Running"
                else "Standby — Ready for TX ENGAGE"
            )
            .setSmallIcon(
                if (isTransmitting) android.R.drawable.ic_media_play
                else android.R.drawable.stat_sys_headset
            )
            .setContentIntent(openPending)
            .setDeleteIntent(repostPending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)

        if (isTransmitting) {
            val stopIntent = Intent(context, AudioCaptureService::class.java).apply { action = "ACTION_STOP" }
            val stopPending = PendingIntent.getService(
                context, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_media_pause, "STOP", stopPending)
        }

        val notif = builder.build()
        notif.flags = notif.flags or Notification.FLAG_NO_CLEAR or Notification.FLAG_ONGOING_EVENT
        return notif
    }

    fun showCurrent(context: Context) {
        if (!StreamState.appAlive && !StreamState.isRunning) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(StreamState.NOTIF_ID, buildNotification(context, isTransmitting = StreamState.isRunning))
    }

    fun cancel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(StreamState.NOTIF_ID)
    }
}

// ================================================================================
// APP-SCOPED WI-FI BRIDGE (Connects to ESP32 Without Breaking 5G Mobile Data!)
// ================================================================================
object EspWifiBridge {
    private var activeCallback: ConnectivityManager.NetworkCallback? = null

    fun connect(context: Context) {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        disconnect(context)

        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid("ESP32_HiFi_DAC")
            .setWpa2Passphrase("hifiaudio123")
            .build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                StreamState.espWifiNetwork = network
                StreamState.isWifiReady = true
            }

            override fun onLost(network: Network) {
                if (StreamState.espWifiNetwork == network) {
                    StreamState.espWifiNetwork = null
                    StreamState.isWifiReady = false
                    StreamState.isEspConnected = false
                }
            }

            override fun onUnavailable() {
                StreamState.espWifiNetwork = null
                StreamState.isWifiReady = false
                StreamState.isEspConnected = false
            }
        }

        activeCallback = cb
        try {
            cm.requestNetwork(request, cb)
        } catch (_: Exception) {}
    }

    fun disconnect(context: Context) {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        activeCallback?.let {
            try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {}
        }
        activeCallback = null
        StreamState.espWifiNetwork = null
        StreamState.isEspConnected = false
    }
}

// ================================================================================
// HARDWARE GRAPHICS HELPER
// ================================================================================
object RackDraw {
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    fun drawScrew(c: Canvas, cx: Float, cy: Float, r: Float) {
        pFill.shader = RadialGradient(
            cx - r * 0.25f, cy - r * 0.25f, r * 1.2f,
            Color.parseColor("#C2C5C8"), Color.parseColor("#35383B"), Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, cy, r, pFill)
        pFill.shader = null
        pStroke.color = Color.parseColor("#18191B")
        pStroke.strokeWidth = 1.5f
        c.drawCircle(cx, cy, r, pStroke)

        pStroke.color = Color.parseColor("#202224")
        pStroke.strokeWidth = (r * 0.28f).coerceAtLeast(1.8f)
        c.drawLine(cx - r * 0.52f, cy - r * 0.14f, cx + r * 0.52f, cy + r * 0.14f, pStroke)
        c.drawLine(cx - r * 0.14f, cy + r * 0.52f, cx + r * 0.14f, cy - r * 0.52f, pStroke)
    }

    fun drawRivet(c: Canvas, cx: Float, cy: Float, r: Float) {
        pFill.shader = RadialGradient(
            cx - r * 0.3f, cy - r * 0.3f, r * 1.15f,
            Color.parseColor("#6E6B66"), Color.parseColor("#1E1D1B"), Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, cy, r, pFill)
        pFill.shader = null
        pStroke.color = Color.parseColor("#111111")
        pStroke.strokeWidth = 1.5f
        c.drawCircle(cx, cy, r, pStroke)
    }

    fun drawBrushedPanel(c: Canvas, w: Float, h: Float, darkMode: Boolean = false) {
        val rect = RectF(2f, 2f, w - 2f, h - 2f)
        pFill.shader = if (darkMode) {
            LinearGradient(
                0f, 0f, 0f, h,
                intArrayOf(Color.parseColor("#2A2C2E"), Color.parseColor("#1D1F21"), Color.parseColor("#17191A")),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
            )
        } else {
            LinearGradient(
                0f, 0f, 0f, h,
                intArrayOf(Color.parseColor("#C7C9CA"), Color.parseColor("#A1A4A6"), Color.parseColor("#86898B")),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
            )
        }
        c.drawRoundRect(rect, 10f, 10f, pFill)
        pFill.shader = null

        pStroke.color = if (darkMode) Color.parseColor("#4B4E52") else Color.parseColor("#EAECEE")
        pStroke.strokeWidth = 2f
        c.drawRoundRect(rect, 10f, 10f, pStroke)

        val sr = (minOf(w, h) * 0.045f).coerceIn(5f, 8.5f)
        val off = sr * 2.1f
        drawScrew(c, off, off, sr)
        drawScrew(c, w - off, off, sr)
        drawScrew(c, off, h - off, sr)
        drawScrew(c, w - off, h - off, sr)
    }

    fun drawLed(c: Canvas, cx: Float, cy: Float, r: Float, isOn: Boolean, onColor: Int, offColor: Int) {
        pFill.shader = null
        pFill.color = Color.parseColor("#1A1B1D")
        c.drawCircle(cx, cy, r * 1.32f, pFill)

        if (isOn) {
            pFill.shader = RadialGradient(
                cx, cy, r * 2.35f,
                intArrayOf(Color.WHITE, onColor, Color.TRANSPARENT),
                floatArrayOf(0f, 0.42f, 1f), Shader.TileMode.CLAMP
            )
            c.drawCircle(cx, cy, r * 2.35f, pFill)
        } else {
            pFill.shader = RadialGradient(
                cx - r * 0.2f, cy - r * 0.2f, r,
                offColor, Color.parseColor("#101010"), Shader.TileMode.CLAMP
            )
            c.drawCircle(cx, cy, r, pFill)
        }
        pFill.shader = null
    }
}

class RivetedRackContainer(context: Context) : LinearLayout(context) {
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val railPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0D0E10")
        strokeWidth = 3f
    }

    init {
        orientation = VERTICAL
        setWillNotDraw(false)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        bgPaint.color = Color.parseColor("#17181A")
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        val railW = paddingLeft * 0.85f
        railPaint.shader = LinearGradient(
            0f, 0f, railW, 0f,
            Color.parseColor("#343638"), Color.parseColor("#1E2022"), Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, railW, h, railPaint)

        railPaint.shader = LinearGradient(
            w - railW, 0f, w, 0f,
            Color.parseColor("#1E2022"), Color.parseColor("#343638"), Shader.TileMode.CLAMP
        )
        canvas.drawRect(w - railW, 0f, w, h, railPaint)

        canvas.drawLine(railW, 0f, railW, h, linePaint)
        canvas.drawLine(w - railW, 0f, w - railW, h, linePaint)

        val step = (railW * 2.3f).coerceAtLeast(48f)
        val r = (railW * 0.26f).coerceIn(4f, 8f)
        var y = step * 0.5f
        while (y < h) {
            RackDraw.drawRivet(canvas, railW * 0.5f, y, r)
            RackDraw.drawRivet(canvas, w - railW * 0.5f, y, r)
            y += step
        }
        super.onDraw(canvas)
    }
}

class DualAnalogVuPanelView(context: Context) : View(context) {
    private var smoothL = -34f
    private var smoothR = -34f

    private val bezelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1D1F21") }
    private val innerShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = Color.parseColor("#101112")
    }
    private val dialPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blackArc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#231D16")
        strokeWidth = 2.6f
    }
    private val redArc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#C62828")
        strokeWidth = 4.0f
    }
    private val textDark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#231D16")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
    private val textRed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#C62828")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#26282A")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
    private val needleShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(65, 0, 0, 0)
        style = Paint.Style.STROKE
        strokeWidth = 4.2f
        strokeCap = Paint.Cap.ROUND
    }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#222222")
        style = Paint.Style.STROKE
        strokeWidth = 3.0f
        strokeCap = Paint.Cap.ROUND
    }

    private fun dbToAngle(db: Float): Float {
        val vu = (db + 10f).coerceIn(-20f, 3f)
        val norm = ((vu + 20f) / 23f).coerceIn(0f, 1f)
        return -34f + norm * 68f
    }

    fun updateMeters() {
        val tgtL = if (StreamState.isRunning) dbToAngle(StreamState.leftDb) else -34f
        val tgtR = if (StreamState.isRunning) dbToAngle(StreamState.rightDb) else -34f
        smoothL += if (tgtL > smoothL) (tgtL - smoothL) * 0.45f else (tgtL - smoothL) * 0.18f
        smoothR += if (tgtR > smoothR) (tgtR - smoothR) * 0.45f else (tgtR - smoothR) * 0.18f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        RackDraw.drawBrushedPanel(canvas, w, h, darkMode = false)

        val padX = w * 0.06f
        val gap = w * 0.04f
        val mw = (w - padX * 2f - gap) / 2f
        val topY = h * 0.09f
        val mh = h * 0.72f

        drawVu(canvas, padX, topY, mw, mh, smoothL, StreamState.leftPeak, "LEFT")
        drawVu(canvas, padX + mw + gap, topY, mw, mh, smoothR, StreamState.rightPeak, "RIGHT")
    }

    private fun drawVu(c: Canvas, left: Float, top: Float, mw: Float, mh: Float, angle: Float, peak: Boolean, title: String) {
        val cx = left + mw / 2f
        val bezel = RectF(left, top, left + mw, top + mh)
        c.drawRoundRect(bezel, 10f, 10f, bezelPaint)

        val inset = mw * 0.045f
        val face = RectF(left + inset, top + inset, left + mw - inset, top + mh - inset)

        dialPaint.shader = if (StreamState.warmLampOn) {
            RadialGradient(
                cx, face.bottom - face.height() * 0.15f, face.width() * 0.7f,
                intArrayOf(Color.parseColor("#FFF4D2"), Color.parseColor("#E6D4A6"), Color.parseColor("#C2AE7D")),
                floatArrayOf(0f, 0.65f, 1f), Shader.TileMode.CLAMP
            )
        } else {
            RadialGradient(
                cx, face.centerY(), face.width() * 0.7f,
                intArrayOf(Color.parseColor("#F5F7F8"), Color.parseColor("#DCE0E3"), Color.parseColor("#B5BABE")),
                floatArrayOf(0f, 0.65f, 1f), Shader.TileMode.CLAMP
            )
        }
        c.drawRoundRect(face, 5f, 5f, dialPaint)
        c.drawRoundRect(face, 5f, 5f, innerShadow)

        val ledX = face.right - face.width() * 0.10f
        val ledY = face.top + face.height() * 0.24f
        RackDraw.drawLed(c, ledX, ledY, (mw * 0.023f).coerceIn(4f, 7.5f), peak, Color.parseColor("#FF1744"), Color.parseColor("#5C1818"))
        textRed.textSize = (mw * 0.045f).coerceIn(9f, 15f)
        c.drawText("PEAK", ledX, ledY - (mw * 0.04f), textRed)

        val pivotX = cx
        val pivotY = face.bottom + face.height() * 0.36f
        val arcR = face.height() * 0.84f

        c.save()
        c.clipRect(face)

        val arcRect = RectF(pivotX - arcR, pivotY - arcR, pivotX + arcR, pivotY + arcR)
        c.drawArc(arcRect, -124f, 54.4f, false, blackArc)
        c.drawArc(arcRect, -69.6f, 13.6f, false, redArc)

        data class T(val norm: Float, val lbl: String, val isRed: Boolean)
        val ticks = listOf(
            T(0.00f, "-20", false), T(0.20f, "-10", false), T(0.35f, "-7", false),
            T(0.48f, "-5", false), T(0.61f, "-3", false), T(0.72f, "-1", false),
            T(0.80f, "0", true), T(0.90f, "+2", true), T(1.00f, "+3", true)
        )
        textDark.textSize = (mw * 0.048f).coerceIn(10f, 16f)
        textRed.textSize = textDark.textSize

        for (t in ticks) {
            val deg = -34f + t.norm * 68f
            val rad = Math.toRadians((deg - 90f).toDouble())
            val cosA = cos(rad).toFloat()
            val sinA = sin(rad).toFloat()
            val p = if (t.isRed) redArc else blackArc
            c.drawLine(pivotX + arcR * cosA, pivotY + arcR * sinA, pivotX + arcR * 1.10f * cosA, pivotY + arcR * 1.10f * sinA, p)
            val pt = if (t.isRed) textRed else textDark
            c.drawText(t.lbl, pivotX + arcR * 1.24f * cosA, pivotY + arcR * 1.24f * sinA + pt.textSize * 0.35f, pt)
        }

        textDark.textSize = (mw * 0.095f).coerceIn(17f, 28f)
        c.drawText("VU", cx, face.bottom - face.height() * 0.14f, textDark)

        val nRad = Math.toRadians((angle - 90f).toDouble())
        val nLen = arcR * 1.15f
        val tipX = pivotX + nLen * cos(nRad).toFloat()
        val tipY = pivotY + nLen * sin(nRad).toFloat()
        c.drawLine(pivotX + 3f, pivotY + 3f, tipX + 3f, tipY + 3f, needleShadow)
        c.drawLine(pivotX, pivotY, tipX, tipY, needlePaint)
        c.restore()

        labelPaint.textSize = (mw * 0.072f).coerceIn(14f, 22f)
        c.drawText(title, cx, top + mh + (height - (top + mh)) * 0.72f, labelPaint)
    }
}

// ================================================================================
// PANEL 2: HIGH-RESOLUTION DYNAMIC CREST (dB) + HIGH-ACTION SUB-BASS PUNCH (%)
// ================================================================================
class CrestAndSubBassPanelView(context: Context) : View(context) {
    private var smoothCrest = 0f
    private var smoothSub = 0f

    private val bezelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1D1F21") }
    private val innerShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = Color.parseColor("#101112")
    }
    private val dialPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val blackArc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#231D16")
        strokeWidth = 2.2f
    }
    private val textDark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#231D16")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#26282A")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E65100")
        style = Paint.Style.STROKE
        strokeWidth = 3.4f
        strokeCap = Paint.Cap.ROUND
    }

    fun updateMeters() {
        val tgtCrest = if (StreamState.isRunning && StreamState.dbFs > -80.0) {
            StreamState.crestFactorDb.toFloat().coerceIn(0f, 20f)
        } else 0f

        val tgtSub = if (StreamState.isRunning && StreamState.dbFs > -80.0) {
            StreamState.subBassPunch.toFloat().coerceIn(0f, 100f)
        } else 0f

        smoothCrest += if (tgtCrest > smoothCrest) (tgtCrest - smoothCrest) * 0.40f else (tgtCrest - smoothCrest) * 0.18f
        smoothSub += if (tgtSub > smoothSub) (tgtSub - smoothSub) * 0.65f else (tgtSub - smoothSub) * 0.22f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        RackDraw.drawBrushedPanel(canvas, w, h, darkMode = false)

        val padX = w * 0.06f
        val gap = w * 0.04f
        val mw = (w - padX * 2f - gap) / 2f
        val topY = h * 0.11f
        val mh = h * 0.62f

        val isInGreenZone = StreamState.isRunning && smoothCrest in 5.0f..17.0f

        drawArcGauge(
            canvas, padX, topY, mw, mh,
            normVal = smoothCrest / 20f,
            labels = listOf(0f to "0", 0.25f to "5", 0.5f to "10", 0.75f to "15", 1.0f to "20"),
            bandColor = Color.parseColor("#43A047"),
            bandStartDeg = -104f, bandSweepDeg = 32f,
            ledLabel = "TX OK",
            ledOn = isInGreenZone,
            ledColor = Color.parseColor("#00E676"),
            title = "DYNAMIC CREST (dB)"
        )

        val isSubKicking = StreamState.isRunning && smoothSub > 75f

        drawArcGauge(
            canvas, padX + mw + gap, topY, mw, mh,
            normVal = smoothSub / 100f,
            labels = listOf(0f to "0", 0.25f to "25", 0.5f to "50", 0.75f to "75", 1.0f to "100"),
            bandColor = Color.parseColor("#D32F2F"),
            bandStartDeg = -76f, bandSweepDeg = 14f,
            ledLabel = "SNAP",
            ledOn = isSubKicking,
            ledColor = Color.parseColor("#FF1744"),
            title = "KICK / THUMP (60-120Hz)"
        )
    }

    private fun drawArcGauge(
        c: Canvas, left: Float, top: Float, mw: Float, mh: Float,
        normVal: Float, labels: List<Pair<Float, String>>,
        bandColor: Int, bandStartDeg: Float, bandSweepDeg: Float,
        ledLabel: String, ledOn: Boolean, ledColor: Int, title: String
    ) {
        val cx = left + mw / 2f
        val bezel = RectF(left, top, left + mw, top + mh)
        c.drawRoundRect(bezel, 8f, 8f, bezelPaint)

        val inset = mw * 0.04f
        val face = RectF(left + inset, top + inset, left + mw - inset, top + mh - inset)
        dialPaint.shader = if (StreamState.warmLampOn) {
            RadialGradient(
                cx, face.bottom, face.width() * 0.7f,
                intArrayOf(Color.parseColor("#FFF4D2"), Color.parseColor("#E6D4A6"), Color.parseColor("#C2AE7D")),
                floatArrayOf(0f, 0.65f, 1f), Shader.TileMode.CLAMP
            )
        } else {
            RadialGradient(
                cx, face.centerY(), face.width() * 0.7f,
                intArrayOf(Color.parseColor("#F5F7F8"), Color.parseColor("#DCE0E3"), Color.parseColor("#B5BABE")),
                floatArrayOf(0f, 0.65f, 1f), Shader.TileMode.CLAMP
            )
        }
        c.drawRoundRect(face, 4f, 4f, dialPaint)
        c.drawRoundRect(face, 4f, 4f, innerShadow)

        val ledX = face.right - face.width() * 0.11f
        val ledY = face.top + face.height() * 0.34f
        RackDraw.drawLed(c, ledX, ledY, (mh * 0.08f).coerceIn(4f, 7.5f), ledOn, ledColor, Color.parseColor("#2A2220"))
        textDark.textSize = (mh * 0.14f).coerceIn(8.5f, 13f)
        c.drawText(ledLabel, ledX, ledY + mh * 0.22f, textDark)

        val pivotX = cx - mw * 0.04f
        val pivotY = face.bottom + face.height() * 0.55f
        val arcR = face.height() * 1.02f

        c.save()
        c.clipRect(face)

        val arcRect = RectF(pivotX - arcR, pivotY - arcR, pivotX + arcR, pivotY + arcR)
        bandPaint.strokeWidth = mh * 0.12f
        bandPaint.color = bandColor
        c.drawArc(arcRect, bandStartDeg, bandSweepDeg, false, bandPaint)

        c.drawArc(arcRect, -118f, 56f, false, blackArc)
        textDark.textSize = (mh * 0.17f).coerceIn(9.5f, 15f)
        for ((norm, txt) in labels) {
            val deg = -28f + norm * 56f
            val rad = Math.toRadians((deg - 90f).toDouble())
            val cosA = cos(rad).toFloat()
            val sinA = sin(rad).toFloat()
            c.drawLine(pivotX + arcR * 0.92f * cosA, pivotY + arcR * 0.92f * sinA, pivotX + arcR * 1.11f * cosA, pivotY + arcR * 1.11f * sinA, blackArc)
            c.drawText(txt, pivotX + arcR * 1.25f * cosA, pivotY + arcR * 1.25f * sinA + textDark.textSize * 0.3f, textDark)
        }

        val angle = -28f + normVal.coerceIn(0f, 1f) * 56f
        val nRad = Math.toRadians((angle - 90f).toDouble())
        c.drawLine(pivotX, pivotY, pivotX + arcR * 1.15f * cos(nRad).toFloat(), pivotY + arcR * 1.15f * sin(nRad).toFloat(), needlePaint)
        c.restore()

        labelPaint.textSize = (mh * 0.20f).coerceIn(11f, 18f)
        c.drawText(title, cx, top + mh + (height - (top + mh)) * 0.68f, labelPaint)
    }
}

// ================================================================================
// PANEL 3: RECESSED SPECTRAL CUTOFF METER (14-24 kHz) + OUTPUT GAIN THUMBWHEEL
// ================================================================================
class CutoffAndGainPanelView(context: Context, private val onGainChanged: (Float) -> Unit) : View(context) {
    private var smoothCutoff = 14.0f

    private val bezelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1D1F21") }
    private val innerShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.parseColor("#101112")
    }
    private val dialPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blackArc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#231D16")
        strokeWidth = 2.0f
    }
    private val greenArc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#2E7D32")
        strokeWidth = 3.6f
    }
    private val textDark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#231D16")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#C62828")
        style = Paint.Style.STROKE
        strokeWidth = 2.8f
        strokeCap = Paint.Cap.ROUND
    }
    private val slotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#161719") }
    private val slotBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.0f
        color = Color.parseColor("#54575A")
    }
    private val amberText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFB300")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val wheelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ribPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2.5f }
    private val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1D1F21")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#26282A")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }

    private var wheelRect = RectF()
    private var lastTouchX = 0f
    private var lastTapMs = 0L
    private var lastHapticStep = 0

    fun updateCutoff() {
        val tgt = if (StreamState.isRunning) StreamState.spectralCutoffKhz.coerceIn(14.0f, 24.0f) else 14.0f
        smoothCutoff += (tgt - smoothCutoff) * 0.22f
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.x < width * 0.45f) return false

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val now = System.currentTimeMillis()
                if (now - lastTapMs < 280) {
                    StreamState.outputGainDb = 0.0f
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    onGainChanged(0.0f)
                    invalidate()
                }
                lastTapMs = now
                lastTouchX = event.x
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastTouchX
                lastTouchX = event.x
                val deltaDb = (dx / (wheelRect.width().coerceAtLeast(100f))) * 40.0f
                var newDb = (StreamState.outputGainDb + deltaDb).coerceIn(-40.0f, 0.0f)
                if (newDb > -0.7f) newDb = 0.0f

                val step = (newDb / 2.0f).toInt()
                if (step != lastHapticStep) {
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    lastHapticStep = step
                }
                StreamState.outputGainDb = newDb
                onGainChanged(newDb)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        RackDraw.drawBrushedPanel(canvas, w, h, darkMode = false)

        val meterLeft = w * 0.055f
        val meterRight = w * 0.445f
        val meterTop = h * 0.08f
        val meterBottom = h * 0.65f
        val mw = meterRight - meterLeft
        val mh = meterBottom - meterTop
        val mcx = (meterLeft + meterRight) / 2f

        val bezel = RectF(meterLeft, meterTop, meterRight, meterBottom)
        canvas.drawRoundRect(bezel, 8f, 8f, bezelPaint)

        val inset = 5f
        val face = RectF(bezel.left + inset, bezel.top + inset, bezel.right - inset, bezel.bottom - inset)
        dialPaint.shader = if (StreamState.warmLampOn) {
            RadialGradient(
                mcx, face.bottom, face.width() * 0.7f,
                intArrayOf(Color.parseColor("#FFF4D2"), Color.parseColor("#E6D4A6"), Color.parseColor("#C2AE7D")),
                floatArrayOf(0f, 0.65f, 1f), Shader.TileMode.CLAMP
            )
        } else {
            RadialGradient(
                mcx, face.centerY(), face.width() * 0.7f,
                intArrayOf(Color.parseColor("#F5F7F8"), Color.parseColor("#DCE0E3"), Color.parseColor("#B5BABE")),
                floatArrayOf(0f, 0.65f, 1f), Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(face, 4f, 4f, dialPaint)
        canvas.drawRoundRect(face, 4f, 4f, innerShadow)

        val isLosslessAudio = StreamState.isRunning && smoothCutoff >= 20.3f
        val ledR = (mh * 0.08f).coerceIn(3.5f, 6.5f)
        val ledX = face.right - face.width() * 0.12f
        val ledY = face.top + face.height() * 0.28f
        RackDraw.drawLed(canvas, ledX, ledY, ledR, isLosslessAudio, Color.parseColor("#00E676"), Color.parseColor("#183820"))
        textDark.textSize = (mh * 0.13f).coerceIn(7.5f, 11f)
        canvas.drawText("LOSSLESS", ledX, ledY + mh * 0.24f, textDark)

        val pivotX = mcx - mw * 0.02f
        val pivotY = face.bottom + face.height() * 0.40f
        val arcR = face.height() * 0.90f

        canvas.save()
        canvas.clipRect(face)

        val arcRect = RectF(pivotX - arcR, pivotY - arcR, pivotX + arcR, pivotY + arcR)
        canvas.drawArc(arcRect, -118f, 33.6f, false, blackArc)
        canvas.drawArc(arcRect, -84.4f, 22.4f, false, greenArc)

        val cutoffTicks = listOf(
            0.0f to "14", 0.2f to "16", 0.4f to "18",
            0.6f to "20k", 0.8f to "22", 1.0f to "24k"
        )
        textDark.textSize = (mh * 0.16f).coerceIn(8.5f, 13f)
        for ((norm, txt) in cutoffTicks) {
            val deg = -28f + norm * 56f
            val rad = Math.toRadians((deg - 90f).toDouble())
            val cosA = cos(rad).toFloat()
            val sinA = sin(rad).toFloat()
            canvas.drawLine(pivotX + arcR * 0.92f * cosA, pivotY + arcR * 0.92f * sinA, pivotX + arcR * 1.10f * cosA, pivotY + arcR * 1.10f * sinA, blackArc)
            canvas.drawText(txt, pivotX + arcR * 1.25f * cosA, pivotY + arcR * 1.25f * sinA + textDark.textSize * 0.3f, textDark)
        }

        val rad20 = Math.toRadians((-28f + 0.6f * 56f - 90f).toDouble())
        needlePaint.color = Color.parseColor("#FFB300")
        needlePaint.strokeWidth = 1.8f
        canvas.drawLine(pivotX + arcR * 0.85f * cos(rad20).toFloat(), pivotY + arcR * 0.85f * sin(rad20).toFloat(), pivotX + arcR * 1.15f * cos(rad20).toFloat(), pivotY + arcR * 1.15f * sin(rad20).toFloat(), needlePaint)

        val normCutoff = ((smoothCutoff - 14.0f) / 10.0f).coerceIn(0.0f, 1.0f)
        val angle = -28f + normCutoff * 56f
        val nRad = Math.toRadians((angle - 90f).toDouble())
        needlePaint.color = Color.parseColor("#D32F2F")
        needlePaint.strokeWidth = 2.6f
        canvas.drawLine(pivotX, pivotY, pivotX + arcR * 1.15f * cos(nRad).toFloat(), pivotY + arcR * 1.15f * sin(nRad).toFloat(), needlePaint)
        canvas.restore()

        val slotRect = RectF(meterLeft, h * 0.68f, meterRight, h * 0.90f)
        canvas.drawRoundRect(slotRect, 6f, 6f, slotPaint)
        canvas.drawRoundRect(slotRect, 6f, 6f, slotBorder)

        val cutoffVerdict = when {
            !StreamState.isRunning || StreamState.dbFs <= -90.0 -> "IDLE / SILENCE"
            smoothCutoff >= 20.4f -> "LOSSLESS [${String.format("%.1f", smoothCutoff)} kHz]"
            smoothCutoff >= 19.5f -> "320k MP3 [${String.format("%.1f", smoothCutoff)} kHz]"
            else -> "LOSSY [${String.format("%.1f", smoothCutoff)} kHz]"
        }
        amberText.textSize = (slotRect.height() * 0.44f).coerceIn(9.5f, 15f)
        canvas.drawText(cutoffVerdict, slotRect.centerX(), slotRect.centerY() + amberText.textSize * 0.35f, amberText)

        val wheelCx = w * 0.65f
        headerPaint.textSize = (h * 0.145f).coerceIn(13f, 20f)
        canvas.drawText("OUTPUT GAIN", wheelCx, h * 0.21f, headerPaint)

        wheelRect = RectF(w * 0.50f, h * 0.30f, w * 0.80f, h * 0.65f)
        canvas.drawRoundRect(wheelRect, 8f, 8f, slotPaint)
        canvas.drawRoundRect(wheelRect, 8f, 8f, slotBorder)

        val drum = RectF(wheelRect.left + 6f, wheelRect.top + 5f, wheelRect.right - 6f, wheelRect.bottom - 5f)
        wheelPaint.shader = LinearGradient(
            drum.left, 0f, drum.right, 0f,
            intArrayOf(Color.parseColor("#18191B"), Color.parseColor("#474A4E"), Color.parseColor("#1A1B1D")),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(drum, 5f, 5f, wheelPaint)
        drawScrollingRibs(canvas, drum)

        subPaint.textSize = (h * 0.10f).coerceIn(9.5f, 14f)
        val marks = listOf("-40", "-20", "-12", "-6", "0dB")
        val yMark = h * 0.85f
        for (i in marks.indices) {
            val mx = wheelRect.left + (i / 4f) * wheelRect.width()
            canvas.drawLine(mx, wheelRect.bottom + 2f, mx, wheelRect.bottom + 8f, subPaint)
            canvas.drawText(marks[i], mx, yMark, subPaint)
        }

        val uCx = w * 0.895f
        canvas.drawText("UNITY", uCx, h * 0.34f, subPaint)
        val isUnity = StreamState.outputGainDb > -0.1f && !StreamState.dimActive
        RackDraw.drawLed(canvas, uCx, h * 0.48f, 5.5f, isUnity, Color.parseColor("#00E676"), Color.parseColor("#193D24"))

        val dbSlot = RectF(w * 0.84f, h * 0.62f, w * 0.955f, h * 0.85f)
        canvas.drawRoundRect(dbSlot, 5f, 5f, slotPaint)
        canvas.drawRoundRect(dbSlot, 5f, 5f, slotBorder)

        val shownDb = if (StreamState.dimActive) (StreamState.outputGainDb - 15f).coerceAtLeast(-55f) else StreamState.outputGainDb
        amberText.textSize = (dbSlot.height() * 0.48f).coerceIn(10f, 17f)
        canvas.drawText(String.format("%.1f", abs(shownDb)), dbSlot.centerX(), dbSlot.centerY() + amberText.textSize * 0.35f, amberText)
    }

    private fun drawScrollingRibs(c: Canvas, drum: RectF) {
        c.save()
        c.clipRect(drum)
        val ribSpacing = (drum.width() / 18f).coerceAtLeast(6f)
        val phaseShift = ((StreamState.outputGainDb + 40f) / 40f) * drum.width() * 0.9f
        val offset = phaseShift % ribSpacing

        var rx = drum.left - ribSpacing + offset
        while (rx < drum.right) {
            ribPaint.color = Color.parseColor("#111213")
            c.drawLine(rx, drum.top, rx, drum.bottom, ribPaint)
            ribPaint.color = Color.parseColor("#5C6066")
            c.drawLine(rx + 2f, drum.top, rx + 2f, drum.bottom, ribPaint)
            rx += ribSpacing
        }
        c.restore()
    }
}

class WordMatrix24FullPanelView(context: Context) : View(context) {
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E0E0E0")
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
    private val hexPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFB300")
        textAlign = Paint.Align.RIGHT
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val rowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CCCCCC")
        typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    }
    private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cellBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#101112")
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        RackDraw.drawBrushedPanel(canvas, w, h, darkMode = true)

        titlePaint.textSize = (h * 0.14f).coerceIn(13f, 20f)
        canvas.drawText("24-BIT WORD MATRIX (${StreamState.activeBitCount}/24)", w * 0.045f, h * 0.21f, titlePaint)

        hexPaint.textSize = (h * 0.13f).coerceIn(12f, 18f)
        canvas.drawText("HEX: ${StreamState.hexPreview}", w * 0.955f, h * 0.21f, hexPaint)

        val labels = arrayOf("01-08:", "09-16:", "17-24:")
        val startY = h * 0.27f
        val availH = h * 0.66f
        val rowH = availH / 3f
        val gridLeft = w * 0.19f
        val gridW = (w * 0.955f) - gridLeft
        val cellW = gridW / 8f

        rowPaint.textSize = (rowH * 0.56f).coerceIn(12f, 19f)

        for (r in 0 until 3) {
            val ry = startY + r * rowH
            canvas.drawText(labels[r], w * 0.045f, ry + rowH * 0.68f, rowPaint)

            for (c in 0 until 8) {
                val bitIdx = r * 8 + c
                val lit = StreamState.isRunning && StreamState.bitActiveFlags[bitIdx]
                val bx = gridLeft + c * cellW + 3f
                val by = ry + 3f
                val rect = RectF(bx, by, bx + cellW - 6f, ry + rowH - 3f)

                cellPaint.shader = if (lit) {
                    RadialGradient(
                        rect.centerX(), rect.centerY(), rect.width() * 0.65f,
                        intArrayOf(Color.parseColor("#FFF9C4"), Color.parseColor("#FFB300"), Color.parseColor("#E65100")),
                        floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
                    )
                } else {
                    LinearGradient(
                        0f, rect.top, 0f, rect.bottom,
                        Color.parseColor("#3B2E1E"), Color.parseColor("#211A10"), Shader.TileMode.CLAMP
                    )
                }
                canvas.drawRoundRect(rect, 4f, 4f, cellPaint)
                canvas.drawRoundRect(rect, 4f, 4f, cellBorder)
            }
        }
    }
}

class SpectrumFullPanelView(
    context: Context,
    private val onButtonAction: (String) -> Unit
) : View(context) {

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E0E0E0")
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
    private val ratePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#80CBC4")
        textAlign = Paint.Align.RIGHT
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D4D4D4")
        typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    }
    private val segPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val btnPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val btnBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#111111")
    }
    private val btnText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }

    private val peakHoldVals = IntArray(6)
    private val peakDecayCnt = IntArray(6)
    private val btnRects = Array(4) { RectF() }
    private val btnIds = arrayOf("LAMP", "DIM", "MONO", "PEAK")

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            for (i in 0 until 4) {
                if (btnRects[i].contains(event.x, event.y)) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onButtonAction(btnIds[i])
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
        RackDraw.drawBrushedPanel(canvas, w, h, darkMode = true)

        titlePaint.textSize = (h * 0.092f).coerceIn(13f, 20f)
        canvas.drawText("AF / ULTRASONIC SPECTRUM (0-48 kHz)", w * 0.045f, h * 0.14f, titlePaint)

        ratePaint.textSize = (h * 0.082f).coerceIn(11f, 17f)
        canvas.drawText("${StreamState.sampleRate}Hz / 24B", w * 0.955f, h * 0.14f, ratePaint)

        val bands = arrayOf(
            "SUB (20-250Hz)" to StreamState.bandBass,
            "MID (250-4kHz)" to StreamState.bandMid,
            "TREBLE (4-12k)" to StreamState.bandTreble,
            "AIR (12-16kHz)" to StreamState.bandAir,
            "LOSSLESS (16-20k)" to StreamState.bandLossless,
            "HI-AIR (20-24kHz)" to StreamState.bandUltrasonic
        )

        val topY = h * 0.20f
        val availH = h * 0.74f
        val rowH = availH / 6f

        val vColX = w * 0.30f
        val vColW = w * 0.022f
        val vSegs = 20
        val vSegH = availH / vSegs
        val masterLvl = ((StreamState.leftLevel + StreamState.rightLevel) / 2).coerceIn(0, 100)
        val litMaster = if (StreamState.isRunning) (masterLvl * vSegs) / 100 else 0
        for (s in 0 until vSegs) {
            val inv = (vSegs - 1) - s
            segPaint.color = if (inv < litMaster) Color.parseColor("#00E676") else Color.parseColor("#182B1E")
            canvas.drawRoundRect(vColX, topY + s * vSegH + 1.5f, vColX + vColW, topY + (s + 1) * vSegH - 1.5f, 2f, 2f, segPaint)
        }

        val barLeft = w * 0.34f
        val barRight = w * 0.775f
        val hSegs = 14
        val segW = (barRight - barLeft) / hSegs
        bandPaint.textSize = (rowH * 0.50f).coerceIn(11f, 17f)

        for (i in bands.indices) {
            val (lbl, lvl) = bands[i]
            val cy = topY + (i + 0.5f) * rowH
            canvas.drawText(lbl, w * 0.045f, cy + bandPaint.textSize * 0.35f, bandPaint)

            val activeSegs = if (StreamState.isRunning) (lvl * hSegs) / 100 else 0
            if (StreamState.peakHoldActive && StreamState.isRunning) {
                if (activeSegs >= peakHoldVals[i]) {
                    peakHoldVals[i] = activeSegs
                    peakDecayCnt[i] = 18
                } else if (peakDecayCnt[i] > 0) {
                    peakDecayCnt[i]--
                } else if (peakHoldVals[i] > 0) {
                    peakHoldVals[i]--
                    peakDecayCnt[i] = 3
                }
            } else {
                peakHoldVals[i] = 0
            }

            for (s in 0 until hSegs) {
                val sx = barLeft + s * segW + 2f
                val sRect = RectF(sx, cy - rowH * 0.30f, sx + segW - 4f, cy + rowH * 0.30f)
                val isLit = s < activeSegs || (StreamState.peakHoldActive && s == peakHoldVals[i] - 1 && peakHoldVals[i] > 0)
                segPaint.color = when {
                    !isLit -> when {
                        s < 7 -> Color.parseColor("#182D1D")
                        s < 11 -> Color.parseColor("#382C13")
                        else -> Color.parseColor("#3A1616")
                    }
                    s < 7 -> Color.parseColor("#00E676")
                    s < 11 -> Color.parseColor("#FFB300")
                    else -> Color.parseColor("#FF1744")
                }
                canvas.drawRoundRect(sRect, 2.5f, 2.5f, segPaint)
            }
        }

        val btnStates = booleanArrayOf(StreamState.warmLampOn, StreamState.dimActive, StreamState.monoActive, StreamState.peakHoldActive)
        val btnLabels = arrayOf("[LAMP]", "[DIM]", "[MONO]", "[PEAK]")
        val btnLeft = w * 0.80f
        val btnRight = w * 0.96f
        val btnStep = availH / 4f
        btnText.textSize = (btnStep * 0.38f).coerceIn(11f, 16f)

        for (i in 0 until 4) {
            val bTop = topY + i * btnStep + 4f
            val bBot = topY + (i + 1) * btnStep - 4f
            btnRects[i].set(btnLeft, bTop, btnRight, bBot)

            val active = btnStates[i]
            btnPaint.shader = if (active) {
                LinearGradient(0f, bTop, 0f, bBot, Color.parseColor("#FFCA28"), Color.parseColor("#E65100"), Shader.TileMode.CLAMP)
            } else {
                LinearGradient(0f, bTop, 0f, bBot, Color.parseColor("#54575B"), Color.parseColor("#2A2C2E"), Shader.TileMode.CLAMP)
            }
            canvas.drawRoundRect(btnRects[i], 6f, 6f, btnPaint)
            canvas.drawRoundRect(btnRects[i], 6f, 6f, btnBorder)

            btnText.color = if (active) Color.parseColor("#1A1208") else Color.parseColor("#DCDCDC")
            canvas.drawText(btnLabels[i], btnRects[i].centerX(), btnRects[i].centerY() + btnText.textSize * 0.35f, btnText)
        }
    }
}

class BottomControlDeckView(
    context: Context,
    private val onMuteToggle: (Boolean) -> Unit,
    private val onTxToggle: (Boolean) -> Unit,
    private val onEditIp: () -> Unit,
    private val onEngageTap: () -> Unit
) : View(context) {

    private val lblPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1F2123")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
    private val rxTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFB300")
        textAlign = Paint.Align.LEFT
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val housingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#141517") }
    private val rockerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val notchPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val drumPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val digitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F5F5F5")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val btnFramePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val btnGlassPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val btnGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val btnBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        color = Color.parseColor("#4B4E52")
    }
    private val engageTxtPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFF8E1")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }

    private val rectMute = RectF()
    private val rectTx = RectF()
    private val rectIp = RectF()
    private val rectEngage = RectF()

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val x = event.x
            val y = event.y
            when {
                rectMute.contains(x, y) -> {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    StreamState.muteSpeaker = !StreamState.muteSpeaker
                    onMuteToggle(StreamState.muteSpeaker)
                    invalidate()
                }
                rectTx.contains(x, y) -> {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    StreamState.streamToEsp32 = !StreamState.streamToEsp32
                    onTxToggle(StreamState.streamToEsp32)
                    invalidate()
                }
                rectIp.contains(x, y) -> {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onEditIp()
                }
                rectEngage.contains(x, y) -> {
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    onEngageTap()
                    invalidate()
                }
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        RackDraw.drawBrushedPanel(canvas, w, h, darkMode = false)

        val sw1Cx = w * 0.135f
        val sw2Cx = w * 0.325f
        val swW = w * 0.12f
        val swTop = h * 0.42f
        val swBot = h * 0.87f

        rectMute.set(sw1Cx - swW * 0.65f, h * 0.08f, sw1Cx + swW * 0.65f, h * 0.94f)
        rectTx.set(sw2Cx - swW * 0.65f, h * 0.08f, sw2Cx + swW * 0.65f, h * 0.94f)

        lblPaint.textSize = (h * 0.098f).coerceIn(10f, 15f)
        canvas.drawText("SPKR MUTE", sw1Cx, h * 0.22f, lblPaint)
        canvas.drawText("RF TX", sw2Cx, h * 0.22f, lblPaint)

        drawVerticalRocker(canvas, sw1Cx, swTop, swW, swBot - swTop, StreamState.muteSpeaker, Color.parseColor("#00E676"))
        drawVerticalRocker(canvas, sw2Cx, swTop, swW, swBot - swTop, StreamState.streamToEsp32, Color.parseColor("#FFB300"))

        val rightL = w * 0.44f
        val rightR = w * 0.945f

        val rxBar = RectF(rightL, h * 0.09f, rightR, h * 0.27f)
        canvas.drawRoundRect(rxBar, 5f, 5f, housingPaint)

        val rxLedColor = when {
            StreamState.isEspConnected -> Color.parseColor("#00E676")
            StreamState.isWifiReady -> Color.parseColor("#FFB300")
            else -> Color.parseColor("#FF1744")
        }
        RackDraw.drawLed(canvas, rxBar.left + 16f, rxBar.centerY(), 5.5f, true, rxLedColor, Color.parseColor("#2A1212"))

        val rxStatusMsg = when {
            StreamState.isEspConnected -> "RX: ONLINE (${String.format("%.0f", StreamState.dataRateKbps)} KB/s)"
            StreamState.streamToEsp32 && StreamState.isWifiReady -> "RX: LINKING ${StreamState.espIp}..."
            StreamState.streamToEsp32 -> "RX: CONNECTING TO ESP32..."
            StreamState.isWifiReady -> "RX: WIFI TUNNEL READY"
            else -> "RX: OFF (FLIP RF TX ON)"
        }
        rxTextPaint.color = rxLedColor
        rxTextPaint.textSize = (rxBar.height() * 0.56f).coerceIn(10f, 16f)
        canvas.drawText(rxStatusMsg, rxBar.left + 32f, rxBar.centerY() + rxTextPaint.textSize * 0.35f, rxTextPaint)

        val winTop = h * 0.31f
        val winBot = h * 0.54f
        rectIp.set(rightL, winTop - 4f, rightR, winBot + 4f)
        canvas.drawRoundRect(rightL, winTop, rightR, winBot, 6f, 6f, housingPaint)

        val chars = StreamState.espIp.toCharArray()
        val count = chars.size.coerceAtLeast(1)
        val cellW = (rightR - rightL - 8f) / count
        digitPaint.textSize = ((winBot - winTop) * 0.66f).coerceIn(15f, 26f)

        for (i in chars.indices) {
            val dl = rightL + 4f + i * cellW + 1.5f
            val dRect = RectF(dl, winTop + 3f, dl + cellW - 3f, winBot - 3f)
            drumPaint.shader = LinearGradient(
                0f, dRect.top, 0f, dRect.bottom,
                intArrayOf(Color.parseColor("#18191B"), Color.parseColor("#3B3E42"), Color.parseColor("#151618")),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(dRect, 3f, 3f, drumPaint)
            canvas.drawText(chars[i].toString(), dRect.centerX(), dRect.centerY() + digitPaint.textSize * 0.35f, digitPaint)
        }

        rectEngage.set(rightL, h * 0.59f, rightR, h * 0.91f)
        btnFramePaint.shader = LinearGradient(0f, rectEngage.top, 0f, rectEngage.bottom, Color.parseColor("#6E7175"), Color.parseColor("#2B2D2F"), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rectEngage, 12f, 12f, btnFramePaint)
        canvas.drawRoundRect(rectEngage, 12f, 12f, btnBorder)

        val glass = RectF(rectEngage.left + 7f, rectEngage.top + 6f, rectEngage.right - 7f, rectEngage.bottom - 6f)
        btnGlassPaint.color = Color.parseColor("#141816")
        canvas.drawRoundRect(glass, 8f, 8f, btnGlassPaint)

        val active = StreamState.isRunning
        btnGlowPaint.shader = RadialGradient(
            glass.centerX(), glass.centerY(), glass.width() * 0.58f,
            if (active) intArrayOf(Color.parseColor("#CCFF1744"), Color.parseColor("#66B71C1C"), Color.TRANSPARENT)
            else intArrayOf(Color.parseColor("#CC00E676"), Color.parseColor("#6600695C"), Color.TRANSPARENT),
            floatArrayOf(0f, 0.58f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(glass, 8f, 8f, btnGlowPaint)

        engageTxtPaint.textSize = (glass.height() * 0.48f).coerceIn(15f, 25f)
        val btnLbl = if (active) "TX ACTIVE  [STOP]" else "TX ENGAGE"
        canvas.drawText(btnLbl, glass.centerX(), glass.centerY() + engageTxtPaint.textSize * 0.36f, engageTxtPaint)
    }

    private fun drawVerticalRocker(c: Canvas, cx: Float, top: Float, swW: Float, swH: Float, isOn: Boolean, ledColor: Int) {
        RackDraw.drawLed(c, cx, top - 11f, 5.5f, isOn, ledColor, Color.parseColor("#2A2D30"))

        val box = RectF(cx - swW / 2f, top, cx + swW / 2f, top + swH)
        c.drawRoundRect(box, 6f, 6f, housingPaint)

        val rocker = RectF(box.left + 5f, box.top + 5f, box.right - 5f, box.bottom - 5f)
        rockerPaint.shader = if (isOn) {
            LinearGradient(0f, rocker.top, 0f, rocker.bottom, Color.parseColor("#474A4E"), Color.parseColor("#18191B"), Shader.TileMode.CLAMP)
        } else {
            LinearGradient(0f, rocker.top, 0f, rocker.bottom, Color.parseColor("#18191B"), Color.parseColor("#474A4E"), Shader.TileMode.CLAMP)
        }
        c.drawRoundRect(rocker, 4f, 4f, rockerPaint)

        val barY = if (isOn) rocker.top + rocker.height() * 0.22f else rocker.bottom - rocker.height() * 0.22f
        notchPaint.color = if (isOn) ledColor else Color.parseColor("#54575B")
        c.drawRoundRect(cx - swW * 0.16f, barY - 3f, cx + swW * 0.16f, barY + 3f, 2f, 2f, notchPaint)
    }
}

class MainActivity : AppCompatActivity() {

    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var audioManager: AudioManager
    private lateinit var connectivityManager: ConnectivityManager
    private val uiHandler = Handler(Looper.getMainLooper())

    private lateinit var vuPanel: DualAnalogVuPanelView
    private lateinit var crestSubPanel: CrestAndSubBassPanelView
    private lateinit var cutoffGainPanel: CutoffAndGainPanelView
    private lateinit var matrixPanel: WordMatrix24FullPanelView
    private lateinit var spectrumPanel: SpectrumFullPanelView
    private lateinit var bottomDeck: BottomControlDeckView

    private val systemEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                StreamState.ACTION_REPOST_NOTIF -> {
                    if (StreamState.appAlive || StreamState.isRunning) {
                        RackNotificationHelper.showCurrent(this@MainActivity)
                    }
                }
                StreamState.ACTION_CLOSE_APP -> {
                    forceCloseApp()
                }
            }
        }
    }

    private val idleWatchdog = object : Runnable {
        override fun run() {
            if (!StreamState.appAlive) return
            val elapsed = SystemClock.elapsedRealtime() - StreamState.lastDataTransferMs
            if (elapsed >= StreamState.IDLE_TIMEOUT_MS) {
                forceCloseApp()
                return
            }
            uiHandler.postDelayed(this, 10_000L)
        }
    }

    private val uiUpdater = object : Runnable {
        override fun run() {
            if (StreamState.espWifiNetwork == null) {
                var wifiUp = false
                for (net in connectivityManager.allNetworks) {
                    val caps = connectivityManager.getNetworkCapabilities(net)
                    if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                        wifiUp = true
                        break
                    }
                }
                StreamState.isWifiReady = wifiUp
            } else {
                StreamState.isWifiReady = true
            }

            vuPanel.updateMeters()
            crestSubPanel.updateMeters()
            cutoffGainPanel.updateCutoff()
            matrixPanel.invalidate()
            spectrumPanel.invalidate()
            bottomDeck.invalidate()
            uiHandler.postDelayed(this, 33)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        StreamState.nativeMixerRate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48000

        StreamState.appAlive = true
        StreamState.lastDataTransferMs = SystemClock.elapsedRealtime()

        val filter = IntentFilter().apply {
            addAction(StreamState.ACTION_REPOST_NOTIF)
            addAction(StreamState.ACTION_CLOSE_APP)
        }
        ContextCompat.registerReceiver(
            this, systemEventReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )

        buildRivetedRackUi()
        requestNeededPermissions()
        RackNotificationHelper.showCurrent(this)
        uiHandler.postDelayed(idleWatchdog, 10_000L)
    }

    private fun forceCloseApp() {
        StreamState.appAlive = false
        uiHandler.removeCallbacks(idleWatchdog)
        uiHandler.removeCallbacks(uiUpdater)
        if (StreamState.isRunning) {
            stopCaptureService()
        }
        StreamState.releaseSpeakerMuteLock(audioManager)
        RackNotificationHelper.cancel(this)
        finishAndRemoveTask()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (StreamState.muteSpeaker && (
                event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
                event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
                event.keyCode == KeyEvent.KEYCODE_VOLUME_MUTE
            )) {
            StreamState.enforceSpeakerMuteLock(audioManager)
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_VOLUME_UP -> {
                        StreamState.outputGainDb = (StreamState.outputGainDb + 2.0f).coerceAtMost(0.0f)
                        if (StreamState.outputGainDb > -0.7f) StreamState.outputGainDb = 0.0f
                    }
                    KeyEvent.KEYCODE_VOLUME_DOWN -> {
                        StreamState.outputGainDb = (StreamState.outputGainDb - 2.0f).coerceAtLeast(-40.0f)
                    }
                }
                cutoffGainPanel.invalidate()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun buildRivetedRackUi() {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.parseColor("#17181A"))
        }

        val rack = RivetedRackContainer(this).apply {
            setPadding(dp(18), dp(10), dp(18), dp(12))
        }

        vuPanel = DualAnalogVuPanelView(this)
        rack.addView(vuPanel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.45f).apply {
            bottomMargin = dp(7)
            height = dp(152)
        })

        crestSubPanel = CrestAndSubBassPanelView(this)
        rack.addView(crestSubPanel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.92f).apply {
            bottomMargin = dp(7)
            height = dp(98)
        })

        cutoffGainPanel = CutoffAndGainPanelView(this) { }
        rack.addView(cutoffGainPanel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.05f).apply {
            bottomMargin = dp(7)
            height = dp(110)
        })

        matrixPanel = WordMatrix24FullPanelView(this)
        rack.addView(matrixPanel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 0.95f).apply {
            bottomMargin = dp(7)
            height = dp(102)
        })

        spectrumPanel = SpectrumFullPanelView(this) { btn ->
            when (btn) {
                "LAMP" -> StreamState.warmLampOn = !StreamState.warmLampOn
                "DIM" -> StreamState.dimActive = !StreamState.dimActive
                "MONO" -> StreamState.monoActive = !StreamState.monoActive
                "PEAK" -> StreamState.peakHoldActive = !StreamState.peakHoldActive
            }
        }
        rack.addView(spectrumPanel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.55f).apply {
            bottomMargin = dp(7)
            height = dp(164)
        })

        bottomDeck = BottomControlDeckView(
            this,
            onMuteToggle = { checked ->
                if (checked) StreamState.enforceSpeakerMuteLock(audioManager)
                else StreamState.releaseSpeakerMuteLock(audioManager)
            },
            onTxToggle = { checked ->
                if (checked) {
                    EspWifiBridge.connect(this)
                } else {
                    EspWifiBridge.disconnect(this)
                }
            },
            onEditIp = { showEditIpDialog() },
            onEngageTap = {
                if (StreamState.isRunning) stopCaptureService()
                else startMediaProjectionRequest()
            }
        )
        rack.addView(bottomDeck, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.25f).apply {
            height = dp(132)
        })

        scroll.addView(rack)
        setContentView(scroll)
    }

    private fun showEditIpDialog() {
        val input = EditText(this).apply {
            setText(StreamState.espIp)
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("Target ESP32 IP Address (Port 8888)")
            .setView(input)
            .setPositiveButton("SAVE") { _, _ ->
                val v = input.text.toString().trim()
                if (v.isNotEmpty()) {
                    StreamState.espIp = v
                    bottomDeck.invalidate()
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

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && StreamState.appAlive) {
            RackNotificationHelper.showCurrent(this)
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

    override fun onDestroy() {
        StreamState.appAlive = false
        uiHandler.removeCallbacks(idleWatchdog)
        try { unregisterReceiver(systemEventReceiver) } catch (_: Exception) {}
        if (!StreamState.isRunning) {
            RackNotificationHelper.cancel(this)
        }
        super.onDestroy()
    }
}

// ================================================================================
// BACKGROUND AUDIO ENGINE: 24-Bit Pre-DAC + Spectral Cutoff Scanner + Dynamic Crest
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
                    if (fmt != null && fmt.sampleRate > 0) sb.append("${fmt.sampleRate}Hz ")
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
        val notif = RackNotificationHelper.buildNotification(this, isTransmitting = true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(StreamState.NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(StreamState.NOTIF_ID, notif)
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
        StreamState.lastDataTransferMs = SystemClock.elapsedRealtime()

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
            var timedOut = false

            var longTermRms = 0.01

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
                        StreamState.crestFactorDb = 0.0
                        StreamState.subBassPunch = 0
                        StreamState.spectralCutoffKhz = 14.0f
                        StreamState.activeBitCount = 0
                        for (b in 0 until 24) StreamState.bitActiveFlags[b] = false
                        StreamState.hexPreview = "00 00 00 00 00 00"
                        StreamState.qualityVerdict = "PAUSED / STANDBY"
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

                    if (nowMs - StreamState.lastDataTransferMs >= StreamState.IDLE_TIMEOUT_MS) {
                        timedOut = true
                        break
                    }

                    Thread.sleep(6)
                    continue
                }

                zeroReadStreakMs = 0

                val effectiveDb = if (StreamState.dimActive) StreamState.outputGainDb - 15.0f else StreamState.outputGainDb
                val gainLinear = when {
                    effectiveDb <= -40.0f && !StreamState.dimActive -> 0.0f
                    effectiveDb >= -0.05f -> 1.0f
                    else -> 10.0.pow(effectiveDb / 20.0).toFloat()
                }
                val isMono = StreamState.monoActive

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

                    if (isMono) {
                        val m = (fL + fR) * 0.5f
                        fL = m
                        fR = m
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

                longTermRms += (maxRms - longTermRms) * 0.08
                val dbRmsL = if (rmsL > 0.00001) (20.0 * log10(rmsL)).toFloat() else -50f
                val dbRmsR = if (rmsR > 0.00001) (20.0 * log10(rmsR)).toFloat() else -50f
                val dbRms = if (maxRms > 0.0000001) 20.0 * log10(maxRms) else -100.0
                val dbPeak = if (peakAmp > 0.0000001) 20.0 * log10(peakAmp) else -100.0
                val dbBase = if (longTermRms > 0.0000001) 20.0 * log10(longTermRms) else -100.0

                StreamState.leftDb = dbRmsL
                StreamState.rightDb = dbRmsR
                StreamState.leftPeak = peakL > 0.65 || dbRmsL > -9f
                StreamState.rightPeak = peakR > 0.65 || dbRmsR > -9f
                StreamState.dbFs = dbRms

                val rawCrest = if (maxRms > 0.0001) (dbPeak - dbBase).coerceAtLeast(0.0) else 0.0
                StreamState.crestFactorDb = (rawCrest * 1.35).coerceIn(0.0, 20.0)

                StreamState.leftLevel = ((rmsL * 145).toInt()).coerceIn(0, 100)
                StreamState.rightLevel = ((rmsR * 145).toInt()).coerceIn(0, 100)
                StreamState.dataRateKbps = (lockedRate * 2 * 3) / 1000.0

                if (frameIdx == 1024) {
                    computeFft1024(fftReal, fftImag)
                    val binHz = lockedRate / 1024.0
                    var eSub = 0.0; var eBass = 0.0; var eMid = 0.0; var eTreble = 0.0
                    var eAir = 0.0; var eLossless = 0.0; var eUltrasonic = 0.0

                    var highestKhz = 14.0f
                    for (bin in 511 downTo 298) {
                        val mag = sqrt(fftReal[bin] * fftReal[bin] + fftImag[bin] * fftImag[bin]) / 512.0
                        if (mag > 0.0000018) {
                            highestKhz = (bin * binHz / 1000.0).toFloat().coerceIn(14.0f, 24.0f)
                            break
                        }
                    }
                    StreamState.spectralCutoffKhz = highestKhz

                    for (bin in 1 until 512) {
                        val freq = bin * binHz
                        val mag = sqrt(fftReal[bin] * fftReal[bin] + fftImag[bin] * fftImag[bin]) / 512.0

                        if (freq in 90.0..110.0 && mag > eSub) eSub = mag

                        when {
                            freq < 250.0 -> if (mag > eBass) eBass = mag
                            freq < 4000.0 -> if (mag > eMid) eMid = mag
                            freq < 12000.0 -> if (mag > eTreble) eTreble = mag
                            freq < 16000.0 -> if (mag > eAir) eAir = mag
                            freq < 20000.0 -> if (mag > eLossless) eLossless = mag
                            else -> if (mag > eUltrasonic) eUltrasonic = mag
                        }
                    }

                    StreamState.subBassPunch = magToProgress(eSub, 102.0)
                    StreamState.bandBass = magToProgress(eBass, 85.0)
                    StreamState.bandMid = magToProgress(eMid, 90.0)
                    StreamState.bandTreble = magToProgress(eTreble, 100.0)
                    StreamState.bandAir = magToProgress(eAir, 110.0)
                    StreamState.bandLossless = magToProgress(eLossless, 115.0)
                    StreamState.bandUltrasonic = magToProgress(eUltrasonic, 126.0)
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
                            if (socket != null) {
                                socket.tcpNoDelay = true
                                socket.connect(InetSocketAddress(StreamState.espIp, StreamState.espPort), 2000)
                                outStream = socket.getOutputStream()
                            }
                        }
                        if (outStream != null) {
                            outStream.write(pcm24Bytes)
                            StreamState.isEspConnected = true
                            StreamState.lastDataTransferMs = nowMs
                            lastKeepAliveMs = nowMs
                        }
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

                if (nowMs - StreamState.lastDataTransferMs >= StreamState.IDLE_TIMEOUT_MS) {
                    timedOut = true
                    break
                }
            }

            try { socket?.close() } catch (_: Exception) {}
            StreamState.isEspConnected = false

            if (timedOut) {
                StreamState.isRunning = false
                StreamState.appAlive = false
                sendBroadcast(Intent(StreamState.ACTION_CLOSE_APP).setPackage(packageName))
                Handler(Looper.getMainLooper()).post { cleanupAndStop() }
            }
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

    private fun createWifiBoundSocket(): Socket? {
        StreamState.espWifiNetwork?.let { net ->
            return net.socketFactory.createSocket()
        }
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        for (net in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(net)
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return net.socketFactory.createSocket()
            }
        }
        return null
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
        StreamState.crestFactorDb = 0.0
        StreamState.subBassPunch = 0
        StreamState.spectralCutoffKhz = 14.0f
        StreamState.activeBitCount = 0
        for (b in 0 until 24) StreamState.bitActiveFlags[b] = false
        StreamState.qualityVerdict = "STANDBY / READY"
        StreamState.statusText = "IDLE"

        stopForeground(STOP_FOREGROUND_DETACH)
        if (StreamState.appAlive) {
            RackNotificationHelper.showCurrent(this)
        } else {
            RackNotificationHelper.cancel(this)
        }
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