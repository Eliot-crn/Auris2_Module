/*
  Auris2_Diag : sketch de TEST, pont USB-serie <-> CAN pilote par le PC (tools/diag.py).

  /!\ ATTENTION : contrairement a Auris2_Module, ce sketch EMET sur le bus (TWAI_MODE_NORMAL).
  - A televerser A LA PLACE d'Auris2_Module, voiture a l'arret, en P.
  - Quand les tests sont finis : RETELEVERSER Auris2_Module (listen-only).

  Services autorises (LISTE BLANCHE codee en dur, tout le reste est REFUSE) :
    01 (donnees courantes), 03 (lecture DTC), 09 (infos vehicule), 21 (KWP lecture), 22 (UDS lecture)
    + controle de flux ISO-TP (octet0 = 0x30) pour recevoir les reponses multi-trames.
  IDs autorises : 0x7DF et 0x700..0x7F7, trame standard, DLC 8, jamais RTR/etendue,
    jamais un ID deja vu sur le bus (anti-collision). Max 20 TX / s.
  Interdits (entre autres) : 10, 11, 14, 27, 2E, 2F, 31, 34-37, 3E, 85, first/consecutive frames.

  Serie 115200 : commande "TX <id hex 3> <16 hex>"  ex "TX 7E0 0201050000000000" -> "OK" / "REFUS <raison>"
  Sortie : trames 0x700..0x7FF au format candump "(sec.ms) can0 7E8#0441057B..."
  La table autorise() est dupliquee dans tools/diag.py : les deux doivent rester IDENTIQUES.
*/
#include "driver/twai.h"

#define CAN_TX_PIN D2
#define CAN_RX_PIN D3

bool vu[2048];  // tout ID standard recu au moins une fois
uint32_t tsTx[20];  // dates des 20 derniers TX acceptes (ms)
uint8_t nTx = 0;
char ligne[48];
uint8_t nl = 0;

void stop(const char* msg) {
  for (;;) { Serial.println(msg); delay(1000); }
}

// Pure (hors vu[]) : true si la trame est autorisee, sinon raison remplie (>= 32 octets).
bool autorise(uint32_t id, const uint8_t* d, char* raison) {
  if (!(id == 0x7DF || (id >= 0x700 && id <= 0x7F7))) { strcpy(raison, "id hors liste"); return false; }
  if (vu[id]) { strcpy(raison, "id utilise sur le bus"); return false; }
  if (d[0] == 0x30) {  // flux ISO-TP CTS ; pas de sens vers le broadcast
    if (id == 0x7DF) { strcpy(raison, "FC vers 7DF"); return false; }
    return true;
  }
  uint8_t n = d[0], s = d[1];
  if (n < 1 || n > 7) { strcpy(raison, "PCI non autorise"); return false; }
  if (!(s == 0x01 || s == 0x03 || s == 0x09 || s == 0x21 || s == 0x22)) { strcpy(raison, "service non autorise"); return false; }
  if ((s == 0x03 && n != 1) || (s == 0x22 && n != 3) || ((s == 0x01 || s == 0x09 || s == 0x21) && n < 2)) { strcpy(raison, "longueur"); return false; }
  return true;
}

int hex(char c) {
  if (c >= '0' && c <= '9') return c - '0';
  if (c >= 'A' && c <= 'F') return c - 'A' + 10;
  if (c >= 'a' && c <= 'f') return c - 'a' + 10;
  return -1;
}

bool chk(uint32_t id, const char* h, bool attendu) {
  uint8_t d[8];
  char r[32];
  for (int i = 0; i < 8; i++) d[i] = hex(h[2 * i]) * 16 + hex(h[2 * i + 1]);
  return autorise(id, d, r) == attendu;
}

void autoTest() {
  bool ok = chk(0x7E0, "0201050000000000", true) &&
            chk(0x7DF, "0201050000000000", true) &&
            chk(0x7E0, "0322F19000000000", true) &&
            chk(0x7E0, "0122000000000000", false) &&  // 22 exige N=3
            chk(0x7E0, "0103000000000000", true) &&
            chk(0x7E0, "0203000000000000", false) &&  // 03 exige N=1
            chk(0x7E0, "022E010000000000", false) &&
            chk(0x7E0, "1003000000000000", false) &&  // first frame
            chk(0x7E0, "0210030000000000", false) &&
            chk(0x7E0, "0114000000000000", false) &&
            chk(0x7E0, "0227000000000000", false) &&
            chk(0x7E0, "0211010000000000", false) &&
            chk(0x6C0, "0201050000000000", false) &&
            chk(0x7F8, "0201050000000000", false) &&
            chk(0x7E0, "3000000000000000", true) &&
            chk(0x7DF, "3000000000000000", false) &&
            chk(0x7E0, "21FF000000000000", false) &&  // consecutive frame
            chk(0x7E0, "0001050000000000", false) &&
            chk(0x7E0, "0801050000000000", false);
  vu[0x7E1] = true;
  ok = ok && chk(0x7E1, "0201050000000000", false);
  vu[0x7E1] = false;
  if (!ok) stop("AUTOTEST autorise() KO : blocage");
}

void commande(const char* l) {
  char r[32];
  uint8_t d[8];
  if (strcmp(l, "ST") == 0) {  // état du contrôleur (lecture seule)
    twai_status_info_t s;
    if (twai_get_status_info(&s) != ESP_OK) { Serial.println("ST erreur"); return; }
    Serial.printf("ST etat=%d (0=STOP 1=RUN 2=BUSOFF 3=RECOV) tec=%lu rec=%lu errbus=%lu txfail=%lu rxmiss=%lu\n",
                  (int)s.state, (unsigned long)s.tx_error_counter, (unsigned long)s.rx_error_counter,
                  (unsigned long)s.bus_error_count, (unsigned long)s.tx_failed_count, (unsigned long)s.rx_missed_count);
    return;
  }
  if (strncmp(l, "TX ", 3) != 0 || strlen(l) != 3 + 3 + 1 + 16 || l[6] != ' ') { Serial.println("REFUS syntaxe"); return; }
  uint32_t id = 0;
  for (int i = 3; i < 6; i++) { int h = hex(l[i]); if (h < 0) { Serial.println("REFUS syntaxe"); return; } id = id * 16 + h; }
  for (int i = 0; i < 8; i++) {
    int a = hex(l[7 + 2 * i]), b = hex(l[8 + 2 * i]);
    if (a < 0 || b < 0) { Serial.println("REFUS syntaxe"); return; }
    d[i] = a * 16 + b;
  }
  if (!autorise(id, d, r)) { Serial.print("REFUS "); Serial.println(r); return; }
  uint32_t now = millis();
  if (nTx == 20 && now - tsTx[0] < 1000) { Serial.println("REFUS limiteur 20/s"); return; }
  twai_status_info_t st;
  if (twai_get_status_info(&st) != ESP_OK || st.state != TWAI_STATE_RUNNING) { Serial.println("REFUS bus pas RUNNING"); return; }
  twai_message_t m = {};
  m.identifier = id;
  m.data_length_code = 8;
  memcpy(m.data, d, 8);
  if (twai_transmit(&m, pdMS_TO_TICKS(10)) != ESP_OK) { Serial.println("REFUS tx"); return; }
  if (nTx == 20) { memmove(tsTx, tsTx + 1, 19 * sizeof(uint32_t)); nTx = 19; }
  tsTx[nTx++] = now;
  Serial.println("OK");
}

void setup() {
  Serial.begin(115200);
  autoTest();
  twai_general_config_t g = TWAI_GENERAL_CONFIG_DEFAULT((gpio_num_t)CAN_TX_PIN, (gpio_num_t)CAN_RX_PIN, TWAI_MODE_NORMAL);
  g.rx_queue_len = 128;
  twai_timing_config_t t = TWAI_TIMING_CONFIG_500KBITS();
  twai_filter_config_t f = TWAI_FILTER_CONFIG_ACCEPT_ALL();
  if (twai_driver_install(&g, &t, &f) != ESP_OK) stop("TWAI install KO");
  if (twai_start() != ESP_OK) stop("TWAI start KO");
  Serial.println("# Auris2_Diag pret (TX en liste blanche)");
}

void loop() {
  twai_message_t m;
  while (twai_receive(&m, 0) == ESP_OK) {
    if (m.extd) continue;
    vu[m.identifier & 0x7FF] = true;
    if (m.identifier >= 0x700 && m.identifier <= 0x7FF) {
      uint32_t ms = millis();
      Serial.printf("(%lu.%03lu) can0 %03X#", (unsigned long)(ms / 1000), (unsigned long)(ms % 1000), (unsigned)m.identifier);
      for (int i = 0; i < m.data_length_code && !m.rtr; i++) Serial.printf("%02X", m.data[i]);
      Serial.print('\n');
    }
  }
  while (Serial.available()) {
    char c = Serial.read();
    if (c == '\r') continue;
    if (c == '\n') { ligne[nl] = 0; if (nl) commande(ligne); nl = 0; }
    else if (nl < sizeof(ligne) - 1) ligne[nl++] = c;  // ligne trop longue : tronquee, donc refusee (longueur)
  }
}
