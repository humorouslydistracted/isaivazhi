package com.makulu.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.io.OutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

// ─────────────────────────────────────────────────────────────────────────────
// BLUETOOTH PRINTER MANAGER
// ESC/POS commands over Bluetooth Classic SPP
// ─────────────────────────────────────────────────────────────────────────────

@Singleton
class PrinterManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepo: SettingsRepository
) {
    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        // ESC/POS Commands
        private val CMD_INIT = byteArrayOf(0x1B, 0x40) // ESC @
        private val CMD_ALIGN_CENTER = byteArrayOf(0x1B, 0x61, 0x01) // ESC a 1
        private val CMD_ALIGN_LEFT = byteArrayOf(0x1B, 0x61, 0x00) // ESC a 0
        private val CMD_ALIGN_RIGHT = byteArrayOf(0x1B, 0x61, 0x02) // ESC a 2
        private val CMD_BOLD_ON = byteArrayOf(0x1B, 0x45, 0x01) // ESC E 1
        private val CMD_BOLD_OFF = byteArrayOf(0x1B, 0x45, 0x00) // ESC E 0
        private val CMD_DOUBLE_SIZE = byteArrayOf(0x1D, 0x21, 0x11) // GS ! 0x11 — double height+width
        private val CMD_LARGE_SIZE  = byteArrayOf(0x1D, 0x21, 0x01) // GS ! 0x01 — double height only
        private val CMD_NORMAL_SIZE = byteArrayOf(0x1D, 0x21, 0x00) // GS ! 0x00
        private val CMD_CUT = byteArrayOf(0x1D, 0x56, 0x42, 0x00) // GS V 66 0
        private val CMD_FEED_3 = byteArrayOf(0x1B, 0x64, 0x03) // ESC d 3
        private val CMD_FEED_2 = byteArrayOf(0x1B, 0x64, 0x02) // ESC d 2
        private val CMD_FEED_4 = byteArrayOf(0x1B, 0x64, 0x04) // ESC d 4
        private val CMD_CODEPAGE_RUPEE = byteArrayOf(0x1B, 0x74, 66) // ESC t 66 — WPC1252 (includes ₹)
        private val CMD_LINE = "--------------------------------\n".toByteArray()
        // Dot spacer line — printed content that forces paper to physically advance
        private val SPACER = ".\n".toByteArray()

        // Format datetime in Indian format: dd-MM-yyyy hh:mm:ss.SS AM/PM
        private fun formatIndianDateTime(isoDateTime: String?): String {
            if (isoDateTime == null) return ""
            return try {
                val ldt = LocalDateTime.parse(isoDateTime, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                ldt.format(DateTimeFormatter.ofPattern("dd-MM-yyyy hh:mm a"))
            } catch (_: Exception) { isoDateTime }
        }

        // Right-aligned 2-column label/amount line for totals section (32 char width)
        private fun formatTotalLine(label: String, amount: String, width: Int = 32): String {
            val combined = "$label $amount"
            return if (combined.length <= width) {
                label.padEnd(width - amount.length) + amount + "\n"
            } else {
                "${label.take(width - amount.length - 1).padEnd(width - amount.length - 1)} $amount\n"
            }
        }
    }

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _printerName = MutableStateFlow<String?>(null)
    val printerName: StateFlow<String?> = _printerName.asStateFlow()

    // Printer log (most recent first)
    data class PrinterLog(val timestamp: String, val event: String, val success: Boolean)
    private val _logs = MutableStateFlow<List<PrinterLog>>(emptyList())
    val logs: StateFlow<List<PrinterLog>> = _logs.asStateFlow()

    private fun log(event: String, success: Boolean = true) {
        val ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        Log.d("MakuluPrinter", "[$ts] $event (${if (success) "OK" else "FAIL"})")
        _logs.value = listOf(PrinterLog(ts, event, success)) + _logs.value.take(49)
    }

    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @SuppressLint("MissingPermission")
    fun getPairedDevices(): List<BluetoothDevice> {
        return try {
            val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = btManager?.adapter ?: return emptyList()
            adapter.bondedDevices?.toList() ?: emptyList()
        } catch (e: SecurityException) {
            log("Bluetooth permission denied: ${e.message}", false)
            emptyList()
        } catch (e: Exception) {
            log("Failed to get paired devices: ${e.message}", false)
            emptyList()
        }
    }

    fun isBluetoothEnabled(): Boolean {
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return btManager?.adapter?.isEnabled == true
    }

    @SuppressLint("MissingPermission")
    fun connectToPrinter(device: BluetoothDevice, onResult: (Boolean) -> Unit) {
        scope.launch {
            try {
                log("Connecting to ${device.name ?: device.address}...")
                socket?.close()
                socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                socket?.connect()
                outputStream = socket?.outputStream
                _isConnected.value = true
                _printerName.value = device.name

                // Save printer address
                settingsRepo.set(SettingsRepository.KEY_PRINTER_ADDRESS, device.address)
                settingsRepo.set(SettingsRepository.KEY_PRINTER_NAME, device.name ?: "Printer")

                log("Connected to ${device.name ?: device.address}")
                withContext(Dispatchers.Main) { onResult(true) }
            } catch (e: IOException) {
                _isConnected.value = false
                _printerName.value = null
                log("Connection failed: ${e.message}", false)
                withContext(Dispatchers.Main) { onResult(false) }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun autoReconnect() {
        scope.launch {
            val address = settingsRepo.get(SettingsRepository.KEY_PRINTER_ADDRESS) ?: return@launch
            if (address.isBlank()) return@launch
            val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = btManager?.adapter ?: return@launch
            if (!adapter.isEnabled) { log("Auto-reconnect skipped: BT off", false); return@launch }

            val device = adapter.bondedDevices?.find { it.address == address } ?: run {
                log("Auto-reconnect: saved device not found", false); return@launch
            }
            try {
                log("Auto-reconnecting to ${device.name}...")
                socket?.close()
                socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                socket?.connect()
                outputStream = socket?.outputStream
                _isConnected.value = true
                _printerName.value = device.name
                log("Auto-reconnected to ${device.name}")
            } catch (e: IOException) {
                _isConnected.value = false
                log("Auto-reconnect failed: ${e.message}", false)
            }
        }
    }

    fun disconnect() {
        scope.launch {
            try {
                socket?.close()
            } catch (_: IOException) {}
            socket = null
            outputStream = null
            _isConnected.value = false
            _printerName.value = null
            log("Disconnected")
        }
    }

    fun forgetPrinter() {
        scope.launch {
            disconnect()
            settingsRepo.set(SettingsRepository.KEY_PRINTER_ADDRESS, "")
            settingsRepo.set(SettingsRepository.KEY_PRINTER_NAME, "")
            log("Printer forgotten")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PRINT RECEIPT
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun printReceipt(order: OrderWithItems, onError: (String) -> Unit = {}): Boolean {
        val os = outputStream
        if (os == null || !_isConnected.value) {
            log("Print receipt failed: not connected", false)
            onError("Printer not connected")
            return false
        }

        return withContext(Dispatchers.IO) {
            try {
                // ── Runtime settings ──
                val charsetEnabled = settingsRepo.get(SettingsRepository.KEY_CHARSET_FIX) != "false"
                val paperChars  = if (settingsRepo.get(SettingsRepository.KEY_PAPER_WIDTH) == "80") 48 else 32
                val divider     = "-".repeat(paperChars) + "\n"
                val dotsTop     = settingsRepo.get(SettingsRepository.KEY_ORDER_DOTS_TOP)?.toIntOrNull()    ?: 1
                val dotsBottom  = settingsRepo.get(SettingsRepository.KEY_ORDER_DOTS_BOTTOM)?.toIntOrNull() ?: 2
                val itemSize    = settingsRepo.get(SettingsRepository.KEY_ORDER_ITEM_SIZE) ?: "Normal"
                val itemBold    = settingsRepo.get(SettingsRepository.KEY_ORDER_ITEM_BOLD) == "true"

                os.write(CMD_INIT)
                if (charsetEnabled) os.write(CMD_CODEPAGE_RUPEE)

                // Configurable dots above header
                os.write(CMD_ALIGN_CENTER)
                repeat(dotsTop) { os.write(SPACER) }

                // Header fields — each with its own fontSize + isBold
                val headerFields = settingsRepo.getEnabledReceiptFieldsByType("order").filter { it.isHeader }
                if (headerFields.isNotEmpty()) {
                    os.write(CMD_ALIGN_CENTER)
                    headerFields.forEach { field ->
                        if (field.isBold) os.write(CMD_BOLD_ON)
                        when (field.fontSize) {
                            "Double" -> { os.write(CMD_DOUBLE_SIZE); os.write("${field.fieldValue}\n".toByteArray()); os.write(CMD_NORMAL_SIZE) }
                            "Large"  -> { os.write(CMD_LARGE_SIZE);  os.write("${field.fieldValue}\n".toByteArray()); os.write(CMD_NORMAL_SIZE) }
                            else     -> os.write("${field.fieldValue}\n".toByteArray())
                        }
                        if (field.isBold) os.write(CMD_BOLD_OFF)
                    }
                }

                os.write(divider.toByteArray())

                // Body meta fields — center aligned
                val showOrderNo  = settingsRepo.get(SettingsRepository.KEY_RECEIPT_SHOW_ORDER_NO)  != "false"
                val showDateTime = settingsRepo.get(SettingsRepository.KEY_RECEIPT_SHOW_DATETIME)  != "false"
                val showTable    = settingsRepo.get(SettingsRepository.KEY_RECEIPT_SHOW_TABLE)     != "false"
                val showItems    = settingsRepo.get(SettingsRepository.KEY_RECEIPT_SHOW_ITEMS)     != "false"
                val showTotal    = settingsRepo.get(SettingsRepository.KEY_RECEIPT_SHOW_TOTAL)     != "false"

                os.write(CMD_ALIGN_CENTER)
                if (showOrderNo)  os.write("Order: ${order.order.orderNumber}\n".toByteArray())
                if (showDateTime) {
                    val dt = order.order.completedAt ?: order.order.createdAt
                    os.write("Date: ${formatIndianDateTime(dt)}\n".toByteArray())
                }
                if (showTable) {
                    os.write(CMD_BOLD_ON)
                    os.write(CMD_DOUBLE_SIZE)
                    os.write("Table: ${order.order.tableName}\n".toByteArray())
                    os.write(CMD_NORMAL_SIZE)
                    os.write(CMD_BOLD_OFF)
                }

                if (showItems) {
                    os.write(divider.toByteArray())
                    os.write(CMD_ALIGN_CENTER)
                    os.write(CMD_BOLD_ON)
                    os.write(formatLine("Item", "Qty", "Amount", paperChars).toByteArray())
                    os.write(CMD_BOLD_OFF)
                    os.write(divider.toByteArray())

                    if (itemBold) os.write(CMD_BOLD_ON)
                    order.items.forEach { item ->
                        val line = formatLine(item.menuItemName, "x${item.quantity}", "₹${"%.2f".format(item.lineTotal)}", paperChars)
                        when (itemSize) {
                            "Large"  -> { os.write(CMD_LARGE_SIZE);  os.write(line.toByteArray()); os.write(CMD_NORMAL_SIZE) }
                            "Double" -> { os.write(CMD_DOUBLE_SIZE); os.write(line.toByteArray()); os.write(CMD_NORMAL_SIZE) }
                            else     -> os.write(line.toByteArray())
                        }
                    }
                    if (itemBold) os.write(CMD_BOLD_OFF)
                }

                if (showTotal) {
                    val itemTotal = order.order.totalAmount

                    val discountAmt: Double
                    val cgstAmt: Double
                    val sgstAmt: Double
                    val finalTotal: Double

                    if (order.order.status == OrderStatus.COMPLETED && order.order.finalTotal > 0) {
                        discountAmt = order.order.discountAmount
                        cgstAmt     = order.order.cgstAmount
                        sgstAmt     = order.order.sgstAmount
                        finalTotal  = order.order.finalTotal
                    } else {
                        val discountEnabled = settingsRepo.get(SettingsRepository.KEY_DISCOUNT_ENABLED) == "true"
                        discountAmt = if (discountEnabled) {
                            val type  = settingsRepo.get(SettingsRepository.KEY_DISCOUNT_TYPE) ?: "percentage"
                            val value = settingsRepo.get(SettingsRepository.KEY_DISCOUNT_VALUE)?.toDoubleOrNull() ?: 0.0
                            if (type == "percentage") itemTotal * value / 100.0 else value
                        } else 0.0

                        val afterDiscount = itemTotal - discountAmt
                        val gstEnabled = settingsRepo.get(SettingsRepository.KEY_GST_ENABLED) == "true"
                        if (gstEnabled) {
                            val cgstPct = settingsRepo.get(SettingsRepository.KEY_CGST_PERCENTAGE)?.toDoubleOrNull() ?: 2.5
                            val sgstPct = settingsRepo.get(SettingsRepository.KEY_SGST_PERCENTAGE)?.toDoubleOrNull() ?: 2.5
                            cgstAmt = afterDiscount * cgstPct / 100.0
                            sgstAmt = afterDiscount * sgstPct / 100.0
                        } else {
                            cgstAmt = 0.0
                            sgstAmt = 0.0
                        }
                        finalTotal = afterDiscount + cgstAmt + sgstAmt
                    }

                    val hasDiscount = discountAmt > 0
                    val hasGst = cgstAmt > 0 || sgstAmt > 0

                    if (hasDiscount || hasGst) {
                        os.write(divider.toByteArray())
                    }

                    os.write(CMD_ALIGN_CENTER)

                    if (hasDiscount) {
                        os.write(formatTotalLine("Item Total:", "₹${"%.2f".format(itemTotal)}", paperChars).toByteArray())
                        os.write(formatTotalLine("Discount:", "-₹${"%.2f".format(discountAmt)}", paperChars).toByteArray())
                    }

                    if (hasGst) {
                        val afterDiscount = itemTotal - discountAmt
                        os.write(formatTotalLine("Sub Total:", "₹${"%.2f".format(afterDiscount)}", paperChars).toByteArray())
                        os.write(formatTotalLine("CGST:", "₹${"%.2f".format(cgstAmt)}", paperChars).toByteArray())
                        os.write(formatTotalLine("SGST:", "₹${"%.2f".format(sgstAmt)}", paperChars).toByteArray())
                    }

                    os.write(divider.toByteArray())
                    os.write(CMD_ALIGN_CENTER)
                    os.write(CMD_BOLD_ON)
                    os.write(CMD_DOUBLE_SIZE)
                    os.write("Total: ₹${"%.2f".format(finalTotal)}\n".toByteArray())
                    os.write(CMD_NORMAL_SIZE)

                    if (order.order.paymentMode.isNotBlank()) {
                        os.write("MODE: ${order.order.paymentMode}\n".toByteArray())
                    }
                    os.write(CMD_BOLD_OFF)
                }

                // Footer
                val footerFields = settingsRepo.getEnabledReceiptFieldsByType("order").filter { !it.isHeader }
                if (footerFields.isNotEmpty()) {
                    os.write(divider.toByteArray())
                    os.write(CMD_ALIGN_CENTER)
                    footerFields.forEach { field ->
                        os.write("${field.fieldValue}\n".toByteArray())
                    }
                }

                // Configurable dots below footer
                os.write(CMD_ALIGN_CENTER)
                repeat(dotsBottom) { os.write(SPACER) }

                os.write(CMD_FEED_4)
                os.write(CMD_FEED_4)
                os.write(CMD_FEED_4)
                os.write(CMD_CUT)
                os.flush()
                log("Receipt printed: ${order.order.orderNumber}")
                true
            } catch (e: IOException) {
                _isConnected.value = false
                log("Receipt print failed: ${e.message}", false)
                onError("Print failed: ${e.message}")
                false
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PRINT KITCHEN ORDER
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun printKitchenOrder(order: OrderWithItems, onError: (String) -> Unit = {}): Boolean {
        val os = outputStream
        if (os == null || !_isConnected.value) {
            log("Kitchen print failed: not connected", false)
            onError("Printer not connected")
            return false
        }

        return withContext(Dispatchers.IO) {
            try {
                // ── Runtime settings ──
                val charsetEnabled   = settingsRepo.get(SettingsRepository.KEY_CHARSET_FIX) != "false"
                val paperChars       = if (settingsRepo.get(SettingsRepository.KEY_PAPER_WIDTH) == "80") 48 else 32
                val divider          = "-".repeat(paperChars) + "\n"
                val dotsTop          = settingsRepo.get(SettingsRepository.KEY_KITCHEN_DOTS_TOP)?.toIntOrNull()    ?: 0
                val dotsBottom       = settingsRepo.get(SettingsRepository.KEY_KITCHEN_DOTS_BOTTOM)?.toIntOrNull() ?: 2
                val itemSize         = settingsRepo.get(SettingsRepository.KEY_KITCHEN_ITEM_SIZE) ?: "Normal"
                val itemBold         = settingsRepo.get(SettingsRepository.KEY_KITCHEN_ITEM_BOLD) == "true"
                val showOrderNo      = settingsRepo.get(SettingsRepository.KEY_KITCHEN_SHOW_ORDER_NO) != "false"
                val showDateTime     = settingsRepo.get(SettingsRepository.KEY_KITCHEN_SHOW_DATETIME) != "false"
                val kitchenHeaders   = settingsRepo.getEnabledReceiptFieldsByType("kitchen").filter { it.isHeader }
                val kitchenFooters   = settingsRepo.getEnabledReceiptFieldsByType("kitchen").filter { !it.isHeader }

                os.write(CMD_INIT)
                if (charsetEnabled) os.write(CMD_CODEPAGE_RUPEE)

                // Configurable dots above header
                os.write(CMD_ALIGN_CENTER)
                repeat(dotsTop) { os.write(SPACER) }

                // Kitchen header fields (default seeded: "KITCHEN ORDER" Double+bold)
                os.write(CMD_ALIGN_CENTER)
                if (kitchenHeaders.isNotEmpty()) {
                    kitchenHeaders.forEach { field ->
                        if (field.isBold) os.write(CMD_BOLD_ON)
                        when (field.fontSize) {
                            "Double" -> { os.write(CMD_DOUBLE_SIZE); os.write("${field.fieldValue}\n".toByteArray()); os.write(CMD_NORMAL_SIZE) }
                            "Large"  -> { os.write(CMD_LARGE_SIZE);  os.write("${field.fieldValue}\n".toByteArray()); os.write(CMD_NORMAL_SIZE) }
                            else     -> os.write("${field.fieldValue}\n".toByteArray())
                        }
                        if (field.isBold) os.write(CMD_BOLD_OFF)
                    }
                } else {
                    // Fallback if DB not yet migrated
                    os.write(CMD_BOLD_ON); os.write(CMD_DOUBLE_SIZE)
                    os.write("KITCHEN ORDER\n".toByteArray())
                    os.write(CMD_NORMAL_SIZE); os.write(CMD_BOLD_OFF)
                }
                os.write(divider.toByteArray())

                // Table (always shown bold+double), Order, Date
                os.write(CMD_ALIGN_CENTER)
                os.write(CMD_BOLD_ON)
                os.write(CMD_DOUBLE_SIZE)
                os.write("Table: ${order.order.tableName}\n".toByteArray())
                os.write(CMD_NORMAL_SIZE)
                os.write(CMD_BOLD_OFF)

                os.write(CMD_ALIGN_CENTER)
                if (showOrderNo) os.write("Order: ${order.order.orderNumber}\n".toByteArray())
                if (showDateTime) {
                    val dt = order.order.completedAt ?: order.order.createdAt
                    os.write("Date: ${formatIndianDateTime(dt)}\n".toByteArray())
                }
                os.write(divider.toByteArray())

                // Items
                os.write(CMD_BOLD_ON)
                os.write(formatKitchenLine("Item", "Qty", paperChars).toByteArray())
                os.write(CMD_BOLD_OFF)
                os.write(divider.toByteArray())
                if (itemBold) os.write(CMD_BOLD_ON)
                order.items.forEach { item ->
                    val line = formatKitchenLine(item.menuItemName, "x${item.quantity}", paperChars)
                    when (itemSize) {
                        "Large"  -> { os.write(CMD_LARGE_SIZE);  os.write(line.toByteArray()); os.write(CMD_NORMAL_SIZE) }
                        "Double" -> { os.write(CMD_DOUBLE_SIZE); os.write(line.toByteArray()); os.write(CMD_NORMAL_SIZE) }
                        else     -> os.write(line.toByteArray())
                    }
                }
                if (itemBold) os.write(CMD_BOLD_OFF)
                os.write(divider.toByteArray())

                // Kitchen footer fields
                if (kitchenFooters.isNotEmpty()) {
                    os.write(CMD_ALIGN_CENTER)
                    kitchenFooters.forEach { field ->
                        os.write("${field.fieldValue}\n".toByteArray())
                    }
                }

                // Configurable dots below footer
                os.write(CMD_ALIGN_CENTER)
                repeat(dotsBottom) { os.write(SPACER) }

                os.write(CMD_FEED_4)
                os.write(CMD_FEED_4)
                os.write(CMD_FEED_4)
                os.write(CMD_CUT)
                os.flush()
                log("Kitchen order printed: ${order.order.orderNumber}")
                true
            } catch (e: IOException) {
                _isConnected.value = false
                log("Kitchen print failed: ${e.message}", false)
                onError("Print failed: ${e.message}")
                false
            }
        }
    }

    // Format a 3-column line for 32-char width (80mm paper).
    // If col1 (item name) is too long, it wraps onto preceding lines and
    // qty+amount appear on the last line aligned to the right.
    private fun formatLine(col1: String, col2: String, col3: String, maxWidth: Int = 32): String {
        val col2Width = if (maxWidth >= 48) 6 else 4
        val col3Width = if (maxWidth >= 48) 14 else 9
        val col1Width = maxWidth - col2Width - col3Width

        if (col1.length <= col1Width) {
            // Fits on one line
            val c1 = col1.padEnd(col1Width)
            val c2 = col2.padStart(col2Width)
            val c3 = col3.padStart(col3Width)
            return "$c1$c2$c3\n"
        }

        // Wrap: chunk col1 into col1Width-sized segments
        val chunks = col1.chunked(col1Width)
        val sb = StringBuilder()
        // All chunks except last — print as full-width name continuation lines
        chunks.dropLast(1).forEach { chunk ->
            sb.append(chunk.padEnd(maxWidth)).append("\n")
        }
        // Last chunk + qty + amount on the same line
        val lastChunk = chunks.last().padEnd(col1Width)
        val c2 = col2.padStart(col2Width)
        val c3 = col3.padStart(col3Width)
        sb.append("$lastChunk$c2$c3\n")
        return sb.toString()
    }

    // Format a 2-column line for kitchen order (Item + Qty aligned).
    // Long item names wrap; qty appears on the last line.
    private fun formatKitchenLine(col1: String, col2: String, maxWidth: Int = 32): String {
        val col2Width = if (maxWidth >= 48) 7 else 5
        val col1Width = maxWidth - col2Width

        if (col1.length <= col1Width) {
            val c1 = col1.padEnd(col1Width)
            val c2 = col2.padStart(col2Width)
            return "$c1$c2\n"
        }

        val chunks = col1.chunked(col1Width)
        val sb = StringBuilder()
        chunks.dropLast(1).forEach { chunk ->
            sb.append(chunk.padEnd(maxWidth)).append("\n")
        }
        val lastChunk = chunks.last().padEnd(col1Width)
        val c2 = col2.padStart(col2Width)
        sb.append("$lastChunk$c2\n")
        return sb.toString()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TEST PRINT
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun printTestPage(onError: (String) -> Unit = {}) {
        val os = outputStream
        if (os == null || !_isConnected.value) {
            onError("Printer not connected")
            return
        }

        withContext(Dispatchers.IO) {
            try {
                os.write(CMD_INIT)
                os.write(CMD_ALIGN_CENTER)
                os.write(CMD_BOLD_ON)
                os.write(CMD_DOUBLE_SIZE)
                os.write("MAKULU\n".toByteArray())
                os.write(CMD_NORMAL_SIZE)
                os.write(CMD_BOLD_OFF)
                os.write("Printer Test Page\n".toByteArray())
                os.write(CMD_LINE)
                os.write(CMD_ALIGN_LEFT)
                os.write("If you can read this, your\n".toByteArray())
                os.write("printer is working correctly!\n".toByteArray())
                os.write(CMD_LINE)
                os.write(CMD_BOLD_ON)
                os.write(formatLine("Item", "Qty", "Amount").toByteArray())
                os.write(CMD_BOLD_OFF)
                os.write(CMD_LINE)
                os.write(formatLine("Test Item 1", "x2", "₹100.00").toByteArray())
                os.write(formatLine("Test Item 2", "x1", "₹50.00").toByteArray())
                os.write(CMD_LINE)
                os.write(CMD_BOLD_ON)
                os.write("Total: ₹150.00\n".toByteArray())
                os.write(CMD_BOLD_OFF)
                os.write(CMD_LINE)
                os.write(CMD_ALIGN_CENTER)
                os.write("Connection: OK ✓\n".toByteArray())
                os.write("Paper: OK ✓\n".toByteArray())
                os.write(CMD_FEED_3)
                os.write(CMD_CUT)
                os.flush()
            } catch (e: IOException) {
                _isConnected.value = false
                onError("Test print failed: ${e.message}")
            }
        }
    }
}
