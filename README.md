# Card Vault — card scanner & collection manager

An Android app (built for phones like the Galaxy S22 Ultra, Android 8.0+) for **Magic: The Gathering**,
**Pokémon** and **One Piece** cards. You can:

- **Scan cards with the camera.** On-device text recognition reads the printed card number
  (`OP05-119`, `057/191`, `DMU • EN 0123`) and the name, then looks the card up in the right database.
  You confirm the printing, finish (foil / holo / reverse) and condition. **Quick add** mode adds cards
  as fast as you can hold them up.
- **Track prices from several marketplaces.** Prices are refreshed **once a day** in the background
  (plus a manual refresh button). Each card gets a combined value (average, TCGplayer only,
  Cardmarket only, highest or lowest — your choice), with 24h / 7-day change and price history.
- **Use the price sheet.** Every card you own with each site's price, its unit value, the stack's total,
  and its trend. Sort and filter it, or export it as CSV for Excel / Google Sheets.
- **Most valuable cards chart.** A ranked bar chart of your cards showing each card's price, how many copies
  you own and today's change, plus your single most expensive card and what share of the collection's
  value your top cards make up. Rank by price per card or by total (price × copies), filter by game,
  and show the top 10, 25, 50 or all.
- **Browse your collection your way.** Search, filter by game, sort by value / name / set / rarity /
  cost / date, group by game / set / type / rarity / color, and switch between list and grid views.
- **Use binders.** Create colored binders (4, 9, 12 or 16 pockets per page) and swipe through them page
  by page like a real binder, or scroll through everything as one big grid.
- **Magic: The Gathering database.** Download every Magic card (about 35,000, with rules text and
  prices) from Scryfall's daily bulk data onto the phone, then search and filter offline: name / type /
  rules text, color identity (within, includes or exactly), card type, rarity, mana value, format
  legality and max price, sorted by name, popularity (EDHREC), price, mana value or release date.
  "Own N" badges show what you already have. It updates weekly on Wi-Fi while charging. Before it's
  downloaded, the same filters run online against Scryfall. The database also lets the scanner and search
  identify Magic cards with no signal, and it powers deck suggestions beyond your own collection.
- **Themes and customization.** 13 themes (Card Vault, Planeswalker, Electric, Grand Line, Holo Foil,
  the five Magic land types, Retro Handheld, Midnight OLED, and Material You colors from your wallpaper), light /
  dark / follow phone, a custom accent color, and background artwork: a tilted collage of your most
  valuable cards, your own photos (picked with the system photo picker, no storage permission needed),
  or any single card ("Use as background" on a card's page). Adjustable visibility, optional blur, and an
  animated holo shimmer on foil cards.
- **Build decks with strategy help.**
  - Format rules for Magic (Standard, Pioneer, Modern, Legacy, Pauper, Commander, Limited), Pokémon
    (Standard / Expanded / Unlimited) and One Piece: deck size, copy limits, legality, Leader / Commander
    colors, ACE SPEC / Radiant limits, and broken evolution lines.
  - Archetype detection (Aggro / Midrange / Control / Ramp; Stage 2 engine / single-prize / ex box;
    Rush / Midrange / Ramp-Control) with guidance based on common deck-building ratios: land and
    energy counts, curve, removal, draw, counters, blockers, Supporters, search cards, and opening-hand odds.
  - **Suggestions from cards you own**, ranked by synergy and by what the deck is missing, plus
    **Auto-build**, which fills a deck from your collection. Magic decks can also get suggestions from the
    whole card database (popular cards in your colors and format) to find upgrades worth buying.
  - Deck value and **cost to complete** (copies you still need × current price). Share the deck list as text.

## Data sources

| Game | Card data | Prices |
|---|---|---|
| Magic | [Scryfall](https://scryfall.com/docs/api) API + [bulk data](https://scryfall.com/docs/api/bulk-data) (offline database) | TCGplayer (USD), Cardmarket (EUR), MTGO (tix, shown for info only) |
| Pokémon | [Pokémon TCG API](https://pokemontcg.io) | TCGplayer (USD, per printing), Cardmarket (EUR) |
| One Piece | [OPTCG API](https://optcgapi.com) | TCGplayer market price |

Euro prices are converted to USD using the ECB rate from [frankfurter.app](https://frankfurter.app).
No API keys are required. A free Pokémon TCG API key (Settings) raises that API's rate limit for big
collections. Camera images never leave the phone.

## Installing on your phone

### Option A — download the built APK (no tools needed)
1. On GitHub, open **Actions → Android build**, click the latest green run, and download the
   **card-vault-debug-apk** artifact (a zip that contains `app-debug.apk`).
2. Copy `app-debug.apk` to your phone and open it. Android will ask you to allow installing from
   that app (Files / Chrome). Allow it, then install.

### Option B — build with Android Studio
1. Install [Android Studio](https://developer.android.com/studio) and open this folder.
2. On the phone, enable **Developer options** (Settings → About phone → Software information → tap
   *Build number* 7 times), then turn on **USB debugging**.
3. Connect the phone by USB, pick it in the device list, and press **Run ▶**.

Command line: `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.

## Scanning tips
- Fill the on-screen frame with the card and keep the **bottom edge** (where the card number is) sharp.
  Tap the screen to focus, and use the flashlight button in dim light.
- If a card isn't recognized, choose the game at the top of the screen (instead of *Auto*) or tap
  **Search by name**.
- Pick a binder at the top of the scanner to file scanned cards straight into it.

## Project layout
```
app/src/main/java/com/hazyoctophant/cardvault/
  data/db        Room database: cards, collection, prices, price history, binders, decks
  data/mtgdb     Offline Magic database: bulk import, filters (SQL + Scryfall syntax), weekly update
  data/remote    Scryfall, Pokémon TCG API, OPTCG API and exchange-rate clients
  data/repo      Collection, pricing (daily refresh + valuation) and binder/deck repositories
  scan           OCR text parser, ML Kit analyzer, scanner state machine
  pricing        Valuation rules and the daily WorkManager job
  strategy       Format rules, card-role detection, deck analysis and suggestions
  ui             Jetpack Compose screens
```
Unit tests (`./gradlew testDebugUnitTest`) cover the scanner text parser, price valuation, the deck
rules, analysis and suggestions, the Magic database filters, and Scryfall bulk-file parsing.

## Notes and limits
- Card recognition is based on the printed number and name, so very old cards without collector
  numbers, and non-English cards, may need a manual search.
- One Piece has a single free price source (TCGplayer via OPTCG API). The pricing code is written so
  more sources can be added per game.
- Strategy advice is heuristic: it encodes widely used deck-building guidelines and doesn't follow the
  current tournament meta.

Card Vault isn't affiliated with Wizards of the Coast, The Pokémon Company, Bandai, TCGplayer or Cardmarket.
