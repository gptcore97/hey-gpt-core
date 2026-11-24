# GPT CORE Assistant (Android nativo)

Assistente di sistema vero (come "Hey Google"): si registra come **assistente digitale predefinito**, si apre con la
pressione lunga su Home / gesto assistente, ascolta, ragiona con Gemini e agisce sul telefono.
Stesso aspetto e stessa logica di GPT CORE Mobile (accesso, nome, config.json, scelta modello, ricerca web), senza la chat e senza la Mail.

```
.github/workflows/build-apk.yml   <- build dell'APK (Java 17, setup-java v5, setup-android con packages vuoto)
app/src/main/assets/config.json   <- prompt del creatore, modello, ripiego, ricerca web, ID Google (letto a ogni avvio)
app/src/main/java/.../            <- MainActivity (impostazioni), AuthActivity (accesso), AssistActivity (pannello),
                                     Brain (Gemini + ripiego), Models (modelli simili), Config, Prefs, Tools, Voice...
debug.keystore                    <- firma fissa: puoi aggiornare l'app senza disinstallarla
```

## Come ottenere l'APK
1. Crea un repository GitHub PRIVATO e carica TUTTO il contenuto di questa cartella (compresa `.github` e `debug.keystore`).
2. Actions -> "Build Android (APK)" -> Run workflow -> scarica l'artefatto **gpt-core-assistant-apk** (app-debug.apk).
3. Installalo sul telefono (consenti "origini sconosciute"). Se avevi la versione 1, si aggiorna da sola (stessa firma).

## Primo avvio
1. **Accesso**: *Continua con Google* oppure account locale (nome + password, niente email). Il nome dell'account e' quello
   con cui ti chiama l'assistente: non te lo chiede piu'. Modificabile in Account.
2. Incolla la **chiave API Gemini** (gratis: aistudio.google.com/apikey) e premi *Verifica e salva la chiave*.
3. Scegli il **modello** dall'elenco (scaricato da Gemini) e la **ricerca web** (Auto / Sempre / Spenta).
4. "Concedi i permessi" (microfono, contatti, telefono, notifiche).
5. "Assistente predefinito" -> scegli **GPT CORE**. Poi tieni premuto Home: compare il pannello.

## Se Gemini dice "riprova piu' tardi"
Con l'interruttore *Se un modello non risponde, passa al piu' simile* (acceso di default):
- errore passeggero (sovraccarico 503, server 5xx): ritenta una volta lo stesso modello;
- quota finita (429) o ancora occupato: passa al modello piu' simile (stessa famiglia flash/pro/lite, versione piu' vicina),
  fino a 4 modelli, e ricorda per un po' quali non rispondono; vale anche per la ricerca web;
- errori di chiave o di richiesta NON cambiano modello (non servirebbe) e ti dice chiaramente cosa non va.
L'elenco dei modelli simili viene da Gemini stesso (quindi include le versioni nuove); `fallback_models` in config.json
e' la lista di riserva.

## config.json (app/src/main/assets/config.json)
`system_prompt` (testo o lista, aggiunto alle istruzioni), `model`, `auto_fallback`, `fallback_models`, `web_search` (auto|always|off),
`google` (web_client_id, ios_client_id). Dentro l'app: *Importa config.json* per cambiarlo senza ricompilare
(accetta anche `api_key`). L'ID Google invece si legge solo dal file nell'APK: se lo cambi, ricompila.
La chiave API non va scritta nel config.json del repository.

## Accesso Google: cosa serve
Usa OAuth con PKCE nel browser di sistema e lo stesso `google.ios_client_id` di GPT CORE Mobile; lo schema di ritorno viene
inserito nel manifest in fase di build leggendo config.json. Se Google rifiuta l'accesso da Android, serve un client OAuth di tipo
Android (package `it.flubatir.coreassistant` + SHA-1 di debug.keystore) oppure usa l'account locale. I token non vengono salvati.
Gli account locali restano solo sul telefono (password con hash PBKDF2): se la dimentichi si elimina l'account e se ne crea uno nuovo.

## Cosa sa fare
- "Chiama Marco": cerca il contatto anche se capito male, controlla SIM / modalita' aereo; se non c'e' la SIM
  dice il motivo, elenca le app per chiamare (WhatsApp, Telegram, Signal...) e chiede quale usare.
- "Apri <app>": trova l'app anche con nome approssimato; se e' ambiguo chiede "Intendi X o Y?".
- Messaggi SMS/WhatsApp (preparati, tu premi invio), sveglie, timer, navigazione, musica, torcia, volume,
  impostazioni, fotocamera, eventi in calendario, stato batteria/rete.
- Ricerca web aggiornata (Gemini + Google Search): meteo, notizie, sport, prezzi.
- Memoria: "ricorda che mamma e' Maria Rossi" -> poi "chiama mamma".
- Conversazione continua, voce naturale Gemini (10 voci) con ripiego sulla voce di Android, tastiera nel pannello.
- "Ehi Core" (facoltativo): ascolto in background; serve il permesso "Mostra sopra altre app".

## Note
- Il riconoscimento vocale usa l'app Google del telefono (deve essere installata/aggiornata).
- Le chiamate dirette richiedono il permesso Telefono; senza, apre il tastierino.
- La chiave API e la memoria restano solo sul telefono.
- Android non permette alle app di accendere Wi-Fi/Bluetooth da sole: apre il pannello giusto.
