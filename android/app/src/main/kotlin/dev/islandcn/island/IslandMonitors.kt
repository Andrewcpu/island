package dev.islandcn.island

import android.Manifest
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.view.Display
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import kotlin.math.roundToInt
import android.provider.CalendarContract
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import kotlin.math.sqrt

object IslandMonitors {
    private var phoneWatching = false
    private var batteryWatching = false
    private var screenWatching = false
    private var keyguardWatching = false
    private var torchWatching = false
    private var keyguard: KeyguardManager? = null
    private var keyguardLocked: Boolean? = null
    private var camera: CameraManager? = null
    private var torchId: String? = null
    private var torchMax = 1
    private var torchHardwareOn = false
    private var torchStep = -1
    private val mainHandler = Handler(Looper.getMainLooper())
    /** Fully on. Doze, off, and AOD are not. */
    private var awake = false
    private var dreaming = false
    private var displayState = Display.STATE_UNKNOWN
    private var wasCharging = false
    private var batteryBaseline = false
    private var recorder: AudioRecord? = null
    @Volatile private var micRunning = false
    private var telephonyCallback: TelephonyCallback? = null
    private var phoneContext: Context? = null
    private var callState = "idle"
    private var callerLabel = ""
    private var pendingLabel = ""
    private val genericCaller = setOf("incoming call", "incoming", "phone", "call", "mobile")

    fun display(context: Context): Map<String, Any> {
        val density = context.resources.displayMetrics.density.toDouble().coerceAtLeast(1.0)
        val metrics = context.getSystemService(android.view.WindowManager::class.java).currentWindowMetrics
        val bounds = metrics.bounds
        return mapOf(
            "width" to bounds.width() / density,
            "height" to bounds.height() / density,
            "cutouts" to cutouts(context),
        )
    }

    fun cutouts(context: Context): List<Map<String, Double>> {
        val density = context.resources.displayMetrics.density.toDouble().coerceAtLeast(1.0)
        val cutout = context.getSystemService(android.view.WindowManager::class.java)
            .currentWindowMetrics.windowInsets.displayCutout ?: return emptyList()
        return cutout.boundingRects.map {
            mapOf(
                "left" to it.left / density,
                "top" to it.top / density,
                "right" to it.right / density,
                "bottom" to it.bottom / density,
            )
        }
    }

    fun battery(context: Context): Map<String, Any?> {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val manager = context.getSystemService(BatteryManager::class.java)
        val remaining = manager.computeChargeTimeRemaining()
        val minutes = if (remaining > 0) (remaining / 60000L).toInt() else null
        return mapOf(
            "type" to "battery",
            "level" to level,
            "scale" to scale,
            "charging" to charging,
            "full" to (status == BatteryManager.BATTERY_STATUS_FULL),
            "plugged" to (plugged != 0),
            "fullInMinutes" to minutes,
        )
    }

    fun watchBattery(context: Context) {
        if (batteryWatching) return
        batteryWatching = true
        val app = context.applicationContext
        val initial = battery(app)
        wasCharging = initial["charging"] == true || initial["plugged"] == true
        batteryBaseline = true
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                IslandBus.emit(battery(app))
                when (intent?.action) {
                    Intent.ACTION_POWER_CONNECTED -> onPower(app, connected = true, sticky = null)
                    Intent.ACTION_POWER_DISCONNECTED -> onPower(app, connected = false, sticky = null)
                    Intent.ACTION_BATTERY_CHANGED -> onPower(app, connected = null, sticky = intent)
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            app.registerReceiver(receiver, filter)
        }
    }

    /**
     * The border comes on only when the display is fully on and no dream is running.
     * Screen off, AOD, and the screensaver stay borderless.
     * The peek is not tied to screen-on or user-present. It runs inside the pill,
     * when the border actually appears. A pill that already had a border does not peek.
     * The first reading only records the current state.
     */
    fun screenLit(): Boolean = awake && !dreaming

    fun watchScreen(context: Context) {
        if (screenWatching) return
        screenWatching = true
        val app = context.applicationContext
        val power = app.getSystemService(PowerManager::class.java)
        displayState = displayState(app)
        awake = power?.isInteractive == true && displayState == Display.STATE_ON
        dreaming = dreamRunning(app)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_DREAMING_STARTED)
            addAction(Intent.ACTION_DREAMING_STOPPED)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        awake = false
                        IslandOverlayService.syncChrome()
                        refreshLock()
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        awake = true
                        IslandOverlayService.syncChrome()
                        refreshLock()
                    }
                    Intent.ACTION_DREAMING_STARTED -> {
                        dreaming = true
                        IslandOverlayService.syncChrome()
                    }
                    Intent.ACTION_DREAMING_STOPPED -> {
                        dreaming = false
                        awake = displayState(app) == Display.STATE_ON && power?.isInteractive != false
                        IslandOverlayService.syncChrome()
                    }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            app.registerReceiver(receiver, filter)
        }
        watchDisplay(app)
    }

    /**
     * The legacy listener does not deliver display-state changes on current
     * platform releases, so AOD leaving doze never reached the pill.
     */
    private fun watchDisplay(app: Context) {
        val displays = app.getSystemService(DisplayManager::class.java) ?: return
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                if (displayId != Display.DEFAULT_DISPLAY) return
                noteDisplay(app)
            }
        }
        if (Build.VERSION.SDK_INT >= 36) {
            displays.registerDisplayListener(
                app.mainExecutor,
                DisplayManager.EVENT_TYPE_DISPLAY_STATE or DisplayManager.EVENT_TYPE_DISPLAY_CHANGED,
                listener,
            )
        } else {
            displays.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        }
    }

    private fun noteDisplay(app: Context) {
        val next = displayState(app)
        if (next == displayState) return
        displayState = next
        awake = when {
            isDark(next) -> false
            next == Display.STATE_ON -> true
            else -> awake
        }
        IslandOverlayService.syncChrome()
    }

    private fun displayState(app: Context): Int =
        app.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
            ?.state
            ?: Display.STATE_UNKNOWN

    private fun isDark(state: Int): Boolean =
        state == Display.STATE_OFF || state == Display.STATE_DOZE || state == Display.STATE_DOZE_SUSPEND

    /**
     * Lock screen is its own activity. Unlock removes it. This does not peek.
     * isKeyguardLocked() needs no special permission. The locked-state listener
     * does, and a normal app cannot hold it, so a short poll covers the gap
     * between user-present and screen-on.
     */
    fun watchKeyguard(context: Context) {
        if (keyguardWatching) return
        keyguardWatching = true
        val app = context.applicationContext
        keyguard = app.getSystemService(KeyguardManager::class.java)
        refreshLock()
        mainHandler.post(lockPoll)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_USER_UNLOCKED)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                refreshLock()
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            app.registerReceiver(receiver, filter)
        }
    }

    private val lockPoll = object : Runnable {
        override fun run() {
            refreshLock()
            val delay = if (keyguardLocked != false) 200L else 1000L
            mainHandler.postDelayed(this, delay)
        }
    }

    fun refreshLock() {
        val locked = keyguard?.isKeyguardLocked == true
        if (keyguardLocked == locked) return
        keyguardLocked = locked
        IslandOverlayService.onKeyguard(locked)
    }

    /**
     * Torch on is an activity until it turns off. Strength uses the camera API when
     * the hardware reports more than one level; otherwise the slider is on or off.
     */
    fun watchTorch(context: Context) {
        if (torchWatching) return
        torchWatching = true
        val manager = context.applicationContext.getSystemService(CameraManager::class.java) ?: return
        camera = manager
        val id = try {
            manager.cameraIdList.firstOrNull { candidate ->
                val chars = manager.getCameraCharacteristics(candidate)
                chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (_: Exception) {
            null
        } ?: return
        torchId = id
        torchMax = torchMaximum(manager, id)
        manager.registerTorchCallback(object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                if (cameraId != torchId) return
                torchHardwareOn = enabled
                if (!enabled) {
                    IslandOverlayService.onTorch(0f, false)
                    return
                }
                val fraction = if (torchMax <= 1) 1f else {
                    val strength = try {
                        manager.getTorchStrengthLevel(cameraId)
                    } catch (_: CameraAccessException) {
                        torchMax
                    }
                    strength.toFloat() / torchMax.toFloat()
                }
                IslandOverlayService.onTorch(fraction.coerceIn(0f, 1f), true)
            }

            override fun onTorchStrengthLevelChanged(cameraId: String, newStrengthLevel: Int) {
                if (cameraId != torchId || torchMax <= 1) return
                torchHardwareOn = true
                IslandOverlayService.onTorch((newStrengthLevel.toFloat() / torchMax.toFloat()).coerceIn(0f, 1f), true)
            }
        }, mainHandler)
    }

    fun setTorchFraction(fraction: Float) {
        val manager = camera ?: return
        val id = torchId ?: return
        val level = fraction.coerceIn(0f, 1f)
        try {
            if (level < 0.04f) {
                if (torchHardwareOn) manager.setTorchMode(id, false)
                torchHardwareOn = false
                torchStep = 0
                IslandOverlayService.onTorch(0f, false)
                return
            }
            if (torchMax <= 1) {
                if (torchHardwareOn) return
                manager.setTorchMode(id, true)
                torchHardwareOn = true
                return
            }
            val step = (level * torchMax).roundToInt().coerceIn(1, torchMax)
            if (step == torchStep && torchHardwareOn) return
            if (Build.VERSION.SDK_INT >= 33) manager.turnOnTorchWithStrengthLevel(id, step)
            else manager.setTorchMode(id, true)
            torchHardwareOn = true
            torchStep = step
        } catch (error: Exception) {
            Log.w("IslandOverlay", "Torch level failed", error)
        }
    }

    private fun torchMaximum(manager: CameraManager, id: String): Int {
        if (Build.VERSION.SDK_INT < 33) return 1
        return try {
            val reported = manager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 1
            reported.coerceAtLeast(1)
        } catch (_: CameraAccessException) {
            1
        }
    }

    /** DreamManager.isDreaming() is hidden. A miss just waits for the broadcasts. */
    private fun dreamRunning(app: Context): Boolean {
        return try {
            val dream = app.getSystemService("dream") ?: return false
            dream.javaClass.getMethod("isDreaming").invoke(dream) == true
        } catch (_: Throwable) {
            false
        }
    }

    /** Plug-in shows the battery island. Unplug leaves it. A baseline reading does not. */
    private fun onPower(app: Context, connected: Boolean?, sticky: Intent?) {
        val level: Int
        val scale: Int
        val plugged: Boolean
        if (sticky != null) {
            level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
            scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            plugged = sticky.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        } else {
            val snap = battery(app)
            level = (snap["level"] as? Number)?.toInt() ?: 0
            scale = (snap["scale"] as? Number)?.toInt()?.coerceAtLeast(1) ?: 100
            plugged = snap["plugged"] == true
        }
        val percent = (level * 100 / scale).coerceIn(0, 100)
        val charging = when (connected) {
            true -> true
            false -> false
            null -> plugged
        }
        if (!batteryBaseline) {
            wasCharging = charging
            batteryBaseline = true
            return
        }
        val started = charging && !wasCharging
        val stopped = !charging && wasCharging
        wasCharging = charging
        if (stopped) {
            IslandOverlayService.onBatteryCleared()
            return
        }
        if (started || (charging && IslandOverlayService.batteryLive())) {
            IslandOverlayService.onBattery(app, percent, true)
        }
    }

    fun calendar(context: Context): Map<String, Any?> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return mapOf("events" to emptyList<Map<String, Any?>>(), "denied" to true)
        }
        val now = System.currentTimeMillis()
        val end = now + 18L * 60L * 60L * 1000L
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(now.toString())
            .appendPath(end.toString())
            .build()
        val events = mutableListOf<Map<String, Any?>>()
        context.contentResolver.query(
            uri,
            arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.EVENT_LOCATION),
            null,
            null,
            "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { cursor ->
            val title = cursor.getColumnIndex(CalendarContract.Instances.TITLE)
            val begin = cursor.getColumnIndex(CalendarContract.Instances.BEGIN)
            val finish = cursor.getColumnIndex(CalendarContract.Instances.END)
            val location = cursor.getColumnIndex(CalendarContract.Instances.EVENT_LOCATION)
            while (cursor.moveToNext() && events.size < 8) {
                events.add(
                    mapOf(
                        "title" to (if (title >= 0) cursor.getString(title) else null),
                        "start" to (if (begin >= 0) cursor.getLong(begin) else 0L),
                        "end" to (if (finish >= 0) cursor.getLong(finish) else 0L),
                        "location" to (if (location >= 0) cursor.getString(location) else ""),
                    ),
                )
            }
        }
        return mapOf("events" to events)
    }

    fun watchPhone(context: Context) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return
        val app = context.applicationContext
        phoneContext = app
        if (phoneWatching) {
            val manager = app.getSystemService(TelephonyManager::class.java) ?: return
            @Suppress("DEPRECATION")
            emitPhone(manager.callState, null)
            return
        }
        val manager = app.getSystemService(TelephonyManager::class.java) ?: return
        phoneWatching = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    emitPhone(state, null)
                }
            }
            telephonyCallback = callback
            manager.registerTelephonyCallback(app.mainExecutor, callback)
        } else {
            @Suppress("DEPRECATION")
            manager.listen(object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    emitPhone(state, phoneNumber)
                }
            }, PhoneStateListener.LISTEN_CALL_STATE)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
                val extra = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
                val state = when (extra) {
                    TelephonyManager.EXTRA_STATE_RINGING -> TelephonyManager.CALL_STATE_RINGING
                    TelephonyManager.EXTRA_STATE_OFFHOOK -> TelephonyManager.CALL_STATE_OFFHOOK
                    else -> TelephonyManager.CALL_STATE_IDLE
                }
                emitPhone(state, intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER))
            }
        }
        val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else app.registerReceiver(receiver, filter)
        @Suppress("DEPRECATION")
        emitPhone(manager.callState, null)
    }

    private fun emitPhone(state: Int, number: String?) {
        val name = when (state) {
            TelephonyManager.CALL_STATE_RINGING -> "ringing"
            TelephonyManager.CALL_STATE_OFFHOOK -> "offhook"
            else -> "idle"
        }
        val host = phoneContext
        if (name == "idle") {
            callerLabel = ""
            pendingLabel = ""
        }
        callState = name
        if (!number.isNullOrBlank() && name != "idle") adoptCaller(number)
        else if (name != "idle" && callerLabel.isEmpty() && pendingLabel.isNotEmpty()) callerLabel = pendingLabel
        IslandBus.emit(mapOf("type" to "phone", "state" to name, "number" to callerLabel))
        if (host != null) IslandOverlayService.onCall(host, name, if (name == "idle") "" else callerLabel)
    }

    fun callActive(): Boolean = callState == "ringing" || callState == "offhook"

    fun noteCaller(context: Context, raw: String?, ongoing: Boolean) {
        phoneContext = phoneContext ?: context.applicationContext
        if (callState == "idle") {
            if (ongoing) adoptPending(raw)
            return
        }
        if (!adoptCaller(raw)) return
        val host = phoneContext ?: return
        IslandOverlayService.onCall(host, callState, callerLabel)
    }

    private fun adoptPending(raw: String?) {
        val candidate = raw?.trim().orEmpty()
        if (candidate.isEmpty() || genericCaller.contains(candidate.lowercase())) return
        pendingLabel = if (looksLikeNumber(candidate)) contactName(candidate) ?: candidate else candidate
    }

    private fun adoptCaller(raw: String?): Boolean {
        val candidate = raw?.trim().orEmpty()
        if (candidate.isEmpty()) return false
        val resolved = if (looksLikeNumber(candidate)) contactName(candidate) ?: candidate else candidate
        if (resolved.equals(callerLabel, ignoreCase = true)) return false
        val generic = genericCaller.contains(resolved.lowercase())
        val currentGeneric = callerLabel.isEmpty() || genericCaller.contains(callerLabel.lowercase())
        if (generic && !currentGeneric) return false
        if (!currentGeneric && looksLikeNumber(resolved) && !looksLikeNumber(callerLabel)) return false
        callerLabel = resolved
        return true
    }

    private fun contactName(number: String): String? {
        val context = phoneContext ?: return null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return try {
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return null
                cursor.getString(0)?.trim()?.ifEmpty { null }
            }
        } catch (_: SecurityException) {
            null
        }
    }

    private fun looksLikeNumber(value: String): Boolean {
        val digits = value.count { it.isDigit() }
        if (digits < 3) return false
        return value.all { it.isDigit() || it == '+' || it == '-' || it == '(' || it == ')' || it == ' ' || it == '.' }
    }

    fun acceptCall(context: Context): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) return false
        return try {
            val telecom = context.getSystemService(TelecomManager::class.java)
            telecom?.acceptRingingCall()
            true
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    fun endCall(context: Context): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) return false
        return try {
            val telecom = context.getSystemService(TelecomManager::class.java) ?: return false
            val method = telecom.javaClass.getMethod("endCall")
            method.invoke(telecom) as? Boolean ?: true
        } catch (_: Exception) {
            false
        }
    }

    fun startMic(context: Context): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        IslandOverlayService.setMic(context, true)
        if (micRunning) return true
        val rate = 16000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return false
        val record = AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 2)
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }
        recorder = record
        micRunning = true
        record.startRecording()
        Thread {
            val buffer = ShortArray(min)
            while (micRunning) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) {
                    var sum = 0.0
                    for (i in 0 until read) sum += buffer[i] * buffer[i]
                    val rms = sqrt(sum / read) / 32768.0
                    IslandBus.emit(mapOf("type" to "mic", "level" to rms.coerceIn(0.0, 1.0)))
                }
            }
        }.start()
        return true
    }

    fun stopMic() {
        IslandOverlayService.setMic(context = null, on = false)
        micRunning = false
        recorder?.run {
            try {
                stop()
            } catch (_: IllegalStateException) {
            }
            release()
        }
        recorder = null
    }

    fun setSpeaker(context: Context, speaker: Boolean) {
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        audio.mode = if (speaker) AudioManager.MODE_IN_COMMUNICATION else AudioManager.MODE_NORMAL
        audio.isSpeakerphoneOn = speaker
    }

    fun mediaAction(action: String) {
        val controls = IslandNotificationListener.controls ?: return
        when (action) {
            "play" -> controls.play()
            "pause" -> controls.pause()
            "next" -> controls.skipToNext()
            "previous" -> controls.skipToPrevious()
        }
    }
}
