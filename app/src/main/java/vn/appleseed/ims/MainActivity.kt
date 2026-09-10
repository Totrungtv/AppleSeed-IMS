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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material3.TextButton
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

private val Bg = Color(0xFF080A0D)
private val Panel = Color(0xFF11151A)
private val Panel2 = Color(0xFF161B21)
private val Line = Color(0xFF252C34)
private val TextMain = Color(0xFFF4F7FA)
private val TextMuted = Color(0xFF8793A0)
private val Accent = Color(0xFFFF6B35)
private val Good = Color(0xFF55E6A5)
private val Warn = Color(0xFFFFC857)

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
            else -> "Unknown ($type)"
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
        var diagnosis by remember { mutableStateOf("Chưa có phiên chẩn đoán.") }
        var permission by remember { mutableStateOf(phonePermission()) }
        var shizukuStatus by remember { mutableStateOf(ShizukuShell.status()) }
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            permission = granted
            if (granted) snap = snapshot()
        }
        val tabs = listOf("OVERVIEW", "IMS", "CARRIER", "TOOLS")
        val icons = listOf("⌂", "◎", "▣", "⚙")
        MaterialTheme {
            Scaffold(
                containerColor = Bg,
                bottomBar = {
                    NavigationBar(
                        modifier = Modifier.navigationBarsPadding(),
                        containerColor = Color(0xFF0D1014),
                        tonalElevation = 0.dp
                    ) {
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
                    Header(shizukuStatus)
                    when (tab) {
                        0 -> OverviewScreen(
                            snapshot = snap,
                            permission = permission,
                            shizukuStatus = shizukuStatus,
                            requestPermission = { launcher.launch(Manifest.permission.READ_PHONE_STATE) },
                            scan = {
                                if (permission) snap = snapshot()
                                shizukuStatus = ShizukuShell.status()
                            },
                            connectShizuku = { shizukuStatus = ShizukuShell.connect() }
                        )
                        1 -> ImsScreen(
                            raw = raw,
                            diagnosis = diagnosis,
                            shizukuStatus = shizukuStatus,
                            runScan = { action ->
                                raw = when (action) {
                                    "IMS" -> ShizukuShell.collectImsDump()
                                    "REG" -> ShizukuShell.collectTelephonyRegistry()
                                    else -> ShizukuShell.collectAll()
                                }
                                diagnosis = ImsDiagnostics.classify(ImsDiagnostics.parseDump(raw))
                                shizukuStatus = ShizukuShell.status()
                            }
                        )
                        2 -> CarrierScreen(raw) {
                            raw = ShizukuShell.collectCarrierConfig()
                            shizukuStatus = ShizukuShell.status()
                        }
                        3 -> ToolsScreen(shizukuStatus) {
                            raw = ShizukuShell.collectAll()
                            diagnosis = ImsDiagnostics.classify(ImsDiagnostics.parseDump(raw))
                            shizukuStatus = ShizukuShell.status()
                            tab = 1
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Header(status: String) {
        Column(
            modifier = Modifier.fillMaxWidth().background(Panel).padding(horizontal = 18.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(48.dp).background(Accent, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                    Text("🍎", style = MaterialTheme.typography.titleLarge)
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("APPLE SEED", color = TextMain, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)
                    Text("IMS / VoLTE TECH WORKSTATION", color = TextMuted, style = MaterialTheme.typography.labelSmall)
                }
                Box(modifier = Modifier.background(Color(0xFF1D2229), RoundedCornerShape(10.dp)).padding(horizontal = 9.dp, vertical = 6.dp)) {
                    Text("VIP", color = Accent, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.labelSmall)
                }
            }
            StatusPill(status)
        }
    }

    @Composable
    private fun StatusPill(status: String) {
        val good = status.contains("ONLINE")
        val textColor = if (good) Good else Warn
        Row(
            modifier = Modifier.fillMaxWidth().background(Color(0xFF181D23), RoundedCornerShape(11.dp)).border(1.dp, Line, RoundedCornerShape(11.dp)).padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("●", color = textColor, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Text(status, color = textColor, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
        }
    }

    @Composable
    private fun OverviewScreen(
        snapshot: DeviceSnapshot?, permission: Boolean, shizukuStatus: String,
        requestPermission: () -> Unit, scan: () -> Unit, connectShizuku: () -> Unit
    ) {
        SectionTitle("DEVICE OVERVIEW", "Baseline trước khi đi sâu vào IMS / VoLTE")
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
        InfoCard("ACCESS CONTROL", "◆") {
            DataRow("PHONE STATE", if (permission) "GRANTED" else "NOT GRANTED", if (permission) Good else Warn)
            DataRow("SHIZUKU", shizukuStatus, if (shizukuStatus.contains("ONLINE")) Good else Warn)
            Spacer(Modifier.height(5.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                if (!permission) PrimaryButton("CẤP QUYỀN", requestPermission, Modifier.weight(1f))
                OutlineButton("KẾT NỐI SHIZUKU", connectShizuku, Modifier.weight(1f))
            }
            Spacer(Modifier.height(1.dp))
            PrimaryButton("QUÉT THIẾT BỊ  →", scan, Modifier.fillMaxWidth())
        }
        EvidenceNote()
    }

    @Composable
    private fun ImsScreen(raw: String, diagnosis: String, shizukuStatus: String, runScan: (String) -> Unit) {
        SectionTitle("IMS DIAGNOSTICS", "Tách DATA • IMS REGISTRATION • VoLTE CALL")
        InfoCard("SCAN ENGINE", "◎") {
            DataRow("ENGINE", shizukuStatus, if (shizukuStatus.contains("ONLINE")) Good else Warn)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) {
                OutlineButton("IMS", { runScan("IMS") }, Modifier.weight(1f))
                OutlineButton("REGISTRY", { runScan("REG") }, Modifier.weight(1f))
            }
            Spacer(Modifier.height(6.dp))
            PrimaryButton("DEEP SCAN  •  FULL EVIDENCE", { runScan("ALL") }, Modifier.fillMaxWidth())
        }
        if (raw.isBlank()) {
            InfoCard("IMS STATUS", "●") {
                StatusRow("IMS REGISTRATION", "UNKNOWN", Warn)
                StatusRow("VoLTE", "UNKNOWN", Warn)
                StatusRow("VoWiFi", "UNKNOWN", Warn)
                StatusRow("VoNR", "UNKNOWN", Warn)
            }
        } else {
            InfoCard("EVIDENCE RESULT", "✓") {
                ImsDiagnostics.parseDump(raw).forEach { result -> DataRow(result.name, result.state) }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = Line)
                Text(diagnosis, color = TextMain, style = MaterialTheme.typography.bodyMedium)
            }
            InfoCard("RAW DUMP", "⌁") {
                Text(raw.takeLast(9000), color = Color(0xFF9FB0C0), style = MaterialTheme.typography.bodySmall)
            }
        }
        EvidenceNote()
    }

    @Composable
    private fun CarrierScreen(raw: String, run: () -> Unit) {
        SectionTitle("CARRIER CONFIG", "Đọc profile thật trước khi kết luận hoặc patch")
        InfoCard("CARRIER PROFILE", "▣") {
            Text("READ-ONLY EVIDENCE", color = Good, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(5.dp))
            Text("Apple Seed không override mù. Thu thập cấu hình trước.", color = TextMuted, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(10.dp))
            PrimaryButton("ĐỌC CARRIER CONFIG  →", run, Modifier.fillMaxWidth())
        }
        if (raw.isNotBlank()) {
            InfoCard("CONFIG DUMP", "⌁") {
                Text(raw.takeLast(10000), color = Color(0xFF9FB0C0), style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    @Composable
    private fun ToolsScreen(status: String, run: () -> Unit) {
        SectionTitle("TECH TOOLS", "Bộ công cụ dành cho kỹ thuật viên board / software")
        InfoCard("SHIZUKU SHELL", "⚙") {
            DataRow("STATUS", status, if (status.contains("ONLINE")) Good else Warn)
            Spacer(Modifier.height(8.dp))
            PrimaryButton("THU THẬP FULL IMS DUMP  →", run, Modifier.fillMaxWidth())
        }
        InfoCard("DIAGNOSTIC PIPELINE", "◇") {
            WorkflowRow("01", "DEVICE / SIM / NETWORK", "BASELINE")
            WorkflowRow("02", "IMS REGISTRATION", "STATE")
            WorkflowRow("03", "CARRIER CONFIG", "PROFILE")
            WorkflowRow("04", "RAW EVIDENCE", "CAPTURE")
            WorkflowRow("05", "TECHNICAL CONCLUSION", "ANALYZE")
        }
    }

    @Composable
    private fun SectionTitle(title: String, subtitle: String) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 15.dp)) {
            Text(title, color = TextMain, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(3.dp))
            Text(subtitle, color = TextMuted, style = MaterialTheme.typography.bodySmall)
        }
    }

    @Composable
    private fun InfoCard(title: String, icon: String, content: @Composable ColumnScope.() -> Unit) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Panel2),
            border = androidx.compose.foundation.BorderStroke(1.dp, Line)
        ) {
            Column(modifier = Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(icon, color = Accent, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    Text(title, color = Color(0xFFA9B6C3), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.ExtraBold)
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
        Row(modifier = Modifier.fillMaxWidth().background(Color(0xFF101419), RoundedCornerShape(9.dp)).padding(horizontal = 10.dp, vertical = 9.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = TextMuted, style = MaterialTheme.typography.labelSmall)
            Text("●  $value", color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
        }
    }

    @Composable
    private fun WorkflowRow(no: String, name: String, tag: String) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(no, color = Accent, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(28.dp))
            Text(name, color = TextMain, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Text(tag, color = TextMuted, style = MaterialTheme.typography.labelSmall)
        }
    }

    @Composable
    private fun PrimaryButton(text: String, action: () -> Unit, modifier: Modifier) {
        Button(onClick = action, modifier = modifier.height(46.dp), shape = RoundedCornerShape(11.dp), colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White)) {
            Text(text, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
        }
    }

    @Composable
    private fun OutlineButton(text: String, action: () -> Unit, modifier: Modifier) {
        OutlinedButton(
            onClick = action,
            modifier = modifier.height(46.dp),
            shape = RoundedCornerShape(11.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextMain)
        ) {
            Text(text, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
        }
    }

    @Composable
    private fun EvidenceNote() {
        TextButton(onClick = {}, modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp)) {
            Text("APPLE SEED • EVIDENCE FIRST • DATA ≠ IMS REGISTERED ≠ VoLTE CALL", color = TextMuted, style = MaterialTheme.typography.labelSmall)
        }
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
