package org.fossify.messages.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.fossify.commons.helpers.ensureBackgroundThread

/**
 * Debug builds only. Feeds a fabricated message through the same path as a real SMS, so the spam filter
 * can be tested without a second phone:
 *
 *   adb shell am broadcast -a org.fossify.messages.debug.INJECT_SMS -p org.fossify.messages.debug \
 *       --es address +15550100 --es body "Test. Reply STOP to unsubscribe."
 */
class DebugSmsInjector : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val address = intent.getStringExtra("address").orEmpty()
        val body = intent.getStringExtra("body").orEmpty()
        if (address.isBlank() || body.isBlank()) {
            Log.w(TAG, "INJECT_SMS needs --es address and --es body")
            return
        }
        Log.i(TAG, "injecting test message from $address")

        val pending = goAsync()
        val appContext = context.applicationContext
        ensureBackgroundThread {
            try {
                SmsReceiver().process(
                    appContext = appContext,
                    address = address,
                    subject = "",
                    body = body,
                    status = -1,
                    subscriptionId = -1
                )
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "DebugSmsInjector"
    }
}
