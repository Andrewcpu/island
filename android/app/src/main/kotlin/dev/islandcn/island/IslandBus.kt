package dev.islandcn.island

import android.os.Handler
import android.os.Looper
import io.flutter.plugin.common.EventChannel

object IslandBus {
    private val main = Handler(Looper.getMainLooper())
    private val pending = mutableListOf<Map<String, Any?>>()
    private val sinks = LinkedHashSet<EventChannel.EventSink>()

    fun add(sink: EventChannel.EventSink) {
        sinks.add(sink)
        flush()
    }

    fun remove(sink: EventChannel.EventSink) {
        sinks.remove(sink)
    }

    fun emit(event: Map<String, Any?>) {
        main.post {
            if (sinks.isEmpty()) {
                pending.add(event)
                if (pending.size > 40) pending.removeAt(0)
            } else {
                for (sink in sinks.toList()) {
                    try {
                        sink.success(event)
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    fun flush() {
        if (sinks.isEmpty()) return
        val queued = pending.toList()
        pending.clear()
        for (event in queued) {
            for (sink in sinks.toList()) {
                try {
                    sink.success(event)
                } catch (_: Exception) {
                }
            }
        }
    }
}
