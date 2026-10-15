package io.gpulse.hud

import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.avmedia.gshockapi.GShockAPI
import org.avmedia.gshockapi.ProgressEvents
import org.avmedia.gshockapi.WatchInfo
import org.avmedia.gshockapi.model.Alarm
import org.avmedia.gshockapi.model.Settings
import org.avmedia.gshockapi.io.CachedIO
import org.avmedia.gshockapi.io.TimeIO
import org.avmedia.gshockapi.io.WatchConditionIO
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.min

data class AlarmEntry(
    val hour: Int,
    val minute: Int,
    val enabled: Boolean,
    val hasHourlyChime: Boolean = false
)

data class WatchConfigState(
    val buttonToneMuted: Boolean = false,
    val autoLight: Boolean = false,
    val timeFormat24h: Boolean = false,
    val powerSaving: Boolean = true,
    val loaded: Boolean = false
)

class WatchController private constructor(appContext: Context) {

    private val context: Context = appContext.applicationContext
    val api = GShockAPI(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Every BLE write goes through this so operations never interleave. */
    private val watchLock = Mutex()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    private val _connectionStatus = MutableStateFlow("Disconnected")
    val connectionStatus: StateFlow<String> = _connectionStatus

    private val _batteryLevel = MutableStateFlow<Int?>(null)
    val batteryLevel: StateFlow<Int?> = _batteryLevel

    private val _detectedModelName = MutableStateFlow<String?>(Prefs.getSavedName(context))
    val detectedModelName: StateFlow<String?> = _detectedModelName

    private val _alarms = MutableStateFlow<List<AlarmEntry>>(
        List(5) { AlarmEntry(hour = 7, minute = 0, enabled = false, hasHourlyChime = false) }
    )
    val alarms: StateFlow<List<AlarmEntry>> = _alarms

    private val _watchConfig = MutableStateFlow(WatchConfigState())
    val watchConfig: StateFlow<WatchConfigState> = _watchConfig

    private val _settingsStatus = MutableStateFlow<String?>(null)
    val settingsStatus: StateFlow<String?> = _settingsStatus

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs

    private var connectionLoopJob: Job? = null
    private var keepAliveJob: Job? = null
    private var activeOpJob: Job? = null

    @Volatile private var stopRequested = false
    @Volatile private var timezoneSynced = false
    @Volatile private var lastBleActivityMs: Long = 0L

    private var clockDirty: Boolean
        get() = prefs.getBoolean(KEY_CLOCK_DIRTY, false)
        set(value) {
            prefs.edit().putBoolean(KEY_CLOCK_DIRTY, value).apply()
        }

    companion object {
        private const val TAG = "WatchController"
        private const val PREFS_NAME = "watch_controller"
        private const val KEY_CLOCK_DIRTY = "clock_dirty"
        private const val INTERNAL_FALLBACK_MODEL = "CASIO GBM-2100"

        private const val MAX_LOG_LINES = 80
        private const val OP_TIMEOUT_MS = 12_000L
        private const val WRITE_ATTEMPTS = 2
        private const val WRITE_RETRY_DELAY_MS = 250L
        private const val KEEP_ALIVE_INTERVAL_MS = 12_000L

        private const val CONNECT_WAIT_MS = 12_000L
        private const val CONNECT_POLL_MS = 400L
        private const val CONNECTED_POLL_MS = 2_500L
        private const val RECONNECT_MIN_MS = 2_000L
        private const val RECONNECT_MAX_MS = 30_000L

        @Volatile
        private var instance: WatchController? = null

        fun getInstance(context: Context): WatchController {
            return instance ?: synchronized(this) {
                instance ?: WatchController(context).also { instance = it }
            }
        }
    }

    init {
        subscribeToConnectionEvents()
    }

    fun log(msg: String) {
        Log.d(TAG, msg)
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        _logs.update { (listOf("[$ts] $msg") + it).take(MAX_LOG_LINES) }
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    private fun resolveDisplayModel(): String {
        val fromWatchInfo = runCatching { WatchInfo.name }.getOrNull()
            ?.takeIf { it.isNotBlank() && !it.equals("Unknown", ignoreCase = true) }
        val saved = Prefs.getSavedName(context)?.takeIf { it.isNotBlank() }
        val resolved = fromWatchInfo ?: saved
        if (resolved != null) {
            _detectedModelName.value = resolved
            val addr = Prefs.getSavedAddress(context)
            if (!addr.isNullOrBlank() && saved != resolved) {
                Prefs.saveWatch(context, addr, resolved)
            }
            return resolved
        }
        return "CasiOak"
    }

    private fun subscribeToConnectionEvents() {
        ProgressEvents.subscriber.start(this.javaClass.canonicalName ?: TAG, { event ->
            when (event) {
                ProgressEvents["ConnectionStarted"] -> {
                    _connectionStatus.value = "Connecting to ${resolveDisplayModel()}..."
                    log("BLE ConnectionStarted")
                }
                ProgressEvents["ConnectionSetupComplete"] -> {
                    _connectionStatus.value = "Connected (Setting up GATT...)"
                    log("BLE ConnectionSetupComplete")
                }
                ProgressEvents["WatchInitializationCompleted"] -> onWatchReady()
                ProgressEvents["Disconnect"], ProgressEvents["ConnectionFailed"] -> onWatchLost()
            }
        }, { throwable ->
            log("Event error: ${throwable.message}")
        })
    }

    private fun onWatchReady() {
        timezoneSynced = false
        lastBleActivityMs = System.currentTimeMillis()
        _isConnected.value = true
        val model = resolveDisplayModel()
        _connectionStatus.value = "Connected & Ready"
        log("$model Connected! Pre-warming HUD link...")

        scope.launch {
            delay(1_000L)
            if (api.isConnected() && !stopRequested) {
                runWatchOp("Pre-sync timezone") {
                    ensureTimezoneSynced()
                }
                if (clockDirty) {
                    syncRealTime()
                }
                sendKeepAlivePing()
                loadWatchFeaturesFromWatch()
            }
        }
    }

    private fun onWatchLost() {
        timezoneSynced = false
        _batteryLevel.value = null
        _isConnected.value = false
        _connectionStatus.value =
            if (stopRequested) "Disconnected" else "Disconnected (Auto-Reconnecting...)"
        log(if (clockDirty) "Watch disconnected. Real time will be restored on reconnect." else "Watch disconnected.")
    }

    fun startPersistentConnection(savedAddress: String?) {
        stopRequested = false
        connectionLoopJob?.cancel()
        connectionLoopJob = scope.launch {
            var backoffMs = RECONNECT_MIN_MS
            while (isActive) {
                if (api.isConnected()) {
                    backoffMs = RECONNECT_MIN_MS
                    val idleMs = System.currentTimeMillis() - lastBleActivityMs
                    if (idleMs >= KEEP_ALIVE_INTERVAL_MS && !watchLock.isLocked) {
                        keepAliveJob = launch { sendKeepAlivePing() }
                    }
                    delay(CONNECTED_POLL_MS)
                    continue
                }

                _isConnected.value = false
                val connected = try {
                    attemptConnection(savedAddress)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("Connection loop error: ${e.message}")
                    false
                }

                if (!connected) {
                    delay(backoffMs)
                    backoffMs = min(backoffMs * 2, RECONNECT_MAX_MS)
                }
            }
        }
    }

    private suspend fun attemptConnection(savedAddress: String?): Boolean {
        cleanupDeadGatt(savedAddress)
        CachedIO.clearCache()

        val savedName = Prefs.getSavedName(context)?.takeIf { it.startsWith("CASIO", ignoreCase = true) }
        WatchInfo.setNameAndModel(savedName ?: INTERNAL_FALLBACK_MODEL)

        val displayModel = Prefs.getSavedName(context)?.takeIf { it.isNotBlank() } ?: "CasiOak"
        if (!savedAddress.isNullOrBlank()) {
            WatchInfo.setAddress(savedAddress)
            _connectionStatus.value = "Waiting for $displayModel..."
            api.waitForConnection(savedAddress)
        } else {
            _connectionStatus.value = "Waiting for your CasiOak..."
            api.waitForConnection("")
        }

        var waitedMs = 0L
        while (scope.isActive && !api.isConnected() && waitedMs < CONNECT_WAIT_MS) {
            delay(CONNECT_POLL_MS)
            waitedMs += CONNECT_POLL_MS
        }

        if (!api.isConnected()) return false
        _isConnected.value = true
        resolveDisplayModel()
        _connectionStatus.value = "Connected & Ready"
        return true
    }

    fun stop() {
        stopRequested = true
        keepAliveJob?.cancel()
        connectionLoopJob?.cancel()
        connectionLoopJob = null
        _isConnected.value = false
        _connectionStatus.value = "Disconnected"

        scope.launch {
            if (clockDirty) runWatchOp("Restore time") { restoreClock() }
            try {
                api.disconnect()
            } catch (e: Exception) {
                log("Disconnect error: ${e.message}")
            }
            log("Connection stopped.")
        }
    }

    fun disconnectWithRealTimeSync(onComplete: () -> Unit = {}) {
        stopRequested = true
        keepAliveJob?.cancel()
        connectionLoopJob?.cancel()
        connectionLoopJob = null
        _connectionStatus.value = "Syncing real time (disconnecting in 5s)..."
        log("Disconnect requested: syncing real time first...")

        scope.launch {
            if (api.isConnected()) {
                runWatchOp("Restore time before disconnect") {
                    restoreClock()
                }
                delay(5_000L)
            }
            try {
                api.disconnect()
            } catch (e: Exception) {
                log("Disconnect error: ${e.message}")
            }
            _isConnected.value = false
            _batteryLevel.value = null
            _connectionStatus.value = "Disconnected"
            log("Disconnected cleanly after 5s real-time sync.")
            onComplete()
        }
    }

    private fun cleanupDeadGatt(address: String?) {
        if (address.isNullOrBlank()) return
        try {
            val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val dev = bm.adapter?.getRemoteDevice(address)
            if (dev != null) api.teardownConnection(dev)
        } catch (e: Exception) {
            Log.w(TAG, "cleanupDeadGatt failed: ${e.message}")
        }
    }

    private suspend fun sendKeepAlivePing() {
        if (!watchLock.tryLock()) return
        try {
            if (!api.isConnected()) return
            withTimeout(4_000L) {
                CachedIO.remove("28")
                CachedIO.remove("10")
                val condition = WatchConditionIO.request()
                _batteryLevel.value = condition.batteryLevel
            }
            lastBleActivityMs = System.currentTimeMillis()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Keep-alive ping warning: ${e.message}")
        } finally {
            watchLock.unlock()
        }
    }

    private suspend fun runWatchOp(label: String, block: suspend () -> Unit) {
        watchLock.withLock {
            if (!api.isConnected()) {
                log("$label skipped (watch not connected)")
                return
            }
            try {
                withTimeout(OP_TIMEOUT_MS) {
                    lastBleActivityMs = System.currentTimeMillis()
                    try {
                        block()
                    } finally {
                        lastBleActivityMs = System.currentTimeMillis()
                    }
                }
            } catch (e: TimeoutCancellationException) {
                log("$label timed out")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("$label failed: ${e.message}")
            }
        }
    }

    private fun launchPriorityWatchOp(label: String, block: suspend () -> Unit): Job {
        keepAliveJob?.cancel()
        activeOpJob?.cancel()
        return scope.launch { runWatchOp(label, block) }.also { activeOpJob = it }
    }

    private suspend fun ensureTimezoneSynced() {
        if (!timezoneSynced) {
            runCatching {
                TimeIO.setTimezone(TimeZone.getDefault().id)
                timezoneSynced = true
            }
        }
    }

    private suspend fun writeWatchTimeFast(epochMs: () -> Long) {
        var attempt = 0
        while (true) {
            try {
                ensureTimezoneSynced()
                TimeIO.set(epochMs())
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (++attempt >= WRITE_ATTEMPTS) throw e
                timezoneSynced = false
                CachedIO.clearCache()
                log("Time write retry (${e.message})...")
                delay(WRITE_RETRY_DELAY_MS)
            }
        }
    }

    private suspend fun setHandsToQuadrant(slot: Int, callerLabel: String) {
        val (hour, minute, labelText) = when (slot) {
            12 -> Triple(12, 0, "12:00 (12 o'clock)")
            3 -> Triple(3, 15, "03:15 (3 o'clock)")
            6 -> Triple(6, 30, "06:30 (6 o'clock)")
            9 -> Triple(9, 45, "09:45 (9 o'clock)")
            else -> Triple(12, 0, "12:00 (12 o'clock)")
        }
        val startMs = System.currentTimeMillis()
        log("HUD Alert [$callerLabel] -> Moving hands to $labelText...")
        clockDirty = true
        writeWatchTimeFast { epochMsForTodayAt(hour, minute, 0) }
        val elapsed = System.currentTimeMillis() - startMs
        log("HUD hands locked at $labelText (${elapsed}ms).")
    }

    private suspend fun restoreClock() {
        log("Syncing real phone time to watch...")
        writeWatchTimeFast { System.currentTimeMillis() }
        clockDirty = false
        log("Real time synced.")
    }

    private fun epochMsForTodayAt(hour: Int, minute: Int, second: Int): Long =
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, second)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    // ---------------------------------------- Public HUD & Time Sync Triggers

    fun pointHandsForCall(slot: Int, callerLabel: String = "VIP") {
        launchPriorityWatchOp("HUD Alert ($slot o'clock)") {
            setHandsToQuadrant(slot, callerLabel)
        }
    }

    fun syncRealTime() {
        launchPriorityWatchOp("Sync real time") {
            restoreClock()
            _settingsStatus.value = "Watch synced to exact phone time"
        }
    }

    // ----------------------------------- Casio Alarms & Hardware Settings API

    fun loadWatchFeaturesFromWatch() {
        scope.launch {
            runWatchOp("Read watch alarms & settings") {
                runCatching {
                    val rawAlarms = api.getAlarms()
                    if (rawAlarms.isNotEmpty()) {
                        _alarms.value = rawAlarms.take(5).map {
                            AlarmEntry(
                                hour = it.hour,
                                minute = it.minute,
                                enabled = it.enabled,
                                hasHourlyChime = it.hasHourlyChime
                            )
                        }
                        log("Loaded ${rawAlarms.size} alarms from watch.")
                    }
                }.onFailure { log("Could not read alarms: ${it.message}") }

                runCatching {
                    val s = api.getBasicSettings()
                    _watchConfig.value = WatchConfigState(
                        buttonToneMuted = !s.buttonTone,
                        autoLight = s.autoLight,
                        timeFormat24h = s.timeFormat == "24h",
                        powerSaving = s.powerSavingMode,
                        loaded = true
                    )
                    log("Loaded watch hardware settings.")
                }.onFailure { log("Could not read watch settings: ${it.message}") }
            }
        }
    }

    fun updateAlarm(index: Int, hour: Int, minute: Int, enabled: Boolean, hourlyChime: Boolean? = null) {
        val current = _alarms.value.toMutableList()
        if (index !in current.indices) return
        val chime = hourlyChime ?: current[0].hasHourlyChime
        current[index] = current[index].copy(hour = hour, minute = minute, enabled = enabled)
        if (hourlyChime != null && current.isNotEmpty()) {
            current[0] = current[0].copy(hasHourlyChime = chime)
        }
        _alarms.value = current
        pushAlarmsToWatch(current)
    }

    fun toggleHourlyChime(enabled: Boolean) {
        val current = _alarms.value.toMutableList()
        if (current.isEmpty()) return
        current[0] = current[0].copy(hasHourlyChime = enabled)
        _alarms.value = current
        pushAlarmsToWatch(current)
    }

    private fun pushAlarmsToWatch(entries: List<AlarmEntry>) {
        scope.launch {
            runWatchOp("Save alarms to watch") {
                val apiList = ArrayList<Alarm>(
                    entries.mapIndexed { idx, e ->
                        Alarm(
                            e.hour,
                            e.minute,
                            e.enabled,
                            if (idx == 0) entries[0].hasHourlyChime else false
                        )
                    }
                )
                api.setAlarms(apiList)
                _settingsStatus.value = "Alarms saved to watch"
                log("Alarms written to watch successfully.")
            }
        }
    }

    fun updateWatchConfig(
        buttonToneMuted: Boolean = _watchConfig.value.buttonToneMuted,
        autoLight: Boolean = _watchConfig.value.autoLight,
        timeFormat24h: Boolean = _watchConfig.value.timeFormat24h,
        powerSaving: Boolean = _watchConfig.value.powerSaving
    ) {
        val updated = _watchConfig.value.copy(
            buttonToneMuted = buttonToneMuted,
            autoLight = autoLight,
            timeFormat24h = timeFormat24h,
            powerSaving = powerSaving,
            loaded = true
        )
        _watchConfig.value = updated

        scope.launch {
            runWatchOp("Save watch settings") {
                val s = runCatching { api.getBasicSettings() }.getOrElse { Settings() }
                s.buttonTone = !updated.buttonToneMuted
                s.autoLight = updated.autoLight
                s.timeFormat = if (updated.timeFormat24h) "24h" else "12h"
                s.powerSavingMode = updated.powerSaving
                api.setSettings(s)
                _settingsStatus.value = "Settings applied to watch"
                log("Watch settings updated over BLE.")
            }
        }
    }
}
