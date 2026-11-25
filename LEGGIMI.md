# GPT CORE — app unica + assistente di sistema

Un solo progetto Android nativo: l'app completa (accesso Google/email, chat, cronologia, Store API con qualsiasi
provider/modello, Mail, impostazioni, dettatura, conversazione vocale) **più** l'assistente di sistema
(pressione lunga su Home, "Ehi Core", apre app, chiama contatti con controllo SIM, ricerca web, ecc.).
Nella chat, il pulsante tondo sfumato in basso a destra della barra messaggi apre l'assistente.

## Compilare l'APK
1. Carica TUTTO il contenuto di questa cartella (cartella nascosta `.github` compresa) in un repository GitHub.
2. Scheda **Actions** → *Build Android (APK)* → Run workflow (parte anche da sola a ogni push).
3. A fine build scarica l'artefatto `gpt-core-assistant-apk` → `app-debug.apk` e installalo.
La firma è fissa (`debug.keystore`): gli aggiornamenti si installano sopra senza disinstallare.

## Primo avvio
- Accedi (Google o email), scrivi il tuo nome.
- Menu → **Store API e modelli**: aggiungi la tua API (OpenRouter, Gemini, OpenAI, locale, qualsiasi URL compatibile) e scegli il modello.
  L'assistente di sistema usa la stessa API attiva. Per la **voce naturale** serve anche una chiave Gemini nello Store.
- Impostazioni → **Assistente di sistema**: "Imposta come assistente", concedi i permessi, prova.

## Voce personalizzabile (Impostazioni → Voce dell'assistente)
30 voci Gemini, campo libero "stile" (es. "come un narratore di documentari"), preset di stile, velocità, anteprima.
Una voce clonata da una persona reale richiede un servizio esterno: non inclusa.

## Login Google
Il redirect usa lo schema `com.googleusercontent.apps.<ID>`, ricavato da `assets/www/config.json`
(`google.android_client_id`, altrimenti `google.ios_client_id`, che funziona senza SHA-1).
Se crei un client OAuth di tipo **Android** in Google Cloud: package `it.flubatir.coreassistant` + SHA-1 del `debug.keystore`
(`keytool -list -v -keystore debug.keystore -storepass android`), poi mettilo in `android_client_id`.

## Note
- Android 13+: concedi notifiche e microfono; la parola "Ehi Core" richiede la notifica fissa.
- iOS non è più incluso (versione solo Android).
- Non ho potuto compilare qui: se l'Action segnala un errore, incolla il log e lo correggo.
