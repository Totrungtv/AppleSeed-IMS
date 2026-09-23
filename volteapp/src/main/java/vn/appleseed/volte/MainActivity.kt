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
        try { Settings.Global.getInt(contentResolver, key, 0) == 1 } catch (_: Exception) { false }

    private fun write(key: String, value: Boolean): Boolean =
        try { Settings.Global.putInt(contentResolver, key, if (value) 1 else 0) }
        catch (_: SecurityException) { false }

    private fun enabled(): Boolean =
        read(KEY_VOLTE_VT) || read(KEY_ENHANCED_4G) || read(KEY_VOLTE) || read(KEY_CARRIER_VT)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var on by remember { mutableStateOf(enabled()) }
            var status by remember { mutableStateOf("Apple Seed VoLTE • Sẵn sàng") }

            MaterialTheme {
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("🍎 Apple Seed VoLTE", style = MaterialTheme.typography.headlineMedium)
                    Text("Điều khiển cờ VoLTE / 4G nâng cao trên thiết bị đã được cấp quyền ADB.")

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("VoLTE", style = MaterialTheme.typography.titleLarge)
                                Text(if (on) "ĐANG BẬT" else "ĐANG TẮT")
                            }
                            Switch(checked = on, onCheckedChange = {
                                val ok = listOf(KEY_VOLTE_VT, KEY_ENHANCED_4G, KEY_VOLTE, KEY_CARRIER_VT).all { key -> write(key, it) }
                                if (ok) {
                                    on = it
                                    status = if (it) "Đã bật 4 cờ VoLTE" else "Đã tắt 4 cờ VoLTE"
                                } else {
                                    status = "Chưa có quyền WRITE_SECURE_SETTINGS. Hãy cài app từ Apple Seed Tool để tool tự cấp quyền ADB."
                                }
                            })
                        }
                    }

                    Text(status)
                    Text("Cờ hiện tại:", style = MaterialTheme.typography.titleMedium)
                    Text("volte_vt_enabled = ${read(KEY_VOLTE_VT)}")
                    Text("enhanced_4g_mode_enabled = ${read(KEY_ENHANCED_4G)}")
                    Text("volte_enabled = ${read(KEY_VOLTE)}")
                    Text("carrier_vt_enabled = ${read(KEY_CARRIER_VT)}")

                    Button(
                        onClick = { startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("MỞ CÀI ĐẶT MẠNG") }

                    Button(
                        onClick = { on = enabled(); status = "Đã đọc lại trạng thái" },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("KIỂM TRA LẠI") }

                    Text("Lưu ý: bật cờ không đảm bảo IMS đã đăng ký. VoLTE còn phụ thuộc SIM, nhà mạng, carrier config, provisioning và modem.")
                }
            }
        }
    }
}
