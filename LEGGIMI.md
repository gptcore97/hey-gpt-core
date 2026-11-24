# GPT CORE Assistant (Android nativo)

Assistente di sistema vero (come "Hey Google"): si registra come **assistente digitale predefinito**, si apre con la
pressione lunga su Home / gesto assistente, ascolta, ragiona con Gemini e agisce sul telefono.

## Come ottenere l'APK
1. Crea un repository GitHub PRIVATO e carica TUTTO il contenuto di questa cartella (compresa `.github` e `debug.keystore`).
2. Actions -> "Build Android (APK)" -> Run workflow -> scarica l'artefatto **gpt-core-assistant-apk** (app-debug.apk).
3. Installalo sul telefono (consenti "origini sconosciute").

## Primo avvio (app "GPT CORE")
1. Incolla la **chiave API Gemini** (gratis: aistudio.google.com/apikey) e salva.
2. "Concedi i permessi" (microfono, contatti, telefono, notifiche).
3. "Assistente predefinito" -> scegli **GPT CORE** come app assistente digitale.
4. Tieni premuto Home (o gesto dagli angoli / tasto power): compare il pannello.

## Cosa sa fare
- "Chiama Marco": cerca il contatto anche se capito male, controlla SIM / modalita' aereo; se non c'e' la SIM
  dice il motivo, elenca le app per chiamare (WhatsApp, Telegram, Signal...) e chiede quale usare.
- "Apri <app>": trova l'app anche con nome approssimato; se e' ambiguo chiede "Intendi X o Y?".
- Messaggi SMS/WhatsApp (preparati, tu premi invio), sveglie, timer, navigazione, musica, torcia, volume,
  impostazioni, fotocamera, eventi in calendario, stato batteria/rete.
- Ricerca web aggiornata (Gemini + Google Search): meteo, notizie, sport, prezzi.
- Memoria: "ricorda che mamma e' Maria Rossi" -> poi "chiama mamma".
- Conversazione continua: se ti fa una domanda, il microfono si riapre da solo.
- Voce naturale Gemini (10 voci) con ripiego sulla voce di Android; pannello animato con onda, bagliore e schede.
- Tastiera: tasto ⌨ nel pannello per scrivere invece di parlare.
- "Ehi Core" (facoltativo): ascolto in background; serve il permesso "Mostra sopra altre app".

## Note
- Il riconoscimento vocale usa l'app Google del telefono (deve essere installata/aggiornata).
- Le chiamate dirette richiedono il permesso Telefono; senza, apre il tastierino.
- La chiave API e la memoria restano solo sul telefono.
- Android non permette alle app di accendere Wi-Fi/Bluetooth da sole: apre il pannello giusto.
