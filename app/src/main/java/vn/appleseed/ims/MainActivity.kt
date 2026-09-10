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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppleSeedIMSApp() }
    }

    private fun hasPhonePermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

    private fun readDevice(): DeviceSnapshot {
        val tm = getSystemService(TelephonyManager::class.java)
        val sm = getSystemService(SubscriptionManager::class.java)
        val carrier = runCatching { tm.networkOperatorName }.getOrDefault("--")
        val mccmnc = runCatching { tm.networkOperator }.getOrDefault("--")
        val data = runCatching { tm.dataNetworkType }
            .getOrDefault(TelephonyManager.NETWORK_TYPE_UNKNOWN)
        val voice = runCatching { tm.voiceNetworkType }
            .getOrDefault(TelephonyManager.NETWORK_TYPE_UNKNOWN)
        val sims = runCatching { sm.activeSubscriptionInfoList?.size ?: 0 }.getOrDefault(0)

        return DeviceSnapshot(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            android = Build.VERSION.RELEASE,
            sdk = Build.VERSION.SDK_INT,
            carrier = carrier.ifBlank { "--" },
            mccmnc = mccmnc.ifBlank { "--" },
            dataNetwork = networkName(data),
            voiceNetwork = networkName(voice),
            simCount = sims
        )
    }

    private fun networkName(type: Int): String = when (type) {
        TelephonyManager.NETWORK_TYPE_LTE -> "LTE / 4G"
        TelephonyManager.NETWORK_TYPE_NR -> "NR / 5G"
        TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS / 3G"
        TelephonyManager.NETWORK_TYPE_GSM -> "GSM / 2G"
        else -> "Unknown ($type)"
    }

    @Composable
    private fun AppleSeedIMSApp() {
        var snapshot by remember { mutableStateOf<DeviceSnapshot?>(null) }
        var permissionGranted by remember { mutableStateOf(hasPhonePermission()) }
        var shizukuState by remember { mutableStateOf(shizukuStatus()) }

        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            permissionGranted = granted
            if (granted) snapshot = readDevice()
        }

        MaterialTheme {
            Surface(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("🍎 APPLE SEED IMS", style = MaterialTheme.typography.headlineSmall)
                    Text("IMS / VoLTE diagnostic tool — V1 Test")

                    InfoCard("DEVICE") {
                        Text("Model: ${snapshot?.model ?: Build.MODEL}")
                        Text("Manufacturer: ${snapshot?.manufacturer ?: Build.MANUFACTURER}")
                        Text("Android: ${snapshot?.android ?: Build.VERSION.RELEASE}")
                        Text("SDK: ${snapshot?.sdk ?: Build.VERSION.SDK_INT}")
                    }

                    InfoCard("SIM / NETWORK") {
                        Text("SIM active: ${snapshot?.simCount ?: "--"}")
                        Text("Carrier: ${snapshot?.carrier ?: "--"}")
                        Text("MCC/MNC: ${snapshot?.mccmnc ?: "--"}")
                        Text("Data network: ${snapshot?.dataNetwork ?: "--"}")
                        Text("Voice network: ${snapshot?.voiceNetwork ?: "--"}")
                    }

                    InfoCard("IMS STATUS") {
                        StatusRow("IMS Registration", "Chưa đọc — V2")
                        StatusRow("VoLTE", "Chưa đọc — V2")
                        StatusRow("VoWiFi", "Chưa đọc — V2")
                        StatusRow("VoNR", "Chưa đọc — V2")
                    }

                    InfoCard("SHIZUKU") {
                        Text(shizukuState)
                        OutlinedButton(
                            onClick = { shizukuState = shizukuStatus() },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("KIỂM TRA SHIZUKU") }
                    }

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (!permissionGranted) {
                            Button(
                                onClick = { launcher.launch(Manifest.permission.READ_PHONE_STATE) },
                                modifier = Modifier.weight(1f)
                            ) { Text("CẤP QUYỀN") }
                        }

                        Button(
                            onClick = {
                                if (permissionGranted) snapshot = readDevice()
                                else launcher.launch(Manifest.permission.READ_PHONE_STATE)
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("KIỂM TRA") }
                    }

                    HorizontalDivider()
                    Text(
                        "V1 chỉ đọc dữ liệu. Chưa thay đổi Carrier Config, IMS hoặc VoLTE.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }

    @Composable
    private fun InfoCard(title: String, content: @Composable ColumnScope.() -> Unit) {
        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                content()
            }
        }
    }

    @Composable
    private fun StatusRow(label: String, value: String) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label)
            Text(value)
        }
    }

    private fun shizukuStatus(): String = runCatching {
        when {
            !Shizuku.pingBinder() -> "Chưa kết nối Shizuku"
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED ->
                "Shizuku: ĐÃ KẾT NỐI + ĐÃ CẤP QUYỀN"
            else -> "Shizuku: ĐÃ KẾT NỐI, CHƯA CẤP QUYỀN"
        }
    }.getOrElse { "Shizuku: chưa sẵn sàng" }
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
