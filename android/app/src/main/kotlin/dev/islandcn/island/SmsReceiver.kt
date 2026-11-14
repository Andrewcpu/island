package dev.islandcn.island

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isEmpty()) return
        val sender = messages.first().displayOriginatingAddress ?: "SMS"
        val body = messages.joinToString(separator = "") { it.displayMessageBody ?: "" }
        IslandBus.emit(
            mapOf(
                "type" to "message",
                "sender" to sender,
                "body" to body,
                "app" to "SMS",
            ),
        )
    }
}
