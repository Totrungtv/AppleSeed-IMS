package vn.appleseed.ims

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

private val Bg = Color(0xFF07090C)
private val Panel = Color(0xFF10141A)
private val Panel2 = Color(0xFF151A21)
private val Line = Color(0xFF252D36)
private val TextMain = Color(0xFFF4F7FA)
private val TextMuted = Color(0xFF7F8C99)
private val Accent = Color(0xFFFF6B35)
private val Good = Color(0xFF49E69B)
private val Warn = Color(0xFFFFC857)
private val Bad = Color(0xFFFF6B6B)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LocalAdbEngine.init(this)
        setContent { AppleSeedImsApp() }
    }

    private fun phonePermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun snapshot(): DeviceSnapshot {
        val tm = getSystemService(TelephonyManager::class.java)
        val sm = getSystemService(SubscriptionManager::class.java)
        fun safe(block: () -> String): String = runCatching { block() }.getOrDefault("--").ifBlank { "--" }
        fun networkType(type: Int): String = when (type) {
            TelephonyManager.NETWORK_TYPE_LTE -> "LTE / 4G"
            TelephonyManager.NETWORK_TYPE_NR -> "NR / 5G"
            TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS / 3G"
            TelephonyManager.NETWORK_TYPE_GSM -> "GSM / 2G"
            else -> "UNKNOWN ($type)"
        }
        return DeviceSnapshot(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            android = Build.VERSION.RELEASE,
            sdk = Build.VERSION.SDK_INT,
            carrier = safe { tm.networkOperatorName },
            mccmnc = safe { tm.networkOperator },
            dataNetwork = networkType(runCatching { tm.dataNetworkType }.getOrDefault(0)),
            voiceNetwork = networkType(runCatching { tm.voiceNetworkType }.getOrDefault(0)),
            simCount = runCatching { sm.activeSubscriptionInfoList?.size ?: 0 }.getOrDefault(0)
        )
    }

    @Composable
    private fun AppleSeedImsApp() {
        var tab by remember { mutableIntStateOf(0) }
        var snap by remember { mutableStateOf<DeviceSnapshot?>(null) }
        var raw by remember { mutableStateOf("") }
        var diagnosis by remember { mutableStateOf("Chưa chạy diagnostic engine.") }
        var permission by remember { mutableStateOf(phonePermission()) }
        var adbStatus by remember { mutableStateOf(LocalAdbEngine.status()) }
        var busy by remember { mutableStateOf(false) }
        var lastAction by remember { mutableStateOf("READY") }
        var showPairDialog by remember { mutableStateOf(false) }
        var pairCode by remember { mutableStateOf("") }
        var pairHint by remember { mutableStateOf("") }

        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            permission = granted
            if (granted) snap = snapshot()
        }

        fun uiStatus() {
            adbStatus = LocalAdbEngine.status()
        }

        fun discoverPairing() {
            lastAction = "SEARCHING WIRELESS DEBUGGING"
            adbStatus = "SEARCHING PAIRING SERVICE..."
            LocalAdbEngine.discoverPairingPort(
                onFound = {
                    runOnUiThread {
                        adbStatus = "PAIRING PORT FOUND"
                        pairHint = "Wireless Debugging đã mở. Nhập đúng mã 6 số Android đang hiển thị."
                        showPairDialog = true
                    }
                },
                onError = { error ->
                    runOnUiThread {
                        adbStatus = "WIRELESS DEBUGGING OFFLINE"
                        pairHint = error
                        Toast.makeText(this@MainActivity, error, Toast.LENGTH_LONG).show()
                    }
                }
            )
        }

        fun runTool(name: String, command: String, targetTab: Int = 1) {
            if (busy) return
            busy = true
            lastAction = name
            Thread {
                val result = LocalAdbEngine.shell(command)
                runOnUiThread {
                    raw = result
                    diagnosis = runCatching { ImsDiagnostics.classify(ImsDiagnostics.parseDump(result)) }
                        .getOrDefault("Evidence đã thu thập.")
                    uiStatus()
                    busy = false
                    tab = targetTab
                }
            }.start()
        }

        fun runPatch(clear: Boolean) {
            if (busy) return
            if (!LocalAdbEngine.hasConnection()) {
                Toast.makeText(this@MainActivity, "Hãy kết nối Wireless Debugging trước.", Toast.LENGTH_LONG).show()
                return
            }
            busy = true
            lastAction = if (clear) "RESTORE CARRIER DEFAULT" else "ACTIVATE IMS / VoLTE"
            LocalAdbEngine.runInstrumentation(clear) { success, output ->
                runOnUiThread {
                    busy = false
                    raw = output.ifBlank { if (success) "Instrumentation completed." else "Instrumentation failed." }
                    uiStatus()
                    diagnosis = if (success) {
                        if (clear) "Carrier override đã được xoá và IMS reset."
                        else "CarrierConfig override đã được áp dụng và IMS reset. Hãy chạy DEEP SCAN để xác nhận REGISTERED."
                    } else output
                    tab = 1
                }
            }
        }

        LaunchedEffect(Unit) {
            snap = snapshot()
            while (true) {
                delay(1200)
                uiStatus()
            }
        }

        if (showPairDialog) {
            AlertDialog(
                onDismissRequest = { showPairDialog = false },
                title = { Text("PAIR WIRELESS DEBUGGING") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(pairHint, color = TextMuted, style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(
                            value = pairCode,
                            onValueChange = { pairCode = it.filter(Char::isDigit).take(6) },
                            label = { Text("6-digit pairing code") },
                            singleLine = true
                        )
                    }
                },
                confirmButton = {
                    Button(
                        enabled = pairCode.length == 6,
                        onClick = {
                            showPairDialog = false
                            adbStatus = "PAIRING..."
                            lastAction = "PAIR WIRELESS DEBUGGING"
                            LocalAdbEngine.pair(pairCode) { success, message ->
                                runOnUiThread {
                                    pairCode = ""
                                    if (success) {
                                        adbStatus = "PAIR OK — CONNECTING..."
                                        LocalAdbEngine.connect { ok, status ->
                                            runOnUiThread {
                                                adbStatus = status
                                                if (!ok) Toast.makeText(this@MainActivity, status, Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    } else {
                                        adbStatus = "PAIR FAILED"
                                        Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        }
                    ) { Text("PAIR") }
                },
                dismissButton = { OutlinedButton(onClick = { showPairDialog = false }) { Text("CANCEL") } }
            )
        }

        val tabs = listOf("OVERVIEW", "IMS", "CARRIER", "TOOLS")
        val icons = listOf("⌂", "◎", "▣", "⚙")
        MaterialTheme {
            Scaffold(
                containerColor = Bg,
                bottomBar = {
                    NavigationBar(containerColor = Color(0xFF0C1014), tonalElevation = 0.dp) {
                        tabs.forEachIndexed { index, title ->
                            NavigationBarItem(
                                selected = tab == index,
                                onClick = { tab = index },
                                icon = { Text(icons[index], fontWeight = FontWeight.Bold) },
                                label = { Text(title, maxLines = 1, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }
            ) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
                    Header(adbStatus, busy, lastAction)
                    when (tab) {
                        0 -> OverviewScreen(
                            snapshot = snap,
                            permission = permission,
                            adbStatus = adbStatus,
                            requestPermission = { launcher.launch(Manifest.permission.READ_PHONE_STATE) },
                            openDeveloper = {
                                runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
                            },
                            pair = { discoverPairing() },
                            reconnect = {
                                adbStatus = "CONNECTING..."
                                LocalAdbEngine.reconnectSaved { _, status -> runOnUiThread { adbStatus = status } }
                            },
                            scanDevice = { snap = snapshot(); uiStatus() }
                        )
                        1 -> ImsScreen(
                            raw = raw,
                            diagnosis = diagnosis,
                            status = adbStatus,
                            busy = busy,
                            onIms = { runTool("IMS SERVICE", "dumpsys ims") },
                            onRegistry = { runTool("TELEPHONY REGISTRY", "dumpsys telephony.registry") },
                            onDeep = { runTool("DEEP IMS", "dumpsys ims; dumpsys telephony.registry; dumpsys carrier_config; dumpsys radio") },
                            onActivate = { runPatch(false) },
                            onRestore = { runPatch(true) }
                        )
                        2 -> CarrierScreen(raw, adbStatus, busy, { runTool("CARRIER CONFIG", "dumpsys carrier_config", 2) })
                        3 -> ToolsScreen(adbStatus, busy, { name, command -> runTool(name, command) })
                    }
                }
            }
        }
    }

    @Composable
    private fun Header(status: String, busy: Boolean, lastAction: String) {
        Column(Modifier.fillMaxWidth().background(Panel).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(46.dp).background(Accent, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) { Text("🍎") }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text("APPLE SEED", color = TextMain, fontWeight = FontWeight.ExtraBold)
                    Text("STANDALONE IMS / VoLTE WORKSTATION", color = TextMuted, style = MaterialTheme.typography.labelSmall)
                }
                Box(Modifier.background(Color(0xFF20252C), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 5.dp)) { Text("PRO", color = Accent, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.labelSmall) }
            }
            Row(Modifier.fillMaxWidth().background(Color(0xFF171C22), RoundedCornerShape(10.dp)).border(1.dp, Line, RoundedCornerShape(10.dp)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("●", color = if (status.contains("ONLINE")) Good else Warn, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(status, color = if (status.contains("ONLINE")) Good else Warn, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                    Text(if (busy) "RUNNING: $lastAction" else "LAST: $lastAction", color = TextMuted, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }

    @Composable
    private fun OverviewScreen(
        snapshot: DeviceSnapshot?, permission: Boolean, adbStatus: String,
        requestPermission: () -> Unit, openDeveloper: () -> Unit, pair: () -> Unit,
        reconnect: () -> Unit, scanDevice: () -> Unit
    ) {
        SectionTitle("SYSTEM BASELINE", "Không Shizuku • không root • kết nối Wireless Debugging trực tiếp")
        InfoCard("DEVICE IDENTITY", "◈") {
            DataRow("MODEL", snapshot?.model ?: Build.MODEL)
            DataRow("MANUFACTURER", snapshot?.manufacturer ?: Build.MANUFACTURER)
            DataRow("ANDROID", snapshot?.android ?: Build.VERSION.RELEASE)
            DataRow("SDK", snapshot?.sdk?.toString() ?: Build.VERSION.SDK_INT.toString())
        }
        InfoCard("SIM / NETWORK", "◉") {
            DataRow("ACTIVE SIM", snapshot?.simCount?.toString() ?: "--")
            DataRow("CARRIER", snapshot?.carrier ?: "--")
            DataRow("MCC / MNC", snapshot?.mccmnc ?: "--")
            DataRow("DATA", snapshot?.dataNetwork ?: "--")
            DataRow("VOICE", snapshot?.voiceNetwork ?: "--")
        }
        InfoCard("WIRELESS DEBUGGING ACCESS", "◆") {
            StatusRow("READ_PHONE_STATE", if (permission) "GRANTED" else "NOT GRANTED", if (permission) Good else Warn)
            StatusRow("ADB ENGINE", adbStatus, if (adbStatus.contains("ONLINE")) Good else Warn)
            Spacer(Modifier.height(5.dp))
            if (!permission) PrimaryButton("CẤP PHONE PERMISSION", requestPermission, Modifier.fillMaxWidth())
            Spacer(Modifier.height(5.dp))
            OutlineButton("MỞ DEVELOPER OPTIONS", openDeveloper, Modifier.fillMaxWidth())
            Spacer(Modifier.height(5.dp))
            PrimaryButton("PAIR WIRELESS DEBUGGING", pair, Modifier.fillMaxWidth())
            Spacer(Modifier.height(5.dp))
            OutlineButton("RECONNECT ĐÃ PAIR", reconnect, Modifier.fillMaxWidth())
            Spacer(Modifier.height(5.dp))
            OutlineButton("SCAN DEVICE", scanDevice, Modifier.fillMaxWidth())
        }
        EvidenceNote("APPLE SEED ENGINE • Wireless Debugging → local ADB → shell identity → IMS broker")
    }

    @Composable
    private fun ImsScreen(raw: String, diagnosis: String, status: String, busy: Boolean, onIms: () -> Unit, onRegistry: () -> Unit, onDeep: () -> Unit, onActivate: () -> Unit, onRestore: () -> Unit) {
        SectionTitle("IMS DIAGNOSTICS + PATCH", "Đọc evidence trước, patch có rollback, sau đó verify REGISTERED")
        InfoCard("IMS ENGINE", "◎") {
            StatusRow("WIRELESS ADB", status, if (status.contains("ONLINE")) Good else Warn)
            Spacer(Modifier.height(7.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) {
                OutlineButton("IMS SERVICE", onIms, Modifier.weight(1f), busy)
                OutlineButton("REGISTRY", onRegistry, Modifier.weight(1f), busy)
            }
            Spacer(Modifier.height(7.dp))
            PrimaryButton(if (busy) "RUNNING..." else "DEEP SCAN  •  FULL EVIDENCE", onDeep, Modifier.fillMaxWidth(), busy)
        }
        InfoCard("IMS CONTROL", "⚡") {
            Text("CarrierConfig override + IMS reset. Restore trả về carrier default.", color = TextMuted, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            PrimaryButton("ACTIVATE VoLTE / VoWiFi / VoNR", onActivate, Modifier.fillMaxWidth(), busy)
            Spacer(Modifier.height(6.dp))
            OutlineButton("RESTORE CARRIER DEFAULT", onRestore, Modifier.fillMaxWidth(), busy)
        }
        if (raw.isBlank()) {
            InfoCard("LIVE STATUS", "●") {
                StatusRow("IMS REGISTRATION", "UNKNOWN", Warn)
                StatusRow("VoLTE", "UNKNOWN", Warn)
                StatusRow("VoWiFi", "UNKNOWN", Warn)
                StatusRow("VoNR", "UNKNOWN", Warn)
            }
        } else {
            InfoCard("DIAGNOSTIC RESULT", "✓") {
                ImsDiagnostics.parseDump(raw).forEach { DataRow(it.name, it.state, stateColor(it.state)) }
                HorizontalDivider(color = Line, modifier = Modifier.padding(vertical = 7.dp))
                Text(diagnosis, color = TextMain, style = MaterialTheme.typography.bodySmall)
            }
            InfoCard("RAW EVIDENCE", "⌁") { Text(raw.takeLast(14000), color = Color(0xFF9EABB8), style = MaterialTheme.typography.bodySmall) }
        }
        EvidenceNote("NO BLIND SUCCESS • chỉ kết luận VoLTE sau khi IMS registration được xác nhận")
    }

    @Composable
    private fun CarrierScreen(raw: String, status: String, busy: Boolean, onCarrier: () -> Unit) {
        SectionTitle("CARRIER PROFILE", "Đọc carrier thật trước và sau khi patch")
        InfoCard("CARRIER CONFIG ENGINE", "▣") {
            StatusRow("WIRELESS ADB", status, if (status.contains("ONLINE")) Good else Warn)
            Spacer(Modifier.height(6.dp))
            PrimaryButton(if (busy) "READING..." else "READ CARRIER CONFIG", onCarrier, Modifier.fillMaxWidth(), busy)
        }
        if (raw.isNotBlank()) InfoCard("CONFIG EVIDENCE", "⌁") { Text(raw.takeLast(14000), color = Color(0xFF9EABB8), style = MaterialTheme.typography.bodySmall) }
    }

    @Composable
    private fun ToolsScreen(status: String, busy: Boolean, onTool: (String, String) -> Unit) {
        SectionTitle("PRO TECH TOOLS", "Shell evidence trực tiếp qua Wireless Debugging")
        InfoCard("ENGINE", "⚙") { StatusRow("ADB", status, if (status.contains("ONLINE")) Good else Warn) }
        InfoCard("QUICK TOOLS", "⚡") {
            ToolButton("IMS SERVICE", "dumpsys ims") { onTool("IMS SERVICE", "dumpsys ims") }
            ToolButton("TELEPHONY REGISTRY", "dumpsys telephony.registry") { onTool("REGISTRY", "dumpsys telephony.registry") }
            ToolButton("CARRIER CONFIG", "dumpsys carrier_config") { onTool("CARRIER CONFIG", "dumpsys carrier_config") }
            ToolButton("RADIO", "dumpsys radio") { onTool("RADIO", "dumpsys radio") }
            ToolButton("WIFI STATE", "dumpsys wifi") { onTool("WIFI", "dumpsys wifi") }
            ToolButton("SYSTEM PROPS", "getprop") { onTool("GETPROP", "getprop") }
        }
        InfoCard("WORKFLOW", "◇") {
            WorkflowRow("01", "WIRELESS DEBUGGING", "PAIR")
            WorkflowRow("02", "ADB SHELL EVIDENCE", "READ")
            WorkflowRow("03", "CARRIER CONFIG", "PATCH")
            WorkflowRow("04", "IMS RESET", "RELOAD")
            WorkflowRow("05", "IMS REGISTERED", "VERIFY")
        }
    }

    @Composable private fun ToolButton(title: String, command: String, action: () -> Unit) {
        OutlinedButton(onClick = action, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), shape = RoundedCornerShape(10.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = TextMain)) {
            Column(Modifier.fillMaxWidth()) { Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium); Text(command, color = TextMuted, style = MaterialTheme.typography.labelSmall) }
        }
    }

    @Composable private fun SectionTitle(title: String, subtitle: String) {
        Column(Modifier.padding(horizontal = 15.dp, vertical = 14.dp)) { Text(title, color = TextMain, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium); Spacer(Modifier.height(3.dp)); Text(subtitle, color = TextMuted, style = MaterialTheme.typography.bodySmall) }
    }

    @Composable private fun InfoCard(title: String, icon: String, content: @Composable () -> Unit) {
        Card(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 5.dp), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Panel2), border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { Text(icon, color = Accent, fontWeight = FontWeight.Bold); Spacer(Modifier.width(8.dp)); Text(title, color = Color(0xFFAAB6C2), fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.labelMedium) }; HorizontalDivider(color = Line); content() }
        }
    }

    @Composable private fun DataRow(label: String, value: String, valueColor: Color = TextMain) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text(label, color = TextMuted, style = MaterialTheme.typography.bodySmall); Text(value, color = valueColor, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall) } }
    @Composable private fun StatusRow(label: String, value: String, color: Color) { Row(Modifier.fillMaxWidth().background(Color(0xFF0E1217), RoundedCornerShape(9.dp)).padding(horizontal = 10.dp, vertical = 9.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text(label, color = TextMuted, style = MaterialTheme.typography.labelSmall); Text("●  $value", color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall) } }
    @Composable private fun WorkflowRow(no: String, name: String, tag: String) { Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) { Text(no, color = Accent, fontWeight = FontWeight.ExtraBold, modifier = Modifier.width(28.dp), style = MaterialTheme.typography.labelSmall); Text(name, color = TextMain, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall); Text(tag, color = TextMuted, style = MaterialTheme.typography.labelSmall) } }
    @Composable private fun PrimaryButton(text: String, action: () -> Unit, modifier: Modifier, disabled: Boolean = false) { Button(onClick = action, enabled = !disabled, modifier = modifier.height(46.dp), shape = RoundedCornerShape(11.dp), colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White)) { Text(text, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium) } }
    @Composable private fun OutlineButton(text: String, action: () -> Unit, modifier: Modifier, disabled: Boolean = false) { OutlinedButton(onClick = action, enabled = !disabled, modifier = modifier.height(46.dp), shape = RoundedCornerShape(11.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = TextMain)) { Text(text, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall) } }
    @Composable private fun EvidenceNote(text: String) { Text(text, color = TextMuted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.fillMaxWidth().padding(12.dp)) }
    private fun stateColor(state: String): Color = when { state.contains("REGISTER", true) || state.contains("ENABLED", true) || state == "OK" -> Good; state.contains("FAIL", true) || state.contains("ERROR", true) -> Bad; else -> Warn }
}

data class DeviceSnapshot(val manufacturer: String, val model: String, val android: String, val sdk: Int, val carrier: String, val mccmnc: String, val dataNetwork: String, val voiceNetwork: String, val simCount: Int)
