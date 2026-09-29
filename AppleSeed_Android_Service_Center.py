import os, sys, time, shutil, subprocess, threading
from pathlib import Path
from PySide6.QtCore import Qt, QEvent
from PySide6.QtWidgets import QApplication,QMainWindow,QWidget,QVBoxLayout,QHBoxLayout,QGridLayout,QLabel,QPushButton,QComboBox,QTextEdit,QLineEdit,QTabWidget,QMessageBox,QFileDialog,QFrame,QStatusBar,QProgressBar

APP="Apple Seed Android Service Center"
VER="PySide6-1.0"

class CallEvent(QEvent):
    TYPE=QEvent.registerEventType()
    def __init__(self,fn):
        super().__init__(QEvent.Type(CallEvent.TYPE)); self.fn=fn

class AndroidTool(QMainWindow):
    def __init__(self):
        super().__init__()
        self.base=Path(__file__).resolve().parent
        self.adb=self.find_adb()
        self.serial=""
        self.setWindowTitle(f"{APP} • {VER}")
        self.resize(1580,960); self.setMinimumSize(1180,720)
        self.setStyleSheet(self.qss())
        self.build()
        self.refresh_devices()

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
            ("🔋 CHẨN ĐOÁN PIN",lambda:self.capture("dumpsys battery","PIN"),""),
            ("🌐 THÔNG TIN MẠNG",lambda:self.capture("getprop | grep -iE 'gsm|radio|baseband|operator|network'","MẠNG"),""),
            ("▣ CHỤP MÀN HÌNH",self.screenshot,""),("♻ REBOOT",lambda:self.reboot(""),"")]):
            x=QPushButton(txt); x.setObjectName(k); x.clicked.connect(fn); g.addWidget(x,i//3,i%3)
        l.addLayout(g)
        n=QLabel("PySide6 • chạy tác vụ nền • log rõ • ADB tích hợp • VoLTE/IMS 1-click"); n.setObjectName("muted"); l.addWidget(n)
        return w

    def volte_tab(self):
        w=QWidget(); l=QVBoxLayout(w); t=QLabel("VoLTE / IMS • APPLE SEED"); t.setStyleSheet("font-size:21pt;font-weight:800"); l.addWidget(t)
        n=QLabel("Native CarrierConfig → fallback flags → kiểm tra lại IMS. Không thay đổi IMEI/SIM lock/modem."); n.setObjectName("muted"); l.addWidget(n)
        g=QGridLayout()
        for txt,fn,k in [("⚡ KÍCH HOẠT VoLTE TỰ ĐỘNG 1-CLICK",self.native_volte,"red"),("🔎 KIỂM TRA VoLTE",self.volte_check,""),
                         ("🧪 IMS CHUYÊN SÂU",self.ims_check,""),("📱 CÀI APP VoLTE",self.install_volte_app,"green"),
                         ("▶ MỞ APP VoLTE",self.open_volte_app,""),("♻ REBOOT",lambda:self.reboot(""),"")]:
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
        if on:self.refresh_info()

    def refresh_info(self):
        def w():
            try:
                model=self.shell("getprop ro.product.model",8);android=self.shell("getprop ro.build.version.release",8);soc=self.shell("getprop ro.board.platform",8)
                self.post(lambda:(self.cards[0].val.setText((model or self.serial).splitlines()[0]),self.cards[1].val.setText((android or "—").splitlines()[0]),self.cards[2].val.setText((soc or "—").splitlines()[0]),self.cards[3].val.setText("KẾT NỐI")))
            except:pass
        self.threaded(w)

    def restart_adb(self):
        try:self.run(["kill-server"],10)
        except:pass
        self.refresh_devices()

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

    def native_volte(self):
        if not self.require():return
        dex=self.base/"assets"/"hbg_volte_fixer.dex"
        if not dex.exists():QMessageBox.critical(self,"VoLTE","Thiếu assets/hbg_volte_fixer.dex");return
        if not self.ask("VoLTE 1-CLICK","Chạy native CarrierConfig + fallback flags + kiểm tra IMS?"):return
        def w():
            out=["===== APPLE SEED NATIVE VoLTE 1-CLICK ====="]
            remote="/data/local/tmp/hbg_volte_fixer.dex"
            apk=self.base/"apps"/"AppleSeed_VoLTE.apk"
            try:
                folder=self.base/"backups"/f"{self.serial}_{time.strftime('%Y%m%d_%H%M%S')}";folder.mkdir(parents=True,exist_ok=True)
                # 1-CLICK: cài APK -> áp dụng cấu hình bằng ADB shell -> mở app.
                # WRITE_SECURE_SETTINGS là signature-only đối với app bên thứ ba, nên không ép pm grant.
                # ADB shell là lớp thực thi quyền; APK chỉ đọc/hiển thị trạng thái sau khi tool áp dụng.
                self._volte_progress(5,"Kiểm tra Apple Seed VoLTE APK")
                installed=False
                try:
                    installed="package:vn.appleseed.volte" in self.shell("pm list packages vn.appleseed.volte",8)
                except Exception:
                    installed=False

                if apk.exists() and not installed:
                    # ColorOS chặn adb install trên một số máy: chép APK vào máy
                    # rồi mở trình cài hệ thống. Chỉ cần người dùng bấm CÀI ĐẶT 1 lần.
                    apk_dir="/sdcard/AppleSeed/APK"
                    apk_remote=apk_dir+"/AppleSeed_VoLTE.apk"
                    self._volte_progress(8,"Chép APK vào Apple Seed/APK")
                    rc_mk,o_mk=self.run(["-s",self.serial,"shell","mkdir","-p",apk_dir],15)
                    out.append(f"\n--- MKDIR APK rc={rc_mk} ---\n{o_mk}")
                    rc_push,o_push=self.run(["-s",self.serial,"push",str(apk),apk_remote],60)
                    out.append(f"\n--- PUSH APK rc={rc_push} ---\n{o_push}")
                    if rc_push!=0:
                        raise RuntimeError("Không chép được APK: "+(o_push or "ADB push thất bại"))

                    self._volte_progress(12,"Mở trình cài APK — bấm CÀI ĐẶT")
                    view_cmd=["-s",self.serial,"shell","am","start","-a","android.intent.action.VIEW","-d","file://"+apk_remote,"-t","application/vnd.android.package-archive"]
                    rc_view,o_view=self.run(view_cmd,20)
                    out.append(f"\n--- OPEN APK INSTALLER rc={rc_view} ---\n{o_view}")
                    if rc_view!=0:
                        rc_view2,o_view2=self.run(["-s",self.serial,"shell","am","start","-a","android.intent.action.VIEW","-d","file:///sdcard/AppleSeed/APK/"],20)
                        out.append(f"\n--- OPEN APK FOLDER rc={rc_view2} ---\n{o_view2}")
                    out.append("\n>>> Bấm CÀI ĐẶT trên điện thoại. Tool tự chờ tối đa 120 giây. <<<")

                    for _ in range(60):
                        time.sleep(2)
                        try:
                            installed="package:vn.appleseed.volte" in self.shell("pm list packages vn.appleseed.volte",8)
                        except Exception:
                            installed=False
                        if installed:
                            break
                    if not installed:
                        raise RuntimeError("APK chưa được cài. Bấm CÀI ĐẶT trên điện thoại rồi chạy lại 1-CLICK.")
                elif installed:
                    out.append("\n--- APK ĐÃ CÓ SẴN, BỎ QUA CÀI ĐẶT ---")
                elif not apk.exists():
                    out.append("\n--- KHÔNG CÓ APK LOCAL, TIẾP TỤC NATIVE ---")

                if installed:
                    out.append("\n--- APK ĐÃ CÀI THÀNH CÔNG ---")
                try:(folder/"getprop.txt").write_text(self.shell("getprop",20),encoding="utf-8")
                except Exception as e: out.append("BACKUP: bỏ qua - "+str(e))
                self._volte_progress(18,"Đưa Native VoLTE runner vào máy")
                rc,o=self.run(["-s",self.serial,"push",str(dex),remote],30);out.append(f"PUSH DEX rc={rc}\n{o}")
                if rc==0:
                    for proc in ("app_process64","app_process"):
                        self._volte_progress(30,"Chạy Native CarrierConfig ("+proc+")")
                        rc,o=self.run(["-s",self.serial,"shell",proc,"-Djava.class.path="+remote,"/system/bin","com.hbg.volte.VolteFixer","ENABLE"],25)
                        out.append(f"{proc} rc={rc}\n{o}")
                        if rc==0:break
                self._volte_progress(55,"Áp dụng VoLTE flags bằng ADB shell")
                for cmd in ["settings put global volte_vt_enabled 1","settings put global enhanced_4g_mode_enabled 1","settings put global volte_enabled 1","settings put global carrier_vt_enabled 1"]:
                    try:
                        rc,o=self.run(["-s",self.serial,"shell","sh","-c",cmd],8)
                        out.append(f"\n{cmd}\nrc={rc}\n{o}")
                    except Exception as e: out.append(f"\n{cmd}\nTIMEOUT/ERROR: {e}")
                self._volte_progress(68,"Đọc lại 4 cờ VoLTE")
                for key in ["volte_vt_enabled","enhanced_4g_mode_enabled","volte_enabled","carrier_vt_enabled"]:
                    try:
                        rc,o=self.run(["-s",self.serial,"shell","settings","get","global",key],8)
                        out.append(f"\nVERIFY {key} rc={rc}: {o.strip() or '—'}")
                    except Exception as e: out.append(f"\nVERIFY {key}: ERROR {e}")
                self._volte_progress(74,"Mở Apple Seed VoLTE")
                try:
                    rc,o=self.run(["-s",self.serial,"shell","monkey","-p","vn.appleseed.volte","1"],15)
                    out.append(f"\n--- OPEN APP rc={rc} ---\n{o}")
                except Exception as e:
                    out.append(f"\n--- OPEN APP ---\nERROR: {e}")
                self._volte_progress(78,"Đọc trạng thái thiết bị")
                for a,cmd,t in [("MODEL","getprop ro.product.model",5),("ANDROID","getprop ro.build.version.release",5)]:
                    try:
                        rc,o=self.run(["-s",self.serial,"shell","sh","-c",cmd],t);out.append(f"\n--- {a} rc={rc} ---\n{o}")
                    except Exception as e:out.append(f"\n--- {a} ---\nERROR: {e}")
                # Một số ColorOS treo dumpsys carrier_config/ims. Đây chỉ là bước VERIFY,
                # không được phép làm Native 1-Click báo lỗi toàn bộ.
                self._volte_progress(82,"Xác minh CarrierConfig (tối đa 3 giây)")
                try:
                    rc,o=self.run(["-s",self.serial,"shell","dumpsys","carrier_config"],4)
                    filt="\n".join(x for x in o.splitlines() if any(k in x.lower() for k in ["carrier_volte","carrier_vt_","enhanced_4g","hide_enhanced","show_4g"]))
                    out.append(f"\n--- CARRIER CONFIG rc={rc} ---\n{filt[:7000] if filt else o[:3000]}")
                except Exception as e:
                    out.append("\n--- CARRIER CONFIG ---\nKHÔNG PHẢN HỒI (bỏ qua bước verify): "+str(e))
                self._volte_progress(92,"Xác minh IMS (tối đa 3 giây)")
                try:
                    rc,o=self.run(["-s",self.serial,"shell","dumpsys","ims"],4)
                    out.append(f"\n--- IMS rc={rc} ---\n{o[:5000]}")
                except Exception as e:
                    out.append("\n--- IMS ---\nKHÔNG PHẢN HỒI (bỏ qua bước verify): "+str(e))
                self._volte_progress(100,"Native VoLTE 1-Click hoàn tất")
                out.append("\n===== KẾT LUẬN =====\n1-CLICK đã hoàn thành chuỗi CÀI APK → ADB FLAGS → MỞ APP. WRITE_SECURE_SETTINGS không cần cấp cho APK.")
            except Exception as e:
                out.append("\nLỖI THỰC THI: "+str(e))
            finally:
                try:self.run(["-s",self.serial,"shell","rm","-f",remote],8)
                except:pass
                self.showout(self.volte_out,"NATIVE 1-CLICK","\n".join(out));self.log("VoLTE Native 1-Click hoàn tất.")
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
            rc,o=self.run(["-s",self.serial,"shell","monkey","-p","vn.appleseed.volte","1"],15);self.showout(self.volte_out,"MỞ APP",f"rc={rc}\n{o}")
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

def shquote(s): return "'"+str(s).replace("'","'\\''")+"'"

if __name__=="__main__":
    app=QApplication(sys.argv);app.setApplicationName(APP);w=AndroidTool();w.show();sys.exit(app.exec())
