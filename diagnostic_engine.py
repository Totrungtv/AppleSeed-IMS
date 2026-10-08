# -*- coding: utf-8 -*-
"""Apple Seed automatic phone diagnostic engine.
Read-only ADB probes; LOGCAT is intentionally not used for diagnosis.
"""

import re
import time


class PhoneDiagnosticEngine:
    def _call(self, shell, command, timeout=12):
        try:
            return True, (shell(command, timeout) or "").strip(), ""
        except Exception as exc:
            return False, "", str(exc)

    def _num(self, text, pattern):
        m = re.search(pattern, text or "", re.I | re.M)
        try:
            return float(m.group(1)) if m else None
        except Exception:
            return None

    def run(self, shell):
        started = time.time()
        checks = []

        def add(name, category, status, detail, evidence="", confidence=0):
            checks.append(dict(name=name, category=category, status=status,
                               detail=detail, evidence=evidence[:1200],
                               confidence=confidence))

        ok, gp, err = self._call(shell, "getprop", 15)
        if ok:
            def prop(key):
                m = re.search(r"^\\[" + re.escape(key) + r"\\]: \\[(.*?)\\]", gp, re.M)
                return m.group(1) if m else "?"
            add("Thiết bị", "Thông tin", "OK",
                f"{prop('ro.product.brand')} {prop('ro.product.model')} • Android {prop('ro.build.version.release')}",
                "getprop", 100)
        else:
            add("Thiết bị", "Kết nối", "FAIL", "Không đọc được thông tin thiết bị qua ADB.", err, 100)

        ok, bat, err = self._call(shell, "dumpsys battery", 12)
        if ok:
            level = self._num(bat, r"level:\\s*(\\d+)")
            temp = self._num(bat, r"temperature:\\s*(\\d+)")
            problems = []
            if level is not None and level <= 5: problems.append(f"pin {level:.0f}%")
            if temp is not None and temp >= 450: problems.append(f"nhiệt pin {temp/10:.1f}°C")
            add("Pin / nhiệt", "Nguồn", "WARN" if problems else "OK",
                "; ".join(problems) if problems else f"Pin {level:.0f}% • nhiệt {temp/10:.1f}°C" if level is not None and temp is not None else "Battery service phản hồi.",
                bat, 90)
        else:
            add("Pin / nhiệt", "Nguồn", "UNKNOWN", "Không đọc được battery service.", err, 70)

        ok, df, err = self._call(shell, "df -k /data", 12)
        if ok:
            row = next((x for x in df.splitlines() if "/data" in x), "")
            used = None
            if row:
                try: used = float(row.split()[4].rstrip("%"))
                except Exception: pass
            status = "FAIL" if used is not None and used >= 95 else "WARN" if used is not None and used >= 90 else "OK"
            add("Bộ nhớ lưu trữ", "Storage", status,
                f"/data sử dụng khoảng {used:.0f}%" if used is not None else "Filesystem phản hồi.", row, 95 if status=="FAIL" else 90)
        else:
            add("Bộ nhớ lưu trữ", "Storage", "UNKNOWN", "Không đọc được /data.", err, 70)

        ok, mem, err = self._call(shell, "dumpsys meminfo", 15)
        if ok:
            total = self._num(mem, r"Total RAM:\\s*([\\d,]+)K")
            free = self._num(mem, r"Free RAM:\\s*([\\d,]+)K")
            if total and free:
                pct = free / total * 100
                add("RAM", "Hiệu năng", "WARN" if pct < 8 else "OK",
                    f"RAM trống khoảng {pct:.1f}%.", f"total={total:.0f}K free={free:.0f}K", 80)
            else:
                add("RAM", "Hiệu năng", "OK", "Memory service phản hồi.", "", 70)
        else:
            add("RAM", "Hiệu năng", "UNKNOWN", "Không đọc được memory service.", err, 60)

        probes = [
            ("Màn hình", "Hiển thị", "wm size; wm density", "WindowManager phản hồi."),
            ("Cảm biến", "Cảm biến", "dumpsys sensorservice", "Sensor service phản hồi."),
            ("Camera", "Camera", "dumpsys media.camera", "Camera service phản hồi."),
            ("Âm thanh", "Audio", "dumpsys media.audio_flinger", "Audio service phản hồi."),
            ("USB", "Kết nối", "dumpsys usb", "USB service phản hồi."),
            ("Hệ thống ứng dụng", "Hệ điều hành", "pm list packages", "Package Manager phản hồi.")
        ]
        for name, cat, cmd, good in probes:
            ok, out, err = self._call(shell, cmd, 15)
            if not ok:
                add(name, cat, "FAIL" if cat in ("Camera","Audio","Kết nối") else "UNKNOWN",
                    "Service không phản hồi.", err, 85 if cat in ("Camera","Audio","Kết nối") else 60)
                continue
            low = out.lower()
            bad = (name == "Camera" and any(x in low for x in ("not available","unable to connect","fatal")))
            add(name, cat, "FAIL" if bad else "OK",
                "Camera service báo lỗi." if bad else good, out, 80 if bad else 65)

        fails = [x for x in checks if x["status"] == "FAIL"]
        warns = [x for x in checks if x["status"] == "WARN"]
        conclusions = []
        for x in fails:
            if x["category"] == "Storage":
                conclusions.append(("NGHI LỖI / ĐẦY BỘ NHỚ", "Bộ nhớ /data bất thường.", 95))
            elif x["category"] == "Camera":
                conclusions.append(("NGHI LỖI CAMERA", "Camera service không khởi tạo bình thường.", 85))
            elif x["category"] == "Audio":
                conclusions.append(("NGHI LỖI AUDIO", "Audio service không phản hồi bình thường.", 85))
            elif x["category"] == "Kết nối":
                conclusions.append(("NGHI LỖI USB / ADB", "Không duy trì được kiểm tra qua USB.", 90))
            elif x["category"] == "Kết nối":
                conclusions.append(("NGHI LỖI KẾT NỐI", x["detail"], 80))
        if not fails and warns:
            conclusions.append(("CẦN KIỂM TRA THÊM", f"Có {len(warns)} cảnh báo; chưa đủ bằng chứng kết luận lỗi phần cứng.", 60))
        if not fails and not warns:
            conclusions.append(("CHƯA PHÁT HIỆN BẤT THƯỜNG", "Các bài test có thể xác minh qua USB đều đạt.", 75))
        conclusions.append(("GIỚI HẠN", "Chạm nguồn, IC nguồn chết, đứt đường mạch, lỗi NAND/UFS vật lý hoặc lỗi chỉ xuất hiện khi tải cao cần nguồn DC/multimeter/oscilloscope để xác nhận.", 100))
        return {"timestamp": time.strftime("%Y-%m-%d %H:%M:%S"),
                "elapsed": round(time.time()-started, 1), "checks": checks,
                "conclusions": conclusions,
                "headline": conclusions[0][0] if conclusions else "Hoàn tất"}

    def summary_text(self, report):
        checks = report.get("checks", [])
        fails = sum(x["status"] == "FAIL" for x in checks)
        warns = sum(x["status"] == "WARN" for x in checks)
        if fails: return f"🔴 {report.get('headline')} • {fails} lỗi nghi ngờ • {warns} cảnh báo"
        if warns: return f"🟡 {report.get('headline')} • {warns} cảnh báo"
        return "🟢 CHƯA PHÁT HIỆN BẤT THƯỜNG QUA CÁC BÀI TEST USB"

    def format_report(self, report):
        icons={"OK":"🟢","WARN":"🟡","FAIL":"🔴","UNKNOWN":"⚪"}
        lines=["===== APPLE SEED — CHẨN ĐOÁN ĐIỆN THOẠI TỰ ĐỘNG =====",
               "Thời gian: "+report["timestamp"],
               "Thời lượng: "+str(report["elapsed"])+" giây","",
               "### KẾT QUẢ TỪNG BÀI TEST"]
        for x in report["checks"]:
            lines.append(f"{icons.get(x['status'],'⚪')} {x['name']} [{x['status']}]")
            lines.append("   "+x["detail"])
        lines += ["","### KẾT LUẬN TỰ ĐỘNG"]
        for title, detail, conf in report["conclusions"]:
            lines.append(f"• {title} — độ tin cậy khoảng {conf}%")
            lines.append("  "+detail)
        lines += ["","### LƯU Ý",
                  "Engine tự chẩn đoán từ phép kiểm tra read-only qua USB/ADB; không dùng LOGCAT làm nguồn kết luận.",
                  "OK không có nghĩa bo mạch chắc chắn 100% không hỏng."]
        return "\n".join(lines)
