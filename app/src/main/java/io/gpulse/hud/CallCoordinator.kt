package io.gpulse.hud

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object ContactLookup {
    fun nameFor(context: Context, number: String): String? {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return null
        return runCatching {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(number)
            )
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()
    }

    fun latestIncomingNumber(context: Context): String? {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return null
        return runCatching {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE),
                "${CallLog.Calls.TYPE} = ?",
                arrayOf(CallLog.Calls.INCOMING_TYPE.toString()),
                "${CallLog.Calls.DATE} DESC"
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val dateMs = cursor.getLong(1)
                    if (System.currentTimeMillis() - dateMs < 10_000L) {
                        cursor.getString(0)
                    } else null
                } else null
            }
        }.getOrNull()
    }
}

class CallCoordinator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val watchController: WatchController = WatchController.getInstance(context)
) {
    private var waitingForNumber: Job? = null
    private var alertedThisRing = false
    private var callAlertActive = false

    fun registerTelephonyListener() {}
    fun unregisterTelephonyListener() {}

    fun onRinging(number: String?) {
        if (alertedThisRing) return
        if (number.isNullOrBlank()) {
            if (waitingForNumber?.isActive == true) return
            waitingForNumber = scope.launch {
                // Ultra-short 80ms wait for second broadcast before hitting CallLog
                delay(80L)
                val fallbackNum = withContext(Dispatchers.IO) {
                    ContactLookup.latestIncomingNumber(context)
                }
                if (!fallbackNum.isNullOrBlank()) {
                    fire(fallbackNum)
                }
            }
            return
        }
        waitingForNumber?.cancel()
        fire(number)
    }

    fun onCallEndedOrAnswered(reason: String) {
        waitingForNumber?.cancel()
        alertedThisRing = false
        if (callAlertActive) {
            callAlertActive = false
            watchController.log("Call $reason — restoring time & clearing temporary call alarm.")
            watchController.restoreAfterCall()
        }
    }

    private fun fire(number: String?) {
        if (alertedThisRing) return
        alertedThisRing = true
        scope.launch {
            // 1. Check raw phone number digits FIRST before even querying Contacts DB for zero-ms matching
            val fastDigitSlot = findMatchingSlot(null, number)
            if (fastDigitSlot != null) {
                callAlertActive = true
                watchController.pointHandsForCall(fastDigitSlot, number ?: "VIP")
                return@launch
            }

            // 2. Otherwise resolve contact name on IO thread and match
            val contact = number?.let { withContext(Dispatchers.IO) { ContactLookup.nameFor(context, it) } }
            val label = contact ?: number ?: "Unknown caller"
            watchController.log("Incoming call detected: $label")

            val matchedSlot = findMatchingSlot(contact, number)
            if (matchedSlot != null) {
                callAlertActive = true
                watchController.pointHandsForCall(matchedSlot, label)
            } else {
                watchController.log("Caller '$label' does not match any VIP slot (12, 3, 6, 9).")
            }
        }
    }

    private fun findMatchingSlot(contactName: String?, rawNumber: String?): Int? {
        val slots = listOf(12, 3, 6, 9)
        val incomingDigits = rawNumber?.filter { it.isDigit() } ?: ""

        for (slot in slots) {
            val saved = Prefs.getVipContact(context, slot).trim()
            if (saved.isEmpty()) continue

            if (!contactName.isNullOrBlank() &&
                (contactName.contains(saved, ignoreCase = true) || saved.contains(contactName, ignoreCase = true))
            ) {
                return slot
            }

            val savedDigits = saved.filter { it.isDigit() }
            if (savedDigits.length >= 4 && incomingDigits.length >= 4) {
                if (incomingDigits.endsWith(savedDigits) ||
                    savedDigits.endsWith(incomingDigits) ||
                    incomingDigits.contains(savedDigits)
                ) {
                    return slot
                }
            }
        }
        return null
    }
}

class CallStateReceiver(private val coordinator: CallCoordinator) : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
            TelephonyManager.EXTRA_STATE_RINGING ->
                coordinator.onRinging(intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER))
            TelephonyManager.EXTRA_STATE_OFFHOOK ->
                coordinator.onCallEndedOrAnswered("answered")
            TelephonyManager.EXTRA_STATE_IDLE ->
                coordinator.onCallEndedOrAnswered("ended/missed")
        }
    }
}
