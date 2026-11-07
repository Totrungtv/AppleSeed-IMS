/*
  APPLE SEED AI ROBOT - VIP CONSOLE V2.8 FUNCTIONAL FIX
  ESP32-WROOM-32 + TFT_eSPI + TAMC_GT911
  Existing display setup retained: TFT_eSPI User_Setup must match the board.
  Known previous touch wiring: SDA=21, SCL=22, INT=32, RST=33.
  UART0 wiring: ESP32 TX0(GPIO1) -> UNO RX(D0)
                UNO TX(D1) -> divider -> ESP32 RX0(GPIO3)
                GND common.
  CAUTION: Disconnect UART wires while uploading either board.
  UNO TX is 5V: use a divider (UNO TX -- 1k --+-- ESP32 RX0
                                             |
                                            2k
                                             |
                                            GND)
*/
#include <WiFi.h>
#include <WebServer.h>
#include <WiFiClientSecure.h>
#include <HTTPClient.h>
#include <ArduinoJson.h>
#include <TFT_eSPI.h>
#include <Wire.h>
#include <TAMC_GT911.h>

#define TOUCH_SDA 21
#define TOUCH_SCL 22
#define TOUCH_INT 32
#define TOUCH_RST 33
#define LCD_W 320
#define LCD_H 480

const char* AP_SSID = "APPLE_SEED_ROBOT";
const char* AP_PASS = "SeedVIP2026";

// CLOUD PAIRING: fill these after pairing in Apple Seed AI VIP.
// Never publish a real device token in a public repository.
const char* HOME_WIFI_SSID = "YOUR_HOME_WIFI";
const char* HOME_WIFI_PASS = "YOUR_HOME_WIFI_PASSWORD";
const char* ROBOT_DEVICE_ID = "APPLESEED_ROBOT_01";
const char* ROBOT_DEVICE_TOKEN = "PASTE_PAIR_TOKEN_HERE";
const char* GATEWAY_URL = "https://nuismqcjyutqigdydfkg.supabase.co/functions/v1/apple-seed-robot-gateway";
const bool CLOUD_ENABLED = true;
uint32_t lastCloudPollMs=0, lastCloudTelemetryMs=0, lastWifiRetryMs=0;
bool cloudConfigured=false, cloudOK=false;
String cloudStatus="CLOUD NOT CONFIGURED";

TFT_eSPI tft;
TAMC_GT911 touch(TOUCH_SDA, TOUCH_SCL, TOUCH_INT, TOUCH_RST, LCD_W, LCD_H);
WebServer server(80);

enum Page { HOME, DRIVE, SENSORS, NETWORK, CAMERA, AI_PAGE, SETTINGS };
Page page = HOME;
int SH = 480;
uint16_t BG=0x0861, PANEL=0x10A3, PANEL2=0x18E5;
uint16_t CYAN=0x5DFF, GREEN=0x47E9, WHITE=0xFFFF;
uint16_t MUTED=0x9D1B, ORANGE=0xFD20, RED=0xF986, PURPLE=0xB7DF;

bool touchOK=false, wifiOK=false, unoOK=false;
bool lastTouch=false;
uint32_t lastTouchMs=0, lastTelemetryMs=0, lastUnoByteMs=0, lastUiRefreshMs=0;
const bool TOUCH_SWAP_XY = false;
const bool TOUCH_INVERT_X = false;
const bool TOUCH_INVERT_Y = false;
Page lastRenderedPage = HOME;
String rxLine;
String unoStatus="UNO WAITING";
String robotMode="STOP";
float batteryV=0, distanceCm=-1;
int batteryPct=0, rain=0;
bool obstacle=false;
bool apEnabled=true;
String lastCommand="S";

const char* pageName(Page p) {
  switch(p) {
    case DRIVE:return "ROBOT CONTROL";
    case SENSORS:return "SENSORS";
    case NETWORK:return "NETWORK";
    case CAMERA:return "CAMERA";
    case AI_PAGE:return "AI ASSISTANT";
    case SETTINGS:return "SETTINGS";
    default:return "HOME";
  }
}
bool cloudCredentialsReady() {
  return String(HOME_WIFI_SSID)!="YOUR_HOME_WIFI" &&
         String(ROBOT_DEVICE_TOKEN)!="PASTE_PAIR_TOKEN_HERE" &&
         String(ROBOT_DEVICE_TOKEN).length()>=24;
}
String cloudPost(JsonDocument &doc) {
  WiFiClientSecure client; client.setInsecure(); // Prototype only; pin CA certificate before production.
  HTTPClient http;
  if(!http.begin(client,GATEWAY_URL)) return "";
  http.addHeader("Content-Type","application/json");
  http.addHeader("x-robot-id",ROBOT_DEVICE_ID);
  http.addHeader("x-device-token",ROBOT_DEVICE_TOKEN);
  String body; serializeJson(doc,body);
  int code=http.POST(body);
  String response=code>0?http.getString():"";
  http.end();
  cloudOK=(code>=200 && code<300);
  return response;
}
void applyCloudCommand(const String &command) {
  if(command=="forward") sendCommand("F");
  else if(command=="backward") sendCommand("B");
  else if(command=="left") sendCommand("L");
  else if(command=="right") sendCommand("R");
  else if(command=="stop") sendCommand("S");
  else if(command=="turn_around") sendCommand("U");
  else if(command=="beep") sendCommand("S"); // UNO beep protocol not implemented yet; safe stop fallback.
}
void cloudPoll() {
  if(!CLOUD_ENABLED || !cloudConfigured || WiFi.status()!=WL_CONNECTED || millis()-lastCloudPollMs<1200) return;
  lastCloudPollMs=millis();
  StaticJsonDocument<128> req; req["action"]="device_poll";
  String raw=cloudPost(req); if(raw.length()==0) {cloudStatus="GATEWAY ERROR";return;}
  StaticJsonDocument<1024> res; if(deserializeJson(res,raw)) {cloudStatus="BAD JSON";return;}
  if(!res["ok"]) {cloudStatus=String((const char*)res["error"]|"AUTH ERROR");return;}
  if(res["command"].isNull()) {cloudStatus="ONLINE - WAITING";return;}
  String id=res["command"]["id"]|""; String cmd=res["command"]["command"]|"";
  applyCloudCommand(cmd);
  StaticJsonDocument<256> done; done["action"]="complete"; done["command_id"]=id; done["status"]="completed";
  done["result"]["accepted"]=true; cloudPost(done);
  cloudStatus="LAST: "+cmd;
}
void cloudTelemetry() {
  if(!CLOUD_ENABLED || !cloudConfigured || WiFi.status()!=WL_CONNECTED || millis()-lastCloudTelemetryMs<10000) return;
  lastCloudTelemetryMs=millis();
  StaticJsonDocument<256> req; req["action"]="telemetry";
  JsonObject t=req.createNestedObject("telemetry");
  t["battery_v"]=batteryV; t["battery_pct"]=batteryPct;
  if(distanceCm>=0) t["distance_cm"]=distanceCm;
  t["rain_detected"]=(rain!=0); t["obstacle_detected"]=obstacle;
  String raw=cloudPost(req);
  cloudStatus=cloudOK?"CLOUD ONLINE":"TELEMETRY ERROR";
}
void startCloudWifi() {
  cloudConfigured=cloudCredentialsReady();
  if(!cloudConfigured) {cloudStatus="ADD WIFI + PAIR TOKEN";return;}
  WiFi.mode(WIFI_AP_STA);
  WiFi.begin(HOME_WIFI_SSID,HOME_WIFI_PASS);
  cloudStatus="CONNECTING WIFI";
}
void keepCloudWifi() {
  if(!cloudConfigured) return;
  if(WiFi.status()!=WL_CONNECTED && millis()-lastWifiRetryMs>15000) {
    lastWifiRetryMs=millis(); WiFi.begin(HOME_WIFI_SSID,HOME_WIFI_PASS);
  }
}
void sendCommand(const char* cmd) {
  Serial.println(cmd);
  robotMode=cmd;
  lastCommand=cmd;
}
void centerText(const String &s,int x,int y,int font,uint16_t fg,uint16_t bg=0x0861) {
  tft.setTextDatum(MC_DATUM); tft.setTextFont(font); tft.setTextColor(fg,bg); tft.drawString(s,x,y);
}
void leftText(const String &s,int x,int y,int font,uint16_t fg,uint16_t bg=0x0861) {
  tft.setTextDatum(TL_DATUM); tft.setTextFont(font); tft.setTextColor(fg,bg); tft.drawString(s,x,y);
}
void logo(int x,int y) {
  tft.drawCircle(x,y,34,CYAN); tft.drawCircle(x,y,27,CYAN);
  tft.fillEllipse(x,y+5,12,17,GREEN);
  tft.drawLine(x,y-10,x+12,y-23,GREEN); tft.drawLine(x+12,y-23,x+23,y-22,GREEN);
}
void boot() {
  tft.fillScreen(BG);
  for(int i=0;i<4;i++){tft.drawCircle(160,190,48+i*7,i?PANEL2:CYAN);delay(60);}
  logo(160,190); centerText("APPLE SEED",160,250,4,WHITE);
  centerText("AI ROBOT",160,284,2,CYAN); centerText("VIP CONSOLE",160,313,2,GREEN);
  tft.drawRoundRect(45,355,230,10,5,PANEL2);
  for(int i=0;i<=222;i+=3){tft.fillRoundRect(49,359,i,2,2,CYAN);delay(8);}
  centerText("INITIALIZING SYSTEM",160,385,1,MUTED);
}
void header(const String &title) {
  tft.fillRect(0,0,320,50,PANEL); leftText("APPLE SEED",12,7,2,WHITE,PANEL);
  leftText(title,12,30,1,CYAN,PANEL);
  if (page == HOME) { tft.fillRoundRect(263,11,47,27,7,PANEL2); centerText("VIP",286,24,1,GREEN,PANEL2); }
  else { tft.fillRoundRect(252,7,58,36,7,PANEL2); centerText("HOME",281,25,1,GREEN,PANEL2); }
}
void footer(const String &s="APPLE SEED  |  VIP ROBOT") {
  tft.drawFastHLine(10,SH-35,300,PANEL2); centerText(s,160,SH-17,1,MUTED);
}
void button(int x,int y,int w,int h,const String &label,uint16_t color=CYAN) {
  tft.fillRoundRect(x,y,w,h,10,PANEL); tft.drawRoundRect(x,y,w,h,10,color);
  centerText(label,x+w/2,y+h/2,2,WHITE,PANEL);
}
void drawHome() {
  page=HOME; tft.fillScreen(BG); header("TRUNG TAM DIEU KHIEN");
  leftText("TOUCH: "+String(touchOK?"READY":"CHECK"),12,58,1,touchOK?GREEN:ORANGE);
  leftText("WIFI AP: ON    UNO: "+String(unoOK?"ONLINE":"WAITING"),12,75,1,MUTED);
  button(10,100,145,88,"DIEU KHIEN",GREEN); button(165,100,145,88,"CAM BIEN",ORANGE);
  button(10,199,145,88,"MANG WIFI",CYAN); button(165,199,145,88,"TRO LY AI",PURPLE);
  button(10,298,145,65,"CAMERA",CYAN); button(165,298,145,65,"CAI DAT",GREEN);
  footer("TAP A FUNCTION");
}
void drawDrive() {
  page=DRIVE;tft.fillScreen(BG);header("DIEU KHIEN ROBOT");
  centerText("MODE: "+robotMode,160,69,2,GREEN);
  button(90,94,140,48,"TIEN",GREEN); button(10,150,90,48,"RE TRAI",CYAN);
  button(110,150,100,48,"DUNG",RED); button(220,150,90,48,"RE PHAI",CYAN);
  button(90,206,140,48,"LUI",ORANGE); button(10,270,300,48,"QUAY DAU 180 DO",ORANGE);
  button(10,SH-105,300,48,"VE TRANG CHU",PURPLE); footer("LENH DIEU KHIEN ROBOT");
}
void drawSensors() {
  page=SENSORS;tft.fillScreen(BG);header("SYSTEM SENSORS");
  leftText("BATTERY",18,90,2,CYAN); leftText(String(batteryV,2)+" V",18,123,4,WHITE);
  leftText("LEVEL: "+String(batteryPct)+"%",18,166,2,GREEN); leftText("DISTANCE",18,220,2,CYAN);
  leftText(distanceCm<0?"NO ECHO":String(distanceCm,1)+" cm",18,253,4,WHITE);
  leftText("RAIN: "+String(rain?"DETECTED":"CLEAR"),18,310,2,rain?ORANGE:GREEN);
  leftText("OBSTACLE: "+String(obstacle?"YES":"NO"),18,345,2,obstacle?RED:GREEN);
  button(10,SH-105,300,58,"VE TRANG CHU",PURPLE); footer("LIVE DATA FROM UNO");
}
void drawNetwork() {
  page=NETWORK;tft.fillScreen(BG);header("NETWORK STATUS");
  leftText("ACCESS POINT",18,100,2,CYAN); leftText(AP_SSID,18,137,2,WHITE);
  leftText("PASSWORD: "+String(AP_PASS),18,172,2,ORANGE);
  leftText("OPEN: http://192.168.4.1",18,225,2,GREEN);
  leftText("CLIENTS: "+String(WiFi.softAPgetStationNum()),18,270,2,WHITE);\n  leftText("CLOUD: "+cloudStatus,18,305,1,ORANGE);
  button(10,SH-105,300,58,"VE TRANG CHU",PURPLE); footer("CONNECT PHONE TO ROBOT WIFI");
}
void drawCamera() {
  page=CAMERA;tft.fillScreen(BG);header("CAMERA MODULE");
  centerText("CAMERA: CHUA KET NOI",160,125,2,ORANGE);
  centerText("ESP32-WROOM KHONG CO CAMERA",160,165,2,WHITE);
  centerText("Can ESP32-CAM + cap nguon",160,205,1,MUTED);
  centerText("Sau khi lap se them stream MJPEG",160,235,1,CYAN);
  centerText("Trang thai: CHO PHAN CUNG",160,270,1,ORANGE);
  button(10,SH-105,300,58,"VE TRANG CHU",PURPLE); footer("CAMERA MODULE NOT DETECTED");
}
void drawAI() {
  page=AI_PAGE;tft.fillScreen(BG);header("APPLE SEED AI");
  centerText("AI CENTER",160,105,3,CYAN); centerText("GIAO DIEN SAN SANG",160,150,2,GREEN);
  centerText("Can Internet + AI server/API",160,195,2,ORANGE);
  centerText("ESP32 hien chua co endpoint AI",160,230,1,WHITE);
  centerText("Khong tu tao cau tra loi gia",160,255,1,MUTED);
  button(10,SH-105,300,58,"VE TRANG CHU",PURPLE); footer("AI BACKEND NOT CONFIGURED");
}
void drawSettings() {
  page=SETTINGS;tft.fillScreen(BG);header("CAI DAT HE THONG");
  leftText("WIFI AP: "+String(wifiOK?"DANG PHAT":"LOI"),14,82,2,wifiOK?GREEN:RED);
  leftText("TEN: "+String(AP_SSID),14,119,2,WHITE); leftText("IP: 192.168.4.1",14,153,2,CYAN);
  leftText("UART UNO: 9600 BPS",14,190,2,GREEN);
  leftText("UNO: "+String(unoOK?"ONLINE":"CHUA CO DATA"),14,226,2,unoOK?GREEN:ORANGE);
  leftText("PIN: "+String(batteryV,2)+"V  "+String(batteryPct)+"%",14,262,2,WHITE);
  button(10,310,145,55,"DUNG ROBOT",RED); button(165,310,145,55,"LAM MOI",CYAN);
  button(10,SH-105,300,58,"VE TRANG CHU",PURPLE); footer("APPLE SEED VIP V2.8");
}
void showPage(Page p) {
  switch(p) { case DRIVE:drawDrive();break; case SENSORS:drawSensors();break;
    case NETWORK:drawNetwork();break; case CAMERA:drawCamera();break;
    case AI_PAGE:drawAI();break; case SETTINGS:drawSettings();break; default:drawHome();break; }
}
void mapTouch(int rawX, int rawY, int &x, int &y) {
  x = constrain(rawX, 0, LCD_W - 1); y = constrain(rawY, 0, LCD_H - 1);
}
bool hit(int x, int y, int bx, int by, int bw, int bh, int margin=12) {
  return x >= bx-margin && x <= bx+bw+margin && y >= by-margin && y <= by+bh+margin;
}
void goHome() { if (page == DRIVE) sendCommand("S"); showPage(HOME); }
void handleTap(int rawX, int rawY) {
  int x, y; mapTouch(rawX, rawY, x, y);
  Serial.printf("TAP page=%d x=%d y=%d\n", (int)page, x, y);
  if (page == HOME) {
    if (hit(x,y,10,100,145,88,10)) { showPage(DRIVE); return; }
    if (hit(x,y,165,100,145,88,10)) { showPage(SENSORS); return; }
    if (hit(x,y,10,199,145,88,10)) { showPage(NETWORK); return; }
    if (hit(x,y,165,199,145,88,10)) { showPage(AI_PAGE); return; }
    if (hit(x,y,10,298,145,65,10)) { showPage(CAMERA); return; }
    if (hit(x,y,165,298,145,65,10)) { showPage(SETTINGS); return; }
    return;
  }
  if (x >= 235 && y <= 55) { goHome(); return; }
  if (y >= SH-118 && y <= SH-42 && x >= 0 && x <= LCD_W-1) { goHome(); return; }
  if (page == DRIVE) {
    if (hit(x,y,90,94,140,48,14)) { sendCommand("F"); centerText("CHE DO: TIEN",160,69,2,GREEN,BG); return; }
    if (hit(x,y,10,150,90,48,14)) { sendCommand("L"); centerText("CHE DO: RE TRAI",160,69,2,GREEN,BG); return; }
    if (hit(x,y,110,150,100,48,14)) { sendCommand("S"); centerText("CHE DO: DUNG",160,69,2,GREEN,BG); return; }
    if (hit(x,y,220,150,90,48,14)) { sendCommand("R"); centerText("CHE DO: RE PHAI",160,69,2,GREEN,BG); return; }
    if (hit(x,y,90,206,140,48,14)) { sendCommand("B"); centerText("CHE DO: LUI",160,69,2,GREEN,BG); return; }
    if (hit(x,y,10,270,300,48,14)) { sendCommand("U"); centerText("DANG GUI LENH QUAY 180 DO",160,69,2,ORANGE,BG); return; }
    return;
  }
  if(page==SETTINGS) {
    if(hit(x,y,10,310,145,55,8)) { sendCommand("S"); centerText("DA GUI LENH DUNG",160,285,2,RED,BG); return; }
    if(hit(x,y,165,310,145,55,8)) { showPage(SETTINGS); return; }
  }
}
void parseTelemetry(String s) {
  if(!s.startsWith("T,")) return;
  int a=s.indexOf(',',2), b=s.indexOf(',',a+1), c=s.indexOf(',',b+1), d=s.indexOf(',',c+1);
  if(a<0||b<0||c<0||d<0) return;
  batteryV=s.substring(2,a).toFloat(); batteryPct=constrain(s.substring(a+1,b).toInt(),0,100);
  distanceCm=s.substring(b+1,c).toFloat(); rain=s.substring(c+1,d).toInt();
  obstacle=s.substring(d+1).toInt()!=0; unoOK=true; lastUnoByteMs=millis();
}
void pollUno() {
  while(Serial.available()) { char ch=Serial.read();
    if(ch=='\n'){parseTelemetry(rxLine);rxLine="";}
    else if(ch!='\r'&&rxLine.length()<100)rxLine+=ch;
  }
  if(millis()-lastUnoByteMs>5000)unoOK=false;
}
void refreshLivePage() {
  if(page==SENSORS) {
    tft.fillRect(12,115,295,65,BG); leftText(String(batteryV,2)+" V",18,123,4,WHITE);
    leftText("LEVEL: "+String(batteryPct)+"%",18,166,2,GREEN);
    tft.fillRect(12,245,295,55,BG); leftText(distanceCm<0?"NO ECHO":String(distanceCm,1)+" cm",18,253,4,WHITE);
    tft.fillRect(12,305,295,60,BG);
    leftText("RAIN: "+String(rain?"DETECTED":"CLEAR"),18,310,2,rain?ORANGE:GREEN);
    leftText("OBSTACLE: "+String(obstacle?"YES":"NO"),18,345,2,obstacle?RED:GREEN);
  } else if(page==NETWORK) {
    tft.fillRect(15,260,290,35,BG); leftText("CLIENTS: "+String(WiFi.softAPgetStationNum()),18,270,2,WHITE);
  } else if(page==HOME) {
    tft.fillRect(8,55,304,38,BG); leftText("TOUCH: READY  WIFI: "+String(wifiOK?"ON":"OFF"),12,58,1,touchOK&&wifiOK?GREEN:ORANGE);
    leftText("UNO: "+String(unoOK?"ONLINE":"WAITING"),12,75,1,unoOK?GREEN:ORANGE);
  } else if(page==DRIVE) {
    tft.fillRect(15,57,290,26,BG); centerText("CHE DO: "+robotMode,160,69,2,GREEN,BG);
  }
}
String html() {
  String h=R"HTML(<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Apple Seed - Dieu khien Robot</title><style>
body{margin:0;background:#071522;color:#eafaff;font-family:Arial;text-align:center}
header{padding:18px;background:#102c42;color:#5dffff;font-size:22px;font-weight:bold}
.grid{display:grid;grid-template-columns:1fr 1fr;gap:12px;padding:16px}
button{border:1px solid #4de4e7;background:#12334b;color:white;border-radius:14px;padding:20px 8px;font-size:18px;font-weight:bold}
.stop{border-color:#ff6868;background:#48202b}.turn{grid-column:span 2;border-color:#ffbf55;background:#59401d}
.wide{grid-column:span 2}#s{padding:10px;color:#74efb2;min-height:24px}
</style></head><body><header>APPLE SEED - DIEU KHIEN ROBOT</header>
<div id=s>Dang ket noi robot...</div><div class=grid>
<button onclick="cmd('F')">TIEN</button><button onclick="cmd('B')">LUI</button>
<button onclick="cmd('L')">RE TRAI</button><button class=stop onclick="cmd('S')">DUNG</button>
<button onclick="cmd('R')">RE PHAI</button><button onclick="location.href='/status'">CAM BIEN</button>
<button class=turn onclick="cmd('U')">QUAY DAU 180 DO</button>
</div><script>
async function cmd(c){try{let r=await fetch('/cmd?c='+c);document.getElementById('s').innerText=await r.text()}catch(e){document.getElementById('s').innerText='Khong gui duoc lenh';}}
async function stat(){try{let r=await fetch('/status');document.getElementById('s').innerText=await r.text()}catch(e){}}
setInterval(stat,1500);stat();</script></body></html>)HTML";
  return h;
}
void setupWeb() {
  server.on("/",HTTP_GET,[]{server.sendHeader("Cache-Control","no-store"); server.send(200,"text/html; charset=utf-8",html());});
  server.on("/cmd",HTTP_GET,[]{
    String c=server.arg("c");
    if(c=="F")sendCommand("F");else if(c=="B")sendCommand("B");
    else if(c=="L")sendCommand("L");else if(c=="R")sendCommand("R");
    else if(c=="U")sendCommand("U");else if(c=="S")sendCommand("S");
    else {server.send(400,"text/plain","Lenh khong hop le");return;}
    String msg = c=="F"?"Da gui lenh TIEN":c=="B"?"Da gui lenh LUI":c=="L"?"Da gui lenh RE TRAI":c=="R"?"Da gui lenh RE PHAI":c=="U"?"Da gui lenh QUAY DAU 180":"Da gui lenh DUNG";
    server.send(200,"text/plain; charset=utf-8",msg);
  });
  server.on("/status",HTTP_GET,[]{String s="UNO: "+String(unoOK?"DA KET NOI":"DANG CHO")+
    " | PIN: "+String(batteryV,2)+"V ("+String(batteryPct)+"%) | KHOANG CACH: "+String(distanceCm,1)+
    "cm | MUA: "+String(rain)+" | VAT CAN: "+String(obstacle); server.send(200,"text/plain",s);});
  server.on("/api/status",HTTP_GET,[]{
    String j="{\"uno\":"+String(unoOK?"true":"false")+",\"battery_v\":"+String(batteryV,2)+
      ",\"battery_pct\":"+String(batteryPct)+",\"distance_cm\":"+String(distanceCm,1)+
      ",\"rain\":"+String(rain)+",\"obstacle\":"+String(obstacle?"true":"false")+
      ",\"mode\":\""+robotMode+"\",\"clients\":"+String(WiFi.softAPgetStationNum())+"}";
    server.send(200,"application/json",j);
  });
  server.on("/stop",HTTP_GET,[]{sendCommand("S");server.send(200,"text/plain; charset=utf-8","Da gui lenh DUNG");});
  server.onNotFound([]{ server.send(404,"text/plain","Apple Seed: endpoint not found"); });
  server.begin(); Serial.println("HTTP_SERVER_STARTED");
}
void setup() {
  Serial.begin(9600); delay(500); Serial.println(); Serial.println("APPLE_SEED_BOOT_V2_8_FUNCTIONAL_FIX");
  tft.init();tft.setRotation(0);SH=tft.height(); Wire.begin(TOUCH_SDA,TOUCH_SCL);
  touch.begin(); touch.setRotation(ROTATION_INVERTED); touchOK=true;
  WiFi.mode(WIFI_AP); WiFi.softAPConfig(IPAddress(192,168,4,1), IPAddress(192,168,4,1), IPAddress(255,255,255,0));
  wifiOK=WiFi.softAP(AP_SSID,AP_PASS);
  Serial.print("AP_START="); Serial.println(wifiOK ? "OK" : "FAIL");
  Serial.print("AP_SSID="); Serial.println(AP_SSID); Serial.print("AP_IP="); Serial.println(WiFi.softAPIP());
  setupWeb(); startCloudWifi(); boot(); drawHome();
}
void loop() {
  server.handleClient(); pollUno(); keepCloudWifi(); cloudPoll(); cloudTelemetry(); touch.read();
  bool pressed = touch.isTouched && touch.touches > 0;
  if (pressed && !lastTouch && millis()-lastTouchMs > 180) {
    int tx=touch.points[0].x, ty=touch.points[0].y;
    Serial.printf("TOUCHDOWN rawX=%d rawY=%d page=%d\n",tx,ty,(int)page);
    handleTap(tx,ty); lastTouchMs=millis();
  }
  lastTouch=pressed;
  if(millis()-lastUiRefreshMs>=1000){lastUiRefreshMs=millis();refreshLivePage();}
  delay(8);
}
