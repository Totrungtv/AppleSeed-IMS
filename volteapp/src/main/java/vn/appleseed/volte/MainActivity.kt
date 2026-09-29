package vn.appleseed.volte

import android.content.ComponentName
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

    private fun write(key: String, value: Boolean): Boolean =
        Settings.Global.putInt(contentResolver, key, if (value) 1 else 0)

    private fun setVolte(enabled: Boolean): Boolean {
        val results = listOf(
            write(KEY_VOLTE_VT, enabled),
            write(KEY_ENHANCED_4G, enabled),
            write(KEY_VOLTE, enabled),
            write(KEY_CARRIER_VT, enabled)
        )
        val readBackMatches = listOf(
            read(KEY_VOLTE_VT),
            read(KEY_ENHANCED_4G),
            read(KEY_VOLTE),
            read(KEY_CARRIER_VT)
        ).all { it == enabled }
        return results.all { it } && readBackMatches
    }

    private fun openVolteSettings() {
        // Samsung/Android không dùng một Activity duy nhất cho mọi phiên bản.
        // Thử màn hình Mobile Network cụ thể trước, sau đó fallback về Settings chuẩn.
        val candidates = listOf(
            Intent().setComponent(
                ComponentName("com.android.phone", "com.android.phone.settings.MobileNetworkSettings")
            ),
            Intent().setComponent(
                ComponentName("com.android.phone", "com.android.phone.settings.Settings$MobileNetworkSettingsActivity")
            ),
            Intent("android.settings.NETWORK_OPERATOR_SETTINGS"),
            Intent(Settings.ACTION_WIRELESS_SETTINGS)
        )

        for (intent in candidates) {
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
                return
            }
        }
        throw IllegalStateException("ROM không cung cấp màn hình Mobile Network/VoLTE")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var enabled by remember { mutableStateOf(allEnabled()) }
            var status by remember {
                mutableStateOf(if (enabled) "Đã đọc thấy đủ 4 cờ đang bật." else "Chưa bật đủ 4 cờ VoLTE.")
            }

            MaterialTheme {
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("🍎 Apple Seed VoLTE", style = MaterialTheme.typography.headlineMedium)
                    Text("Hai chức năng riêng: điều khiển cờ và mở Cài đặt mạng để tự thao tác.")

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("CỜ VoLTE (ADB/QUYỀN HỆ THỐNG)", style = MaterialTheme.typography.titleMedium)
                                Text(if (enabled) "ĐỦ 4 CỜ ĐANG BẬT" else "CHƯA ĐỦ 4 CỜ BẬT")
                            }
                            Switch(
                                checked = enabled,
                                onCheckedChange = { wantEnabled ->
                                    runCatching {
                                        val accepted = setVolte(wantEnabled)
                                        enabled = allEnabled()
                                        status = when {
                                            accepted && enabled == wantEnabled ->
                                                "Đã ghi và đọc lại đủ 4 cờ: " + if (wantEnabled) "BẬT." else "TẮT."
                                            else ->
                                                "Không xác nhận được đủ 4 cờ. Máy có thể từ chối quyền ghi hoặc không hỗ trợ các cờ này."
                                        }
                                    }.onFailure { e ->
                                        enabled = allEnabled()
                                        status = if (e is SecurityException) {
                                            "Không đủ quyền ghi cờ hệ thống. Cần quyền phù hợp; quyền trong Manifest không tự cấp quyền."
                                        } else {
                                            "Không thể đổi cờ: " + (e.message ?: e.javaClass.simpleName)
                                        }
                                    }
                                }
                            )
                        }
                    }

                    Text(status)
                    Text("Trạng thái cờ hiện tại:", style = MaterialTheme.typography.titleMedium)
                    Text("volte_vt_enabled = " + read(KEY_VOLTE_VT))
                    Text("enhanced_4g_mode_enabled = " + read(KEY_ENHANCED_4G))
                    Text("volte_enabled = " + read(KEY_VOLTE))
                    Text("carrier_vt_enabled = " + read(KEY_CARRIER_VT))

                    Button(
                        onClick = {
                            enabled = allEnabled()
                            status = if (enabled) "Đã đọc thấy đủ 4 cờ đang bật." else "Chưa bật đủ 4 cờ VoLTE."
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("KIỂM TRA CỜ") }

                    Divider()

                    Text("CÀI ĐẶT VoLTE CỦA ANDROID", style = MaterialTheme.typography.titleMedium)
                    Text("Nút này chỉ mở Cài đặt mạng. Ông tự tìm và bật/tắt VoLTE trên máy; APK không tự đổi công tắc hệ thống ở đây.")

                    Button(
                        onClick = {
                            runCatching {
                                openVolteSettings()
                                status = "Đã mở màn hình mạng/VoLTE của máy."
                            }.onFailure { e ->
                                status = "Không mở được Cài đặt VoLTE: " + (e.message ?: e.javaClass.simpleName)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("📶 MỞ CÀI ĐẶT VoLTE") }

                    Text(
                        "Lưu ý: ghi được cờ không chứng minh VoLTE hoạt động. IMS thực tế phụ thuộc SIM, nhà mạng, cấu hình nhà mạng, provisioning và modem.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}
