#include <esp_now.h>
#include <WiFi.h>
#include <esp_wifi.h>
#include <Wire.h>
#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>
#include <BluetoothSerial.h>
#include <nvs_flash.h>
#include <Preferences.h> 

BluetoothSerial SerialBT;
#define SCREEN_ADDRESS 0x3C 
Adafruit_SSD1306 display(128, 64, &Wire, -1);

Preferences preferences;
String savedSSID = "";
String savedPass = "";
bool hasWiFiCreds = false;

typedef struct struct_message {
  int id; int rssi; int snr; unsigned long packetId; int voltage; int latency; 
} struct_message;
struct_message incoming;

typedef struct struct_config {
  char msgType[10]; char ssid[32]; char password[64];
} struct_config;
struct_config configMsg;

// --- YOUR EXACT SLAVE MAC ---
uint8_t slaveMAC[] = {0x48, 0x3F, 0xDA, 0x05, 0x37, 0x53};

int currentChannel = 1;
bool isConnected = false;
unsigned long lastPacketId = 0;
int totalPacketsLost = 0;
unsigned long lastArrivalTime = 0;
int jitter = 0;
unsigned long lastRecvTime = 0;      
const unsigned long TIMEOUT_MS = 5000; 
int displayLatency = 0;       
float smoothedLatency = 0;
const float filterWeight = 0.2; 

// --- THE MISSING BLUETOOTH PIPELINE ---
void sendToPhone() {
  if (SerialBT.hasClient()) {
    SerialBT.print(incoming.id); SerialBT.print(",");
    SerialBT.print(incoming.rssi); SerialBT.print(",");
    SerialBT.print(incoming.snr); SerialBT.print(",");
    SerialBT.print(incoming.voltage/1000.0); SerialBT.print(",");
    float lossPct = 0.0; if (incoming.packetId > 0) lossPct = (float)totalPacketsLost / incoming.packetId * 100.0;
    SerialBT.print(lossPct, 2); SerialBT.print(",");
    SerialBT.print(jitter); SerialBT.print(","); SerialBT.println(displayLatency); 
  }
}

void OnConfigSent(const uint8_t *mac_addr, esp_now_send_status_t status) {
  Serial.print("Burst Send Status: ");
  Serial.println(status == ESP_NOW_SEND_SUCCESS ? "DELIVERED" : "FAILED");
}

void OnDataRecv(const uint8_t * mac, const uint8_t *incomingData, int len) {
  unsigned long now = millis();
  isConnected = true; 
  lastRecvTime = now; 
  if(len == sizeof(incoming)) {
    memcpy(&incoming, incomingData, sizeof(incoming));
    if (incoming.latency != 999) {
      displayLatency = incoming.latency; 
    }
    if (lastArrivalTime != 0) {
      long diff = now - lastArrivalTime;
      int rawJitter = abs((long)diff - 200); 
      if (rawJitter < 500) jitter = rawJitter;
    }
    lastArrivalTime = now;
    if (incoming.packetId < lastPacketId || (now - lastRecvTime > 3000) || lastPacketId == 0) {
        lastPacketId = incoming.packetId; totalPacketsLost = 0; 
    } else {
        if (incoming.packetId > lastPacketId + 1) totalPacketsLost += (incoming.packetId - lastPacketId - 1);
        lastPacketId = incoming.packetId;
    }
    
    // --- SEND DATA TO THE APP ---
    static unsigned long lastBTSend = 0;
    if (now - lastBTSend > 100) { 
       sendToPhone(); 
       lastBTSend = now; 
    }
  }
}

void drawScanningScreen() {
  display.clearDisplay(); display.setTextColor(WHITE);
  display.setCursor(28, 0); display.println("NET ANALYZER");
  display.setCursor(15, 25); 
  display.println(hasWiFiCreds ? "TRACKING SLAVE..." : "AWAITING BT SETUP");
  display.setCursor(35, 45); display.print("CH: "); display.print(currentChannel);
  display.display();
}

void drawMainScreen() {
  display.clearDisplay(); display.setTextColor(WHITE);
  display.setCursor(28, 0); display.println("NET ANALYZER");
  display.setCursor(0, 12); display.print("RSSI:"); display.print(incoming.rssi); display.print("dBm");
  display.setCursor(74, 12); display.print("SNR:"); display.print(incoming.snr);
  display.setCursor(0, 24); display.print("PING:"); display.print(displayLatency); display.print("ms");
  display.setCursor(0, 36); display.print("LOSS:"); display.print(((float)totalPacketsLost/incoming.packetId)*100.0, 1); display.print("%");
  display.setCursor(0, 52); display.print("CH:"); display.print(currentChannel);
  display.setCursor(80, 52); display.print(SerialBT.hasClient() ? "[BT:ON]" : "[BT:--]");
  display.display();
}

void setup() {
  Serial.begin(115200);
  delay(1000);
  Serial.println("\n\n--- MASTER BOOTING ---");

  nvs_flash_init();
  
  preferences.begin("wifi_creds", false);
  savedSSID = preferences.getString("ssid", "");
  savedPass = preferences.getString("pass", "");
  if(savedSSID != "") hasWiFiCreds = true;
  preferences.end();

  SerialBT.begin("ESP32_Scout_Master"); 
  display.begin(SSD1306_SWITCHCAPVCC, SCREEN_ADDRESS);
  display.clearDisplay();
  
  WiFi.mode(WIFI_STA); 
  WiFi.disconnect(); 
  Serial.println("Radio set to Passive Radar Mode.");

  esp_now_init();
  esp_now_register_recv_cb(esp_now_recv_cb_t(OnDataRecv));
  esp_now_register_send_cb(OnConfigSent);

  esp_now_peer_info_t peerInfo = {};
  memcpy(peerInfo.peer_addr, slaveMAC, 6);
  peerInfo.channel = 0; 
  esp_now_add_peer(&peerInfo);
}

void loop() {
  unsigned long now = millis();

  // --- BLUETOOTH PROVISIONING ---
  if (SerialBT.available()) {
    String btData = SerialBT.readStringUntil('\n'); btData.trim(); 
    if (btData.startsWith("WIFI:")) {
      btData.remove(0, 5); 
      int comma = btData.indexOf(','); 
      if (comma > 0) {
        String nSSID = btData.substring(0, comma);
        String nPass = btData.substring(comma + 1);
        
        preferences.begin("wifi_creds", false);
        preferences.putString("ssid", nSSID);
        preferences.putString("pass", nPass);
        preferences.end();

        strcpy(configMsg.msgType, "WIFI_UPDT");
        strcpy(configMsg.ssid, nSSID.c_str());
        strcpy(configMsg.password, nPass.c_str());

        Serial.println("\nExecuting Dummy Router Hack...");
        WiFi.disconnect();
        WiFi.mode(WIFI_AP_STA);
        WiFi.softAP("PROV_LOBBY", NULL, 1, 1); 
        delay(500);

        esp_now_deinit(); 
        esp_now_init(); 
        esp_now_peer_info_t p = {};
        memcpy(p.peer_addr, slaveMAC, 6);
        p.channel = 1; 
        esp_now_add_peer(&p);

        Serial.println("Starting 50-packet burst fire...");
        for (int i = 0; i < 50; i++) {
          esp_now_send(slaveMAC, (uint8_t *) &configMsg, sizeof(configMsg));
          delay(50);
        }
        
        Serial.println("Provisioning sequence complete. Restarting...");
        delay(2000);
        ESP.restart(); 
      }
    }
  }

  // --- THE RADAR TRACKER LOGIC ---
  if (now - lastRecvTime > TIMEOUT_MS) {
    isConnected = false;
    if (hasWiFiCreds) {
      static unsigned long lastHop = 0;
      if (now - lastHop > 200) { 
        currentChannel++;
        if (currentChannel > 13) currentChannel = 1;
        
        esp_wifi_set_promiscuous(true);
        esp_wifi_set_channel(currentChannel, WIFI_SECOND_CHAN_NONE);
        esp_wifi_set_promiscuous(false);
        lastHop = now;
      }
    }
  }

  // UI Updates
  if (isConnected) {
    static unsigned long lastDraw = 0;
    if (now - lastDraw > 500) { drawMainScreen(); lastDraw = now; }
  } else {
    drawScanningScreen(); 
  }
  delay(10);
}