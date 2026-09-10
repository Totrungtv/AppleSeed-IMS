package vn.appleseed.ims

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

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
        var shizukuStatus by remember { mutableStateOf(ShizukuShell.status()) }
        var scanning by remember { mutableStateOf(false) }
        var lastTool by remember { mutableStateOf("READY") }

        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            permission = granted
            if (granted) snap = snapshot()
        }

        fun execute(tool: String, task: () -> String, targetTab: Int = 1) {
            if (scanning) return
            scanning = true
            lastTool = tool
            Thread {
                val result = runCatching { task() }.getOrElse { "Tool error: ${it.message ?: it.javaClass.simpleName}" }
                runOnUiThread {
                    raw = result
                    if (result.isNotBlank()) {
                        diagnosis = runCatching {
                            ImsDiagnostics.classify(ImsDiagnostics.parseDump(result))
                        }.getOrDefault("Evidence đã thu thập. Chưa đủ dữ liệu để kết luận.")
                    }
                    shizukuStatus = ShizukuShell.status()
                    scanning = false
                    tab = targetTab
                }
            }.start()
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
                Column(
                    modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                ) {
                    Header(shizukuStatus, scanning, lastTool)
                    when (tab) {
                        0 -> OverviewScreen(
                            snapshot = snap,
                            permission = permission,
                            shizukuStatus = shizukuStatus,
                            requestPermission = { launcher.launch(Manifest.permission.READ_PHONE_STATE) },
                            connectShizuku = {
                                shizukuStatus = ShizukuShell.connect()
                            },
                            scanDevice = {
                                snap = snapshot()
                                shizukuStatus = ShizukuShell.status()
                            }
                        )
                        1 -> ImsScreen(
                            raw = raw,
                            diagnosis = diagnosis,
                            status = shizukuStatus,
                            scanning = scanning,
                            onIms = { execute("IMS SERVICE", { ShizukuShell.collectImsDump() }) },
                            onRegistry = { execute("TELEPHONY REGISTRY", { ShizukuShell.collectTelephonyRegistry() }) },
                            onDeep = { execute("DEEP IMS", { ShizukuShell.collectAll() }) }
                        )
                        2 -> CarrierScreen(
                            raw = raw,
                            status = shizukuStatus,
                            scanning = scanning,
                            onCarrier = { execute("CARRIER CONFIG", { ShizukuShell.collectCarrierConfig() }, 2) }
                        )
                        3 -> ToolsScreen(
                            status = shizukuStatus,
                            scanning = scanning,
                            onTool = { name, task -> execute(name, task, 1) }
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun Header(status: String, scanning: Boolean, lastTool: String) {
        Column(
            modifier = Modifier.fillMaxWidth().background(Panel).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(46.dp).background(Accent, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                    Text("🍎", style = MaterialTheme.typography.titleLarge)
                }
                Spacer(Modifier.width(11.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("APPLE SEED", color = TextMain, fontWeight = FontWeight.ExtraBold)
                    Text("IMS / VoLTE DIAGNOSTIC WORKSTATION", color = TextMuted, style = MaterialTheme.typography.labelSmall)
                }
                Box(modifier = Modifier.background(Color(0xFF20252C), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 5.dp)) {
                    Text("VIP", color = Accent, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.labelSmall)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().background(Color(0xFF171C22), RoundedCornerShape(10.dp)).border(1.dp, Line, RoundedCornerShape(10.dp)).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("●", color = if (status.contains("ONLINE")) Good else Warn, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(status, color = if (status.contains("ONLINE")) Good else Warn, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                    Text(if (scanning) "ĐANG THU THẬP: $lastTool" else "LAST: $lastTool", color = TextMuted, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }

    @Composable
    private fun OverviewScreen(
        snapshot: DeviceSnapshot?, permission: Boolean, shizukuStatus: String,
        requestPermission: () -> Unit, connectShizuku: () -> Unit, scanDevice: () -> Unit
    ) {
        SectionTitle("SYSTEM BASELINE", "Đọc thiết bị trước, sau đó mới phân tích IMS")
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
        InfoCard("ACCESS", "◆") {
            StatusRow("READ_PHONE_STATE", if (permission) "GRANTED" else "NOT GRANTED", if (permission) Good else Warn)
            StatusRow("SHIZUKU", shizukuStatus, if (shizukuStatus.contains("ONLINE")) Good else Warn)
            Spacer(Modifier.height(5.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) {
                if (!permission) PrimaryButton("CẤP QUYỀN", requestPermission, Modifier.weight(1f))
                OutlineButton("KẾT NỐI SHIZUKU", connectShizuku, Modifier.weight(1f))
            }
            Spacer(Modifier.height(2.dp))
            PrimaryButton("SCAN DEVICE  →", scanDevice, Modifier.fillMaxWidth())
        }
        EvidenceNote("EVIDENCE FIRST • DATA ≠ IMS REGISTERED ≠ VoLTE CALL")
    }

    @Composable
    private fun ImsScreen(
        raw: String, diagnosis: String, status: String, scanning: Boolean,
        onIms: () -> Unit, onRegistry: () -> Unit, onDeep: () -> Unit
    ) {
        SectionTitle("IMS DIAGNOSTICS", "3 tầng: service → registry → full evidence")
        InfoCard("SCAN ENGINE", "◎") {
            StatusRow("ENGINE", status, if (status.contains("ONLINE")) Good else Warn)
            Spacer(Modifier.height(7.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) {
                OutlineButton("IMS SERVICE", onIms, Modifier.weight(1f), scanning)
                OutlineButton("REGISTRY", onRegistry, Modifier.weight(1f), scanning)
            }
            Spacer(Modifier.height(7.dp))
            PrimaryButton(if (scanning) "SCANNING..." else "DEEP SCAN  •  FULL EVIDENCE", onDeep, Modifier.fillMaxWidth(), scanning)
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
            InfoCard("RAW EVIDENCE", "⌁") {
                Text(raw.takeLast(12000), color = Color(0xFF9EABB8), style = MaterialTheme.typography.bodySmall)
            }
        }
        EvidenceNote("ENGINE READS EVIDENCE • NO BLIND PATCH")
    }

    @Composable
    private fun CarrierScreen(raw: String, status: String, scanning: Boolean, onCarrier: () -> Unit) {
        SectionTitle("CARRIER PROFILE", "Đọc cấu hình thật trước khi kết luận hoặc patch")
        InfoCard("CARRIER CONFIG ENGINE", "▣") {
            StatusRow("SHIZUKU", status, if (status.contains("ONLINE")) Good else Warn)
            Spacer(Modifier.height(6.dp))
            Text("READ-ONLY • profile được thu thập nguyên trạng.", color = TextMuted, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(9.dp))
            PrimaryButton(if (scanning) "READING..." else "READ CARRIER CONFIG  →", onCarrier, Modifier.fillMaxWidth(), scanning)
        }
        if (raw.isNotBlank()) {
            InfoCard("CONFIG EVIDENCE", "⌁") {
                Text(raw.takeLast(12000), color = Color(0xFF9EABB8), style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    @Composable
    private fun ToolsScreen(status: String, scanning: Boolean, onTool: (String, () -> String) -> Unit) {
        SectionTitle("PRO TECH TOOLS", "Thu thập từng lớp evidence như một workstation thực thụ")
        InfoCard("ENGINE", "⚙") {
            StatusRow("SHIZUKU", status, if (status.contains("ONLINE")) Good else Warn)
            Spacer(Modifier.height(8.dp))
            PrimaryButton("FULL IMS EVIDENCE", { onTool("FULL IMS", { ShizukuShell.collectAll() }) }, Modifier.fillMaxWidth(), scanning)
        }
        InfoCard("QUICK TOOLS", "⚡") {
            ToolButton("IMS SERVICE", "dumpsys ims") { onTool("IMS SERVICE", { ShizukuShell.collectImsDump() }) }
            ToolButton("TELEPHONY REGISTRY", "dumpsys telephony.registry") { onTool("REGISTRY", { ShizukuShell.collectTelephonyRegistry() }) }
            ToolButton("CARRIER CONFIG", "dumpsys carrier_config") { onTool("CARRIER CONFIG", { ShizukuShell.collectCarrierConfig() }) }
            ToolButton("RADIO", "dumpsys radio") { onTool("RADIO", { ShizukuShell.collectRadioLogs() }) }
            ToolButton("WIFI STATE", "dumpsys wifi") { onTool("WIFI", { ShizukuShell.run("dumpsys wifi") }) }
            ToolButton("SYSTEM PROPS", "getprop") { onTool("GETPROP", { ShizukuShell.run("getprop") }) }
        }
        InfoCard("WORKFLOW", "◇") {
            WorkflowRow("01", "DEVICE / SIM / NETWORK", "BASELINE")
            WorkflowRow("02", "IMS SERVICE / REGISTRY", "STATE")
            WorkflowRow("03", "CARRIER CONFIG", "PROFILE")
            WorkflowRow("04", "RADIO / WIFI / PROPS", "EVIDENCE")
            WorkflowRow("05", "CLASSIFY + CONCLUDE", "ANALYZE")
        }
    }

    @Composable
    private fun ToolButton(title: String, command: String, action: () -> Unit) {
        OutlinedButton(
            onClick = action,
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextMain)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                Text(command, color = TextMuted, style = MaterialTheme.typography.labelSmall)
            }
        }
    }

    @Composable
    private fun SectionTitle(title: String, subtitle: String) {
        Column(modifier = Modifier.padding(horizontal = 15.dp, vertical = 14.dp)) {
            Text(title, color = TextMain, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(3.dp))
            Text(subtitle, color = TextMuted, style = MaterialTheme.typography.bodySmall)
        }
    }

    @Composable
    private fun InfoCard(title: String, icon: String, content: @Composable () -> Unit) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 5.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Panel2),
            border = androidx.compose.foundation.BorderStroke(1.dp, Line)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(icon, color = Accent, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    Text(title, color = Color(0xFFAAB6C2), fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.labelMedium)
                }
                HorizontalDivider(color = Line)
                content()
            }
        }
    }

    @Composable
    private fun DataRow(label: String, value: String, valueColor: Color = TextMain) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = TextMuted, style = MaterialTheme.typography.bodySmall)
            Text(value, color = valueColor, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
        }
    }

    @Composable
    private fun StatusRow(label: String, value: String, color: Color) {
        Row(
            modifier = Modifier.fillMaxWidth().background(Color(0xFF0E1217), RoundedCornerShape(9.dp)).padding(horizontal = 10.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = TextMuted, style = MaterialTheme.typography.labelSmall)
            Text("●  $value", color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
        }
    }

    @Composable
    private fun WorkflowRow(no: String, name: String, tag: String) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(no, color = Accent, fontWeight = FontWeight.ExtraBold, modifier = Modifier.width(28.dp), style = MaterialTheme.typography.labelSmall)
            Text(name, color = TextMain, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            Text(tag, color = TextMuted, style = MaterialTheme.typography.labelSmall)
        }
    }

    @Composable
    private fun PrimaryButton(text: String, action: () -> Unit, modifier: Modifier, disabled: Boolean = false) {
        Button(
            onClick = action,
            enabled = !disabled,
            modifier = modifier.height(46.dp),
            shape = RoundedCornerShape(11.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White)
        ) { Text(text, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium) }
    }

    @Composable
    private fun OutlineButton(text: String, action: () -> Unit, modifier: Modifier, disabled: Boolean = false) {
        OutlinedButton(
            onClick = action,
            enabled = !disabled,
            modifier = modifier.height(46.dp),
            shape = RoundedCornerShape(11.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextMain)
        ) { Text(text, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall) }
    }

    @Composable
    private fun EvidenceNote(text: String) {
        Text(text, color = TextMuted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.fillMaxWidth().padding(12.dp))
    }

    private fun stateColor(state: String): Color = when {
        state.contains("REGISTER", true) || state.contains("ENABLED", true) || state == "OK" -> Good
        state.contains("FAIL", true) || state.contains("ERROR", true) -> Bad
        else -> Warn
    }
}

data class DeviceSnapshot(
    val manufacturer: String,
    val model: String,
    val android: String,
    val sdk: Int,
    val carrier: String,
    val mccmnc: String,
    val dataNetwork: String,
    val voiceNetwork: String,
    val simCount: Int
)
