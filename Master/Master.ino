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
  // Silent to keep Serial Monitor clean during normal operation
}

void OnDataRecv(const uint8_t * mac, const uint8_t *incomingData, int len) {
  unsigned long now = millis();
  isConnected = true; 
  
  if(len == sizeof(incoming)) {
    memcpy(&incoming, incomingData, sizeof(incoming));
    
    // Raw, unfiltered ping latency 
    if (incoming.latency != 999) {
      displayLatency = incoming.latency;
    }
    
    if (lastArrivalTime != 0) {
      long diff = now - lastArrivalTime;
      int rawJitter = abs((long)diff - 1000); // Adjusted for the 1-second delay
      if (rawJitter < 500) jitter = rawJitter;
    }
    lastArrivalTime = now;
    
    // --- THE STOPWATCH FIX ---
    // A 3-second gap will cleanly reset the loss to 0 before the stopwatch updates
    if (incoming.packetId < lastPacketId || (now - lastRecvTime > 3000) || lastPacketId == 0) {
        lastPacketId = incoming.packetId; 
        totalPacketsLost = 0; 
    } else {
        if (incoming.packetId > lastPacketId + 1) {
            totalPacketsLost += (incoming.packetId - lastPacketId - 1);
        }
        lastPacketId = incoming.packetId;
    }
    
    static unsigned long lastBTSend = 0;
    if (now - lastBTSend > 100) { 
       sendToPhone(); 
       lastBTSend = now; 
    }
  }
  
  // The stopwatch starts AFTER the math is done
  lastRecvTime = now; 
}

void drawScanningScreen() {
  display.clearDisplay(); display.setTextColor(WHITE);
  display.setCursor(0, 0); display.print("NET ANALYZER");
  display.setCursor(15, 25); 
  display.println(hasWiFiCreds ? "TRACKING SLAVE..." : "AWAITING BT SETUP");
  display.setCursor(35, 45); display.print("CH: "); display.print(currentChannel);
  display.display();
}

void drawMainScreen() {
  display.clearDisplay(); display.setTextColor(WHITE);
  
  // --- NODE ID ON DISPLAY ---
  display.setCursor(0, 0); display.print("NET ANALYZER");
  display.setCursor(95, 0); display.print("ID:"); display.print(incoming.id); 
  
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

  // --- BLUETOOTH COMMAND HANDLER ---
  if (SerialBT.available()) {
    String btData = SerialBT.readStringUntil('\n'); btData.trim(); 
    
    // --- NEW: REMOTE RESET COMMAND ---
    if (btData == "RESET") {
      totalPacketsLost = 0;
      lastPacketId = 0;
      jitter = 0;
      display.clearDisplay(); display.setCursor(0, 20); display.println("STATS RESET!"); display.display();
      delay(500);
    }
    
    // --- OTA PROVISIONING ---
    else if (btData.startsWith("WIFI:")) {
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

        display.clearDisplay(); display.setCursor(0, 20); display.println("UPDATING SLAVE..."); display.display();
        Serial.println("\nExecuting Over-The-Air Update Sweep...");
        WiFi.disconnect();
        delay(100);

        // Hop through all channels to find and update the Slave
        for (int ch = 1; ch <= 11; ch++) {
          esp_wifi_set_promiscuous(true);
          esp_wifi_set_channel(ch, WIFI_SECOND_CHAN_NONE);
          esp_wifi_set_promiscuous(false);

          esp_now_del_peer(slaveMAC);
          esp_now_peer_info_t p = {};
          memcpy(p.peer_addr, slaveMAC, 6);
          p.channel = ch; 
          esp_now_add_peer(&p);

          Serial.printf("Blasting new config on CH %d...\n", ch);
          for (int i = 0; i < 20; i++) {
            esp_now_send(slaveMAC, (uint8_t *) &configMsg, sizeof(configMsg));
            delay(15);
          }
        }
        
        display.clearDisplay(); display.setCursor(0, 20); display.println("REBOOTING..."); display.display();
        delay(1000);
        ESP.restart(); 
      }
    }
  }

  // --- RADAR TRACKER LOGIC ---
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