package vn.appleseed.volte

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private const val KEY_VOLTE_VT = "volte_vt_enabled"
private const val KEY_ENHANCED_4G = "enhanced_4g_mode_enabled"
private const val KEY_VOLTE = "volte_enabled"
private const val KEY_CARRIER_VT = "carrier_vt_enabled"

class MainActivity : ComponentActivity() {
    private fun read(key: String): Boolean =
        runCatching { Settings.Global.getInt(contentResolver, key, 0) == 1 }.getOrDefault(false)

    private fun allEnabled() =
        read(KEY_VOLTE_VT) && read(KEY_ENHANCED_4G) && read(KEY_VOLTE) && read(KEY_CARRIER_VT)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var enabled by remember { mutableStateOf(allEnabled()) }
            var status by remember {
                mutableStateOf(if (enabled) "VoLTE flags đã được Apple Seed Tool bật." else "Chưa bật đủ cờ VoLTE.")
            }

            MaterialTheme {
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("🍎 Apple Seed VoLTE", style = MaterialTheme.typography.headlineMedium)
                    Text("Trạng thái VoLTE / 4G trên thiết bị.")

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("VoLTE", style = MaterialTheme.typography.titleLarge)
                                Text(if (enabled) "ĐANG BẬT" else "CHƯA BẬT ĐỦ")
                            }
                            Switch(
                                checked = enabled,
                                onCheckedChange = {
                                    status = "Hãy dùng Apple Seed Android Service Center → VOLTE 1-CLICK để áp dụng. APK không tự cấp WRITE_SECURE_SETTINGS."
                                }
                            )
                        }
                    }

                    Text(status)
                    Text("Cờ hiện tại:", style = MaterialTheme.typography.titleMedium)
                    Text("volte_vt_enabled = " + read(KEY_VOLTE_VT))
                    Text("enhanced_4g_mode_enabled = " + read(KEY_ENHANCED_4G))
                    Text("volte_enabled = " + read(KEY_VOLTE))
                    Text("carrier_vt_enabled = " + read(KEY_CARRIER_VT))

                    Button(
                        onClick = {
                            enabled = allEnabled()
                            status = if (enabled) "Đã bật đủ 4 cờ VoLTE." else "Chưa bật đủ 4 cờ VoLTE."
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("KIỂM TRA LẠI") }

                    Button(
                        onClick = { startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("MỞ CÀI ĐẶT MẠNG") }

                    Text(
                        "Lưu ý: các cờ hệ thống không tự chứng minh IMS đã đăng ký. VoLTE còn phụ thuộc SIM, nhà mạng, carrier config, provisioning và modem.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}
