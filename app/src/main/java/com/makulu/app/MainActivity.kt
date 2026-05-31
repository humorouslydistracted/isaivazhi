package com.makulu.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var printerManager: PrinterManager
    @Inject lateinit var csvManager: CsvManager
    @Inject lateinit var settingsRepo: SettingsRepository
    @Inject lateinit var orderRepo: OrderRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MakuluTheme {
                var isAuthenticated by remember { mutableStateOf(false) }
                var setupComplete by remember { mutableStateOf<Boolean?>(null) }

                // Check setup status
                LaunchedEffect(Unit) {
                    setupComplete = settingsRepo.get(SettingsRepository.KEY_SETUP_COMPLETE) == "true"
                }

                if (!isAuthenticated) {
                    BiometricGate(
                        activity = this@MainActivity,
                        onAuthenticated = { isAuthenticated = true }
                    )
                } else {
                    // Permission gate
                    var permissionsGranted by remember { mutableStateOf(false) }
                    if (!permissionsGranted) {
                        PermissionGate(onAllGranted = { permissionsGranted = true })
                    } else when (setupComplete) {
                    null -> {
                        // Loading
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    false -> {
                        // First-time setup
                        FirstTimeSetupFlow(
                            settingsRepo = settingsRepo,
                            printerManager = printerManager,
                            onComplete = { setupComplete = true }
                        )
                    }
                    true -> {
                        // Main app
                        LaunchedEffect(Unit) {
                            printerManager.autoReconnect()
                            // CSV sync: check if external changes were made
                            if (csvManager.hasExternalChanges()) {
                                csvManager.syncFromCsv()
                            }
                        }
                        MainApp(
                            printerManager = printerManager,
                            csvManager = csvManager,
                            settingsRepo = settingsRepo,
                            orderRepo = orderRepo
                        )
                    }
                }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// HILT APPLICATION
// ─────────────────────────────────────────────────────────────────────────────

@dagger.hilt.android.HiltAndroidApp
class MakuluApplication : android.app.Application()

// ─────────────────────────────────────────────────────────────────────────────
// BIOMETRIC GATE
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun BiometricGate(activity: FragmentActivity, onAuthenticated: () -> Unit) {
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        val biometricManager = BiometricManager.from(context)
        val canAuthenticate = biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )

        if (canAuthenticate == BiometricManager.BIOMETRIC_SUCCESS) {
            val executor = ContextCompat.getMainExecutor(context)
            val prompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onAuthenticated()
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // If user cancels, we could show retry — for now just pass through
                    if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON || errorCode == BiometricPrompt.ERROR_USER_CANCELED) {
                        // stay on gate
                    }
                }
            })

            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Makulu")
                .setSubtitle("Authenticate to continue")
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
                .build()

            prompt.authenticate(promptInfo)
        } else {
            // Device has no lock set — allow through
            onAuthenticated()
        }
    }

    // Show a splash-like screen while authenticating
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🦁", style = MaterialTheme.typography.headlineLarge)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Makulu", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Authenticating...", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// PERMISSION GATE
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun PermissionGate(onAllGranted: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var storageGranted by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else true
        )
    }
    var notificationGranted by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
            } else true
        )
    }
    var bluetoothGranted by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED
            } else true
        )
    }

    // Check if all permissions are already granted
    LaunchedEffect(storageGranted, notificationGranted, bluetoothGranted) {
        if (storageGranted && notificationGranted && bluetoothGranted) onAllGranted()
    }

    // Notification permission launcher
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationGranted = granted
    }

    // Bluetooth permission launcher
    val btLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        bluetoothGranted = granted
    }

    // Storage permission — needs intent for MANAGE_EXTERNAL_STORAGE
    val storageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        storageGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else true
    }

    if (storageGranted && notificationGranted && bluetoothGranted) return

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("🦁", style = MaterialTheme.typography.headlineLarge)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Permissions Required", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "Makulu needs storage access, Bluetooth for printing, and notifications for alerts.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(24.dp))

            if (!storageGranted) {
                Button(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            storageLauncher.launch(intent)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Folder, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Grant Storage Access")
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (!bluetoothGranted) {
                Button(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            btLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Bluetooth, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Allow Bluetooth (Printer)")
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (!notificationGranted) {
                Button(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Notifications, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Allow Notifications")
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            TextButton(onClick = onAllGranted) {
                Text("Skip for now")
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// FIRST-TIME SETUP FLOW
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun FirstTimeSetupFlow(
    settingsRepo: SettingsRepository,
    printerManager: PrinterManager,
    onComplete: () -> Unit
) {
    var step by remember { mutableStateOf(0) } // 0=PIN, 1=Printer
    val scope = rememberCoroutineScope()

    when (step) {
        0 -> PinSetupScreen(settingsRepo = settingsRepo, onDone = { step = 1 })
        1 -> PrinterSetupScreen(
            printerManager = printerManager,
            onDone = {
                scope.launch {
                    // Seed default tables
                    val db = MakuluDatabase.getInstance(printerManager.let {
                        // Get context from settingsRepo — we'll use a workaround
                        return@let null
                    } ?: return@launch)
                }
                onComplete()
            },
            onSkip = { onComplete() }
        )
    }

    // Seed defaults after setup
    LaunchedEffect(Unit) {
        // Check and seed default tables if needed
        settingsRepo.set(SettingsRepository.KEY_RECEIPT_FOOTER, "Thank you, visit again!")
        settingsRepo.set(SettingsRepository.KEY_RECEIPT_FOOTER_ENABLED, "true")
        settingsRepo.set(SettingsRepository.KEY_RECEIPT_SHOW_ORDER_NO, "true")
        settingsRepo.set(SettingsRepository.KEY_RECEIPT_SHOW_DATETIME, "true")
        settingsRepo.set(SettingsRepository.KEY_RECEIPT_SHOW_TABLE, "true")
        settingsRepo.set(SettingsRepository.KEY_RECEIPT_SHOW_ITEMS, "true")
        settingsRepo.set(SettingsRepository.KEY_RECEIPT_SHOW_TOTAL, "true")
    }
}

@Composable
fun PinSetupScreen(settingsRepo: SettingsRepository, onDone: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var selectedQuestion by remember { mutableStateOf(0) }
    var answer by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val questions = listOf(
        "What is your mother's maiden name?",
        "What is your best friend's name?",
        "What is your favorite sport?",
        "What city were you born in?",
        "What is your pet's name?"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp)
            .statusBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(32.dp))
        Text("🦁", style = MaterialTheme.typography.headlineLarge)
        Text("Setup Admin PIN", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(24.dp))

        OutlinedTextField(
            value = pin,
            onValueChange = { if (it.length <= 4 && it.all { c -> c.isDigit() }) pin = it },
            label = { Text("4-digit PIN") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = makuluOutlinedTextFieldColors()
        )
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = confirmPin,
            onValueChange = { if (it.length <= 4 && it.all { c -> c.isDigit() }) confirmPin = it },
            label = { Text("Confirm PIN") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = makuluOutlinedTextFieldColors()
        )
        Spacer(modifier = Modifier.height(16.dp))

        // Security question
        Text("Security Question (for PIN recovery)", style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(8.dp))

        questions.forEachIndexed { i, q ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                RadioButton(selected = selectedQuestion == i, onClick = { selectedQuestion = i })
                Text(q, style = MaterialTheme.typography.bodyMedium)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = answer,
            onValueChange = { input ->
                // Only lowercase alphabets
                answer = input.filter { it.isLetter() }.lowercase()
            },
            label = { Text("Answer (lowercase letters only)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = makuluOutlinedTextFieldColors()
        )

        error?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                when {
                    pin.length != 4 -> error = "PIN must be 4 digits"
                    pin != confirmPin -> error = "PINs don't match"
                    answer.isBlank() -> error = "Answer is required"
                    else -> {
                        scope.launch {
                            settingsRepo.set(SettingsRepository.KEY_ADMIN_PIN, pin)
                            settingsRepo.set(SettingsRepository.KEY_SECURITY_QUESTION, questions[selectedQuestion])
                            settingsRepo.set(SettingsRepository.KEY_SECURITY_ANSWER, answer)
                            settingsRepo.set(SettingsRepository.KEY_SETUP_COMPLETE, "true")
                            onDone()
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Continue")
        }
    }
}

@Composable
fun PrinterSetupScreen(
    printerManager: PrinterManager,
    onDone: () -> Unit,
    onSkip: () -> Unit
) {
    val isConnected by printerManager.isConnected.collectAsState()
    val pairedDevices = remember { printerManager.getPairedDevices() }
    var connecting by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp)
            .statusBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(32.dp))
        Icon(Icons.Default.Print, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(8.dp))
        Text("Connect Printer", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Select your PosBox printer from paired devices", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(24.dp))

        if (!printerManager.isBluetoothEnabled()) {
            Text("⚠️ Bluetooth is OFF. Turn it on first.", color = MaterialTheme.colorScheme.error)
        } else if (pairedDevices.isEmpty()) {
            Text("No paired devices found. Pair your printer in Android Bluetooth Settings first.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            pairedDevices.forEach { device ->
                @Suppress("MissingPermission")
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    onClick = {
                        connecting = true
                        printerManager.connectToPrinter(device) { success ->
                            connecting = false
                            if (success) onDone()
                        }
                    }
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Bluetooth, contentDescription = null)
                        Spacer(modifier = Modifier.width(12.dp))
                        @Suppress("MissingPermission")
                        Text(device.name ?: device.address, modifier = Modifier.weight(1f))
                        if (connecting) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
            }
        }

        if (isConnected) {
            Spacer(modifier = Modifier.height(16.dp))
            Text("✅ Connected!", color = Olive, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
        }

        Spacer(modifier = Modifier.weight(1f))

        TextButton(onClick = onSkip) {
            Text("Skip for now")
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// MAIN APP (after auth + setup)
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainApp(
    printerManager: PrinterManager,
    csvManager: CsvManager,
    settingsRepo: SettingsRepository,
    orderRepo: OrderRepository
) {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val isConnected by printerManager.isConnected.collectAsState()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route.orEmpty()
    val currentAdminSection = backStackEntry?.arguments?.getString("section")?.toIntOrNull()
    val snackbarHostState = remember { SnackbarHostState() }

    var adminUnlocked by remember { mutableStateOf(false) }
    var adminLeftAt by remember { mutableStateOf(0L) }
    var adminJustLocked by remember { mutableStateOf(false) }
    var printerBannerDismissed by remember { mutableStateOf(false) }

    LaunchedEffect(isConnected) {
        if (isConnected) printerBannerDismissed = false
    }

    // Admin lock logic: lock after 10s grace period when leaving admin pages
    val isOnAdminPage = currentRoute.startsWith("admin")
    LaunchedEffect(isOnAdminPage, adminUnlocked) {
        if (adminUnlocked && !isOnAdminPage) {
            // User left admin pages — start grace period
            adminLeftAt = System.currentTimeMillis()
            while (true) {
                delay(1000)
                val stillOutside = !navController.currentBackStackEntry?.destination?.route.orEmpty().startsWith("admin")
                if (stillOutside == true && System.currentTimeMillis() - adminLeftAt > 10_000) {
                    adminUnlocked = false
                    adminJustLocked = true
                    break
                }
                // If user went back to admin, cancel
                if (stillOutside == false) break
            }
        }
    }

    LaunchedEffect(adminJustLocked) {
        if (adminJustLocked) {
            delay(1600)
            adminJustLocked = false
        }
    }

    BackHandler(enabled = currentRoute != "home") {
        navController.navigate("home") {
            popUpTo("home") { inclusive = false }
            launchSingleTop = true
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Spacer(modifier = Modifier.height(16.dp))
                Text("  🦁 Makulu", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp))
                HorizontalDivider()

                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Receipt, contentDescription = null) },
                    label = { Text("Today's Orders") },
                    selected = currentRoute == "today_orders",
                    onClick = {
                        scope.launch { drawerState.close() }
                        navController.navigate("today_orders") { launchSingleTop = true }
                    }
                )

                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.AttachMoney, contentDescription = null) },
                    label = { Text("Shop Spending") },
                    selected = currentRoute == "spending",
                    onClick = {
                        scope.launch { drawerState.close() }
                        navController.navigate("spending") { launchSingleTop = true }
                    }
                )

                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Print, contentDescription = null) },
                    label = { Text("Printer") },
                    selected = currentRoute == "printer",
                    onClick = {
                        scope.launch { drawerState.close() }
                        navController.navigate("printer") { launchSingleTop = true }
                    }
                )

                HorizontalDivider()

                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.AdminPanelSettings, contentDescription = null) },
                    label = { Text(if (adminUnlocked) "🔓 Admin" else "🔒 Admin") },
                    selected = currentRoute.contains("admin") && currentAdminSection == null,
                    onClick = {
                        scope.launch { drawerState.close() }
                        if (adminUnlocked) {
                            navController.navigate("admin/0") { launchSingleTop = true }
                        } else {
                            navController.navigate("admin_pin/0") { launchSingleTop = true }
                        }
                    }
                )

                if (adminUnlocked) {
                    listOf(
                        "Tables" to 0,
                        "Menu Items" to 1,
                        "Ledger" to 2,
                        "Spending" to 3,
                        "Analysis" to 4,
                        "CSV Backup" to 5,
                        "Receipt" to 6,
                        "Inclusions" to 7
                    ).forEach { (label, section) ->
                        NavigationDrawerItem(
                            icon = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
                            label = { Text(label) },
                            selected = currentRoute == "admin/{section}" && currentAdminSection == section,
                            onClick = {
                                scope.launch { drawerState.close() }
                                navController.navigate("admin/$section") { launchSingleTop = true }
                            },
                            modifier = Modifier.padding(start = 24.dp, end = 8.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))
                HorizontalDivider()
                Text("  Makulu v1.0.0", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            }
        }
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = {
                        TextButton(onClick = {
                            navController.navigate("home") { popUpTo("home") { inclusive = true } }
                        }) {
                            Text("🦁 Makulu", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Menu")
                        }
                    },
                    actions = {
                        // Printer status dot
                        Icon(
                            Icons.Default.Circle,
                            contentDescription = if (isConnected) "Printer connected" else "Printer not connected",
                            tint = if (isConnected) PrinterConnected else PrinterDisconnected,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                    }
                )
            }
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = "home",
                modifier = Modifier.padding(padding)
            ) {
                composable("home") {
                    val orderVm: OrderViewModel = hiltViewModel()
                    OrderScreen(
                        viewModel = orderVm,
                        onPrintReceipt = { orderId ->
                            scope.launch {
                                val owi = orderRepo.getOrderWithItems(orderId)
                                if (owi == null) {
                                    snackbarHostState.showSnackbar("❌ Order not found")
                                    return@launch
                                }
                                if (!printerManager.isConnected.value) {
                                    snackbarHostState.showSnackbar("🖨️ Printer not connected. Go to Settings → Printer to connect.")
                                    return@launch
                                }
                                snackbarHostState.showSnackbar("🖨️ Printing receipt...")
                                val success = printerManager.printReceipt(owi)
                                if (success) {
                                    snackbarHostState.showSnackbar("✅ Receipt printed successfully")
                                } else {
                                    snackbarHostState.showSnackbar("❌ Print failed. Check printer connection.")
                                }
                            }
                        },
                        onPrintKitchenOrder = { orderId ->
                            scope.launch {
                                val owi = orderRepo.getOrderWithItems(orderId)
                                if (owi == null) {
                                    snackbarHostState.showSnackbar("❌ Order not found")
                                    return@launch
                                }
                                if (!printerManager.isConnected.value) {
                                    snackbarHostState.showSnackbar("🖨️ Printer not connected. Go to Settings → Printer to connect.")
                                    return@launch
                                }
                                snackbarHostState.showSnackbar("🖨️ Printing kitchen order...")
                                val success = printerManager.printKitchenOrder(owi)
                                if (success) {
                                    snackbarHostState.showSnackbar("✅ Kitchen order printed")
                                } else {
                                    snackbarHostState.showSnackbar("❌ Print failed. Check printer connection.")
                                }
                            }
                        },
                        onPreviewOrder = { /* handled internally by OrderScreen */ }
                    )
                }

                composable("today_orders") {
                    TodayOrdersSidebar(
                        orderRepo = orderRepo,
                        csvManager = csvManager,
                        onPrintReceipt = { orderId ->
                            scope.launch {
                                val owi = orderRepo.getOrderWithItems(orderId)
                                if (owi == null) { snackbarHostState.showSnackbar("❌ Order not found"); return@launch }
                                if (!printerManager.isConnected.value) { snackbarHostState.showSnackbar("🖨️ Printer not connected"); return@launch }
                                val success = printerManager.printReceipt(owi)
                                snackbarHostState.showSnackbar(if (success) "✅ Receipt printed" else "❌ Print failed")
                            }
                        },
                        onPrintKitchenOrder = { orderId ->
                            scope.launch {
                                val owi = orderRepo.getOrderWithItems(orderId)
                                if (owi == null) { snackbarHostState.showSnackbar("❌ Order not found"); return@launch }
                                if (!printerManager.isConnected.value) { snackbarHostState.showSnackbar("🖨️ Printer not connected"); return@launch }
                                val success = printerManager.printKitchenOrder(owi)
                                snackbarHostState.showSnackbar(if (success) "✅ Kitchen order printed" else "❌ Print failed")
                            }
                        },
                        onUpdateOrder = { orderId ->
                            scope.launch {
                                val result = orderRepo.reopenCompletedOrder(orderId)
                                if (result == null) {
                                    snackbarHostState.showSnackbar("❌ Table has active order. Complete it first.")
                                } else {
                                    snackbarHostState.showSnackbar("✏️ Order reopened on ${result}. Go to homepage.")
                                    navController.navigate("home") { launchSingleTop = true }
                                }
                            }
                        }
                    )
                }

                composable("spending") {
                    ShopSpendingSidebar()
                }

                composable("printer") {
                    AdminPrinterSection(printerManager = printerManager, settingsRepo = settingsRepo)
                }

                composable("admin_pin/{section}") { entry ->
                    val section = entry.arguments?.getString("section")?.toIntOrNull() ?: 0
                    AdminPinScreen(
                        onPinVerified = {
                            adminUnlocked = true
                            navController.navigate("admin/$section") {
                                popUpTo("admin_pin/$section") { inclusive = true }
                            }
                        },
                        settingsRepo = settingsRepo,
                        onForgotPin = {
                            navController.navigate("forgot_pin") { launchSingleTop = true }
                        }
                    )
                }

                composable("admin/{section}") { entry ->
                    val section = entry.arguments?.getString("section")?.toIntOrNull() ?: 0
                    AdminScreen(
                        initialSection = section,
                        csvManager = csvManager,
                        settingsRepo = settingsRepo
                    )
                }

                composable("reset_pin") {
                    ResetPinScreen(settingsRepo = settingsRepo, onDone = {
                        navController.navigate("home") {
                            popUpTo("home") { inclusive = false }
                            launchSingleTop = true
                        }
                    })
                }

                composable("forgot_pin") {
                    ResetPinScreen(settingsRepo = settingsRepo, onDone = {
                        adminUnlocked = true
                        navController.navigate("admin/0") {
                            popUpTo("forgot_pin") { inclusive = true }
                        }
                    })
                }
            }

            // Printer banner (non-blocking, shown when not connected)
            if (!isConnected && !printerBannerDismissed) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(padding),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.PrintDisabled, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Printer not connected", style = MaterialTheme.typography.bodySmall)
                        Spacer(modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            navController.navigate("printer") { launchSingleTop = true }
                        }) { Text("Connect", style = MaterialTheme.typography.bodySmall) }
                        IconButton(onClick = { printerBannerDismissed = true }) {
                            Icon(Icons.Default.Close, contentDescription = "Dismiss")
                        }
                    }
                }
            }

            if (adminJustLocked) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.large,
                        tonalElevation = 8.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("Admin locked", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// RESET PIN SCREEN (via security question)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun ResetPinScreen(settingsRepo: SettingsRepository, onDone: () -> Unit) {
    var step by remember { mutableStateOf(0) } // 0=answer question, 1=set new PIN
    var answer by remember { mutableStateOf("") }
    var question by remember { mutableStateOf("") }
    var savedAnswer by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        question = settingsRepo.get(SettingsRepository.KEY_SECURITY_QUESTION) ?: "Security question not set"
        savedAnswer = settingsRepo.get(SettingsRepository.KEY_SECURITY_ANSWER) ?: ""
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp)
            .statusBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(48.dp))
        Icon(Icons.Default.LockReset, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(16.dp))
        Text("Reset Admin PIN", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(24.dp))

        when (step) {
            0 -> {
                // Security question step
                Text(question, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = answer,
                    onValueChange = { input -> answer = input.filter { it.isLetter() }.lowercase() },
                    label = { Text("Answer (lowercase letters only)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = makuluOutlinedTextFieldColors()
                )
                error?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        if (answer == savedAnswer) {
                            error = null
                            step = 1
                        } else {
                            error = "Incorrect answer. Try again."
                        }
                    },
                    enabled = answer.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Verify") }
            }
            1 -> {
                // Set new PIN step
                Text("Set your new 4-digit PIN", style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = newPin,
                    onValueChange = { if (it.length <= 4 && it.all { c -> c.isDigit() }) newPin = it },
                    label = { Text("New PIN") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = makuluOutlinedTextFieldColors()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = confirmPin,
                    onValueChange = { if (it.length <= 4 && it.all { c -> c.isDigit() }) confirmPin = it },
                    label = { Text("Confirm PIN") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = makuluOutlinedTextFieldColors()
                )
                error?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        when {
                            newPin.length != 4 -> error = "PIN must be 4 digits"
                            newPin != confirmPin -> error = "PINs don't match"
                            else -> {
                                scope.launch {
                                    settingsRepo.set(SettingsRepository.KEY_ADMIN_PIN, newPin)
                                    onDone()
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Save New PIN") }
            }
        }
    }
}
