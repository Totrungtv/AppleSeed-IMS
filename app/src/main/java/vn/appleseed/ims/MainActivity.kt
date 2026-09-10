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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppleSeedImsApp() }
    }

    private fun phonePermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun snapshot(): DeviceSnapshot {
        val tm = getSystemService(TelephonyManager::class.java)
        val sm = getSystemService(SubscriptionManager::class.java)

        fun safe(block: () -> String): String =
            runCatching { block() }.getOrDefault("--").ifBlank { "--" }

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
        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            permission = granted
            if (granted) snap = snapshot()
        }

        val tabs = listOf("TỔNG QUAN", "IMS", "CARRIER", "TOOLS")

        MaterialTheme {
            Scaffold(
                containerColor = Color(0xFF0B0D10),
                bottomBar = {
                    NavigationBar(
                        modifier = Modifier.navigationBarsPadding(),
                        containerColor = Color(0xFF12161B)
                    ) {
                        tabs.forEachIndexed { index, title ->
                            NavigationBarItem(
                                selected = tab == index,
                                onClick = { tab = index },
                                icon = { Text(listOf("⌂", "◎", "▣", "⚙")[index]) },
                                label = { Text(title, maxLines = 1) }
                            )
                        }
                    }
                }
            ) { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
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
                            connectShizuku = {
                                shizukuStatus = ShizukuShell.connect()
                            }
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

                        2 -> CarrierScreen(
                            raw = raw,
                            run = {
                                raw = ShizukuShell.collectCarrierConfig()
                                shizukuStatus = ShizukuShell.status()
                            }
                        )

                        3 -> ToolsScreen(
                            status = shizukuStatus,
                            run = {
                                raw = ShizukuShell.collectAll()
                                diagnosis = ImsDiagnostics.classify(ImsDiagnostics.parseDump(raw))
                                shizukuStatus = ShizukuShell.status()
                                tab = 1
                            }
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun Header(status: String) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF11151A))
                .padding(horizontal = 18.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(Color(0xFFE9EDF2)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("🍎", style = MaterialTheme.typography.titleLarge)
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("APPLE SEED", fontWeight = FontWeight.Bold, color = Color.White)
                    Text("IMS DIAGNOSTIC WORKSTATION", color = Color(0xFF9AA5B1), style = MaterialTheme.typography.labelSmall)
                }
            }
            StatusPill(status)
        }
    }

    @Composable
    private fun StatusPill(status: String) {
        val good = status.contains("ONLINE")
        val textColor = if (good) Color(0xFF6EE7B7) else Color(0xFFFFC66D)
        Text(
            text = "●  $status",
            color = textColor,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1A2027))
                .padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }

    @Composable
    private fun OverviewScreen(
        snapshot: DeviceSnapshot?,
        permission: Boolean,
        shizukuStatus: String,
        requestPermission: () -> Unit,
        scan: () -> Unit,
        connectShizuku: () -> Unit
    ) {
        SectionTitle("DEVICE OVERVIEW", "Thông tin nền trước khi phân tích IMS")
        InfoCard("THIẾT BỊ") {
            DataRow("Model", snapshot?.model ?: Build.MODEL)
            DataRow("Hãng", snapshot?.manufacturer ?: Build.MANUFACTURER)
            DataRow("Android", snapshot?.android ?: Build.VERSION.RELEASE)
            DataRow("SDK", snapshot?.sdk?.toString() ?: Build.VERSION.SDK_INT.toString())
        }
        InfoCard("SIM / NETWORK") {
            DataRow("SIM active", snapshot?.simCount?.toString() ?: "--")
            DataRow("Carrier", snapshot?.carrier ?: "--")
            DataRow("MCC/MNC", snapshot?.mccmnc ?: "--")
            DataRow("Data", snapshot?.dataNetwork ?: "--")
            DataRow("Voice", snapshot?.voiceNetwork ?: "--")
        }
        InfoCard("ACCESS") {
            DataRow("READ_PHONE_STATE", if (permission) "OK" else "CHƯA CẤP")
            DataRow("Shizuku", shizukuStatus)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!permission) {
                    Button(onClick = requestPermission, modifier = Modifier.weight(1f)) {
                        Text("CẤP QUYỀN")
                    }
                }
                OutlinedButton(onClick = connectShizuku, modifier = Modifier.weight(1f)) {
                    Text("KẾT NỐI SHIZUKU")
                }
            }
            Spacer(Modifier.height(4.dp))
            Button(onClick = scan, modifier = Modifier.fillMaxWidth()) {
                Text("QUÉT THIẾT BỊ")
            }
        }
        EvidenceNote()
    }

    @Composable
    private fun ImsScreen(
        raw: String,
        diagnosis: String,
        shizukuStatus: String,
        runScan: (String) -> Unit
    ) {
        SectionTitle("IMS DIAGNOSTICS", "Tách data network khỏi IMS registration và voice")
        InfoCard("IMS CONTROL") {
            DataRow("Shizuku", shizukuStatus)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                SmallAction("IMS", { runScan("IMS") }, Modifier.weight(1f))
                SmallAction("REGISTRY", { runScan("REG") }, Modifier.weight(1f))
                SmallAction("DEEP SCAN", { runScan("ALL") }, Modifier.weight(1f))
            }
        }

        if (raw.isBlank()) {
            InfoCard("IMS STATUS") {
                DataRow("IMS Registration", "UNKNOWN")
                DataRow("VoLTE", "UNKNOWN")
                DataRow("VoWiFi", "UNKNOWN")
                DataRow("VoNR", "UNKNOWN")
            }
        } else {
            InfoCard("EVIDENCE") {
                ImsDiagnostics.parseDump(raw).forEach { result ->
                    DataRow(result.name, result.state)
                }
                Divider(modifier = Modifier.padding(vertical = 8.dp))
                Text(diagnosis, color = Color(0xFFD9E2EC))
            }
            InfoCard("RAW DUMP") {
                Text(
                    raw.takeLast(9000),
                    color = Color(0xFF9FB0C0),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        EvidenceNote()
    }

    @Composable
    private fun CarrierScreen(raw: String, run: () -> Unit) {
        SectionTitle("CARRIER CONFIG", "Đọc cấu hình hiện tại trước khi thay đổi bất kỳ profile nào")
        InfoCard("CARRIER PROFILE") {
            Text(
                "Apple Seed chỉ đọc evidence trước. Không override mù.",
                color = Color(0xFFAAB7C4)
            )
            Spacer(Modifier.height(10.dp))
            Button(onClick = run, modifier = Modifier.fillMaxWidth()) {
                Text("ĐỌC CARRIER CONFIG")
            }
        }
        if (raw.isNotBlank()) {
            InfoCard("CONFIG DUMP") {
                Text(
                    raw.takeLast(10000),
                    color = Color(0xFF9FB0C0),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }

    @Composable
    private fun ToolsScreen(status: String, run: () -> Unit) {
        SectionTitle("TECH TOOLS", "Bộ công cụ thu thập evidence cho kỹ thuật viên")
        InfoCard("SHIZUKU") {
            DataRow("Status", status)
            Spacer(Modifier.height(8.dp))
            Button(onClick = run, modifier = Modifier.fillMaxWidth()) {
                Text("THU THẬP FULL IMS DUMP")
            }
        }
        InfoCard("WORKFLOW") {
            DataRow("01", "Device / SIM / Network")
            DataRow("02", "IMS registration")
            DataRow("03", "Carrier Config")
            DataRow("04", "Raw evidence")
            DataRow("05", "Technical conclusion")
        }
    }

    @Composable
    private fun SectionTitle(title: String, subtitle: String) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, color = Color(0xFF8996A3), style = MaterialTheme.typography.bodySmall)
        }
    }

    @Composable
    private fun InfoCard(title: String, content: @Composable ColumnScope.() -> Unit) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 5.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF151A20))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
                content = {
                    Text(title, color = Color(0xFF8EA2B5), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    content()
                }
            )
        }
    }

    @Composable
    private fun DataRow(label: String, value: String) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = Color(0xFFAAB7C4))
            Text(value, color = Color.White, fontWeight = FontWeight.Medium)
        }
    }

    @Composable
    private fun SmallAction(text: String, action: () -> Unit, modifier: Modifier) {
        OutlinedButton(onClick = action, modifier = modifier) {
            Text(text, style = MaterialTheme.typography.labelSmall)
        }
    }

    @Composable
    private fun EvidenceNote() {
        TextButton(
            onClick = {},
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 2.dp)
        ) {
            Text(
                "Evidence-first • 4G data ≠ IMS Registered ≠ VoLTE call",
                color = Color(0xFF718191),
                style = MaterialTheme.typography.labelSmall
            )
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
