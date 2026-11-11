/*
  Auris2_Module : sniffer CAN OBD2 PASSIF, Toyota Auris II Hybride, sortie Bluetooth BLE.

  SECURITE : le TWAI est configuré en TWAI_MODE_LISTEN_ONLY. Le contrôleur n'émet
  rien, pas même les ACK. Ce programme n'appelle JAMAIS twai_transmit : il
  n'envoie aucune trame sur le bus du véhicule.

  Câblage :
    SN65HVD230 : CTX -> D2, CRX -> D3, VCC -> 3V3, GND -> GND
    CANH -> prise OBD broche 6, CANL -> prise OBD broche 14, GND -> OBD broche 4/5
    OLED 0,96" SSD1315 (I2C, 0x3C) : SDA -> D4, SCL -> D5, VCC -> 3V3, GND -> GND
    Alim : 12V OBD broche 16 -> convertisseur 12V->5V -> 5V du XIAO

  BLE : serveur "Auris2" (lib BLE intégrée au core), diffuse toutes les trames CAN.
    Service         8f1a0001-3c2b-4d5e-9f00-a0b1c2d3e4f5
    Caract. NOTIFY  8f1a0002-3c2b-4d5e-9f00-a0b1c2d3e4f5
    Paquet = seq uint16 LE puis N trames : ts_ms u32 LE, id u32 LE
    (bits 0-28 id, bit 30 RTR, bit 31 étendue), dlc u8, données (0 si RTR).
    Taille max = MTU-3 (plafond 244, 20 si MTU inconnu).

  OLED réduit : débit CAN (trames/s), état BLE, état réseau CAN/TWAI, erreurs.
  Aucune valeur véhicule affichée (le décodage se fait dans l'appli).

  Carte : Seeed XIAO ESP32-C6, core esp32 3.x.
  Bibliothèque à installer : U8g2

  Note : SOC batterie HV non diffusé sur l'OBD, requête diag requise (impossible en listen-only).
*/
#include "driver/twai.h"
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLE2902.h>
#include <U8g2lib.h>

U8G2_SSD1306_128X64_NONAME_F_HW_I2C u8g2(U8G2_R0, U8X8_PIN_NONE);  // SSD1315 compatible SSD1306, SDA=D4, SCL=D5

#define CAN_TX_PIN D2  // modifiable
#define CAN_RX_PIN D3  // modifiable

// --- BLE : paquet = seq (2 octets) + trames concaténées ---
#define BLE_SVC  "8f1a0001-3c2b-4d5e-9f00-a0b1c2d3e4f5"
#define BLE_CHR  "8f1a0002-3c2b-4d5e-9f00-a0b1c2d3e4f5"
BLECharacteristic* bleChar;
BLE2902* bleCccd;
volatile bool bleConn = false;
// shortcut: suppose MTU 247 (l'appli le demande avant d'activer les notifs) ; lire le MTU réel si un client ne le négocie pas
const uint16_t bleMax = 244;  // taille max d'un paquet (MTU-3)
uint8_t blePkt[244];
uint16_t bleLen = 2, bleSeq = 0;
uint32_t bleT0;  // date de la 1re trame du buffer

class BleCb : public BLEServerCallbacks {
  void onConnect(BLEServer*) override { bleConn = true; }
  void onDisconnect(BLEServer*) override {
    bleConn = false;
    BLEDevice::startAdvertising();  // client suivant
  }
};

// Client connecté (la pile BLE ignore les notifs si le client ne s'est pas abonné)
bool bleOk() { return bleConn; }

void bleEnvoi() {
  if (bleLen <= 2) return;
  blePkt[0] = bleSeq; blePkt[1] = bleSeq >> 8; bleSeq++;
  bleChar->setValue(blePkt, bleLen);
  bleChar->notify();
  bleLen = 2;
}

void bleAjoute(const twai_message_t& m, uint32_t ts) {
  if (!bleOk()) { bleLen = 2; return; }
  uint8_t dlc = m.data_length_code > 8 ? 8 : m.data_length_code;
  uint8_t n = m.rtr ? 0 : dlc;
  if (bleLen + 9 + n > bleMax) bleEnvoi();
  if (bleLen == 2) bleT0 = ts;
  uint32_t id = (m.identifier & 0x1FFFFFFF) | (m.extd ? 1UL << 31 : 0) | (m.rtr ? 1UL << 30 : 0);
  memcpy(blePkt + bleLen, &ts, 4);  // ESP32 little-endian
  memcpy(blePkt + bleLen + 4, &id, 4);
  blePkt[bleLen + 8] = dlc;
  memcpy(blePkt + bleLen + 9, m.data, n);
  bleLen += 9 + n;
}

void bleInit() {
  BLEDevice::init("Auris2");
  BLEDevice::setMTU(247);
  BLEServer* srv = BLEDevice::createServer();
  srv->setCallbacks(new BleCb());
  BLEService* svc = srv->createService(BLE_SVC);
  bleChar = svc->createCharacteristic(BLE_CHR, BLECharacteristic::PROPERTY_NOTIFY);
  bleCccd = new BLE2902();
  bleChar->addDescriptor(bleCccd);
  svc->start();
  BLEAdvertising* adv = BLEDevice::getAdvertising();
  adv->addServiceUUID(BLE_SVC);
  adv->setScanResponse(true);
  BLEDevice::startAdvertising();
}

// DBC Motorola (@0) : start = bit de poids fort, numérotation octet*8 + bit (7..0)
uint32_t getBE(const uint8_t* d, int start, int len) {
  uint32_t v = 0; int bit = start;
  for (int i = 0; i < len; i++) {
    v = (v << 1) | ((d[bit >> 3] >> (bit & 7)) & 1);
    bit = ((bit & 7) == 0) ? bit + 15 : bit - 1;
  }
  return v;
}
int32_t getBEs(const uint8_t* d, int start, int len) {  // signé (complément à 2)
  uint32_t v = getBE(d, start, len);
  if (v & (1UL << (len - 1))) v |= ~((1UL << len) - 1);
  return (int32_t)v;
}

uint32_t tDerniere = 0;

uint32_t nTrames = 0, canFps = 0, tFps = 0, tOled = 0;

void erreurFatale(const char* msg) {
  Serial.println(msg);
  u8g2.clearBuffer();
  u8g2.setFont(u8g2_font_6x10_tf);
  u8g2.drawStr(0, 10, "ERREUR");
  u8g2.drawStr(0, 26, msg);
  u8g2.sendBuffer();
  while (true) delay(1000);
}

// Écran réduit : 5 lignes (débit, BLE, réseau, état TWAI, compteurs d'erreurs)
void oledMaj() {
  char l[24];
  twai_status_info_t st;
  bool ok = twai_get_status_info(&st) == ESP_OK;
  u8g2.clearBuffer();
  u8g2.setFont(u8g2_font_6x10_tf);
  snprintf(l, sizeof l, "CAN: %lu tr/s", (unsigned long)canFps);
  u8g2.drawStr(0, 10, l);
  u8g2.drawStr(0, 22, bleConn ? "BLE: connecte" : "BLE: en attente");
  u8g2.drawStr(0, 34, (millis() - tDerniere < 2000 && tDerniere) ? "Reseau: OK" : "Reseau: Pas de CAN");
  if (ok) {
    const char* e = st.state == TWAI_STATE_RUNNING ? "RUNNING" : st.state == TWAI_STATE_BUS_OFF ? "BUS-OFF"
                  : st.state == TWAI_STATE_RECOVERING ? "RECOV" : "STOPPED";
    snprintf(l, sizeof l, "TWAI:%s bus:%lu", e, (unsigned long)st.bus_error_count);
    u8g2.drawStr(0, 46, l);
    snprintf(l, sizeof l, "rxE:%lu mis:%lu ovr:%lu", (unsigned long)st.rx_error_counter,
             (unsigned long)st.rx_missed_count, (unsigned long)st.rx_overrun_count);
    u8g2.drawStr(0, 58, l);
  } else {
    u8g2.drawStr(0, 46, "TWAI: ?");
  }
  u8g2.sendBuffer();
}

// Auto-test des fonctions d'extraction
void autoTest() {
  uint8_t a[8] = {0x12, 0x34, 0, 0, 0, 0, 0, 0};
  uint8_t b[8] = {0x0F, 0xFF, 0, 0, 0, 0, 0, 0};
  if (getBE(a, 7, 16) != 0x1234) erreurFatale("Test getBE KO");
  if (getBEs(b, 3, 12) != -1) erreurFatale("Test getBEs KO");
}

void setup() {
  Serial.begin(115200);
  u8g2.begin();  // avant autoTest : erreurFatale écrit sur l'écran
  autoTest();
  bleInit();

  twai_general_config_t g = TWAI_GENERAL_CONFIG_DEFAULT((gpio_num_t)CAN_TX_PIN, (gpio_num_t)CAN_RX_PIN, TWAI_MODE_LISTEN_ONLY);
  g.rx_queue_len = 64;
  twai_timing_config_t t = TWAI_TIMING_CONFIG_500KBITS();
  twai_filter_config_t f = TWAI_FILTER_CONFIG_ACCEPT_ALL();
  if (twai_driver_install(&g, &t, &f) != ESP_OK) erreurFatale("TWAI install KO");
  if (twai_start() != ESP_OK) erreurFatale("TWAI start KO");
}

void loop() {
  twai_message_t m;
  while (twai_receive(&m, 0) == ESP_OK) {
    tDerniere = millis();
    nTrames++;
    // Dump candump : (sec.ms) can0 ID#DONNEES
    uint32_t ms = tDerniere;
    Serial.printf("(%lu.%03lu) can0 %03X#", (unsigned long)(ms / 1000), (unsigned long)(ms % 1000), (unsigned)m.identifier);
    for (int i = 0; i < m.data_length_code && !m.rtr; i++) Serial.printf("%02X", m.data[i]);
    Serial.print('\n');
    bleAjoute(m, tDerniere);
  }
  if (bleLen > 2 && millis() - bleT0 > 20) bleEnvoi();  // flush après 20 ms
  uint32_t now = millis();
  if (now - tFps >= 1000) { canFps = nTrames; nTrames = 0; tFps = now; }
  if (now - tOled >= 500) { tOled = now; oledMaj(); }  // shortcut: sendBuffer I2C ~25 ms en bloquant ; file RX 64 trames, ok à 500 kbit/s si bus pas saturé
}
