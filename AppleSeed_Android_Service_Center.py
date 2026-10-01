import os, sys, time, shutil, subprocess, threading, tempfile, zipfile, urllib.request, json
from pathlib import Path
from PySide6.QtGui import QPixmap, QPainter, QColor, QPen, QBrush, QFont, QPainterPath
from PySide6.QtCore import Qt, QEvent, QTimer
from PySide6.QtWidgets import QApplication,QMainWindow,QWidget,QVBoxLayout,QHBoxLayout,QGridLayout,QLabel,QPushButton,QComboBox,QTextEdit,QLineEdit,QTabWidget,QMessageBox,QFileDialog,QFrame,QStatusBar,QProgressBar,QSplashScreen

APP="Apple Seed Android Service Center"
VER="PySide6-1.1"


class AppleSeedSplash(QSplashScreen):
    """Màn hình khởi động nhẹ, không cần thêm ảnh/asset bên ngoài."""
    def __init__(self):
        self._progress = 0
        self._status = "Đang khởi tạo Apple Seed..." 
        super().__init__(self._render())
        self.setWindowFlag(Qt.FramelessWindowHint, True)
        self.setWindowFlag(Qt.WindowStaysOnTopHint, True)
        self.setAttribute(Qt.WA_TranslucentBackground, True)
        self.setFixedSize(680, 400)

    def _render(self):
        pix = QPixmap(680, 400)
        pix.fill(Qt.transparent)
        p = QPainter(pix)
        p.setRenderHint(QPainter.Antialiasing)

        # Nền card tối, bo góc giống giao diện chính.
        path = QPainterPath()
        path.addRoundedRect(4, 4, 672, 392, 24, 24)
        p.fillPath(path, QBrush(QColor("#080d18")))
        p.setPen(QPen(QColor("#24324a"), 1))
        p.drawPath(path)

        # Điện thoại Android ở giữa — vẽ bằng Qt, không cần PNG.
        phone = QPainterPath()
        phone.addRoundedRect(268, 36, 144, 214, 22, 22)
        p.fillPath(phone, QBrush(QColor("#101a2b")))
        p.setPen(QPen(QColor("#38bdf8"), 3))
        p.drawPath(phone)
        p.setPen(Qt.NoPen)
        p.setBrush(QBrush(QColor("#050a12")))
        p.drawRoundedRect(278, 51, 124, 184, 13, 13)
        p.setBrush(QBrush(QColor("#22c55e")))
        p.drawRoundedRect(324, 43, 32, 4, 2, 2)

        # Robot Android tối giản.
        p.setPen(QPen(QColor("#22c55e"), 4, Qt.SolidLine, Qt.RoundCap, Qt.RoundJoin))
        p.drawLine(304, 99, 292, 86)
        p.drawLine(376, 99, 388, 86)
        p.setBrush(QBrush(QColor("#22c55e")))
        p.drawRoundedRect(304, 91, 72, 54, 17, 17)
        p.drawRect(304, 124, 72, 47)
        p.drawRoundedRect(304, 164, 18, 42, 7, 7)
        p.drawRoundedRect(358, 164, 18, 42, 7, 7)
        p.setPen(QPen(QColor("#080d18"), 3))
        p.drawPoint(323, 113); p.drawPoint(357, 113)
        p.setPen(QPen(QColor("#22c55e"), 3, Qt.SolidLine, Qt.RoundCap))
        p.drawLine(319, 145, 319, 163); p.drawLine(361, 145, 361, 163)

        p.setPen(Qt.NoPen)
        p.setBrush(QBrush(QColor("#f8fafc")))
        p.setFont(QFont("Segoe UI", 22, QFont.Bold))
        p.drawText(0, 278, 680, 32, Qt.AlignCenter, "APPLE SEED")
        p.setBrush(QBrush(QColor("#38bdf8")))
        p.setFont(QFont("Segoe UI", 11, QFont.Bold))
        p.drawText(0, 310, 680, 24, Qt.AlignCenter, "ANDROID SERVICE CENTER")

        # Thanh tiến trình.
        p.setBrush(QBrush(QColor("#172033")))
        p.drawRoundedRect(105, 350, 470, 8, 4, 4)
        width = max(0, min(470, int(470 * self._progress / 100)))
        if width:
            p.setBrush(QBrush(QColor("#22c55e")))
            p.drawRoundedRect(105, 350, width, 8, 4, 4)
        p.setPen(QColor("#94a3b8"))
        p.setFont(QFont("Segoe UI", 9))
        p.drawText(105, 374, 470, 18, Qt.AlignCenter, self._status)
        p.end()
        return pix

    def set_progress(self, value, status):
        self._progress = value
        self._status = status
        self.setPixmap(self._render())
        QApplication.processEvents()

class CallEvent(QEvent):
    TYPE=QEvent.registerEventType()
    def __init__(self,fn):
        super().__init__(QEvent.Type(CallEvent.TYPE)); self.fn=fn

class AndroidTool(QMainWindow):
    def __init__(self):
        super().__init__()
        self.base=Path(__file__).resolve().parent
        self.adb=self.find_adb()
        self.scrcpy=self.find_scrcpy()
        self.scrcpy_proc=None
        self.serial=""
        self._last_adb_serial=""
        self._device_monitor_busy=False
        self._shizuku_autostart_serial=""
        self._shizuku_autostart_busy=False
        self.setWindowTitle(f"{APP} • {VER}")
        self.resize(1580,960); self.setMinimumSize(1180,720)
        self.setStyleSheet(self.qss())
        self.build()
        self.refresh_devices()

        # Theo dõi ADB để sau khi điện thoại reboot/reconnect, Apple Seed
        # tự khởi động lại Shizuku mà không cần bấm nút.
        self.device_monitor=QTimer(self)
        self.device_monitor.setInterval(3000)
        self.device_monitor.timeout.connect(self.monitor_device_connection)
        self.device_monitor.start()

    def qss(self):
        return """
        QWidget{background:#080d18;color:#e5e7eb;font-family:'Segoe UI';font-size:10pt}
        QFrame#card,QFrame#head{background:#0d1422;border:1px solid #1e293b;border-radius:12px}
        QLabel#brand{font-size:24pt;font-weight:800;color:#f8fafc}
        QLabel#blue{color:#38bdf8;font-weight:800}
        QLabel#muted{color:#64748b}
        QPushButton{background:#172033;color:#e5e7eb;border:1px solid #2b3a52;border-radius:8px;padding:9px 13px;font-weight:700}
        QPushButton:hover{background:#1d4ed8;border-color:#3b82f6}
        QPushButton#primary{background:#2563eb;border-color:#60a5fa}
        QPushButton#green{background:#15803d;border-color:#22c55e}
        QPushButton#red{background:#dc2626;border-color:#f87171;font-size:11pt;padding:12px}
        QComboBox,QLineEdit{background:#0f172a;border:1px solid #334155;border-radius:8px;padding:8px;color:#f8fafc}
        QTabWidget::pane{background:#0d1422;border:1px solid #1e293b;border-radius:10px}
        QTabBar::tab{background:#172033;color:#94a3b8;padding:10px 16px;margin:2px;border-radius:7px}
        QTabBar::tab:selected{background:#2563eb;color:white}
        QTextEdit{background:#050a12;border:1px solid #1e293b;border-radius:9px;color:#dbeafe;font-family:Consolas;font-size:10pt}
        QStatusBar{background:#101827;color:#94a3b8}
        """

    def build(self):
        root=QWidget(); self.setCentralWidget(root)
        main=QVBoxLayout(root); main.setContentsMargins(14,14,14,10); main.setSpacing(9)
        head=QFrame(); head.setObjectName("head"); h=QHBoxLayout(head); h.setContentsMargins(18,12,18,12)
        a=QLabel("◉ Apple Seed"); a.setObjectName("brand"); h.addWidget(a)
        b=QLabel("ANDROID SERVICE CENTER"); b.setObjectName("blue"); h.addWidget(b)
        h.addStretch(); self.badge=QLabel("ADB: CHƯA SẴN SÀNG"); self.badge.setStyleSheet(self.badge_css(False)); h.addWidget(self.badge)
        main.addWidget(head)
        bar=QFrame(); bar.setObjectName("card"); r=QHBoxLayout(bar); r.setContentsMargins(10,7,10,7)
        r.addWidget(QLabel("THIẾT BỊ"))
        self.devices=QComboBox(); self.devices.setMinimumWidth(350); self.devices.currentIndexChanged.connect(self.select_device); r.addWidget(self.devices)
        self.button(r,"↻ LÀM MỚI",self.refresh_devices)
        self.button(r,"CHỌN ADB.EXE",self.choose_adb)
        self.button(r,"RESTART ADB",self.restart_adb); self.button(r,"🩺 CHẨN ĐOÁN ADB",self.adb_diagnose)
        self.button(r,"📱 LIVE SCREEN",self.start_mirror,"green")
        self.button(r,"⬇ CẬP NHẬT",self.update_from_github,"primary")
        r.addStretch(); self.devlabel=QLabel("Chưa chọn"); self.devlabel.setObjectName("muted"); r.addWidget(self.devlabel)
        main.addWidget(bar)
        self.tabs=QTabWidget(); main.addWidget(self.tabs,1)
        self.tabs.addTab(self.home_tab(),"⌂  TỔNG QUAN")
        self.tabs.addTab(self.volte_tab(),"☎  VoLTE / IMS")
        self.tabs.addTab(self.device_tab(),"▣  THIẾT BỊ")
        self.tabs.addTab(self.diag_tab(),"⌁  CHẨN ĐOÁN")
        self.tabs.addTab(self.control_tab(),"⚙  ĐIỀU KHIỂN")
        self.tabs.addTab(self.apps_tab(),"▤  ỨNG DỤNG")
        self.tabs.addTab(self.files_tab(),"□  TỆP")
        self.tabs.addTab(self.adb_tab()," >_  LỆNH ADB")
        self.tabs.addTab(self.logs_tab(),"≡  NHẬT KÝ")
        self.status=QStatusBar(); self.setStatusBar(self.status)
        self.progress=QProgressBar(); self.progress.setRange(0,0); self.progress.setFixedWidth(180); self.progress.hide()
        self.status.addPermanentWidget(self.progress)
        self.status.showMessage("Sẵn sàng.")

    def badge_css(self,on):
        return ("background:#064e3b;color:#bbf7d0;" if on else "background:#3b1720;color:#fecaca;")+"border-radius:8px;padding:8px 14px;font-weight:800"

    def button(self,layout,text,fn,kind=""):
        x=QPushButton(text); x.clicked.connect(lambda checked=False, b=x, f=fn: (b.setEnabled(False), self.status.showMessage("⏳ "+b.text()+" ..."), f(), b.setEnabled(True)))
        if kind:x.setObjectName(kind)
        layout.addWidget(x); return x

    def find_scrcpy(self):
        # Tìm scrcpy đi kèm Apple Seed trước, sau đó mới dùng bản cài trong PATH.
        names=["scrcpy.exe","scrcpy"] if os.name=="nt" else ["scrcpy"]
        candidates=[]
        for folder in ("scrcpy","tools/scrcpy","platform-tools"):
            for name in names:candidates.append(self.base/folder/name)
        for name in names:
            p=shutil.which(name)
            if p:candidates.append(Path(p))
        for p in candidates:
            if Path(p).exists():return str(p)
        return None

    def find_adb(self):
        # Ưu tiên ADB đi kèm tool, nhưng tự fallback sang ADB trong PATH.
        names=["adb.exe","adb"] if os.name=="nt" else ["adb"]
        for name in names:
            p=self.base/"platform-tools"/name
            if p.exists():
                return str(p)
        return shutil.which("adb")

    def adb_diagnose(self):
        if not self.adb:
            self.log("❌ Không tìm thấy adb.exe. Hãy dùng CHỌN ADB.EXE hoặc đặt platform-tools cạnh tool.")
            return
        def w():
            try:
                rc,v=self.run(["version"],10)
                rc2,d=self.run(["devices","-l"],15)
                self.log("ADB VERSION: "+v.replace("\n"," | "))
                self.log("ADB DEVICES: "+(d or "(trống)"))
                if "unauthorized" in d:
                    self.post(lambda:QMessageBox.warning(self,"ADB","Điện thoại chưa cấp quyền USB debugging. Mở khóa máy và bấm Allow/Cho phép trên điện thoại."))
                elif not any(line.strip() and not line.startswith("List of devices") for line in d.splitlines()):
                    self.post(lambda:QMessageBox.warning(self,"ADB","ADB chạy được nhưng chưa thấy thiết bị. Kiểm tra cáp dữ liệu, USB debugging và driver ADB."))
            except Exception as e:
                self.log("ADB DIAG ERROR: "+str(e))
        self.threaded(w)

    def cmd(self,args):
        if not self.adb: raise RuntimeError("Không tìm thấy adb.exe")
        return [self.adb]+list(args)

    def run(self,args,timeout=30):
        p=subprocess.run(self.cmd(args),capture_output=True,text=True,encoding="utf-8",errors="replace",timeout=timeout,
                         creationflags=(subprocess.CREATE_NO_WINDOW if os.name=="nt" else 0))
        return p.returncode,((p.stdout or "")+(p.stderr or "")).strip()

    def shell(self,s,timeout=30):
        parts=s.strip().split()
        simple={"getprop","settings","pm","reboot","screencap","rm","ls","df","logcat","dumpsys","service","ps","input","am"}
        if len(parts)>0 and parts[0] in simple and not any(ch in s for ch in "|&;><"):
            args=["-s",self.serial,"shell"]+parts
        else:
            args=["-s",self.serial,"shell","sh","-c",s]
        rc,out=self.run(args,timeout)
        if rc: raise RuntimeError(out or f"ADB rc={rc}")
        return out

    def post(self,fn): QApplication.postEvent(self,CallEvent(fn))
    def customEvent(self,e):
        if isinstance(e,CallEvent):
            try:e.fn()
            except Exception:pass
        else:super().customEvent(e)

    def threaded(self,fn):
        self.post(lambda:(self.progress.show(), self.status.showMessage("⏳ ĐANG XỬ LÝ...")))
        def worker():
            started=time.time(); ok=True
            try: fn()
            except Exception as e:
                ok=False; self.log("TASK ERROR: "+str(e))
            finally:
                elapsed=time.time()-started
                self.post(lambda ok=ok,elapsed=elapsed:(self.progress.hide(), self.status.showMessage(("✅ HOÀN TẤT" if ok else "❌ CÓ LỖI")+f" • {elapsed:.1f}s",5000)))
        threading.Thread(target=worker,daemon=True).start()
    def require(self):
        if not self.serial:
            QMessageBox.warning(self,"Apple Seed","Chưa chọn thiết bị ADB."); return False
        return True
    def ask(self,t,s): return QMessageBox.question(self,t,s,QMessageBox.Yes|QMessageBox.No)==QMessageBox.Yes
    def log(self,s): self.post(lambda:self.logbox.append(f"[{time.strftime('%H:%M:%S')}] {s}"))
    def showout(self,w,title,text): self.post(lambda:(w.setPlainText(f"===== {title} =====\n\n{text}"),w.moveCursor(w.textCursor().End)))

    def home_tab(self):
        w=QWidget(); l=QVBoxLayout(w)
        t=QLabel("BẢNG ĐIỀU KHIỂN"); t.setStyleSheet("font-size:21pt;font-weight:800"); l.addWidget(t)
        row=QHBoxLayout(); self.cards=[]
        for title,val in [("THIẾT BỊ","—"),("ANDROID","—"),("SOC","—"),("ADB","—")]:
            f=QFrame(); f.setObjectName("card"); q=QVBoxLayout(f); lab=QLabel(title); lab.setObjectName("muted"); q.addWidget(lab); v=QLabel(val); v.setStyleSheet("font-size:15pt;font-weight:800;color:#38bdf8"); q.addWidget(v); f.val=v; self.cards.append(f); row.addWidget(f)
        l.addLayout(row)
        g=QGridLayout()
        for i,(txt,fn,k) in enumerate([
            ("🔍 PHÂN TÍCH THIẾT BỊ",self.analyze,"primary"),("⚡ VOLTE 1-CLICK",self.native_volte,"red"),
            ("🚀 START SHIZUKU",self.start_shizuku,"green"),
            ("🔋 CHẨN ĐOÁN PIN",lambda:self.capture("dumpsys battery","PIN"),""),
            ("🌐 THÔNG TIN MẠNG",lambda:self.capture("getprop | grep -iE 'gsm|radio|baseband|operator|network'","MẠNG"),""),
            ("▣ CHỤP MÀN HÌNH",self.screenshot,""),("♻ REBOOT",lambda:self.reboot(""),"")]):
            x=QPushButton(txt); x.setObjectName(k); x.clicked.connect(fn); g.addWidget(x,i//3,i%3)
        l.addLayout(g)
        n=QLabel("PySide6 • chạy tác vụ nền • log rõ • ADB tích hợp • VoLTE/IMS 1-click"); n.setObjectName("muted"); l.addWidget(n)
        return w

    def volte_tab(self):
        w=QWidget(); l=QVBoxLayout(w); t=QLabel("VoLTE / IMS • APPLE SEED"); t.setStyleSheet("font-size:21pt;font-weight:800"); l.addWidget(t)
        n=QLabel("ADB VoLTE flags → Apple Seed FIX_VOLTE → Shizuku/CarrierConfig nếu đã được cấp quyền → kiểm tra IMS. Không thay đổi IMEI/SIM lock/modem."); n.setObjectName("muted"); l.addWidget(n)
        g=QGridLayout()
        for txt,fn,k in [("⚡ KÍCH HOẠT VoLTE TỰ ĐỘNG 1-CLICK",self.native_volte,"red"),("🚀 START SHIZUKU",self.start_shizuku,"green"),
                         ("🔎 KIỂM TRA VoLTE",self.volte_check,""),("🧪 IMS CHUYÊN SÂU",self.ims_check,""),
                         ("📱 CÀI APP VoLTE",self.install_volte_app,"green"),("▶ MỞ APP VoLTE",self.open_volte_app,""),
                         ("♻ REBOOT",lambda:self.reboot(""),"")]:
            self.button(g,txt,fn,k)
        l.addLayout(g); self.volte_out=QTextEdit(); self.volte_out.setReadOnly(True); l.addWidget(self.volte_out,1); return w

    def device_tab(self):
        w=QWidget(); l=QVBoxLayout(w); self.device_out=QTextEdit(); self.device_out.setReadOnly(True); l.addWidget(self.device_out,1)
        r=QHBoxLayout(); self.button(r,"🔍 PHÂN TÍCH",self.analyze,"primary"); self.button(r,"SAO LƯU",self.backup); self.button(r,"XUẤT TXT",self.export_report); l.addLayout(r); return w

    def diag_tab(self):
        w=QWidget(); l=QVBoxLayout(w); g=QGridLayout()
        items=[("LOGCAT","logcat -d -t 1000"),("GETPROP","getprop"),("DUMPSYS","dumpsys"),("PIN","dumpsys battery"),("BỘ NHỚ","df -h"),("RAM","dumpsys meminfo"),("TIẾN TRÌNH","ps -A"),("DỊCH VỤ","service list"),("USB","dumpsys usb"),("CẢM BIẾN","dumpsys sensorservice"),("CAMERA","dumpsys media.camera"),("WINDOW","dumpsys window")]
        for i,(a,c) in enumerate(items): self.button(g,a,lambda c=c,a=a:self.capture(c,a))
        l.addLayout(g); self.diag_out=QTextEdit(); self.diag_out.setReadOnly(True); l.addWidget(self.diag_out,1); return w

    def control_tab(self):
        w=QWidget(); l=QVBoxLayout(w); g=QGridLayout()
        items=[("REBOOT",lambda:self.reboot("")),("RECOVERY",lambda:self.reboot("recovery")),("BOOTLOADER",lambda:self.reboot("bootloader")),("SCREENSHOT",self.screenshot),
        ("WAKE",lambda:self.action("input keyevent KEYCODE_WAKEUP")),("LOCK",lambda:self.action("input keyevent KEYCODE_POWER")),("VOL +",lambda:self.action("input keyevent KEYCODE_VOLUME_UP")),("VOL -",lambda:self.action("input keyevent KEYCODE_VOLUME_DOWN")),
        ("CÀI ĐẶT",lambda:self.action("am start -a android.settings.SETTINGS")),("NHÀ PHÁT TRIỂN",lambda:self.action("am start -a android.settings.DEVELOPMENT_SETTINGS"))]
        for i,(a,f) in enumerate(items):self.button(g,a,f)
        l.addLayout(g);self.control_out=QTextEdit();self.control_out.setReadOnly(True);l.addWidget(self.control_out,1);return w

    def apps_tab(self):
        w=QWidget();l=QVBoxLayout(w);r=QHBoxLayout();self.pkg=QLineEdit();self.pkg.setPlaceholderText("lọc package, ví dụ volte");r.addWidget(self.pkg)
        self.button(r,"LIỆT KÊ",self.list_packages);self.button(r,"CÀI APK",self.install_apk);self.button(r,"CÀI APP VoLTE",self.install_volte_app,"green");l.addLayout(r)
        self.app_out=QTextEdit();self.app_out.setReadOnly(True);l.addWidget(self.app_out,1);return w

    def files_tab(self):
        w=QWidget();l=QVBoxLayout(w);r=QHBoxLayout();self.remote=QLineEdit("/sdcard");r.addWidget(self.remote);self.button(r,"LS",self.file_ls);self.button(r,"PULL",self.pull_file);self.button(r,"PUSH",self.push_file);l.addLayout(r)
        self.files_out=QTextEdit();self.files_out.setReadOnly(True);l.addWidget(self.files_out,1);return w

    def adb_tab(self):
        w=QWidget();l=QVBoxLayout(w);r=QHBoxLayout();self.adb_edit=QLineEdit();self.adb_edit.setPlaceholderText("dumpsys ims / getprop / settings get global ...");r.addWidget(self.adb_edit);self.button(r,"▶ CHẠY",self.run_manual,"primary");l.addLayout(r)
        self.adb_out=QTextEdit();self.adb_out.setReadOnly(True);l.addWidget(self.adb_out,1);return w

    def logs_tab(self):
        w=QWidget();l=QVBoxLayout(w);self.logbox=QTextEdit();self.logbox.setReadOnly(True);l.addWidget(self.logbox);self.button(l,"XÓA LOG",self.logbox.clear);return w

    def refresh_devices(self):
        if not self.adb:self.adb=self.find_adb()
        if not self.adb:self.log("❌ Thiếu platform-tools/adb.exe");return
        def w():
            try:
                self.run(["start-server"],10);rc,out=self.run(["devices","-l"],15);rows=[]
                for line in out.splitlines()[1:]:
                    p=line.split()
                    if len(p)>=2:
                        rows.append((p[0],p[1]))
                if not rows:
                    self.log("ADB devices: KHÔNG CÓ THIẾT BỊ")
                    self.log("ADB output: "+(out or "(trống)"))
                else:
                    self.log("ADB devices: "+str(rows))
                def ui():
                    self.devices.blockSignals(True);self.devices.clear()
                    for s,st in rows:self.devices.addItem(f"{s} • {st}",s)
                    self.devices.blockSignals(False)
                    if rows:
                        self.devices.setCurrentIndex(0)
                        self.select_device()
                        if self.isVisible():QTimer.singleShot(450,self.start_mirror)
                    else:
                        self.serial=""
                        self.badge.setText("ADB: CHƯA SẴN SÀNG")
                        self.devlabel.setText("Chưa chọn")
                    self.log("ADB: "+(str(rows) if rows else "không có thiết bị"))
                self.post(ui)
            except Exception as e:self.log("ADB ERROR: "+str(e))
        self.threaded(w)

    def select_device(self):
        i=self.devices.currentIndex()
        if i<0:return
        self.serial=self.devices.itemData(i) or ""; on=bool(self.serial);self.badge.setText("ADB: KẾT NỐI" if on else "ADB: CHƯA SẴN SÀNG");self.badge.setStyleSheet(self.badge_css(on));self.devlabel.setText(self.serial or "Chưa chọn")
        self.status.showMessage(("📱 Đã chọn thiết bị: "+self.serial) if on else "Chưa chọn thiết bị",4000)
        if on:
            self.refresh_info()
            # Chỉ tự start 1 lần cho mỗi phiên ADB. Không gọi start.sh lặp,
            # vì start.sh sẽ kill server cũ rồi tạo server mới.
            if self._shizuku_autostart_serial != self.serial:
                self._shizuku_autostart_serial = self.serial
                QTimer.singleShot(1200, self.auto_start_shizuku_after_reconnect)
            if self.isVisible():QTimer.singleShot(350,self.start_mirror)

    def monitor_device_connection(self):
        """Tự phát hiện điện thoại vừa online lại sau reboot."""
        if self._device_monitor_busy or not self.adb:
            return
        self._device_monitor_busy=True
        def w():
            try:
                self.run(["start-server"],8)
                rc,out=self.run(["devices"],8)
                online=[]
                for line in out.splitlines()[1:]:
                    p=line.split()
                    if len(p)>=2 and p[1] in ("device","unauthorized"):
                        online.append(p[0])
                current=self.serial
                if current and current not in online:
                    self._shizuku_autostart_serial=""
                    self.post(self.refresh_devices)
                elif not current and online:
                    self.post(self.refresh_devices)
            except Exception as e:
                self.log("ADB monitor: "+str(e))
            finally:
                self._device_monitor_busy=False
        threading.Thread(target=w,daemon=True).start()

    def auto_start_shizuku_after_reconnect(self):
        """Tự phục hồi Shizuku đúng 1 lần sau khi thiết bị reconnect/reboot."""
        if not self.serial or self._shizuku_autostart_busy:
            return
        serial=self.serial
        self._shizuku_autostart_busy=True

        def w():
            try:
                # Chờ Android boot hoàn tất.
                for _ in range(10):
                    if serial != self.serial:
                        return
                    try:
                        _,boot=self.run(
                            ["-s",serial,"shell","getprop","sys.boot_completed"],8
                        )
                        if boot.strip()=="1":
                            break
                    except Exception:
                        pass
                    time.sleep(1.5)

                # QUAN TRỌNG: kiểm tra PID trước.
                # Nếu Shizuku đang chạy thì tuyệt đối không gọi start.sh.
                try:
                    pid=self.shell("pidof moe.shizuku.privileged.api",5).strip()
                except Exception:
                    pid=""

                if pid:
                    self.log("✅ Shizuku đã chạy sau reconnect. Không restart. PID="+pid)
                    return

                ok,msg=self.start_shizuku_via_adb()
                if ok:
                    self.log("✅ Sau reboot: Shizuku tự khởi động OK — "+msg.replace("\n"," | "))
                    # Chỉ gọi FIX_VOLTE sau khi Shizuku thật sự có PID.
                    try:
                        installed="package:vn.appleseed.volte" in self.shell(
                            "pm list packages vn.appleseed.volte",8
                        )
                    except Exception:
                        installed=False

                    if installed:
                        time.sleep(1)
                        rc,o=self.run([
                            "-s",serial,"shell","am","start",
                            "-a","vn.appleseed.volte.action.FIX_VOLTE",
                            "-n","vn.appleseed.volte/.MainActivity"
                        ],15)
                        self.log(
                            "📡 Sau reboot: FIX_VOLTE rc="+str(rc)+
                            (" | "+o if o else "")
                        )
                else:
                    self.log("❌ Sau reboot: không khởi động được Shizuku — "+msg.replace("\n"," | "))
            except Exception as e:
                self.log("🔄 Auto Shizuku ERROR: "+str(e))
            finally:
                self._shizuku_autostart_busy=False

        threading.Thread(target=w,daemon=True).start()

    def refresh_info(self):
        def w():
            try:
                model=self.shell("getprop ro.product.model",8);android=self.shell("getprop ro.build.version.release",8);soc=self.shell("getprop ro.board.platform",8)
                self.post(lambda:(self.cards[0].val.setText((model or self.serial).splitlines()[0]),self.cards[1].val.setText((android or "—").splitlines()[0]),self.cards[2].val.setText((soc or "—").splitlines()[0]),self.cards[3].val.setText("KẾT NỐI")))
            except:pass
        self.threaded(w)

    def restart_adb(self):
        try:self.stop_mirror()
        except:pass
        try:self.run(["kill-server"],10)
        except:pass
        self.refresh_devices()

    def update_from_github(self):
        """Đồng bộ bản mới nhất từ GitHub rồi tự khởi động lại Apple Seed."""
        if not self.ask(
            "CẬP NHẬT APPLE SEED",
            "Đồng bộ Apple Seed với GitHub ngay bây giờ?\n\n"
            "Tool sẽ lấy bản main mới nhất, cập nhật file và tự mở lại.\n"
            "Các thư mục backups/ sẽ được giữ lại."
        ):
            return

        def w():
            self._volte_progress(10,"Kiểm tra GitHub...")
            try:
                remote_url="https://github.com/Totrungtv/AppleSeed-IMS.git"
                current=""
                try:
                    rc,h=self.run(["rev-parse","HEAD"],10)
                    if rc==0:current=h.strip()
                except Exception:
                    pass

                # Ưu tiên Git để đồng bộ đúng như sync_github.bat hiện tại.
                git=shutil.which("git")
                if git:
                    self._volte_progress(25,"Đang lấy bản mới từ GitHub...")
                    if not (self.base/".git").exists():
                        self.run_git([git,"init"],20)
                    self.run_git([git,"remote","set-url","origin",remote_url],20,allow_error=True)
                    self.run_git([git,"fetch","origin","main"],90)
                    rc_new,out=self.run_git([git,"rev-parse","origin/main"],20)
                    latest=out.strip() if rc_new==0 else ""

                    if latest and latest==current:
                        self._volte_progress(100,"Đã là bản mới nhất")
                        self.log("✓ Apple Seed đã là bản GitHub mới nhất.")
                        self.post(lambda:QMessageBox.information(
                            self,"Apple Seed — Cập nhật","Bạn đang dùng bản mới nhất trên GitHub."
                        ))
                        return

                    self._volte_progress(60,"Đang đồng bộ mã nguồn...")
                    self.run_git([git,"reset","--hard","origin/main"],90)
                    self.run_git([git,"checkout","-B","main","origin/main"],30)
                    self._volte_progress(100,"Cập nhật hoàn tất")
                    self.log("✓ Đã đồng bộ Apple Seed với GitHub.")
                else:
                    # Máy không có Git: tải ZIP main trực tiếp từ GitHub.
                    self._volte_progress(30,"Không có Git — tải ZIP GitHub...")
                    url="https://github.com/Totrungtv/AppleSeed-IMS/archive/refs/heads/main.zip"
                    with tempfile.TemporaryDirectory(prefix="appleseed_update_") as td:
                        zpath=Path(td)/"main.zip"
                        urllib.request.urlretrieve(url,str(zpath))
                        self._volte_progress(65,"Giải nén bản cập nhật...")
                        with zipfile.ZipFile(zpath) as z:z.extractall(td)
                        roots=[p for p in Path(td).iterdir() if p.is_dir() and p.name.startswith("AppleSeed-IMS-")]
                        if not roots:raise RuntimeError("ZIP GitHub không đúng cấu trúc.")
                        source=roots[0]
                        for item in source.iterdir():
                            if item.name in (".git","backups"):continue
                            dest=self.base/item.name
                            if item.is_dir():
                                shutil.copytree(item,dest,dirs_exist_ok=True)
                            else:
                                shutil.copy2(item,dest)
                    self._volte_progress(100,"Cập nhật hoàn tất")
                    self.log("✓ Đã tải và đồng bộ bản GitHub mới nhất.")

                self.post(lambda:self.restart_after_update())
            except Exception as e:
                self._volte_progress(0,"Cập nhật thất bại")
                self.log("❌ UPDATE ERROR: "+str(e))
                self.post(lambda e=str(e):QMessageBox.warning(
                    self,"Apple Seed — Cập nhật lỗi",
                    "Không cập nhật được từ GitHub.\n\n"+e
                ))

        self.threaded(w)

    def run_git(self,cmd,timeout=60,allow_error=False):
        p=subprocess.run(
            cmd,capture_output=True,text=True,encoding="utf-8",errors="replace",
            timeout=timeout,cwd=str(self.base),
            creationflags=(subprocess.CREATE_NO_WINDOW if os.name=="nt" else 0)
        )
        out=((p.stdout or "")+(p.stderr or "")).strip()
        if p.returncode and not allow_error:
            raise RuntimeError(out or f"Git rc={p.returncode}")
        return p.returncode,out

    def restart_after_update(self):
        self.log("↻ Đang khởi động lại Apple Seed...")
        try:self.stop_mirror()
        except:pass
        try:
            script=Path(__file__).resolve()
            subprocess.Popen(
                [sys.executable,str(script)],
                cwd=str(self.base),
                creationflags=(subprocess.CREATE_NEW_PROCESS_GROUP if os.name=="nt" else 0)
            )
            QTimer.singleShot(350,self.close)
        except Exception as e:
            QMessageBox.warning(self,"Cập nhật","Đã cập nhật xong nhưng không tự mở lại:\n"+str(e))

    def start_mirror(self):
        """Mở Live Screen bằng scrcpy: video thật, 60 FPS, chuột + bàn phím."""
        if not self.require():return
        if self.scrcpy_proc is not None and self.scrcpy_proc.poll() is None:
            self.log("LIVE SCREEN: đã chạy.")
            return
        self.scrcpy=self.find_scrcpy() or self.scrcpy
        if not self.scrcpy:
            self.log("❌ Không tìm thấy scrcpy.exe.")
            self.post(lambda:QMessageBox.information(
                self,"Apple Seed — Live Screen",
                "Chưa có scrcpy.exe.\n\nĐặt bộ scrcpy chính thức vào thư mục:\n"
                "scrcpy\\scrcpy.exe\n\nSau đó bấm LIVE SCREEN lại."
            ))
            return
        env=os.environ.copy()
        adb_parent=str(Path(self.adb).resolve().parent) if self.adb else ""
        if adb_parent:env["PATH"]=adb_parent+os.pathsep+env.get("PATH","")
        try:
            geo=self.geometry()
            x=geo.x()+geo.width()+12
            y=max(30,geo.y()+28)
        except Exception:
            x=1280;y=40
        cmd=[
            self.scrcpy,"--serial",self.serial,
            "--window-title",f"Apple Seed • Android Live • {self.serial}",
            "--max-fps=60","--max-size=900","--video-bit-rate=8M",
            "--no-audio","--stay-awake",
            "--window-width=360","--window-height=800",
            f"--window-x={x}",f"--window-y={y}"
        ]
        try:
            self.scrcpy_proc=subprocess.Popen(
                cmd,stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,env=env,
                creationflags=(subprocess.CREATE_NO_WINDOW if os.name=="nt" else 0)
            )
            self.log("📱 LIVE SCREEN: đã khởi động 60 FPS.")
            self.status.showMessage("📱 Live Screen Android đang chạy • 60 FPS",5000)
        except Exception as e:
            self.scrcpy_proc=None
            self.log("❌ LIVE SCREEN ERROR: "+str(e))

    def stop_mirror(self):
        p=self.scrcpy_proc
        self.scrcpy_proc=None
        if p is not None and p.poll() is None:
            try:p.terminate()
            except:pass
            self.log("LIVE SCREEN: đã đóng.")


    def choose_adb(self):
        p,_=QFileDialog.getOpenFileName(self,"Chọn adb.exe","","ADB (*.exe)")
        if p:self.adb=p;self.refresh_devices()

    def analyze(self):
        if not self.require():return
        def w():
            try:
                out=[]
                for a,c in [("MODEL","getprop ro.product.model"),("ANDROID","getprop ro.build.version.release"),("API","getprop ro.build.version.sdk"),("SOC","getprop ro.board.platform"),("BASEBAND","getprop gsm.version.baseband"),("SIM","getprop gsm.sim.state"),("OPERATOR","getprop gsm.operator.alpha")]:
                    out.append(f"{a}: {self.shell(c,8) or '—'}")
                self.showout(self.device_out,"PHÂN TÍCH THIẾT BỊ","\n".join(out));self.log("Đã phân tích thiết bị.")
            except Exception as e:self.showout(self.device_out,"PHÂN TÍCH","LỖI: "+str(e))
        self.threaded(w)

    def backup(self):
        if not self.require():return
        folder=self.base/"backups"/f"{self.serial}_{time.strftime('%Y%m%d_%H%M%S')}";folder.mkdir(parents=True,exist_ok=True)
        def w():
            try:(folder/"getprop.txt").write_text(self.shell("getprop",20),encoding="utf-8");self.log("Backup: "+str(folder))
            except Exception as e:self.log("Backup lỗi: "+str(e))
        self.threaded(w)

    def export_report(self):
        p,_=QFileDialog.getSaveFileName(self,"Xuất báo cáo","","Text (*.txt)")
        if p:Path(p).write_text(self.device_out.toPlainText(),encoding="utf-8")

    def reboot(self,mode):
        if not self.require() or not self.ask("Xác nhận",f"Khởi động lại {mode or 'bình thường'}?"):return
        try:self.log(f"REBOOT {mode or 'normal'}: {self.run(['-s',self.serial,'reboot']+([mode] if mode else []),10)}")
        except Exception as e:self.log(str(e))

    def screenshot(self):
        if not self.require():return
        p,_=QFileDialog.getSaveFileName(self,"Lưu screenshot","","PNG (*.png)")
        if not p:return
        def w():
            try:
                remote="/sdcard/apple_seed_screen.png";self.run(["-s",self.serial,"shell","screencap","-p",remote],15);rc,out=self.run(["-s",self.serial,"pull",remote,p],30);self.run(["-s",self.serial,"shell","rm","-f",remote],8);self.log("Screenshot: "+(p if rc==0 else out))
            except Exception as e:self.log("Screenshot lỗi: "+str(e))
        self.threaded(w)

    def action(self,c):
        if not self.require():return
        def w():
            try:self.showout(self.control_out,c,self.shell(c,10))
            except Exception as e:self.showout(self.control_out,c,"LỖI: "+str(e))
        self.threaded(w)

    def capture(self,c,title):
        if not self.require():return
        def w():
            try:self.showout(self.diag_out,title,self.shell(c,45))
            except Exception as e:self.showout(self.diag_out,title,"LỖI: "+str(e))
        self.threaded(w)

    def _volte_progress(self,value,text):
        self.post(lambda value=value,text=text: (self.progress.setRange(0,100), self.progress.setValue(value), self.status.showMessage(f"⏳ {text}")))

    def volte_check(self):
        if not self.require():return
        def w():
            out=[]
            checks=[
                ("SIM","getprop gsm.sim.state",5),
                ("NHÀ MẠNG","getprop gsm.operator.alpha",20),
                ("LTE","getprop gsm.network.type",35),
                ("VOICE","getprop gsm.voice.network.type",50),
                ("VOLTE FLAG","settings get global volte_vt_enabled",65),
                ("ENHANCED 4G","settings get global enhanced_4g_mode_enabled",80),
            ]
            for a,cmd,p in checks:
                self._volte_progress(p,"Kiểm tra "+a)
                try: out.append(f"[{a}]\n{self.shell(cmd,8).strip() or '—'}")
                except Exception as e: out.append(f"[{a}]\nERROR: {e}")
            # dumpsys ims có thể treo trên một số ColorOS/Android 8. Không để nó khóa toàn bộ bài test.
            self._volte_progress(90,"Kiểm tra IMS (tối đa 5 giây)")
            try:
                ims=self.shell("dumpsys ims",5).strip()
                out.append("[IMS]\n"+(ims[:6000] if ims else "Không trả dữ liệu"))
            except Exception as e:
                out.append("[IMS]\nKHÔNG PHẢN HỒI / KHÔNG HỖ TRỢ: "+str(e))
            self._volte_progress(100,"Hoàn tất kiểm tra VoLTE")
            self.showout(self.volte_out,"KIỂM TRA VoLTE / IMS","\n\n".join(out))
        self.threaded(w)

    def ims_check(self):
        if not self.require():return
        def w():
            out=[]
            checks=[
                ("SERVICE","service list | grep -iE 'ims|telephony|phone'",20),
                ("PACKAGES","pm list packages | grep -iE 'ims|carrier|telephony'",40),
            ]
            for a,cmd,p in checks:
                self._volte_progress(p,"Kiểm tra "+a)
                try: out.append(f"### {a}\n{self.shell(cmd,10).strip()}")
                except Exception as e: out.append(f"### {a}\nERROR: {e}")
            self._volte_progress(60,"Kiểm tra IMS (tối đa 5 giây)")
            try: out.append("### IMS\n"+self.shell("dumpsys ims",5)[:6000])
            except Exception as e: out.append("### IMS\nKHÔNG PHẢN HỒI / KHÔNG HỖ TRỢ: "+str(e))
            self._volte_progress(80,"Kiểm tra Telephony Registry (tối đa 10 giây)")
            try: out.append("### TELEPHONY\n"+self.shell("dumpsys telephony.registry",10)[:6000])
            except Exception as e: out.append("### TELEPHONY\nERROR: "+str(e))
            self._volte_progress(100,"Hoàn tất IMS")
            self.showout(self.volte_out,"IMS CHUYÊN SÂU","\n\n".join(out))
        self.threaded(w)

    def start_shizuku_via_adb(self):
        """Khởi động Shizuku bằng đúng kiểu ADB đã test thành công trên CPH1905."""
        if not self.serial:
            return False, "Chưa chọn thiết bị ADB."

        try:
            pkg_out = self.shell("pm list packages moe.shizuku.privileged.api", 8)
        except Exception as e:
            return False, "Không kiểm tra được package Shizuku: " + str(e)

        if "moe.shizuku.privileged.api" not in pkg_out:
            return False, "Chưa cài Shizuku (moe.shizuku.privileged.api)."

        try:
            pid = self.shell("pidof moe.shizuku.privileged.api", 5).strip()
            if pid:
                return True, "Shizuku đang chạy. PID=" + pid
        except Exception:
            pass

        paths=[
            "/sdcard/Android/data/moe.shizuku.privileged.api/start.sh",
            "/storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh",
        ]
        last=""

        for path in paths:
            try:
                # Không dùng sh -c. Phải giống lệnh đã test thủ công:
                # adb shell sh /sdcard/.../start.sh
                rc,out=self.run(
                    ["-s",self.serial,"shell","sh",path],
                    15
                )
                last=(out or "").strip()

                if rc==0:
                    for _ in range(8):
                        time.sleep(0.75)
                        try:
                            pid=self.shell(
                                "pidof moe.shizuku.privileged.api", 5
                            ).strip()
                        except Exception:
                            pid=""
                        if pid:
                            return True, (
                                "Shizuku START OK. PID=" + pid +
                                ("\\n"+last if last else "")
                            )

            except subprocess.TimeoutExpired as e:
                last="START SHIZUKU TIMEOUT: "+str(e)
                try:
                    pid=self.shell(
                        "pidof moe.shizuku.privileged.api", 5
                    ).strip()
                except Exception:
                    pid=""
                if pid:
                    return True, "Shizuku START OK sau timeout. PID="+pid
            except Exception as e:
                last=str(e)

        return False, (
            "Không xác nhận được Shizuku đang chạy."
            + ("\\n"+last if last else "")
        )

    def start_shizuku(self):
        """Nút 1-click: ADB -> Shizuku starter -> PID verification."""
        if not self.require():
            return

        def w():
            self._volte_progress(15, "Kiểm tra Shizuku")
            ok,msg=self.start_shizuku_via_adb()

            if ok:
                self._volte_progress(100, "Shizuku đang chạy")
                self.showout(
                    self.volte_out,
                    "START SHIZUKU",
                    "===== APPLE SEED — START SHIZUKU =====\\n\\n"
                    "✅ " + msg +
                    "\\n\\n"
                    "Khách không cần mở CMD. Apple Seed đã tự gọi ADB "
                    "và xác nhận server Shizuku bằng PID."
                )
                self.log("Shizuku: START OK — " + msg.replace("\\n", " | "))
                self.post(lambda: QMessageBox.information(
                    self,
                    "Shizuku",
                    "✅ Shizuku đang chạy.\\n\\n" + msg
                ))
            else:
                self._volte_progress(100, "Không khởi động được Shizuku")
                self.showout(
                    self.volte_out,
                    "START SHIZUKU",
                    "===== APPLE SEED — START SHIZUKU =====\\n\\n"
                    "❌ " + msg +
                    "\\n\\n"
                    "Kiểm tra USB debugging, ADB authorization và "
                    "Shizuku đã được cài trên máy."
                )
                self.log("Shizuku: START FAILED — " + msg)
                self.post(lambda: QMessageBox.warning(
                    self,
                    "Shizuku",
                    "❌ Không khởi động được Shizuku.\\n\\n" + msg
                ))

        self.threaded(w)

    def native_volte(self):
        """VoLTE 1-click bằng ADB + Apple Seed VoLTE app.

        Không đẩy/chạy DEX native CarrierConfig từ /data/local/tmp.
        CarrierConfig nâng cao chỉ được thực hiện bên trong Apple Seed VoLTE
        khi người dùng đã cài/chạy Shizuku và cấp quyền cho app.
        """
        if not self.require():
            return
        if not self.ask(
            "VoLTE 1-CLICK",
            "Chạy quy trình Apple Seed VoLTE?\\n\\n"
            "1) Kiểm tra/cài Apple Seed VoLTE\\n"
            "2) Bật các cờ VoLTE bằng ADB shell\\n"
            "3) Mở FIX_VOLTE để app dùng Shizuku nếu đã được cấp quyền\\n"
            "4) Đọc lại trạng thái IMS/CarrierConfig"
        ):
            return

        def w():
            out=["===== APPLE SEED VoLTE 1-CLICK ====="]
            apk=self.base/"apps"/"AppleSeed_VoLTE.apk"
            try:
                folder=self.base/"backups"/f"{self.serial}_{time.strftime('%Y%m%d_%H%M%S')}"
                folder.mkdir(parents=True,exist_ok=True)

                self._volte_progress(5,"Kiểm tra Apple Seed VoLTE")
                installed=False
                try:
                    installed="package:vn.appleseed.volte" in self.shell(
                        "pm list packages vn.appleseed.volte",8
                    )
                except Exception as e:
                    out.append("CHECK APK ERROR: "+str(e))

                if not installed and apk.exists():
                    self._volte_progress(12,"Cài Apple Seed VoLTE bằng ADB")
                    rc,install_out=self.run([
                        "-s",self.serial,"install","-r","-d","-g",
                        "--no-incremental",str(apk)
                    ],90)
                    out.append(f"\\n--- ADB INSTALL rc={rc} ---\\n{install_out}")
                    if rc==0:
                        installed=True
                    else:
                        # ColorOS có thể chặn cài trực tiếp. Chép APK và mở
                        # Package Installer; không tự tắt bảo vệ hệ thống.
                        remote="/sdcard/AppleSeed/APK/AppleSeed_VoLTE.apk"
                        self._volte_progress(20,"ROM chặn ADB install — mở trình cài hệ thống")
                        self.run([
                            "-s",self.serial,"shell","mkdir","-p",
                            "/sdcard/AppleSeed/APK"
                        ],15)
                        rc_push,push_out=self.run([
                            "-s",self.serial,"push",str(apk),remote
                        ],90)
                        out.append(f"\\n--- PUSH APK rc={rc_push} ---\\n{push_out}")
                        if rc_push==0:
                            rc_view,view_out=self.run([
                                "-s",self.serial,"shell","am","start",
                                "-a","android.intent.action.VIEW",
                                "-d","file://"+remote,
                                "-t","application/vnd.android.package-archive",
                                "-f","0x10000000"
                            ],20)
                            out.append(
                                f"\\n--- PACKAGE INSTALLER rc={rc_view} ---\\n{view_out}"
                            )
                            out.append(
                                "\\n>>> Hãy bấm CÀI ĐẶT trên điện thoại. "
                                "Tool chờ tối đa 90 giây. <<<"
                            )
                            for _ in range(45):
                                time.sleep(2)
                                try:
                                    installed="package:vn.appleseed.volte" in self.shell(
                                        "pm list packages vn.appleseed.volte",8
                                    )
                                except Exception:
                                    installed=False
                                if installed:
                                    break

                if installed:
                    out.append("\\n✓ Apple Seed VoLTE đã được cài.")
                else:
                    out.append("\\n⚠ Chưa có Apple Seed VoLTE; tiếp tục phần ADB flags.")

                try:
                    (folder/"getprop.txt").write_text(
                        self.shell("getprop",20),encoding="utf-8"
                    )
                except Exception as e:
                    out.append("BACKUP: "+str(e))

                self._volte_progress(35,"Bật cờ VoLTE bằng ADB shell")
                for cmd in [
                    "settings put global volte_vt_enabled 1",
                    "settings put global enhanced_4g_mode_enabled 1",
                    "settings put global volte_enabled 1",
                    "settings put global carrier_vt_enabled 1",
                ]:
                    try:
                        rc,o=self.run(
                            ["-s",self.serial,"shell","sh","-c",cmd],8
                        )
                        out.append(f"\\n{cmd}\\nrc={rc}\\n{o}")
                    except Exception as e:
                        out.append(f"\\n{cmd}\\nERROR: {e}")

                self._volte_progress(55,"Khởi động Shizuku tự động")
                try:
                    shizuku_pkg="package:moe.shizuku.privileged.api" in self.shell(
                        "pm list packages moe.shizuku.privileged.api",8
                    )
                except Exception:
                    shizuku_pkg=False

                shizuku_started=False
                shizuku_msg=""
                if shizuku_pkg:
                    try:
                        shizuku_started,shizuku_msg=self.start_shizuku_via_adb()
                    except Exception as e:
                        shizuku_msg=str(e)

                out.append(
                    "\n--- SHIZUKU ---\n"
                    + (
                        "✓ Đã cài Shizuku và đã gửi lệnh START qua ADB."
                        if shizuku_started
                        else (
                            "✓ Đã thấy gói Shizuku nhưng chưa xác nhận server đang chạy."
                            + ("\n"+shizuku_msg if shizuku_msg else "")
                            if shizuku_pkg
                            else "⚠ Chưa thấy Shizuku."
                        )
                    )
                )

                if installed:
                    self._volte_progress(68,"Mở Apple Seed FIX_VOLTE")
                    rc,o=self.run([
                        "-s",self.serial,"shell","am","start",
                        "-a","vn.appleseed.volte.action.FIX_VOLTE",
                        "-n","vn.appleseed.volte/.MainActivity"
                    ],15)
                    out.append(f"\\n--- FIX_VOLTE rc={rc} ---\\n{o}")
                    if rc!=0:
                        rc2,o2=self.run([
                            "-s",self.serial,"shell","monkey",
                            "-p","vn.appleseed.volte","1"
                        ],15)
                        out.append(f"\\n--- OPEN APP FALLBACK rc={rc2} ---\\n{o2}")

                self._volte_progress(75,"Đọc lại cờ VoLTE")
                for key in [
                    "volte_vt_enabled",
                    "enhanced_4g_mode_enabled",
                    "volte_enabled",
                    "carrier_vt_enabled"
                ]:
                    try:
                        rc,o=self.run([
                            "-s",self.serial,"shell","settings","get",
                            "global",key
                        ],8)
                        out.append(f"\\nVERIFY {key} rc={rc}: {o.strip() or '—'}")
                    except Exception as e:
                        out.append(f"\\nVERIFY {key}: ERROR {e}")

                self._volte_progress(84,"Xác minh CarrierConfig (tối đa 5 giây)")
                try:
                    rc,o=self.run([
                        "-s",self.serial,"shell","dumpsys","carrier_config"
                    ],5)
                    filt="\\n".join(
                        x for x in o.splitlines()
                        if any(k in x.lower() for k in [
                            "carrier_volte",
                            "carrier_vt_",
                            "enhanced_4g",
                            "hide_enhanced",
                            "show_4g"
                        ])
                    )
                    out.append(
                        f"\\n--- CARRIER CONFIG rc={rc} ---\\n"
                        +(filt[:7000] if filt else o[:3000])
                    )
                except Exception as e:
                    out.append("\\n--- CARRIER CONFIG ---\\nKHÔNG PHẢN HỒI: "+str(e))

                self._volte_progress(94,"Xác minh IMS (tối đa 5 giây)")
                try:
                    rc,o=self.run([
                        "-s",self.serial,"shell","dumpsys","ims"
                    ],5)
                    out.append(f"\\n--- IMS rc={rc} ---\\n{o[:6000]}")
                except Exception as e:
                    out.append("\\n--- IMS ---\\nKHÔNG PHẢN HỒI: "+str(e))

                self._volte_progress(100,"VoLTE 1-Click hoàn tất")
                if installed:
                    if shizuku_pkg:
                        conclusion=(
                            "ADB flags đã ghi và đã thử START Shizuku bằng ADB. "
                            "Nếu đây là lần đầu, hãy bấm Cho phép quyền Shizuku cho Apple Seed; "
                            "sau đó FIX_VOLTE sẽ chạy CarrierConfig/IMS."
                        )
                    else:
                        conclusion=(
                            "ADB flags đã ghi. Chưa có Shizuku nên chỉ hoàn tất phần "
                            "ADB/public settings; CarrierConfig nâng cao chưa được xác nhận."
                        )
                else:
                    conclusion="Chưa cài được Apple Seed VoLTE; chỉ hoàn tất phần ADB flags."
                out.append("\\n===== KẾT LUẬN =====\\n"+conclusion)
            except Exception as e:
                out.append("\\n❌ LỖI THỰC THI: "+str(e))
            finally:
                self.showout(
                    self.volte_out,"APPLE SEED VoLTE 1-CLICK","\\n".join(out)
                )
                self.log("Apple Seed VoLTE 1-Click: hoàn tất kiểm tra.")

        self.threaded(w)

    def install_volte_app(self):
        if not self.require():return
        apk=self.base/"apps"/"AppleSeed_VoLTE.apk"
        if not apk.exists():
            QMessageBox.warning(self,"APK","Thiếu apps/AppleSeed_VoLTE.apk");return

        def w():
            out=["===== CÀI APP VoLTE — APPLE SEED ====="]
            original_verifier={}
            try:
                self._volte_progress(10,"Cài AppleSeed_VoLTE.apk bằng ADB")
                rc,o=self.run(["-s",self.serial,"install","-r","-d","-g","--no-incremental",str(apk)],120)
                out.append(f"\n--- ADB INSTALL #1 rc={rc} ---\n{o}")

                verification_failure=("INSTALL_FAILED_VERIFICATION_FAILURE" in o or
                                       "verification failure" in o.lower() or
                                       "Package Verification Result" in o)
                if rc!=0 and verification_failure:
                    self._volte_progress(28,"Tắt verifier ADB tạm thời")
                    for key in ("verifier_verify_adb_installs","package_verifier_enable"):
                        try:
                            _,v=self.run(["-s",self.serial,"shell","settings","get","global",key],8)
                            original_verifier[key]=v.strip()
                            self.run(["-s",self.serial,"shell","settings","put","global",key,"0"],8)
                            out.append(f"SET {key}=0 (cũ: {v.strip() or 'unknown'})")
                        except Exception as e: out.append(f"SET {key}=0 ERROR: {e}")

                    rc2,o2=self.run(["-s",self.serial,"install","-r","-d","-g","--no-incremental",str(apk)],120)
                    out.append(f"\n--- ADB INSTALL #2 rc={rc2} ---\n{o2}")
                    if rc2==0: rc,o=rc2,o2

                for key,v in original_verifier.items():
                    if v in ("0","1"):
                        try:self.run(["-s",self.serial,"shell","settings","put","global",key,v],8)
                        except:pass

                if rc==0:
                    pkg="package:vn.appleseed.volte" in self.shell("pm list packages vn.appleseed.volte",8)
                    if not pkg: raise RuntimeError("ADB báo thành công nhưng không thấy package vn.appleseed.volte.")
                    self._volte_progress(100,"AppleSeed VoLTE đã cài")
                    out.append("\n✅ APP ĐÃ CÀI THÀNH CÔNG.")
                    self.showout(self.volte_out,"CÀI APP VoLTE","\n".join(out))
                    self.log("AppleSeed VoLTE: installed successfully.")
                    return

                remote="/sdcard/AppleSeed/APK/AppleSeed_VoLTE.apk"
                self._volte_progress(55,"ADB bị ROM chặn — đưa APK vào máy")
                self.run(["-s",self.serial,"shell","mkdir","-p","/sdcard/AppleSeed/APK"],15)
                rc_push,o_push=self.run(["-s",self.serial,"push",str(apk),remote],90)
                out.append(f"\n--- PUSH rc={rc_push} ---\n{o_push}")
                if rc_push!=0: raise RuntimeError(o_push or "ADB push thất bại")

                self._volte_progress(75,"Mở trình cài hệ thống")
                launched=False
                for action in ("android.intent.action.VIEW","android.intent.action.INSTALL_PACKAGE"):
                    rc_i,o_i=self.run(["-s",self.serial,"shell","am","start",
                        "-a",action,"-d","file://"+remote,
                        "-t","application/vnd.android.package-archive","-f","0x10000000"],20)
                    out.append(f"\n--- {action} rc={rc_i} ---\n{o_i}")
                    if rc_i==0:
                        launched=True
                        break

                out.append("\nAPK: "+remote)
                if launched:
                    out.append("⚠ ROM/SafeCenter chặn cài im lặng; Package Installer đã mở.")
                    self.post(lambda:QMessageBox.information(
                        self,"Apple Seed VoLTE",
                        "Đã mở trình cài hệ thống. Nếu máy hỏi quyền bảo mật, cho phép cài APK rồi bấm CÀI ĐẶT."
                    ))
                else:
                    self.post(lambda:QMessageBox.warning(
                        self,"Cài AppleSeed VoLTE",
                        "ROM chặn cả ADB install và Package Installer. APK đã được chép vào /sdcard/AppleSeed/APK/."
                    ))
                self._volte_progress(100,"Hoàn tất")
                self.showout(self.volte_out,"CÀI APP VoLTE","\n".join(out))
            except Exception as e:
                for key,v in original_verifier.items():
                    if v in ("0","1"):
                        try:self.run(["-s",self.serial,"shell","settings","put","global",key,v],8)
                        except:pass
                out.append("\n❌ LỖI: "+str(e))
                self.showout(self.volte_out,"CÀI APP VoLTE","\n".join(out))
                self.post(lambda e=str(e):QMessageBox.warning(self,"Cài APP VoLTE lỗi",e))
        self.threaded(w)

    def open_volte_app(self):
        if not self.require():return
        def w():
            # Explicit component ổn định hơn monkey trên Samsung/ColorOS.
            rc,o=self.run(["-s",self.serial,"shell","am","start","-n","vn.appleseed.volte/.MainActivity"],15)
            if rc!=0:
                rc,o=self.run(["-s",self.serial,"shell","monkey","-p","vn.appleseed.volte","1"],15)
            self.showout(self.volte_out,"MỞ APP",f"rc={rc}\n{o}")
        self.threaded(w)

    def list_packages(self):
        if not self.require():return
        q=self.pkg.text().strip()
        def w():
            try:
                c="pm list packages"+((" | grep -i "+shquote(q)) if q else "")
                self.showout(self.app_out,"PACKAGES",self.shell(c,30))
            except Exception as e:self.showout(self.app_out,"PACKAGES","LỖI: "+str(e))
        self.threaded(w)

    def install_apk(self):
        if not self.require():return
        p,_=QFileDialog.getOpenFileName(self,"Chọn APK","","APK (*.apk)")
        if not p:return
        def w():
            out=["===== APPLE SEED APK INSTALLER =====",f"FILE: {p}"]
            original_verifier={}
            try:
                self._volte_progress(10,"Kiểm tra APK")
                rc,o=self.run(["-s",self.serial,"install","-r","-d","-g","--no-incremental",p],120)
                out.append(f"\n--- ADB INSTALL #1 rc={rc} ---\n{o}")

                verification_failure=("INSTALL_FAILED_VERIFICATION_FAILURE" in o or
                                       "verification failure" in o.lower() or
                                       "Package Verification Result" in o)
                if rc != 0 and verification_failure:
                    self._volte_progress(30,"Tắt xác minh APK qua ADB và thử lại")
                    for key in ("verifier_verify_adb_installs","package_verifier_enable"):
                        try:
                            _,v=self.run(["-s",self.serial,"shell","settings","get","global",key],8)
                            original_verifier[key]=v.strip()
                            self.run(["-s",self.serial,"shell","settings","put","global",key,"0"],8)
                            out.append(f"SET {key}=0 (cũ: {v.strip() or 'unknown'})")
                        except Exception as e:
                            out.append(f"SET {key}=0 ERROR: {e}")

                    rc2,o2=self.run(["-s",self.serial,"install","-r","-d","-g","--no-incremental",p],120)
                    out.append(f"\n--- ADB INSTALL #2 rc={rc2} ---\n{o2}")
                    if rc2==0: rc,o=rc2,o2

                for key,v in original_verifier.items():
                    if v in ("0","1"):
                        try:self.run(["-s",self.serial,"shell","settings","put","global",key,v],8)
                        except:pass

                if rc==0:
                    self._volte_progress(100,"Cài APK thành công")
                    out.append("\n✅ CÀI APK THÀNH CÔNG BẰNG ADB.")
                    self.showout(self.app_out,"CÀI APK","\n".join(out))
                    self.log(f"APK installed: {Path(p).name}")
                    return

                remote="/sdcard/AppleSeed/APK/"+Path(p).name
                self._volte_progress(55,"ADB bị chặn — đưa APK vào điện thoại")
                self.run(["-s",self.serial,"shell","mkdir","-p","/sdcard/AppleSeed/APK"],15)
                rc_push,o_push=self.run(["-s",self.serial,"push",p,remote],90)
                out.append(f"\n--- PUSH rc={rc_push} ---\n{o_push}")
                if rc_push!=0: raise RuntimeError(o_push or "Không push được APK")

                self._volte_progress(75,"Mở trình cài hệ thống")
                attempts=[
                    ["-s",self.serial,"shell","am","start","-a","android.intent.action.VIEW",
                     "-d","file://"+remote,"-t","application/vnd.android.package-archive","-f","0x10000000"],
                    ["-s",self.serial,"shell","am","start","-a","android.intent.action.INSTALL_PACKAGE",
                     "-d","file://"+remote,"-t","application/vnd.android.package-archive","-f","0x10000000"]
                ]
                launched=False
                for cmd in attempts:
                    rc_i,o_i=self.run(cmd,20)
                    out.append(f"\n--- PACKAGE INSTALLER rc={rc_i} ---\n{o_i}")
                    if rc_i==0:
                        launched=True
                        break

                if launched:
                    out.append("\n⚠ ROM/SafeCenter chặn cài im lặng; trình cài hệ thống đã mở.")
                    self.post(lambda:QMessageBox.information(
                        self,"Apple Seed — Cài APK",
                        "ADB bị ROM/SafeCenter chặn cài trực tiếp.\n\n"
                        "Apple Seed đã đưa APK vào máy và mở trình cài hệ thống.\n"
                        "Nếu có hộp thoại bảo mật, cho phép cài ứng dụng rồi bấm CÀI ĐẶT."
                    ))
                else:
                    out.append("\n❌ Không gọi được Package Installer của ROM.")
                    self.post(lambda:QMessageBox.warning(
                        self,"Cài APK lỗi",
                        "ROM đã chặn cả ADB install và Package Installer.\n"
                        "APK vẫn nằm trong /sdcard/AppleSeed/APK/ để cài thủ công."
                    ))
                self._volte_progress(100,"Hoàn tất")
                self.showout(self.app_out,"CÀI APK","\n".join(out))
            except Exception as e:
                for key,v in original_verifier.items():
                    if v in ("0","1"):
                        try:self.run(["-s",self.serial,"shell","settings","put","global",key,v],8)
                        except:pass
                out.append("\n❌ LỖI: "+str(e))
                self.showout(self.app_out,"CÀI APK","\n".join(out))
                self.post(lambda e=str(e):QMessageBox.warning(self,"Cài APK lỗi",e))
        self.threaded(w)

    def file_ls(self):
        if not self.require():return
        p=self.remote.text().strip() or "/sdcard"
        def w():
            try:self.showout(self.files_out,"LS "+p,self.shell("ls -la "+shquote(p),20))
            except Exception as e:self.showout(self.files_out,"LS","LỖI: "+str(e))
        self.threaded(w)

    def pull_file(self):
        if not self.require():return
        remote=self.remote.text().strip();p,_=QFileDialog.getSaveFileName(self,"Lưu file","","All files (*)")
        if not remote or not p:return
        def w():rc,o=self.run(["-s",self.serial,"pull",remote,p],60);self.showout(self.files_out,"PULL",f"rc={rc}\n{o}")
        self.threaded(w)

    def push_file(self):
        if not self.require():return
        p,_=QFileDialog.getOpenFileName(self,"Chọn file","","All files (*)")
        if not p:return
        remote=self.remote.text().strip() or "/sdcard/"
        def w():rc,o=self.run(["-s",self.serial,"push",p,remote],60);self.showout(self.files_out,"PUSH",f"rc={rc}\n{o}")
        self.threaded(w)

    def run_manual(self):
        if not self.require():return
        c=self.adb_edit.text().strip()
        if not c:return
        def w():
            try:self.showout(self.adb_out,"ADB SHELL",self.shell(c,60))
            except Exception as e:self.showout(self.adb_out,"ADB SHELL","LỖI: "+str(e))
        self.threaded(w)

    def closeEvent(self,e):
        try:self.stop_mirror()
        except:pass
        super().closeEvent(e)

def shquote(s): return "'"+str(s).replace("'","'\\''")+"'"

if __name__=="__main__":
    app=QApplication(sys.argv)
    app.setApplicationName(APP)

    # Splash Android hiển thị ngay khi mở tool để tránh cảm giác đứng/chậm.
    splash=AppleSeedSplash()
    splash.show()
    app.processEvents()
    splash.set_progress(18, "Đang khởi tạo giao diện...")
    w=AndroidTool()
    splash.set_progress(72, "Đang khởi động ADB...")
    app.processEvents()
    splash.set_progress(100, "Apple Seed sẵn sàng.")

    # Giữ splash ngắn rồi chuyển mượt sang cửa sổ chính.
    def open_main():
        splash.close()
        w.show()
        w.raise_()
        w.activateWindow()

    QTimer.singleShot(420, open_main)
    sys.exit(app.exec())
