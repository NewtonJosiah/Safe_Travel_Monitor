package com.safetravelmonitor.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.widget.Toast

class SMSReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            
            for (message in messages) {
                val sender = message.originatingAddress
                val messageBody = message.messageBody
                val timestamp = message.timestampMillis

                handleIncomingSMS(context, sender, messageBody, timestamp)
            }
        }
    }

    private fun handleIncomingSMS(
        context: Context,
        sender: String?,
        messageBody: String,
        timestamp: Long
    ) {
        Toast.makeText(context, "Received SMS from $sender", Toast.LENGTH_SHORT).show()
    }
}
