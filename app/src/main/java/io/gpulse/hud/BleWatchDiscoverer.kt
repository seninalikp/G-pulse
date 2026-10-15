package io.gpulse.hud

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log

data class DiscoveredWatch(
    val name: String,
    val address: String,
    val rssi: Int,
    val isBonded: Boolean,
    val device: BluetoothDevice
)

@SuppressLint("MissingPermission")
class BleWatchDiscoverer(
    private val context: Context,
    private val onWatchesUpdated: (List<DiscoveredWatch>) -> Unit,
    private val onLog: (String) -> Unit,
    var onScanStateChanged: ((Boolean) -> Unit)? = null
) {
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val handler = Handler(Looper.getMainLooper())
    private val discoveredMap = LinkedHashMap<String, DiscoveredWatch>()
    private var isScanning = false

    fun isScanning(): Boolean = isScanning

    companion object {
        private const val TAG = "BleWatchDiscoverer"
        private val CASIO_UUID = ParcelUuid.fromString("00001804-0000-1000-8000-00805f9b34fb")
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            processResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { processResult(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            onLog("BLE Scan failed with error code: $errorCode")
            Log.e(TAG, "onScanFailed: $errorCode")
        }
    }

    private fun processResult(result: ScanResult) {
        val device = result.device ?: return
        val address = device.address ?: return
        val rawName = device.name ?: result.scanRecord?.deviceName ?: ""
        val upperName = rawName.uppercase()
        val uuids = result.scanRecord?.serviceUuids ?: emptyList()

        val hasCasioUuid = uuids.any { it == CASIO_UUID || it.uuid.toString().startsWith("00001804") }
        val hasCasioName = upperName.contains("CASIO") ||
                           upperName.contains("GBM") ||
                           upperName.contains("2100") ||
                           upperName.contains("G-SHOCK")

        if (hasCasioName || hasCasioUuid) {
            val displayName = when {
                rawName.isNotBlank() -> rawName
                hasCasioUuid -> "CASIO GBM-2100 (Pairing Mode)"
                else -> "CASIO GBM-2100"
            }
            val isNew = !discoveredMap.containsKey(address)
            discoveredMap[address] = DiscoveredWatch(
                name = displayName,
                address = address,
                rssi = result.rssi,
                isBonded = device.bondState == BluetoothDevice.BOND_BONDED,
                device = device
            )
            if (isNew) {
                onLog("Found watch: $displayName [$address] (RSSI: ${result.rssi} dBm)")
            }
            onWatchesUpdated(discoveredMap.values.toList())
        }
    }

    fun startDiscovery(timeoutMs: Long = 20000L) {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            onLog("Error: Bluetooth is turned OFF.")
            return
        }

        stopDiscovery()
        discoveredMap.clear()

        // 1. Check already-bonded Casio watches in Android system settings
        adapter.bondedDevices?.forEach { bonded ->
            val bName = (bonded.name ?: "").uppercase()
            if (bName.contains("CASIO") || bName.contains("GBM") || bName.contains("2100")) {
                discoveredMap[bonded.address] = DiscoveredWatch(
                    name = bonded.name ?: "CASIO GBM-2100 (Paired)",
                    address = bonded.address,
                    rssi = 0,
                    isBonded = true,
                    device = bonded
                )
                onLog("Bonded watch found: ${bonded.name} [${bonded.address}]")
            }
        }
        onWatchesUpdated(discoveredMap.values.toList())

        // 2. Start active Low-Latency BLE scan with no hardware filter restrictions
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            onLog("Error: BluetoothLeScanner unavailable.")
            return
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0L)
            .build()

        try {
            scanner.startScan(emptyList(), settings, scanCallback)
            isScanning = true
            onScanStateChanged?.invoke(true)
            onLog("Scanning for Casio GBM-2100 in pairing mode...")
            handler.postDelayed({
                if (isScanning) {
                    stopDiscovery()
                    onLog("Scan finished (${discoveredMap.size} watch(es) found).")
                }
            }, timeoutMs)
        } catch (e: Exception) {
            onLog("Scan exception: ${e.message}")
            isScanning = false
            onScanStateChanged?.invoke(false)
        }
    }

    fun stopDiscovery() {
        if (!isScanning) return
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {}
        isScanning = false
        onScanStateChanged?.invoke(false)
        handler.removeCallbacksAndMessages(null)
    }
}
