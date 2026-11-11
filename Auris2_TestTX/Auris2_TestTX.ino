/*
  Auris2_TestTX : teste le chemin d'émission D2 -> CTX -> transceiver -> CRX -> D3, SANS multimètre.

  /!\ MODULE DÉBRANCHÉ DE LA PRISE OBD (alimenté par l'USB seulement).
  Le SN65HVD230 recopie son entrée D (CTX) sur sa sortie R (CRX) quand personne d'autre ne parle :
    D2 = 0 -> bus dominant -> D3 doit lire 0
    D2 = 1 -> bus récessif -> D3 doit lire 1
  Sécurité : si D3 bouge tout seul pendant 500 ms (bus actif = branché sur la voiture), le test est refusé.
  Puis 20 vraies trames à 500 kbit/s renvoyées à soi-même (même garde).
  Résultat sur le moniteur série (115200). Ensuite, retéléverser Auris2_Module.
*/
#include "driver/twai.h"

#define CAN_TX_PIN D2
#define CAN_RX_PIN D3

// Vraies trames 500 kbit/s renvoyées à soi-même (mode NO_ACK + auto-réception), bus débranché
void testTwai() {
  twai_general_config_t g = TWAI_GENERAL_CONFIG_DEFAULT((gpio_num_t)CAN_TX_PIN, (gpio_num_t)CAN_RX_PIN, TWAI_MODE_NO_ACK);
  twai_timing_config_t t = TWAI_TIMING_CONFIG_500KBITS();
  twai_filter_config_t f = TWAI_FILTER_CONFIG_ACCEPT_ALL();
  if (twai_driver_install(&g, &t, &f) != ESP_OK) { Serial.println("TWAI install KO"); return; }
  if (twai_start() != ESP_OK) { Serial.println("TWAI start KO"); twai_driver_uninstall(); return; }
  int recu = 0;
  for (int i = 0; i < 20; i++) {
    twai_message_t m = {};
    m.identifier = 0x7FF; m.data_length_code = 8; m.self = 1;
    m.data[0] = i; m.data[1] = 0x55; m.data[2] = 0xAA; m.data[3] = 0xFF;
    twai_transmit(&m, pdMS_TO_TICKS(50));
    twai_message_t r;
    if (twai_receive(&r, pdMS_TO_TICKS(50)) == ESP_OK && r.identifier == 0x7FF && r.data[0] == i) recu++;
  }
  twai_status_info_t st;
  twai_get_status_info(&st);
  Serial.printf("TWAI 500k : %d/20 trames OK, etat=%d (1=RUN 2=BUSOFF) tec=%lu errbus=%lu\n",
                recu, (int)st.state, (unsigned long)st.tx_error_counter, (unsigned long)st.bus_error_count);
  if (recu == 20 && st.bus_error_count == 0) Serial.println("RESULTAT 500k : transceiver OK a pleine vitesse -> probleme cote voiture (charge 120 ohms)");
  else Serial.println("RESULTAT 500k : ECHEC -> transceiver trop lent ou defectueux a 500 kbit/s");
  twai_stop();
  twai_driver_uninstall();
  pinMode(CAN_RX_PIN, INPUT);
  pinMode(CAN_TX_PIN, OUTPUT);
  digitalWrite(CAN_TX_PIN, HIGH);
}

void setup() {
  Serial.begin(115200);
  pinMode(CAN_RX_PIN, INPUT);
  pinMode(CAN_TX_PIN, OUTPUT);
  digitalWrite(CAN_TX_PIN, HIGH);  // récessif : on n'impose rien au bus
}

// Test répété toutes les 3 s pour ne pas rater le résultat
void loop() {
  delay(3000);

  // Bus silencieux ? (une trame CAN ferait passer D3 à 0)
  uint32_t t0 = millis();
  while (millis() - t0 < 500) {
    if (digitalRead(CAN_RX_PIN) == LOW) {
      Serial.println("REFUS : activité sur le bus, débranche le module de la prise OBD");
      return;
    }
  }

  int ok = 0;
  for (int i = 0; i < 10; i++) {
    digitalWrite(CAN_TX_PIN, LOW);
    delayMicroseconds(50);
    int bas = digitalRead(CAN_RX_PIN);
    digitalWrite(CAN_TX_PIN, HIGH);
    delayMicroseconds(50);
    int haut = digitalRead(CAN_RX_PIN);
    Serial.printf("essai %d : D2=0 -> D3=%d (attendu 0) | D2=1 -> D3=%d (attendu 1)\n", i, bas, haut);
    if (bas == 0 && haut == 1) ok++;
  }
  digitalWrite(CAN_TX_PIN, HIGH);
  if (ok == 10) Serial.println("RESULTAT : chemin d'émission OK (le problème est ailleurs : CANH/CANL, prise OBD)");
  else if (ok == 0) Serial.println("RESULTAT : AUCUN retour -> fil D2-CTX coupé / mal soudé, ou transceiver HS côté émission");
  else Serial.printf("RESULTAT : instable (%d/10) -> faux contact probable\n", ok);
  testTwai();
}
