# Couchmates: texter till Google Play och App Store

Båda butikerna låter dig ha en egen titel, beskrivning och (App Store) egna sökord per språk.
Den som har telefonen eller TV:n på svenska ser och söker i de svenska texterna.

Varumärket är **Couchmates** på alla språk. Den svenska titeln innehåller de ord svenskar troligen söker på.

---

## Svenska (sv-SE)

| Fält | Text | Gräns |
|---|---|---|
| Titel (Play) / Namn (App Store) | Couchmates – Titta tillsammans | 30/30 |
| Undertitel (App Store) | Samma film, samma sekund | 24/30 |
| Kort beskrivning (Play) | Titta på samma kanal, film eller serie som dina vänner, helt i synk. | 68/80 |
| Sökord (App Store) | titta tillsammans,soffkompisar,synk,iptv,vänner,filmkväll,live-tv,serier,xtream,watch party | 91/100 |

**Lång beskrivning**

Couchmates gör att du och dina vänner tittar på exakt samma sak samtidigt, var ni än sitter.

• Samma bild för alla. Live-TV, filmer och serier spelas i synk på alla TV-apparater, ner på tiondels sekunden.
• Vem som helst har fjärren. Alla kan byta kanal, pausa och spola, och det gäller direkt för alla.
• TV-guide med vad som sänds nu och härnäst.
• Sök bland kanaler, filmer och serier på en gång.
• Historik: fortsätt där ni slutade.
• Välj ljud och undertext själv, utan att det påverkar de andra.
• Fungerar på Android TV, Google TV, Apple TV och i webbläsaren.

Couchmates innehåller inga kanaler eller filmer. Appen ansluter till din egen Couchmates-server, som använder din egen IPTV-tjänst (Xtream Codes, till exempel via Dispatcharr).

---

## English (en-US / en-GB)

| Field | Text | Limit |
|---|---|---|
| Title (Play) / Name (App Store) | Couchmates – Watch Together | 27/30 |
| Subtitle (App Store) | Same show, same second | 22/30 |
| Short description (Play) | Watch the same channel, movie or series as your friends, perfectly in sync. | 75/80 |
| Keywords (App Store) | watch party,watch together,sync,iptv,friends,movie night,live tv,series,xtream,couch | 84/100 |

**Full description**

Couchmates lets you and your friends watch exactly the same thing at the same time, wherever you are.

• Everyone sees the same picture. Live TV, movies and series play in sync on every TV, down to a fraction of a second.
• Anyone can hold the remote. Change channel, pause or skip, and it happens for everyone instantly.
• TV guide showing what's on now and next.
• Search channels, movies and series at once.
• History: pick up where you left off.
• Choose your own audio and subtitles without affecting anyone else.
• Works on Android TV, Google TV, Apple TV and in the browser.

Couchmates does not include any channels or movies. The app connects to your own Couchmates server, which uses your own IPTV service (Xtream Codes, for example through Dispatcharr).

---

## Bra att veta inför publicering

- **Google Play:** ett utvecklarkonto kostar 25 USD en gång. TV-appar granskas extra, så det behövs skärmdumpar från TV:n och en banner (finns i `android-tv/app/src/main/res/drawable-xhdpi/banner.png`).
- **App Store:** kräver Apple Developer (99 USD/år). Namnet "Couchmates" måste vara ledigt i App Store. Det kontrolleras när appen registreras i App Store Connect.
- **IPTV-appar** granskas strikt i båda butikerna. Det viktiga är att appen inte innehåller eller pekar på något innehåll själv. Därför står det tydligt i beskrivningen att man ansluter till sin egen server.
- **Namnet under ikonen** kan också vara olika per språk:
  - Android: `android-tv/app/src/main/res/values-sv/strings.xml`
  - Apple TV: `apple-tv/SyncTV/sv.lproj/InfoPlist.strings`

  Just nu står det "Couchmates" på båda språken.
