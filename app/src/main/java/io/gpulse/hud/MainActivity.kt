package io.gpulse.hud

import android.Manifest
import android.app.TimePickerDialog
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.ContactsContract
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.avmedia.gshockapi.WatchInfo
import java.util.Locale

private const val APP_TITLE = "G-PULSE"
private const val APP_VERSION = "v1.0"
private const val UNPAIRED_WATCH_TITLE = "Connect your CasiOak"
private const val PAIRED_FALLBACK_LABEL = "CASIO G-SHOCK"

// Grok / ChatGPT Stark Monochrome Palette
private val MonoBlack = Color(0xFF000000)
private val MonoSurface = Color(0xFF0A0A0A)
private val MonoElevated = Color(0xFF121212)
private val MonoBorder = Color(0xFF242424)
private val MonoBorderBright = Color(0xFF3A3A3C)
private val MonoWhite = Color(0xFFFFFFFF)
private val MonoSilver = Color(0xFFE5E5EA)
private val MonoMuted = Color(0xFF8E8E93)
private val MonoDim = Color(0xFF48484A)

private val MonochromeScheme = darkColorScheme(
    primary = MonoWhite,
    onPrimary = MonoBlack,
    secondary = MonoSilver,
    onSecondary = MonoBlack,
    background = MonoBlack,
    surface = MonoSurface,
    onSurface = MonoWhite,
    onBackground = MonoWhite,
    surfaceVariant = MonoElevated,
    onSurfaceVariant = MonoMuted,
    outline = MonoBorder,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WatchController.getInstance(applicationContext)
        setContent {
            MaterialTheme(colorScheme = MonochromeScheme) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MonoBlack)
                        .systemBarsPadding()
                        .imePadding()
                ) {
                    MainScreen()
                }
            }
        }
    }
}

private fun allPermissions(): List<String> = buildList {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_CONNECT)
    }
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    add(Manifest.permission.READ_PHONE_STATE)
    add(Manifest.permission.READ_CALL_LOG)
    add(Manifest.permission.READ_CONTACTS)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}

private fun has(ctx: Context, p: String) =
    ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

private fun bluetoothOk(ctx: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        has(ctx, Manifest.permission.BLUETOOTH_SCAN) && has(ctx, Manifest.permission.BLUETOOTH_CONNECT)
    else true

private fun locationPermissionOk(ctx: Context): Boolean =
    has(ctx, Manifest.permission.ACCESS_FINE_LOCATION)

private fun callPermissionsOk(ctx: Context): Boolean =
    has(ctx, Manifest.permission.READ_PHONE_STATE) &&
        has(ctx, Manifest.permission.READ_CALL_LOG) &&
        has(ctx, Manifest.permission.READ_CONTACTS)

private fun resolvePickedContactName(ctx: Context, contactUri: Uri): String? {
    return runCatching {
        ctx.contentResolver.query(
            contactUri,
            arrayOf(ContactsContract.Contacts.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()
}

private fun openSettings(ctx: Context, action: String) {
    runCatching {
        ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private fun openAppNotificationSettings(ctx: Context) {
    runCatching {
        ctx.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

@Composable
fun MainScreen() {
    val ctx = LocalContext.current
    val activity = ctx as ComponentActivity
    val watchController = remember { WatchController.getInstance(ctx) }

    val isConnected by watchController.isConnected.collectAsState()
    val connectionStatus by watchController.connectionStatus.collectAsState()
    val batteryLevel by watchController.batteryLevel.collectAsState()
    val detectedModel by watchController.detectedModelName.collectAsState()
    val alarms by watchController.alarms.collectAsState()
    val watchConfig by watchController.watchConfig.collectAsState()
    val settingsStatus by watchController.settingsStatus.collectAsState()

    var currentPage by remember { mutableIntStateOf(0) } // 0 = HUD (Page 1), 1 = Settings (Page 2)
    var refresh by remember { mutableIntStateOf(0) }
    var savedAddress by remember { mutableStateOf(Prefs.getSavedAddress(ctx)) }
    var savedName by remember { mutableStateOf(Prefs.getSavedName(ctx)) }

    var vip12 by remember { mutableStateOf(Prefs.getVipContact(ctx, 12)) }
    var vip3 by remember { mutableStateOf(Prefs.getVipContact(ctx, 3)) }
    var vip6 by remember { mutableStateOf(Prefs.getVipContact(ctx, 6)) }
    var vip9 by remember { mutableStateOf(Prefs.getVipContact(ctx, 9)) }
    var pickingForSlot by remember { mutableIntStateOf(12) }

    val contactPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickContact()
    ) { uri: Uri? ->
        if (uri != null) {
            val pickedName = resolvePickedContactName(ctx, uri)
            if (!pickedName.isNullOrBlank()) {
                Prefs.setVipContact(ctx, pickingForSlot, pickedName)
                when (pickingForSlot) {
                    12 -> vip12 = pickedName
                    3 -> vip3 = pickedName
                    6 -> vip6 = pickedName
                    9 -> vip9 = pickedName
                }
            }
        }
    }

    val discoveredWatches = remember { mutableStateListOf<DiscoveredWatch>() }
    var isDiscovering by remember { mutableStateOf(false) }

    val bleDiscoverer = remember {
        BleWatchDiscoverer(
            context = ctx,
            onWatchesUpdated = { list ->
                discoveredWatches.clear()
                discoveredWatches.addAll(list)
            },
            onLog = { msg -> watchController.log(msg) },
            onScanStateChanged = { scanning -> isDiscovering = scanning }
        )
    }

    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh++
        }
        activity.lifecycle.addObserver(observer)
        onDispose {
            activity.lifecycle.removeObserver(observer)
            bleDiscoverer.stopDiscovery()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refresh++ }

    val btPermOk = remember(refresh) { bluetoothOk(ctx) }
    val locPermOk = remember(refresh) { locationPermissionOk(ctx) }
    val callsPermOk = remember(refresh) { callPermissionsOk(ctx) }
    val notifOk = remember(refresh) {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            has(ctx, Manifest.permission.POST_NOTIFICATIONS)
    }
    val batteryOptimizationIgnored = remember(refresh) {
        (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(ctx.packageName)
    }

    val bluetoothManager = remember { ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager }
    val btEnabled = remember(refresh) { bluetoothManager.adapter?.isEnabled == true }

    val locationManager = remember { ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager }
    val locationEnabled = remember(refresh) { LocationManagerCompat.isLocationEnabled(locationManager) }

    val requestPermissions = { permissionLauncher.launch(allPermissions().toTypedArray()) }
    val launchContactPickerForSlot: (Int) -> Unit = { slot ->
        pickingForSlot = slot
        if (!has(ctx, Manifest.permission.READ_CONTACTS)) {
            requestPermissions()
        } else {
            runCatching { contactPickerLauncher.launch(null) }
        }
    }

    val disconnectAndStopService = {
        watchController.disconnectWithRealTimeSync {
            KeepAliveService.stop(ctx)
        }
    }

    LaunchedEffect(savedAddress) {
        if (!savedAddress.isNullOrBlank()) {
            KeepAliveService.start(ctx)
            watchController.startPersistentConnection(savedAddress)
        }
    }

    val activeWatchTitle = when {
        savedAddress == null -> UNPAIRED_WATCH_TITLE
        !detectedModel.isNullOrBlank() -> detectedModel!!
        !savedName.isNullOrBlank() -> savedName!!
        else -> PAIRED_FALLBACK_LABEL
    }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        // Header
        item(key = "header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        APP_TITLE,
                        color = MonoWhite,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 19.sp,
                        letterSpacing = 2.sp
                    )
                    Text(
                        APP_VERSION,
                        color = MonoMuted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .border(1.dp, MonoBorder, RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                // Online / Standby Pill
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (isConnected) MonoWhite else MonoElevated)
                        .border(1.dp, if (isConnected) MonoWhite else MonoBorder, RoundedCornerShape(50))
                        .padding(horizontal = 12.dp, vertical = 5.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(if (isConnected) MonoBlack else MonoMuted)
                    )
                    Text(
                        if (isConnected) "ONLINE" else "STANDBY",
                        color = if (isConnected) MonoBlack else MonoMuted,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        letterSpacing = 1.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        // 2-Page Segmented Navigation Bar (Page 1: HUD | Page 2: SETTINGS)
        item(key = "nav_tabs") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MonoSurface)
                    .border(1.dp, MonoBorder, RoundedCornerShape(14.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TabPill(
                    title = "HUD & CONTACTS",
                    selected = currentPage == 0,
                    onClick = { currentPage = 0 }
                )
                TabPill(
                    title = "WATCH SETTINGS",
                    selected = currentPage == 1,
                    onClick = {
                        currentPage = 1
                        if (isConnected) watchController.loadWatchFeaturesFromWatch()
                    }
                )
            }
        }

        if (currentPage == 0) {
            // ==================== PAGE 1: CONNECTION & CALLER HUD CONTACTS ====================
            item(key = "connection") {
                MonoCard {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    activeWatchTitle,
                                    color = MonoWhite,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 17.sp
                                )
                                Text(
                                    if (savedAddress == null) "No CasiOak paired — tap Pair Watch"
                                    else if (isConnected) "Active BLE Link"
                                    else connectionStatus,
                                    color = MonoMuted,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .border(1.dp, MonoBorderBright, RoundedCornerShape(8.dp))
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Text(
                                    text = batteryLevel?.let { "BAT $it%" } ?: "BAT --%",
                                    color = if (batteryLevel != null) MonoWhite else MonoMuted,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 11.sp
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            StatusChip("Bluetooth", btPermOk && btEnabled) {
                                if (!btPermOk) requestPermissions()
                                else openSettings(ctx, Settings.ACTION_BLUETOOTH_SETTINGS)
                            }
                            StatusChip("Location", locPermOk && locationEnabled) {
                                if (!locPermOk) requestPermissions()
                                else openSettings(ctx, Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                            }
                            StatusChip("Calls", callsPermOk) {
                                requestPermissions()
                            }
                            StatusChip("Alerts", notifOk) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) requestPermissions()
                                else openAppNotificationSettings(ctx)
                            }
                        }

                        if (!(btPermOk && locPermOk && callsPermOk && notifOk)) {
                            Button(
                                onClick = { requestPermissions() },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MonoWhite,
                                    contentColor = MonoBlack
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Grant Required Permissions", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Button(
                                onClick = {
                                    when {
                                        isDiscovering -> bleDiscoverer.stopDiscovery()
                                        !btPermOk || !locPermOk -> requestPermissions()
                                        else -> {
                                            discoveredWatches.clear()
                                            bleDiscoverer.startDiscovery()
                                        }
                                    }
                                },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MonoWhite,
                                    contentColor = MonoBlack
                                ),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    if (isDiscovering) "Stop Scan" else if (savedAddress == null) "Pair Watch" else "Scan",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            }

                            if (isConnected) {
                                OutlinedButton(
                                    onClick = { disconnectAndStopService() },
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, MonoBorderBright),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MonoWhite),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Disconnect", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                }
                            } else {
                                OutlinedButton(
                                    onClick = {
                                        KeepAliveService.start(ctx)
                                        watchController.startPersistentConnection(savedAddress)
                                    },
                                    enabled = savedAddress != null,
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, MonoBorderBright),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MonoWhite),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Connect", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                }
                            }
                        }

                        if (isDiscovering) {
                            Text(
                                "Hold the lower-left CONNECT (C) button on your CasiOak for 3 seconds.",
                                color = MonoMuted,
                                fontSize = 12.sp
                            )
                        }

                        if (discoveredWatches.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    "DETECTED WATCHES",
                                    color = MonoMuted,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                )
                                discoveredWatches.forEach { d ->
                                    DiscoveredWatchRow(d) {
                                        bleDiscoverer.stopDiscovery()
                                        val chosenName = if (d.name.isNotBlank()) d.name else PAIRED_FALLBACK_LABEL
                                        Prefs.saveWatch(ctx, d.address, chosenName)
                                        savedAddress = d.address
                                        savedName = chosenName
                                        if (chosenName.startsWith("CASIO", ignoreCase = true)) {
                                            WatchInfo.setNameAndModel(chosenName)
                                        }
                                        WatchInfo.setAddress(d.address)
                                        KeepAliveService.start(ctx)
                                        watchController.startPersistentConnection(d.address)
                                    }
                                }
                            }
                        }

                        if (!batteryOptimizationIgnored || savedAddress != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (!batteryOptimizationIgnored) {
                                    Text(
                                        "Unrestrict Background Battery",
                                        color = MonoSilver,
                                        fontSize = 11.sp,
                                        modifier = Modifier.clickable {
                                            runCatching {
                                                ctx.startActivity(
                                                    Intent(
                                                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                                        Uri.parse("package:${ctx.packageName}")
                                                    )
                                                )
                                            }
                                        }
                                    )
                                } else {
                                    Spacer(Modifier.width(1.dp))
                                }

                                if (savedAddress != null) {
                                    Text(
                                        "Unpair Watch",
                                        color = MonoMuted,
                                        fontSize = 11.sp,
                                        modifier = Modifier.clickable {
                                            bleDiscoverer.stopDiscovery()
                                            disconnectAndStopService()
                                            Prefs.forgetWatch(ctx)
                                            savedAddress = null
                                            savedName = null
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item(key = "vip_contacts") {
                MonoCard {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "CALLER HUD DIAL",
                                color = MonoWhite,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                letterSpacing = 1.sp
                            )
                            Text(
                                "Assign contacts to watch positions. Both analog hands stack at the caller's marker while ringing and return to real time afterwards.",
                                color = MonoMuted,
                                fontSize = 12.sp,
                                lineHeight = 17.sp
                            )
                        }

                        VipSlotField(
                            marker = "12",
                            subtitle = "12:00 Position",
                            value = vip12,
                            onValueChange = { vip12 = it; Prefs.setVipContact(ctx, 12, it) },
                            onPickContact = { launchContactPickerForSlot(12) }
                        )
                        VipSlotField(
                            marker = "03",
                            subtitle = "03:15 Position",
                            value = vip3,
                            onValueChange = { vip3 = it; Prefs.setVipContact(ctx, 3, it) },
                            onPickContact = { launchContactPickerForSlot(3) }
                        )
                        VipSlotField(
                            marker = "06",
                            subtitle = "06:30 Position",
                            value = vip6,
                            onValueChange = { vip6 = it; Prefs.setVipContact(ctx, 6, it) },
                            onPickContact = { launchContactPickerForSlot(6) }
                        )
                        VipSlotField(
                            marker = "09",
                            subtitle = "09:45 Position",
                            value = vip9,
                            onValueChange = { vip9 = it; Prefs.setVipContact(ctx, 9, it) },
                            onPickContact = { launchContactPickerForSlot(9) }
                        )
                    }
                }
            }
        } else {
            // ==================== PAGE 2: CASIO WATCH SETTINGS & ALARMS ====================
            if (!settingsStatus.isNullOrBlank()) {
                item(key = "settings_banner") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MonoElevated)
                            .border(1.dp, MonoBorderBright, RoundedCornerShape(12.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            settingsStatus!!,
                            color = MonoWhite,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            // 1. Real-Time Sync Card
            item(key = "time_sync_card") {
                MonoCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "TIME SYNCHRONIZATION",
                            color = MonoWhite,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            letterSpacing = 1.sp
                        )
                        Text(
                            "Calibrate your CasiOak's analog hands and internal clock with your phone's exact system time and timezone.",
                            color = MonoMuted,
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )

                        Button(
                            onClick = { watchController.syncRealTime() },
                            enabled = isConnected,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MonoWhite,
                                contentColor = MonoBlack,
                                disabledContainerColor = MonoElevated,
                                disabledContentColor = MonoDim
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Sync Real Time Now", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }

            // 2. Casio 5 Daily Alarms & Hourly Signal Card
            item(key = "alarms_card") {
                MonoCard {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    "WATCH ALARMS & SIGNAL",
                                    color = MonoWhite,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    letterSpacing = 1.sp
                                )
                                Text(
                                    "Tap any alarm time to edit. Changes write directly to watch.",
                                    color = MonoMuted,
                                    fontSize = 11.sp
                                )
                            }
                            IconButton(
                                onClick = { watchController.loadWatchFeaturesFromWatch() },
                                enabled = isConnected
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Refresh from watch",
                                    tint = if (isConnected) MonoWhite else MonoDim
                                )
                            }
                        }

                        // Hourly Chime (SIG) Toggle
                        val chimeOn = alarms.firstOrNull()?.hasHourlyChime == true
                        SettingToggleRow(
                            title = "Hourly Time Signal (SIG)",
                            subtitle = "Watch beeps twice at the top of every hour",
                            checked = chimeOn,
                            enabled = isConnected,
                            onCheckedChange = { watchController.toggleHourlyChime(it) }
                        )

                        HorizontalDivider(color = MonoBorder)

                        alarms.forEachIndexed { idx, alarm ->
                            AlarmRow(
                                index = idx,
                                alarm = alarm,
                                enabled = isConnected,
                                onTimeSelected = { h, m ->
                                    watchController.updateAlarm(idx, h, m, alarm.enabled)
                                },
                                onToggle = { active ->
                                    watchController.updateAlarm(idx, alarm.hour, alarm.minute, active)
                                }
                            )
                        }
                    }
                }
            }

            // 3. Hardware Configuration Card (Tone, Light, 24h, Power Saving)
            item(key = "hardware_settings_card") {
                MonoCard {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "HARDWARE CONFIGURATION",
                                color = MonoWhite,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                letterSpacing = 1.sp
                            )
                            Text(
                                "Configure internal CasiOak module behavior over BLE.",
                                color = MonoMuted,
                                fontSize = 12.sp
                            )
                        }

                        SettingToggleRow(
                            title = "Mute Button Operation Tone",
                            subtitle = "Silence the beep when pressing physical watch buttons",
                            checked = watchConfig.buttonToneMuted,
                            enabled = isConnected,
                            onCheckedChange = { watchController.updateWatchConfig(buttonToneMuted = it) }
                        )

                        SettingToggleRow(
                            title = "Auto LED Light (Tilt)",
                            subtitle = "Illuminate watch face automatically when angling wrist",
                            checked = watchConfig.autoLight,
                            enabled = isConnected,
                            onCheckedChange = { watchController.updateWatchConfig(autoLight = it) }
                        )

                        SettingToggleRow(
                            title = "24-Hour Time Format",
                            subtitle = "Use 24-hour military time format on watch module",
                            checked = watchConfig.timeFormat24h,
                            enabled = isConnected,
                            onCheckedChange = { watchController.updateWatchConfig(timeFormat24h = it) }
                        )

                        SettingToggleRow(
                            title = "Power Saving Mode",
                            subtitle = "Sleep watch in the dark to conserve Tough Solar battery",
                            checked = watchConfig.powerSaving,
                            enabled = isConnected,
                            onCheckedChange = { watchController.updateWatchConfig(powerSaving = it) }
                        )
                    }
                }
            }
        }

        // Maintainer Attribution Footer
        item(key = "footer") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Maintained by Senin Ali KP",
                    color = MonoMuted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.5.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun RowScope.TabPill(title: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) MonoWhite else Color.Transparent)
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            color = if (selected) MonoBlack else MonoMuted,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            letterSpacing = 1.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun AlarmRow(
    index: Int,
    alarm: AlarmEntry,
    enabled: Boolean,
    onTimeSelected: (Int, Int) -> Unit,
    onToggle: (Boolean) -> Unit
) {
    val ctx = LocalContext.current
    val formattedTime = String.format(Locale.US, "%02d:%02d", alarm.hour, alarm.minute)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MonoElevated)
            .border(1.dp, MonoBorder, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled) {
                TimePickerDialog(
                    ctx,
                    { _, h, m -> onTimeSelected(h, m) },
                    alarm.hour,
                    alarm.minute,
                    true
                ).show()
            }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "ALARM ${index + 1}",
                color = MonoMuted,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                letterSpacing = 1.sp
            )
            Text(
                text = formattedTime,
                color = if (alarm.enabled) MonoWhite else MonoMuted,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            )
        }

        Switch(
            checked = alarm.enabled,
            enabled = enabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MonoBlack,
                checkedTrackColor = MonoWhite,
                uncheckedThumbColor = MonoMuted,
                uncheckedTrackColor = MonoBlack,
                uncheckedBorderColor = MonoBorderBright
            )
        )
    }
}

@Composable
private fun SettingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                title,
                color = if (enabled) MonoWhite else MonoMuted,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp
            )
            Text(
                subtitle,
                color = MonoMuted,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MonoBlack,
                checkedTrackColor = MonoWhite,
                uncheckedThumbColor = MonoMuted,
                uncheckedTrackColor = MonoElevated,
                uncheckedBorderColor = MonoBorderBright
            )
        )
    }
}

@Composable
private fun MonoCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MonoSurface,
        border = BorderStroke(1.dp, MonoBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            content = content
        )
    }
}

@Composable
private fun VipSlotField(
    marker: String,
    subtitle: String,
    value: String,
    onValueChange: (String) -> Unit,
    onPickContact: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MonoElevated)
            .border(1.dp, MonoBorder, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (value.isNotBlank()) MonoWhite else MonoBlack)
                .border(1.dp, if (value.isNotBlank()) MonoWhite else MonoBorderBright, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = marker,
                color = if (value.isNotBlank()) MonoBlack else MonoWhite,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 14.sp
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = subtitle.uppercase(),
                color = MonoMuted,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                letterSpacing = 0.8.sp
            )
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = {
                    Text(
                        "Assign contact or number...",
                        color = MonoDim,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = MonoWhite,
                    unfocusedTextColor = MonoWhite,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    cursorColor = MonoWhite
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .offset(x = (-12).dp)
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (value.isNotEmpty()) {
                IconButton(
                    onClick = { onValueChange("") },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = "Clear",
                        tint = MonoMuted,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            IconButton(
                onClick = onPickContact,
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MonoBlack)
                    .border(1.dp, MonoBorderBright, RoundedCornerShape(8.dp))
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = "Pick Contact",
                    tint = MonoWhite,
                    modifier = Modifier.size(17.dp)
                )
            }
        }
    }
}

@Composable
private fun DiscoveredWatchRow(d: DiscoveredWatch, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MonoElevated)
            .border(1.dp, MonoBorderBright, RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(d.name.ifBlank { PAIRED_FALLBACK_LABEL }, color = MonoWhite, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Text("TAP TO PAIR", color = MonoMuted, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
    }
}

@Composable
private fun RowScope.StatusChip(label: String, ok: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .weight(1f)
            .clip(shape)
            .background(if (ok) MonoElevated else MonoBlack, shape)
            .border(1.dp, if (ok) MonoBorder else MonoWhite, shape)
            .clickable(enabled = !ok) { onClick() }
            .padding(vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = (if (ok) "● " else "○ ") + label,
            color = if (ok) MonoSilver else MonoWhite,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            textAlign = TextAlign.Center
        )
    }
}
