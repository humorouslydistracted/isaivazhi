package com.makulu.app

import android.content.Context
import android.os.Environment
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

// ─────────────────────────────────────────────────────────────────────────────
// CSV MANAGER
// Handles: auto-save on order complete, manual export, dual folder save,
// monthly files + latest file, health check
// ─────────────────────────────────────────────────────────────────────────────

@Singleton
class CsvManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val orderDao: OrderDao,
    private val menuItemDao: MenuItemDao,
    private val categoryDao: CategoryDao,
    private val shopSpendDao: ShopSpendDao,
    private val appSettingsDao: AppSettingsDao,
    private val receiptFieldDao: ReceiptFieldDao
) {
    data class FolderHealth(
        val appFolderOk: Boolean,
        val documentsFolderOk: Boolean,
        val appFolderPath: String,
        val documentsFolderPath: String
    )

    private val monthFormat = DateTimeFormatter.ofPattern("yyyy-MM")

    // App's external files directory
    private fun getAppFolder(): File {
        val dir = File(context.getExternalFilesDir(null), "makulu_backup")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    // Documents/Makulu folder
    private fun getDocumentsFolder(): File {
        val docs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val dir = File(docs, "Makulu")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun checkFolderHealth(): FolderHealth {
        val appFolder = getAppFolder()
        val docsFolder = getDocumentsFolder()
        return FolderHealth(
            appFolderOk = appFolder.exists() && appFolder.canWrite(),
            documentsFolderOk = docsFolder.exists() && docsFolder.canWrite(),
            appFolderPath = appFolder.absolutePath,
            documentsFolderPath = docsFolder.absolutePath
        )
    }

    // Called every time an order is completed
    suspend fun onOrderCompleted() {
        withContext(Dispatchers.IO) {
            exportAll()
        }
    }

    // Manual full export
    suspend fun exportAll() {
        withContext(Dispatchers.IO) {
            val month = LocalDate.now().format(monthFormat)
            exportOrders(month)
            exportSpending(month)
            exportMenuItems(month)
            exportConsolidated(month)
            markExportTime()
        }
    }

    private suspend fun exportOrders(month: String) {
        val allOrders = orderDao.getAllCompletedOrdersSync()

        val header = "OrderNumber,Table,Date,ItemName,Quantity,Price,LineTotal,OrderTotal,Discount,CGST,SGST,FinalTotal,PaymentMode,Status\n"
        val rows = StringBuilder(header)
        allOrders.forEach { owi ->
            owi.items.forEach { item ->
                rows.append("${owi.order.orderNumber},${owi.order.tableName},${owi.order.completedAt},")
                rows.append("${escapeCsv(item.menuItemName)},${item.quantity},${item.price},${item.lineTotal},")
                rows.append("${owi.order.totalAmount},${owi.order.discountAmount},${owi.order.cgstAmount},${owi.order.sgstAmount},${owi.order.finalTotal},${escapeCsv(owi.order.paymentMode)},${owi.order.status}\n")
            }
        }

        val content = rows.toString()
        writeToFile("makulu_orders_$month.csv", content)
        writeToFile("makulu_orders_latest.csv", content)
    }

    private suspend fun exportSpending(month: String) {
        val spends = shopSpendDao.getAllSync()
        val header = "ItemName,Amount,Date,CreatedAt\n"
        val rows = StringBuilder(header)
        spends.forEach { s ->
            rows.append("${escapeCsv(s.itemName)},${s.amount},${s.date},${s.createdAt}\n")
        }

        val content = rows.toString()
        writeToFile("makulu_spending_$month.csv", content)
        writeToFile("makulu_spending_latest.csv", content)
    }

    private suspend fun exportMenuItems(month: String) {
        val categories = categoryDao.getAllSync()
        val items = menuItemDao.getAllSync()
        val header = "ItemName,Price,Category,IsAvailable,SortOrder\n"
        val rows = StringBuilder(header)
        items.forEach { item ->
            val catName = categories.find { it.id == item.categoryId }?.name ?: "Unknown"
            rows.append("${escapeCsv(item.name)},${item.price},${escapeCsv(catName)},${item.isAvailable},${item.sortOrder}\n")
        }

        val content = rows.toString()
        writeToFile("makulu_menu_items_$month.csv", content)
        writeToFile("makulu_menu_items_latest.csv", content)
    }

    private suspend fun exportConsolidated(month: String) {
        val allOrders = orderDao.getAllCompletedOrdersSync()
        val spends = shopSpendDao.getAllSync()

        val totalRevenue = allOrders.sumOf { it.order.totalAmount }
        val totalSpending = spends.sumOf { it.amount }
        val totalOrders = allOrders.size

        val header = "Type,Metric,Value\n"
        val rows = StringBuilder(header)
        rows.append("Summary,TotalOrders,$totalOrders\n")
        rows.append("Summary,TotalRevenue,$totalRevenue\n")
        rows.append("Summary,TotalSpending,$totalSpending\n")
        rows.append("Summary,NetProfit,${totalRevenue - totalSpending}\n")
        rows.append("\n")

        // Item-wise sales summary
        val salesMap = mutableMapOf<String, Int>()
        allOrders.forEach { owi ->
            owi.items.forEach { item ->
                salesMap[item.menuItemName] = (salesMap[item.menuItemName] ?: 0) + item.quantity
            }
        }
        salesMap.entries.sortedByDescending { it.value }.forEach { (name, qty) ->
            rows.append("ItemSales,${escapeCsv(name)},$qty\n")
        }

        val content = rows.toString()
        writeToFile("makulu_consolidated_$month.csv", content)
        writeToFile("makulu_consolidated_latest.csv", content)
    }

    private fun writeToFile(fileName: String, content: String) {
        try {
            val appFile = File(getAppFolder(), fileName)
            FileWriter(appFile).use { it.write(content) }
        } catch (_: Exception) {}

        try {
            val docsFile = File(getDocumentsFolder(), fileName)
            FileWriter(docsFile).use { it.write(content) }
        } catch (_: Exception) {}
    }

    private fun escapeCsv(value: String): String {
        return if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            "\"${value.replace("\"", "\"\"")}\""
        } else value
    }

    data class BackupFileInfo(val name: String, val sizeKb: Long, val lastModified: Long)

    fun listBackupFiles(): Pair<List<BackupFileInfo>, List<BackupFileInfo>> {
        val appFiles = getAppFolder().listFiles()
            ?.filter { it.extension == "csv" }
            ?.sortedByDescending { it.lastModified() }
            ?.map { BackupFileInfo(it.name, it.length() / 1024, it.lastModified()) }
            ?: emptyList()

        val docFiles = getDocumentsFolder().listFiles()
            ?.filter { it.extension == "csv" }
            ?.sortedByDescending { it.lastModified() }
            ?.map { BackupFileInfo(it.name, it.length() / 1024, it.lastModified()) }
            ?: emptyList()

        return appFiles to docFiles
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CSV SYNC: Check if app folder CSVs were modified externally
    // ─────────────────────────────────────────────────────────────────────────

    private val LAST_EXPORT_KEY = "csv_last_export_timestamp"

    /** Returns true if CSV files appear to have been modified externally since last export */
    suspend fun hasExternalChanges(): Boolean {
        val lastExport = appSettingsDao.get(LAST_EXPORT_KEY)?.toLongOrNull() ?: return false
        val appFolder = getAppFolder()
        val latestFiles = appFolder.listFiles()?.filter { it.name.contains("_latest.csv") } ?: return false
        return latestFiles.any { it.lastModified() > lastExport }
    }

    /** Update the last export timestamp to current time */
    private suspend fun markExportTime() {
        appSettingsDao.set(AppSettings(LAST_EXPORT_KEY, System.currentTimeMillis().toString()))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CSV IMPORT: Import from a directory, replacing all data
    // ─────────────────────────────────────────────────────────────────────────

    data class ImportResult(val success: Boolean, val message: String, val ordersImported: Int = 0, val spendsImported: Int = 0, val menuItemsImported: Int = 0)

    /** Import CSV files from a given directory. Replaces all existing data. */
    suspend fun importFromDirectory(directory: File): ImportResult = withContext(Dispatchers.IO) {
        try {
            var ordersImported = 0
            var spendsImported = 0
            var menuItemsImported = 0

            // Import menu items
            val menuFile = findLatestFile(directory, "menu_items")
            if (menuFile != null) {
                menuItemsImported = importMenuItems(menuFile)
            }

            // Import spending
            val spendFile = findLatestFile(directory, "spending")
            if (spendFile != null) {
                spendsImported = importSpending(spendFile)
            }

            // Import orders
            val orderFile = findLatestFile(directory, "orders")
            if (orderFile != null) {
                ordersImported = importOrders(orderFile)
            }

            ImportResult(true, "Import complete", ordersImported, spendsImported, menuItemsImported)
        } catch (e: Exception) {
            ImportResult(false, "Import failed: ${e.message}")
        }
    }

    /** Sync from app folder CSVs into DB (for external edits) */
    suspend fun syncFromCsv(): ImportResult {
        return importFromDirectory(getAppFolder())
    }

    private fun findLatestFile(dir: File, nameContains: String): File? {
        return dir.listFiles()
            ?.filter { it.name.contains(nameContains) && it.extension == "csv" }
            ?.maxByOrNull { it.lastModified() }
    }

    private suspend fun importMenuItems(file: File): Int {
        val lines = file.readLines().drop(1) // Skip header
        if (lines.isEmpty()) return 0

        // Clear existing
        menuItemDao.deleteAll()
        categoryDao.deleteAll()

        // Parse and insert
        val categoryMap = mutableMapOf<String, Long>()
        var catOrder = 0
        val items = mutableListOf<MenuItem>()

        lines.forEach { line ->
            val cols = parseCsvLine(line)
            if (cols.size >= 4) {
                val itemName = cols[0]
                val price = cols[1].toDoubleOrNull() ?: 0.0
                val catName = cols[2]
                val isAvailable = cols[3].toBooleanStrictOrNull() ?: true
                val sortOrder = cols.getOrNull(4)?.toIntOrNull() ?: 0

                if (!categoryMap.containsKey(catName)) {
                    val catId = categoryDao.insert(Category(name = catName, sortOrder = catOrder++))
                    categoryMap[catName] = catId
                }

                items.add(MenuItem(name = itemName, price = price, categoryId = categoryMap[catName]!!, isAvailable = isAvailable, sortOrder = sortOrder))
            }
        }
        menuItemDao.insertAll(items)
        return items.size
    }

    private suspend fun importSpending(file: File): Int {
        val lines = file.readLines().drop(1)
        if (lines.isEmpty()) return 0

        shopSpendDao.deleteAll()
        val spends = mutableListOf<ShopSpend>()

        lines.forEach { line ->
            val cols = parseCsvLine(line)
            if (cols.size >= 3) {
                val itemName = cols[0]
                val amount = cols[1].toDoubleOrNull() ?: 0.0
                val date = cols[2]
                val createdAt = cols.getOrNull(3) ?: date
                spends.add(ShopSpend(itemName = itemName, amount = amount, date = date, createdAt = createdAt))
            }
        }
        shopSpendDao.insertAll(spends)
        return spends.size
    }

    private suspend fun importOrders(file: File): Int {
        val lines = file.readLines().drop(1)
        if (lines.isEmpty()) return 0

        orderDao.deleteAllOrderItems()
        orderDao.deleteAllOrders()

        // Group by order number
        val orderGroups = mutableMapOf<String, MutableList<List<String>>>()
        lines.forEach { line ->
            val cols = parseCsvLine(line)
            if (cols.size >= 8) {
                val orderNumber = cols[0]
                orderGroups.getOrPut(orderNumber) { mutableListOf() }.add(cols)
            }
        }

        var count = 0
        orderGroups.forEach { (orderNumber, rows) ->
            val firstRow = rows.first()
            val tableName = firstRow[1]
            val completedAt = firstRow[2]
            val total = firstRow[7].toDoubleOrNull() ?: 0.0
            val status = firstRow.getOrNull(8) ?: "COMPLETED"

            val orderId = orderDao.insertOrder(Order(
                orderNumber = orderNumber,
                tableId = 0, // Can't recover original table ID
                tableName = tableName,
                status = OrderStatus.valueOf(status),
                totalAmount = total,
                completedAt = completedAt
            ))

            val orderItems = rows.map { cols ->
                OrderItem(
                    orderId = orderId,
                    menuItemId = 0,
                    menuItemName = cols[3],
                    price = cols[5].toDoubleOrNull() ?: 0.0,
                    quantity = cols[4].toIntOrNull() ?: 1
                )
            }
            orderDao.insertOrderItems(orderItems)
            count++
        }
        return count
    }

    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        var current = StringBuilder()
        var inQuotes = false
        for (c in line) {
            when {
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> { result.add(current.toString()); current = StringBuilder() }
                else -> current.append(c)
            }
        }
        result.add(current.toString())
        return result
    }
}
