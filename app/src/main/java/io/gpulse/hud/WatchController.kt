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
import org.avmedia.gshockapi.io.CachedIO
import org.avmedia.gshockapi.io.TimeIO
import org.avmedia.gshockapi.io.WatchConditionIO
import org.avmedia.gshockapi.model.Alarm
import org.avmedia.gshockapi.model.Settings
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.min

data class AlarmEntry(
    val hour: Int? = null,
    val minute: Int? = null,
    val enabled: Boolean = false,
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
        List(5) { AlarmEntry(hour = null, minute = null, enabled = false, hasHourlyChime = false) }
    )
    val alarms: StateFlow<List<AlarmEntry>> = _alarms

    /** Snapshot of the user's manual alarms so temporary call-beep alarms never overwrite user alarms. */
    private var userAlarmsSnapshot: List<AlarmEntry>? = null

    private val _watchConfig = MutableStateFlow(WatchConfigState())
    val watchConfig: StateFlow<WatchConfigState> = _watchConfig

    private val _selectedTimezone = MutableStateFlow(Prefs.getSelectedTimezone(context))
    val selectedTimezone: StateFlow<String> = _selectedTimezone

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
        private const val WRITE_RETRY_DELAY_MS = 200L
        private const val KEEP_ALIVE_INTERVAL_MS = 15_000L

        private const val CONNECT_WAIT_MS = 12_000L
        private const val CONNECT_POLL_MS = 300L
        private const val CONNECTED_POLL_MS = 2_500L
        private const val RECONNECT_MIN_MS = 2_000L
        private const val RECONNECT_MAX_MS = 25_000L

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
                    _connectionStatus.value = "Connecting..."
                    log("BLE ConnectionStarted")
                }
                ProgressEvents["ConnectionSetupComplete"] -> {
                    _connectionStatus.value = "Setting up link..."
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
        _connectionStatus.value = "Connected"
        log("$model Connected! Starting 360° connection ritual...")

        scope.launch {
            delay(500L)
            if (api.isConnected() && !stopRequested) {
                runWatchOp("360° Connection Ritual") {
                    ensureTimezoneSynced()
                    performConnectionRitual360()
                }
                sendKeepAlivePing()
                loadWatchFeaturesFromWatch()
            }
        }
    }

    /**
     * Spins the hands 180 degrees opposite the current time, waits for the stepper motors
     * to hit full stride, and then sweeps the remaining 180 degrees back to exact real time.
     */
    private suspend fun performConnectionRitual360() {
        val now = Calendar.getInstance()
        val oppositeHour = (now.get(Calendar.HOUR_OF_DAY) + 6) % 24
        val oppositeMinute = (now.get(Calendar.MINUTE) + 30) % 60

        log("Ritual: Sweeping hands across dial...")
        clockDirty = true
        writeWatchTimeFast { epochMsForTodayAt(oppositeHour, oppositeMinute, 0) }
        delay(2_600L) // Let physical stepper motors sweep across the face
        log("Ritual: Locking onto exact real time...")
        restoreClock()
    }

    private fun onWatchLost() {
        timezoneSynced = false
        _batteryLevel.value = null
        _isConnected.value = false
        val auto = Prefs.isAutoConnect(context)
        _connectionStatus.value =
            if (stopRequested || !auto) "Disconnected" else "Auto-connecting..."
        log(if (clockDirty) "Watch disconnected. Real time will be restored on reconnect." else "Watch disconnected.")
    }

    fun setAutoConnectEnabled(enabled: Boolean) {
        Prefs.setAutoConnect(context, enabled)
        val savedAddr = Prefs.getSavedAddress(context)
        if (enabled) {
            log("Auto-connect enabled.")
            if (!_isConnected.value && !savedAddr.isNullOrBlank()) {
                KeepAliveService.start(context)
                startPersistentConnection(savedAddr, forceOnce = false)
            }
        } else {
            log("Auto-connect disabled.")
            if (!_isConnected.value) {
                connectionLoopJob?.cancel()
                connectionLoopJob = null
                _connectionStatus.value = "Disconnected"
            }
        }
    }

    fun startPersistentConnection(savedAddress: String?, forceOnce: Boolean = false) {
        if (!Prefs.isAutoConnect(context) && !forceOnce) {
            _connectionStatus.value = "Disconnected"
            return
        }
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
                if (!Prefs.isAutoConnect(context) && !forceOnce) {
                    _connectionStatus.value = "Disconnected"
                    break
                }

                val connected = try {
                    attemptConnection(savedAddress)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("Connection loop error: ${e.message}")
                    false
                }

                if (!connected) {
                    if (!Prefs.isAutoConnect(context)) {
                        _connectionStatus.value = "Disconnected"
                        break
                    }
                    _connectionStatus.value = "Auto-connecting..."
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

        if (!savedAddress.isNullOrBlank()) {
            WatchInfo.setAddress(savedAddress)
            _connectionStatus.value = if (Prefs.isAutoConnect(context)) "Auto-connecting..." else "Connecting..."
            api.waitForConnection(savedAddress)
        } else {
            _connectionStatus.value = "Waiting for CasiOak..."
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
        _connectionStatus.value = "Connected"
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
        _connectionStatus.value = "Syncing time (5s)..."
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

    /** Cancels any background ping immediately so incoming calls never wait in a queue. */
    private fun launchPriorityWatchOp(label: String, block: suspend () -> Unit): Job {
        keepAliveJob?.cancel()
        activeOpJob?.cancel()
        return scope.launch { runWatchOp(label, block) }.also { activeOpJob = it }
    }

    private suspend fun ensureTimezoneSynced() {
        if (!timezoneSynced) {
            val tzId = Prefs.getSelectedTimezone(context)
            runCatching {
                TimeIO.setTimezone(tzId)
                timezoneSynced = true
            }
        }
    }

    /** Ultra-fast time write: assumes timezone was already synced on connect so only 1 BLE write occurs. */
    private suspend fun writeWatchTimeFast(epochMs: () -> Long) {
        var attempt = 0
        while (true) {
            try {
                TimeIO.set(epochMs())
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (++attempt >= WRITE_ATTEMPTS) throw e
                CachedIO.clearCache()
                ensureTimezoneSynced()
                log("Time write retry (${e.message})...")
                delay(WRITE_RETRY_DELAY_MS)
            }
        }
    }

    /**
     * FAST CALL ALERT:
     * 1. First moves the hands immediately via a single `TimeIO.set` write so there is zero lag.
     * 2. Then arms only that specific alarm slot at the `:00` rollover so the watch beeps,
     *    backing up your existing user alarms in memory so they are never lost!
     */
    private suspend fun executeFastCallAlert(slot: Int, callerLabel: String) {
        val (targetHour, targetMin, preHour, preMin, alarmIdx) = when (slot) {
            12 -> CallQuadSpec(12, 0, 11, 59, 0)
            3 -> CallQuadSpec(3, 15, 3, 14, 1)
            6 -> CallQuadSpec(6, 30, 6, 29, 2)
            9 -> CallQuadSpec(9, 45, 9, 44, 3)
            else -> CallQuadSpec(12, 0, 11, 59, 0)
        }

        val startMs = System.currentTimeMillis()
        clockDirty = true

        // Snapshot user's alarms once before modifying the target slot
        if (userAlarmsSnapshot == null) {
            userAlarmsSnapshot = _alarms.value.map { it.copy() }
        }

        // Step 1: Arm only the specific alarm slot in our cached alarm list (no slow getAlarms() read!)
        val baseList = (userAlarmsSnapshot ?: _alarms.value).toMutableList()
        while (baseList.size < 5) baseList.add(AlarmEntry())
        val chime = baseList[0].hasHourlyChime
        baseList[alarmIdx] = AlarmEntry(
            hour = targetHour,
            minute = targetMin,
            enabled = true,
            hasHourlyChime = if (alarmIdx == 0) chime else false
        )

        val apiAlarms = ArrayList<Alarm>(
            baseList.mapIndexed { idx, e ->
                Alarm(
                    e.hour ?: 0,
                    e.minute ?: 0,
                    e.enabled && e.hour != null && e.minute != null,
                    if (idx == 0) chime else false
                )
            }
        )

        // Write the targeted alarm and immediately set clock to :58 seconds before target minute
        // so the hands sweep immediately AND the watch rolls over to :00 to trigger the beep!
        runCatching { api.setAlarms(apiAlarms) }
        writeWatchTimeFast { epochMsForTodayAt(preHour, preMin, 58) }

        val elapsed = System.currentTimeMillis() - startMs
        log("HUD + Beep Alert [$callerLabel] -> Slot $slot (${elapsed}ms)")
    }

    private data class CallQuadSpec(
        val targetHour: Int,
        val targetMin: Int,
        val preHour: Int,
        val preMin: Int,
        val alarmIdx: Int
    )

    private suspend fun restoreClockAndAlarms() {
        log("Restoring real phone time...")
        writeWatchTimeFast { currentEpochForSelectedTimezone() }
        clockDirty = false

        // Restore the user's exact pre-call alarms so the temporary call alarm is removed cleanly
        val snapshot = userAlarmsSnapshot
        if (snapshot != null) {
            userAlarmsSnapshot = null
            _alarms.value = snapshot
            runCatching {
                val chime = snapshot.firstOrNull()?.hasHourlyChime == true
                val apiList = ArrayList<Alarm>(
                    snapshot.mapIndexed { idx, e ->
                        Alarm(
                            e.hour ?: 0,
                            e.minute ?: 0,
                            e.enabled && e.hour != null && e.minute != null,
                            if (idx == 0) chime else false
                        )
                    }
                )
                api.setAlarms(apiList)
                log("Temporary call alarm cleared; user alarms restored.")
            }
        }
    }

    private suspend fun restoreClock() {
        log("Syncing time to watch (${_selectedTimezone.value})...")
        writeWatchTimeFast { currentEpochForSelectedTimezone() }
        clockDirty = false
        log("Real time synced.")
    }

    private fun currentEpochForSelectedTimezone(): Long {
        val targetTz = TimeZone.getTimeZone(_selectedTimezone.value)
        val defaultTz = TimeZone.getDefault()
        val now = System.currentTimeMillis()
        val offsetDiff = targetTz.getOffset(now) - defaultTz.getOffset(now)
        return now + offsetDiff
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
        launchPriorityWatchOp("HUD Call Alert ($slot o'clock)") {
            executeFastCallAlert(slot, callerLabel)
        }
    }

    fun restoreAfterCall() {
        launchPriorityWatchOp("Restore after call") {
            restoreClockAndAlarms()
        }
    }

    fun syncRealTime() {
        launchPriorityWatchOp("Sync real time") {
            restoreClock()
            _settingsStatus.value = "Synced to ${_selectedTimezone.value}"
        }
    }

    fun setWatchTimezone(tzId: String) {
        Prefs.setSelectedTimezone(context, tzId)
        _selectedTimezone.value = tzId
        timezoneSynced = false
        if (api.isConnected()) {
            launchPriorityWatchOp("Update watch timezone ($tzId)") {
                runCatching { TimeIO.setTimezone(tzId) }
                timezoneSynced = true
                restoreClock()
                _settingsStatus.value = "Timezone updated to $tzId"
            }
        } else {
            _settingsStatus.value = "Timezone saved ($tzId) — will apply on connect"
        }
    }

    // ----------------------------------- Casio Alarms & Hardware Settings API

    fun loadWatchFeaturesFromWatch() {
        scope.launch {
            runWatchOp("Read watch alarms & settings") {
                runCatching {
                    val rawAlarms = api.getAlarms()
                    if (rawAlarms.isNotEmpty()) {
                        val currentList = _alarms.value
                        val mapped = rawAlarms.take(5).mapIndexed { idx, a ->
                            val existing = currentList.getOrNull(idx)
                            val showTime = a.enabled || existing?.hour != null
                            AlarmEntry(
                                hour = if (showTime) a.hour else null,
                                minute = if (showTime) a.minute else null,
                                enabled = a.enabled,
                                hasHourlyChime = a.hasHourlyChime
                            )
                        }
                        _alarms.value = mapped
                        userAlarmsSnapshot = null
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
        userAlarmsSnapshot = null
        pushAlarmsToWatch(current)
    }

    fun clearAlarmSlot(index: Int) {
        val current = _alarms.value.toMutableList()
        if (index !in current.indices) return
        current[index] = current[index].copy(hour = null, minute = null, enabled = false)
        _alarms.value = current
        userAlarmsSnapshot = null
        pushAlarmsToWatch(current)
    }

    fun toggleHourlyChime(enabled: Boolean) {
        val current = _alarms.value.toMutableList()
        if (current.isEmpty()) return
        current[0] = current[0].copy(hasHourlyChime = enabled)
        _alarms.value = current
        userAlarmsSnapshot = null
        pushAlarmsToWatch(current)
    }

    private fun pushAlarmsToWatch(entries: List<AlarmEntry>) {
        scope.launch {
            runWatchOp("Save alarms to watch") {
                val apiList = ArrayList<Alarm>(
                    entries.mapIndexed { idx, e ->
                        Alarm(
                            e.hour ?: 0,
                            e.minute ?: 0,
                            e.enabled && e.hour != null && e.minute != null,
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
