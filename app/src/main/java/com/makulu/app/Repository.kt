package com.makulu.app

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject
import javax.inject.Singleton

// ─────────────────────────────────────────────────────────────────────────────
// HILT MODULE
// ─────────────────────────────────────────────────────────────────────────────

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): MakuluDatabase =
        MakuluDatabase.getInstance(context)

    @Provides fun provideCategoryDao(db: MakuluDatabase) = db.categoryDao()
    @Provides fun provideMenuItemDao(db: MakuluDatabase) = db.menuItemDao()
    @Provides fun provideTableDao(db: MakuluDatabase) = db.tableDao()
    @Provides fun provideOrderDao(db: MakuluDatabase) = db.orderDao()
    @Provides fun provideShopSpendDao(db: MakuluDatabase) = db.shopSpendDao()
    @Provides fun provideAppSettingsDao(db: MakuluDatabase) = db.appSettingsDao()
    @Provides fun provideReceiptFieldDao(db: MakuluDatabase) = db.receiptFieldDao()
}

// ─────────────────────────────────────────────────────────────────────────────
// REPOSITORIES
// ─────────────────────────────────────────────────────────────────────────────

@Singleton
class MenuRepository @Inject constructor(
    private val categoryDao: CategoryDao,
    private val menuItemDao: MenuItemDao
) {
    fun getAllCategories() = categoryDao.getAll()
    suspend fun getAllCategoriesSync() = categoryDao.getAllSync()
    suspend fun insertCategory(name: String, order: Int = 0) =
        categoryDao.insert(Category(name = name, sortOrder = order))
    suspend fun updateCategory(category: Category) = categoryDao.update(category)
    suspend fun deleteCategory(category: Category) = categoryDao.delete(category)
    suspend fun deleteCategoryWithItems(category: Category) {
        menuItemDao.deleteByCategoryId(category.id)
        categoryDao.delete(category)
    }
    suspend fun updateCategorySortOrder(id: Long, order: Int) = categoryDao.updateSortOrder(id, order)

    fun getAvailableItems() = menuItemDao.getAvailable()
    fun getAllItems() = menuItemDao.getAll()
    suspend fun getAllItemsSync() = menuItemDao.getAllSync()
    fun getItemsByCategory(categoryId: Long) = menuItemDao.getByCategory(categoryId)
    suspend fun insertItem(item: MenuItem) = menuItemDao.insert(item)
    suspend fun updateItem(item: MenuItem) = menuItemDao.update(item)
    suspend fun deleteItem(item: MenuItem) = menuItemDao.delete(item)
    suspend fun setAvailability(id: Long, available: Boolean) = menuItemDao.setAvailability(id, available)
    suspend fun updateItemSortOrder(id: Long, order: Int) = menuItemDao.updateSortOrder(id, order)
}

@Singleton
class TableRepository @Inject constructor(private val tableDao: TableDao) {
    fun getAll() = tableDao.getAll()
    suspend fun getAllSync() = tableDao.getAllSync()
    suspend fun insert(name: String, order: Int = 0) =
        tableDao.insert(TableInfo(name = name, sortOrder = order))
    suspend fun update(table: TableInfo) = tableDao.update(table)
    suspend fun delete(table: TableInfo) = tableDao.delete(table)
    suspend fun updateSortOrder(id: Long, order: Int) = tableDao.updateSortOrder(id, order)
}

@Singleton
class OrderRepository @Inject constructor(
    private val orderDao: OrderDao,
    private val settingsRepository: SettingsRepository
) {

    suspend fun generateOrderNumber(): String {
        val today = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
        val count = orderDao.getOrderCountForDate(LocalDate.now().toString()) + 1
        return "ORD-$today-${count.toString().padStart(3, '0')}"
    }

    suspend fun createDraftOrder(tableId: Long, tableName: String, items: List<CartItem>): Long {
        val orderNumber = generateOrderNumber()
        val total = items.sumOf { it.menuItem.price * it.quantity }
        val orderId = orderDao.insertOrder(
            Order(
                orderNumber = orderNumber,
                tableId = tableId,
                tableName = tableName,
                status = OrderStatus.DRAFT,
                totalAmount = total
            )
        )
        val orderItems = items.map {
            OrderItem(
                orderId = orderId,
                menuItemId = it.menuItem.id,
                menuItemName = it.menuItem.name,
                price = it.menuItem.price,
                quantity = it.quantity
            )
        }
        orderDao.insertOrderItems(orderItems)
        return orderId
    }

    suspend fun placeOrder(orderId: Long) {
        val owi = orderDao.getOrderWithItems(orderId) ?: return
        orderDao.updateOrder(owi.order.copy(status = OrderStatus.PLACED))
    }

    suspend fun updateOrderItems(orderId: Long, items: List<CartItem>) {
        orderDao.deleteOrderItems(orderId)
        val total = items.sumOf { it.menuItem.price * it.quantity }
        val owi = orderDao.getOrderWithItems(orderId) ?: return
        orderDao.updateOrder(owi.order.copy(totalAmount = total))
        val orderItems = items.map {
            OrderItem(
                orderId = orderId,
                menuItemId = it.menuItem.id,
                menuItemName = it.menuItem.name,
                price = it.menuItem.price,
                quantity = it.quantity
            )
        }
        orderDao.insertOrderItems(orderItems)
    }

    suspend fun completeOrder(orderId: Long) {
        val owi = orderDao.getOrderWithItems(orderId) ?: return
        val now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

        val subtotal = owi.items.sumOf { it.price * it.quantity }

        // Calculate discount
        val discountEnabled = settingsRepository.get(SettingsRepository.KEY_DISCOUNT_ENABLED) == "true"
        val discountAmount = if (discountEnabled) {
            val type = settingsRepository.get(SettingsRepository.KEY_DISCOUNT_TYPE) ?: "percentage"
            val value = settingsRepository.get(SettingsRepository.KEY_DISCOUNT_VALUE)?.toDoubleOrNull() ?: 0.0
            if (type == "percentage") subtotal * value / 100.0 else value
        } else 0.0

        val afterDiscount = subtotal - discountAmount

        // Calculate GST
        val gstEnabled = settingsRepository.get(SettingsRepository.KEY_GST_ENABLED) == "true"
        val cgstAmount: Double
        val sgstAmount: Double
        if (gstEnabled) {
            val cgstPct = settingsRepository.get(SettingsRepository.KEY_CGST_PERCENTAGE)?.toDoubleOrNull() ?: 2.5
            val sgstPct = settingsRepository.get(SettingsRepository.KEY_SGST_PERCENTAGE)?.toDoubleOrNull() ?: 2.5
            cgstAmount = afterDiscount * cgstPct / 100.0
            sgstAmount = afterDiscount * sgstPct / 100.0
        } else {
            cgstAmount = 0.0
            sgstAmount = 0.0
        }

        val finalTotal = afterDiscount + cgstAmount + sgstAmount

        orderDao.updateOrder(
            owi.order.copy(
                status = OrderStatus.COMPLETED,
                completedAt = now,
                discountAmount = discountAmount,
                cgstAmount = cgstAmount,
                sgstAmount = sgstAmount,
                finalTotal = finalTotal
            )
        )
    }

    suspend fun cancelOrder(orderId: Long) {
        val owi = orderDao.getOrderWithItems(orderId) ?: return
        orderDao.deleteOrder(owi.order)
    }

    suspend fun getActiveOrderForTable(tableId: Long) = orderDao.getActiveOrderForTable(tableId)
    suspend fun getOrderWithItems(orderId: Long) = orderDao.getOrderWithItems(orderId)
    suspend fun getAllActiveOrders() = orderDao.getAllActiveOrders()

    fun getTodayCompletedOrders(): Flow<List<OrderWithItems>> {
        val today = LocalDate.now().toString()
        return orderDao.getCompletedOrdersForDate(today)
    }

    fun getCompletedOrdersSince(startDate: String) = orderDao.getCompletedOrdersSince(startDate)
    fun getAllCompletedOrders() = orderDao.getAllCompletedOrders()

    suspend fun getRevenueToday(): Double {
        val today = LocalDate.now().toString()
        return orderDao.getRevenueForDate(today)
    }

    suspend fun getRevenueSince(startDate: String) = orderDao.getRevenueSince(startDate)

    suspend fun deleteCompletedOrder(order: Order) = orderDao.deleteOrder(order)

    // Reopen a completed order back to PLACED status on its original table.
    // Returns table name on success, null if table already has active order.
    suspend fun reopenCompletedOrder(orderId: Long): String? {
        val owi = orderDao.getOrderWithItems(orderId) ?: return null
        // Check if table already has an active order
        val existing = orderDao.getActiveOrderForTable(owi.order.tableId)
        if (existing != null) return null
        // Reopen: set status back to PLACED
        orderDao.updateOrder(owi.order.copy(status = OrderStatus.PLACED, completedAt = null))
        return owi.order.tableName
    }

    suspend fun updatePaymentMode(orderId: Long, mode: String) {
        orderDao.updatePaymentMode(orderId, mode)
    }

    /** Calculate discount & GST live from current settings (for preview before completion) */
    data class InclusionsBreakdown(
        val subtotal: Double,
        val discountAmount: Double,
        val cgstAmount: Double,
        val sgstAmount: Double,
        val finalTotal: Double
    )

    suspend fun calculateInclusions(itemTotal: Double): InclusionsBreakdown {
        val discountEnabled = settingsRepository.get(SettingsRepository.KEY_DISCOUNT_ENABLED) == "true"
        val discountAmount = if (discountEnabled) {
            val type = settingsRepository.get(SettingsRepository.KEY_DISCOUNT_TYPE) ?: "percentage"
            val value = settingsRepository.get(SettingsRepository.KEY_DISCOUNT_VALUE)?.toDoubleOrNull() ?: 0.0
            if (type == "percentage") itemTotal * value / 100.0 else value
        } else 0.0

        val afterDiscount = itemTotal - discountAmount

        val gstEnabled = settingsRepository.get(SettingsRepository.KEY_GST_ENABLED) == "true"
        val cgstAmount: Double
        val sgstAmount: Double
        if (gstEnabled) {
            val cgstPct = settingsRepository.get(SettingsRepository.KEY_CGST_PERCENTAGE)?.toDoubleOrNull() ?: 2.5
            val sgstPct = settingsRepository.get(SettingsRepository.KEY_SGST_PERCENTAGE)?.toDoubleOrNull() ?: 2.5
            cgstAmount = afterDiscount * cgstPct / 100.0
            sgstAmount = afterDiscount * sgstPct / 100.0
        } else {
            cgstAmount = 0.0
            sgstAmount = 0.0
        }

        val finalTotal = afterDiscount + cgstAmount + sgstAmount
        return InclusionsBreakdown(itemTotal, discountAmount, cgstAmount, sgstAmount, finalTotal)
    }
}

@Singleton
class SpendRepository @Inject constructor(private val dao: ShopSpendDao) {
    fun getLastTen() = dao.getLastTen()
    fun getAll() = dao.getAll()
    fun getByDate(date: String) = dao.getByDate(date)
    fun getSince(startDate: String) = dao.getSince(startDate)
    suspend fun getTotalForDate(date: String) = dao.getTotalForDate(date)
    suspend fun getTotalSince(startDate: String) = dao.getTotalSince(startDate)
    suspend fun insert(itemName: String, amount: Double, date: String = LocalDate.now().toString()) =
        dao.insert(ShopSpend(itemName = itemName, amount = amount, date = date))
    suspend fun update(spend: ShopSpend) = dao.update(spend)
    suspend fun delete(spend: ShopSpend) = dao.delete(spend)
}

@Singleton
class SettingsRepository @Inject constructor(
    private val settingsDao: AppSettingsDao,
    private val receiptFieldDao: ReceiptFieldDao
) {
    suspend fun get(key: String) = settingsDao.get(key)
    suspend fun set(key: String, value: String) = settingsDao.set(AppSettings(key, value))
    suspend fun getAllSettings() = settingsDao.getAll()

    fun getReceiptFields() = receiptFieldDao.getAll()
    suspend fun getEnabledReceiptFields() = receiptFieldDao.getEnabled()
    fun getReceiptFieldsByType(type: String) = receiptFieldDao.getByType(type)
    suspend fun getEnabledReceiptFieldsByType(type: String) = receiptFieldDao.getEnabledByType(type)
    suspend fun insertReceiptField(field: ReceiptField) = receiptFieldDao.insert(field)
    suspend fun updateReceiptField(field: ReceiptField) = receiptFieldDao.update(field)
    suspend fun deleteReceiptField(field: ReceiptField) = receiptFieldDao.delete(field)

    // Settings keys
    companion object {
        const val KEY_ADMIN_PIN = "admin_pin"
        const val KEY_SECURITY_QUESTION = "security_question"
        const val KEY_SECURITY_ANSWER = "security_answer"
        const val KEY_SETUP_COMPLETE = "setup_complete"
        const val KEY_PRINTER_ADDRESS = "printer_address"
        const val KEY_PRINTER_NAME = "printer_name"
        const val KEY_RECEIPT_FOOTER = "receipt_footer"
        const val KEY_RECEIPT_FOOTER_ENABLED = "receipt_footer_enabled"
        const val KEY_RECEIPT_SHOW_ORDER_NO = "receipt_show_order_no"
        const val KEY_RECEIPT_SHOW_DATETIME = "receipt_show_datetime"
        const val KEY_RECEIPT_SHOW_TABLE = "receipt_show_table"
        const val KEY_RECEIPT_SHOW_ITEMS = "receipt_show_items"
        const val KEY_RECEIPT_SHOW_TOTAL = "receipt_show_total"
        // Item size per receipt type
        const val KEY_ORDER_ITEM_SIZE = "order_item_size"         // "Normal", "Large", "Double"
        const val KEY_KITCHEN_ITEM_SIZE = "kitchen_item_size"     // "Normal", "Large", "Double"
        // Item bold per receipt type
        const val KEY_ORDER_ITEM_BOLD = "order_item_bold"         // "true" or "false"
        const val KEY_KITCHEN_ITEM_BOLD = "kitchen_item_bold"     // "true" or "false"
        // Kitchen body toggles
        const val KEY_KITCHEN_SHOW_ORDER_NO = "kitchen_show_order_no"
        const val KEY_KITCHEN_SHOW_DATETIME = "kitchen_show_datetime"
        // Dot spacers (0-10)
        const val KEY_ORDER_DOTS_TOP = "order_dots_top"           // default "1"
        const val KEY_ORDER_DOTS_BOTTOM = "order_dots_bottom"     // default "2"
        const val KEY_KITCHEN_DOTS_TOP = "kitchen_dots_top"       // default "0"
        const val KEY_KITCHEN_DOTS_BOTTOM = "kitchen_dots_bottom" // default "2"
        // Printer hardware
        const val KEY_PAPER_WIDTH = "paper_width"                 // "58" (32-char) or "80" (48-char)
        const val KEY_CHARSET_FIX = "charset_fix"                 // "true" or "false"
        // Inclusions
        const val KEY_GST_ENABLED = "gst_enabled"
        const val KEY_GST_PERCENTAGE = "gst_percentage"
        const val KEY_CGST_PERCENTAGE = "cgst_percentage"
        const val KEY_SGST_PERCENTAGE = "sgst_percentage"
        const val KEY_DISCOUNT_ENABLED = "discount_enabled"
        const val KEY_DISCOUNT_TYPE = "discount_type" // "percentage" or "flat"
        const val KEY_DISCOUNT_VALUE = "discount_value"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// DATA CLASSES FOR UI STATE
// ─────────────────────────────────────────────────────────────────────────────

data class CartItem(
    val menuItem: MenuItem,
    val quantity: Int
)

data class TableState(
    val table: TableInfo,
    val status: OrderStatus? = null // null = free, DRAFT = draft, PLACED = placed
)

// ─────────────────────────────────────────────────────────────────────────────
// VIEW MODELS
// ─────────────────────────────────────────────────────────────────────────────

@HiltViewModel
class OrderViewModel @Inject constructor(
    private val menuRepo: MenuRepository,
    private val tableRepo: TableRepository,
    val orderRepo: OrderRepository,
    private val csvManager: CsvManager
) : ViewModel() {

    private val _tables = MutableStateFlow<List<TableState>>(emptyList())
    val tables: StateFlow<List<TableState>> = _tables.asStateFlow()

    private val _selectedTable = MutableStateFlow<TableInfo?>(null)
    val selectedTable: StateFlow<TableInfo?> = _selectedTable.asStateFlow()

    private val _categories = MutableStateFlow<List<Category>>(emptyList())
    val categories: StateFlow<List<Category>> = _categories.asStateFlow()

    private val _availableItems = MutableStateFlow<List<MenuItem>>(emptyList())
    val availableItems: StateFlow<List<MenuItem>> = _availableItems.asStateFlow()

    private val _cart = MutableStateFlow<List<CartItem>>(emptyList())
    val cart: StateFlow<List<CartItem>> = _cart.asStateFlow()

    private val _currentOrderId = MutableStateFlow<Long?>(null)
    val currentOrderId: StateFlow<Long?> = _currentOrderId.asStateFlow()

    private val _currentOrderStatus = MutableStateFlow<OrderStatus?>(null)
    val currentOrderStatus: StateFlow<OrderStatus?> = _currentOrderStatus.asStateFlow()

    // Items as they were when order was placed (for change tracking)
    private val _placedItems = MutableStateFlow<List<CartItem>>(emptyList())
    val placedItems: StateFlow<List<CartItem>> = _placedItems.asStateFlow()

    init {
        viewModelScope.launch {
            if (tableRepo.getAllSync().isEmpty()) {
                listOf("T01", "T02", "T03", "T04", "T05").forEachIndexed { index, tableName ->
                    tableRepo.insert(tableName, index)
                }
            }
        }
        viewModelScope.launch {
            menuRepo.getAllCategories().collect { _categories.value = it }
        }
        viewModelScope.launch {
            menuRepo.getAvailableItems().collect { _availableItems.value = it }
        }
        viewModelScope.launch {
            tableRepo.getAll().collect { tables ->
                refreshTableStates(tables)
            }
        }
    }

    private suspend fun refreshTableStates(tables: List<TableInfo>? = null) {
        val tableList = tables ?: tableRepo.getAllSync()
        val activeOrders = orderRepo.getAllActiveOrders()
        _tables.value = tableList.map { table ->
            val activeOrder = activeOrders.find { it.tableId == table.id }
            TableState(table = table, status = activeOrder?.status)
        }
    }

    fun selectTable(table: TableInfo) {
        viewModelScope.launch {
            // Auto-save current table's items as draft before switching
            autoSaveCurrentTable()

            _selectedTable.value = table
            val activeOrder = orderRepo.getActiveOrderForTable(table.id)
            if (activeOrder != null) {
                _currentOrderId.value = activeOrder.order.id
                _currentOrderStatus.value = activeOrder.order.status
                val cartItems = activeOrder.items.map { oi ->
                    val menuItem = _availableItems.value.find { it.id == oi.menuItemId }
                        ?: MenuItem(id = oi.menuItemId, name = oi.menuItemName, price = oi.price, categoryId = 0)
                    CartItem(menuItem = menuItem, quantity = oi.quantity)
                }
                _cart.value = cartItems
                if (activeOrder.order.status == OrderStatus.PLACED) {
                    _placedItems.value = cartItems.toList()
                } else {
                    _placedItems.value = emptyList()
                }
            } else {
                _currentOrderId.value = null
                _currentOrderStatus.value = null
                _cart.value = emptyList()
                _placedItems.value = emptyList()
            }
        }
    }

    private suspend fun autoSaveCurrentTable() {
        val table = _selectedTable.value ?: return
        val cart = _cart.value
        val orderId = _currentOrderId.value
        val status = _currentOrderStatus.value

        if (cart.isEmpty()) return
        if (status == OrderStatus.PLACED) {
            // Already placed, save changes
            if (orderId != null) orderRepo.updateOrderItems(orderId, cart)
        } else if (orderId != null) {
            // Existing draft, update
            orderRepo.updateOrderItems(orderId, cart)
        } else {
            // New items, no order yet — create draft
            orderRepo.createDraftOrder(table.id, table.name, cart)
        }
        refreshTableStates()
    }

    fun addToCart(menuItem: MenuItem) {
        val current = _cart.value.toMutableList()
        val existing = current.find { it.menuItem.id == menuItem.id }
        if (existing != null) {
            current[current.indexOf(existing)] = existing.copy(quantity = existing.quantity + 1)
        } else {
            current.add(CartItem(menuItem = menuItem, quantity = 1))
        }
        _cart.value = current
        saveIfPlaced()
    }

    fun removeFromCart(menuItem: MenuItem) {
        val current = _cart.value.toMutableList()
        val existing = current.find { it.menuItem.id == menuItem.id } ?: return
        if (existing.quantity > 1) {
            current[current.indexOf(existing)] = existing.copy(quantity = existing.quantity - 1)
        } else {
            current.remove(existing)
        }
        _cart.value = current
        saveIfPlaced()
    }

    fun removeItemCompletely(menuItem: MenuItem) {
        _cart.value = _cart.value.filter { it.menuItem.id != menuItem.id }
        saveIfPlaced()
    }

    /** Immediately persist cart to DB if the order is already placed */
    private fun saveIfPlaced() {
        if (_currentOrderStatus.value == OrderStatus.PLACED) {
            val orderId = _currentOrderId.value ?: return
            viewModelScope.launch {
                orderRepo.updateOrderItems(orderId, _cart.value)
                refreshTableStates()
            }
        }
    }

    fun saveDraft(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            val table = _selectedTable.value ?: return@launch
            val cart = _cart.value
            if (cart.isEmpty()) return@launch

            val orderId = _currentOrderId.value
            if (orderId != null) {
                orderRepo.updateOrderItems(orderId, cart)
            } else {
                val newId = orderRepo.createDraftOrder(table.id, table.name, cart)
                _currentOrderId.value = newId
                _currentOrderStatus.value = OrderStatus.DRAFT
            }
            refreshTableStates()
            onDone()
        }
    }

    fun placeOrder(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            val table = _selectedTable.value ?: return@launch
            val cart = _cart.value
            if (cart.isEmpty()) return@launch

            var orderId = _currentOrderId.value
            if (orderId == null) {
                orderId = orderRepo.createDraftOrder(table.id, table.name, cart)
            } else {
                orderRepo.updateOrderItems(orderId, cart)
            }
            orderRepo.placeOrder(orderId)
            _currentOrderId.value = orderId
            _currentOrderStatus.value = OrderStatus.PLACED
            _placedItems.value = _cart.value.toList()
            refreshTableStates()
            onDone()
        }
    }

    fun updateOrder(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            val orderId = _currentOrderId.value ?: return@launch
            orderRepo.updateOrderItems(orderId, _cart.value)
            _placedItems.value = _cart.value.toList()
            refreshTableStates()
            onDone()
        }
    }

    fun completeOrder(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            val orderId = _currentOrderId.value ?: return@launch
            orderRepo.updateOrderItems(orderId, _cart.value)
            orderRepo.completeOrder(orderId)
            csvManager.onOrderCompleted()
            _currentOrderId.value = null
            _currentOrderStatus.value = null
            _cart.value = emptyList()
            _placedItems.value = emptyList()
            refreshTableStates()
            onDone()
        }
    }

    fun clearTable(onConfirmNeeded: () -> Unit = {}, onDone: () -> Unit = {}) {
        val status = _currentOrderStatus.value
        if (status == OrderStatus.PLACED) {
            // Needs confirmation popup
            onConfirmNeeded()
        } else {
            // Draft or no order — clear silently
            viewModelScope.launch {
                val orderId = _currentOrderId.value
                if (orderId != null) orderRepo.cancelOrder(orderId)
                _currentOrderId.value = null
                _currentOrderStatus.value = null
                _cart.value = emptyList()
                _placedItems.value = emptyList()
                refreshTableStates()
                onDone()
            }
        }
    }

    fun confirmCancelPlacedOrder(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            val orderId = _currentOrderId.value ?: return@launch
            orderRepo.cancelOrder(orderId)
            _currentOrderId.value = null
            _currentOrderStatus.value = null
            _cart.value = emptyList()
            _placedItems.value = emptyList()
            refreshTableStates()
            onDone()
        }
    }

    fun getCartTotal(): Double = _cart.value.sumOf { it.menuItem.price * it.quantity }

    fun updatePaymentMode(orderId: Long, mode: String) {
        viewModelScope.launch {
            orderRepo.updatePaymentMode(orderId, mode)
            csvManager.onOrderCompleted()
        }
    }

    suspend fun calculateInclusions(itemTotal: Double) = orderRepo.calculateInclusions(itemTotal)
}

@HiltViewModel
class AdminViewModel @Inject constructor(
    private val menuRepo: MenuRepository,
    private val tableRepo: TableRepository,
    private val settingsRepo: SettingsRepository
) : ViewModel() {

    val categories = menuRepo.getAllCategories().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val allItems = menuRepo.getAllItems().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val tables = tableRepo.getAll().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val receiptFields = settingsRepo.getReceiptFields().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val orderReceiptFields = settingsRepo.getReceiptFieldsByType("order").stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val kitchenReceiptFields = settingsRepo.getReceiptFieldsByType("kitchen").stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // Tables
    fun addTable(name: String) = viewModelScope.launch {
        val count = tableRepo.getAllSync().size
        tableRepo.insert(name, count)
    }

    fun deleteTable(table: TableInfo) = viewModelScope.launch { tableRepo.delete(table) }

    fun renameTable(table: TableInfo, newName: String) = viewModelScope.launch {
        tableRepo.update(table.copy(name = newName))
    }

    fun reorderTables(tables: List<TableInfo>) = viewModelScope.launch {
        tables.forEachIndexed { i, t -> tableRepo.updateSortOrder(t.id, i) }
    }

    // Categories
    fun addCategory(name: String) = viewModelScope.launch {
        val count = menuRepo.getAllCategoriesSync().size
        menuRepo.insertCategory(name, count)
    }

    fun deleteCategory(category: Category) = viewModelScope.launch { menuRepo.deleteCategory(category) }
    fun deleteCategoryWithItems(category: Category) = viewModelScope.launch { menuRepo.deleteCategoryWithItems(category) }

    fun renameCategory(category: Category, newName: String) = viewModelScope.launch {
        menuRepo.updateCategory(category.copy(name = newName))
    }

    fun reorderCategories(categories: List<Category>) = viewModelScope.launch {
        categories.forEachIndexed { i, c -> menuRepo.updateCategorySortOrder(c.id, i) }
    }

    // Menu Items
    fun addItem(name: String, price: Double, categoryId: Long) = viewModelScope.launch {
        menuRepo.insertItem(MenuItem(name = name, price = price, categoryId = categoryId))
    }

    fun updateItem(item: MenuItem) = viewModelScope.launch { menuRepo.updateItem(item) }
    fun deleteItem(item: MenuItem) = viewModelScope.launch { menuRepo.deleteItem(item) }
    fun toggleAvailability(item: MenuItem) = viewModelScope.launch {
        menuRepo.setAvailability(item.id, !item.isAvailable)
    }

    fun reorderItems(items: List<MenuItem>) = viewModelScope.launch {
        items.forEachIndexed { i, item -> menuRepo.updateItemSortOrder(item.id, i) }
    }

    // Receipt fields
    fun addReceiptField(name: String, value: String, isHeader: Boolean) = viewModelScope.launch {
        settingsRepo.insertReceiptField(ReceiptField(fieldName = name, fieldValue = value, isHeader = isHeader))
    }

    fun addReceiptFieldWithStyle(name: String, value: String, isHeader: Boolean, fontSize: String, isBold: Boolean, receiptType: String = "order") = viewModelScope.launch {
        settingsRepo.insertReceiptField(ReceiptField(fieldName = name, fieldValue = value, isHeader = isHeader, fontSize = fontSize, isBold = isBold, receiptType = receiptType))
    }

    fun updateReceiptField(field: ReceiptField) = viewModelScope.launch {
        settingsRepo.updateReceiptField(field)
    }

    fun deleteReceiptField(field: ReceiptField) = viewModelScope.launch {
        settingsRepo.deleteReceiptField(field)
    }

    // Settings
    fun getSetting(key: String, onResult: (String?) -> Unit) = viewModelScope.launch {
        onResult(settingsRepo.get(key))
    }

    fun setSetting(key: String, value: String) = viewModelScope.launch {
        settingsRepo.set(key, value)
    }
}

@HiltViewModel
class LedgerViewModel @Inject constructor(
    private val orderRepo: OrderRepository
) : ViewModel() {

    private val _selectedTab = MutableStateFlow(0) // 0=History, 1=Today, 2=Week, 3=Month
    val selectedTab: StateFlow<Int> = _selectedTab.asStateFlow()

    val todayOrders = orderRepo.getTodayCompletedOrders()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val weekOrders: StateFlow<List<OrderWithItems>> = flow {
        val monday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString()
        emitAll(orderRepo.getCompletedOrdersSince(monday))
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val monthOrders: StateFlow<List<OrderWithItems>> = flow {
        val firstOfMonth = LocalDate.now().withDayOfMonth(1).toString()
        emitAll(orderRepo.getCompletedOrdersSince(firstOfMonth))
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _revenueToday = MutableStateFlow(0.0)
    val revenueToday: StateFlow<Double> = _revenueToday.asStateFlow()

    private val _revenueWeek = MutableStateFlow(0.0)
    val revenueWeek: StateFlow<Double> = _revenueWeek.asStateFlow()

    private val _revenueMonth = MutableStateFlow(0.0)
    val revenueMonth: StateFlow<Double> = _revenueMonth.asStateFlow()

    init { refreshRevenue() }

    fun selectTab(tab: Int) { _selectedTab.value = tab }

    fun refreshRevenue() {
        viewModelScope.launch {
            _revenueToday.value = orderRepo.getRevenueToday()
            val monday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString()
            _revenueWeek.value = orderRepo.getRevenueSince(monday)
            val firstOfMonth = LocalDate.now().withDayOfMonth(1).toString()
            _revenueMonth.value = orderRepo.getRevenueSince(firstOfMonth)
        }
    }

    fun deleteOrder(order: Order) {
        viewModelScope.launch {
            orderRepo.deleteCompletedOrder(order)
            refreshRevenue()
        }
    }
}

@HiltViewModel
class SpendingViewModel @Inject constructor(
    private val spendRepo: SpendRepository,
    private val csvManager: CsvManager
) : ViewModel() {

    val lastTen = spendRepo.getLastTen().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val allSpends = spendRepo.getAll().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _todayTotal = MutableStateFlow(0.0)
    val todayTotal: StateFlow<Double> = _todayTotal.asStateFlow()

    private val _weekTotal = MutableStateFlow(0.0)
    val weekTotal: StateFlow<Double> = _weekTotal.asStateFlow()

    private val _monthTotal = MutableStateFlow(0.0)
    val monthTotal: StateFlow<Double> = _monthTotal.asStateFlow()

    init { refreshTotals() }

    fun refreshTotals() {
        viewModelScope.launch {
            _todayTotal.value = spendRepo.getTotalForDate(LocalDate.now().toString())
            val monday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString()
            _weekTotal.value = spendRepo.getTotalSince(monday)
            val firstOfMonth = LocalDate.now().withDayOfMonth(1).toString()
            _monthTotal.value = spendRepo.getTotalSince(firstOfMonth)
        }
    }

    fun addSpend(itemName: String, amount: Double, date: String? = null) {
        viewModelScope.launch {
            spendRepo.insert(itemName, amount, date ?: LocalDate.now().toString())
            refreshTotals()
            csvManager.exportAll()
        }
    }

    fun updateSpend(spend: ShopSpend) {
        viewModelScope.launch {
            spendRepo.update(spend)
            refreshTotals()
            csvManager.exportAll()
        }
    }

    fun deleteSpend(spend: ShopSpend) {
        viewModelScope.launch {
            spendRepo.delete(spend)
            refreshTotals()
            csvManager.exportAll()
        }
    }

    fun getSpendsSince(startDate: String) = spendRepo.getSince(startDate)
}

@HiltViewModel
class AnalysisViewModel @Inject constructor(
    private val orderRepo: OrderRepository,
    private val spendRepo: SpendRepository,
    private val menuRepo: MenuRepository
) : ViewModel() {

    data class ItemSales(val name: String, val categoryName: String, val quantity: Int, val revenue: Double)
    data class AnalysisSummary(
        val revenue: Double = 0.0,
        val spending: Double = 0.0,
        val net: Double = 0.0,
        val itemSales: List<ItemSales> = emptyList()
    )

    private val _selectedPeriod = MutableStateFlow(0) // 0=Today, 1=Week, 2=Month
    val selectedPeriod: StateFlow<Int> = _selectedPeriod.asStateFlow()

    private val _summary = MutableStateFlow(AnalysisSummary())
    val summary: StateFlow<AnalysisSummary> = _summary.asStateFlow()

    private val _showAll = MutableStateFlow(false)
    val showAll: StateFlow<Boolean> = _showAll.asStateFlow()

    private val _ascending = MutableStateFlow(false)
    val ascending: StateFlow<Boolean> = _ascending.asStateFlow()

    init { loadAnalysis() }

    fun selectPeriod(period: Int) {
        _selectedPeriod.value = period
        loadAnalysis()
    }

    fun toggleShowAll() { _showAll.value = !_showAll.value }
    fun toggleSortOrder() {
        _ascending.value = !_ascending.value
        sortItems()
    }

    private fun sortItems() {
        val current = _summary.value
        val sorted = if (_ascending.value) {
            current.itemSales.sortedBy { it.quantity }
        } else {
            current.itemSales.sortedByDescending { it.quantity }
        }
        _summary.value = current.copy(itemSales = sorted)
    }

    fun loadAnalysis() {
        viewModelScope.launch {
            val startDate = when (_selectedPeriod.value) {
                0 -> LocalDate.now().toString()
                1 -> LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString()
                2 -> LocalDate.now().withDayOfMonth(1).toString()
                else -> LocalDate.now().toString()
            }

            val revenue = if (_selectedPeriod.value == 0) {
                orderRepo.getRevenueToday()
            } else {
                orderRepo.getRevenueSince(startDate)
            }

            val spending = if (_selectedPeriod.value == 0) {
                spendRepo.getTotalForDate(LocalDate.now().toString())
            } else {
                spendRepo.getTotalSince(startDate)
            }

            // Get all menu items for complete list
            val allMenuItems = menuRepo.getAllItemsSync()
            val allCategories = menuRepo.getAllCategoriesSync()

            // Get orders for period and calculate item sales
            val orders = if (_selectedPeriod.value == 0) {
                orderRepo.getTodayCompletedOrders().first()
            } else {
                orderRepo.getCompletedOrdersSince(startDate).first()
            }

            val salesMap = mutableMapOf<Long, Pair<String, Int>>()
            orders.forEach { owi ->
                owi.items.forEach { item ->
                    val current = salesMap[item.menuItemId]
                    salesMap[item.menuItemId] = Pair(
                        item.menuItemName,
                        (current?.second ?: 0) + item.quantity
                    )
                }
            }

            // Include all menu items (show 0 for unsold)
            val itemSales = allMenuItems.map { mi ->
                val sold = salesMap[mi.id]
                ItemSales(
                    name = sold?.first ?: mi.name,
                    categoryName = allCategories.firstOrNull { it.id == mi.categoryId }?.name ?: "Uncategorized",
                    quantity = sold?.second ?: 0,
                    revenue = (sold?.second ?: 0) * mi.price
                )
            }.sortedByDescending { it.quantity }

            _summary.value = AnalysisSummary(
                revenue = revenue,
                spending = spending,
                net = revenue - spending,
                itemSales = itemSales
            )
        }
    }
}
