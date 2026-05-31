makulu app

\# 🦁 Makulu — Eatery Management App

\> \*\*Sidenote:\*\* This project is primarily for \*\*learning purposes\*\* — specifically to explore prompt engineering and how effectively requirements can be communicated to an LLM. However, the \*\*ultimate goal is to build a fully functional, installable app\*\* for real eatery use.

\> \*\*App Name Origin:\*\* \*Makulu\* — named after the famous lion from the Mapogo Coalition, known for the longest longevity among lions. The name reflects the ambition: \*\*build an eatery app that lasts\*\*.

\---

\## 📌 Project Status

\`\[x\] Requirements Gathered\` → \`\[x\] Tech Decision Confirmed\` → \`\[x\] UI/UX Finalized\` → \`\[x\] Development\` → \`\[ \] Build Ready\` → \`\[ \] APK/Deploy\`

\> ⚠️ \*\*RULE: No coding until every screen/behaviour is discussed and confirmed one-by-one.\*\*

\> ✅ \*\*All screens discussed and confirmed. Ready for development.\*\*

\> 🔁 \*\*Previous partial code to be scrapped. Full rewrite from scratch.\*\*

\---

\## 🧭 How We Work

\- All discussions, decisions, and progress are tracked in \*\*this file only\*\*.

\- Code is developed here; user copies files to a build laptop for compilation.

\- When requested, a \*\*"Files to Copy"\*\* list will be provided clearly.

\- Reference UI: https://sabtechnologies.xyz/maddy/dashboard/order.php (login: sabari / sabari)

\---

\## 📋 App Requirements (Revised v2)

\### App Name

\*\*Makulu\*\*

\### Purpose

A unified eatery management app for a small restaurant/eatery. Single operator use.

\### Core Functionalities (Must Work Perfectly)

1. Take orders

2. Print receipt (Bluetooth PosBox 3" thermal)

3. Save ledger (all transactions)

4. Add/remove tables

5. Add/modify/remove menu items + modify price

\### Supplementary Functionalities

6. Shop spending tracker

7. Sales analysis (item-wise breakdown)

8. CSV backup to Android folder + Documents folder

\---

\## 🔐 Security & Login

\### App Login

\- \*\*No username/password\*\* — uses Android device biometric/screen lock (fingerprint, PIN, pattern — whatever the user has set up on their phone)

\- App opens only after device-level authentication passes

\### Admin Panel Security

\- Accessed via \*\*hamburger menu sidebar\*\*

\- Protected by a \*\*separate 4-digit PIN\*\* (set on first-time setup)

\- \*\*One security question\*\* for PIN recovery:

  \- 5 standard questions (user picks one):

    1. What is your mother's maiden name?

    2. What is your best friend's name?

    3. What is your favorite sport?

    4. What city were you born in?

    5. What is your pet's name?

  \- Answer input: \*\*alphabets only, lowercase only\*\*

\---

\## 🏠 Homepage / Order Page

\### Top Bar

\- \*\*Makulu logo/heading\*\* at the top

\- Clicking it from \*\*any screen\*\* → navigates back to Homepage

\- Acts as universal "home" button

\### Table Selection (Required Before Ordering)

\- Below the top bar: \*\*table selector\*\* (T1, T2, T3, etc.)

\- \*\*Must select a table\*\* before proceeding to order — no table = no order allowed

\- Table names: 3-digit alphanumeric, no special characters (e.g., T01, T02, VIP)

\- Tables with \*\*draft orders\*\* should be visually distinct (e.g., highlighted) — clicking them reopens the draft

\### Menu Display

\- Shows \*\*category headings\*\* (e.g., Chicken Roll, Veg Pasta, Momos, etc.)

\- Clicking a category → expands/navigates into \*\*submenu items\*\* within that category

\- Each item has \*\*\[+\] \[\-\]\*\* buttons to add/remove quantity

\### Review Order (Bottom Bar)

\- \*\*"Review Order"\*\* button pinned at the bottom of the screen

\- Clicking it → \*\*swipes up to top\*\* showing all selected items with:

  \- Item name

  \- Quantity

  \- Respective price

\- \*\*Can remove items\*\* directly from the Review Order view

\- \*\*Four action buttons\*\* at the bottom of Review Order:

  1. \*\*Save Draft\*\* — saves current items to this table, user can come back later

  2. \*\*Place Order\*\* — confirms the order (order is placed, items locked in)

  3. \*\*Complete\*\* — marks order done, table becomes free, order goes to ledger + CSV

  4. \*\*Print Receipt\*\* — prints itemized receipt on PosBox 3" Bluetooth printer

\### Order Lifecycle & Adding Items After Placing

\- \*\*Order statuses:\*\* Draft → Placed → Complete

\- \*\*Save Draft\*\* = items saved locally, just a reminder for user. Can be cleared with "Clear" button to reset table.

\- \*\*Place Order\*\* = items confirmed/sent to kitchen. This is the real order moment.

\- \*\*Complete\*\* = customer paid, table freed, order goes to ledger + CSV.

\- \*\*Print Receipt\*\* = prints the FINAL item list only (no intermediate changes shown on receipt).

\### Print Types (Two Different Prints)

1. \*\*Print Kitchen Order\*\* — prints ONLY item names + quantity (no prices, no total). For kitchen use. \*\*User manually triggers\*\* — no auto-print on Place/Update Order.

2. \*\*Print Receipt\*\* — prints full receipt with header, items, prices, total, etc. For customer.

\*\*UI: Split Button ✅ Confirmed\*\*

\`\`\`

┌─────────────────────┬───┐

│   Print Receipt     │ ▾ │

└─────────────────────┴───┘

         dropdown:

  ┌────────────────────────┐

  │ 🧾 Print Receipt       │

  │ 🍳 Print Kitchen Order │

  └────────────────────────┘

\`\`\`

\- Main button = "Print Receipt" (most common)

\- Dropdown = "Print Kitchen Order"

\- Both are \*\*purely manual\*\* — user decides when to print either

\### Modifying a Placed Order (Add/Remove Items After Place Order)

\- After "Place Order", user can tap the table again and:

  \- \*\*Remove items\*\* from already ordered list

  \- \*\*Add new items\*\*

\- \*\*Display logic in Review Order after modifications:\*\*

  1. Show \*\*original placed order list\*\* (with updated values if qty changed)

  2. If an item is \*\*completely removed\*\* → show it with ~~strikethrough~~

  3. If an item \*\*qty is reduced\*\* → show updated count, then a "Changes" sub-heading showing "Removed: \\<item\\\>"

  4. If \*\*new items added\*\* → show under "Additions" sub-heading with new items

  5. If \*\*both changes and additions\*\* → show both sub-headings

\- \*\*Print Receipt\*\* always prints the \*\*final consolidated list\*\* — no intermediate change history on paper

\### Button Visibility (Contextual)

\- All 4 buttons appear \*\*contextually\*\* based on order state:

  \- \*\*No items selected:\*\* No buttons visible (or greyed out)

  \- \*\*Items selected, no order placed yet:\*\* Show \`Save Draft\` + \`Place Order\` + \`Clear\`

  \- \*\*After Place Order:\*\* Show \`Update Order\` + \`Complete\` + \`Print Receipt\` + \`Clear\` (Clear shows cancel confirmation popup)

  \- \`Clear\` before Place Order → resets table silently

  \- \`Clear\` after Place Order → popup: "Cancel order? This won't go to ledger." → if confirmed, order erased completely

\### Change History vs Final State

\- Strikethrough + Changes/Additions sub-headings → shown \*\*only in Review Order screen\*\* (live editing view)

\- \*\*Ledger, Order History, Receipt\*\* → all show \*\*final state only\*\*, no audit trail

\### Multi-Table Parallel Orders ✅ Confirmed

\- Multiple tables can have active orders simultaneously (T01 placed, T02 draft, T03 selecting items)

\- User switches between tables freely

\- \*\*Auto-draft:\*\* If user is selecting items for a table and switches to another table WITHOUT pressing Save Draft, the items are \*\*automatically saved as draft\*\* for that table

\- No data loss on table switch

\---

\## ☰ Admin Panel (Hamburger Sidebar)

\### Access

\- Hamburger icon (☰) → opens sidebar

\- Tap "Admin" → prompts for \*\*4-digit PIN\*\*

\- On success → enters Admin section

\### Admin Sub-sections

\#### 1. Tables Management

\- Add / Remove / Rename tables

\- Table name: \*\*up to 3 characters, alphanumeric, no special characters\*\* (e.g., T1, T01, VIP, 1, B1)

\- \*\*Default tables on fresh install:\*\* T01, T02, T03, T04, T05

\- \*\*Max tables:\*\* ~10; shown as \*\*horizontal scroll row\*\* on homepage

\- \*\*Table order on homepage\*\* = same order as in Admin tables list; admin can \*\*drag to reorder\*\* in admin page → reflects on homepage

\- \*\*Deleting a table with active order:\*\* Blocked with warning — \*"Table has active order, complete it first"\*

\- \*\*Renaming a table:\*\* Allowed, but shows warning — \*"Old orders in ledger will still show the previous table name"\*; ledger names are NOT retroactively updated

\#### 2. Menu Items Management

\- Add / modify / remove items

\- \*\*Item name:\*\* Long names supported (no strict character limit)

\- \*\*Price:\*\* Whole numbers or decimals (e.g., ₹50, ₹49.50) — always shown with ₹ symbol

\- \*\*No emoji/image\*\* — text only

\- \*\*Availability checkbox:\*\* Unchecked = hidden on order page immediately; re-checking = visible immediately

\- \*\*Deleted items:\*\* Permanently removed, not visible anywhere

\- \*\*To move item between categories:\*\* Delete from one, re-add under new category

\- \*\*Item reordering:\*\* Admin can drag to reorder items within a category → reflects on order page

\- \*\*Category reordering:\*\* Admin can drag to reorder categories → reflects on order page

\- \*\*Reordering between categories\*\* (moving items across categories via drag): Not needed

\- \*\*Deleting a category with items:\*\* Blocked — \*"Remove or move all items first"\*

\- \*\*Fresh install:\*\* Empty menu — admin builds from scratch

\- \*\*No default/seed data for menu\*\*

\#### 3. Ledger

\- \*\*4 tabs:\*\* Order History | Today | This Week (Mon–Sun) | This Month (1st–end)

\- \*\*All tabs:\*\* Summary at top (Total Orders count + Total Revenue ₹) + order list below

\- \*\*Order History tab:\*\*

  \- Default view: \*\*today's orders only\*\*

  \- Each row: Table number | Time | Date | No. of items | Total amount

  \- Can load up to \*\*last 30 days\*\* (no lag concern with Room + pagination)

  \- \*\*No date range filter\*\* (keep it simple)

\- \*\*Today / Week / Month tabs:\*\* Same list format, filtered to respective period

\- \*\*Clicking an order:\*\* Opens detail view — all items (name, qty, price), order total, table, date/time

\- \*\*Deleting an order:\*\* Allowed from ledger; total revenue recalculates immediately

\- \*\*Payment mode:\*\* Always assumed \*\*Cash\*\* — not tracked, not shown

\- \*\*Total = Revenue only\*\* (order totals); no profit calculation in ledger

\#### 4. Shop Spending

\- Add spends: \*\*Item name (free-form) + Amount (₹, whole or decimal)\*\*

\- \*\*Date:\*\* Optional date picker (calendar) — if not selected, defaults to today's date

\- \*\*Edit:\*\* Inline editing (tap entry to edit in place)

\- \*\*Delete:\*\* Allowed; totals recalculate

\- \*\*View:\*\* Same 4-tab structure as Ledger (History | Today | Week | Month)

\- Summary at top: Total spent for the selected period

\- \*\*Revenue vs Spending side-by-side\*\* → shown in \*\*Analysis page\*\* (not here)

\#### 5. Data Backup (CSV)

\- \*\*File structure:\*\* Separate files per data type + one consolidated file per month

  \- \`makulu\_orders\_2026-05.csv\`

  \- \`makulu\_spending\_2026-05.csv\`

  \- \`makulu\_menu\_items\_2026-05.csv\`

  \- \`makulu\_consolidated\_2026-05.csv\`

\- \*\*Monthly files\*\* — new set created each month (no single year-long file)

\- \*\*File naming:\*\* Both dated archive (\`makulu\_orders\_2026-05.csv\`) + always-updated latest file (\`makulu\_orders\_latest.csv\`)

\- \*\*Auto-save trigger:\*\* Every time an order is completed (not midnight, not manual only)

\- \*\*CSV is supplementary\*\* — Room DB is the primary source of truth; app functions fully even if CSV files are deleted. Ledger/analysis reads from Room DB, not CSV.

\- \*\*Two save locations\*\* (identical files):

  1. App internal folder

  2. Documents folder

\- \*\*Folder access health check:\*\* App periodically checks if both folders are accessible and writable. If not → shows warning to user to grant folder access permission.

\- \*\*Manual export button:\*\* Inside Admin → CSV Backup section only (requires admin PIN)

\#### 6. Analysis Tab

\- \*\*Period filter:\*\* Today | This Week | This Month (same as ledger/spending)

\- \*\*Revenue vs Spending summary\*\* (top of page, same filter applies):

  \- Revenue: ₹X | Spending: ₹Y | Net: ₹Z (simple 3-number row)

\- \*\*Items sold — 3 view tabs:\*\*

  1. \*\*Bar Chart\*\* — each item = one bar, height = qty sold

  2. \*\*Pie Chart\*\* — each item = slice of total sold

  3. \*\*Ranked List\*\* — text rows, item name + qty sold, ascending/descending toggle

\- \*\*Items shown:\*\* All menu items (0 qty for items not sold in period)

\- \*\*Default:\*\* Top 20 items; "Show All" button to expand full list

\#### 7. Receipt / Printer Heading Settings

\- Admin can \*\*add, remove, reorder\*\* any header fields freely — nothing is mandatory

\- \*\*Live preview\*\* in the settings tab showing how the receipt header will look

\- Common fields (all optional, all toggleable): Shop Name, Address, Phone, GSTN — user can add custom fields too

\- \*\*Footer:\*\* "Thank you, visit again!" — optional, toggleable; admin can edit the text

\- \*\*Receipt body fields\*\* — each toggleable on/off:

  \- Order Number

  \- Date/Time

  \- Table Number

  \- Item list + prices

  \- Grand Total

\- If all header fields disabled → receipt just prints order items + total, no header

\- Settings stored in Room DB; \*\*backed up in CSV\*\*

\#### Print Formats

\- \*\*Print Receipt\*\* (customer bill): Configurable header + body fields (as above)

\- \*\*Print Kitchen Order:\*\* Table number + Order number at top, then items + quantity only (no prices, no header branding)

\---

\## 🖨️ Printer Details

\### Hardware

\- \*\*Model:\*\* PosBox 3 Inch Label + Receipt Printer (USB + Bluetooth)

\- \*\*Print Type:\*\* Direct Thermal (no ink/ribbon needed)

\- \*\*Paper Width:\*\* 80mm (3 inch)

\- \*\*Resolution:\*\* 203 DPI

\- \*\*Speed:\*\* 90 mm/sec

\- \*\*Battery:\*\* 2600mAh Li-Ion (portable, battery operated)

\- \*\*Connectivity:\*\* Bluetooth Classic + USB

\- \*\*Compatible OS:\*\* Android, Windows, iOS, Mac, Linux

\### Bluetooth Technical Details

\- \*\*Protocol:\*\* Bluetooth Classic — SPP (Serial Port Profile)

\- \*\*SPP UUID:\*\* \`00001101-0000-1000-8000-00805F9B34FB\`

\- \*\*Print Command Standard:\*\* ESC/POS (industry standard — no special SDK needed)

\- \*\*Android API used:\*\* \`android.bluetooth.BluetoothAdapter\` + \`BluetoothSocket\`

\- \*\*Pairing:\*\* User pairs printer manually via Android Bluetooth Settings first; app discovers from paired devices list

\### Printer Connection UX

\- \*\*First-time setup:\*\* After admin PIN setup → Printer Setup screen → shows list of paired BT devices → user selects PosBox → connect → \*"Connected ✅"\*; \[Skip for now\] option available

\- \*\*Connection status:\*\* Small dot in top bar always visible — 🟢 connected / 🔴 not connected

\- \*\*Auto-reconnect:\*\* On every app open, if BT is on and printer was previously paired, app reconnects silently

\- \*\*BT off / not connected:\*\* Non-blocking banner: \*"🖨️ Printer not connected — tap to connect"\*; app still works fully

\- \*\*Print attempted when disconnected:\*\* Bottom sheet shows \*"Printer not connected. Turn on Bluetooth and connect."\* + "Connect Now" button

\- \*\*Admin → Printer Settings section:\*\*

  \- View current paired printer name

  \- Re-scan / reconnect

  \- Forget printer (reset connection)

\- \*\*One printer at a time\*\* — no multi-printer support

\### ESC/POS Commands Needed (for code)

| Function | Command |

|---|---|

| Initialize printer | \`ESC @\` (0x1B 0x40) |

| Center align | \`ESC a 1\` |

| Left align | \`ESC a 0\` |

| Bold ON | \`ESC E 1\` |

| Bold OFF | \`ESC E 0\` |

| Double height/width | \`GS !\` |

| Print + feed line | \`LF\` (0x0A) |

| Cut paper | \`GS V 66 0\` |

| Set char size normal | \`GS ! 0\` |

\### Print Formats

\- \*\*Print Receipt\*\* (customer bill): Configurable header → body fields → footer → cut

\- \*\*Print Kitchen Order:\*\* Table No + Order No → items + qty only → cut (no prices, no header branding)

\---

\## 📌 Receipt Content

\- \*\*Header\*\* (configurable from Admin → Printer Heading Settings):

  \- Eatery name, Address, Phone number, GSTN (each toggleable)

\- \*\*Body:\*\*

  \- Order number

  \- Date/time

  \- Table number

  \- Itemized list: item name + quantity + price

  \- Grand total

\- \*(Footer text — to be decided)\*

\---

\## ☰ Hamburger Sidebar

\### Structure

\`\`\`

☰ Sidebar

─────────────────

📋 Today's Orders     ← no PIN (view only, tap to see detail)

─────────────────

💰 Shop Spending      ← no PIN (add / edit inline / delete last 10 entries)

─────────────────

🔒 Admin              ← 4-digit PIN required

    └── Tables

    └── Menu Items

    └── Ledger

    └── Analysis

    └── CSV Backup

    └── Receipt Settings

    └── Printer Settings

─────────────────

ℹ️  About / Version

\`\`\`

\### Today's Orders (no PIN)

\- Shows today's completed orders

\- Each row: Table | Time | Item count | Total

\- Tap any order → full detail view (items, prices, total)

\- View only — no edit/delete from here

\### Shop Spending (no PIN)

\- Shows \*\*last 10 entries\*\* (item name + amount + date)

\- Summary at top: \*Today's spending: ₹X\*

\- \`+ Add Spend\` button — Item name + Amount + optional date picker

\- Tap entry → \*\*edit inline\*\*

\- \*\*Delete allowed without PIN\*\* — only entries visible in this view (last 10) can be deleted

\- Full breakdown (History/Today/Week/Month tabs + revenue vs spending comparison) → inside Admin

\### Admin (4-digit PIN)

\- PIN timeout: \*\*30 seconds of inactivity\*\* → re-prompt

\- Once PIN entered, stays unlocked until timeout or app close

\- Sub-sections: Tables | Menu Items | Ledger | Analysis | CSV Backup | Receipt Settings | Printer Settings

\### First-Time Setup Flow (Fresh Install)

1. App opens → \*\*Biometric/device lock authentication\*\*

2. After biometric → \*\*Admin PIN setup screen\*\* (mandatory):

   \- Enter 4-digit PIN → Confirm PIN

   \- Choose 1 security question from 5 → type answer (alphabets only, lowercase only)

3. → \*\*Printer Setup screen:\*\*

   \- Shows paired Bluetooth devices list

   \- Tap PosBox → \*"Connected ✅"\*

   \- Or tap \*"Skip for now"\*

4. → Lands on \*\*Homepage (Order Page)\*\*

\---

\## ⚖️ Tech Decision: Web App vs Native Android (Kotlin)

\### 🔵 Option A — Native Android App (Kotlin + Jetpack Compose)

| Pro | Con |

|---|---|

| Full Bluetooth printer control (Android BT APIs) | Requires Android Studio to build (.apk) |

| Works fully offline | Steeper learning curve |

| Best performance, native UX | More code to write upfront |

| No server/hosting dependency | Can't test in browser |

| One-time install, no browser needed | |

\### 🟢 Option B — Progressive Web App (PWA) / Web App

| Pro | Con |

|---|---|

| Test instantly in any browser | Bluetooth printing is severely limited in browsers (Web Bluetooth API — experimental, unreliable on Android Chrome) |

| Easier to iterate UI fast | Needs a server or local hosting |

| Can be "installed" via browser shortcut | Offline support needs extra work (Service Workers) |

| No build tools needed | Not a "real" APK |

\### 🔴 Option C — Hybrid (Recommended Path) ✅

\> \*\*Develop as a Web App first → migrate/wrap into a native Android app (Kotlin/WebView or full rewrite) when ready.\*\*

| Phase | What |

|---|---|

| Phase 1 | Build as a local web app (HTML + JS + local storage or SQLite via backend) |

| Phase 2 | Test all UI/UX and business logic in browser |

| Phase 3 | Wrap in Android WebView app (Kotlin shell) — gives you an APK fast |

| Phase 4 (optional) | Full native Kotlin rewrite for Bluetooth printing & performance |

\### 📌 My Recommendation

\*\*Start with a Kotlin Android app directly\*\* — here's why:

\- Your printer is \*\*Bluetooth-only\*\* → Web Bluetooth on Android Chrome is unreliable. Native Kotlin handles this cleanly via \`android.bluetooth\` APIs.

\- The app is \*\*offline-first\*\* by nature (eatery doesn't need internet).

\- Jetpack Compose makes UI fast to build — not as complex as it used to be.

\- You already want an APK → building a web app first adds an extra migration step with no real gain.

\*\*Verdict: Go native Kotlin from day one. Build here, compile on the other laptop.\*\*

\---

\## 🛠️ Tech Stack (Proposed — Pending Confirmation)

| Layer | Technology |

|---|---|

| Language | Kotlin |

| UI Framework | Jetpack Compose (Material 3) |

| Local Database | Room (SQLite wrapper) |

| Bluetooth Printing | Android Bluetooth Classic API |

| Architecture | MVVM (clean, maintainable) |

| Build Tool | Gradle |

| Min SDK | API 31 (Android 12) |

| Target SDK | API 35 (Android 15) |

\---

\## 📁 Planned File Structure (Rewrite — Minimal Files)

\> ⚠️ All previously created code files are \*\*scrapped\*\*. Full rewrite below.

\> 🎯 Goal: ≤10 Kotlin files to copy to build laptop.

\`\`\`

MakuluApp/

├── settings.gradle.kts                  (keep — no change)

├── build.gradle.kts                     (keep — no change)

├── gradle/libs.versions.toml            (keep — update deps)

└── app/

    ├── build.gradle.kts                 (keep — minor updates)

    └── src/main/

        ├── AndroidManifest.xml          (update permissions)

        └── java/com/makulu/app/

            │

            ├── 1. Database.kt           ← ALL Room entities + DAOs + AppDatabase in ONE file

            │                               (MenuItem, Order, OrderItem, DraftOrder, ShopSpend,

            │                                AppSettings, MenuItemDao, OrderDao, SpendDao,

            │                                SettingsDao, AppDatabase)

            │

            ├── 2. Repository.kt         ← ALL repositories + ALL ViewModels in ONE file

            │                               (MenuRepository, OrderRepository, SpendRepository,

            │                                SettingsRepository, OrderViewModel, AdminViewModel,

            │                                LedgerViewModel, AnalysisViewModel)

            │

            ├── 3. OrderScreen.kt        ← Homepage: biometric gate + table selector +

            │                               category/item list + Review Order sheet +

            │                               all order buttons (Draft/Place/Update/Complete/Print)

            │

            ├── 4. AdminScreen.kt        ← PIN setup/entry + all admin sections:

            │                               Tables, Menu Items, Ledger, Shop Spending,

            │                               Analysis, CSV Backup, Receipt Settings, Printer Settings

            │

            ├── 5. PrinterManager.kt     ← Bluetooth connection + ESC/POS commands +

            │                               auto-reconnect + receipt/kitchen order formatting

            │

            ├── 6. CsvManager.kt         ← CSV read/write, auto-save on order complete,

            │                               manual export, dual folder save, health check

            │

            ├── 7. Theme.kt              ← Color palette + Typography + Material3 Theme

            │

            └── 8. MainActivity.kt       ← App entry point + first-time setup flow +

                                            NavHost + hamburger sidebar + biometric auth

\`\`\`

\*\*Total: 8 Kotlin files + 4 Gradle/config files = 12 files to copy\*\*

\*(Gradle files rarely change after setup — effectively 8 new files per build)\*

\---

\## 📄 Created Files — Detail

\### Build/Config Files

| File | Purpose | Status |

|---|---|---|

| \`settings.gradle.kts\` | Root Gradle settings, declares \`:app\` module | ✅ |

| \`build.gradle.kts\` (root) | Plugins: AGP 8.5.2, Kotlin 2.0.0, KSP, Hilt, Compose compiler | ✅ |

| \`gradle/libs.versions.toml\` | Version catalog: AGP 8.5.2, Kotlin 2.0.0, Compose BOM 2024.06.00, Room 2.6.1, Hilt 2.51.1, Navigation 2.7.7, Biometric 1.1.0, Vico 2.0.0-alpha.28 | ✅ |

| \`app/build.gradle.kts\` | App module config: namespace \`com.makulu.app\`, minSdk 31, targetSdk 35, all deps | ✅ |

| \`AndroidManifest.xml\` | BLUETOOTH\_CONNECT, BLUETOOTH\_SCAN, USE\_BIOMETRIC, storage permissions | ✅ |

| \`app/src/main/res/values/themes.xml\` | Base theme (Material3 DayNight) | ✅ |

| \`app/proguard-rules.pro\` | ProGuard config | ✅ |

\### Kotlin Source Files (\`app/src/main/java/com/makulu/app/\`)

| File | Purpose | Status |

|---|---|---|

| \`Database.kt\` | Entities (Category, MenuItem, TableInfo, Order, OrderItem, ShopSpend, AppSettings, ReceiptField), DAOs, Room DB, TypeConverters | ✅ |

| \`Repository.kt\` | Hilt DI module, all Repositories (Menu, Table, Order, Spend, Settings), all ViewModels (Order, Admin, Ledger, Spending, Analysis) | ✅ |

| \`Theme.kt\` | Warm earthy color palette, light/dark schemes, MakuluTypography, table state colors | ✅ |

| \`PrinterManager.kt\` | BT Classic SPP connection, ESC/POS commands, auto-reconnect, printReceipt, printKitchenOrder | ✅ |

| \`CsvManager.kt\` | CSV export (orders, spending, menu, consolidated), monthly + latest files, dual folder save, health check | ✅ |

| \`OrderScreen.kt\` | TableSelector, CategoryHeader, MenuItemRow, ReviewOrderContent (bottom sheet), contextual buttons, cancel dialog | ✅ |

| \`AdminScreen.kt\` | AdminPinScreen, 8 admin tabs (Tables, Menu, Ledger, Spending, Analysis, CSV, Receipt, Printer), ShopSpendingSidebar, TodayOrdersSidebar | ✅ |

| \`MainActivity.kt\` | MakuluApplication, biometric gate, first-time setup flow (PIN + printer), NavHost, hamburger drawer, printer status dot | ✅ |

\---

\## 🔍 Reference App Analysis (from \`console\_html\_files/\`)

\### Order Page

\- Items shown \*\*by category sections\*\*; each item row: \`\[Name\] \[- qty +\]\` (no price shown, qty starts at 0)

\- Category scroll buttons at top to jump to section

\- Live search filters items in real-time

\- Sticky \*\*"Preview Order"\*\* button at bottom

\- Preview modal → order summary + \*\*table selector (T1–T6)\*\* → "Confirm Order"

\- Confirm → AJAX POST → redirects to order details page

\- Then \*\*"Print Order?"\*\* modal → "Yes, Print" / "No"

\- Categories (17 found): Chicken Roll/Wrap, Paneer Roll/Wrap, Veg/Paneer/Chicken Pasta, Veg/Chicken Maggie, Veg/Chicken Momos, Momos Plater, Vanilla/Red Velvet/Premium Waffle, Waffle Cakes, Takeaway (Parcel), Add On (Cheese)

\### Table Page

\- Card with "Add New" button → modal: single input \*\*Table Name\*\*

\- Table cols: S.No | Table Name (inline editable) | Action (Delete)

\- Current tables: T1–T6

\- Inline edit on blur → AJAX update

\### Report Page

\- \*\*Date range filter\*\*: From Date + To Date + Filter button

\- Table cols: S.No | Order ID | Order Date | Total | View Products | Action (Delete)

\- "View Products" modal → Product Name | Price | Quantity | Total (AJAX)

\- \*\*Total Earnings: ₹0.00\*\* shown below table

\### Category Page

\- 35 total categories (paginated, 10/page)

\- Add New → modal: single input \*\*Category Name\*\*

\- Table cols: S.No | Category Name (inline editable) | Action (Delete)

\- Sample categories: French Fries, Nuggets, Cheese Balls, Milkshake, Icecream, Desserts, Mojito, Veg Pizza, Chicken Pizza

\### Items Page

\- ⚠️ \*\*Not yet fully analyzed\*\* — form fields (name, price, category, availability toggle) to be confirmed

\---

\## 🗂️ Development Log

\### Session 1 — May 27, 2026

\- Project initialized. \`project\_development.md\` created.

\### Session 2 — May 27, 2026

\- App name confirmed: \*\*Makulu\*\*

\- Full requirements captured (ordering, printing, admin, ledger)

\- Printer identified: PosBox 3" Bluetooth thermal printer

\- Tech confirmed: Native Kotlin + Jetpack Compose. Min SDK: Android 12 (API 31).

\### Session 3 — May 27, 2026

\- Partial code written (Gradle, models, DAOs, ViewModels) — \*\*scrapped, to be rewritten\*\*

\- Reference app HTML analyzed (5 pages)

\### Session 4 — May 27, 2026

\- \*\*Full UI/UX specification confirmed\*\* across all screens

\- All Q&A documented and locked in (login, order flow, admin, printer, CSV, analysis)

\- Printer technical specs confirmed: ESC/POS over Bluetooth Classic SPP

\- Decided: ≤8 Kotlin files (minimal file count for easy copy to build laptop)

\- \*\*Status: Ready for development\*\*

\### Session 5 — May 27, 2026

\- \*\*Full development completed in one session\*\*

\- All 8 Kotlin files created from scratch

\- All Gradle/config files created

\- Old partial code deleted, full rewrite done

\- \*\*Status: Ready for build & test\*\*

\---

\## ✅ Decision Log

| # | Decision | Reason | Status |

|---|---|---|---|

| 1 | App Name: Makulu | Named after Mapogo lion for longevity | ✅ |

| 2 | Native Kotlin (Jetpack Compose) | Full Bluetooth support, offline-first, real APK | ✅ |

| 3 | Min SDK: API 31 (Android 12+) | Pixel 7, any Android 12+ device | ✅ |

| 4 | Architecture: MVVM | Clean separation | ✅ |

| 5 | DI: Hilt | Industry standard | ✅ |

| 6 | Biometric login | No username/password — device lock | ✅ |

| 7 | Admin PIN: 4-digit + security question | Separate security from device login | ✅ |

| 8 | PIN timeout: 30 seconds inactivity | Auto re-lock admin | ✅ |

| 9 | Order states: Draft → Placed → Complete | Clear flow, no data loss | ✅ |

| 10 | Auto-draft on table switch | Prevent accidental item loss | ✅ |

| 11 | Split print button | Receipt + Kitchen Order from one button | ✅ |

| 12 | ESC/POS over BT Classic SPP | Standard protocol, no SDK needed | ✅ |

| 13 | CSV: monthly files + latest + dual folder | Organised + backup redundancy | ✅ |

| 14 | Room DB = primary source of truth | App works even if CSV deleted | ✅ |

| 15 | ≤8 Kotlin files | Easy to copy to build laptop | ✅ |

| 16 | Scrap old code, rewrite from scratch | Design changed significantly | ✅ |

\---

\## ✅ Completed Tasks

1. ~~\`Database.kt\` — all entities + DAOs + AppDatabase~~ ✅

2. ~~\`Repository.kt\` — all repositories + all ViewModels~~ ✅

3. ~~\`Theme.kt\` — colors + typography + Material3 theme~~ ✅

4. ~~\`PrinterManager.kt\` — BT Classic SPP + ESC/POS + auto-reconnect~~ ✅

5. ~~\`CsvManager.kt\` — CSV read/write + dual folder + health check~~ ✅

6. ~~\`OrderScreen.kt\` — full order taking flow~~ ✅

7. ~~\`AdminScreen.kt\` — all admin sections~~ ✅

8. ~~\`MainActivity.kt\` — entry point + first-time setup + nav + biometric~~ ✅

9. ~~Update \`AndroidManifest.xml\` — new permissions (biometric, BT, storage)~~ ✅

10. ~~Update \`libs.versions.toml\` + \`app/build.gradle.kts\` — new deps (biometric, charts)~~ ✅

\## ⏳ Pending Tasks

1. Copy files to build laptop and compile

2. Test on physical device (Pixel 7 or similar)

3. Connect PosBox printer and test printing

4. Fix any compilation issues

5. Build signed APK for production use

\---

\## 🐛 Issues & Fixes

| # | Issue | Fix | Status |

|---|---|---|---|

| — | — | — | — |

\---

\## 📦 Build Instructions

\### Files to Copy to Build Laptop

Copy the entire \`MakuluApp/\` folder. Total: \*\*15 files\*\*

\`\`\`

MakuluApp/

├── settings.gradle.kts

├── build.gradle.kts

├── gradle/libs.versions.toml

└── app/

    ├── build.gradle.kts

    ├── proguard-rules.pro

    └── src/main/

        ├── AndroidManifest.xml

        ├── res/values/themes.xml

        └── java/com/makulu/app/

            ├── Database.kt

            ├── Repository.kt

            ├── Theme.kt

            ├── PrinterManager.kt

            ├── CsvManager.kt

            ├── OrderScreen.kt

            ├── AdminScreen.kt

            └── MainActivity.kt

\`\`\`

\### Build Steps

1. Open \`MakuluApp/\` folder in Android Studio (Jellyfish 2024.1+ recommended)

2. Wait for Gradle sync to complete

3. Connect Android device (USB debugging enabled, Android 12+)

4. Run → Select device → Build & Install

5. For signed APK: Build → Generate Signed Bundle/APK → APK → create keystore → release

\---

\*Last Updated: May 27, 2026 — Development Complete ✅\*