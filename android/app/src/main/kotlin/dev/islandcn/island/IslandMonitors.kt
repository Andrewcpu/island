package dev.islandcn.island

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.BatteryManager
import android.os.Build
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
    private var recorder: AudioRecord? = null
    @Volatile private var micRunning = false
    private var telephonyCallback: TelephonyCallback? = null

    fun cutouts(activity: MainActivity): List<Map<String, Double>> {
        val density = activity.resources.displayMetrics.density.toDouble()
        val cutout = activity.window.decorView.rootWindowInsets?.displayCutout ?: return emptyList()
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
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val app = context.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                IslandBus.emit(battery(app))
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            app.registerReceiver(receiver, filter)
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
        if (phoneWatching) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(TelephonyManager::class.java) ?: return
        phoneWatching = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    emitPhone(state, null)
                }
            }
            telephonyCallback = callback
            manager.registerTelephonyCallback(context.mainExecutor, callback)
        } else {
            @Suppress("DEPRECATION")
            manager.listen(object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    emitPhone(state, phoneNumber)
                }
            }, PhoneStateListener.LISTEN_CALL_STATE)
        }
    }

    private fun emitPhone(state: Int, number: String?) {
        val name = when (state) {
            TelephonyManager.CALL_STATE_RINGING -> "ringing"
            TelephonyManager.CALL_STATE_OFFHOOK -> "offhook"
            else -> "idle"
        }
        IslandBus.emit(mapOf("type" to "phone", "state" to name, "number" to (number ?: "")))
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
        LiveActivityService.useMic = true
        LiveActivityService.start(context, "Recording", "Microphone")
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
        LiveActivityService.useMic = false
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
