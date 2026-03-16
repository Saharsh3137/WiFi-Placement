#include <ESP8266WiFi.h>
#include <espnow.h>
#include <EEPROM.h>

ADC_MODE(ADC_VCC); 

int myID = 1; 

// THE MASTER'S MAC ADDRESS 
uint8_t masterMAC[] = {0xB0, 0xCB, 0xD8, 0xC6, 0x66, 0x7C}; 

unsigned long startSendTime = 0;
unsigned long lastLatency = 0;
unsigned long globalPacketCount = 0;
int routerChannel = 1; 

bool hasWiFiCreds = false;
char currentSSID[32] = "";
char currentPass[64] = "";

typedef struct struct_message {
  int id; int rssi; int snr; unsigned long packetId; int voltage; int latency;
} struct_message;
struct_message myData;

typedef struct struct_config {
  char msgType[10]; char ssid[32]; char password[64];
} struct_config;

void OnDataSent(uint8_t *mac_addr, uint8_t sendStatus) {
  unsigned long endTime = micros();
  if (sendStatus == 0) {
    lastLatency = (endTime - startSendTime) / 1000;
    if (lastLatency == 0) lastLatency = 1;
  } else lastLatency = 999; 
}

void OnDataRecv(uint8_t * mac, uint8_t *incomingData, uint8_t len) {
  Serial.print("Rx Packet Size: "); Serial.println(len); 
  if (len == sizeof(struct_config)) {
    struct_config newConfig;
    memcpy(&newConfig, incomingData, sizeof(newConfig));
    if (strcmp(newConfig.msgType, "WIFI_UPDT") == 0) {
      Serial.println("\n[!] TARGETED CONFIG RECEIVED!");
      Serial.print("SSID: "); Serial.println(newConfig.ssid);
      
      EEPROM.put(0, newConfig); EEPROM.commit();
      Serial.println("Saved to memory. Rebooting to apply...");
      delay(1000); ESP.restart(); 
    }
  }
}

void setup() {
  delay(2000); 
  Serial.begin(115200);
  Serial.println("\n\n--- ESP8266 SLAVE STARTING ---");

  // --- THE FACTORY RESET BUTTON ---
  pinMode(0, INPUT_PULLUP); // Pin 0 is the built-in FLASH button
  if (digitalRead(0) == LOW) {
    Serial.println("\n[!] RESET BUTTON HELD: Wiping Memory...");
    EEPROM.begin(512);
    struct_config blankConfig;
    strcpy(blankConfig.msgType, "EMPTY"); 
    EEPROM.put(0, blankConfig);
    EEPROM.commit();
    Serial.println("Memory erased! Rebooting to Lobby...");
    delay(1000);
    ESP.restart(); 
  }

  // ... (The rest of your setup code remains exactly the same)
  EEPROM.begin(512);
  struct_config savedConfig; EEPROM.get(0, savedConfig);
  if (strcmp(savedConfig.msgType, "WIFI_UPDT") == 0) {
    hasWiFiCreds = true;
    strcpy(currentSSID, savedConfig.ssid); strcpy(currentPass, savedConfig.password);
    Serial.println("Loaded saved WiFi creds!");
  }

  WiFi.mode(WIFI_STA); WiFi.setSleepMode(WIFI_NONE_SLEEP); WiFi.persistent(false); WiFi.disconnect(true); delay(100);

  if (esp_now_init() != 0) { Serial.println("ESP-NOW Init Failed"); return; }
  esp_now_set_self_role(ESP_NOW_ROLE_COMBO);
  esp_now_register_recv_cb(OnDataRecv);
  
  esp_now_add_peer(masterMAC, ESP_NOW_ROLE_COMBO, 1, NULL, 0);

  if (hasWiFiCreds) {
    Serial.print("Connecting to: "); Serial.println(currentSSID);
    WiFi.begin(currentSSID, currentPass);
    unsigned long startAttempt = millis();
    while (WiFi.status() != WL_CONNECTED) {
      delay(500); Serial.print(".");
      
      // Changed to 40 seconds to survive Campus Wi-Fi DHCP servers
      if (millis() - startAttempt > 40000) { 
        Serial.println("\nWifi Failed! Wiping memory and entering Setup Mode..."); 
        
        struct_config blankConfig;
        strcpy(blankConfig.msgType, "EMPTY"); 
        EEPROM.put(0, blankConfig);
        EEPROM.commit();
        
        delay(500);
        ESP.restart(); 
      }
    }
    Serial.println("\nWiFi Connected!");
    routerChannel = WiFi.channel(); 
    esp_now_register_send_cb(OnDataSent);
    
  } else {
    // --- THE DUMMY ROUTER HACK (SLAVE) ---
    Serial.println("No WiFi credentials. Brutally forcing hardware to Channel 1...");
    WiFi.mode(WIFI_AP_STA); // AP + Station mode
    WiFi.softAP("SLAVE_DUMMY", "12345678", 1, 1); // Hidden router on Channel 1
    Serial.println("Radio locked. Waiting for Master...");
  }
}

void loop() {
  if (hasWiFiCreds) {
    if (lastLatency == 999) {
       esp_now_del_peer(masterMAC); esp_now_add_peer(masterMAC, ESP_NOW_ROLE_COMBO, WiFi.channel(), NULL, 0);
    }
    myData.id = myID; myData.rssi = WiFi.RSSI(); myData.snr = myData.rssi - (-95); myData.packetId = globalPacketCount++;
    int rawVolt = ESP.getVcc(); myData.voltage = (rawVolt > 4000) ? 3300 : rawVolt; myData.latency = (int)lastLatency; 
    startSendTime = micros(); 
    esp_now_send(masterMAC, (uint8_t *) &myData, sizeof(myData));
    delay(1000); 

  } else {
    // Hardware is locked on Channel 1. Just wait.
    delay(100); 
  }
}