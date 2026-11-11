/*
  Auris2_Sniffer : sniffer CAN PASSIF, Toyota Auris II Hybride.
  Sortie série au format candump (rejouable avec canplayer / SavvyCAN) + OLED.

  SECURITE : TWAI en TWAI_MODE_LISTEN_ONLY (aucune émission, pas même les ACK).
  Ce programme n'appelle JAMAIS twai_transmit.

  Câblage identique à Auris2_Module.ino (XIAO ESP32-C6, D2=CTX, D3=CRX, OLED SDA D4 / SCL D5).
  Bibliothèque : U8g2. Carte : Seeed XIAO ESP32-C6, core esp32 3.x.

  Sortie : "(sec.micros) can0 ID#DONNEES" par trame, et toutes les secondes
  une ligne de statut commençant par '#'.
*/
#include "driver/twai.h"
#include "esp_timer.h"
#include <U8g2lib.h>

#define CAN_TX_PIN D2  // modifiable
#define CAN_RX_PIN D3  // modifiable

U8G2_SSD1306_128X64_NONAME_F_HW_I2C u8g2(U8G2_R0, U8X8_PIN_NONE);

uint32_t nbTrames = 0, nbPrec = 0, fps = 0, nbIds = 0, perdues = 0;
uint32_t tDerniere = 0, tEcran = 0, tStatut = 0;
uint16_t nouveau[2];  // 2 derniers ID découverts (nouveau[1] = le plus récent)
bool vu[2048];

void ecran(const char* l1, const char* l2) {
  u8g2.clearBuffer();
  u8g2.drawStr(0, 10, l1);
  u8g2.drawStr(0, 20, l2);
  u8g2.sendBuffer();
}

[[noreturn]] void erreurFatale(const char* msg) {
  Serial.println(msg);
  ecran("ERREUR", msg);
  while (true) delay(1000);
}

void rafraichir() {
  char l[32];
  u8g2.clearBuffer();
  u8g2.drawStr(0, 10, "SNIFFER CAN");
  if (millis() - tDerniere > 2000) {
    u8g2.drawStr(0, 20, "Pas de CAN");
  } else {
    snprintf(l, sizeof l, "Trames/s: %lu", (unsigned long)fps);
    u8g2.drawStr(0, 20, l);
    snprintf(l, sizeof l, "IDs: %lu", (unsigned long)nbIds);
    u8g2.drawStr(0, 30, l);
    snprintf(l, sizeof l, "Perdues: %lu", (unsigned long)perdues);
    u8g2.drawStr(0, 40, l);
    snprintf(l, sizeof l, "Nouveaux: %03X %03X", nouveau[0], nouveau[1]);
    u8g2.drawStr(0, 50, l);
  }
  u8g2.sendBuffer();
}

void setup() {
  Serial.begin(115200);
  u8g2.begin();
  u8g2.setFont(u8g2_font_6x10_tf);

  twai_general_config_t g = TWAI_GENERAL_CONFIG_DEFAULT((gpio_num_t)CAN_TX_PIN, (gpio_num_t)CAN_RX_PIN, TWAI_MODE_LISTEN_ONLY);
  g.rx_queue_len = 128;
  twai_timing_config_t t = TWAI_TIMING_CONFIG_500KBITS();
  twai_filter_config_t f = TWAI_FILTER_CONFIG_ACCEPT_ALL();
  if (twai_driver_install(&g, &t, &f) != ESP_OK) erreurFatale("TWAI install KO");
  if (twai_start() != ESP_OK) erreurFatale("TWAI start KO");
}

void loop() {
  twai_message_t m;
  while (twai_receive(&m, 0) == ESP_OK) {
    // micros 64 bits : micros() 32 bits reboucle toutes les 71 min
    uint64_t us = (uint64_t)esp_timer_get_time();
    nbTrames++;
    tDerniere = millis();
    char b[64];
    int n = snprintf(b, sizeof b, m.extd ? "(%lu.%06lu) can0 %08X#" : "(%lu.%06lu) can0 %03X#",
                     (unsigned long)(us / 1000000), (unsigned long)(us % 1000000), (unsigned)m.identifier);
    if (m.rtr) b[n++] = 'R';
    else for (int i = 0; i < m.data_length_code && i < 8; i++) n += snprintf(b + n, sizeof b - n, "%02X", m.data[i]);
    b[n++] = '\n';
    Serial.write((const uint8_t*)b, n);
    if (!m.extd && !vu[m.identifier & 0x7FF]) {
      vu[m.identifier & 0x7FF] = true; nbIds++;
      nouveau[0] = nouveau[1]; nouveau[1] = m.identifier & 0x7FF;
    }
  }
  uint32_t now = millis();
  if (now - tStatut >= 1000) {
    tStatut = now;
    fps = nbTrames - nbPrec; nbPrec = nbTrames;
    twai_status_info_t s;
    twai_get_status_info(&s);
    perdues = s.rx_missed_count;
    char b[96];
    int n = snprintf(b, sizeof b, "# fps=%lu ids=%lu missed=%lu overrun=%lu bus=%d\n", (unsigned long)fps,
                     (unsigned long)nbIds, (unsigned long)s.rx_missed_count, (unsigned long)s.rx_overrun_count, (int)s.state);
    Serial.write((const uint8_t*)b, n);
  }
  if (now - tEcran >= 500) { tEcran = now; rafraichir(); }
}
