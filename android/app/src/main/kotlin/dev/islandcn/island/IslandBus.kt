package dev.islandcn.island

import android.os.Handler
import android.os.Looper
import io.flutter.plugin.common.EventChannel

object IslandBus {
    private val main = Handler(Looper.getMainLooper())
    private val pending = mutableListOf<Map<String, Any?>>()
    var sink: EventChannel.EventSink? = null
    private var owner: MainActivity? = null

    fun attach(activity: MainActivity) {
        owner = activity
    }

    fun detach(activity: MainActivity) {
        if (owner == activity) owner = null
    }

    fun emit(event: Map<String, Any?>) {
        main.post {
            val current = sink
            if (current == null) {
                pending.add(event)
            } else {
                current.success(event)
            }
        }
    }

    fun flush() {
        val queued = pending.toList()
        pending.clear()
        queued.forEach { sink?.success(it) }
    }
}
