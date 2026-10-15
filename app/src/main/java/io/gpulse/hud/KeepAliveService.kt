package io.gpulse.hud

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the persistent watch link and listens for incoming calls.
 * Android keeps a foreground service alive far more reliably than a plain background process.
 */
class KeepAliveService : Service() {

    companion object {
        const val ACTION_STOP = "io.gpulse.hud.STOP"
        private const val CHANNEL_ID = "watch_link"
        private const val NOTIFICATION_ID = 17

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, KeepAliveService::class.java))
        }

        fun stop(context: Context) {
            Prefs.setKeepRunning(context, false)
            context.stopService(Intent(context, KeepAliveService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val watchController by lazy { WatchController.getInstance(applicationContext) }
    private var callCoordinator: CallCoordinator? = null
    private var callReceiver: BroadcastReceiver? = null
    private var bluetoothReceiver: BroadcastReceiver? = null
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification("Starting…"),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
            )
        } catch (e: Exception) {
            watchController.log("Could not start background service: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_STOP) {
            Prefs.setKeepRunning(this, false)
            stopSelf()
            return START_NOT_STICKY
        }

        val address = Prefs.getSavedAddress(this)
        Prefs.setKeepRunning(this, true)

        if (!running) {
            running = true
            registerCallReceiver()
            registerBluetoothReceiver()
            observeStateForNotification()
        }
        watchController.startPersistentConnection(address)
        return START_STICKY
    }

    override fun onDestroy() {
        callCoordinator?.unregisterTelephonyListener()
        callReceiver?.let { runCatching { unregisterReceiver(it) } }
        bluetoothReceiver?.let { runCatching { unregisterReceiver(it) } }
        callReceiver = null
        bluetoothReceiver = null
        callCoordinator = null
        scope.cancel()
        watchController.stop()
        super.onDestroy()
    }

    private fun registerCallReceiver() {
        val coordinator = CallCoordinator(applicationContext, scope, watchController)
        coordinator.registerTelephonyListener()
        callCoordinator = coordinator

        val receiver = CallStateReceiver(coordinator)
        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED
        )
        callReceiver = receiver
    }

    private fun registerBluetoothReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_OFF -> watchController.log("Bluetooth turned off")
                    BluetoothAdapter.STATE_ON -> {
                        watchController.log("Bluetooth turned on - reconnecting")
                        val address = Prefs.getSavedAddress(context)
                        watchController.startPersistentConnection(address)
                    }
                }
            }
        }
        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED
        )
        bluetoothReceiver = receiver
    }

    private fun observeStateForNotification() {
        scope.launch {
            watchController.connectionStatus.collect { status ->
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, buildNotification(status))
            }
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Watch link",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Shows that the app is holding the link to your watch" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, KeepAliveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("G-Pulse")
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Stop", stopIntent)
            .build()
    }
}
