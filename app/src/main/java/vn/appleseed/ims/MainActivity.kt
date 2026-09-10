package vn.appleseed.ims

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { App() } }

    private fun phonePermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    private fun snapshot(): DeviceSnapshot {
        val tm = getSystemService(TelephonyManager::class.java)
        val sm = getSystemService(SubscriptionManager::class.java)
        fun safe(block: () -> String) = runCatching(block).getOrDefault("--").ifBlank { "--" }
        fun nt(type: Int) = when (type) {
            TelephonyManager.NETWORK_TYPE_LTE -> "LTE / 4G"
            TelephonyManager.NETWORK_TYPE_NR -> "NR / 5G"
            TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS / 3G"
            TelephonyManager.NETWORK_TYPE_GSM -> "GSM / 2G"
            else -> "Unknown ($type)"
        }
        return DeviceSnapshot(Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE, Build.VERSION.SDK_INT,
            safe { tm.networkOperatorName }, safe { tm.networkOperator },
            nt(runCatching { tm.dataNetworkType }.getOrDefault(0)),
            nt(runCatching { tm.voiceNetworkType }.getOrDefault(0)),
            runCatching { sm.activeSubscriptionInfoList?.size ?: 0 }.getOrDefault(0))
    }

    @Composable private fun App() {
        var tab by remember { mutableIntStateOf(0) }
        var snap by remember { mutableStateOf<DeviceSnapshot?>(null) }
        var raw by remember { mutableStateOf("") }
        var diagnosis by remember { mutableStateOf("Chưa chạy chẩn đoán.") }
        var shizuku by remember { mutableStateOf(shizukuText()) }
        var permission by remember { mutableStateOf(phonePermission()) }
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> permission = ok; if (ok) snap = snapshot() }

        MaterialTheme {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("🍎 APPLE SEED IMS", style = MaterialTheme.typography.headlineSmall)
                        Text("Professional IMS / VoLTE diagnostic workstation")
                    }
                    TabRow(selectedTabIndex = tab) {
                        listOf("TỔNG QUAN", "IMS", "CARRIER", "TOOLS").forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
                    }
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        when (tab) {
                            0 -> Overview(snap, permission, launcher, shizuku) { snap = snapshot(); shizuku = shizukuText() }
                            1 -> ImsPanel(raw, diagnosis, shizuku) { action ->
                                raw = when (action) { "IMS" -> ShizukuShell.collectImsDump(); "REG" -> ShizukuShell.collectTelephonyRegistry(); else -> ShizukuShell.collectAll() }
                                diagnosis = ImsDiagnostics.classify(ImsDiagnostics.parseDump(raw))
                            }
                            2 -> CarrierPanel(raw) { raw = ShizukuShell.collectCarrierConfig() }
                            3 -> ToolsPanel(shizuku) { shizuku = shizukuText(); raw = ShizukuShell.collectAll(); diagnosis = ImsDiagnostics.classify(ImsDiagnostics.parseDump(raw)) }
                        }
                        Text("Evidence-first: không kết luận VoLTE chỉ dựa vào có 4G. Cần phân biệt data, IMS registration và voice call.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    @Composable private fun Overview(s: DeviceSnapshot?, permission: Boolean, launcher: androidx.activity.result.ActivityResultLauncher<String>, shizuku: String, refresh: () -> Unit) {
        Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { Text("DEVICE", style = MaterialTheme.typography.titleMedium); Text("Model: ${s?.model ?: Build.MODEL}"); Text("Manufacturer: ${s?.manufacturer ?: Build.MANUFACTURER}"); Text("Android: ${s?.android ?: Build.VERSION.RELEASE}"); Text("SDK: ${s?.sdk ?: Build.VERSION.SDK_INT}") } }
        Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { Text("SIM / NETWORK", style = MaterialTheme.typography.titleMedium); Text("SIM: ${s?.simCount ?: "--"}"); Text("Carrier: ${s?.carrier ?: "--"}"); Text("MCC/MNC: ${s?.mccmnc ?: "--"}"); Text("Data: ${s?.dataNetwork ?: "--"}"); Text("Voice: ${s?.voiceNetwork ?: "--"}") } }
        Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("ACCESS", style = MaterialTheme.typography.titleMedium); Text(if (permission) "READ_PHONE_STATE: OK" else "READ_PHONE_STATE: CHƯA CẤP"); Text(shizuku); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { if (!permission) Button({ launcher.launch(Manifest.permission.READ_PHONE_STATE) }) { Text("CẤP QUYỀN") }; Button({ refresh() }, Modifier.weight(1f)) { Text("QUÉT THIẾT BỊ") } } } }
    }

    @Composable private fun ImsPanel(raw: String, diagnosis: String, shizuku: String, run: (String) -> Unit) {
        Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("IMS DIAGNOSTICS", style = MaterialTheme.typography.titleMedium); Text(shizuku); Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Button({ run("IMS") }, Modifier.weight(1f)) { Text("IMS") }; Button({ run("REG") }, Modifier.weight(1f)) { Text("REGISTRY") }; Button({ run("ALL") }, Modifier.weight(1f)) { Text("DEEP SCAN") } } } }
        if (raw.isNotBlank()) Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { ImsDiagnostics.parseDump(raw).forEach { StatusRow(it.name, it.state) }; HorizontalDivider(); Text(diagnosis); Text(raw.takeLast(8000), style = MaterialTheme.typography.bodySmall) } }
        else Card { Column(Modifier.padding(16.dp)) { Text("IMS Registration: UNKNOWN"); Text("VoLTE: UNKNOWN"); Text("VoWiFi: UNKNOWN"); Text("VoNR: UNKNOWN") } }
    }

    @Composable private fun CarrierPanel(raw: String, run: () -> Unit) {
        Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("CARRIER CONFIG", style = MaterialTheme.typography.titleMedium); Text("Đọc cấu hình hiện tại trước khi nghĩ tới override."); Button(run, Modifier.fillMaxWidth()) { Text("ĐỌC CARRIER CONFIG") } } }
        if (raw.isNotBlank()) Card { Column(Modifier.padding(16.dp)) { Text(raw.takeLast(10000), style = MaterialTheme.typography.bodySmall) } }
    }

    @Composable private fun ToolsPanel(shizuku: String, run: () -> Unit) {
        Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("TECH TOOLS", style = MaterialTheme.typography.titleMedium); Text(shizuku); Button(run, Modifier.fillMaxWidth()) { Text("THU THẬP FULL IMS DUMP") }; Text("V3 sẽ thêm profile carrier, snapshot/rollback và apply có kiểm chứng. Không thực hiện hidden API override mù.", style = MaterialTheme.typography.bodySmall) } }
    }

    @Composable private fun StatusRow(a: String, b: String) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(a); Text(b) } }
    private fun shizukuText() = runCatching { when { !Shizuku.pingBinder() -> "Shizuku: CHƯA KẾT NỐI"; Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> "Shizuku: CONNECTED + AUTHORIZED"; else -> "Shizuku: CONNECTED, CHƯA CẤP QUYỀN" } }.getOrElse { "Shizuku: unavailable" }
}

data class DeviceSnapshot(val manufacturer: String, val model: String, val android: String, val sdk: Int, val carrier: String, val mccmnc: String, val dataNetwork: String, val voiceNetwork: String, val simCount: Int)
