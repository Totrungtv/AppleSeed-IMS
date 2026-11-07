# APPLE SEED ROBOT V2.9 CLOUD CONNECT

## Tình trạng
Firmware dựa trên V2.8: giữ giao diện TFT/GT911, Wi-Fi AP nội bộ và UART với UNO; bổ sung Wi-Fi nhà (AP+STA), gọi Supabase Gateway để nhận lệnh và gửi telemetry.

## Cần chuẩn bị
- Arduino IDE.
- Board ESP32 Dev Module / đúng board đang dùng.
- Thư viện hiện tại: TFT_eSPI, TAMC_GT911.
- Cài thêm thư viện **ArduinoJson (Benoit Blanchon) phiên bản 6.x**.
- Không nối UART UNO vào TX0/RX0 trong lúc nạp code. UNO TX 5V phải qua cầu chia áp trước khi vào ESP32 RX.

## Cấu hình trước khi nạp
Mở file `AppleSeed_ESP32_V2_9_CLOUD_CONNECT.ino`, sửa 4 dòng:
- `HOME_WIFI_SSID`: tên Wi-Fi nhà.
- `HOME_WIFI_PASS`: mật khẩu Wi-Fi nhà.
- `ROBOT_DEVICE_ID`: đúng mã thiết bị đã ghép đôi trên web.
- `ROBOT_DEVICE_TOKEN`: token vừa tạo trên web (chỉ hiển thị một lần).

Không commit token thật hoặc mật khẩu Wi-Fi lên GitHub. Điền trực tiếp trong bản local trước khi nạp.

## Ghép đôi
1. Đăng nhập trang Robot AI VIP bằng tài khoản admin.
2. Nhập mã robot, ví dụ `APPLESEED_ROBOT_01`, rồi bấm Ghép đôi.
3. Sao chép token hiện ra ngay lúc đó; lưu an toàn.
4. Điền ID và token vào firmware local, cùng Wi-Fi nhà.
5. Nạp ESP32; robot phát AP `APPLE_SEED_ROBOT` như trước và đồng thời kết nối Wi-Fi nhà.
6. Web sẽ hiện ONLINE sau khi thiết bị gọi Gateway thành công.

## Lệnh đã ánh xạ
forward=F, backward=B, left=L, right=R, stop=S, turn_around=U.
Telemetry gửi mỗi 10 giây: pin, phần trăm pin, khoảng cách, mưa, vật cản.

## Giới hạn / kiểm thử
- Chưa biên dịch hoặc thử trên phần cứng trong phiên này.
- Chưa kiểm tra thật tài khoản admin, pairing, secret service-role và bảng dữ liệu trên môi trường production.
- ESP32 hiện chỉ gửi lệnh UART một ký tự xuống UNO. Các lệnh autonomy_on/off chưa được triển khai; beep tạm thời gửi STOP để không giả vờ có còi.
- TLS đang dùng `setInsecure()` cho giai đoạn thử nghiệm; cần thay bằng xác minh CA certificate trước khi dùng lâu dài.
- Không chạy thử bánh xe khi robot kê không an toàn. Thử STOP trước, sau đó thử từng lệnh ở tốc độ thấp và có người giữ nút dừng/công tắc nguồn.
