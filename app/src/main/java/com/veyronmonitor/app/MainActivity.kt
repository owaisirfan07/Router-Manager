package com.veyronmonitor.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.veyronmonitor.app.api.TumcApi
import com.veyronmonitor.app.api.UpdateChecker
import com.veyronmonitor.app.api.UpdateInfo
import com.veyronmonitor.app.data.CredentialStore
import com.veyronmonitor.app.data.EnergyStore
import com.veyronmonitor.app.data.ChargeSchedule
import com.veyronmonitor.app.data.ScheduleStore
import com.veyronmonitor.app.data.WarningLogStore
import com.veyronmonitor.app.schedule.AlarmScheduler
import com.veyronmonitor.app.model.AuthState
import com.veyronmonitor.app.model.ConnectionStatus
import com.veyronmonitor.app.model.Device
import com.veyronmonitor.app.ui.DashboardScreen
import com.veyronmonitor.app.ui.UnitsScreen
import com.veyronmonitor.app.service.BackupWorker
import com.veyronmonitor.app.service.MonitorService
import com.veyronmonitor.app.service.Poller
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.veyronmonitor.app.ui.LoginScreen
import com.veyronmonitor.app.ui.ScheduleScreen
import com.veyronmonitor.app.ui.SettingsScreen
import com.veyronmonitor.app.ui.WarningsScreen
import com.veyronmonitor.app.ui.VeyronMonitorTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// How often we re-poll the cloud while the dashboard is open. The dongle
// itself only pushes new data to the cloud every so often, but polling
// this often means the moment new data lands, you see it almost instantly
// instead of waiting on a slow app refresh cycle.
private const val REFRESH_INTERVAL_MS = 15_000L

// Online/offline status changes less often than live readings, so we only
// re-check the device list (which carries onlineStatus) every few polls
// instead of on every single tick, to keep things light.
private const val DEVICE_STATUS_EVERY_N_POLLS = 4

// How long without a genuinely NEW reading before we consider the inverter
// actually offline. The dongle normally reports every ~5 minutes, so this
// gives generous slack for a slow/congested WiFi connection without
// falsely flipping to "offline" the way the cloud's own flag sometimes does.
private const val STALE_THRESHOLD_MS = 20 * 60_000L

// A reading younger than this (by the inverter's own clock) counts as "Live".
private const val LIVE_THRESHOLD_MS = 8 * 60_000L

private enum class Screen { DASHBOARD, SETTINGS, ENERGY, METER, SCHEDULE, WARNINGS, REPORTS, ASSISTANT }

class MainActivity : ComponentActivity() {
    companion object {
        /** Set when the monthly meter reminder notification is tapped. */
        val openMeter = kotlinx.coroutines.flow.MutableStateFlow(false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(com.veyronmonitor.app.schedule.MeterReminder.EXTRA_OPEN_METER, false)) openMeter.value = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.getBooleanExtra(com.veyronmonitor.app.schedule.MeterReminder.EXTRA_OPEN_METER, false) == true) openMeter.value = true
        com.veyronmonitor.app.schedule.MeterReminder.schedule(this)
        setContent {
            VeyronMonitorTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot()
                }
            }
        }
    }
}

@Composable
private fun AppRoot() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var auth by remember { mutableStateOf<AuthState?>(null) }
    var device by remember { mutableStateOf<Device?>(null) }
    val data by Poller.latest.collectAsState()
    var isLoading by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var lastUpdatedAt by remember { mutableStateOf<Long?>(null) }
    var clockTick by remember { mutableStateOf(0L) }
    var statusPolls by remember { mutableStateOf(0) }

    var screen by remember { mutableStateOf(Screen.DASHBOARD) }
    var params by remember { mutableStateOf<JSONObject?>(null) }
    var isLoadingParams by remember { mutableStateOf(false) }
    var paramsError by remember { mutableStateOf<String?>(null) }
    var isSavingParam by remember { mutableStateOf(false) }
    var saveParamError by remember { mutableStateOf<String?>(null) }
    var saveSuccessMessage by remember { mutableStateOf<String?>(null) }

    remember { EnergyStore.load(context) }
    val energyState by EnergyStore.state.collectAsState()
    var tariff by remember { mutableStateOf(EnergyStore.tariff(context)) }
    var meterVersion by remember { mutableIntStateOf(0) }
    var batteryCfg by remember { mutableStateOf(com.veyronmonitor.app.data.BatteryConfig.get(context)) }
    val chat = remember { mutableStateListOf<com.veyronmonitor.app.ui.ChatMsg>() }
    var chatBusy by remember { mutableStateOf(false) }
    val customRates = remember(meterVersion) {
        context.getSharedPreferences("veyron_monitor_meter", android.content.Context.MODE_PRIVATE).contains("bill_rates")
    }
    val openMeterRequest by MainActivity.openMeter.collectAsState()
    var backgroundEnabled by remember { mutableStateOf(MonitorService.isEnabled(context)) }
    var batteryRestricted by remember { mutableStateOf(!MonitorService.isIgnoringBatteryOptimizations(context)) }
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // Called once we're logged in: share the session with the background
    // monitor, start it, and ask for notification permission (Android 13+).
    fun onLoggedIn(a: AuthState, d: Device) {
        Poller.setSession(a, d)
        MonitorService.start(context)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            try { notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) } catch (_: Exception) { }
        }
    }
    var schedules by remember { mutableStateOf(ScheduleStore.getAll(context)) }
    var hasUnreadWarnings by remember { mutableStateOf(WarningLogStore.hasUnread(context)) }

    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }

    // Check GitHub for a newer build once, in the background. Never blocks
    // or interrupts anything -- if it fails or finds nothing newer, it's silent.
    // If the person already dismissed this exact version's banner before,
    // don't show it again every time the app is reopened.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val found = UpdateChecker.checkForUpdate(BuildConfig.VERSION_CODE)
            val dismissedVersion = context.getSharedPreferences("veyron_monitor_prefs", android.content.Context.MODE_PRIVATE)
                .getInt("dismissed_update_version", 0)
            if (found != null && found.versionCode > dismissedVersion) {
                updateInfo = found
            }
        }
    }

    fun attemptLogin(username: String, password: String) {
        isLoading = true
        errorMessage = null
        scope.launch {
            try {
                val authResult = withContext(Dispatchers.IO) {
                    TumcApi.login(username, password)
                }
                val devices = withContext(Dispatchers.IO) {
                    TumcApi.getDevices(authResult)
                }
                if (devices.isEmpty()) {
                    errorMessage = "Koi device is account se register nahi mila."
                    isLoading = false
                    return@launch
                }
                CredentialStore.save(context, username, password)
                auth = authResult
                device = devices.first()
                onLoggedIn(authResult, devices.first())
            } catch (e: Exception) {
                errorMessage = "Login fail hua: ${e.message ?: "network error"}"
            } finally {
                isLoading = false
            }
        }
    }

    suspend fun refreshData() {
        val currentAuth = auth ?: return
        val currentDevice = device ?: return
        isRefreshing = true

        // Refresh online/offline status independently of the real-data
        // fetch below, so a transient hiccup fetching live readings never
        // freezes the online badge at a stale value.
        if (statusPolls++ % DEVICE_STATUS_EVERY_N_POLLS == 0) try {
            val devices = withContext(Dispatchers.IO) { TumcApi.getDevices(currentAuth) }
            devices.firstOrNull { it.serialNumber == currentDevice.serialNumber }?.let {
                device = it
            }
        } catch (_: Exception) {
            // non-critical -- keep showing the last known status
        }

        try {
            // Same shared routine the background service uses: fetches the
            // reading, counts its units (once only) and logs warnings.
            withContext(Dispatchers.IO) { Poller.fetchOnceLocked(context) }
            lastUpdatedAt = System.currentTimeMillis()
            errorMessage = null
            hasUnreadWarnings = WarningLogStore.hasUnread(context)
        } catch (e: Exception) {
            errorMessage = "Update fail: ${e.message ?: "network error"}"
        } finally {
            isRefreshing = false
        }
    }

    suspend fun loadParams() {
        val currentAuth = auth ?: return
        val currentDevice = device ?: return
        isLoadingParams = true
        paramsError = null
        try {
            params = withContext(Dispatchers.IO) { TumcApi.getParams(currentAuth, currentDevice) }
        } catch (e: Exception) {
            paramsError = "Parameters load nahi ho sake: ${e.message ?: "network error"}"
        } finally {
            isLoadingParams = false
        }
    }

    suspend fun setChargingPriority(value: String) {
        val currentAuth = auth ?: return
        val currentDevice = device ?: return
        isSavingParam = true
        saveParamError = null
        saveSuccessMessage = null
        try {
            // CONFIRMED via network capture of the real i.Solar app: the
            // display shows this setting as "PC" with values 1/2/3, but the
            // actual command the inverter understands uses a different key
            // ("S06") and a different value format ("PCP01"/"PCP02"/"PCP03").
            // Sending {PC: value} (what the read-side key implied) was
            // accepted by the server as well-formed but silently never
            // reached the inverter -- this is the real fix.
            val wireValue = "PCP0$value"
            withContext(Dispatchers.IO) { TumcApi.setParam(currentAuth, currentDevice, "S06", wireValue) }

            // The inverter can take a while to actually apply and report
            // back the change (the real i.Solar app itself only confirms
            // "request sent", not "applied"). So we optimistically show the
            // new selection right away instead of immediately re-fetching
            // (which would often still show the OLD value and look like
            // nothing happened), then quietly reconcile with the server
            // a bit later.
            params?.let { current ->
                val rest = current.optString("PC", "").substringAfter(' ', "")
                val patched = JSONObject(current.toString())
                patched.put("PC", if (rest.isNotEmpty()) "$value $rest" else value)
                params = patched
            }
            saveSuccessMessage = "Command bhej di gayi hai -- inverter ko apply hone mein thodi der lag sakti hai."

            delay(20_000L)
            try {
                params = withContext(Dispatchers.IO) { TumcApi.getParams(currentAuth, currentDevice) }
            } catch (_: Exception) {
                // silent -- optimistic value stays on screen if this fails
            }
        } catch (e: Exception) {
            saveParamError = "Setting apply nahi ho saki: ${e.message ?: "network error"}"
        } finally {
            isSavingParam = false
        }
    }

    LaunchedEffect(openMeterRequest) {
        if (openMeterRequest) { screen = Screen.METER; MainActivity.openMeter.value = false }
    }

    // Try auto-login from saved credentials once, on first composition.
    LaunchedEffect(Unit) {
        val saved = CredentialStore.load(context)
        if (saved != null && auth == null) {
            val (u, p) = saved
            isLoading = true
            try {
                val authResult = withContext(Dispatchers.IO) { TumcApi.login(u, p) }
                val devices = withContext(Dispatchers.IO) { TumcApi.getDevices(authResult) }
                if (devices.isNotEmpty()) {
                    auth = authResult
                    device = devices.first()
                    onLoggedIn(authResult, devices.first())
                }
            } catch (_: Exception) {
                // silent fail -> fall back to manual login screen
            } finally {
                isLoading = false
            }
        }
    }

    // Poll loop while logged in AND the app is on screen (the background
    // service takes over counting when the app is closed).
    LaunchedEffect(auth, device?.serialNumber) {
        if (auth != null && device != null) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                batteryRestricted = !MonitorService.isIgnoringBatteryOptimizations(context)
                EnergyStore.load(context) // rolls the day over if midnight passed
                while (true) {
                    refreshData()
                    clockTick = System.currentTimeMillis()
                    delay(REFRESH_INTERVAL_MS)
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        updateInfo?.let { info ->
            UpdateBanner(
                info = info,
                onDismiss = {
                    context.getSharedPreferences("veyron_monitor_prefs", android.content.Context.MODE_PRIVATE)
                        .edit().putInt("dismissed_update_version", info.versionCode).apply()
                    updateInfo = null
                },
                onUpdate = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.apkDownloadUrl)))
                }
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            when {
                auth == null || device == null -> {
                    LoginScreen(
                        isLoading = isLoading,
                        errorMessage = errorMessage,
                        onLogin = ::attemptLogin
                    )
                }
                screen == Screen.REPORTS -> {
                    androidx.activity.compose.BackHandler { screen = Screen.ENERGY }
                    com.veyronmonitor.app.ui.ReportsScreen(refreshKey = energyState.dayKey to meterVersion, onBack = { screen = Screen.ENERGY })
                }
                screen == Screen.ASSISTANT -> {
                    androidx.activity.compose.BackHandler { screen = Screen.DASHBOARD }
                    com.veyronmonitor.app.ui.AssistantScreen(chat, chatBusy, onAsk = { q ->
                        chat.add(com.veyronmonitor.app.ui.ChatMsg(true, q))
                        chatBusy = true
                        scope.launch {
                            val a = withContext(Dispatchers.IO) {
                                try { com.veyronmonitor.app.data.SolarAssistant.answer(context, q, data) }
                                catch (e: Exception) { "Jawab nahi ban saka: ${e.message}" }
                            }
                            chat.add(com.veyronmonitor.app.ui.ChatMsg(false, a))
                            chatBusy = false
                        }
                    }, onBack = { screen = Screen.DASHBOARD })
                }
                screen == Screen.SCHEDULE -> {
                    ScheduleScreen(
                        schedules = schedules,
                        needsExactAlarmPermission = !AlarmScheduler.canScheduleExact(context),
                        onRequestExactAlarmPermission = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                context.startActivity(
                                    Intent(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                        .setData(Uri.parse("package:${context.packageName}"))
                                )
                            }
                        },
                        onAdd = { newSchedule ->
                            schedules = ScheduleStore.add(context, newSchedule)
                            AlarmScheduler.schedule(context, newSchedule)
                        },
                        onToggle = { id, enabled ->
                            schedules = ScheduleStore.setEnabled(context, id, enabled)
                            val s = schedules.first { it.id == id }
                            if (enabled) AlarmScheduler.schedule(context, s) else AlarmScheduler.cancel(context, s)
                        },
                        onRemove = { id ->
                            val toCancel = schedules.firstOrNull { it.id == id }
                            schedules = ScheduleStore.remove(context, id)
                            toCancel?.let { AlarmScheduler.cancel(context, it) }
                        },
                        onBack = { screen = Screen.SETTINGS }
                    )
                }
                else -> {
                    // Main tabs, with a persistent bottom navigation bar.
                    Scaffold(
                        bottomBar = {
                            NavigationBar {
                                NavigationBarItem(
                                    selected = screen == Screen.DASHBOARD,
                                    onClick = { screen = Screen.DASHBOARD },
                                    icon = { Icon(Icons.Filled.Bolt, contentDescription = "Live") },
                                    label = { Text("Live") }
                                )
                                NavigationBarItem(
                                    selected = screen == Screen.ENERGY,
                                    onClick = { screen = Screen.ENERGY },
                                    icon = { Icon(Icons.Filled.BarChart, contentDescription = "Units") },
                                    label = { Text("Units") }
                                )
                                NavigationBarItem(
                                    selected = screen == Screen.METER,
                                    onClick = { screen = Screen.METER },
                                    icon = { Icon(Icons.Filled.Speed, contentDescription = "Meter") },
                                    label = { Text("Meter") }
                                )
                                NavigationBarItem(
                                    selected = screen == Screen.WARNINGS,
                                    onClick = {
                                        screen = Screen.WARNINGS
                                        WarningLogStore.markAllViewed(context)
                                        hasUnreadWarnings = false
                                    },
                                    icon = {
                                        BadgedBox(badge = { if (hasUnreadWarnings) Badge() }) {
                                            Icon(Icons.Filled.Warning, contentDescription = "Warnings")
                                        }
                                    },
                                    label = { Text("Warnings") }
                                )
                                NavigationBarItem(
                                    selected = screen == Screen.SETTINGS,
                                    onClick = {
                                        screen = Screen.SETTINGS
                                        scope.launch { loadParams() }
                                    },
                                    icon = { Icon(Icons.Filled.Settings, contentDescription = "Settings") },
                                    label = { Text("Settings") }
                                )
                            }
                        }
                    ) { innerPadding ->
                        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                            when (screen) {
                                Screen.ENERGY -> UnitsScreen(
                                    energy = energyState,
                                    history = remember(energyState) { EnergyStore.getHistory(context) },
                                    sampleDays = remember(energyState.dayKey) { EnergyStore.sampleDays(context) },
                                    samplesFor = { EnergyStore.getSamples(context, it) },
                                    tariff = if (customRates) remember(meterVersion, energyState.dayKey) {
                                        com.veyronmonitor.app.data.Insights.effectiveRate(context)
                                    } else tariff,
                                    onTariffChange = { EnergyStore.setTariff(context, it); tariff = it },
                                    onOpenReports = { screen = Screen.REPORTS },
                                    onRateTap = if (customRates) ({ screen = Screen.METER }) else null,
                                    backgroundEnabled = backgroundEnabled,
                                    onBackgroundToggle = {
                                        backgroundEnabled = it
                                        MonitorService.setEnabled(context, it)
                                        if (!it) BackupWorker.cancel(context)
                                    },
                                    batteryRestricted = batteryRestricted,
                                    onFixBattery = {
                                        try {
                                            context.startActivity(
                                                Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                                    .setData(Uri.parse("package:${context.packageName}"))
                                            )
                                        } catch (_: Exception) {
                                            context.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                                        }
                                    },
                                    onResetSolar = { EnergyStore.resetSolar(context) },
                                    onResetGrid = { EnergyStore.resetGrid(context) },
                                    onResetLoad = { EnergyStore.resetLoad(context) }
                                )
                                Screen.METER -> {
                                    val v = meterVersion
                                    val ms = com.veyronmonitor.app.data.MeterStore
                                    com.veyronmonitor.app.ui.MeterScreen(
                                        readings = remember(v) { ms.readings(context) },
                                        periods = remember(v) { ms.periods(context) },
                                        current = remember(v) { ms.current(context) },
                                        rates = remember(v) { ms.rates(context) },
                                        warnAt = remember(v) { ms.warnAt(context) },
                                        limitAt = remember(v) { ms.limitAt(context) },
                                        recentDays = remember(energyState.dayKey) {
                                            EnergyStore.getHistory(context).filter { it.dateKey != energyState.dayKey }
                                        },
                                        onAddReading = { at, value, bill ->
                                            ms.add(context, at, value, bill).also { if (it == null) meterVersion++ }
                                        },
                                        onRemoveReading = { ms.remove(context, it); meterVersion++ },
                                        onSetLimits = { w, l -> ms.setLimits(context, w, l); meterVersion++ },
                                        onSetRates = { ms.setRates(context, it); meterVersion++ }
                                    )
                                }
                                Screen.WARNINGS -> WarningsScreen(
                                    entries = WarningLogStore.getLog(context)
                                )
                                Screen.SETTINGS -> SettingsScreen(
                                    deviceName = device?.displayName ?: "Inverter",
                                    params = params,
                                    isLoading = isLoadingParams,
                                    errorMessage = paramsError,
                                    isSaving = isSavingParam,
                                    saveError = saveParamError,
                                    saveSuccessMessage = saveSuccessMessage,
                                    onBack = { screen = Screen.DASHBOARD },
                                    onSetChargingPriority = { value ->
                                        scope.launch { setChargingPriority(value) }
                                    },
                                    onOpenSchedule = { screen = Screen.SCHEDULE }
                                )
                                else -> {
                                    val lastUpdatedText = lastUpdatedAt?.let {
                                        SimpleDateFormat("hh:mm:ss a", Locale.getDefault()).format(Date(it))
                                    } ?: "--"

                                    // How old is the newest reading, by the inverter's own clock?
                                    @Suppress("UNUSED_VARIABLE") val tick = clockTick
                                    val readingAt = data?.let { EnergyStore.readingTime(it) } ?: 0L
                                    val msSinceFresh = if (readingAt > 0L) (System.currentTimeMillis() - readingAt).coerceAtLeast(0L) else Long.MAX_VALUE
                                    val serverSaysOnline = device?.isOnline ?: false
                                    val connectionStatus = when {
                                        msSinceFresh < LIVE_THRESHOLD_MS -> ConnectionStatus.LIVE
                                        serverSaysOnline || msSinceFresh < STALE_THRESHOLD_MS -> ConnectionStatus.RECENT
                                        else -> ConnectionStatus.OFFLINE
                                    }
                                    val minutesSinceFresh = if (readingAt > 0L) (msSinceFresh / 60_000L).toInt() else 0

                                    DashboardScreen(
                                        deviceName = device?.displayName ?: "Inverter",
                                        connectionStatus = connectionStatus,
                                        minutesSinceFresh = minutesSinceFresh,
                                        data = data,
                                        lastUpdatedText = lastUpdatedText,
                                        isRefreshing = isRefreshing,
                                        errorMessage = errorMessage,
                                        todaySolarWh = energyState.daySolarWh,
                                        todayGridWh = energyState.dayGridWh,
                                        todayLoadWh = energyState.dayLoadWh,
                                        onRefresh = { scope.launch { refreshData() } },
                                        onOpenUnits = { screen = Screen.ENERGY },
                                        runtime = remember(data, batteryCfg) { com.veyronmonitor.app.data.Insights.runtime(context, data) },
                                        battery = batteryCfg,
                                        onSaveBattery = { com.veyronmonitor.app.data.BatteryConfig.set(context, it); batteryCfg = it },
                                        onOpenAssistant = { screen = Screen.ASSISTANT },
                                        onLogout = {
                                            MonitorService.stop(context)
                                            BackupWorker.cancel(context)
                                            CredentialStore.clear(context)
                                            Poller.clear()
                                            auth = null
                                            device = null
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateBanner(info: UpdateInfo, onDismiss: () -> Unit, onUpdate: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "Nayi version available: ${info.versionLabel}",
                style = MaterialTheme.typography.bodyMedium
            )
            Row {
                TextButton(onClick = onDismiss) { Text("Baad mein") }
                Button(onClick = onUpdate) { Text("Update") }
            }
        }
    }
}
