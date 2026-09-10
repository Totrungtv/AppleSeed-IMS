package vn.appleseed.ims

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Bg = Color(0xFF05070A)
private val Panel = Color(0xFF0D1218)
private val Panel2 = Color(0xFF131A22)
private val MainText = Color(0xFFF5F7FA)
private val Muted = Color(0xFF8A96A3)
private val Cyan = Color(0xFF46E6FF)
private val Blue = Color(0xFF557CFF)
private val Good = Color(0xFF42E6A4)
private val Warn = Color(0xFFFFC857)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LocalAdbEngine.init(this)
        setContent { AppleSeedApp() }
    }

    @Composable
    private fun AppleSeedApp() {
        var tab by remember { mutableIntStateOf(0) }
        var adb by remember { mutableStateOf(LocalAdbEngine.status()) }
        var evidence by remember { mutableStateOf("") }
        var lastAction by remember { mutableStateOf("READY") }
        var busy by remember { mutableStateOf(false) }
        var pairDialog by remember { mutableStateOf(false) }
        var setupDialog by remember { mutableStateOf(false) }
        var resultDialog by remember { mutableStateOf(false) }
        var resultTitle by remember { mutableStateOf("") }
        var resultMessage by remember { mutableStateOf("") }
        var resultAction by remember { mutableStateOf<(() -> Unit)?>(null) }
        var resultActionText by remember { mutableStateOf("ĐÓNG") }
        var pairCode by remember { mutableStateOf("") }
        var volte by remember { mutableStateOf(true) }
        var vowifi by remember { mutableStateOf(true) }
        var vonr by remember { mutableStateOf(true) }
        var permission by remember { mutableStateOf(checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) }
        var nearbyPermission by remember { mutableStateOf(Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED) }
        var panicImage by remember { mutableStateOf<Bitmap?>(null) }

        fun refresh() { adb = LocalAdbEngine.status() }
        fun showResult(title: String, message: String, actionText: String = "ĐÓNG", action: (() -> Unit)? = null) {
            resultTitle = title
            resultMessage = message
            resultActionText = actionText
            resultAction = action
            resultDialog = true
        }

        fun openWirelessDebugging() {
            val direct = Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS")
            runCatching { startActivity(direct) }.onFailure {
                startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            }
        }

        val phonePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            permission = it
            if (!it) showResult("CẦN QUYỀN ĐIỆN THOẠI", "Apple Seed chưa được cấp quyền cần thiết. Hãy cấp quyền rồi bấm THIẾT LẬP & KẾT NỐI THIẾT BỊ.")
        }
        val nearbyPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            nearbyPermission = it
            if (it) showResult("ĐÃ CẤP QUYỀN", "Quyền Thiết bị ở gần đã sẵn sàng. Bấm THIẾT LẬP & KẾT NỐI THIẾT BỊ để tiếp tục.")
            else showResult("CẦN QUYỀN THIẾT BỊ Ở GẦN", "Apple Seed cần quyền này để tự tìm Wireless Debugging trên điện thoại. Vào Cài đặt → Quyền ứng dụng → Thiết bị ở gần, cấp quyền rồi thử lại.")
        }
        val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap -> panicImage = bitmap }

        fun startPairing() {
            if (Build.VERSION.SDK_INT >= 33 && !nearbyPermission) {
                nearbyPermissionLauncher.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
                return
            }
            pairCode = ""
            pairDialog = true
            LocalAdbEngine.preparePairing()
        }

        fun setupDevice() {
            if (LocalAdbEngine.hasConnection()) {
                showResult("THIẾT BỊ ĐÃ KẾT NỐI", "Wireless ADB đang ONLINE. Apple Seed đã sẵn sàng.")
                return
            }
            if (!permission) {
                phonePermissionLauncher.launch(Manifest.permission.READ_PHONE_STATE)
                return
            }
            if (Build.VERSION.SDK_INT >= 33 && !nearbyPermission) {
                nearbyPermissionLauncher.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
                return
            }
            setupDialog = true
        }

        fun broker(mode: String, patch: String = "") {
            if (busy) return
            if (!LocalAdbEngine.hasConnection()) {
                showResult("CHƯA KẾT NỐI THIẾT BỊ", "Apple Seed chưa có Wireless ADB. Hãy thiết lập thiết bị trước.", "THIẾT LẬP THIẾT BỊ") { setupDevice() }
                return
            }
            busy = true
            lastAction = mode.uppercase()
            LocalAdbEngine.runBroker(mode, patch = patch) { ok, result ->
                runOnUiThread {
                    busy = false
                    evidence = result
                    refresh()
                    if (ok) showResult("THÀNH CÔNG", result.ifBlank { "Thao tác đã hoàn tất." })
                    else showResult("THAO TÁC THẤT BẠI", "${result.ifBlank { "Apple Seed không nhận được kết quả." }}\n\nKiểm tra kết nối thiết bị rồi thử lại.", "THỬ LẠI") { broker(mode, patch) }
                }
            }
        }

        fun runTool(name: String, command: String) {
            if (busy) return
            if (!LocalAdbEngine.hasConnection()) {
                showResult("CHƯA KẾT NỐI THIẾT BỊ", "Công cụ $name cần Wireless ADB. Hãy thiết lập thiết bị trước.", "THIẾT LẬP THIẾT BỊ") { setupDevice() }
                return
            }
            busy = true
            lastAction = name
            Thread {
                val result = LocalAdbEngine.shell(command)
                runOnUiThread {
                    busy = false
                    evidence = result
                    refresh()
                    if (result.startsWith("ADB SHELL ERROR") || result == "ADB OFFLINE") {
                        showResult("KHÔNG CHẠY ĐƯỢC $name", "$result\n\nHãy kết nối lại thiết bị rồi thử lại.", "THIẾT LẬP LẠI") { setupDevice() }
                    } else showResult("$name HOÀN TẤT", "Đã lấy dữ liệu từ thiết bị. Kết quả chi tiết nằm bên dưới.")
                }
            }.start()
        }

        LaunchedEffect(Unit) {
            while (true) {
                kotlinx.coroutines.delay(1000)
                refresh()
            }
        }

        if (setupDialog) {
            AlertDialog(
                onDismissRequest = { setupDialog = false },
                title = { Text("THIẾT LẬP THIẾT BỊ") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Apple Seed sẽ tự xử lý kết nối. Bạn chỉ cần thực hiện bước Android bắt buộc:", color = MainText)
                        Text("1. Bật Wireless Debugging.", color = Cyan, fontWeight = FontWeight.Bold)
                        Text("2. Chọn Pair device with pairing code.", color = MainText)
                        Text("3. Nhập mã 6 số vào Apple Seed.", color = MainText)
                        Text("Sau đó app tự Pair → tìm kết nối → ONLINE.", color = Good, fontWeight = FontWeight.Bold)
                    }
                },
                confirmButton = { Button(onClick = { setupDialog = false; openWirelessDebugging() }) { Text("MỞ WIRELESS DEBUGGING") } },
                dismissButton = { OutlinedButton(onClick = { setupDialog = false; startPairing() }) { Text("ĐÃ BẬT → TIẾP TỤC") } }
            )
        }

        if (pairDialog) {
            AlertDialog(
                onDismissRequest = { LocalAdbEngine.stopPairingDiscovery(); pairDialog = false },
                title = { Text("NHẬP MÃ 6 SỐ") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Lấy mã trong Pair device with pairing code trên điện thoại.", color = Muted)
                        OutlinedTextField(value = pairCode, onValueChange = { pairCode = it.filter(Char::isDigit).take(6) }, label = { Text("Mã ghép nối") }, singleLine = true)
                    }
                },
                confirmButton = {
                    Button(enabled = pairCode.length == 6, onClick = {
                        pairDialog = false
                        adb = "ĐANG GHÉP NỐI..."
                        LocalAdbEngine.pair(pairCode) { ok, result ->
                            runOnUiThread {
                                pairCode = ""
                                refresh()
                                if (ok) showResult("THIẾT BỊ ĐÃ KẾT NỐI", "Pair thành công và Apple Seed đã thiết lập Wireless ADB.\n\n${result.ifBlank { "Bạn có thể sử dụng các công cụ ngay." }}")
                                else showResult("KHÔNG KẾT NỐI ĐƯỢC", "$result\n\nHãy kiểm tra Wireless Debugging đang bật, mở lại Pair device with pairing code nếu cần, rồi thử lại.", "THỬ LẠI") { startPairing() }
                            }
                        }
                    }) { Text("KẾT NỐI") }
                },
                dismissButton = { OutlinedButton(onClick = { LocalAdbEngine.stopPairingDiscovery(); pairDialog = false }) { Text("HỦY") } }
            )
        }

        if (resultDialog) {
            AlertDialog(
                onDismissRequest = { resultDialog = false },
                title = { Text(resultTitle) },
                text = { Text(resultMessage, color = MainText) },
                confirmButton = {
                    Button(onClick = {
                        val action = resultAction
                        resultDialog = false
                        action?.invoke()
                    }) { Text(resultActionText) }
                },
                dismissButton = if (resultAction != null) ({ OutlinedButton(onClick = { resultDialog = false }) { Text("ĐÓNG") } }) else null
            )
        }

        val tabs = listOf("HOME", "PANIC", "IMS", "TOOLS")
        MaterialTheme {
            Scaffold(
                containerColor = Bg,
                bottomBar = {
                    NavigationBar(containerColor = Panel) {
                        tabs.forEachIndexed { index, title ->
                            NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = { Text(listOf("⌂", "◉", "⚡", "⚙")[index], fontSize = 17.sp) }, label = { Text(title, fontSize = 10.sp) })
                        }
                    }
                }
            ) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).background(Bg)) {
                    VipHeader(adb, lastAction)
                    when (tab) {
                        0 -> HomeScreen(adb, permission, nearbyPermission, { phonePermissionLauncher.launch(Manifest.permission.READ_PHONE_STATE) }, { setupDevice() }, { tab = 1 }, { tab = 2 })
                        1 -> PanicScreen(panicImage) { cameraLauncher.launch(null) }
                        2 -> ImsScreen(evidence, busy, volte, vowifi, vonr, { volte = it }, { vowifi = it }, { vonr = it }, { broker("read") }, {
                            val patch = listOf("carrier_volte_available_bool=$volte", "enhanced_4g_lte_on_by_default_bool=$volte", "editable_enhanced_4g_lte_bool=true", "hide_enhanced_4g_lte_bool=false", "carrier_volte_provisioned_bool=$volte", "carrier_volte_provisioning_required_bool=false", "carrier_wfc_ims_available_bool=$vowifi", "carrier_default_wfc_ims_enabled_bool=$vowifi", "carrier_wfc_ims_provisioned_bool=$vowifi", "editable_wfc_mode_bool=$vowifi", "editable_wfc_roaming_mode_bool=$vowifi", "carrier_default_wfc_ims_roaming_enabled_bool=$vowifi", "vonr_enabled_bool=$vonr", "vonr_setting_visibility_bool=$vonr", "carrier_supports_ss_over_ut_bool=true", "show_ims_registration_status_bool=true").joinToString(";;")
                            broker("patch", patch)
                        }, { broker("clear") }, { broker("verify") })
                        else -> ToolsScreen(busy) { name, command -> runTool(name, command) }
                    }
                }
            }
        }
    }

    @Composable
    private fun VipHeader(status: String, last: String) {
        Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xFF111C29), Bg))).padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(54.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Cyan, Blue))), contentAlignment = Alignment.Center) { Text("TT", color = Color.White, fontWeight = FontWeight.Black, fontSize = 17.sp) }
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) { Text("APPLE SEED", color = MainText, fontSize = 22.sp, fontWeight = FontWeight.Black); Text("VIP TECHNICIAN CONSOLE", color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp) }
                StatusPill(status)
            }
            Spacer(Modifier.height(18.dp)); Text("BOARD INTELLIGENCE", color = MainText, fontSize = 26.sp, fontWeight = FontWeight.Black); Text("Diagnose. Measure. Repair. Verify.", color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(12.dp)); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { MetricCard("ADB", if (status.contains("ONLINE")) "ONLINE" else "OFFLINE", if (status.contains("ONLINE")) Good else Warn, Modifier.weight(1f)); MetricCard("SESSION", last, Cyan, Modifier.weight(1f)) }
        }
    }

    @Composable
    private fun StatusPill(status: String) {
        val online = status.contains("ONLINE")
        Box(Modifier.background(if (online) Good.copy(.12f) else Warn.copy(.12f), RoundedCornerShape(50.dp)).border(1.dp, if (online) Good.copy(.35f) else Warn.copy(.35f), RoundedCornerShape(50.dp)).padding(horizontal = 10.dp, vertical = 6.dp)) { Text(if (online) "● LIVE" else "● READY", color = if (online) Good else Warn, fontSize = 9.sp, fontWeight = FontWeight.Bold) }
    }

    @Composable
    private fun HomeScreen(adb: String, permission: Boolean, nearbyPermission: Boolean, requestPermission: () -> Unit, setupDevice: () -> Unit, openPanic: () -> Unit, openIms: () -> Unit) {
        Section("TECHNICIAN CONTROL", "Một nút xử lý kết nối — kết quả luôn nói rõ bước tiếp theo")
        Card(Modifier.fillMaxWidth().padding(horizontal = 14.dp), colors = CardDefaults.cardColors(containerColor = Panel)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("QUICK DIAGNOSTICS", color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { ActionTile("📷", "PANIC AI", "Chụp log", openPanic, Modifier.weight(1f)); ActionTile("⚡", "IMS", "VoLTE / VoWiFi", openIms, Modifier.weight(1f)) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { ActionTile("⌁", "THIẾT BỊ", if (adb.contains("ONLINE")) "Đã kết nối" else "Thiết lập / kết nối", setupDevice, Modifier.weight(1f)); ActionTile("◈", "BOARD", "Sơ đồ · Đo đạc", {}, Modifier.weight(1f)) }
            }
        }
        Spacer(Modifier.height(12.dp)); VisualHero("iPhone BOARD REPAIR", "Sơ đồ • Đo đạc • Panic log • Kinh nghiệm thực tế", R.drawable.apple_seed_iphone_hero, Cyan)
        Spacer(Modifier.height(12.dp)); Section("DEVICE STATUS", "Apple Seed tự kiểm tra điều kiện cần thiết")
        Card(Modifier.fillMaxWidth().padding(horizontal = 14.dp), colors = CardDefaults.cardColors(containerColor = Panel2)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) { Data("MODEL", Build.MODEL); Data("ANDROID", Build.VERSION.RELEASE ?: "--"); Data("SDK", Build.VERSION.SDK_INT.toString()); Data("PHONE PERMISSION", if (permission) "GRANTED" else "CẦN CẤP"); Data("NEARBY DEVICE", if (nearbyPermission) "GRANTED" else "CẦN CẤP"); Data("WIRELESS ADB", adb); if (!permission) Button(onClick = requestPermission, modifier = Modifier.fillMaxWidth()) { Text("CẤP QUYỀN ĐIỆN THOẠI") } }
        }
        Spacer(Modifier.height(12.dp)); Button(onClick = setupDevice, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp), colors = ButtonDefaults.buttonColors(containerColor = Blue)) { Text(if (adb.contains("ONLINE")) "THIẾT BỊ ĐÃ SẴN SÀNG" else "THIẾT LẬP & KẾT NỐI THIẾT BỊ", fontWeight = FontWeight.Black) }; Spacer(Modifier.height(18.dp))
    }

    @Composable
    private fun VisualHero(title: String, subtitle: String, image: Int, accent: Color) {
        Card(Modifier.fillMaxWidth().padding(horizontal = 14.dp), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Panel)) { Box(Modifier.fillMaxWidth().height(155.dp)) { Image(painterResource(image), title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop); Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC05070A))))); Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) { Text(title, color = MainText, fontSize = 18.sp, fontWeight = FontWeight.Black); Text(subtitle, color = accent, fontSize = 10.sp) } } }
    }

    @Composable
    private fun PanicScreen(bitmap: Bitmap?, capture: () -> Unit) {
        Section("PANIC INTELLIGENCE", "Camera evidence → đọc log → mã lỗi → hướng đo"); VisualHero("PANIC ANALYZER", "IMAGE → ERROR CODE → KNOWLEDGE → MEASUREMENT", R.drawable.apple_seed_panic_hero, Cyan); Spacer(Modifier.height(10.dp))
        Card(Modifier.fillMaxWidth().padding(horizontal = 14.dp), colors = CardDefaults.cardColors(containerColor = Panel)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Box(Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFF080D13)), contentAlignment = Alignment.Center) { if (bitmap == null) Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("◉", color = Cyan, fontSize = 42.sp); Text("PANIC PHOTO", color = MainText, fontWeight = FontWeight.Black, fontSize = 18.sp); Text("Chụp màn hình panic/log của máy", color = Muted, fontSize = 11.sp) } else Image(bitmap.asImageBitmap(), "Panic evidence", Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }; Button(onClick = capture, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Color.Black)) { Text(if (bitmap == null) "📷 CHỤP PANIC LOG" else "📷 CHỤP LẠI", fontWeight = FontWeight.Black) }; Text("EVIDENCE PIPELINE", color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp); listOf("01  IMAGE CAPTURE" to "Thu ảnh thật", "02  ERROR CODE" to "Tách mã / chuỗi lỗi", "03  KNOWLEDGE" to "Tìm ca tương tự", "04  MEASUREMENT" to "Đề xuất điểm đo").forEach { (title, detail) -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(title, color = MainText, fontSize = 11.sp, fontWeight = FontWeight.Bold); Text(detail, color = Muted, fontSize = 10.sp) } }; Text("Vision engine: local/offline pipeline sẽ nối vào bước phân tích tiếp theo.", color = Muted, fontSize = 10.sp) } }; Spacer(Modifier.height(18.dp))
    }

    @Composable
    private fun ImsScreen(evidence: String, busy: Boolean, volte: Boolean, vowifi: Boolean, vonr: Boolean, setVolte: (Boolean) -> Unit, setVowifi: (Boolean) -> Unit, setVonr: (Boolean) -> Unit, read: () -> Unit, apply: () -> Unit, restore: () -> Unit, verify: () -> Unit) {
        Section("IMS INTELLIGENCE", "READ → PATCH → RESET → VERIFY"); VisualHero("IMS & NETWORK TOOLS", "VoLTE • VoWiFi • VoNR • ADB • RADIO", R.drawable.apple_seed_ims_hero, Cyan); Spacer(Modifier.height(10.dp)); Card(Modifier.fillMaxWidth().padding(horizontal = 14.dp), colors = CardDefaults.cardColors(containerColor = Panel)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { Toggle("VoLTE", volte, setVolte); Toggle("VoWiFi", vowifi, setVowifi); Toggle("VoNR", vonr, setVonr); Button(onClick = apply, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("APPLY + RESET IMS") }; OutlinedButton(onClick = verify, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("VERIFY CONFIG") }; OutlinedButton(onClick = restore, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("RESTORE OVERRIDE") } } }; Spacer(Modifier.height(10.dp)); Button(onClick = read, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp)) { Text(if (busy) "READING..." else "READ ALL CARRIER CONFIG") }; Evidence(evidence)
    }

    @Composable
    private fun ToolsScreen(busy: Boolean, run: (String, String) -> Unit) {
        Section("TECH TOOLS", "Mỗi thao tác đều trả kết quả và hướng xử lý nếu thất bại"); listOf("IMS SERVICE" to "dumpsys ims", "TELEPHONY REGISTRY" to "dumpsys telephony.registry", "CARRIER CONFIG" to "dumpsys carrier_config", "RADIO" to "dumpsys radio", "PROPERTIES" to "getprop").forEach { (name, command) -> OutlinedButton(onClick = { run(name, command) }, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 3.dp)) { Text(name) } }
    }

    @Composable private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) { Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text(label, color = MainText, fontWeight = FontWeight.Bold); Switch(checked = value, onCheckedChange = onChange) } }
    @Composable private fun MetricCard(label: String, value: String, accent: Color, modifier: Modifier = Modifier) { Card(modifier, colors = CardDefaults.cardColors(containerColor = Panel2)) { Column(Modifier.padding(12.dp)) { Text(label, color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold); Text(value, color = accent, fontSize = 12.sp, fontWeight = FontWeight.Black) } } }
    @Composable private fun ActionTile(icon: String, title: String, detail: String, action: () -> Unit, modifier: Modifier = Modifier) { Card(modifier = modifier, onClick = action, colors = CardDefaults.cardColors(containerColor = Panel2)) { Column(Modifier.padding(13.dp)) { Text(icon, fontSize = 22.sp); Spacer(Modifier.height(5.dp)); Text(title, color = MainText, fontWeight = FontWeight.Black, fontSize = 12.sp); Text(detail, color = Muted, fontSize = 9.sp) } } }
    @Composable private fun Section(title: String, subtitle: String) { Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) { Text(title, color = MainText, fontSize = 14.sp, fontWeight = FontWeight.Black); Text(subtitle, color = Muted, fontSize = 10.sp) } }
    @Composable private fun Data(label: String, value: String) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(label, color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold); Text(value, color = MainText, fontSize = 10.sp, fontWeight = FontWeight.Bold) } }
    @Composable private fun Evidence(text: String) { if (text.isNotBlank()) Card(Modifier.fillMaxWidth().padding(14.dp), colors = CardDefaults.cardColors(containerColor = Panel2)) { Text(text, color = MainText, fontSize = 10.sp, modifier = Modifier.padding(12.dp)) } }
}