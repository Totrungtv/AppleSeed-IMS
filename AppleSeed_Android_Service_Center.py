
import os, re, json, time, shutil, subprocess, threading, queue, urllib.request, urllib.parse, html as htmlmod
from pathlib import Path
import tkinter as tk
from tkinter import ttk, messagebox, filedialog

APP = "Apple Seed Android Service Tool"
VER = "CLEAN-1CLICK"

class AndroidTool:
    def __init__(self, root):
        self.root = root
        self.root.title(f"{APP} v{VER}")
        self.root.geometry("1650x1000")
        self.root.minsize(1350, 820)
        self.root.configure(bg="#0b1020")
        self.q = queue.Queue()
        self.adb = None
        self.devices = []
        self.selected = None
        self.busy = False
        self.base = Path(__file__).resolve().parent
        self.backups = self.base / "backups"
        self.captures = self.base / "captures"
        self.backups.mkdir(exist_ok=True)
        self.captures.mkdir(exist_ok=True)
        self._style()
        self._build()
        self._find_adb()
        self.root.after(100, self._pump)

    def _style(self):
        s = ttk.Style()
        try: s.theme_use("clam")
        except: pass
        s.configure("TNotebook", background="#0b1020", borderwidth=0)
        s.configure("TNotebook.Tab", background="#172033", foreground="#e5e7eb",
                    padding=(18, 10), font=("Segoe UI", 10, "bold"))
        s.map("TNotebook.Tab", background=[("selected","#2563eb")])
        s.configure("TCombobox", fieldbackground="#0f172a", background="#0f172a",
                    foreground="#e5e7eb", arrowcolor="#e5e7eb",
                    selectbackground="#1d4ed8", selectforeground="#ffffff")
        s.map("TCombobox",
              fieldbackground=[("readonly", "#0f172a"), ("disabled", "#0f172a")],
              foreground=[("readonly", "#e5e7eb"), ("disabled", "#64748b")],
              selectbackground=[("readonly", "#1d4ed8")],
              selectforeground=[("readonly", "#ffffff")])
        s.configure("Treeview", background="#0f172a", fieldbackground="#0f172a",
                    foreground="#e5e7eb", rowheight=27)
        s.configure("Treeview.Heading", background="#1e293b", foreground="#e5e7eb")

    def _btn(self, p, text, cmd, bg="#1e293b", fg="#e5e7eb", side=None):
        b = tk.Button(p, text=text, command=cmd, bg=bg, fg=fg,
                      activebackground=bg, activeforeground="white",
                      relief="flat", bd=0, padx=10, pady=8,
                      font=("Segoe UI", 10, "bold"), cursor="hand2")
        if side: b.pack(side=side, padx=3)
        else: b.pack(fill="x", pady=3)
        return b

    def _build(self):
        # ===== GIAO DIỆN VIP MỚI =====
        top = tk.Frame(self.root, bg="#080d18", height=72)
        top.pack(fill="x", padx=12, pady=(10, 6))
        top.pack_propagate(False)
        tk.Label(top, text="◉  Apple Seed", bg="#080d18", fg="#f8fafc",
                 font=("Segoe UI", 24, "bold")).pack(side="left", padx=(14, 2))
        tk.Label(top, text="ANDROID SERVICE CENTER", bg="#080d18", fg="#38bdf8",
                 font=("Segoe UI", 24, "bold")).pack(side="left")
        tk.Label(top, text=f"  VIP v{VER}  •  ADB SERVICE SUITE", bg="#080d18", fg="#64748b",
                 font=("Segoe UI", 10, "bold")).pack(side="left", pady=(13, 0))
        self.adb_status = tk.Label(top, text="ADB: Chưa sẵn sàng", bg="#3b1720",
                                   fg="#fecaca", font=("Segoe UI", 10, "bold"), padx=14, pady=8)
        self.adb_status.pack(side="right", padx=8)

        bar = tk.Frame(self.root, bg="#101827", height=54)
        bar.pack(fill="x", padx=12, pady=(0, 8))
        bar.pack_propagate(False)
        tk.Label(bar, text="THIẾT BỊ", bg="#101827", fg="#64748b",
                 font=("Segoe UI", 8, "bold")).pack(side="left", padx=(12, 6))
        self.combo = ttk.Combobox(bar, state="readonly", width=30)
        self.combo.pack(side="left", padx=4, pady=9)
        self._btn(bar, "↻  LÀM MỚI", self.refresh_devices, "#1e293b", side="left")
        self._btn(bar, "CHỌN ADB.EXE", self.choose_adb, "#1e293b", side="left")
        self._btn(bar, "KHỞI ĐỘNG LẠI ADB", self.restart_adb, "#1e293b", side="left")
        tk.Label(bar, text="ADB là lớp giao tiếp chính của bộ công cụ.", bg="#101827", fg="#475569",
                 font=("Segoe UI", 8)).pack(side="right", padx=14)

        body = tk.Frame(self.root, bg="#080d18")
        body.pack(fill="both", expand=True, padx=12, pady=(0, 10))

        self.sidebar = tk.Frame(body, bg="#0d1422", width=235)
        self.sidebar.pack(side="left", fill="y", padx=(0, 8))
        self.sidebar.pack_propagate(False)
        tk.Label(self.sidebar, text="TRUNG TÂM DỊCH VỤ", bg="#0d1422", fg="#38bdf8",
                 font=("Segoe UI", 11, "bold")).pack(fill="x", padx=14, pady=(16, 10), anchor="w")
        tk.Label(self.sidebar, text="◆  ANDROID  •  ADB  •  DIAGNOSTIC  ◆", bg="#0d1422", fg="#64748b",
                 font=("Segoe UI", 8, "bold")).pack(fill="x", padx=14, pady=(0, 10), anchor="w")

        self.pages = tk.Frame(body, bg="#0d1422")
        self.pages.pack(side="left", fill="both", expand=True)

        self.tab_home = tk.Frame(self.pages, bg="#0d1422")
        self.tab_device = tk.Frame(self.pages, bg="#0d1422")
        self.tab_control = tk.Frame(self.pages, bg="#0d1422")
        self.tab_apps = tk.Frame(self.pages, bg="#0d1422")
        self.tab_files = tk.Frame(self.pages, bg="#0d1422")
        self.tab_diag = tk.Frame(self.pages, bg="#0d1422")
        self.tab_network = tk.Frame(self.pages, bg="#0d1422")
        self.tab_volte = tk.Frame(self.pages, bg="#0d1422")
        self.tab_log = tk.Frame(self.pages, bg="#0d1422")
        self.tab_adb = tk.Frame(self.pages, bg="#0d1422")
        self.tab_knowledge = tk.Frame(self.pages, bg="#0d1422")
        self.tab_online = tk.Frame(self.pages, bg="#0d1422")
        self._home_tab()
        self._device_tab(); self._control_tab(); self._apps_tab()
        self._files_tab(); self._diag_tab(); self._network_tab(); self._volte_tab(); self._adb_tab(); self._knowledge_tab(); self._online_docs_tab(); self._log_tab()

        nav = [
            ("⌂", "TỔNG QUAN", self.tab_home),
            ("▣", "THIẾT BỊ", self.tab_device),
            ("⚙", "ĐIỀU KHIỂN", self.tab_control),
            ("▤", "ỨNG DỤNG", self.tab_apps),
            ("□", "TỆP / DỮ LIỆU", self.tab_files),
            ("⌁", "CHẨN ĐOÁN", self.tab_diag),
            ("◉", "MẠNG / SÓNG", self.tab_network),
            ("☎", "VoLTE / IMS", self.tab_volte),
            (">_", "LỆNH ADB", self.tab_adb),
            ("✦", "KHO KIẾN THỨC", self.tab_knowledge),
            ("◎", "TÀI LIỆU TRỰC TUYẾN", self.tab_online),
            ("≡", "NHẬT KÝ HỆ THỐNG", self.tab_log),
        ]
        self.nav_buttons = []
        for icon, title, page in nav:
            b = tk.Button(self.sidebar, text=f" {icon}   {title}", anchor="w",
                          command=lambda p=page, t=title: self._show_page(p, t),
                          bg="#0d1422", fg="#cbd5e1", activebackground="#172554",
                          activeforeground="#ffffff", relief="flat", bd=0,
                          padx=12, pady=11, font=("Segoe UI", 10, "bold"), cursor="hand2")
            b.pack(fill="x", padx=8, pady=2)
            self.nav_buttons.append((b, page))

        tk.Frame(self.sidebar, bg="#1e293b", height=1).pack(fill="x", padx=12, pady=12)
        tk.Label(self.sidebar, text="AN TOÀN", bg="#0d1422", fg="#64748b",
                 font=("Segoe UI", 9, "bold")).pack(anchor="w", padx=14)
        tk.Label(self.sidebar, text="Chỉ thao tác trên thiết bị\nđược ủy quyền.", justify="left",
                 bg="#0d1422", fg="#475569", font=("Segoe UI", 9)).pack(anchor="w", padx=14, pady=(4, 10))

        self._show_page(self.tab_home, "TỔNG QUAN")

    def _show_page(self, page, title=""):
        for child in self.pages.winfo_children():
            child.pack_forget()
        page.pack(fill="both", expand=True)
        for b, p in getattr(self, "nav_buttons", []):
            b.configure(bg="#172554" if p is page else "#0d1422",
                        fg="#ffffff" if p is page else "#cbd5e1")
        if page is self.tab_home:
            self._refresh_home()

    def _home_tab(self):
        head = tk.Frame(self.tab_home, bg="#0d1422")
        head.pack(fill="x", padx=14, pady=(14, 8))
        tk.Label(head, text="▣  BẢNG ĐIỀU KHIỂN ANDROID", bg="#0d1422", fg="#f8fafc",
                 font=("Segoe UI", 20, "bold")).pack(side="left")
        tk.Label(head, text="  Một giao diện — toàn bộ công cụ dịch vụ", bg="#0d1422", fg="#64748b",
                 font=("Segoe UI", 9)).pack(side="left", pady=(7, 0))

        cards = tk.Frame(self.tab_home, bg="#0d1422")
        cards.pack(fill="x", padx=14, pady=6)
        self.home_device = self._stat_card(cards, "THIẾT BỊ", "Chưa chọn", "#38bdf8")
        self.home_android = self._stat_card(cards, "ANDROID", "—", "#a78bfa")
        self.home_soc = self._stat_card(cards, "NỀN TẢNG", "—", "#34d399")
        self.home_adb = self._stat_card(cards, "ADB", "Đang kiểm tra", "#fbbf24")
        for c in range(4): cards.grid_columnconfigure(c, weight=1)

        quick = tk.LabelFrame(self.tab_home, text="THAO TÁC NHANH", bg="#0d1422", fg="#e2e8f0",
                              font=("Segoe UI", 11, "bold"), bd=1, relief="groove")
        quick.pack(fill="x", padx=14, pady=10)
        qgrid = tk.Frame(quick, bg="#0d1422"); qgrid.pack(fill="x", padx=10, pady=10)
        actions = [
            ("🔍  PHÂN TÍCH THIẾT BỊ", lambda:self._quick_page(self.tab_device, self.analyze), "#2563eb"),
            ("📶  KIỂM TRA VoLTE / IMS", lambda:self._quick_page(self.tab_volte, self.volte_check), "#7c3aed"),
            ("🔋  CHẨN ĐOÁN PIN", lambda:self._quick_page(self.tab_diag, lambda:self.capture_command("dumpsys battery", "diag_out", "PIN")), "#0f766e"),
            ("🌐  THÔNG TIN MẠNG", lambda:self._quick_page(self.tab_network, lambda:self.capture_command("getprop | grep -iE 'gsm|radio|baseband|operator|network'", "net_out", "THÔNG TIN SÓNG")), "#0e7490"),
            ("▤  QUẢN LÝ ỨNG DỤNG", lambda:self._quick_page(self.tab_apps, self.list_packages), "#334155"),
            ("▣  CHỤP MÀN HÌNH", lambda:self._quick_page(self.tab_control, self.screenshot), "#334155"),
        ]
        for i,(t,c,bg) in enumerate(actions):
            tk.Button(qgrid,text=t,command=c,bg=bg,fg="white",activebackground=bg,
                      relief="flat",bd=0,pady=12,font=("Segoe UI",10,"bold"),cursor="hand2").grid(
                      row=i//3,column=i%3,sticky="nsew",padx=4,pady=4)
        for c in range(3): qgrid.grid_columnconfigure(c, weight=1)

        note = tk.Label(self.tab_home,
            text="Apple Seed Android Service Center • ADB • Điều khiển • Ứng dụng • Tệp • Chẩn đoán • Mạng • VoLTE / IMS",
            bg="#101827", fg="#64748b", font=("Segoe UI", 10), pady=12)
        note.pack(fill="x", padx=14, pady=(6, 14))

    def _stat_card(self, parent, title, value, accent):
        f=tk.Frame(parent,bg="#101827",height=88,bd=1,relief="flat")
        # grid placement is assigned by caller through the return object's grid info
        idx=len(getattr(parent,"_cards",[]))
        if not hasattr(parent,"_cards"): parent._cards=[]
        parent._cards.append(f)
        f.grid(row=0,column=idx,sticky="nsew",padx=4)
        tk.Label(f,text=title,bg="#101827",fg="#64748b",font=("Segoe UI",10,"bold")).pack(anchor="w",padx=12,pady=(10,0))
        label=tk.Label(f,text=value,bg="#101827",fg=accent,font=("Segoe UI",13,"bold"))
        label.pack(anchor="w",padx=12,pady=(4,10))
        f.value_label=label
        return f

    def _quick_page(self, page, command):
        self._show_page(page)
        command()

    def _refresh_home(self):
        if not hasattr(self,"home_device"): return
        s=self.serial()
        self.home_device.value_label.configure(text=s or "Chưa chọn")
        self.home_adb.value_label.configure(text="Sẵn sàng" if self.adb else "Chưa tìm thấy")
        if s:
            def w():
                try:
                    rc,out=self.run(["-s",s,"shell","getprop","ro.build.version.release"],8)
                    android=out.strip() or "—"
                    rc,out=self.run(["-s",s,"shell","getprop","ro.board.platform"],8)
                    soc=out.strip() or "—"
                    self.q.put(("home_stats",android,soc))
                except Exception:
                    self.q.put(("home_stats","—","—"))
            threading.Thread(target=w,daemon=True).start()

    def _panel(self,p,title):
        f=tk.LabelFrame(p,text=title,bg="#111827",fg="#e5e7eb",
                        font=("Segoe UI",10,"bold"),bd=1,relief="groove")
        f.pack(fill="both",expand=True,padx=10,pady=8)
        return f

    def _device_tab(self):
        p=self._panel(self.tab_device,"THÔNG TIN THIẾT BỊ")
        self.info, _ = self._text_area(p, font=("Consolas",11), wrap="word")
        bottom=tk.Frame(p,bg="#111827"); bottom.pack(fill="x",padx=10,pady=8)
        for text,cmd,bg in [
            ("🔍  PHÂN TÍCH THIẾT BỊ",self.analyze,"#2563eb"),
            ("SAO LƯU THÔNG TIN",self.backup,"#334155"),
            ("XUẤT BÁO CÁO",self.export_report,"#334155"),
        ]:
            self._btn(bottom,text,cmd,bg,side="left")

    def _control_tab(self):
        p=self._panel(self.tab_control,"ĐIỀU KHIỂN THIẾT BỊ")
        grid=tk.Frame(p,bg="#111827");grid.pack(fill="x",padx=10,pady=10)
        actions=[
            ("KHỞI ĐỘNG LẠI",lambda:self.reboot(""),"#334155"),
            ("CHẾ ĐỘ KHÔI PHỤC",lambda:self.reboot("recovery"),"#334155"),
            ("BOOTLOADER",lambda:self.reboot("bootloader"),"#334155"),
            ("▣  CHỤP MÀN HÌNH",self.screenshot,"#2563eb"),
            ("QUAY MÀN HÌNH",self.screenrecord,"#2563eb"),
            ("ĐÁNH THỨC",lambda:self.control_shell("input keyevent KEYCODE_WAKEUP","ĐÁNH THỨC"),"#1e293b"),
            ("KHÓA MÀN HÌNH",lambda:self.control_shell("input keyevent KEYCODE_POWER","KHÓA / NGUỒN"),"#1e293b"),
            ("ÂM LƯỢNG +",lambda:self.control_shell("input keyevent KEYCODE_VOLUME_UP","ÂM LƯỢNG +"),"#1e293b"),
            ("ÂM LƯỢNG -",lambda:self.control_shell("input keyevent KEYCODE_VOLUME_DOWN","ÂM LƯỢNG -"),"#1e293b"),
            ("CÀI ĐẶT",lambda:self.control_shell("am start -a android.settings.SETTINGS","MỞ CÀI ĐẶT"),"#1e293b"),
            ("TÙY CHỌN NHÀ PHÁT TRIỂN",lambda:self.control_shell("am start -a android.settings.DEVELOPMENT_SETTINGS","MỞ TÙY CHỌN NHÀ PHÁT TRIỂN"),"#1e293b"),
            ("APP CÀI ĐẶT",lambda:self.control_shell("am start -a android.settings.APPLICATION_SETTINGS","MỞ QUẢN LÝ ỨNG DỤNG"),"#1e293b"),
        ]
        for i,(t,c,bg) in enumerate(actions):
            b=tk.Button(grid,text=t,command=c,bg=bg,fg="#e5e7eb",relief="flat",bd=0,pady=12,font=("Segoe UI",10,"bold"))
            b.grid(row=i//4,column=i%4,sticky="nsew",padx=3,pady=3)
        for c in range(4):grid.grid_columnconfigure(c,weight=1)
        self.control_out, _ = self._text_area(p, font=("Consolas",11), wrap="word", height=10)
        warn=tk.Label(p,text="KẾT QUẢ ĐIỀU KHIỂN HIỂN THỊ NGAY TẠI TRANG NÀY. Chỉ thao tác trên máy đã được ủy quyền.",
                      bg="#241a08",fg="#fcd34d",font=("Segoe UI",9),pady=9)
        warn.pack(fill="x",padx=10,pady=10)

    def _apps_tab(self):
        p=self._panel(self.tab_apps,"QUẢN LÝ ỨNG DỤNG")
        row=tk.Frame(p,bg="#111827");row.pack(fill="x",padx=10,pady=8)
        tk.Label(row,text="Gói ứng dụng:",bg="#111827",fg="#94a3b8").pack(side="left")
        self.pkg=tk.Entry(row,bg="#0f172a",fg="#e5e7eb",insertbackground="white",relief="flat")
        self.pkg.pack(side="left",fill="x",expand=True,padx=6,ipady=7)
        self._btn(row,"LỌC / LIỆT KÊ",self.list_packages,"#1e293b",side="left")
        self.app_list, _ = self._text_area(p, font=("Consolas",11))
        self.app_status=tk.Label(p,text="KẾT QUẢ ỨNG DỤNG SẼ HIỂN THỊ NGAY TẠI TRANG NÀY",bg="#111827",fg="#64748b",anchor="w",font=("Segoe UI",10))
        self.app_status.pack(fill="x",padx=10)
        row2=tk.Frame(p,bg="#111827");row2.pack(fill="x",padx=10,pady=8)
        for t,c,bg in [
            ("📱 CÀI APP VoLTE CÓ SẴN",self.install_volte_app,"#16a34a"),
            ("▶ MỞ APP VoLTE",self.open_volte_app,"#0ea5e9"),
            ("CÀI APK TỪ FILE",self.install_apk,"#2563eb"),
            ("GỠ ỨNG DỤNG NGƯỜI DÙNG",self.uninstall_pkg,"#7f1d1d"),
            ("BUỘC DỪNG",self.force_stop,"#334155"),
            ("XÓA DỮ LIỆU",self.clear_data,"#7f1d1d"),
            ("MỞ ỨNG DỤNG",self.launch_pkg,"#14532d"),
            ("TRÍCH APK",self.extract_apk,"#334155"),
        ]: self._btn(row2,t,c,bg,side="left")

    def _files_tab(self):
        p=self._panel(self.tab_files,"QUẢN LÝ TỆP QUA ADB")
        row=tk.Frame(p,bg="#111827");row.pack(fill="x",padx=10,pady=8)
        tk.Label(row,text="Đường dẫn trên máy:",bg="#111827",fg="#94a3b8").pack(side="left")
        self.remote=tk.Entry(row,bg="#0f172a",fg="#e5e7eb",insertbackground="white",relief="flat")
        self.remote.insert(0,"/sdcard")
        self.remote.pack(side="left",fill="x",expand=True,padx=6,ipady=7)
        self._btn(row,"LS",self.file_ls,"#1e293b",side="left")
        self._btn(row,"PULL",self.pull_file,"#2563eb",side="left")
        self._btn(row,"PUSH",self.push_file,"#2563eb",side="left")
        self._btn(row,"XÓA",self.delete_file,"#7f1d1d",side="left")
        self.files_out, _ = self._text_area(p, font=("Consolas",11))

    def _diag_tab(self):
        p=self._panel(self.tab_diag,"CHẨN ĐOÁN")
        grid=tk.Frame(p,bg="#111827");grid.pack(fill="x",padx=10,pady=8)
        actions=[
            ("NHẬT KÝ HỆ THỐNG",lambda:self.capture_command("logcat -d -t 1000", "diag_out", "NHẬT KÝ HỆ THỐNG")),
            ("THÔNG TIN GETPROP",lambda:self.capture_command("getprop", "diag_out", "THÔNG TIN HỆ THỐNG")),
            ("THÔNG TIN DUMPSYS",lambda:self.capture_command("dumpsys", "diag_out", "DUMPSYS")),
            ("PIN",lambda:self.capture_command("dumpsys battery", "diag_out", "PIN")),
            ("BỘ NHỚ",lambda:self.capture_command("df -h", "diag_out", "BỘ NHỚ")),
            ("RAM",lambda:self.capture_command("dumpsys meminfo", "diag_out", "RAM")),
            ("TIẾN TRÌNH",lambda:self.capture_command("ps -A", "diag_out", "TIẾN TRÌNH")),
            ("DỊCH VỤ",lambda:self.capture_command("service list", "diag_out", "DỊCH VỤ")),
            ("CỬA SỔ",lambda:self.capture_command("dumpsys window", "diag_out", "CỬA SỔ")),
            ("USB",lambda:self.capture_command("dumpsys usb", "diag_out", "USB")),
            ("CẢM BIẾN",lambda:self.capture_command("dumpsys sensorservice", "diag_out", "CẢM BIẾN")),
            ("CAMERA",lambda:self.capture_command("dumpsys media.camera", "diag_out", "CAMERA")),
        ]
        for i,(t,c) in enumerate(actions):
            b=tk.Button(grid,text=t,command=c,bg="#1e293b",fg="#e5e7eb",relief="flat",bd=0,pady=10,font=("Segoe UI",10,"bold"))
            b.grid(row=i//4,column=i%4,sticky="nsew",padx=3,pady=3)
        for c in range(4):grid.grid_columnconfigure(c,weight=1)
        self.diag_out, _ = self._text_area(p, font=("Consolas",11), wrap="none")
        self.diag_status=tk.Label(p,text="Sẵn sàng. Chọn một mục chẩn đoán.",bg="#111827",fg="#64748b",anchor="w",font=("Segoe UI",9),pady=5)
        self.diag_status.pack(fill="x",padx=10,pady=(0,6))

    def _network_tab(self):
        p=self._panel(self.tab_network,"MẠNG / SÓNG")
        grid=tk.Frame(p,bg="#111827");grid.pack(fill="x",padx=10,pady=8)
        for i,(t,c) in enumerate([
            ("THÔNG TIN SÓNG",lambda:self.capture_command("getprop | grep -iE 'gsm|radio|baseband|operator|network'", "net_out", "THÔNG TIN SÓNG")),
            ("ĐIỆN THOẠI",lambda:self.capture_command("dumpsys telephony.registry", "net_out", "ĐIỆN THOẠI")),
            ("THUÊ BAO",lambda:self.capture_command("dumpsys subscription", "net_out", "THUÊ BAO")),
            ("CẤU HÌNH NHÀ MẠNG",lambda:self.capture_command("dumpsys carrier_config", "net_out", "CẤU HÌNH NHÀ MẠNG")),
            ("IP / ĐỊNH TUYẾN",lambda:self.capture_command("ip addr; ip route", "net_out", "IP / ĐỊNH TUYẾN")),
            ("WI-FI",lambda:self.capture_command("dumpsys wifi", "net_out", "WI-FI")),
            ("KẾT NỐI",lambda:self.capture_command("dumpsys connectivity", "net_out", "KẾT NỐI")),
            ("VPN",lambda:self.capture_command("dumpsys connectivity | grep -i vpn", "net_out", "VPN")),
        ]):
            b=tk.Button(grid,text=t,command=c,bg="#1e293b",fg="#e5e7eb",relief="flat",bd=0,pady=10,font=("Segoe UI",10,"bold"))
            b.grid(row=i//4,column=i%4,sticky="nsew",padx=3,pady=3)
        for c in range(4):grid.grid_columnconfigure(c,weight=1)
        self.net_out, _ = self._text_area(p, font=("Consolas",11))
        self.net_status=tk.Label(p,text="Sẵn sàng. Chọn một mục mạng / sóng.",bg="#111827",fg="#64748b",anchor="w",font=("Segoe UI",9),pady=5)
        self.net_status.pack(fill="x",padx=10,pady=(0,6))

    def _volte_tab(self):
        p=self._panel(self.tab_volte,"DỊCH VỤ VoLTE / IMS")
        grid=tk.Frame(p,bg="#111827");grid.pack(fill="x",padx=10,pady=8)
        for i,(t,c,bg) in enumerate([
            ("⚡ KÍCH HOẠT VoLTE TỰ ĐỘNG 1-CLICK",self.oppo_native_one_click,"#dc2626"),
            ("KIỂM TRA VoLTE THÔNG MINH",self.volte_check,"#2563eb"),
            ("KIỂM TRA IMS CHUYÊN SÂU",self.ims_check,"#7c3aed"),
            ("BẬT CỜ VoLTE CƠ BẢN",self.volte_enable,"#16a34a"),
            ("CẤU HÌNH VIVO / IQOO",lambda:self.vendor_profile("vivo"),"#7c3aed"),
            ("CẤU HÌNH XIAOMI / POCO",lambda:self.vendor_profile("xiaomi"),"#2563eb"),
            ("ONE-CLICK OPPO VoLTE",self.oppo_volte_one_click,"#ea580c"),
            ("📱 CÀI APP APPLE SEED VoLTE",self.install_volte_app,"#16a34a"),
            ("▶ MỞ APP APPLE SEED VoLTE",self.open_volte_app,"#0ea5e9"),
            ("CẤU HÌNH OPPO / REALME",lambda:self.vendor_profile("oppo"),"#c2410c"),
            ("CẤU HÌNH SAMSUNG",lambda:self.vendor_profile("samsung"),"#a16207"),
            ("VERIFY AFTER KHỞI ĐỘNG LẠI",self.volte_check,"#14532d"),
        ]):
            b=tk.Button(grid,text=t,command=c,bg=bg,fg="white",relief="flat",bd=0,pady=10,font=("Segoe UI",10,"bold"))
            b.grid(row=i//4,column=i%4,sticky="nsew",padx=3,pady=3)
        for c in range(4):grid.grid_columnconfigure(c,weight=1)
        self.volte_out, _ = self._text_area(p, font=("Consolas",11))
        self.volte_status=tk.Label(p,text="Sẵn sàng. Chọn kiểm tra VoLTE / IMS.",bg="#111827",fg="#64748b",anchor="w",font=("Segoe UI",9),pady=5)
        self.volte_status.pack(fill="x",padx=10,pady=(0,6))
        guide=(
            "HƯỚNG DẪN APPLE SEED VoLTE\n"
            "1. Kết nối máy bằng USB và bật USB Debugging.\n"
            "2. Bấm CÀI APP APPLE SEED VoLTE: tool cài APK, cấp WRITE_SECURE_SETTINGS qua ADB và mở app.\n"
            "3. Trong app điện thoại, gạt công tắc VoLTE để bật/tắt 4 cờ VoLTE phổ biến.\n"
            "4. Nếu muốn kiểm tra trên tool PC, dùng KIỂM TRA VoLTE THÔNG MINH / KIỂM TRA IMS CHUYÊN SÂU.\n"
            "5. Nếu công tắc không có tác dụng hoặc IMS chưa Registered: kiểm tra SIM, carrier config, provisioning và modem.\n"
            "6. App/tool không thay đổi IMEI, SIM lock, carrier lock hay firmware modem.\n\n"
            "4 CỜ APP ĐIỀU KHIỂN: volte_vt_enabled • enhanced_4g_mode_enabled • volte_enabled • carrier_vt_enabled"
        )
        tk.Label(p,text=guide,justify="left",anchor="w",bg="#0b2a1b",fg="#bbf7d0",font=("Segoe UI",9),pady=9,padx=10).pack(fill="x",padx=10,pady=6)
        tk.Label(p,text="PASS chỉ khi có bằng chứng phù hợp từ SIM / IMS / mạng. Bật cờ cài đặt không có nghĩa IMS đã đăng ký.",
                 bg="#241a08",fg="#fcd34d",font=("Segoe UI",9),pady=8).pack(fill="x",padx=10,pady=8)

    def _text_area(self, parent, font=("Consolas",11), wrap="none", height=None):
        box=tk.Frame(parent,bg="#07101c")
        box.pack(fill="both",expand=True,padx=10,pady=8)
        text=tk.Text(box,bg="#07101c",fg="#cbd5e1",relief="flat",font=font,wrap=wrap)
        if height is not None: text.configure(height=height)
        y=tk.Scrollbar(box,orient="vertical",command=text.yview)
        x=tk.Scrollbar(box,orient="horizontal",command=text.xview)
        text.configure(yscrollcommand=y.set,xscrollcommand=x.set)
        text.grid(row=0,column=0,sticky="nsew")
        y.grid(row=0,column=1,sticky="ns")
        if wrap=="none": x.grid(row=1,column=0,sticky="ew")
        box.grid_rowconfigure(0,weight=1); box.grid_columnconfigure(0,weight=1)
        return text, box

    def _adb_tab(self):
        p=self._panel(self.tab_adb,"⌨  TRUNG TÂM LỆNH ADB THỦ CÔNG")

        # Command header: deliberately large and high-contrast so the manual input
        # is immediately visible to a technician.
        head=tk.Frame(p,bg="#0f1a2b",bd=1,relief="solid",highlightthickness=1,
                      highlightbackground="#2563eb")
        head.pack(fill="x",padx=10,pady=(8,6))
        tk.Label(head,text="⌨  ADB >",bg="#0f1a2b",fg="#38bdf8",
                 font=("Segoe UI",12,"bold")).pack(side="left",padx=(12,8),pady=10)
        self.adb_cmd=tk.Entry(head,bg="#020817",fg="#f8fafc",insertbackground="#38bdf8",
                              selectbackground="#2563eb",relief="flat",bd=0,
                              font=("Consolas",13),highlightthickness=1,
                              highlightbackground="#334155",highlightcolor="#38bdf8")
        self.adb_cmd.pack(side="left",fill="x",expand=True,padx=4,pady=9,ipady=9)
        self.adb_cmd.insert(0,"Nhập lệnh ADB hoặc lệnh shell tại đây...")
        self.adb_cmd.config(fg="#64748b")
        def focus_cmd(_=None):
            if self.adb_cmd.get()=="Nhập lệnh ADB hoặc lệnh shell tại đây...":
                self.adb_cmd.delete(0,"end"); self.adb_cmd.config(fg="#f8fafc")
        def restore_placeholder(_=None):
            if not self.adb_cmd.get().strip():
                self.adb_cmd.insert(0,"Nhập lệnh ADB hoặc lệnh shell tại đây..."); self.adb_cmd.config(fg="#64748b")
        self.adb_cmd.bind("<FocusIn>",focus_cmd)
        self.adb_cmd.bind("<FocusOut>",restore_placeholder)
        self.adb_cmd.bind("<Return>",lambda e:self.run_manual_adb())
        self._btn(head,"▶  CHẠY",self.run_manual_adb,"#2563eb",side="left")
        self._btn(head,"✕  XÓA",self.clear_adb_console,"#334155",side="left")

        row=tk.Frame(p,bg="#0d1422"); row.pack(fill="x",padx=10,pady=(0,6))
        tk.Label(row,text="CHẾ ĐỘ",bg="#0d1422",fg="#64748b",font=("Segoe UI",10,"bold")).pack(side="left",padx=(2,6))
        self.adb_mode=ttk.Combobox(row,state="readonly",values=["LỆNH ADB","ADB SHELL"],width=14)
        self.adb_mode.current(0); self.adb_mode.pack(side="left",padx=(0,10),pady=3)
        tk.Label(row,text="Lịch sử lệnh",bg="#0d1422",fg="#64748b",font=("Segoe UI",10,"bold")).pack(side="left",padx=(8,6))
        self.adb_history=ttk.Combobox(row,state="readonly",width=48,values=[])
        self.adb_history.pack(side="left",fill="x",expand=True,pady=3)
        self.adb_history.bind("<<ComboboxSelected>>",self.use_adb_history)
        tk.Label(row,text="Enter = chạy lệnh",bg="#0d1422",fg="#475569",font=("Segoe UI",10)).pack(side="right",padx=6)

        quick=tk.LabelFrame(p,text="⚡ LỆNH NHANH",bg="#0d1422",fg="#e2e8f0",
                            font=("Segoe UI",9,"bold"),bd=1,relief="groove")
        quick.pack(fill="x",padx=10,pady=(0,7))
        q=tk.Frame(quick,bg="#0d1422"); q.pack(fill="x",padx=8,pady=7)
        quicks=[
            ("▣  THIẾT BỊ","devices"),("●  TRẠNG THÁI","get-state"),
            ("⌁  MODEL","shell getprop ro.product.model"),("◆  ANDROID","shell getprop ro.build.version.release"),
            ("⚙  GETPROP","shell getprop"),("▤  GÓI APP","shell pm list packages -3"),
            ("🔋  PIN","shell dumpsys battery"),("◉  IP","shell ip addr"),
        ]
        for i,(label,cmd) in enumerate(quicks):
            tk.Button(q,text=label,command=lambda c=cmd:self.run_quick_adb(c),
                      bg="#172033",fg="#e5e7eb",activebackground="#1d4ed8",
                      activeforeground="white",relief="flat",bd=0,padx=10,pady=9,
                      font=("Segoe UI",10,"bold"),cursor="hand2").grid(row=i//4,column=i%4,sticky="ew",padx=3,pady=3)
        for c in range(4): q.grid_columnconfigure(c,weight=1)

        out_frame=tk.LabelFrame(p,text="▣  KẾT QUẢ LỆNH",bg="#0d1422",fg="#e2e8f0",
                                font=("Segoe UI",9,"bold"),bd=1,relief="groove")
        out_frame.pack(fill="both",expand=True,padx=10,pady=(0,6))
        self.adb_out,_=self._text_area(out_frame,font=("Consolas",11),wrap="none")
        tk.Label(p,text="🔒 Chỉ thao tác trên thiết bị ADB đã được ủy quyền • Không hỗ trợ bypass FRP / khóa SIM / thay IMEI.",
                 bg="#241a08",fg="#fcd34d",font=("Segoe UI",9),pady=8).pack(fill="x",padx=10,pady=7)

    def clear_adb_console(self):
        self.adb_out.delete("1.0","end")
        self.adb_out.insert("end","SẴN SÀNG NHẬN LỆNH ADB.\n\n")

    def use_adb_history(self,_=None):
        cmd=self.adb_history.get().strip()
        if cmd:
            self.adb_cmd.delete(0,"end"); self.adb_cmd.insert(0,cmd); self.adb_cmd.config(fg="#f8fafc"); self.adb_cmd.focus_set()

    def _knowledge_tab(self):
        p=self._panel(self.tab_knowledge,"📚  KHO KIẾN THỨC ANDROID / ADB")
        top=tk.Frame(p,bg="#111827"); top.pack(fill="x",padx=10,pady=(8,4))
        tk.Label(top,text="BÍ KÍP KỸ THUẬT ANDROID",bg="#111827",fg="#38bdf8",font=("Segoe UI",15,"bold")).pack(side="left",padx=10,pady=8)
        tk.Label(top,text="Tài liệu offline + liên kết tài liệu chính thức",bg="#111827",fg="#64748b",font=("Segoe UI",10)).pack(side="left",pady=8)
        search=tk.Frame(p,bg="#111827"); search.pack(fill="x",padx=10,pady=4)
        tk.Label(search,text="TÌM TRONG BÍ KÍP",bg="#111827",fg="#94a3b8",font=("Segoe UI",10,"bold")).pack(side="left",padx=5)
        self.knowledge_query=tk.Entry(search,bg="#020817",fg="#f8fafc",insertbackground="#38bdf8",relief="flat",font=("Segoe UI",10))
        self.knowledge_query.pack(side="left",fill="x",expand=True,padx=6,ipady=7)
        self.knowledge_query.bind("<Return>",lambda e:self.search_knowledge())
        self._btn(search,"🔎 TÌM",self.search_knowledge,"#2563eb",side="left")
        body=tk.Frame(p,bg="#111827"); body.pack(fill="both",expand=True,padx=10,pady=6)
        left=tk.Frame(body,bg="#0d1422",width=260); left.pack(side="left",fill="y",padx=(0,8)); left.pack_propagate(False)
        right=tk.Frame(body,bg="#0d1422"); right.pack(side="left",fill="both",expand=True)
        self.knowledge_list=tk.Listbox(left,bg="#07101c",fg="#dbeafe",selectbackground="#1d4ed8",selectforeground="white",relief="flat",font=("Segoe UI",10))
        self.knowledge_list.pack(fill="both",expand=True,padx=6,pady=6)
        self.knowledge_list.bind("<<ListboxSelect>>",self.open_knowledge)
        self.knowledge_text,_=self._text_area(right,font=("Consolas",11),wrap="word")
        self.knowledge_items=self._build_knowledge_items(); self._knowledge_filtered=self.knowledge_items
        for item in self.knowledge_items:self.knowledge_list.insert("end",item["title"])
        if self.knowledge_items:self.knowledge_list.selection_set(0); self.open_knowledge()

    def _build_knowledge_items(self):
        return [
            {"title":"01 • ADB cơ bản","tags":"adb devices shell serial usb debugging","text":"""ADB — ANDROID DEBUG BRIDGE\n\nCú pháp nền:\n  adb devices\n  adb get-state\n  adb shell <lệnh Android>\n  adb -s SERIAL shell <lệnh>\n\nTrong ô LỆNH ADB của Apple Seed:\n• Chế độ ADB SHELL: nhập trực tiếp lệnh Android, ví dụ: ip addr\n• Nếu nhập “shell ip addr” hoặc “adb shell ip addr”, tool tự chuẩn hóa.\n• Chế độ LỆNH ADB: dùng lệnh adb như devices, install, pull, push, reboot.\n\nNếu có nhiều thiết bị, chọn SERIAL trên thanh thiết bị."""},
            {"title":"02 • Thiết bị & kết nối","tags":"device unauthorized offline usb wifi wireless debugging","text":"""KIỂM TRA KẾT NỐI\n\nadb devices -l\nadb get-state\nadb get-serialno\nadb reconnect\nadb kill-server\nadb start-server\n\nTrạng thái:\n• device: đã kết nối và được ủy quyền.\n• unauthorized: cần mở khóa và chấp nhận khóa RSA.\n• offline: ADB chưa sẵn sàng; thử reconnect/restart-server, đổi cáp/cổng hoặc bật lại USB debugging.\n\nAndroid 11+ hỗ trợ Wireless Debugging qua ADB."""},
            {"title":"03 • Shell / hệ thống","tags":"getprop settings service cmd proc cpu memory mount ps shell","text":"""CÁC LỆNH SOI MÁY\n\ngetprop\ngetprop ro.product.model\ngetprop ro.build.version.release\ngetprop ro.build.version.sdk\ngetprop ro.board.platform\ngetprop gsm.version.baseband\nsettings list global\nsettings list system\nsettings list secure\nservice list\ncmd -l\nid\nwhoami\nuname -a\ncat /proc/cpuinfo\ncat /proc/meminfo\nmount\nps -A\n"""},
            {"title":"04 • dumpsys chẩn đoán","tags":"dumpsys battery meminfo cpu activity window input camera connectivity wifi telephony","text":"""DUMPSYS — CHẨN ĐOÁN DỊCH VỤ HỆ THỐNG\n\nadb shell dumpsys -l\nadb shell dumpsys battery\nadb shell dumpsys meminfo\nadb shell dumpsys cpuinfo\nadb shell dumpsys activity\nadb shell dumpsys window\nadb shell dumpsys package <package>\nadb shell dumpsys input\nadb shell dumpsys media.camera\nadb shell dumpsys connectivity\nadb shell dumpsys wifi\nadb shell dumpsys telephony.registry\nadb shell dumpsys subscription\nadb shell dumpsys carrier_config\n\nDumpsys có thể rất dài; nên chỉ lấy đúng service cần kiểm tra."""},
            {"title":"05 • Logcat / PANIC","tags":"logcat panic crash radio main system events","text":"""LOGCAT — NHẬT KÝ HỆ THỐNG\n\nadb logcat\nadb logcat -d\nadb logcat -c\nadb logcat -b radio -d\nadb logcat -b crash -d\nadb logcat -b all -d\nadb logcat -v threadtime -d\n\nLọc ví dụ:\n  adb logcat -d *:W\n  adb logcat -b radio -d\n\nNên lưu log trước khi reboot nếu cần phân tích crash/panic. Một số tùy chọn cần quyền cao hơn."""},
            {"title":"06 • Ứng dụng / Package","tags":"pm package install uninstall apk force stop am activity","text":"""QUẢN LÝ ỨNG DỤNG\n\nadb shell pm list packages\nadb shell pm list packages -3\nadb shell pm path <package>\nadb install app.apk\nadb uninstall <package>\nadb shell am force-stop <package>\nadb shell monkey -p <package> 1\nadb shell cmd package resolve-activity --brief <package>\nadb shell dumpsys package <package>"""},
            {"title":"07 • Tệp / truyền dữ liệu","tags":"pull push ls mkdir rm files storage","text":"""ADB PULL / PUSH\n\nadb pull /sdcard/Download/file.txt .\nadb push file.txt /sdcard/Download/file.txt\nadb shell ls -lah /sdcard/\nadb shell mkdir -p /sdcard/AppleSeed\nadb shell rm -f /sdcard/AppleSeed/file.txt\n\nQuyền truy cập đường dẫn phụ thuộc Android/OEM."""},
            {"title":"08 • Mạng / IP / Wi‑Fi","tags":"ip addr route wifi connectivity network wireless","text":"""MẠNG\n\nadb shell ip addr\nadb shell ip route\nadb shell dumpsys connectivity\nadb shell dumpsys wifi\nadb shell cmd wifi status\nadb shell getprop | grep -iE 'gsm|radio|baseband|operator|network'\n\nWireless Debugging Android 11+ yêu cầu ghép đôi theo hướng dẫn của Android."""},
            {"title":"09 • Radio / SIM / IMS / VoLTE","tags":"sim radio ims volte carrier config provisioning modem","text":"""RADIO / SIM / IMS\n\ngetprop gsm.sim.state\ngetprop gsm.operator.alpha\ngetprop gsm.network.type\ngetprop gsm.voice.network.type\ngetprop gsm.data.network.type\nservice list | grep -iE 'ims|telephony|phone'\ndumpsys telephony.registry\ndumpsys subscription\ndumpsys carrier_config\n\nKhông kết luận VoLTE chỉ từ một cờ settings; IMS, provisioning, carrier config, modem, SIM và mạng thực tế đều có thể ảnh hưởng."""},
            {"title":"10 • Pin / bộ nhớ / RAM","tags":"battery storage ram memory cpu top df","text":"""CHẨN ĐOÁN TÀI NGUYÊN\n\nadb shell dumpsys battery\nadb shell df -h\nadb shell cat /proc/meminfo\nadb shell dumpsys meminfo\nadb shell dumpsys cpuinfo\nadb shell top -n 1\n\nĐối chiếu dữ liệu với hiện tượng thực tế; không kết luận phần cứng từ một giá trị đơn lẻ."""},
            {"title":"11 • USB / Debugging","tags":"usb debugging rsa authorized unauthorized driver","text":"""USB DEBUGGING\n\n• Bật Developer Options → USB debugging.\n• Kết nối USB lần đầu sẽ yêu cầu xác nhận khóa RSA.\n• unauthorized: kiểm tra màn hình khóa và hộp thoại RSA.\n• offline: restart-server/reconnect, đổi cáp/cổng và kiểm tra driver Windows."""},
            {"title":"12 • Android Architecture","tags":"architecture binder aidl hal vendor kernel selinux art partitions","text":"""KIẾN TRÚC ANDROID — GÓC NHÌN SỬA MÁY\n\nApplication → Android Framework → System Services / Binder → HAL → Vendor / Native → Kernel → Hardware\n\nKhái niệm: Binder/IPC, AIDL, HAL, init, logd, storaged, system/vendor/product, SELinux, ART.\n\nKhi chẩn đoán sâu, xác định lỗi nằm ở app, framework/service, vendor/HAL, kernel hay phần cứng trước khi thay đổi hệ thống."""},
            {"title":"13 • Lệnh thực dụng","tags":"cheat sheet commands adb reboot recovery bootloader","text":"""BỘ LỆNH THỰC DỤNG\n\nadb devices -l\nadb get-state\nadb shell id\nadb shell getprop\nadb shell dumpsys battery\nadb shell dumpsys meminfo\nadb shell dumpsys cpuinfo\nadb shell dumpsys -l\nadb shell service list\nadb shell pm list packages -3\nadb logcat -d\nadb reboot\nadb reboot recovery\nadb reboot bootloader\nadb pull <remote> <local>\nadb push <local> <remote>"""},
            {"title":"14 • Tài liệu chính thức","tags":"official docs android developer source aosp sdk adb","text":"""TÀI LIỆU CHÍNH THỨC\n\nADB / Android Debug Bridge\nhttps://developer.android.com/tools/adb\n\nSDK Platform-Tools\nhttps://developer.android.com/tools/releases/platform-tools\n\ndumpsys\nhttps://developer.android.com/tools/dumpsys\n\nlogcat\nhttps://developer.android.com/tools/logcat\n\nSDK command-line tools\nhttps://developer.android.com/tools/sdkmanager\n\nAOSP Architecture\nhttps://source.android.com/docs/core/architecture\n\nBinder\nhttps://source.android.com/docs/core/architecture/ipc/binder-overview\n\nAIDL\nhttps://source.android.com/docs/core/architecture/aidl\n\nHAL\nhttps://source.android.com/docs/core/architecture/hal\n\nTool chứa bản tóm tắt offline và đường dẫn nguồn chính thức; không sao chép nguyên văn toàn bộ website."""},
        ]

    def open_knowledge(self,_=None):
        sel=self.knowledge_list.curselection()
        if not sel:return
        source=getattr(self,"_knowledge_filtered",self.knowledge_items)
        item=source[sel[0]] if sel[0] < len(source) else self.knowledge_items[sel[0]]
        self.knowledge_text.delete("1.0","end"); self.knowledge_text.insert("end",item["text"]); self.knowledge_text.see("1.0")

    def search_knowledge(self):
        q=self.knowledge_query.get().strip().lower()
        if not q:
            self._knowledge_filtered=self.knowledge_items
        else:
            terms=q.split(); ranked=[]
            for item in self.knowledge_items:
                hay=(item["title"]+" "+item["text"]+" "+item["tags"]).lower(); score=sum(t in hay for t in terms)
                if score: ranked.append((score,item))
            ranked.sort(key=lambda x:x[0],reverse=True); self._knowledge_filtered=[item for _,item in ranked]
        self.knowledge_list.delete(0,"end")
        for item in self._knowledge_filtered:self.knowledge_list.insert("end",item["title"])
        if self._knowledge_filtered:self.knowledge_list.selection_set(0); self.open_knowledge()

    def _online_docs_tab(self):
        p=self._panel(self.tab_online,"🌐  THƯ VIỆN TÀI LIỆU ANDROID TRỰC TUYẾN")
        top=tk.Frame(p,bg="#111827"); top.pack(fill="x",padx=10,pady=(8,5))
        tk.Label(top,text="🌐 TÀI LIỆU ONLINE",bg="#111827",fg="#38bdf8",font=("Segoe UI",15,"bold")).pack(side="left",padx=10,pady=8)
        tk.Label(top,text="Xem nội dung tài liệu chính thức ngay trong Apple Seed",bg="#111827",fg="#64748b",font=("Segoe UI",10)).pack(side="left",pady=8)
        urlbar=tk.Frame(p,bg="#0d1422"); urlbar.pack(fill="x",padx=10,pady=(0,6))
        tk.Label(urlbar,text="URL",bg="#0d1422",fg="#64748b",font=("Segoe UI",10,"bold")).pack(side="left",padx=(4,6))
        self.doc_url=tk.Entry(urlbar,bg="#020817",fg="#e5e7eb",insertbackground="#38bdf8",relief="flat",font=("Consolas",11))
        self.doc_url.pack(side="left",fill="x",expand=True,padx=4,ipady=7)
        self._btn(urlbar,"🌐 XEM TRONG TOOL",self.load_online_doc,"#2563eb",side="left")
        self._btn(urlbar,"↗ MỞ TRÌNH DUYỆT",self.open_external_doc,"#334155",side="left")
        self._btn(urlbar,"XÓA",lambda:self.doc_view.delete("1.0","end"),"#334155",side="left")
        body=tk.Frame(p,bg="#111827"); body.pack(fill="both",expand=True,padx=10,pady=5)
        left=tk.Frame(body,bg="#0d1422",width=275); left.pack(side="left",fill="y",padx=(0,8)); left.pack_propagate(False)
        tk.Label(left,text="TÀI LIỆU CHÍNH THỨC",bg="#0d1422",fg="#38bdf8",font=("Segoe UI",9,"bold")).pack(anchor="w",padx=10,pady=(10,5))
        self.online_docs=[
            ("ADB — Android Debug Bridge","https://developer.android.com/tools/adb?hl=vi"),
            ("SDK Platform-Tools","https://developer.android.com/tools/releases/platform-tools?hl=vi"),
            ("dumpsys — chẩn đoán","https://developer.android.com/tools/dumpsys?hl=vi"),
            ("logcat — nhật ký hệ thống","https://developer.android.com/tools/logcat?hl=vi"),
            ("SDK Command-line Tools","https://developer.android.com/tools/sdkmanager?hl=vi"),
            ("AOSP — tài liệu Android","https://source.android.com/docs?hl=vi"),
            ("AOSP — kiến trúc","https://source.android.com/docs/core/architecture?hl=vi"),
            ("AOSP — Binder","https://source.android.com/docs/core/architecture/ipc/binder-overview?hl=vi"),
            ("AOSP — AIDL","https://source.android.com/docs/core/architecture/aidl?hl=vi"),
            ("AOSP — HAL","https://source.android.com/docs/core/architecture/hal?hl=vi"),
            ("AOSP — Kernel","https://source.android.com/docs/core/architecture/kernel?hl=vi"),
            ("AOSP — Partitions","https://source.android.com/docs/core/architecture/partitions?hl=vi"),
            ("AOSP — Connectivity","https://source.android.com/docs/core/connectivity?hl=vi"),
            ("AOSP — Power","https://source.android.com/docs/core/power?hl=vi"),
            ("AOSP — Camera","https://source.android.com/docs/core/camera?hl=vi"),
        ]
        self.online_list=tk.Listbox(left,bg="#07101c",fg="#dbeafe",selectbackground="#1d4ed8",selectforeground="white",relief="flat",font=("Segoe UI",10))
        self.online_list.pack(fill="both",expand=True,padx=6,pady=6)
        for title,_ in self.online_docs:self.online_list.insert("end",title)
        self.online_list.bind("<<ListboxSelect>>",self.select_online_doc)
        right=tk.Frame(body,bg="#07101c"); right.pack(side="left",fill="both",expand=True)
        status=tk.Frame(right,bg="#0f1a2b"); status.pack(fill="x")
        self.online_status=tk.Label(status,text="Chọn tài liệu bên trái rồi bấm XEM TRONG TOOL.",bg="#0f1a2b",fg="#94a3b8",anchor="w",font=("Segoe UI",9),padx=10,pady=7)
        self.online_status.pack(fill="x")
        self.doc_view,_=self._text_area(right,font=("Consolas",11),wrap="word")
        self.doc_view.insert("end","THƯ VIỆN TÀI LIỆU ANDROID\n\nChọn một tài liệu chính thức ở bên trái.\nApple Seed sẽ tải nội dung qua Internet và hiển thị ngay trong cửa sổ này.\n\nNếu mạng không có, hãy dùng KHO KIẾN THỨC để tra cứu offline.\n")

    def select_online_doc(self,_=None):
        sel=self.online_list.curselection()
        if not sel:return
        url=self.online_docs[sel[0]][1]
        self.doc_url.delete(0,"end"); self.doc_url.insert(0,url)
        self.load_online_doc()

    def open_external_doc(self):
        import webbrowser
        url=self.doc_url.get().strip()
        if url:webbrowser.open(url)

    def load_online_doc(self):
        url=self.doc_url.get().strip()
        if not url:return
        if not re.match(r"^https?://",url,re.I):
            url="https://"+url
            self.doc_url.delete(0,"end"); self.doc_url.insert(0,url)
        self.doc_view.delete("1.0","end")
        self.doc_view.insert("end",f"🌐 ĐANG TẢI TÀI LIỆU...\n{url}\n\n")
        self.online_status.configure(text="Đang tải tài liệu trực tuyến…")
        def w():
            try:
                req=urllib.request.Request(url,headers={"User-Agent":"AppleSeed Android Service Center/6.0"})
                with urllib.request.urlopen(req,timeout=15) as r:
                    raw=r.read(2_500_000)
                    enc=r.headers.get_content_charset() or "utf-8"
                text=htmlmod.unescape(raw.decode(enc,errors="replace"))
                text=re.sub(r"(?is)<(script|style|noscript).*?>.*?</\1>"," ",text)
                text=re.sub(r"(?is)<br\s*/?>","\n",text)
                text=re.sub(r"(?is)</(p|div|li|h[1-6]|pre|tr)>","\n",text)
                text=re.sub(r"(?is)<[^>]+>"," ",text)
                text=re.sub(r"[ \t]+"," ",text)
                text=re.sub(r"\n[ \t]+","\n",text)
                lines=[]
                for line in text.splitlines():
                    line=line.strip()
                    if line:lines.append(line)
                clean="\n".join(lines)
                if len(clean)>180000:clean=clean[:180000]+"\n\n[Đã cắt bớt nội dung để giữ giao diện nhẹ.]"
                self.q.put(("online_doc",url,clean))
            except Exception as e:
                self.q.put(("online_error",url,str(e)))
        threading.Thread(target=w,daemon=True).start()

    def _log_tab(self):
        self.logbox, _ = self._text_area(self.tab_log, font=("Consolas",11), wrap="word")
        self.logbox.insert("end","NHẬT KÝ HỆ THỐNG\n- Chỉ dùng cho lỗi ADB và sự kiện nền.\n- Kết quả thao tác luôn hiển thị ngay tại đúng trang chức năng.\n\n")
        self._btn(self.tab_log,"XÓA NHẬT KÝ",lambda:self.logbox.delete("1.0","end"),"#3b1720")

    # ---------- core ----------
    def _find_adb(self):
        here=Path(__file__).resolve().parent
        candidates=[
            here/"platform-tools"/"adb.exe", here/"adb.exe",
            Path.cwd()/"platform-tools"/"adb.exe", Path.cwd()/"adb.exe",
            Path(os.environ.get("LOCALAPPDATA",""))/"Android"/"Sdk"/"platform-tools"/"adb.exe",
        ]
        w=shutil.which("adb")
        if w:candidates.append(Path(w))
        for p in candidates:
            if p and p.exists():
                self.adb=str(p);break
        if self.adb:
            self.adb_status.configure(text="ADB: Sẵn sàng",bg="#10351f",fg="#bbf7d0")
            self.log(f"[ADB] {self.adb}");self.refresh_devices()
        else:self.log("[ADB] Chưa tìm thấy adb.exe.")

    def choose_adb(self):
        p=filedialog.askopenfilename(title="Chọn tệp adb.exe",filetypes=[("Tệp adb.exe","adb.exe"),("Tất cả tệp","*.*")])
        if p:
            self.adb=p;self.adb_status.configure(text="ADB: Sẵn sàng",bg="#10351f",fg="#bbf7d0");self.refresh_devices()

    def restart_adb(self):
        try:
            self.run(["kill-server"],10);self.run(["start-server"],15);self.log("[ADB] Đã khởi động lại máy chủ ADB.");self.refresh_devices()
        except Exception as e:self.log("[LỖI] "+str(e))

    def run(self,args,timeout=30):
        if not self.adb:raise RuntimeError("Chưa có adb.exe")
        p=subprocess.run([self.adb]+args,capture_output=True,text=True,timeout=timeout,encoding="utf-8",errors="replace")
        return p.returncode,(p.stdout or "")+(p.stderr or "")

    def serial(self):
        x=self.combo.get().strip()
        return x.split(" ",1)[0] if x else None

    def shell(self,cmd,timeout=30,log_output=False):
        s=self.serial()
        if not s: raise RuntimeError("Chưa chọn thiết bị")
        rc,out=self.run(["-s",s,"shell","sh","-c",cmd],timeout)
        if log_output:
            self.log(f"$ {cmd}\n{out[-5000:]}")
        return out

    def control_shell(self,cmd,label):
        def w():
            try:
                out=self.shell(cmd,30,log_output=False)
                text=f"{label}\n\n{out.strip() or 'Đã gửi lệnh thành công.'}"
                self.q.put(("panel_text","control_out",text))
            except Exception as e:
                self.q.put(("panel_text","control_out",f"{label}\n\nLỖI: {e}"))
        threading.Thread(target=w,daemon=True).start()

    def refresh_devices(self):
        threading.Thread(target=self._refresh,daemon=True).start()
    def _refresh(self):
        try:
            self.run(["start-server"],15);_,out=self.run(["devices"],15)
            ds=[]
            for line in out.splitlines()[1:]:
                a=line.split()
                if len(a)>=2:ds.append((a[0],a[1]))
            self.q.put(("devices",ds))
        except Exception as e:self.q.put(("err",str(e)))

    # ---------- device ----------
    def analyze(self):
        s=self.serial()
        if not s:
            messagebox.showwarning("Phân tích thiết bị", "Vui lòng chọn một thiết bị ADB trước.")
            return
        if self.busy:
            return
        self.busy=True
        self.info.delete("1.0","end")
        self.info.insert("end", "ĐANG PHÂN TÍCH THIẾT BỊ...\n\nVui lòng chờ, không cần bấm lại nút.\n")
        self.log("[PHÂN TÍCH] Bắt đầu phân tích thiết bị: " + s)
        def w():
            try:
                self.q.put(("analysis_progress", "[1/4] Kiểm tra kết nối ADB..."))
                rc,out=self.run(["-s",s,"get-state"],10)
                if rc != 0 or "device" not in out.lower():
                    raise RuntimeError("Thiết bị chưa ở trạng thái ADB sẵn sàng: " + out.strip())

                self.q.put(("analysis_progress", "[2/4] Đọc thông tin hệ thống..."))
                rc,props_out=self.run(["-s",s,"shell","getprop"],15)
                if rc != 0:
                    raise RuntimeError("Không đọc được getprop: " + props_out.strip())
                props={}
                for line in props_out.splitlines():
                    m=re.match(r"\[(.*?)\]: \[(.*?)\]", line.strip())
                    if m: props[m.group(1)]=m.group(2)
                def prop(k): return props.get(k, "Không có dữ liệu")

                self.q.put(("analysis_progress", "[3/4] Đọc pin, bộ nhớ và trạng thái mạng..."))
                def adb_shell(command, timeout):
                    rc,o=self.run(["-s",s,"shell","sh","-c",command],timeout)
                    return o.strip()
                try: battery=adb_shell("dumpsys battery",8)
                except Exception as e: battery="Không đọc được ("+str(e)+")"
                try: storage=adb_shell("df -h /data /sdcard 2>&1",8)
                except Exception as e: storage="Không đọc được ("+str(e)+")"

                bat_level="Không rõ"
                bm=re.search(r"level: (\d+)", battery)
                if bm: bat_level=bm.group(1)+"%"
                charging="Không rõ"
                cm=re.search(r"AC powered: (true|false)|USB powered: (true|false)|Wireless powered: (true|false)", battery)
                if cm: charging="Đang sạc / cấp nguồn" if cm.group(1)=="true" else "Không sạc"

                report=(
                    "===== KẾT QUẢ PHÂN TÍCH THIẾT BỊ =====\n\n"
                    f"Hãng sản xuất       : {prop('ro.product.manufacturer')}\n"
                    f"Thương hiệu         : {prop('ro.product.brand')}\n"
                    f"Model               : {prop('ro.product.model')}\n"
                    f"Android             : {prop('ro.build.version.release')}\n"
                    f"API Android         : {prop('ro.build.version.sdk')}\n"
                    f"Phiên bản hệ thống  : {prop('ro.build.display.id')}\n"
                    f"Nền tảng SoC        : {prop('ro.board.platform')}\n"
                    f"Phần cứng           : {prop('ro.hardware')}\n"
                    f"Baseband            : {prop('gsm.version.baseband')}\n"
                    f"Nhà mạng            : {prop('gsm.operator.alpha')}\n"
                    f"Quốc gia SIM        : {prop('gsm.operator.iso-country')}\n"
                    f"Trạng thái SIM      : {prop('gsm.sim.state')}\n"
                    f"Loại mạng           : {prop('gsm.network.type')}\n"
                    f"Mạng thoại          : {prop('gsm.voice.network.type')}\n"
                    f"Mạng dữ liệu        : {prop('gsm.data.network.type')}\n"
                    f"Pin                 : {bat_level} ({charging})\n\n"
                    "--- DUNG LƯỢNG BỘ NHỚ ---\n" + storage + "\n\n"
                    "Phân tích hoàn tất. Chi tiết lệnh vẫn nằm trong các mục chẩn đoán tương ứng."
                )
                self.q.put(("analysis_done",report))
            except Exception as e:
                self.q.put(("analysis_fail",str(e)))
            finally:
                self.q.put(("analysis_busy",False))
        threading.Thread(target=w,daemon=True).start()

    def backup(self):
        s=self.serial()
        if not s:return
        def w():
            stamp=time.strftime("%Y%m%d_%H%M%S")
            d=self.backups/f"{s}_{stamp}";d.mkdir(parents=True,exist_ok=True)
            cmds=["getprop","settings list global","settings list system","settings list secure",
                  "dumpsys telephony.registry","dumpsys subscription","dumpsys carrier_config","dumpsys battery"]
            for i,c in enumerate(cmds,1):
                try:(d/f"{i:02d}.txt").write_text(self.shell(c,40,log_output=False),encoding="utf-8",errors="ignore")
                except Exception as e:(d/f"{i:02d}_ERROR.txt").write_text(str(e),encoding="utf-8")
            self.log(f"[SAO LƯU] {d}")
        threading.Thread(target=w,daemon=True).start()

    def export_report(self):
        f=filedialog.asksaveasfilename(defaultextension=".txt",initialfile="AppleSeed_Android_Report.txt")
        if f:
            Path(f).write_text(self.info.get("1.0","end")+"\n\n"+self.logbox.get("1.0","end"),encoding="utf-8")
            self.log("[BÁO CÁO] "+f)

    # ---------- control ----------
    def reboot(self,arg):
        s=self.serial()
        if not s:return
        if not messagebox.askyesno("Xác nhận",f"Khởi động lại {arg or 'hệ thống'}?"):return
        cmd=["reboot"]+([arg] if arg else [])
        def w():
            try:
                rc,out=self.run(["-s",s]+cmd,10)
                text=f"KHỞI ĐỘNG LẠI: {arg or 'hệ thống'}\nMã kết quả: {rc}\n{out.strip() or 'Lệnh đã được gửi tới thiết bị.'}"
                self.q.put(("panel_text","control_out",text))
            except Exception as e:
                self.q.put(("panel_text","control_out","LỖI ĐIỀU KHIỂN\n"+str(e)))
        threading.Thread(target=w,daemon=True).start()

    def screenshot(self):
        s=self.serial()
        if not s:return
        f=self.captures/f"screenshot_{time.strftime('%Y%m%d_%H%M%S')}.png"
        try:
            p=subprocess.Popen([self.adb,"-s",s,"exec-out","screencap","-p"],stdout=subprocess.PIPE,stderr=subprocess.PIPE)
            data,_=p.communicate(timeout=20)
            f.write_bytes(data)
            self.q.put(("panel_text","control_out",f"CHỤP MÀN HÌNH THÀNH CÔNG\n\nĐã lưu: {f}"))
        except Exception as e:self.q.put(("panel_text","control_out","LỖI CHỤP MÀN HÌNH: "+str(e)))

    def screenrecord(self):
        messagebox.showinfo("Quay màn hình","Bấm OK để quay màn hình trong 15 giây. Tệp sẽ được lấy từ máy Android về thư mục captures.")
        s=self.serial()
        if not s:return
        remote="/sdcard/AppleSeed_record.mp4";local=self.captures/f"record_{time.strftime('%Y%m%d_%H%M%S')}.mp4"
        def w():
            try:
                self.shell(f"screenrecord --time-limit 15 {remote}",20)
                self.run(["-s",s,"pull",remote,str(local)],30)
                self.shell(f"rm -f {remote}",10,log_output=False)
                self.q.put(("panel_text","control_out",f"QUAY MÀN HÌNH THÀNH CÔNG\n\nĐã lưu: {local}"))
            except Exception as e:self.q.put(("panel_text","control_out","LỖI QUAY MÀN HÌNH: "+str(e)))
        threading.Thread(target=w,daemon=True).start()

    # ---------- apps ----------
    def list_packages(self):
        try:
            q=self.pkg.get().strip()
            out=self.shell("pm list packages -3"+(f" | grep -i '{q}'" if q else ""),40,log_output=False)
            self.q.put(("panel_text","app_list",out))
        except Exception as e:self.q.put(("panel_text","app_list","LỖI: "+str(e)))

    def install_apk(self):
        p=filedialog.askopenfilename(filetypes=[("Android APK","*.apk"),("All files","*.*")])
        if not p:return
        try:
            rc,out=self.run(["-s",self.serial(),"install","-r",p],120)
            self.q.put(("panel_text","app_list",f"CÀI APK\nMã kết quả: {rc}\n{out.strip()}"))
        except Exception as e:self.q.put(("panel_text","app_list","LỖI CÀI APK: "+str(e)))

    def _pkg(self):return self.pkg.get().strip()
    def uninstall_pkg(self):
        p=self._pkg()
        if p and messagebox.askyesno("Gỡ ứng dụng", "Bạn có chắc muốn gỡ gói ứng dụng này không?\n\n"+p):
            try:
                rc,out=self.run(["-s",self.serial(),"uninstall",p],60)
                self.q.put(("panel_text","app_list",f"GỠ ỨNG DỤNG: {p}\nMã kết quả: {rc}\n{out.strip()}"))
            except Exception as e:self.q.put(("panel_text","app_list","LỖI: "+str(e)))
    def force_stop(self):
        p=self._pkg()
        if p:self.control_shell(f"am force-stop {p}",f"BUỘC DỪNG: {p}")
    def clear_data(self):
        p=self._pkg()
        if p and messagebox.askyesno("Xóa dữ liệu", "Bạn có chắc muốn xóa dữ liệu ứng dụng này không?\n\n"+p):self.control_shell(f"pm clear {p}",f"XÓA DỮ LIỆU: {p}")
    def launch_pkg(self):
        p=self._pkg()
        if p:self.control_shell(f"monkey -p {p} 1",f"MỞ ỨNG DỤNG: {p}")
    def extract_apk(self):
        p=self._pkg()
        if not p:return
        out=self.shell(f"pm path {shlex_quote(p)}",20,log_output=False)
        paths=re.findall(r"package:(\S+)",out)
        if not paths:return
        local=filedialog.askdirectory()
        if not local:return
        for i,r in enumerate(paths):
            self.run(["-s",self.serial(),"pull",r,local],60)
        self.q.put(("panel_text","app_list",f"TRÍCH APK THÀNH CÔNG\n\nĐã lấy {len(paths)} tệp APK tới:\n{local}"))

    # ---------- files ----------
    def file_ls(self):
        path=self.remote.get().strip()
        def w():
            try:self.q.put(("panel_text","files_out",self.shell("ls -lah "+shlex_quote(path),20,log_output=False)))
            except Exception as e:self.q.put(("panel_text","files_out","LỖI: "+str(e)))
        threading.Thread(target=w,daemon=True).start()
    def pull_file(self):
        r=self.remote.get().strip()
        if not r:return
        d=filedialog.askdirectory()
        if d:
            try:
                rc,out=self.run(["-s",self.serial(),"pull",r,d],120)
                self.q.put(("panel_text","files_out",f"PULL TỆP\nMã kết quả: {rc}\n{out.strip() or 'Hoàn tất.'}"))
            except Exception as e:self.q.put(("panel_text","files_out","LỖI PULL: "+str(e)))
    def push_file(self):
        p=filedialog.askopenfilename()
        if p:
            try:
                rc,out=self.run(["-s",self.serial(),"push",p,self.remote.get().strip()],120)
                self.q.put(("panel_text","files_out",f"PUSH TỆP\nMã kết quả: {rc}\n{out.strip() or 'Hoàn tất.'}"))
            except Exception as e:self.q.put(("panel_text","files_out","LỖI PUSH: "+str(e)))
    def delete_file(self):
        r=self.remote.get().strip()
        if r and messagebox.askyesno("Xóa tệp", "Bạn có chắc muốn xóa tệp này không?\n\n"+r):
            def w():
                try:
                    out=self.shell("rm -f "+shlex_quote(r),15,log_output=False)
                    self.q.put(("panel_text","files_out",f"XÓA TỆP\n{r}\n\n"+(out.strip() or "Đã gửi lệnh xóa.")))
                except Exception as e:self.q.put(("panel_text","files_out","LỖI XÓA TỆP: "+str(e)))
            threading.Thread(target=w,daemon=True).start()
    # ---------- diagnostics ----------
    def capture_command(self,cmd,target="diag_out",label=None):
        title=label or "ĐANG THỰC HIỆN"
        status_attr={"diag_out":"diag_status","net_out":"net_status","volte_out":"volte_status"}.get(target)
        if status_attr and hasattr(self,status_attr):
            getattr(self,status_attr).configure(text=f"ĐANG CHẠY: {title} …")
        widget=getattr(self,target,None)
        if widget:
            widget.delete("1.0","end"); widget.insert("end",f"{title}\n\nĐang lấy dữ liệu từ thiết bị…\n")
        def w():
            started=time.time()
            try:
                out=self.shell(cmd,45,log_output=False)
                elapsed=time.time()-started
                self.q.put(("panel_text", target, (label + f"\nThời gian: {elapsed:.1f} giây\n\n" if label else "") + (out.strip() or "Không có dữ liệu trả về.")))
                if status_attr:self.q.put(("panel_status",status_attr,f"HOÀN TẤT: {title} • {elapsed:.1f} giây"))
            except Exception as e:
                self.q.put(("panel_text", target, f"{title}\n\nLỖI: {e}"))
                if status_attr:self.q.put(("panel_status",status_attr,f"LỖI: {title}"))
        threading.Thread(target=w,daemon=True).start()

    def _normalize_manual_command(self, cmd, mode):
        import shlex
        raw=cmd.strip(); low=raw.lower()
        if mode=="ADB SHELL":
            if low.startswith("adb "):
                parts=[x.strip('"') for x in shlex.split(raw, posix=False)]
                if "shell" in [x.lower() for x in parts]:
                    i=[x.lower() for x in parts].index("shell"); raw=" ".join(parts[i+1:])
                else: raw=" ".join(parts[1:])
            elif low.startswith("shell "):
                raw=raw[6:].lstrip()
            return raw
        if low.startswith("adb "): return raw[4:].lstrip()
        return raw

    def run_manual_adb(self):
        cmd=self.adb_cmd.get().strip()
        if not cmd or cmd=="Nhập lệnh ADB hoặc lệnh shell tại đây...":return
        mode=self.adb_mode.get(); s=self.serial()
        if not s:
            self.adb_out.delete("1.0","end"); self.adb_out.insert("end","⚠  CHƯA CHỌN THIẾT BỊ ADB.\nHãy chọn thiết bị ở thanh trên rồi chạy lại.\n"); return
        normalized=self._normalize_manual_command(cmd,mode)
        if not normalized:return
        history=list(self.adb_history.cget("values"))
        if cmd in history:history.remove(cmd)
        history.insert(0,cmd);self.adb_history.configure(values=history[:50])
        self.adb_out.delete("1.0","end");self.adb_out.insert("end",f"▶  {cmd}\nLỆNH THỰC: {normalized}\nCHẾ ĐỘ: {mode}\n\n⏳ ĐANG THỰC THI...\n")
        def w():
            started=time.time()
            try:
                if mode=="ADB SHELL": rc,out=self.run(["-s",s,"shell","sh","-c",normalized],45)
                else:
                    import shlex
                    parts=[x.strip('"') for x in shlex.split(normalized,posix=False)]
                    if "-s" not in parts:parts=["-s",s]+parts
                    rc,out=self.run(parts,45)
                elapsed=time.time()-started
                self.q.put(("panel_text","adb_out",f"▶  {cmd}\nLỆNH THỰC: {normalized}\nCHẾ ĐỘ: {mode}\nTHỜI GIAN: {elapsed:.1f} giây\nMÃ KẾT QUẢ: {rc}\n\n{out.strip() or 'Lệnh hoàn tất, không có dữ liệu trả về.'}"))
            except Exception as e:self.q.put(("panel_text","adb_out",f"▶  {cmd}\nLỆNH THỰC: {normalized}\n\n❌ LỖI: {e}"))
        threading.Thread(target=w,daemon=True).start()

    def run_quick_adb(self,cmd):
        self.adb_cmd.delete(0,"end");self.adb_cmd.insert(0,cmd);self.adb_cmd.config(fg="#f8fafc")
        self.adb_mode.set("LỆNH ADB" if cmd in ("devices","get-state") else "ADB SHELL")
        self.run_manual_adb()

    # ---------- network / volte ----------
    def volte_check(self):
        def w():
            cmds=[
                ("Trạng thái SIM", "getprop gsm.sim.state"),
                ("Nhà mạng", "getprop gsm.operator.alpha"),
                ("Loại mạng", "getprop gsm.network.type"),
                ("Mạng thoại", "getprop gsm.voice.network.type"),
                ("Cờ VoLTE", "settings get global volte_vt_enabled"),
                ("Cờ 4G nâng cao", "settings get global enhanced_4g_mode_enabled"),
                ("Cờ Wi-Fi Calling", "settings get global wfc_ims_enabled"),
                ("Cờ gọi Wi-Fi", "settings get global wifi_calling_enabled"),
                ("Dịch vụ IMS", "service list | grep -iE 'ims|telephony|phone'"),
            ]
            out=["===== KIỂM TRA VoLTE / IMS =====\n"]
            for label,c in cmds:
                try:
                    value=self.shell(c,6,log_output=False).strip()
                    if not value:value="Không có dữ liệu"
                    # Một số ROM Vivo trả trang trợ giúp cho 'settings get global'; báo rõ thay vì coi đó là lỗi toàn tool.
                    if value.startswith("Settings provider (settings) commands:"):
                        value="ROM này không hỗ trợ đọc mục này bằng lệnh settings chuẩn"
                    out.append(f"{label}:\n{value}\n\n")
                except Exception as e:
                    out.append(f"{label}:\nKhông đọc được: {e}\n\n")
            self.q.put(("panel_text", "volte_out", "".join(out)))
        threading.Thread(target=w,daemon=True).start()

    def ims_check(self):
        def w():
            cmds=[
                ("Dịch vụ hệ thống", "service list | grep -iE 'ims|telephony|phone'", 8),
                ("Gói IMS / nhà mạng", "pm list packages | grep -iE 'ims|carrier|telephony'", 8),
                ("Trạng thái IMS", "dumpsys ims 2>&1", 8),
                ("Trạng thái điện thoại", "dumpsys telephony.registry 2>&1", 8),
            ]
            out=["===== CHẨN ĐOÁN IMS CHUYÊN SÂU =====\n"]
            for label,c,t in cmds:
                try:
                    value=self.shell(c,t,log_output=False).strip()
                    if not value:value="Không có dữ liệu"
                    out.append(f"### {label}\n{value}\n\n")
                except Exception as e:
                    out.append(f"### {label}\nKhông đọc được: {e}\n\n")
            self.q.put(("panel_text", "volte_out", "".join(out)))
        threading.Thread(target=w,daemon=True).start()

    def volte_enable(self):
        if not messagebox.askyesno("VoLTE", "Sao lưu trước và bật các cờ VoLTE phổ biến của Android?"):return
        self.backup()
        def w():
            out=["===== BẬT CỜ VoLTE =====\n"]
            for c in ["settings put global volte_vt_enabled 1","settings put global enhanced_4g_mode_enabled 1"]:
                try:
                    rc,o=self.run(["-s",self.serial(),"shell","sh","-c",c],8)
                    out.append(f"$ {c}\n{o.strip() or ('Đã gửi lệnh' if rc==0 else 'Lệnh trả về mã '+str(rc))}\n\n")
                except Exception as e:out.append(f"$ {c}\nLỖI: {e}\n\n")
            self.q.put(("panel_text", "volte_out", "".join(out)))
            self.q.put(("event_log", "[VoLTE] Đã thực hiện bật cờ VoLTE cơ bản."))
        threading.Thread(target=w,daemon=True).start()

    def oppo_volte_one_click(self):
        """One-click VoLTE helper for OPPO/ColorOS legacy devices.
        It only changes Android settings flags; it does not alter IMEI, SIM locks,
        carrier locks, modem firmware, or protected carrier configuration.
        """
        if not messagebox.askyesno(
            "ONE-CLICK OPPO VoLTE",
            "Bật các cờ VoLTE/4G nâng cao phổ biến trên OPPO và khởi động lại máy?\n"
            "Tool sẽ không thay đổi IMEI, SIM lock, carrier lock hay modem."
        ):
            return
        self.backup()
        def w():
            cmds = [
                "settings put global volte_vt_enabled 1",
                "settings put global enhanced_4g_mode_enabled 1",
                "settings put global volte_enabled 1",
                "settings put global carrier_vt_enabled 1",
            ]
            out = ["===== ONE-CLICK OPPO VoLTE =====\n"]
            for c in cmds:
                try:
                    rc, o = self.run(["-s", self.serial(), "shell", "sh", "-c", c], 8)
                    out.append(f"$ {c}\n{o.strip() or ('OK' if rc == 0 else 'RC='+str(rc))}\n\n")
                except Exception as e:
                    out.append(f"$ {c}\nLỖI: {e}\n\n")
            out.append("Đã gửi cấu hình. Khởi động lại để ROM tải lại cấu hình.\n\n")
            self.q.put(("panel_text", "volte_out", "".join(out)))
            self.q.put(("event_log", "[VoLTE] ONE-CLICK OPPO: đã áp dụng 4 cờ VoLTE và chuẩn bị reboot."))
            try:
                self.run(["-s", self.serial(), "reboot"], 5)
                self.q.put(("event_log", "[VoLTE] OPPO đang khởi động lại."))
            except Exception as e:
                self.q.put(("event_log", f"[VoLTE] Không reboot tự động: {e}"))
        threading.Thread(target=w, daemon=True).start()

    def oppo_native_one_click(self):
        """HBG-style native CarrierConfig test runner + legacy VoLTE flags."""
        serial = self.serial()
        if not serial:
            messagebox.showwarning("VoLTE 1-CLICK", "Chưa chọn thiết bị ADB.")
            return
        dex = self.base / "assets" / "hbg_volte_fixer.dex"
        if not dex.exists():
            messagebox.showerror("VoLTE 1-CLICK", "Thiếu assets/hbg_volte_fixer.dex.")
            return
        if not messagebox.askyesno(
            "VoLTE 1-CLICK",
            "Chạy native CarrierConfig runner trên thiết bị?\\n"
            "Sau đó tool sẽ áp dụng fallback VoLTE flags và đọc lại IMS/CarrierConfig."
        ):
            return
        self.backup()
        def w():
            out = ["===== APPLE SEED VoLTE NATIVE 1-CLICK =====\\n"]
            def add(title, rc, text):
                out.append(f"{title}: rc={rc}\\n{text.strip()}\\n\\n")
            try:
                rc, o = self.run(["-s", serial, "get-state"], 8)
                add("ADB STATE", rc, o)
                if rc != 0:
                    self.q.put(("panel_text", "volte_out", "".join(out)))
                    return
                rc, o = self.run(["-s", serial, "shell", "getprop", "ro.product.model"], 8)
                add("MODEL", rc, o)
                rc, o = self.run(["-s", serial, "shell", "getprop", "ro.build.version.release"], 8)
                add("ANDROID", rc, o)
                remote = "/data/local/tmp/hbg_volte_fixer.dex"
                rc, o = self.run(["-s", serial, "push", str(dex), remote], 30)
                add("PUSH NATIVE RUNNER", rc, o)
                if rc == 0:
                    cmd = ["-s", serial, "shell", "app_process64",
                           "-Djava.class.path=" + remote, "/system/bin",
                           "com.hbg.volte.VolteFixer", "ENABLE"]
                    rc, o = self.run(cmd, 20)
                    add("NATIVE RUNNER app_process64", rc, o)
                    if rc != 0:
                        cmd[4] = "app_process"
                        rc, o = self.run(cmd, 20)
                        add("NATIVE RUNNER app_process", rc, o)
                for c in [
                    "settings put global volte_vt_enabled 1",
                    "settings put global enhanced_4g_mode_enabled 1",
                    "settings put global volte_enabled 1",
                    "settings put global carrier_vt_enabled 1",
                ]:
                    rc, o = self.run(["-s", serial, "shell", "sh", "-c", c], 8)
                    add("FALLBACK " + c, rc, o)
                rc, o = self.run(["-s", serial, "shell", "dumpsys", "carrier_config"], 15)
                lines = [x for x in o.splitlines() if any(k in x.lower() for k in [
                    "carrier_volte_available_bool","carrier_volte_provisioned_bool",
                    "carrier_vt_available_bool","editable_enhanced_4g_lte_bool",
                    "hide_enhanced_4g_lte_bool","show_4g_for_lte_data_icon_bool"])]
                add("CARRIER CONFIG", rc, "\\n".join(lines) if lines else o[:4000])
                rc, o = self.run(["-s", serial, "shell", "dumpsys", "ims"], 15)
                add("IMS", rc, o[:6000])
                self.run(["-s", serial, "shell", "rm", "-f", remote], 8)
                self.q.put(("panel_text", "volte_out", "".join(out)))
                self.q.put(("event_log", "[VoLTE] Native 1-CLICK đã chạy xong; xem log để xác định IMS."))
            except Exception as e:
                out.append("EXCEPTION: " + str(e) + "\\n")
                self.q.put(("panel_text", "volte_out", "".join(out)))
        threading.Thread(target=w, daemon=True).start()

    def install_volte_app(self):
        """Install the bundled Apple Seed VoLTE APK with a ColorOS-friendly fallback."""
        serial=self.serial()
        if not serial:
            messagebox.showwarning("Apple Seed VoLTE", "Chưa chọn thiết bị ADB.")
            return
        apk=self.base / "apps" / "AppleSeed_VoLTE.apk"
        if not apk.exists():
            messagebox.showwarning("Apple Seed VoLTE", "Không tìm thấy apps\\AppleSeed_VoLTE.apk trong bộ tool.")
            return
        if not messagebox.askyesno("Cài Apple Seed VoLTE",
            "Cài APK VoLTE có sẵn trong tool\n\n"
            "Tool sẽ tự xử lý kiểm tra cài đặt ADB, thử cài trực tiếp,\n"
            "nếu ColorOS chặn verification sẽ chuyển sang cài qua pm install.\n\n"
            "Tiếp tục?"): return
        def w():
            out=["===== APPLE SEED VoLTE — CÀI APK CÓ SẴN =====\n", f"APK: {apk}\n\n"]
            try:
                # Preflight: disable common ADB package verification switches when the ROM allows it.
                for key,val in [("verifier_verify_adb_installs","0"),("package_verifier_enable","0"),("package_verifier_user_consent","1")]:
                    rc,o=self.run(["-s",serial,"shell","settings","put","global",key,val],10)
                    out.append(f"VERIFY {key}={val}: rc={rc}\n")
                out.append("\n")

                rc,o=self.run(["-s",serial,"install","-r","-d",str(apk)],90)
                out.append(f"ADB INSTALL: rc={rc}\n{o.strip()}\n\n")

                # ColorOS/legacy verifier fallback: push then invoke PackageManager from the shell.
                if rc != 0 and "INSTALL_FAILED_VERIFICATION_FAILURE" in o:
                    remote="/data/local/tmp/AppleSeed_VoLTE.apk"
                    rc2,o2=self.run(["-s",serial,"push",str(apk),remote],90)
                    out.append(f"PUSH FALLBACK: rc={rc2}\n{o2.strip()}\n\n")
                    if rc2==0:
                        rc3,o3=self.run(["-s",serial,"shell","pm","install","-r","-d","-g",remote],90)
                        out.append(f"PM INSTALL FALLBACK: rc={rc3}\n{o3.strip()}\n\n")
                        rc=rc3; o=o3
                        self.run(["-s",serial,"shell","rm","-f",remote],10)

                if rc != 0:
                    out.append("KẾT LUẬN: Android/ColorOS vẫn từ chối cài APK.\n")
                    out.append("Nếu còn INSTALL_FAILED_VERIFICATION_FAILURE, hãy bật 'Cài đặt qua USB / Install via USB' trong Tùy chọn nhà phát triển và chạy lại.\n")
                    self.q.put(("panel_text","volte_out","".join(out)))
                    return

                rcg,og=self.run(["-s",serial,"shell","pm","grant","vn.appleseed.volte","android.permission.WRITE_SECURE_SETTINGS"],15)
                out.append(f"WRITE_SECURE_SETTINGS: rc={rcg}\n{og.strip() or ('OK' if rcg==0 else 'FAILED')}\n\n")
                rco,oo=self.run(["-s",serial,"shell","monkey","-p","vn.appleseed.volte","1"],15)
                out.append(f"MỞ APP: rc={rco}\n{oo.strip()}\n")
                self.q.put(("panel_text","volte_out","".join(out)))
                self.q.put(("event_log","[VoLTE] Đã cài APK VoLTE có sẵn trong tool; đã thử cấp WRITE_SECURE_SETTINGS và mở app."))
            except Exception as e:
                out.append(f"LỖI: {e}\n")
                self.q.put(("panel_text","volte_out","".join(out)))
        threading.Thread(target=w,daemon=True).start()

    def open_volte_app(self):
        serial=self.serial()
        if not serial:
            messagebox.showwarning("Apple Seed VoLTE", "Chưa chọn thiết bị ADB.")
            return
        def w():
            try:
                rc,o=self.run(["-s",serial,"shell","monkey","-p","vn.appleseed.volte","1"],15)
                self.q.put(("panel_text","volte_out",f"===== MỞ APP APPLE SEED VoLTE =====\nrc={rc}\n{o.strip()}"))
            except Exception as e:
                self.q.put(("panel_text","volte_out",f"Không mở được app: {e}"))
        threading.Thread(target=w,daemon=True).start()

    def vendor_profile(self,vendor):
        if vendor.lower() == "oppo":
            return self.oppo_volte_one_click()
        if not messagebox.askyesno("Cấu hình",f"Áp dụng cấu hình an toàn {vendor.upper()} (các cài đặt phổ biến)?\nKhông vượt khóa nhà mạng hoặc khóa SIM."):return
        self.volte_enable()

    # ---------- queue ----------
    def _pump(self):
        try:
            while True:
                x=self.q.get_nowait()
                if x[0]=="devices":
                    ds=x[1];self.devices=ds
                    self.combo["values"]=[f"{a} ({b})" for a,b in ds]
                    if ds:self.combo.current(0);self.log("[ADB] Thiết bị: "+str(ds))
                elif x[0]=="panel_text":
                    widget=getattr(self,x[1],None)
                    if widget:
                        widget.delete("1.0","end")
                        widget.insert("end",x[2])
                        widget.see("1.0")
                elif x[0]=="panel_status":
                    widget=getattr(self,x[1],None)
                    if widget: widget.configure(text=x[2])
                elif x[0]=="analysis_progress":
                    self.info.insert("end", x[1] + "\n")
                    self.info.see("end")
                elif x[0]=="analysis_done":
                    self.info.delete("1.0","end")
                    self.info.insert("end",x[1])
                    self.info.see("1.0")

                elif x[0]=="analysis_fail":
                    self.info.delete("1.0","end")
                    self.info.insert("end","PHÂN TÍCH KHÔNG THÀNH CÔNG\n\n"+x[1])

                elif x[0]=="analysis_busy":
                    self.busy=x[1]
                elif x[0]=="home_stats":
                    self.home_android.value_label.configure(text=x[1])
                    self.home_soc.value_label.configure(text=x[2])
                elif x[0]=="online_doc":
                    self.doc_view.delete("1.0","end"); self.doc_view.insert("end",f"URL: {x[1]}\n\n{x[2]}"); self.doc_view.see("1.0"); self.online_status.configure(text="Đã tải xong tài liệu trực tuyến.")
                elif x[0]=="online_error":
                    self.doc_view.delete("1.0","end"); self.doc_view.insert("end",f"KHÔNG TẢI ĐƯỢC TÀI LIỆU\n\n{x[1]}\n\nLỗi: {x[2]}\n\nBạn có thể bấm 'MỞ TRÌNH DUYỆT' hoặc dùng KHO KIẾN THỨC offline."); self.online_status.configure(text="Không tải được tài liệu.")
                elif x[0]=="event_log":self.log(x[1])
                elif x[0]=="err":self.log("[LỖI] "+x[1])
        except queue.Empty:pass
        self.root.after(100,self._pump)

    def log(self,t):
        try:
            self.logbox.insert("end",t+"\n\n");self.logbox.see("end")
        except: pass

def shlex_quote(s):
    # simple shell quoting for remote Android sh
    return "'" + str(s).replace("'","'\\''") + "'"

if __name__=="__main__":
    root=tk.Tk()
    AndroidTool(root)
    root.mainloop()
