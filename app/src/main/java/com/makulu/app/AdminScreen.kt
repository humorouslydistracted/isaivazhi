package com.makulu.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

// Indian date format helper
private fun formatIndianDateTime(isoDateTime: String?): String {
    if (isoDateTime == null) return ""
    return try {
        val ldt = LocalDateTime.parse(isoDateTime, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        ldt.format(DateTimeFormatter.ofPattern("dd-MM-yyyy hh:mm a"))
    } catch (_: Exception) { isoDateTime }
}

// Input sanitizer: allows letters, digits, spaces, and safe special chars
private fun sanitizeInput(input: String, maxLen: Int = 50): String {
    return input.filter { c ->
        c.isLetterOrDigit() || c in " -./()&@#:+₹%"
    }.take(maxLen)
}

// Permissive sanitizer for receipt header/footer fields — allows common punctuation
private fun sanitizeReceiptInput(input: String, maxLen: Int = 60): String {
    return input.filter { c ->
        c.isLetterOrDigit() || c in " -./()&@#:+₹%!',_*?\"~;"
    }.take(maxLen)
}

// ─────────────────────────────────────────────────────────────────────────────
// ADMIN PIN ENTRY SCREEN
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun AdminPinScreen(
    onPinVerified: () -> Unit,
    settingsRepo: SettingsRepository,
    onForgotPin: () -> Unit = {}
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var verifying by remember { mutableStateOf(false) }
    var lastAttemptedPin by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current

    fun submitPin() {
        if (pin.length != 4 || verifying) return
        verifying = true
        val currentPin = pin
        lastAttemptedPin = currentPin
        scope.launch {
            val savedPin = settingsRepo.get(SettingsRepository.KEY_ADMIN_PIN)
            if (currentPin == savedPin) {
                keyboard?.hide()
                error = null
                onPinVerified()
            } else {
                error = "Incorrect PIN"
                pin = ""
            }
            verifying = false
        }
    }

    LaunchedEffect(pin) {
        if (pin.length < 4) lastAttemptedPin = null
        if (pin.length == 4 && pin != lastAttemptedPin) submitPin()
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().imePadding()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(this@BoxWithConstraints.maxHeight * 0.18f))
            Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(16.dp))
            Text("Enter Admin PIN", style = MaterialTheme.typography.headlineSmall)
            Spacer(modifier = Modifier.height(24.dp))

            OutlinedTextField(
                value = pin,
                onValueChange = { if (it.length <= 4 && it.all { c -> c.isDigit() }) pin = it },
                label = { Text("4-digit PIN") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { submitPin() }),
                singleLine = true,
                colors = makuluOutlinedTextFieldColors()
            )

            error?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }

            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = { submitPin() }, enabled = pin.length == 4 && !verifying) {
                Text(if (verifying) "Checking..." else "Unlock")
            }
            Spacer(modifier = Modifier.height(16.dp))
            TextButton(onClick = onForgotPin) {
                Text("Forgot PIN?", color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ADMIN MAIN SCREEN (after PIN verified)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun AdminScreen(
    initialSection: Int = 0,
    adminVm: AdminViewModel = hiltViewModel(),
    ledgerVm: LedgerViewModel = hiltViewModel(),
    spendVm: SpendingViewModel = hiltViewModel(),
    analysisVm: AnalysisViewModel = hiltViewModel(),
    csvManager: CsvManager,
    settingsRepo: SettingsRepository
) {
    var selectedSection by remember(initialSection) { mutableStateOf(initialSection) }
    val sections = listOf("Tables", "Menu Items", "Ledger", "Spending", "Analysis", "CSV Backup", "Receipt", "Inclusions")

    Column(modifier = Modifier.fillMaxSize()) {
        // Section content
        when (selectedSection) {
            0 -> AdminTablesSection(adminVm)
            1 -> AdminMenuItemsSection(adminVm)
            2 -> AdminLedgerSection(ledgerVm)
            3 -> AdminSpendingSection(spendVm)
            4 -> AdminAnalysisSection(analysisVm)
            5 -> AdminCsvSection(csvManager)
            6 -> AdminReceiptSection(adminVm, settingsRepo)
            7 -> AdminInclusionsSection(settingsRepo)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// TABLES SECTION
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun AdminTablesSection(vm: AdminViewModel) {
    val tables by vm.tables.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var newTableName by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<TableInfo?>(null) }
    var renameText by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Tables (${tables.size})", style = MaterialTheme.typography.titleLarge)
            Button(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text(" Add Table")
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text("Drag to reorder • Tap to rename • Swipe to delete", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn {
            items(tables, key = { it.id }) { table ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.DragHandle, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(table.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        IconButton(onClick = { renameTarget = table; renameText = table.name }) {
                            Icon(Icons.Default.Edit, contentDescription = "Rename")
                        }
                        IconButton(onClick = { vm.deleteTable(table) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }

    // Add dialog
    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Add Table") },
            text = {
                    OutlinedTextField(
                        value = newTableName,
                        onValueChange = { if (it.length <= 3 && it.all { c -> c.isLetterOrDigit() }) newTableName = it },
                        label = { Text("Table Name (up to 3 chars)") },
                        singleLine = true,
                        colors = makuluOutlinedTextFieldColors()
                    )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newTableName.isNotBlank()) { vm.addTable(newTableName); newTableName = ""; showAddDialog = false }
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { showAddDialog = false }) { Text("Cancel") } }
        )
    }

    // Rename dialog
    renameTarget?.let { table ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename Table") },
            text = {
                Column {
                    Text("Old orders in ledger will keep the previous name.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { if (it.length <= 3 && it.all { c -> c.isLetterOrDigit() }) renameText = it },
                        label = { Text("New Name") },
                        singleLine = true,
                        colors = makuluOutlinedTextFieldColors()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (renameText.isNotBlank()) { vm.renameTable(table, renameText); renameTarget = null }
                }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// MENU ITEMS SECTION
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun AdminMenuItemsSection(vm: AdminViewModel) {
    val categories by vm.categories.collectAsState()
    val allItems by vm.allItems.collectAsState()
    var expandedCat by remember { mutableStateOf<Long?>(null) }
    var showAddCategoryDialog by remember { mutableStateOf(false) }
    var showAddItemDialog by remember { mutableStateOf(false) }
    var addItemCategoryId by remember { mutableStateOf<Long?>(null) }
    var newCategoryName by remember { mutableStateOf("") }
    var newItemName by remember { mutableStateOf("") }
    var newItemPrice by remember { mutableStateOf("") }
    var editingItem by remember { mutableStateOf<MenuItem?>(null) }
    var editName by remember { mutableStateOf("") }
    var editPrice by remember { mutableStateOf("") }
    var categoryDeleteTarget by remember { mutableStateOf<Category?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Menu Items", style = MaterialTheme.typography.titleLarge)
            Button(onClick = { showAddCategoryDialog = true }) {
                Text("+ Category")
            }
        }
        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn {
            categories.forEach { cat ->
                val catItems = allItems.filter { it.categoryId == cat.id }
                item(key = "cat_${cat.id}") {
                    Surface(
                        onClick = { expandedCat = if (expandedCat == cat.id) null else cat.id },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(cat.name, fontWeight = FontWeight.Bold, fontSize = 20.sp, modifier = Modifier.weight(1f))
                            Text("${catItems.size}", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { addItemCategoryId = cat.id; showAddItemDialog = true }) {
                                Text("ADD", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                            IconButton(onClick = {
                                categoryDeleteTarget = cat
                            }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete Category", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }

                if (expandedCat == cat.id) {
                    items(catItems, key = { it.id }) { menuItem ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(menuItem.name, fontSize = 20.sp)
                                Text("₹${"%.2f".format(menuItem.price)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            // Availability checkbox
                            Checkbox(
                                checked = menuItem.isAvailable,
                                onCheckedChange = { vm.toggleAvailability(menuItem) }
                            )
                            IconButton(onClick = { editingItem = menuItem; editName = menuItem.name; editPrice = menuItem.price.toString() }) {
                                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                            IconButton(onClick = { vm.deleteItem(menuItem) }) {
                                Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    // Add Category Dialog
    if (showAddCategoryDialog) {
        AlertDialog(
            onDismissRequest = { showAddCategoryDialog = false },
            title = { Text("Add Category") },
            text = {
                OutlinedTextField(value = newCategoryName, onValueChange = { newCategoryName = sanitizeInput(it, 30) }, label = { Text("Category Name") }, singleLine = true, colors = makuluOutlinedTextFieldColors())
            },
            confirmButton = { TextButton(onClick = { vm.addCategory(newCategoryName); newCategoryName = ""; showAddCategoryDialog = false }) { Text("Add") } },
            dismissButton = { TextButton(onClick = { showAddCategoryDialog = false }) { Text("Cancel") } }
        )
    }

    // Add Item Dialog
    if (showAddItemDialog) {
        AlertDialog(
            onDismissRequest = { showAddItemDialog = false },
            title = { Text("Add Menu Item") },
            text = {
                Column {
                    OutlinedTextField(value = newItemName, onValueChange = { newItemName = sanitizeInput(it, 40) }, label = { Text("Item Name") }, singleLine = true, colors = makuluOutlinedTextFieldColors())
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newItemPrice, onValueChange = { newItemPrice = it },
                        label = { Text("Price (₹)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        colors = makuluOutlinedTextFieldColors()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val price = newItemPrice.toDoubleOrNull()
                    if (newItemName.isNotBlank() && price != null && addItemCategoryId != null) {
                        vm.addItem(newItemName, price, addItemCategoryId!!)
                        newItemName = ""; newItemPrice = ""; showAddItemDialog = false
                    }
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { showAddItemDialog = false }) { Text("Cancel") } }
        )
    }

    // Edit Item Dialog
    editingItem?.let { item ->
        AlertDialog(
            onDismissRequest = { editingItem = null },
            title = { Text("Edit ${item.name}") },
            text = {
                Column {
                    OutlinedTextField(value = editName, onValueChange = { editName = sanitizeInput(it, 40) }, label = { Text("Name") }, singleLine = true, colors = makuluOutlinedTextFieldColors())
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = editPrice, onValueChange = { editPrice = it }, label = { Text("Price (₹)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val price = editPrice.toDoubleOrNull()
                    if (editName.isNotBlank() && price != null) {
                        vm.updateItem(item.copy(name = editName, price = price))
                        editingItem = null
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editingItem = null }) { Text("Cancel") } }
        )
    }

    // Category delete confirmation (Option B: delete items + category)
    categoryDeleteTarget?.let { category ->
        val catItems = allItems.filter { it.categoryId == category.id }
        AlertDialog(
            onDismissRequest = { categoryDeleteTarget = null },
            title = { Text("Delete Category") },
            text = {
                if (catItems.isNotEmpty()) {
                    Text("This will permanently delete \"${category.name}\" and all ${catItems.size} items inside it. Continue?")
                } else {
                    Text("Delete empty category \"${category.name}\"?")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (catItems.isNotEmpty()) {
                        vm.deleteCategoryWithItems(category)
                    } else {
                        vm.deleteCategory(category)
                    }
                    categoryDeleteTarget = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { categoryDeleteTarget = null }) { Text("Cancel") } }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// LEDGER SECTION
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun AdminLedgerSection(vm: LedgerViewModel) {
    val selectedTab by vm.selectedTab.collectAsState()
    val todayOrders by vm.todayOrders.collectAsState()
    val weekOrders by vm.weekOrders.collectAsState()
    val monthOrders by vm.monthOrders.collectAsState()
    val revenueToday by vm.revenueToday.collectAsState()
    val revenueWeek by vm.revenueWeek.collectAsState()
    val revenueMonth by vm.revenueMonth.collectAsState()

    var selectedOrder by remember { mutableStateOf<OrderWithItems?>(null) }
    var pendingDeleteOrderId by remember { mutableStateOf<Long?>(null) }

    val orders = when (selectedTab) { 0 -> todayOrders; 1 -> todayOrders; 2 -> weekOrders; 3 -> monthOrders; else -> todayOrders }
    val revenue = when (selectedTab) { 1 -> revenueToday; 2 -> revenueWeek; 3 -> revenueMonth; else -> revenueToday }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        TabRow(selectedTabIndex = selectedTab) {
            listOf("History", "Today", "Week", "Month").forEachIndexed { i, t ->
                Tab(selected = selectedTab == i, onClick = { vm.selectTab(i) }) { Text(t, modifier = Modifier.padding(12.dp)) }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Summary
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Total Orders: ${orders.size}", style = MaterialTheme.typography.titleMedium)
                val totalDiscount = orders.sumOf { it.order.discountAmount }
                val totalCgst = orders.sumOf { it.order.cgstAmount }
                val totalSgst = orders.sumOf { it.order.sgstAmount }
                val totalFinal = orders.sumOf { if (it.order.finalTotal > 0) it.order.finalTotal else it.order.totalAmount }
                if (totalDiscount > 0) Text("Discount: -₹${"%.2f".format(totalDiscount)}", style = MaterialTheme.typography.bodyMedium)
                if (totalCgst > 0 || totalSgst > 0) Text("CGST: ₹${"%.2f".format(totalCgst)}  SGST: ₹${"%.2f".format(totalSgst)}", style = MaterialTheme.typography.bodyMedium)
                Text("Revenue: ₹${"%.2f".format(totalFinal)}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Olive)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Order list
        LazyColumn {
            items(orders) { owi ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    onClick = { selectedOrder = owi }
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(owi.order.tableName, fontWeight = FontWeight.Bold)
                            Text("${owi.order.orderNumber} • ${owi.items.size} items", style = MaterialTheme.typography.bodySmall)
                        }
                        Text("₹${"%.2f".format(if (owi.order.finalTotal > 0) owi.order.finalTotal else owi.order.totalAmount)}", fontWeight = FontWeight.Bold)
                        if (pendingDeleteOrderId == owi.order.id) {
                            IconButton(onClick = {
                                vm.deleteOrder(owi.order)
                                pendingDeleteOrderId = null
                            }) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = Olive, modifier = Modifier.size(18.dp))
                            }
                            IconButton(onClick = { pendingDeleteOrderId = null }) {
                                Icon(Icons.Default.Close, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                            }
                        } else {
                            IconButton(onClick = { pendingDeleteOrderId = owi.order.id }) {
                                Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    // Order detail dialog
    selectedOrder?.let { owi ->
        AlertDialog(
            onDismissRequest = { selectedOrder = null },
            title = { Text("${owi.order.orderNumber}") },
            text = {
                Column {
                    Text("Table: ${owi.order.tableName}")
                    Text("Date: ${formatIndianDateTime(owi.order.completedAt ?: owi.order.createdAt)}")
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    owi.items.forEach { item ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(item.menuItemName, modifier = Modifier.weight(1f))
                            Text("x${item.quantity}")
                            Text(" ₹${"%.2f".format(item.lineTotal)}", modifier = Modifier.width(80.dp))
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text("Total: ₹${"%.2f".format(owi.order.totalAmount)}", fontWeight = FontWeight.Bold)
                }
            },
            confirmButton = { TextButton(onClick = { selectedOrder = null }) { Text("Close") } }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// SPENDING SECTION (Full — Admin)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun AdminSpendingSection(vm: SpendingViewModel) {
    val allSpends by vm.allSpends.collectAsState()
    val todayTotal by vm.todayTotal.collectAsState()
    val weekTotal by vm.weekTotal.collectAsState()
    val monthTotal by vm.monthTotal.collectAsState()
    var selectedTab by remember { mutableStateOf(0) }
    var showAddDialog by remember { mutableStateOf(false) }
    var editingSpend by remember { mutableStateOf<ShopSpend?>(null) }
    var spendName by remember { mutableStateOf("") }
    var spendAmount by remember { mutableStateOf("") }
    var spendDate by remember { mutableStateOf("") }
    var pendingDeleteSpendId by remember { mutableStateOf<Long?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Shop Spending", style = MaterialTheme.typography.titleLarge)
            Button(onClick = {
                editingSpend = null
                spendName = ""
                spendAmount = ""
                spendDate = ""
                showAddDialog = true
            }) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text(" Add Spend")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        TabRow(selectedTabIndex = selectedTab) {
            listOf("History", "Today", "Week", "Month").forEachIndexed { i, t ->
                Tab(selected = selectedTab == i, onClick = { selectedTab = i }) { Text(t, modifier = Modifier.padding(12.dp)) }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            val total = when (selectedTab) { 1 -> todayTotal; 2 -> weekTotal; 3 -> monthTotal; else -> allSpends.sumOf { it.amount } }
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Total Spent: ₹${"%.2f".format(total)}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn {
            items(allSpends) { spend ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    onClick = {
                        editingSpend = spend
                        spendName = spend.itemName
                        spendAmount = spend.amount.toString()
                        spendDate = spend.date
                    }
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(spend.itemName, fontWeight = FontWeight.Medium)
                            Text(spend.date, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("₹${"%.2f".format(spend.amount)}", fontWeight = FontWeight.Bold)
                        IconButton(onClick = {
                            editingSpend = spend
                            spendName = spend.itemName
                            spendAmount = spend.amount.toString()
                            spendDate = spend.date
                        }) {
                            Icon(Icons.Default.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        }
                        if (pendingDeleteSpendId == spend.id) {
                            IconButton(onClick = {
                                vm.deleteSpend(spend)
                                pendingDeleteSpendId = null
                            }) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = Olive, modifier = Modifier.size(18.dp))
                            }
                            IconButton(onClick = { pendingDeleteSpendId = null }) {
                                Icon(Icons.Default.Close, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                            }
                        } else {
                            IconButton(onClick = { pendingDeleteSpendId = spend.id }) {
                                Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        var showDatePicker by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Add Spend") },
            text = {
                Column {
                    OutlinedTextField(value = spendName, onValueChange = { spendName = sanitizeInput(it, 50) }, label = { Text("Item Name") }, singleLine = true, colors = makuluOutlinedTextFieldColors())
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = spendAmount, onValueChange = { spendAmount = it }, label = { Text("Amount") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, colors = makuluOutlinedTextFieldColors())
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.CalendarToday, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (spendDate.isBlank()) "Today (tap to change)" else spendDate)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val amount = spendAmount.toDoubleOrNull()
                    if (spendName.isNotBlank() && amount != null) {
                        vm.addSpend(spendName, amount, spendDate.ifBlank { null })
                        spendName = ""
                        spendAmount = ""
                        spendDate = ""
                        showAddDialog = false
                    }
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { showAddDialog = false }) { Text("Cancel") } }
        )
        if (showDatePicker) {
            MakuluDatePicker(
                onDateSelected = { spendDate = it },
                onDismiss = { showDatePicker = false }
            )
        }
    }

    editingSpend?.let { spend ->
        var showDatePicker by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { editingSpend = null },
            title = { Text("Edit Spend") },
            text = {
                Column {
                    OutlinedTextField(value = spendName, onValueChange = { spendName = sanitizeInput(it, 50) }, label = { Text("Item Name") }, singleLine = true, colors = makuluOutlinedTextFieldColors())
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = spendAmount, onValueChange = { spendAmount = it }, label = { Text("Amount") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, colors = makuluOutlinedTextFieldColors())
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.CalendarToday, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (spendDate.isBlank()) "Select date" else spendDate)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val amount = spendAmount.toDoubleOrNull()
                    if (spendName.isNotBlank() && amount != null && spendDate.isNotBlank()) {
                        vm.updateSpend(spend.copy(itemName = spendName, amount = amount, date = spendDate))
                        editingSpend = null
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editingSpend = null }) { Text("Cancel") } }
        )
        if (showDatePicker) {
            MakuluDatePicker(
                onDateSelected = { spendDate = it },
                onDismiss = { showDatePicker = false }
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ANALYSIS SECTION
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun AdminAnalysisSection(vm: AnalysisViewModel) {
    val period by vm.selectedPeriod.collectAsState()
    val summary by vm.summary.collectAsState()
    val showAll by vm.showAll.collectAsState()
    val ascending by vm.ascending.collectAsState()
    var showCategory by remember { mutableStateOf(false) } // false=List, true=Category

    LaunchedEffect(Unit) { vm.loadAnalysis() }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // Period selector
        TabRow(selectedTabIndex = period) {
            listOf("Today", "Week", "Month").forEachIndexed { i, t ->
                Tab(selected = period == i, onClick = { vm.selectPeriod(i) }) { Text(t, modifier = Modifier.padding(12.dp)) }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Revenue vs Spending
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Revenue", style = MaterialTheme.typography.bodySmall)
                    Text("₹${"%.0f".format(summary.revenue)}", fontWeight = FontWeight.Bold, color = Olive)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Spending", style = MaterialTheme.typography.bodySmall)
                    Text("₹${"%.0f".format(summary.spending)}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Net", style = MaterialTheme.typography.bodySmall)
                    Text("₹${"%.0f".format(summary.net)}", fontWeight = FontWeight.Bold, color = if (summary.net >= 0) Olive else MaterialTheme.colorScheme.error)
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Controls: Ascending/Descending + List/Category toggle
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { vm.toggleSortOrder() }) {
                Text(if (ascending) "↑ Ascending" else "↓ Descending")
            }
            TextButton(onClick = { showCategory = !showCategory }) {
                Text(if (showCategory) "📂 Category" else "📋 List")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Header row
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text("#", modifier = Modifier.width(28.dp), fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Item", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text("Qty", modifier = Modifier.width(40.dp), fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text("%", modifier = Modifier.width(48.dp), fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
        HorizontalDivider()

        val itemsToShow = if (showAll) summary.itemSales else summary.itemSales.take(20)
        val totalQty = summary.itemSales.sumOf { it.quantity }.coerceAtLeast(1)

        LazyColumn(modifier = Modifier.weight(1f)) {
            if (showCategory) {
                // Group by category
                val grouped = itemsToShow.groupBy { it.categoryName }
                grouped.forEach { (catName, catItems) ->
                    val catQty = catItems.sumOf { it.quantity }
                    val catPct = (catQty * 100.0 / totalQty)
                    item(key = "cat_header_$catName") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(catName, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
                            Text("$catQty", modifier = Modifier.width(40.dp), fontWeight = FontWeight.Bold)
                            Text("${"%.1f".format(catPct)}%", modifier = Modifier.width(48.dp), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
                    }
                    itemsIndexed(catItems) { index, sale ->
                        val pct = (sale.quantity * 100.0 / totalQty)
                        Row(modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 2.dp, bottom = 2.dp)) {
                            Text("${index + 1}", modifier = Modifier.width(28.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(sale.name, modifier = Modifier.weight(1f), fontSize = 13.sp)
                            Text("${sale.quantity}", modifier = Modifier.width(40.dp), fontSize = 13.sp)
                            Text("${"%.1f".format(pct)}%", modifier = Modifier.width(48.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else {
                // Flat list
                itemsIndexed(itemsToShow) { index, sale ->
                    val pct = (sale.quantity * 100.0 / totalQty)
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text("${index + 1}", modifier = Modifier.width(28.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(sale.name, modifier = Modifier.weight(1f), fontSize = 13.sp)
                        Text("${sale.quantity}", modifier = Modifier.width(40.dp), fontSize = 13.sp)
                        Text("${"%.1f".format(pct)}%", modifier = Modifier.width(48.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        if (summary.itemSales.size > 20 && !showAll) {
            TextButton(onClick = { vm.toggleShowAll() }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Show All (${summary.itemSales.size} items)")
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// CSV BACKUP SECTION
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun AdminCsvSection(csvManager: CsvManager) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var health by remember { mutableStateOf(csvManager.checkFolderHealth()) }
    var exporting by remember { mutableStateOf(false) }
    var exportDone by remember { mutableStateOf(false) }
    var backupFiles by remember { mutableStateOf(csvManager.listBackupFiles()) }
    var showImportWarning by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var importResult by remember { mutableStateOf<CsvManager.ImportResult?>(null) }
    var syncing by remember { mutableStateOf(false) }
    var syncResult by remember { mutableStateOf<String?>(null) }

    // Directory picker for import
    val dirPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val docFile = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)
            // Copy CSVs to a temp dir for processing
            scope.launch {
                importing = true
                try {
                    val tempDir = java.io.File(context.cacheDir, "import_temp")
                    if (tempDir.exists()) tempDir.deleteRecursively()
                    tempDir.mkdirs()

                    docFile?.listFiles()?.filter { it.name?.endsWith(".csv") == true }?.forEach { docF ->
                        val inputStream = context.contentResolver.openInputStream(docF.uri)
                        val outFile = java.io.File(tempDir, docF.name ?: "unknown.csv")
                        inputStream?.use { ins -> outFile.outputStream().use { outs -> ins.copyTo(outs) } }
                    }

                    val result = csvManager.importFromDirectory(tempDir)
                    importResult = result
                    tempDir.deleteRecursively()
                    backupFiles = csvManager.listBackupFiles()
                } catch (e: Exception) {
                    importResult = CsvManager.ImportResult(false, "Import failed: ${e.message}")
                }
                importing = false
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("CSV Backup", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(8.dp))

        // Explanation
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("What is this?", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(
                    "CSV backup saves your data (orders, spending, menu items) as spreadsheet files " +
                    "(.csv) that can be opened in Excel or Google Sheets. This is your safety net — " +
                    "if the app is uninstalled or phone is lost, your data survives in these files.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text("How it works:", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(
                    "• Auto-save: Every time you complete an order, all CSV files are automatically updated.\n" +
                    "• Export Now: Immediately re-generates all CSV files from your current data.\n" +
                    "• Files are saved to TWO locations for redundancy.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text("Files generated:", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(
                    "• makulu_orders_YYYY-MM.csv — All completed orders\n" +
                    "• makulu_spending_YYYY-MM.csv — Shop spending entries\n" +
                    "• makulu_menu_items_YYYY-MM.csv — Menu catalog snapshot\n" +
                    "• makulu_consolidated_YYYY-MM.csv — Summary + item sales\n" +
                    "• *_latest.csv — Always-updated copy of the above",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Folder health
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Save Locations", fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Circle, contentDescription = null, tint = if (health.appFolderOk) TableFree else TablePlaced, modifier = Modifier.size(12.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text("App Folder: ${if (health.appFolderOk) "✓ OK" else "✗ NOT ACCESSIBLE"}", style = MaterialTheme.typography.bodyMedium)
                        Text(health.appFolderPath, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Circle, contentDescription = null, tint = if (health.documentsFolderOk) TableFree else TablePlaced, modifier = Modifier.size(12.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text("Documents Folder: ${if (health.documentsFolderOk) "✓ OK" else "✗ NOT ACCESSIBLE"}", style = MaterialTheme.typography.bodyMedium)
                        Text(health.documentsFolderPath, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                exporting = true
                exportDone = false
                scope.launch {
                    csvManager.exportAll()
                    exporting = false
                    exportDone = true
                    health = csvManager.checkFolderHealth()
                    backupFiles = csvManager.listBackupFiles()
                }
            },
            enabled = !exporting,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (exporting) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            else { Icon(Icons.Default.CloudUpload, contentDescription = null); Spacer(modifier = Modifier.width(8.dp)); Text("Export Now") }
        }

        if (exportDone) {
            Spacer(modifier = Modifier.height(8.dp))
            Text("✅ Export complete! All CSV files regenerated from current data.", color = Olive, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Sync from CSV button
        OutlinedButton(
            onClick = {
                syncing = true
                syncResult = null
                scope.launch {
                    val result = csvManager.syncFromCsv()
                    syncResult = if (result.success) "✅ Synced: ${result.menuItemsImported} items, ${result.spendsImported} spends, ${result.ordersImported} orders"
                    else "❌ ${result.message}"
                    syncing = false
                    backupFiles = csvManager.listBackupFiles()
                }
            },
            enabled = !syncing,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (syncing) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            else { Icon(Icons.Default.Sync, contentDescription = null); Spacer(modifier = Modifier.width(8.dp)); Text("Sync from CSV") }
        }
        syncResult?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = if (it.startsWith("✅")) Olive else MaterialTheme.colorScheme.error)
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Import from directory button
        OutlinedButton(
            onClick = { showImportWarning = true },
            enabled = !importing,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.tertiary)
        ) {
            if (importing) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            else { Icon(Icons.Default.FileDownload, contentDescription = null); Spacer(modifier = Modifier.width(8.dp)); Text("Import from Directory") }
        }
        importResult?.let { result ->
            Text(
                if (result.success) "✅ Imported: ${result.menuItemsImported} menu items, ${result.spendsImported} spends, ${result.ordersImported} orders"
                else "❌ ${result.message}",
                style = MaterialTheme.typography.bodySmall,
                color = if (result.success) Olive else MaterialTheme.colorScheme.error
            )
        }

        // Import warning dialog
        if (showImportWarning) {
            AlertDialog(
                onDismissRequest = { showImportWarning = false },
                title = { Text("⚠️ Replace All Data?") },
                text = {
                    Text("Importing will REPLACE all existing data (orders, menu items, spending) with the CSV files you select.\n\nThis cannot be undone. If you want to merge data, merge the CSV files externally first, then import.")
                },
                confirmButton = {
                    TextButton(onClick = { showImportWarning = false; dirPicker.launch(null) }) {
                        Text("Import & Replace", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showImportWarning = false }) { Text("Cancel") }
                }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Show existing files
        Text("Backup Files (App Folder)", fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(4.dp))
        if (backupFiles.first.isEmpty()) {
            Text("No files yet. Complete an order or tap Export Now.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            backupFiles.first.forEach { f ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(f.name, modifier = Modifier.weight(1f), fontSize = 12.sp)
                    Text("${f.sizeKb} KB", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// RECEIPT SETTINGS SECTION
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun AdminReceiptSection(vm: AdminViewModel, settingsRepo: SettingsRepository) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Order Receipt", "Kitchen Receipt")
    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { index, title ->
                Tab(selected = selectedTab == index, onClick = { selectedTab = index }, text = { Text(title) })
            }
        }
        when (selectedTab) {
            0 -> OrderReceiptTab(vm, settingsRepo)
            1 -> KitchenReceiptTab(vm, settingsRepo)
        }
    }
}

@Composable
private fun OrderReceiptTab(vm: AdminViewModel, settingsRepo: SettingsRepository) {
    val fields by vm.orderReceiptFields.collectAsState()
    var showAddField by remember { mutableStateOf(false) }
    var newFieldName by remember { mutableStateOf("") }
    var newFieldValue by remember { mutableStateOf("") }
    var newFieldFontSize by remember { mutableStateOf("Normal") }
    var newFieldBold by remember { mutableStateOf(false) }
    var editingField by remember { mutableStateOf<ReceiptField?>(null) }
    val scope = rememberCoroutineScope()

    // Body toggles
    var showOrderNo by remember { mutableStateOf(true) }
    var showDateTime by remember { mutableStateOf(true) }
    var showTable by remember { mutableStateOf(true) }
    var showItems by remember { mutableStateOf(true) }
    var showTotal by remember { mutableStateOf(true) }
    var itemSize by remember { mutableStateOf("Normal") }
    var itemBold by remember { mutableStateOf(false) }
    // Dot spacers
    var dotsTop by remember { mutableStateOf(1) }
    var dotsBottom by remember { mutableStateOf(2) }

    LaunchedEffect(Unit) {
        showOrderNo  = settingsRepo.get(SettingsRepository.KEY_RECEIPT_SHOW_ORDER_NO) != "false"
        showDateTime = settingsRepo.get(SettingsRepository.KEY_RECEIPT_SHOW_DATETIME) != "false"
        showTable    = settingsRepo.get(SettingsRepository.KEY_RECEIPT_SHOW_TABLE)    != "false"
        showItems    = settingsRepo.get(SettingsRepository.KEY_RECEIPT_SHOW_ITEMS)    != "false"
        showTotal    = settingsRepo.get(SettingsRepository.KEY_RECEIPT_SHOW_TOTAL)    != "false"
        itemSize     = settingsRepo.get(SettingsRepository.KEY_ORDER_ITEM_SIZE)       ?: "Normal"
        itemBold     = settingsRepo.get(SettingsRepository.KEY_ORDER_ITEM_BOLD)       == "true"
        dotsTop      = settingsRepo.get(SettingsRepository.KEY_ORDER_DOTS_TOP)?.toIntOrNull()    ?: 1
        dotsBottom   = settingsRepo.get(SettingsRepository.KEY_ORDER_DOTS_BOTTOM)?.toIntOrNull() ?: 2
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {

        // ── Paper Spacing ──
        Text("Paper Spacing", fontWeight = FontWeight.SemiBold)
        Text("Dot lines printed to ensure paper advances past the cutter", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(4.dp))
        DotSpacerPicker("Dots above header", dotsTop)    { dotsTop    = it; scope.launch { settingsRepo.set(SettingsRepository.KEY_ORDER_DOTS_TOP,    it.toString()) } }
        DotSpacerPicker("Dots below footer", dotsBottom) { dotsBottom = it; scope.launch { settingsRepo.set(SettingsRepository.KEY_ORDER_DOTS_BOTTOM, it.toString()) } }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // ── Header fields ──
        Text("Header Fields", fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(4.dp))
        fields.filter { it.isHeader }.forEach { field ->
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = field.isEnabled, onCheckedChange = { vm.updateReceiptField(field.copy(isEnabled = it)) })
                    Column(modifier = Modifier.weight(1f)) {
                        Text(field.fieldName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(field.fieldValue, style = receiptFieldTextStyle(field.fontSize, field.isBold))
                        Text("${field.fontSize}${if (field.isBold) " · Bold" else ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = { editingField = field }) { Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    IconButton(onClick = { vm.deleteReceiptField(field) }) { Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error) }
                }
            }
        }
        TextButton(onClick = { newFieldName = ""; newFieldValue = ""; newFieldFontSize = "Normal"; newFieldBold = false; showAddField = true }) { Text("+ Add Header Field") }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // ── Body toggles ──
        Text("Body Fields", fontWeight = FontWeight.SemiBold)
        ReceiptToggle("Order Number", showOrderNo) { showOrderNo = it; scope.launch { settingsRepo.set(SettingsRepository.KEY_RECEIPT_SHOW_ORDER_NO, it.toString()) } }
        ReceiptToggle("Date/Time", showDateTime)   { showDateTime = it; scope.launch { settingsRepo.set(SettingsRepository.KEY_RECEIPT_SHOW_DATETIME, it.toString()) } }
        ReceiptToggle("Table Number", showTable)   { showTable    = it; scope.launch { settingsRepo.set(SettingsRepository.KEY_RECEIPT_SHOW_TABLE,    it.toString()) } }
        ReceiptToggle("Items + Prices", showItems) { showItems    = it; scope.launch { settingsRepo.set(SettingsRepository.KEY_RECEIPT_SHOW_ITEMS,    it.toString()) } }
        ReceiptToggle("Total", showTotal)          { showTotal    = it; scope.launch { settingsRepo.set(SettingsRepository.KEY_RECEIPT_SHOW_TOTAL,    it.toString()) } }
        Spacer(modifier = Modifier.height(8.dp))
        Text("Item Font Size", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(4.dp))
        FontSizeChips(selected = itemSize, onSelect = { itemSize = it; scope.launch { settingsRepo.set(SettingsRepository.KEY_ORDER_ITEM_SIZE, it) } })
        Spacer(modifier = Modifier.height(8.dp))
        ReceiptToggle("Bold order items", itemBold) { itemBold = it; scope.launch { settingsRepo.set(SettingsRepository.KEY_ORDER_ITEM_BOLD, it.toString()) } }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // ── Footer lines ──
        Text("Footer Lines", fontWeight = FontWeight.SemiBold)
        val footerFields = fields.filter { !it.isHeader }
        footerFields.forEach { field ->
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = field.isEnabled, onCheckedChange = { vm.updateReceiptField(field.copy(isEnabled = it)) })
                    Text(field.fieldValue, modifier = Modifier.weight(1f))
                    IconButton(onClick = { editingField = field }) { Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    IconButton(onClick = { vm.deleteReceiptField(field) }) { Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error) }
                }
            }
        }
        if (footerFields.size < 5) {
            var showAddFooter by remember { mutableStateOf(false) }
            var newFooterValue by remember { mutableStateOf("") }
            TextButton(onClick = { showAddFooter = true }) { Text("+ Add Footer Line") }
            if (showAddFooter) {
                AlertDialog(
                    onDismissRequest = { showAddFooter = false },
                    title = { Text("Add Footer Line") },
                    text = { OutlinedTextField(value = newFooterValue, onValueChange = { newFooterValue = sanitizeReceiptInput(it, 60) }, label = { Text("Footer text") }, singleLine = true, colors = makuluOutlinedTextFieldColors()) },
                    confirmButton = {
                        TextButton(onClick = {
                            if (newFooterValue.isNotBlank()) {
                                vm.addReceiptFieldWithStyle("Footer", newFooterValue, false, "Normal", false, "order")
                                newFooterValue = ""; showAddFooter = false
                            }
                        }) { Text("Add") }
                    },
                    dismissButton = { TextButton(onClick = { showAddFooter = false }) { Text("Cancel") } }
                )
            }
        } else {
            Text("Maximum 5 footer lines reached", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        // ── Live Preview (always visible, updates immediately) ──
        Text("Live Preview", fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(8.dp))
        ReceiptLivePreview(
            headerFields = fields.filter { it.isHeader && it.isEnabled },
            footerFields = fields.filter { !it.isHeader && it.isEnabled },
            showOrderNo = showOrderNo,
            showDateTime = showDateTime,
            showTable = showTable,
            showItems = showItems,
            showTotal = showTotal,
            itemSize = itemSize,
            itemBold = itemBold
        )
    }

    // ── Add Header Field dialog ──
    if (showAddField) {
        AlertDialog(
            onDismissRequest = { showAddField = false },
            title = { Text("Add Header Field") },
            text = {
                Column {
                    OutlinedTextField(
                        value = newFieldName,
                        onValueChange = { newFieldName = sanitizeReceiptInput(it, 30) },
                        label = { Text("Label (e.g. Phone)") },
                        singleLine = true,
                        colors = makuluOutlinedTextFieldColors()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newFieldValue,
                        onValueChange = { newFieldValue = sanitizeReceiptInput(it, 50) },
                        label = { Text("Value") },
                        singleLine = true,
                        colors = makuluOutlinedTextFieldColors()
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Font Size", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(4.dp))
                    FontSizeChips(selected = newFieldFontSize, onSelect = { newFieldFontSize = it })
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = newFieldBold, onCheckedChange = { newFieldBold = it })
                        Text("Bold")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    // Inline preview of the new field
                    Text("Preview:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = newFieldValue.ifBlank { "Sample Text" },
                        style = receiptFieldTextStyle(newFieldFontSize, newFieldBold)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newFieldName.isNotBlank()) {
                        vm.addReceiptFieldWithStyle(newFieldName, newFieldValue, true, newFieldFontSize, newFieldBold, "order")
                        newFieldName = ""; newFieldValue = ""; newFieldFontSize = "Normal"; newFieldBold = false
                        showAddField = false
                    }
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { showAddField = false }) { Text("Cancel") } }
        )
    }

    // ── Edit Field dialog ──
    editingField?.let { field ->
        var editValue by remember(field.id) { mutableStateOf(field.fieldValue) }
        var editFontSize by remember(field.id) { mutableStateOf(field.fontSize) }
        var editBold by remember(field.id) { mutableStateOf(field.isBold) }
        AlertDialog(
            onDismissRequest = { editingField = null },
            title = { Text("Edit ${field.fieldName}") },
            text = {
                Column {
                    OutlinedTextField(
                        value = editValue,
                        onValueChange = { editValue = sanitizeReceiptInput(it, if (field.isHeader) 50 else 60) },
                        label = { Text("Value") },
                        singleLine = true,
                        colors = makuluOutlinedTextFieldColors()
                    )
                    if (field.isHeader) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Font Size", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(4.dp))
                        FontSizeChips(selected = editFontSize, onSelect = { editFontSize = it })
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = editBold, onCheckedChange = { editBold = it })
                            Text("Bold")
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Preview:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = editValue.ifBlank { "Sample Text" },
                            style = receiptFieldTextStyle(editFontSize, editBold)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.updateReceiptField(field.copy(fieldValue = editValue, fontSize = editFontSize, isBold = editBold))
                    editingField = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editingField = null }) { Text("Cancel") } }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// KITCHEN RECEIPT TAB
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun KitchenReceiptTab(vm: AdminViewModel, settingsRepo: SettingsRepository) {
    val fields by vm.kitchenReceiptFields.collectAsState()
    var showAddField by remember { mutableStateOf(false) }
    var newFieldName by remember { mutableStateOf("") }
    var newFieldValue by remember { mutableStateOf("") }
    var newFieldFontSize by remember { mutableStateOf("Normal") }
    var newFieldBold by remember { mutableStateOf(false) }
    var editingField by remember { mutableStateOf<ReceiptField?>(null) }
    val scope = rememberCoroutineScope()

    var showOrderNo by remember { mutableStateOf(true) }
    var showDateTime by remember { mutableStateOf(true) }
    var itemSize by remember { mutableStateOf("Normal") }
    var itemBold by remember { mutableStateOf(false) }
    var dotsTop by remember { mutableStateOf(0) }
    var dotsBottom by remember { mutableStateOf(2) }

    LaunchedEffect(Unit) {
        showOrderNo  = settingsRepo.get(SettingsRepository.KEY_KITCHEN_SHOW_ORDER_NO) != "false"
        showDateTime = settingsRepo.get(SettingsRepository.KEY_KITCHEN_SHOW_DATETIME) != "false"
        itemSize     = settingsRepo.get(SettingsRepository.KEY_KITCHEN_ITEM_SIZE)     ?: "Normal"
        itemBold     = settingsRepo.get(SettingsRepository.KEY_KITCHEN_ITEM_BOLD)     == "true"
        dotsTop      = settingsRepo.get(SettingsRepository.KEY_KITCHEN_DOTS_TOP)?.toIntOrNull()    ?: 0
        dotsBottom   = settingsRepo.get(SettingsRepository.KEY_KITCHEN_DOTS_BOTTOM)?.toIntOrNull() ?: 2
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {

        // ── Paper Spacing ──
        Text("Paper Spacing", fontWeight = FontWeight.SemiBold)
        Text("Dot lines printed to ensure paper advances past the cutter", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(4.dp))
        DotSpacerPicker("Dots above header", dotsTop)    { dotsTop    = it; scope.launch { settingsRepo.set(SettingsRepository.KEY_KITCHEN_DOTS_TOP,    it.toString()) } }
        DotSpacerPicker("Dots below footer", dotsBottom) { dotsBottom = it; scope.launch { settingsRepo.set(SettingsRepository.KEY_KITCHEN_DOTS_BOTTOM, it.toString()) } }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // ── Header fields ──
        Text("Header Fields", fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(4.dp))
        fields.filter { it.isHeader }.forEach { field ->
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = field.isEnabled, onCheckedChange = { vm.updateReceiptField(field.copy(isEnabled = it)) })
                    Column(modifier = Modifier.weight(1f)) {
                        Text(field.fieldName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(field.fieldValue, style = receiptFieldTextStyle(field.fontSize, field.isBold))
                        Text("${field.fontSize}${if (field.isBold) " · Bold" else ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = { editingField = field }) { Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    IconButton(onClick = { vm.deleteReceiptField(field) }) { Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error) }
                }
            }
        }
        TextButton(onClick = { newFieldName = ""; newFieldValue = ""; newFieldFontSize = "Normal"; newFieldBold = false; showAddField = true }) { Text("+ Add Header Field") }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // ── Body toggles ──
        Text("Body Fields", fontWeight = FontWeight.SemiBold)
        Text("Table number is always printed (bold/double size)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ReceiptToggle("Order Number", showOrderNo) { showOrderNo = it; scope.launch { settingsRepo.set(SettingsRepository.KEY_KITCHEN_SHOW_ORDER_NO, it.toString()) } }
        ReceiptToggle("Date/Time", showDateTime)   { showDateTime = it; scope.launch { settingsRepo.set(SettingsRepository.KEY_KITCHEN_SHOW_DATETIME, it.toString()) } }
        Spacer(modifier = Modifier.height(8.dp))
        Text("Item Font Size", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(4.dp))
        FontSizeChips(selected = itemSize, onSelect = { itemSize = it; scope.launch { settingsRepo.set(SettingsRepository.KEY_KITCHEN_ITEM_SIZE, it) } })
        Spacer(modifier = Modifier.height(8.dp))
        ReceiptToggle("Bold order items", itemBold) { itemBold = it; scope.launch { settingsRepo.set(SettingsRepository.KEY_KITCHEN_ITEM_BOLD, it.toString()) } }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // ── Footer lines ──
        Text("Footer Lines", fontWeight = FontWeight.SemiBold)
        val footerFields = fields.filter { !it.isHeader }
        footerFields.forEach { field ->
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = field.isEnabled, onCheckedChange = { vm.updateReceiptField(field.copy(isEnabled = it)) })
                    Text(field.fieldValue, modifier = Modifier.weight(1f))
                    IconButton(onClick = { editingField = field }) { Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    IconButton(onClick = { vm.deleteReceiptField(field) }) { Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error) }
                }
            }
        }
        if (footerFields.size < 5) {
            var showAddFooter by remember { mutableStateOf(false) }
            var newFooterValue by remember { mutableStateOf("") }
            TextButton(onClick = { showAddFooter = true }) { Text("+ Add Footer Line") }
            if (showAddFooter) {
                AlertDialog(
                    onDismissRequest = { showAddFooter = false },
                    title = { Text("Add Footer Line") },
                    text = { OutlinedTextField(value = newFooterValue, onValueChange = { newFooterValue = sanitizeReceiptInput(it, 60) }, label = { Text("Footer text") }, singleLine = true, colors = makuluOutlinedTextFieldColors()) },
                    confirmButton = {
                        TextButton(onClick = {
                            if (newFooterValue.isNotBlank()) {
                                vm.addReceiptFieldWithStyle("Footer", newFooterValue, false, "Normal", false, "kitchen")
                                newFooterValue = ""; showAddFooter = false
                            }
                        }) { Text("Add") }
                    },
                    dismissButton = { TextButton(onClick = { showAddFooter = false }) { Text("Cancel") } }
                )
            }
        } else {
            Text("Maximum 5 footer lines reached", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        // ── Live Preview ──
        Text("Live Preview", fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(8.dp))
        KitchenReceiptLivePreview(
            headerFields = fields.filter { it.isHeader && it.isEnabled },
            footerFields = fields.filter { !it.isHeader && it.isEnabled },
            showOrderNo = showOrderNo,
            showDateTime = showDateTime,
            itemSize = itemSize,
            itemBold = itemBold
        )
    }

    // ── Add Header Field dialog ──
    if (showAddField) {
        AlertDialog(
            onDismissRequest = { showAddField = false },
            title = { Text("Add Header Field") },
            text = {
                Column {
                    OutlinedTextField(value = newFieldName, onValueChange = { newFieldName = sanitizeReceiptInput(it, 30) }, label = { Text("Label (e.g. Station)") }, singleLine = true, colors = makuluOutlinedTextFieldColors())
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = newFieldValue, onValueChange = { newFieldValue = sanitizeReceiptInput(it, 50) }, label = { Text("Value") }, singleLine = true, colors = makuluOutlinedTextFieldColors())
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Font Size", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(4.dp))
                    FontSizeChips(selected = newFieldFontSize, onSelect = { newFieldFontSize = it })
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = newFieldBold, onCheckedChange = { newFieldBold = it })
                        Text("Bold")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Preview:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(text = newFieldValue.ifBlank { "Sample Text" }, style = receiptFieldTextStyle(newFieldFontSize, newFieldBold))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newFieldName.isNotBlank()) {
                        vm.addReceiptFieldWithStyle(newFieldName, newFieldValue, true, newFieldFontSize, newFieldBold, "kitchen")
                        newFieldName = ""; newFieldValue = ""; newFieldFontSize = "Normal"; newFieldBold = false; showAddField = false
                    }
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { showAddField = false }) { Text("Cancel") } }
        )
    }

    // ── Edit Field dialog ──
    editingField?.let { field ->
        var editValue by remember(field.id) { mutableStateOf(field.fieldValue) }
        var editFontSize by remember(field.id) { mutableStateOf(field.fontSize) }
        var editBold by remember(field.id) { mutableStateOf(field.isBold) }
        AlertDialog(
            onDismissRequest = { editingField = null },
            title = { Text("Edit ${field.fieldName}") },
            text = {
                Column {
                    OutlinedTextField(value = editValue, onValueChange = { editValue = sanitizeReceiptInput(it, if (field.isHeader) 50 else 60) }, label = { Text("Value") }, singleLine = true, colors = makuluOutlinedTextFieldColors())
                    if (field.isHeader) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Font Size", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(4.dp))
                        FontSizeChips(selected = editFontSize, onSelect = { editFontSize = it })
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = editBold, onCheckedChange = { editBold = it })
                            Text("Bold")
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Preview:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(text = editValue.ifBlank { "Sample Text" }, style = receiptFieldTextStyle(editFontSize, editBold))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.updateReceiptField(field.copy(fieldValue = editValue, fontSize = editFontSize, isBold = editBold))
                    editingField = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editingField = null }) { Text("Cancel") } }
        )
    }
}

// Helper: map fontSize string to Compose TextStyle for in-app preview
@Composable
private fun receiptFieldTextStyle(fontSize: String, isBold: Boolean): androidx.compose.ui.text.TextStyle {
    val weight = if (isBold) FontWeight.Bold else FontWeight.Normal
    return when (fontSize) {
        "Large"  -> MaterialTheme.typography.titleMedium.copy(fontWeight = weight)
        "Double" -> MaterialTheme.typography.titleLarge.copy(fontWeight = weight)
        else     -> MaterialTheme.typography.bodyMedium.copy(fontWeight = weight)
    }
}

@Composable
private fun FontSizeChips(selected: String, onSelect: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("Normal", "Large", "Double").forEach { size ->
            FilterChip(
                selected = selected == size,
                onClick = { onSelect(size) },
                label = { Text(size) }
            )
        }
    }
}

@Composable
private fun DotSpacerPicker(label: String, value: Int, onValueChange: (Int) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        IconButton(onClick = { if (value > 0) onValueChange(value - 1) }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.Remove, contentDescription = "Decrease", modifier = Modifier.size(16.dp))
        }
        Text(
            "$value",
            modifier = Modifier.widthIn(min = 28.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            fontWeight = FontWeight.Bold
        )
        IconButton(onClick = { if (value < 10) onValueChange(value + 1) }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.Add, contentDescription = "Increase", modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
fun ReceiptToggle(label: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onToggle)
        Text(label)
    }
}

@Composable
fun ReceiptLivePreview(
    headerFields: List<ReceiptField>,
    footerFields: List<ReceiptField>,
    showOrderNo: Boolean,
    showDateTime: Boolean,
    showTable: Boolean,
    showItems: Boolean,
    showTotal: Boolean,
    itemSize: String = "Normal",
    itemBold: Boolean = false
) {
    Surface(
        color = Color.White,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(4.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val mono = androidx.compose.ui.text.TextStyle(
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                color = Color.Black
            )
            val divider = "--------------------------------"
            val itemStyle = when (itemSize) {
                "Large"  -> mono.copy(fontSize = 13.sp, lineHeight = 17.sp, fontWeight = if (itemBold) FontWeight.Bold else FontWeight.Normal)
                "Double" -> mono.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = if (itemBold) FontWeight.Bold else FontWeight.Normal)
                else     -> mono.copy(fontWeight = if (itemBold) FontWeight.Bold else FontWeight.Normal)
            }

            // Header
            if (headerFields.isNotEmpty()) {
                headerFields.forEach { field ->
                    val style = when (field.fontSize) {
                        "Large"  -> mono.copy(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = if (field.isBold) FontWeight.Bold else FontWeight.Normal)
                        "Double" -> mono.copy(fontSize = 18.sp, lineHeight = 22.sp, fontWeight = if (field.isBold) FontWeight.Bold else FontWeight.Normal)
                        else     -> mono.copy(fontWeight = if (field.isBold) FontWeight.Bold else FontWeight.Normal)
                    }
                    Text(field.fieldValue, style = style, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
                Text(divider, style = mono)
            }

            // Body
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                if (showOrderNo) Text("Order: ORD-20260529-001", style = mono)
                if (showDateTime) Text("Date: 29-05-2026 02:30 PM", style = mono)
                if (showTable) Text("Table: T01", style = mono.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold))
                if (showItems) {
                    Text(divider, style = mono)
                    Text("Item         Qty   Amount", style = mono.copy(fontWeight = FontWeight.Bold))
                    Text(divider, style = mono)
                    Text("Chicken Roll  x2   ₹120.00", style = itemStyle)
                    Text("Veg Pasta     x1    ₹80.00", style = itemStyle)
                    Text("Cold Coffee   x2   ₹100.00", style = itemStyle)
                }
                if (showTotal) {
                    Text(divider, style = mono)
                    Text("Total: ₹300.00", style = mono.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }

            // Footer
            if (footerFields.isNotEmpty()) {
                Text(divider, style = mono)
                footerFields.forEach { field ->
                    Text(field.fieldValue, style = mono, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
    }
}

@Composable
fun KitchenReceiptLivePreview(
    headerFields: List<ReceiptField>,
    footerFields: List<ReceiptField>,
    showOrderNo: Boolean,
    showDateTime: Boolean,
    itemSize: String = "Normal",
    itemBold: Boolean = false
) {
    Surface(
        color = Color.White,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(4.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val mono = androidx.compose.ui.text.TextStyle(
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                color = Color.Black
            )
            val divider = "--------------------------------"
            val itemStyle = when (itemSize) {
                "Large"  -> mono.copy(fontSize = 13.sp, lineHeight = 17.sp, fontWeight = if (itemBold) FontWeight.Bold else FontWeight.Normal)
                "Double" -> mono.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = if (itemBold) FontWeight.Bold else FontWeight.Normal)
                else     -> mono.copy(fontWeight = if (itemBold) FontWeight.Bold else FontWeight.Normal)
            }

            if (headerFields.isNotEmpty()) {
                headerFields.forEach { field ->
                    val style = when (field.fontSize) {
                        "Large"  -> mono.copy(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = if (field.isBold) FontWeight.Bold else FontWeight.Normal)
                        "Double" -> mono.copy(fontSize = 18.sp, lineHeight = 22.sp, fontWeight = if (field.isBold) FontWeight.Bold else FontWeight.Normal)
                        else     -> mono.copy(fontWeight = if (field.isBold) FontWeight.Bold else FontWeight.Normal)
                    }
                    Text(field.fieldValue, style = style, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
                Text(divider, style = mono)
            } else {
                Text("KITCHEN ORDER", style = mono.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Text(divider, style = mono)
            }

            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Table: T01", style = mono.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold))
                if (showOrderNo)  Text("Order: ORD-20260529-001", style = mono)
                if (showDateTime) Text("Date: 29-05-2026 02:30 PM", style = mono)
                Text(divider, style = mono)
                Text("Item                      Qty", style = mono.copy(fontWeight = FontWeight.Bold))
                Text(divider, style = mono)
                Text("Chicken Roll               x2", style = itemStyle)
                Text("Veg Pasta                  x1", style = itemStyle)
                Text("Cold Coffee                x2", style = itemStyle)
            }

            if (footerFields.isNotEmpty()) {
                Text(divider, style = mono)
                footerFields.forEach { field ->
                    Text(field.fieldValue, style = mono, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
    }
}


// ─────────────────────────────────────────────────────────────────────────────
// INCLUSIONS SECTION (GST + Discount)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun AdminInclusionsSection(settingsRepo: SettingsRepository) {
    val scope = rememberCoroutineScope()

    // GST state
    var gstEnabled by remember { mutableStateOf(false) }
    var gstPercentage by remember { mutableStateOf("") }
    var cgstPercentage by remember { mutableStateOf("") }
    var sgstPercentage by remember { mutableStateOf("") }
    var gstCustomSplit by remember { mutableStateOf(false) }

    // Discount state
    var discountEnabled by remember { mutableStateOf(false) }
    var discountType by remember { mutableStateOf("percentage") } // "percentage" or "flat"
    var discountValue by remember { mutableStateOf("") }

    // Load settings
    LaunchedEffect(Unit) {
        gstEnabled = settingsRepo.get(SettingsRepository.KEY_GST_ENABLED) == "true"
        gstPercentage = settingsRepo.get(SettingsRepository.KEY_GST_PERCENTAGE) ?: ""
        cgstPercentage = settingsRepo.get(SettingsRepository.KEY_CGST_PERCENTAGE) ?: ""
        sgstPercentage = settingsRepo.get(SettingsRepository.KEY_SGST_PERCENTAGE) ?: ""
        discountEnabled = settingsRepo.get(SettingsRepository.KEY_DISCOUNT_ENABLED) == "true"
        discountType = settingsRepo.get(SettingsRepository.KEY_DISCOUNT_TYPE) ?: "percentage"
        discountValue = settingsRepo.get(SettingsRepository.KEY_DISCOUNT_VALUE) ?: ""
        // If CGST/SGST are custom (not equal split), show custom mode
        val gst = gstPercentage.toDoubleOrNull() ?: 0.0
        val cgst = cgstPercentage.toDoubleOrNull() ?: 0.0
        if (gst > 0 && cgst != gst / 2.0) gstCustomSplit = true
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("Inclusions", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(16.dp))

        // ─── GST SECTION ───
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("GST", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Switch(checked = gstEnabled, onCheckedChange = {
                        gstEnabled = it
                        scope.launch { settingsRepo.set(SettingsRepository.KEY_GST_ENABLED, it.toString()) }
                    })
                }

                if (gstEnabled) {
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = gstPercentage,
                        onValueChange = { input ->
                            val filtered = input.filter { c -> c.isDigit() || c == '.' }
                            val value = filtered.toDoubleOrNull()
                            if (value == null || value <= 100) {
                                gstPercentage = filtered
                                // Auto-split 50/50 unless custom
                                if (!gstCustomSplit) {
                                    val half = (value ?: 0.0) / 2.0
                                    cgstPercentage = "%.2f".format(half)
                                    sgstPercentage = "%.2f".format(half)
                                }
                                scope.launch {
                                    settingsRepo.set(SettingsRepository.KEY_GST_PERCENTAGE, filtered)
                                    if (!gstCustomSplit) {
                                        val half = (value ?: 0.0) / 2.0
                                        settingsRepo.set(SettingsRepository.KEY_CGST_PERCENTAGE, "%.2f".format(half))
                                        settingsRepo.set(SettingsRepository.KEY_SGST_PERCENTAGE, "%.2f".format(half))
                                    }
                                }
                            }
                        },
                        label = { Text("GST %") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = makuluOutlinedTextFieldColors()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // CGST / SGST display
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("CGST", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (gstCustomSplit) {
                                OutlinedTextField(
                                    value = cgstPercentage,
                                    onValueChange = { input ->
                                        val filtered = input.filter { c -> c.isDigit() || c == '.' }
                                        val gst = gstPercentage.toDoubleOrNull() ?: 0.0
                                        val cgst = filtered.toDoubleOrNull()
                                        if (cgst == null || cgst <= gst) {
                                            cgstPercentage = filtered
                                            val remaining = gst - (cgst ?: 0.0)
                                            sgstPercentage = "%.2f".format(remaining)
                                            scope.launch {
                                                settingsRepo.set(SettingsRepository.KEY_CGST_PERCENTAGE, filtered)
                                                settingsRepo.set(SettingsRepository.KEY_SGST_PERCENTAGE, "%.2f".format(remaining))
                                            }
                                        }
                                    },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = makuluOutlinedTextFieldColors()
                                )
                            } else {
                                Text("${cgstPercentage}%", fontWeight = FontWeight.Medium)
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text("SGST", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (gstCustomSplit) {
                                Text("${sgstPercentage}% (auto)", fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                Text("${sgstPercentage}%", fontWeight = FontWeight.Medium)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = {
                        if (gstCustomSplit) {
                            // Reset to 50/50
                            gstCustomSplit = false
                            val gst = gstPercentage.toDoubleOrNull() ?: 0.0
                            val half = gst / 2.0
                            cgstPercentage = "%.2f".format(half)
                            sgstPercentage = "%.2f".format(half)
                            scope.launch {
                                settingsRepo.set(SettingsRepository.KEY_CGST_PERCENTAGE, "%.2f".format(half))
                                settingsRepo.set(SettingsRepository.KEY_SGST_PERCENTAGE, "%.2f".format(half))
                            }
                        } else {
                            gstCustomSplit = true
                        }
                    }) {
                        Text(if (gstCustomSplit) "Reset to 50/50 split" else "Edit CGST/SGST split")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ─── DISCOUNT SECTION ───
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Discount", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Switch(checked = discountEnabled, onCheckedChange = {
                        discountEnabled = it
                        scope.launch { settingsRepo.set(SettingsRepository.KEY_DISCOUNT_ENABLED, it.toString()) }
                    })
                }

                if (discountEnabled) {
                    Spacer(modifier = Modifier.height(12.dp))

                    // Type toggle
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = discountType == "percentage",
                            onClick = {
                                discountType = "percentage"
                                scope.launch { settingsRepo.set(SettingsRepository.KEY_DISCOUNT_TYPE, "percentage") }
                            },
                            label = { Text("Percentage %") }
                        )
                        FilterChip(
                            selected = discountType == "flat",
                            onClick = {
                                discountType = "flat"
                                scope.launch { settingsRepo.set(SettingsRepository.KEY_DISCOUNT_TYPE, "flat") }
                            },
                            label = { Text("Flat ₹") }
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = discountValue,
                        onValueChange = { input ->
                            val filtered = input.filter { c -> c.isDigit() || c == '.' }
                            if (discountType == "percentage") {
                                val v = filtered.toDoubleOrNull()
                                if (v == null || v <= 100) {
                                    discountValue = filtered
                                    scope.launch { settingsRepo.set(SettingsRepository.KEY_DISCOUNT_VALUE, filtered) }
                                }
                            } else {
                                discountValue = filtered
                                scope.launch { settingsRepo.set(SettingsRepository.KEY_DISCOUNT_VALUE, filtered) }
                            }
                        },
                        label = { Text(if (discountType == "percentage") "Discount %" else "Discount ₹") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = makuluOutlinedTextFieldColors()
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ─── CALCULATION PREVIEW ───
        if (gstEnabled || discountEnabled) {
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Receipt Preview (₹500 order)", fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(8.dp))

                    val itemTotal = 500.0
                    var afterDiscount = itemTotal
                    val discVal = discountValue.toDoubleOrNull() ?: 0.0

                    if (discountEnabled && discVal > 0) {
                        val discountAmt = if (discountType == "percentage") itemTotal * discVal / 100.0 else discVal
                        afterDiscount = itemTotal - discountAmt
                        Text("Item Total: ₹${"%.2f".format(itemTotal)}")
                        if (discountType == "percentage") {
                            Text("Discount (${"%.1f".format(discVal)}%): -₹${"%.2f".format(discountAmt)}")
                        } else {
                            Text("Discount: -₹${"%.2f".format(discountAmt)}")
                        }
                    }

                    if (gstEnabled) {
                        val gst = gstPercentage.toDoubleOrNull() ?: 0.0
                        val cgst = cgstPercentage.toDoubleOrNull() ?: (gst / 2.0)
                        val sgst = sgstPercentage.toDoubleOrNull() ?: (gst / 2.0)
                        val cgstAmt = afterDiscount * cgst / 100.0
                        val sgstAmt = afterDiscount * sgst / 100.0
                        if (!discountEnabled || discVal <= 0) {
                            Text("Sub Total: ₹${"%.2f".format(afterDiscount)}")
                        } else {
                            Text("Sub Total: ₹${"%.2f".format(afterDiscount)}")
                        }
                        Text("CGST (${"%.1f".format(cgst)}%): ₹${"%.2f".format(cgstAmt)}")
                        Text("SGST (${"%.1f".format(sgst)}%): ₹${"%.2f".format(sgstAmt)}")
                        val finalTotal = afterDiscount + cgstAmt + sgstAmt
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Total: ₹${"%.2f".format(finalTotal)}", fontWeight = FontWeight.Bold)
                    } else {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Total: ₹${"%.2f".format(afterDiscount)}", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "Note: Calculation order → Item Total → Discount → GST on discounted amount → Final Total",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// PRINTER SETTINGS SECTION
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun AdminPrinterSection(printerManager: PrinterManager, settingsRepo: SettingsRepository) {
    val isConnected by printerManager.isConnected.collectAsState()
    val printerName by printerManager.printerName.collectAsState()
    val pairedDevices = remember { printerManager.getPairedDevices() }
    var scanning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    var paperWidth by remember { mutableStateOf("58") }
    var charsetFix by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        paperWidth = settingsRepo.get(SettingsRepository.KEY_PAPER_WIDTH) ?: "58"
        charsetFix = settingsRepo.get(SettingsRepository.KEY_CHARSET_FIX) != "false"
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("Printer Settings", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(16.dp))

        // Status card
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Print,
                    contentDescription = null,
                    tint = if (isConnected) PrinterConnected else PrinterDisconnected,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(if (isConnected) "Connected" else "Not Connected", fontWeight = FontWeight.Bold)
                    printerName?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Paired devices
        Text("Paired Bluetooth Devices", fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(8.dp))

        if (pairedDevices.isEmpty()) {
            Text("No paired devices. Pair your printer via Android Bluetooth Settings first.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            pairedDevices.forEach { device ->
                @Suppress("MissingPermission")
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    onClick = {
                        printerManager.connectToPrinter(device) { }
                    }
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Bluetooth, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        @Suppress("MissingPermission")
                        Text(device.name ?: device.address, modifier = Modifier.weight(1f))
                        if (isConnected && printerName == device.name) {
                            Text("Connected", color = PrinterConnected, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Action buttons
        if (isConnected) {
            Button(
                onClick = {
                    scope.launch { printerManager.printTestPage() }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Print, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Test Print")
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(onClick = { printerManager.disconnect() }, modifier = Modifier.fillMaxWidth()) {
                Text("Disconnect")
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
        OutlinedButton(
            onClick = { printerManager.forgetPrinter() },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
        ) {
            Text("Forget Printer")
        }

        // ── Print Settings ──
        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
        Text("Print Settings", fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(8.dp))

        Text("Paper Width", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        Text("Must match your physical printer paper size", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(4.dp))
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = paperWidth == "58", onClick = {
                    paperWidth = "58"; scope.launch { settingsRepo.set(SettingsRepository.KEY_PAPER_WIDTH, "58") }
                })
                Text("58mm / 2 inch")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = paperWidth == "80", onClick = {
                    paperWidth = "80"; scope.launch { settingsRepo.set(SettingsRepository.KEY_PAPER_WIDTH, "80") }
                })
                Text("80mm / 3 inch")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Checkbox(checked = charsetFix, onCheckedChange = {
                charsetFix = it; scope.launch { settingsRepo.set(SettingsRepository.KEY_CHARSET_FIX, it.toString()) }
            })
            Column {
                Text("₹ Symbol Fix")
                Text("Sends a codepage command so ₹ prints correctly. Disable only if you see extra characters before the header.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // Printer Log
        Spacer(modifier = Modifier.height(12.dp))
        Text("Printer Log", fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(8.dp))
        val printerLogs by printerManager.logs.collectAsState()
        val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
        if (printerLogs.isEmpty()) {
            Text("No activity yet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            printerLogs.take(15).forEach { entry ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(entry.timestamp, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(56.dp))
                    Icon(
                        if (entry.success) Icons.Default.CheckCircle else Icons.Default.Error,
                        contentDescription = null,
                        tint = if (entry.success) Olive else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(entry.event, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = {
                    val logText = printerLogs.joinToString("\n") { "[${it.timestamp}] ${if (it.success) "OK" else "FAIL"} ${it.event}" }
                    clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(logText))
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Copy Logs")
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// SHOP SPENDING SIDEBAR (no PIN required)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun ShopSpendingSidebar(vm: SpendingViewModel = hiltViewModel()) {
    val lastTen by vm.lastTen.collectAsState()
    val todayTotal by vm.todayTotal.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var editingSpend by remember { mutableStateOf<ShopSpend?>(null) }
    var newItemName by remember { mutableStateOf("") }
    var newAmount by remember { mutableStateOf("") }
    var newDate by remember { mutableStateOf("") }
    var pendingDeleteSpendId by remember { mutableStateOf<Long?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Shop Spending", style = MaterialTheme.typography.titleLarge)
            Button(onClick = { newItemName = ""; newAmount = ""; newDate = ""; showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text(" Add")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text("Today: ₹${"%.2f".format(todayTotal)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
        Spacer(modifier = Modifier.height(12.dp))

        Text("Last 10 entries", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(4.dp))

        LazyColumn {
            items(lastTen) { spend ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    onClick = {
                        editingSpend = spend
                        newItemName = spend.itemName
                        newAmount = spend.amount.toString()
                        newDate = spend.date
                    }
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(spend.itemName, fontWeight = FontWeight.Medium)
                            Text(spend.date, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("₹${"%.2f".format(spend.amount)}", fontWeight = FontWeight.Bold)
                        if (pendingDeleteSpendId == spend.id) {
                            IconButton(onClick = {
                                vm.deleteSpend(spend)
                                pendingDeleteSpendId = null
                            }) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = Olive, modifier = Modifier.size(18.dp))
                            }
                            IconButton(onClick = { pendingDeleteSpendId = null }) {
                                Icon(Icons.Default.Close, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                            }
                        } else {
                            IconButton(onClick = { pendingDeleteSpendId = spend.id }) {
                                Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        var showDatePicker by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Add Spend") },
            text = {
                Column {
                    OutlinedTextField(value = newItemName, onValueChange = { newItemName = sanitizeInput(it, 50) }, label = { Text("Item Name") }, singleLine = true, colors = makuluOutlinedTextFieldColors())
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = newAmount, onValueChange = { newAmount = it }, label = { Text("Amount (₹)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.CalendarToday, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (newDate.isBlank()) "Today (tap to change)" else newDate)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val amount = newAmount.toDoubleOrNull()
                    if (newItemName.isNotBlank() && amount != null) {
                        vm.addSpend(newItemName, amount, newDate.ifBlank { null })
                        newItemName = ""; newAmount = ""; newDate = ""; showAddDialog = false
                    }
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { showAddDialog = false }) { Text("Cancel") } }
        )
        if (showDatePicker) {
            MakuluDatePicker(
                onDateSelected = { newDate = it },
                onDismiss = { showDatePicker = false }
            )
        }
    }

    editingSpend?.let { spend ->
        var showDatePicker by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { editingSpend = null },
            title = { Text("Edit Spend") },
            text = {
                Column {
                    OutlinedTextField(value = newItemName, onValueChange = { newItemName = sanitizeInput(it, 50) }, label = { Text("Item Name") }, singleLine = true, colors = makuluOutlinedTextFieldColors())
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = newAmount, onValueChange = { newAmount = it }, label = { Text("Amount") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, colors = makuluOutlinedTextFieldColors())
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.CalendarToday, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (newDate.isBlank()) "Select date" else newDate)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val amount = newAmount.toDoubleOrNull()
                    if (newItemName.isNotBlank() && amount != null && newDate.isNotBlank()) {
                        vm.updateSpend(spend.copy(itemName = newItemName, amount = amount, date = newDate))
                        editingSpend = null
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editingSpend = null }) { Text("Cancel") } }
        )
        if (showDatePicker) {
            MakuluDatePicker(
                onDateSelected = { newDate = it },
                onDismiss = { showDatePicker = false }
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// TODAY'S ORDERS SIDEBAR (no PIN)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun TodayOrdersSidebar(
    ledgerVm: LedgerViewModel = hiltViewModel(),
    onPrintReceipt: (Long) -> Unit = {},
    onPrintKitchenOrder: (Long) -> Unit = {},
    onUpdateOrder: (Long) -> Unit = {},
    orderRepo: OrderRepository,
    csvManager: CsvManager
) {
    val todayOrders by ledgerVm.todayOrders.collectAsState()
    var selectedOrder by remember { mutableStateOf<OrderWithItems?>(null) }
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Today's Orders", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(8.dp))
        Text("${todayOrders.size} orders completed today", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn {
            items(todayOrders) { owi ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    onClick = { selectedOrder = owi }
                ) {
                    Row(modifier = Modifier.padding(12.dp)) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(owi.order.tableName, fontWeight = FontWeight.Bold)
                            Text("${owi.items.size} items • ${formatIndianDateTime(owi.order.completedAt)}", style = MaterialTheme.typography.bodySmall)
                        }
                        Text("₹${"%.2f".format(if (owi.order.finalTotal > 0) owi.order.finalTotal else owi.order.totalAmount)}", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    selectedOrder?.let { owi ->
        AlertDialog(
            onDismissRequest = { selectedOrder = null },
            title = { Text(owi.order.orderNumber) },
            text = {
                Column {
                    Text("Table: ${owi.order.tableName}")
                    Text("Time: ${formatIndianDateTime(owi.order.completedAt)}")
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    owi.items.forEach { item ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(item.menuItemName, modifier = Modifier.weight(1f))
                            Text("x${item.quantity}  ₹${"%.2f".format(item.lineTotal)}")
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    if (owi.order.discountAmount > 0) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Item Total"); Text("₹${"%.2f".format(owi.order.totalAmount)}")
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Discount"); Text("-₹${"%.2f".format(owi.order.discountAmount)}")
                        }
                    }
                    if (owi.order.cgstAmount > 0 || owi.order.sgstAmount > 0) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("CGST"); Text("₹${"%.2f".format(owi.order.cgstAmount)}")
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("SGST"); Text("₹${"%.2f".format(owi.order.sgstAmount)}")
                        }
                    }
                    Text("Total: ₹${"%.2f".format(if (owi.order.finalTotal > 0) owi.order.finalTotal else owi.order.totalAmount)}", fontWeight = FontWeight.Bold)
                    if (owi.order.paymentMode.isNotBlank()) {
                        Text("Mode: ${owi.order.paymentMode}", fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(12.dp))

                    // Payment mode radio buttons
                    var paymentMode by remember { mutableStateOf(
                        when {
                            owi.order.paymentMode == "GPay" || owi.order.paymentMode == "Cash" -> owi.order.paymentMode
                            owi.order.paymentMode.isNotBlank() -> "custom"
                            else -> ""
                        }
                    ) }
                    var customModeText by remember { mutableStateOf(
                        if (owi.order.paymentMode != "GPay" && owi.order.paymentMode != "Cash") owi.order.paymentMode else ""
                    ) }
                    var showPaymentWarning by remember { mutableStateOf(false) }

                    Text("Payment Mode", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = paymentMode == "GPay", onClick = {
                            paymentMode = "GPay"; showPaymentWarning = false
                            scope.launch { orderRepo.updatePaymentMode(owi.order.id, "GPay"); csvManager.onOrderCompleted() }
                        })
                        Text("GPay", modifier = Modifier.padding(end = 12.dp))
                        RadioButton(selected = paymentMode == "Cash", onClick = {
                            paymentMode = "Cash"; showPaymentWarning = false
                            scope.launch { orderRepo.updatePaymentMode(owi.order.id, "Cash"); csvManager.onOrderCompleted() }
                        })
                        Text("Cash", modifier = Modifier.padding(end = 12.dp))
                        RadioButton(selected = paymentMode == "custom", onClick = {
                            paymentMode = "custom"; showPaymentWarning = false
                            if (customModeText.isNotBlank()) {
                                scope.launch { orderRepo.updatePaymentMode(owi.order.id, customModeText); csvManager.onOrderCompleted() }
                            }
                        })
                    }
                    if (paymentMode == "custom") {
                        OutlinedTextField(
                            value = customModeText,
                            onValueChange = { input ->
                                val filtered = input.filter { c -> c.isLetterOrDigit() }.take(10)
                                customModeText = filtered
                                if (filtered.isNotBlank()) {
                                    scope.launch { orderRepo.updatePaymentMode(owi.order.id, filtered); csvManager.onOrderCompleted() }
                                }
                            },
                            label = { Text("Custom mode") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Max 10 chars") }
                        )
                    }
                    if (showPaymentWarning) {
                        Text("⚠️ Select payment mode to print order receipt", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val effectiveMode = if (paymentMode == "custom") customModeText else paymentMode
                            if (effectiveMode.isBlank()) {
                                showPaymentWarning = true
                            } else {
                                selectedOrder = null; onPrintReceipt(owi.order.id)
                            }
                        }, modifier = Modifier.weight(1f)) {
                            Text("Order Receipt", fontSize = 11.sp)
                        }
                        OutlinedButton(onClick = { selectedOrder = null; onPrintKitchenOrder(owi.order.id) }, modifier = Modifier.weight(1f)) {
                            Text("Kitchen Receipt", fontSize = 11.sp)
                        }
                        OutlinedButton(onClick = { selectedOrder = null; onUpdateOrder(owi.order.id) }, modifier = Modifier.weight(1f)) {
                            Text("Update", fontSize = 11.sp)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selectedOrder = null }) { Text("Close") } }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// DATE PICKER COMPOSABLE
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MakuluDatePicker(
    onDateSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val datePickerState = rememberDatePickerState()
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                datePickerState.selectedDateMillis?.let { millis ->
                    val instant = java.time.Instant.ofEpochMilli(millis)
                    val date = instant.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                    onDateSelected(date.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE))
                }
                onDismiss()
            }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    ) {
        DatePicker(state = datePickerState)
    }
}
