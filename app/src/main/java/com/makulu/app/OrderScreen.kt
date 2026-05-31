package com.makulu.app

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel

// ─────────────────────────────────────────────────────────────────────────────
// ORDER SCREEN — Main homepage for order taking
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrderScreen(
    viewModel: OrderViewModel = hiltViewModel(),
    onPrintReceipt: (Long) -> Unit = {},
    onPrintKitchenOrder: (Long) -> Unit = {},
    onPreviewOrder: (Long) -> Unit = {}
) {
    val tables by viewModel.tables.collectAsState()
    val selectedTable by viewModel.selectedTable.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val availableItems by viewModel.availableItems.collectAsState()
    val cart by viewModel.cart.collectAsState()
    val currentOrderStatus by viewModel.currentOrderStatus.collectAsState()
    val currentOrderId by viewModel.currentOrderId.collectAsState()

    var showReviewSheet by remember { mutableStateOf(false) }
    var showCancelDialog by remember { mutableStateOf(false) }
    var expandedCategory by remember { mutableStateOf<Long?>(null) }
    var showPreview by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        // ─── TABLE SELECTOR (Horizontal Scroll) ───
        if (selectedTable == null) {
            // Show table selection prompt
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("Select a table to start", style = MaterialTheme.typography.headlineSmall)
                Spacer(modifier = Modifier.height(24.dp))
                TableSelector(tables = tables, selectedTable = null, onSelect = { viewModel.selectTable(it.table) })
            }
        } else {
            // Table selector row (always visible)
            TableSelector(
                tables = tables,
                selectedTable = selectedTable,
                onSelect = { viewModel.selectTable(it.table) }
            )

            Spacer(modifier = Modifier.height(8.dp))

            // ─── MENU CATEGORIES & ITEMS ───
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
            ) {
                if (categories.isEmpty() || availableItems.isEmpty()) {
                    item("empty_menu_state") {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    Icons.Default.RestaurantMenu,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(40.dp)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    "No menu items available yet",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    "Open Admin and add categories and items before taking orders for ${selectedTable?.name}.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                categories.forEach { category ->
                    val itemsInCategory = availableItems.filter { it.categoryId == category.id }
                    if (itemsInCategory.isNotEmpty()) {
                        item(key = "cat_${category.id}") {
                            CategoryHeader(
                                category = category,
                                isExpanded = expandedCategory == category.id,
                                itemCount = itemsInCategory.size,
                                onClick = {
                                    expandedCategory = if (expandedCategory == category.id) null else category.id
                                }
                            )
                        }

                        if (expandedCategory == category.id) {
                            items(itemsInCategory, key = { it.id }) { menuItem ->
                                MenuItemRow(
                                    item = menuItem,
                                    quantity = cart.find { it.menuItem.id == menuItem.id }?.quantity ?: 0,
                                    onAdd = { viewModel.addToCart(menuItem) },
                                    onRemove = { viewModel.removeFromCart(menuItem) }
                                )
                            }
                        }
                    }
                }
            }

            // ─── REVIEW ORDER BUTTON (Bottom) ───
            if (cart.isNotEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shadowElevation = 8.dp,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Button(
                        onClick = { showReviewSheet = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(Icons.Default.ShoppingCart, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Review Order (${cart.sumOf { it.quantity }} items) — ₹${"%.2f".format(viewModel.getCartTotal())}",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }

    // ─── REVIEW ORDER BOTTOM SHEET ───
    if (showReviewSheet) {
        ModalBottomSheet(
            onDismissRequest = { showReviewSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            ReviewOrderContent(
                cart = cart,
                orderStatus = currentOrderStatus,
                total = viewModel.getCartTotal(),
                tableName = selectedTable?.name ?: "",
                onRemoveItem = { viewModel.removeItemCompletely(it.menuItem) },
                onDecrease = { viewModel.removeFromCart(it.menuItem) },
                onIncrease = { viewModel.addToCart(it.menuItem) },
                onSaveDraft = {
                    viewModel.saveDraft { showReviewSheet = false }
                },
                onPlaceOrder = {
                    viewModel.placeOrder { showReviewSheet = false }
                },
                onComplete = {
                    viewModel.completeOrder {
                        showReviewSheet = false
                    }
                },
                onClear = {
                    viewModel.clearTable(
                        onConfirmNeeded = { showCancelDialog = true },
                        onDone = { showReviewSheet = false }
                    )
                },
                onPreview = {
                    showPreview = true
                }
            )
        }
    }

    // ─── CANCEL PLACED ORDER DIALOG ───
    if (showCancelDialog) {
        AlertDialog(
            onDismissRequest = { showCancelDialog = false },
            title = { Text("Cancel Order?") },
            text = { Text("This order won't be saved to ledger. Are you sure?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.confirmCancelPlacedOrder { showReviewSheet = false }
                    showCancelDialog = false
                }) { Text("Yes, Cancel", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showCancelDialog = false }) { Text("No, Keep") }
            }
        )
    }

    // ─── PREVIEW ORDER DIALOG ───
    if (showPreview && currentOrderId != null) {
        var paymentMode by remember { mutableStateOf("") }
        var customModeText by remember { mutableStateOf("") }
        var showPaymentWarning by remember { mutableStateOf(false) }

        // Load stored payment mode
        LaunchedEffect(currentOrderId) {
            val owi = viewModel.orderRepo.getOrderWithItems(currentOrderId!!)
            val stored = owi?.order?.paymentMode ?: ""
            if (stored == "GPay" || stored == "Cash") {
                paymentMode = stored
            } else if (stored.isNotBlank()) {
                paymentMode = "custom"
                customModeText = stored
            }
        }

        // Calculate inclusions live
        var inclusions by remember { mutableStateOf<OrderRepository.InclusionsBreakdown?>(null) }
        LaunchedEffect(cart) {
            inclusions = viewModel.calculateInclusions(viewModel.getCartTotal())
        }

        AlertDialog(
            onDismissRequest = { showPreview = false },
            title = { Text("${selectedTable?.name ?: ""} — Order Preview") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text("Order #${currentOrderId}")
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    cart.forEach { item ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(item.menuItem.name, modifier = Modifier.weight(1f))
                            Text("x${item.quantity}  ₹${"%.2f".format(item.menuItem.price * item.quantity)}")
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    // Show inclusions breakdown
                    inclusions?.let { inc ->
                        if (inc.discountAmount > 0) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Item Total"); Text("₹${"%.2f".format(inc.subtotal)}")
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Discount"); Text("-₹${"%.2f".format(inc.discountAmount)}")
                            }
                        }
                        if (inc.cgstAmount > 0 || inc.sgstAmount > 0) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("CGST"); Text("₹${"%.2f".format(inc.cgstAmount)}")
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("SGST"); Text("₹${"%.2f".format(inc.sgstAmount)}")
                            }
                        }
                        Text("Total: ₹${"%.2f".format(inc.finalTotal)}", fontWeight = FontWeight.Bold)
                    } ?: run {
                        Text("Total: ₹${"%.2f".format(viewModel.getCartTotal())}", fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(8.dp))

                    // Payment mode radio buttons
                    Text("Payment Mode", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(4.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = paymentMode == "GPay", onClick = {
                            paymentMode = "GPay"
                            showPaymentWarning = false
                            currentOrderId?.let { viewModel.updatePaymentMode(it, "GPay") }
                        })
                        Text("GPay", modifier = Modifier.padding(end = 12.dp))

                        RadioButton(selected = paymentMode == "Cash", onClick = {
                            paymentMode = "Cash"
                            showPaymentWarning = false
                            currentOrderId?.let { viewModel.updatePaymentMode(it, "Cash") }
                        })
                        Text("Cash", modifier = Modifier.padding(end = 12.dp))

                        RadioButton(selected = paymentMode == "custom", onClick = {
                            paymentMode = "custom"
                            showPaymentWarning = false
                            if (customModeText.isNotBlank()) {
                                currentOrderId?.let { viewModel.updatePaymentMode(it, customModeText) }
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
                                    currentOrderId?.let { viewModel.updatePaymentMode(it, filtered) }
                                }
                            },
                            label = { Text("Custom mode") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Max 10 chars") }
                        )
                    }

                    if (showPaymentWarning) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("⚠️ Select payment mode to print order receipt", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                val effectiveMode = if (paymentMode == "custom") customModeText else paymentMode
                                if (effectiveMode.isBlank()) {
                                    showPaymentWarning = true
                                } else {
                                    showPreview = false
                                    currentOrderId?.let { onPrintReceipt(it) }
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Order Receipt", fontSize = 11.sp)
                        }
                        OutlinedButton(
                            onClick = { showPreview = false; currentOrderId?.let { onPrintKitchenOrder(it) } },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Kitchen Receipt", fontSize = 11.sp)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showPreview = false }) { Text("Close") } }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// TABLE SELECTOR
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun TableSelector(
    tables: List<TableState>,
    selectedTable: TableInfo?,
    onSelect: (TableState) -> Unit
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(tables) { tableState ->
            val bgColor = when (tableState.status) {
                null -> TableFree
                OrderStatus.DRAFT -> TableDraft
                OrderStatus.PLACED -> TablePlaced
                OrderStatus.COMPLETED -> TableFree
            }
            val isSelected = selectedTable?.id == tableState.table.id

            Surface(
                onClick = { onSelect(tableState) },
                shape = RoundedCornerShape(12.dp),
                color = if (isSelected) bgColor else bgColor.copy(alpha = 0.3f),
                border = if (isSelected) BorderStroke(2.dp, bgColor) else null,
                modifier = Modifier.size(56.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = tableState.table.name,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// CATEGORY HEADER
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun CategoryHeader(
    category: Category,
    isExpanded: Boolean,
    itemCount: Int,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = category.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                fontSize = 19.sp,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "$itemCount items",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// MENU ITEM ROW (with +/- buttons)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun MenuItemRow(
    item: MenuItem,
    quantity: Int,
    onAdd: () -> Unit,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = item.name, fontSize = 19.sp)
            Text(
                text = "₹${"%.2f".format(item.price)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Quantity controls
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onRemove,
                enabled = quantity > 0,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (quantity > 0) MaterialTheme.colorScheme.errorContainer
                        else Color.Transparent
                    )
            ) {
                Icon(Icons.Default.Remove, contentDescription = "Remove", modifier = Modifier.size(20.dp))
            }

            Text(
                text = quantity.toString(),
                modifier = Modifier.padding(horizontal = 16.dp),
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )

            IconButton(
                onClick = onAdd,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add", modifier = Modifier.size(20.dp))
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// REVIEW ORDER CONTENT (inside bottom sheet)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun ReviewOrderContent(
    cart: List<CartItem>,
    orderStatus: OrderStatus?,
    total: Double,
    tableName: String,
    onRemoveItem: (CartItem) -> Unit,
    onDecrease: (CartItem) -> Unit,
    onIncrease: (CartItem) -> Unit,
    onSaveDraft: () -> Unit,
    onPlaceOrder: () -> Unit,
    onComplete: () -> Unit,
    onClear: () -> Unit,
    onPreview: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        // Header
        Text(
            text = "$tableName — Review Order",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(12.dp))

        // Items list (simple — no change tracking display)
        LazyColumn(modifier = Modifier.weight(1f, fill = false).heightIn(max = 400.dp)) {
            items(cart) { cartItem ->
                ReviewItemRow(
                    item = cartItem,
                    strikethrough = false,
                    onRemove = { onRemoveItem(cartItem) },
                    onDecrease = { onDecrease(cartItem) },
                    onIncrease = { onIncrease(cartItem) }
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // Total
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Total", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("₹${"%.2f".format(total)}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Action buttons (contextual)
        if (orderStatus == OrderStatus.PLACED) {
            // After place order: Preview | Complete | Clear (single row)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onPreview,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Olive)
                ) {
                    Text("Preview", fontSize = 12.sp)
                }
                Button(onClick = onComplete, modifier = Modifier.weight(1f)) {
                    Text("Complete", fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = onClear,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Clear", fontSize = 12.sp)
                }
            }
        } else {
            // Draft or new: Save Draft, Place Order, Clear
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSaveDraft, modifier = Modifier.weight(1f)) {
                    Text("Save Draft", fontSize = 12.sp)
                }
                Button(onClick = onPlaceOrder, modifier = Modifier.weight(1f)) {
                    Text("Place Order", fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = onClear,
                    modifier = Modifier.weight(0.7f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Clear", fontSize = 12.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
fun ReviewItemRow(
    item: CartItem,
    strikethrough: Boolean,
    onRemove: () -> Unit,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.menuItem.name,
                fontSize = 19.sp,
                textDecoration = if (strikethrough) TextDecoration.LineThrough else TextDecoration.None,
                color = if (strikethrough) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
            Text(
                "₹${"%.2f".format(item.menuItem.price)} × ${item.quantity} = ₹${"%.2f".format(item.menuItem.price * item.quantity)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (!strikethrough) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(
                    onClick = onDecrease,
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.errorContainer)
                ) {
                    Icon(Icons.Default.Remove, contentDescription = null, modifier = Modifier.size(14.dp))
                }
                Text("${item.quantity}", fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.padding(horizontal = 12.dp))
                IconButton(
                    onClick = onIncrease,
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                }
                Spacer(modifier = Modifier.width(6.dp))
                IconButton(onClick = onRemove, modifier = Modifier.size(42.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}
