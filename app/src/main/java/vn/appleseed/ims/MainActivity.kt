package vn.appleseed.ims

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Bg = Color(0xFF07090C)
private val Panel = Color(0xFF11161C)
private val MainText = Color(0xFFF4F7FA)
private val Muted = Color(0xFF87939F)
private val Good = Color(0xFF49E69B)
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
        var pairCode by remember { mutableStateOf("") }
        var volte by remember { mutableStateOf(true) }
        var vowifi by remember { mutableStateOf(true) }
        var vonr by remember { mutableStateOf(true) }
        var permission by remember { mutableStateOf(checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) }
        val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permission = it }

        fun refresh() { adb = LocalAdbEngine.status() }

        fun openWirelessDebugging() {
            val direct = Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS")
            runCatching {
                startActivity(direct)
            }.onFailure {
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
                onDismissRequest = {
                    LocalAdbEngine.stopPairingDiscovery()
                    pairDialog = false
                },
                title = { Text("PAIR WIRELESS DEBUGGING") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Nhập mã 6 số đang hiện trong Pair device with pairing code rồi bấm PAIR.", color = Muted)
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
                                if (ok) {
                                    adb = result
                                } else {
                                    adb = "PAIR FAILED"
                                    android.widget.Toast.makeText(this@MainActivity, result, android.widget.Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }) { Text("PAIR") }
                },
                dismissButton = {
                    OutlinedButton(onClick = {
                        LocalAdbEngine.stopPairingDiscovery()
                        pairDialog = false
                    }) { Text("CANCEL") }
                }
            )
        }

        val tabs = listOf("DEVICE", "CARRIER", "PATCH", "TOOLS")
        MaterialTheme {
            Scaffold(
                containerColor = Bg,
                bottomBar = {
                    NavigationBar(containerColor = Panel) {
                        tabs.forEachIndexed { index, title ->
                            NavigationBarItem(
                                selected = tab == index,
                                onClick = { tab = index },
                                icon = { Text(listOf("▣", "▤", "⚡", "⚙")[index]) },
                                label = { Text(title) }
                            )
                        }
                    }
                }
            ) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
                    Header(adb, lastAction)
                    when (tab) {
                        0 -> DeviceScreen(permission, adb,
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
                            })
                        1 -> CarrierScreen(evidence, busy) { broker("read") }
                        2 -> PatchScreen(volte, vowifi, vonr, busy,
                            setVolte = { volte = it },
                            setVowifi = { vowifi = it },
                            setVonr = { vonr = it },
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
                            verify = { broker("verify") })
                        3 -> ToolsScreen(adb, busy) { name, command ->
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

    @Composable private fun Header(status: String, last: String) {
        Column(Modifier.fillMaxWidth().background(Panel).padding(16.dp)) {
            Text("APPLE SEED", color = MainText, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            Text("CARRIER / IMS WORKSTATION", color = Muted, fontSize = 11.sp)
            Spacer(Modifier.height(9.dp))
            Row(Modifier.fillMaxWidth().background(Color(0xFF181E25), RoundedCornerShape(10.dp)).padding(10.dp)) {
                Text("●", color = if (status.contains("ONLINE")) Good else Warn)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(status, color = MainText, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text("LAST: $last", color = Muted, fontSize = 10.sp)
                }
            }
        }
    }

    @Composable private fun DeviceScreen(permission: Boolean, adb: String, requestPermission: () -> Unit, openSettings: () -> Unit, pair: () -> Unit, reconnect: () -> Unit) {
        Section("DEVICE / ACCESS", "Wireless Debugging → local ADB → Apple Seed broker")
        Card(Modifier.fillMaxWidth().padding(12.dp), colors = CardDefaults.cardColors(containerColor = Panel)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Data("MODEL", Build.MODEL)
                Data("ANDROID", Build.VERSION.RELEASE ?: "--")
                Data("SDK", Build.VERSION.SDK_INT.toString())
                Data("PHONE PERMISSION", if (permission) "GRANTED" else "NOT GRANTED")
                Data("ADB", adb)
                if (!permission) Button(onClick = requestPermission) { Text("CẤP PHONE PERMISSION") }
                OutlinedButton(onClick = openSettings, modifier = Modifier.fillMaxWidth()) { Text("MỞ WIRELESS DEBUGGING") }
                Button(onClick = pair, modifier = Modifier.fillMaxWidth()) { Text("PAIR WIRELESS DEBUGGING") }
                OutlinedButton(onClick = reconnect, modifier = Modifier.fillMaxWidth()) { Text("RECONNECT ĐÃ PAIR") }
            }
        }
        UsageGuide(adb)
    }

    @Composable private fun UsageGuide(adb: String) {
        Section("HƯỚNG DẪN SỬ DỤNG", "Làm theo đúng thứ tự — chỉ Pair lần đầu")
        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = Panel)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GuideRow("1", "BẬT WIRELESS DEBUGGING", "Trên điện thoại: Developer options → Wireless debugging → ON.")
                GuideRow("2", "PAIR LẦN ĐẦU", "Mở Wireless debugging bằng nút bên trên → chọn Pair device with pairing code → giữ mã 6 số → quay lại Apple Seed → bấm PAIR WIRELESS DEBUGGING.")
                GuideRow("3", "KẾT NỐI", "Sau khi Pair thành công, app tự tìm cổng ADB và kết nối. Chờ trạng thái WIRELESS ADB ONLINE.")
                GuideRow("4", "LẦN SAU", "Không cần Pair lại. Bấm RECONNECT ĐÃ PAIR nếu máy chưa tự kết nối.")
                GuideRow("5", "ĐỌC DỮ LIỆU", "Vào CARRIER → READ ALL CARRIER CONFIG để đọc cấu hình SIM.")
                GuideRow("6", "PATCH IMS", "Vào PATCH → chọn VoLTE / VoWiFi / VoNR → APPLY + RESET IMS → VERIFY CONFIG.")
                GuideRow("7", "KHÔI PHỤC", "Muốn bỏ override → RESTORE OVERRIDE. Sau đó VERIFY CONFIG để kiểm tra lại.")
                Spacer(Modifier.height(2.dp))
                Text("KẾT NỐI: $adb", color = if (adb.contains("ONLINE")) Good else Warn, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text("Yêu cầu: điện thoại và máy tính cùng mạng Wi‑Fi. Wireless Debugging phải đang bật.", color = Muted, fontSize = 10.sp)
            }
        }
    }

    @Composable private fun GuideRow(number: String, title: String, detail: String) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(number, color = Good, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
            Column(Modifier.weight(1f)) {
                Text(title, color = MainText, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                Text(detail, color = Muted, fontSize = 10.sp, lineHeight = 14.sp)
            }
        }
    }

    @Composable private fun CarrierScreen(evidence: String, busy: Boolean, read: () -> Unit) {
        Section("CARRIER CONFIG", "Đọc CarrierConfig thật theo Subscription / SIM")
        Button(onClick = read, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) { Text(if (busy) "READING..." else "READ ALL CARRIER CONFIG") }
        Evidence(evidence)
    }

    @Composable private fun PatchScreen(volte: Boolean, vowifi: Boolean, vonr: Boolean, busy: Boolean, setVolte: (Boolean) -> Unit, setVowifi: (Boolean) -> Unit, setVonr: (Boolean) -> Unit, apply: () -> Unit, restore: () -> Unit, verify: () -> Unit) {
        Section("IMS PATCH", "READ → PATCH → RESET IMS → VERIFY")
        Card(Modifier.fillMaxWidth().padding(12.dp), colors = CardDefaults.cardColors(containerColor = Panel)) {
            Column(Modifier.padding(14.dp)) {
                Toggle("VoLTE", volte, setVolte)
                Toggle("VoWiFi", vowifi, setVowifi)
                Toggle("VoNR", vonr, setVonr)
                Spacer(Modifier.height(8.dp))
                Button(onClick = apply, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("APPLY + RESET IMS") }
                Spacer(Modifier.height(5.dp))
                OutlinedButton(onClick = verify, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("VERIFY CONFIG") }
                Spacer(Modifier.height(5.dp))
                OutlinedButton(onClick = restore, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("RESTORE OVERRIDE") }
            }
        }
    }

    @Composable private fun ToolsScreen(adb: String, busy: Boolean, run: (String, String) -> Unit) {
        Section("TECH TOOLS", "Shell evidence trực tiếp")
        listOf("IMS SERVICE" to "dumpsys ims", "TELEPHONY REGISTRY" to "dumpsys telephony.registry", "CARRIER CONFIG" to "dumpsys carrier_config", "RADIO" to "dumpsys radio", "PROPERTIES" to "getprop").forEach { (name, command) ->
            OutlinedButton(onClick = { run(name, command) }, enabled = !busy && adb.contains("ONLINE"), modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp)) { Text(name) }
        }
    }

    @Composable private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = MainText, fontWeight = FontWeight.SemiBold)
            Switch(checked = value, onCheckedChange = onChange)
        }
    }

    @Composable private fun Section(title: String, subtitle: String) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(title, color = MainText, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
            Text(subtitle, color = Muted, fontSize = 10.sp)
        }
    }

    @Composable private fun Data(label: String, value: String) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Muted, fontSize = 10.sp)
            Text(value, color = MainText, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
    }

    @Composable private fun Evidence(text: String) {
        if (text.isNotBlank()) {
            Card(Modifier.fillMaxWidth().padding(12.dp), colors = CardDefaults.cardColors(containerColor = Panel)) {
                Text(text, color = MainText, fontSize = 10.sp, modifier = Modifier.padding(12.dp))
            }
        }
    }
}
