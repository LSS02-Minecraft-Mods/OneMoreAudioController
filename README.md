# One More Audio Controller

Forge 1.20.1, mod completamente **client-side**.

Aggiunge alla schermata vanilla **Opzioni → Musica e suoni** slider audio indipendenti da quelli
vanilla (Master, Musica, Jukebox/Blocchi note, Meteo, Blocchi, Creature ostili, Creature amichevoli,
Giocatori, Ambiente, Voce/Parlato). Ogni slider aggiuntivo controlla **solo** i suoni che gli
assegni tu (o un altro mod), senza toccare Master, Players o qualunque altra categoria vanilla.

Puoi aggiungere nuovi controller in due modi, usabili insieme:

1. **JSON** - modifichi due file di config, niente codice. Pensato per modpack/utenti finali.
2. **API** - un altro mod registra i propri controller a runtime dal proprio codice Java. Pensato
   per sviluppatori (es. un mod di pistole che vuole uno slider "Suoni Pistole" separato da
   "Giocatori").

Tutto si applica **senza riavviare il gioco**: i due file JSON vengono riletti ogni volta che apri
la schermata Musica e suoni (anche se la apri dal bottone "Config" del menù Mods o da mod come
**Catalogue**).

---

## 1. Configurazione via JSON

I file si trovano in `config/onemoreaudiocontroller/` e vengono creati automaticamente al primo
avvio con dei valori di default.

### `controllers.json` - quali controller esistono

Ogni voce è un controller: un id, i suoni che deve gestire, e (dopo la prima modifica dello
slider) il volume salvato.

```json
[
  {
    "id": "menu_music",
    "sounds": ["minecraft:music.menu"]
  }
]
```

Campi:

| Campo            | Obbligatorio | Descrizione |
|-------------------|:---:|-------------|
| `id`               | sì | Identificatore univoco del controller (minuscolo, senza spazi). Non può coincidere con un nome di categoria vanilla (`master`, `music`, `records`, `weather`, `blocks`, `hostile`, `neutral`, `players`, `ambient`, `voice`). |
| `sounds`           | sì | Lista di sound event (`namespace:path`) che questo slider deve controllare. Trovi gli id dei suoni in `assets/<namespace>/sounds.json` del mod/resource pack che li definisce. |
| `translationKey`   | no | Chiave di traduzione per l'etichetta mostrata nel menù. Default: `soundCategory.<id>`. Aggiungi la chiave nei tuoi file lang (`assets/<namespace>/lang/it_it.json`, ecc.) o in un resource pack. |
| `volume`           | no | Volume iniziale, `0.0`-`1.0`. Default `1.0`. Il mod lo riscrive automaticamente ogni volta che sposti lo slider in gioco: non serve modificarlo a mano dopo il primo avvio. |

Esempio - un modpack vuole uno slider separato per la musica di un mod di ambientazione:

```json
[
  { "id": "menu_music", "sounds": ["minecraft:music.menu"] },
  { "id": "tavern_music", "sounds": ["mytavernmod:music.tavern_loop"] }
]
```

### `order.json` - in che ordine appaiono gli slider

Lista di id (categorie vanilla in minuscolo + id dei controller custom) che decide come vengono
accoppiati a due a due nella griglia:

```json
[
  "music", "menu_music",
  "records", "voice",
  "ambient", "weather",
  "hostile", "neutral",
  "players", "blocks"
]
```

Regole:

- **Master** è sempre il primo, da solo in cima: non va (e non può essere) inserito qui.
- **Selezione dispositivo audio**, **Sottotitoli** e **Audio direzionale** restano sempre le ultime
  tre voci, sotto a tutti i controller: anche queste non vanno inserite qui, il mod non le tocca.
- Ogni id elencato qui viene mostrato per primo, nell'ordine in cui lo scrivi.
- Qualsiasi controller (vanilla o custom) **non** elencato qui viene comunque mostrato, in coda,
  così un refuso in questo file non nasconde mai uno slider.
- Un id sconosciuto (typo, controller non definito da nessuna parte) viene ignorato con un
  warning nel log.

### `externalcontroller.json` - solo lettura, generato dal mod

Ogni volta che un mod registra un controller via API (vedi sotto), questo mod lo salva qui insieme
al volume corrente. Serve **solo** a farti vedere quali id sono già occupati dal codice di altri
mod, così quando scrivi `controllers.json` a mano eviti di riusare lo stesso id. Non modificarlo a
mano: viene rigenerato a ogni avvio e ogni cambio di volume, e non viene mai letto come sorgente di
nuovi controller (solo per ripristinare il volume salvato di un controller registrato via API).

Se `controllers.json` e un controller registrato via API usano lo stesso id, **vince sempre
l'API**: la voce JSON viene ignorata con un warning nel log che ti rimanda a
`externalcontroller.json`.

---

## 2. API per sviluppatori

Se stai scrivendo un mod (es. un mod di pistole) e vuoi un tuo slider indipendente senza chiedere
all'utente di editare JSON, registralo in codice con `OneMoreAudioControllerApi`.

### Dipendenza

Aggiungi questo mod come dipendenza di compilazione (jar in `libs/`, Maven, o Curse/Modrinth
maven a seconda di dove lo pubblichi) e dichiaralo come dipendenza opzionale nel tuo `mods.toml`,
così il tuo mod funziona anche senza:

```toml
[[dependencies.tuomodid]]
    modId="onemoreaudiocontroller"
    mandatory=false
    versionRange="[1.0,)"
    ordering="NONE"
    side="CLIENT"
```

Se il tuo mod deve funzionare anche senza `onemoreaudiocontroller` installato, avvolgi la chiamata
in un controllo `ModList.get().isLoaded("onemoreaudiocontroller")` prima di chiamare l'API.

### Uso

```java
import net.fancymenuaddon.onemoreaudiocontroller.api.OneMoreAudioControllerApi;
import net.minecraft.resources.ResourceLocation;

// Etichetta presa da soundCategory.mygunmod_gun_sounds nei tuoi file lang
OneMoreAudioControllerApi.registerController(
        "mygunmod_gun_sounds",
        new ResourceLocation("mygunmod", "gun_shot"),
        new ResourceLocation("mygunmod", "gun_reload")
);

// Oppure con una chiave di traduzione esplicita
OneMoreAudioControllerApi.registerController(
        "mygunmod_gun_sounds",
        "mygunmod.options.gun_sounds",
        new ResourceLocation("mygunmod", "gun_shot"),
        new ResourceLocation("mygunmod", "gun_reload")
);
```

Chiamala una volta sola, presto (costruttore del mod o `FMLCommonSetupEvent` vanno bene entrambi):
l'ordine finale sullo schermo viene calcolato solo quando il giocatore apre per la prima volta la
schermata Musica e suoni, quindi ben dopo che tutti i mod hanno finito di caricarsi.

Regole pratiche:

- **Scegli un id che includa il tuo modid** (es. `"mygunmod_gun_sounds"`, non `"gun_sounds"`) per
  evitare collisioni con altri mod.
- Non puoi usare un id riservato alle categorie vanilla (`master`, `music`, `records`, `weather`,
  `blocks`, `hostile`, `neutral`, `players`, `ambient`, `voice`).
- Richiamare `registerController` una seconda volta con lo stesso id lo ridefinisce (utile se il
  tuo mod ricalcola la lista suoni), mantenendo il volume che l'utente aveva già impostato.
- Il volume viene salvato automaticamente in `externalcontroller.json` a ogni modifica dello
  slider e ripristinato ai successivi avvii, senza bisogno di codice aggiuntivo lato tuo.
- Se in `order.json` non citi il tuo id, il tuo slider compare comunque, in coda a tutti gli
  altri controller.

---

## Compatibilità con Catalogue / menù Mods

Il mod registra la vanilla `ConfigScreenHandler` di Forge, quindi il bottone "Config" nel menù Mods
e mod come **Catalogue** aprono direttamente la stessa schermata Musica e suoni, con tutti gli
slider (vanilla + JSON + API) già nell'ordine configurato. Ogni apertura ricarica `controllers.json`
e `order.json` da disco, quindi puoi modificare i JSON, riaprire quella schermata e vedere subito
le modifiche, senza riavviare Minecraft.

---

## Build

```bash
./gradlew build
```

Richiede Java 17 (Minecraft 1.20.1 lo richiede a runtime). Il file `gradle.properties` è già
puntato a una JDK 17 locale (`org.gradle.java.installations.paths`): aggiornalo al percorso della
tua installazione se necessario. Il jar compilato si trova in `build/libs/`.
