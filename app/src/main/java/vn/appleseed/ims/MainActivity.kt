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
private val Danger = Color(0xFFFF667A)

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
        var pairCode by remember { mutableStateOf("") }
        var volte by remember { mutableStateOf(true) }
        var vowifi by remember { mutableStateOf(true) }
        var vonr by remember { mutableStateOf(true) }
        var permission by remember { mutableStateOf(checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) }
        var panicImage by remember { mutableStateOf<Bitmap?>(null) }
        val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permission = it }
        val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap -> panicImage = bitmap }

        fun refresh() { adb = LocalAdbEngine.status() }

        fun openWirelessDebugging() {
            val direct = Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS")
            runCatching { startActivity(direct) }.onFailure {
                startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                android.widget.Toast.makeText(this@MainActivity, "Kéo xuống Wireless debugging → Pair device with pairing code.", android.widget.Toast.LENGTH_LONG).show()
            }
        }

        fun broker(mode: String, patch: String = "") {
            if (busy) return
            if (!LocalAdbEngine.hasConnection()) {
                android.widget.Toast.makeText(this@MainActivity, "Hãy kết nối Wireless Debugging trước.", android.widget.Toast.LENGTH_LONG).show()
                return
            }
            busy = true
            lastAction = mode.uppercase()
            LocalAdbEngine.runBroker(mode, patch = patch) { ok, result ->
                runOnUiThread {
                    busy = false
                    evidence = result
                    refresh()
                    if (!ok) android.widget.Toast.makeText(this@MainActivity, result, android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }

        LaunchedEffect(Unit) {
            while (true) {
                kotlinx.coroutines.delay(1000)
                refresh()
            }
        }

        if (pairDialog) {
            AlertDialog(
                onDismissRequest = { LocalAdbEngine.stopPairingDiscovery(); pairDialog = false },
                title = { Text("PAIR WIRELESS DEBUGGING") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Nhập mã 6 số đang hiện trong Pair device with pairing code.", color = Muted)
                        OutlinedTextField(
                            value = pairCode,
                            onValueChange = { pairCode = it.filter(Char::isDigit).take(6) },
                            label = { Text("6-digit pairing code") },
                            singleLine = true
                        )
                    }
                },
                confirmButton = {
                    Button(enabled = pairCode.length == 6, onClick = {
                        pairDialog = false
                        adb = "PAIRING..."
                        LocalAdbEngine.pair(pairCode) { ok, result ->
                            runOnUiThread {
                                pairCode = ""
                                adb = if (ok) result else "PAIR FAILED"
                                if (!ok) android.widget.Toast.makeText(this@MainActivity, result, android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                    }) { Text("PAIR") }
                },
                dismissButton = {
                    OutlinedButton(onClick = { LocalAdbEngine.stopPairingDiscovery(); pairDialog = false }) { Text("CANCEL") }
                }
            )
        }

        val tabs = listOf("HOME", "PANIC", "IMS", "TOOLS")
        MaterialTheme {
            Scaffold(
                containerColor = Bg,
                bottomBar = {
                    NavigationBar(containerColor = Panel) {
                        tabs.forEachIndexed { index, title ->
                            NavigationBarItem(
                                selected = tab == index,
                                onClick = { tab = index },
                                icon = { Text(listOf("⌂", "◉", "⚡", "⚙")[index], fontSize = 17.sp) },
                                label = { Text(title, fontSize = 10.sp) }
                            )
                        }
                    }
                }
            ) { padding ->
                Column(
                    Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).background(Bg)
                ) {
                    VipHeader(adb, lastAction)
                    when (tab) {
                        0 -> HomeScreen(
                            adb = adb,
                            permission = permission,
                            requestPermission = { permissionLauncher.launch(Manifest.permission.READ_PHONE_STATE) },
                            openSettings = { openWirelessDebugging() },
                            pair = {
                                adb = "READY FOR 6-DIGIT PAIRING CODE"
                                pairCode = ""
                                pairDialog = true
                                LocalAdbEngine.preparePairing()
                            },
                            reconnect = {
                                adb = "CONNECTING..."
                                LocalAdbEngine.reconnectSaved { _, status -> runOnUiThread { adb = status } }
                            },
                            openPanic = { tab = 1 }
                        )
                        1 -> PanicScreen(panicImage, { cameraLauncher.launch(null) })
                        2 -> ImsScreen(
                            evidence, busy, volte, vowifi, vonr,
                            setVolte = { volte = it }, setVowifi = { vowifi = it }, setVonr = { vonr = it },
                            read = { broker("read") },
                            apply = {
                                val patch = listOf(
                                    "carrier_volte_available_bool=$volte",
                                    "enhanced_4g_lte_on_by_default_bool=$volte",
                                    "editable_enhanced_4g_lte_bool=true",
                                    "hide_enhanced_4g_lte_bool=false",
                                    "carrier_volte_provisioned_bool=$volte",
                                    "carrier_volte_provisioning_required_bool=false",
                                    "carrier_wfc_ims_available_bool=$vowifi",
                                    "carrier_default_wfc_ims_enabled_bool=$vowifi",
                                    "carrier_wfc_ims_provisioned_bool=$vowifi",
                                    "editable_wfc_mode_bool=$vowifi",
                                    "editable_wfc_roaming_mode_bool=$vowifi",
                                    "carrier_default_wfc_ims_roaming_enabled_bool=$vowifi",
                                    "vonr_enabled_bool=$vonr",
                                    "vonr_setting_visibility_bool=$vonr",
                                    "carrier_supports_ss_over_ut_bool=true",
                                    "show_ims_registration_status_bool=true"
                                ).joinToString(";;")
                                broker("patch", patch)
                            },
                            restore = { broker("clear") },
                            verify = { broker("verify") }
                        )
                        else -> ToolsScreen(adb, busy) { name, command ->
                            if (busy || !LocalAdbEngine.hasConnection()) return@ToolsScreen
                            busy = true
                            lastAction = name
                            Thread {
                                val result = LocalAdbEngine.shell(command)
                                runOnUiThread { evidence = result; busy = false; refresh() }
                            }.start()
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun VipHeader(status: String, last: String) {
        Column(
            Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xFF111C29), Bg))).padding(horizontal = 18.dp, vertical = 18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(54.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Cyan, Blue))), contentAlignment = Alignment.Center) {
                    Text("TT", color = Color.White, fontWeight = FontWeight.Black, fontSize = 17.sp)
                }
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Text("APPLE SEED", color = MainText, fontSize = 22.sp, fontWeight = FontWeight.Black)
                    Text("VIP TECHNICIAN CONSOLE", color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
                }
                StatusPill(status)
            }
            Spacer(Modifier.height(18.dp))
            Text("BOARD INTELLIGENCE", color = MainText, fontSize = 26.sp, fontWeight = FontWeight.Black)
            Text("Diagnose. Measure. Repair. Verify.", color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCard("ADB", if (status.contains("ONLINE")) "ONLINE" else "OFFLINE", if (status.contains("ONLINE")) Good else Warn, Modifier.weight(1f))
                MetricCard("SESSION", last, Cyan, Modifier.weight(1f))
            }
        }
    }

    @Composable private fun StatusPill(status: String) {
        val online = status.contains("ONLINE")
        Box(Modifier.background(if (online) Good.copy(alpha = .12f) else Warn.copy(alpha = .12f), RoundedCornerShape(50.dp)).border(1.dp, if (online) Good.copy(alpha = .35f) else Warn.copy(alpha = .35f), RoundedCornerShape(50.dp)).padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(if (online) "● LIVE" else "● READY", color = if (online) Good else Warn, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
    }

    @Composable
    private fun HomeScreen(adb: String, permission: Boolean, requestPermission: () -> Unit, openSettings: () -> Unit, pair: () -> Unit, reconnect: () -> Unit, openPanic: () -> Unit) {
        Section("TECHNICIAN CONTROL", "Một màn hình — mọi thứ quan trọng trong tầm tay")
        Card(Modifier.fillMaxWidth().padding(horizontal = 14.dp), colors = CardDefaults.cardColors(containerColor = Panel)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("QUICK DIAGNOSTICS", color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionTile("📷", "PANIC AI", "Chụp log", openPanic, Modifier.weight(1f))
                    ActionTile("⚡", "IMS", "VoLTE / VoWiFi", {}, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionTile("⌁", "ADB", if (adb.contains("ONLINE")) "Đã kết nối" else "Chưa kết nối", reconnect, Modifier.weight(1f))
                    ActionTile("◈", "BOARD", "Evidence", {}, Modifier.weight(1f))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Section("DEVICE IDENTITY", "Thiết bị đang được Apple Seed xử lý")
        Card(Modifier.fillMaxWidth().padding(horizontal = 14.dp), colors = CardDefaults.cardColors(containerColor = Panel2)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Data("MODEL", Build.MODEL)
                Data("ANDROID", Build.VERSION.RELEASE ?: "--")
                Data("SDK", Build.VERSION.SDK_INT.toString())
                Data("PHONE PERMISSION", if (permission) "GRANTED" else "NOT GRANTED")
                Data("WIRELESS ADB", adb)
                if (!permission) Button(onClick = requestPermission, modifier = Modifier.fillMaxWidth()) { Text("CẤP PHONE PERMISSION") }
            }
        }
        Spacer(Modifier.height(12.dp))
        Section("WIRELESS ACCESS", "Pair chỉ lần đầu — sau đó Apple Seed tự reconnect")
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = openSettings, modifier = Modifier.weight(1f)) { Text("WIRELESS DEBUGGING", fontSize = 10.sp) }
            Button(onClick = pair, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Blue)) { Text("PAIR 6-DIGIT", fontSize = 10.sp) }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = reconnect, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp)) { Text("RECONNECT ĐÃ PAIR") }
        Spacer(Modifier.height(18.dp))
    }

    @Composable
    private fun PanicScreen(bitmap: Bitmap?, capture: () -> Unit) {
        Section("PANIC INTELLIGENCE", "Camera evidence → đọc log → mã lỗi → hướng đo")
        Card(Modifier.fillMaxWidth().padding(horizontal = 14.dp), colors = CardDefaults.cardColors(containerColor = Panel)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFF080D13)), contentAlignment = Alignment.Center) {
                    if (bitmap == null) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("◉", color = Cyan, fontSize = 42.sp)
                            Text("PANIC PHOTO", color = MainText, fontWeight = FontWeight.Black, fontSize = 18.sp)
                            Text("Chụp màn hình panic/log của máy", color = Muted, fontSize = 11.sp)
                        }
                    } else {
                        Image(bitmap.asImageBitmap(), "Panic evidence", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                }
                Button(onClick = capture, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Color.Black)) {
                    Text(if (bitmap == null) "📷 CHỤP PANIC LOG" else "📷 CHỤP LẠI", fontWeight = FontWeight.Black)
                }
                Text("EVIDENCE PIPELINE", color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                listOf("01  IMAGE CAPTURE" to "Thu ảnh thật", "02  ERROR CODE" to "Tách mã / chuỗi lỗi", "03  KNOWLEDGE" to "Tìm ca tương tự", "04  MEASUREMENT" to "Đề xuất điểm đo").forEach { (title, detail) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(title, color = MainText, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text(detail, color = Muted, fontSize = 10.sp)
                    }
                }
                Text("Lưu ý: màn hình này hiện đã có capture/preview; bộ phân tích Vision local sẽ được nối vào pipeline ở bước tiếp theo.", color = Warn, fontSize = 10.sp)
            }
        }
        Spacer(Modifier.height(18.dp))
    }

    @Composable
    private fun ImsScreen(evidence: String, busy: Boolean, volte: Boolean, vowifi: Boolean, vonr: Boolean, setVolte: (Boolean) -> Unit, setVowifi: (Boolean) -> Unit, setVonr: (Boolean) -> Unit, read: () -> Unit, apply: () -> Unit, restore: () -> Unit, verify: () -> Unit) {
        Section("IMS CONTROL", "READ → PATCH → RESET IMS → VERIFY")
        Card(Modifier.fillMaxWidth().padding(horizontal = 14.dp), colors = CardDefaults.cardColors(containerColor = Panel)) {
            Column(Modifier.padding(16.dp)) {
                Toggle("VoLTE", volte, setVolte)
                Toggle("VoWiFi", vowifi, setVowifi)
                Toggle("VoNR", vonr, setVonr)
                Spacer(Modifier.height(10.dp))
                Button(onClick = apply, enabled = !busy, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Blue)) { Text("APPLY + RESET IMS") }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = verify, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("VERIFY CONFIG") }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = restore, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("RESTORE OVERRIDE") }
            }
        }
        Spacer(Modifier.height(10.dp))
        Section("LIVE EVIDENCE", "CarrierConfig thật từ thiết bị")
        Button(onClick = read, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp)) { Text(if (busy) "READING..." else "READ ALL CARRIER CONFIG") }
        Evidence(evidence)
        Spacer(Modifier.height(18.dp))
    }

    @Composable private fun ToolsScreen(adb: String, busy: Boolean, run: (String, String) -> Unit) {
        Section("TECH TOOLS", "Shell evidence trực tiếp")
        listOf("IMS SERVICE" to "dumpsys ims", "TELEPHONY REGISTRY" to "dumpsys telephony.registry", "CARRIER CONFIG" to "dumpsys carrier_config", "RADIO" to "dumpsys radio", "PROPERTIES" to "getprop").forEach { (name, command) ->
            OutlinedButton(onClick = { run(name, command) }, enabled = !busy && adb.contains("ONLINE"), modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)) { Text(name) }
        }
        Spacer(Modifier.height(18.dp))
    }

    @Composable private fun ActionTile(icon: String, title: String, detail: String, action: () -> Unit, modifier: Modifier) {
        Card(onClick = action, modifier = modifier, colors = CardDefaults.cardColors(containerColor = Panel2), shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(icon, fontSize = 22.sp)
                Text(title, color = MainText, fontWeight = FontWeight.Black, fontSize = 12.sp)
                Text(detail, color = Muted, fontSize = 9.sp)
            }
        }
    }

    @Composable private fun MetricCard(label: String, value: String, color: Color, modifier: Modifier) {
        Column(modifier.background(Color(0xFF0B1118), RoundedCornerShape(10.dp)).padding(10.dp)) {
            Text(label, color = Muted, fontSize = 8.sp, fontWeight = FontWeight.Bold)
            Text(value, color = color, fontSize = 11.sp, fontWeight = FontWeight.Black)
        }
    }

    @Composable private fun Section(title: String, subtitle: String) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp)) {
            Text(title, color = MainText, fontSize = 13.sp, fontWeight = FontWeight.Black, letterSpacing = .7.sp)
            Text(subtitle, color = Muted, fontSize = 10.sp)
        }
    }

    @Composable private fun Data(label: String, value: String) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text(value.take(42), color = MainText, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }

    @Composable private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(label, color = MainText, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(if (value) "ENABLED" else "DISABLED", color = if (value) Good else Danger, fontSize = 9.sp)
            }
            Switch(checked = value, onCheckedChange = onChange)
        }
    }

    @Composable private fun Evidence(text: String) {
        if (text.isBlank()) return
        Card(Modifier.fillMaxWidth().padding(14.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF080B0F))) {
            Text(text, color = Color(0xFFB8C4D0), fontSize = 9.sp, modifier = Modifier.padding(12.dp))
        }
    }
}
