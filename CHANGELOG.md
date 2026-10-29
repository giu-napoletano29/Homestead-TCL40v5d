# Changelog

This fork keeps the original's version numbers and adds a fourth digit or a new minor. The
original's history (up to 1.6.14) is in the git log below the fork's first commit.

## 1.7.0

A new look, a rebuilt Settings, and a Canali TV page for Italian live TV. Developed and tested
on a Fire TV Stick (`AFTSS`, Fire OS 7.7) and a TCL 40V5D (Fire OS 14).

### Look

- **A side rail** instead of the top bar: the quick settings you choose come first, then the
  Appstore, System settings and Settings. Icons at rest, labels when it has focus; glass at
  rest and the settings pages' own colour when open. Back from the rail returns to the start.
- **Manrope** throughout, bundled in the app (`res/raw`, OFL).
- **A dock with no band**: Canali TV and the three most used apps, tiles 3:2, no zoom beyond
  4% on focus, a white rim on the focused tile only. Under it, a grid of **square icons**
  drawn edge to edge (a wide logo such as Prime Video's is extended into a square, not
  stretched), and the name of the focused tile above the dock.
- **Backgrounds**: a dark fade that leaves the top half untouched and goes to near-black
  under the dock, over presets and photos alike; **four public-domain paintings** bundled
  (Starry Night, The Great Wave, The Kiss, Water Lilies); the idle clock sits on a blurred,
  heavily dimmed copy of a photo or painting.
- The clock moves to the top right with a small spaced date and a network icon that turns
  amber when there is no connection.
- Very light tiles (Netflix's white banner) are toned down until they have focus.
- Settings pages take the wallpaper's colour, with a translucent row band and 6 dp corners.
- Sharper tiles: icons are decoded at 160 px and banners at the dock tile's own size.

### Canali TV

- **Tabs**: Rai, Mediaset, Radio Mediaset and other apps. Left and Right on the strip change
  tab; Down enters the cards.
- Rai's own colours (1 blue, 2 red, 3 green, 4 violet) and a TV-style card: a small label
  over one big word or number.
- **Every Mediaset channel and radio** the Infinity "Dirette" page lists, each opening
  Infinity on that channel (`/diretta/<slug>`). Checked on a Stick: Canale 5, Italia 1,
  Rete 4. The others follow the same pattern but are not individually verified.
- A near-black Canali TV tile with the Material antenna.

### Settings

- **Settings** (Homestead's own) and **System settings** (the TV's) are separate pages.
- **Screen & sound** (no root needed): HDMI-CEC switches, sleep with the TV, Miracast,
  **overscan on/off and amount**, the Stick's own **15-minute sleep**, and the colour depth
  shown for reference. System settings shows how many of these deserve a look.
- **App info** in a tile's Menu: Amazon's page for the app (cache, data, force stop).
- Quick settings now feed the rail; Settings and TV Health can be hidden like any tile.
- Italian translation of the home screen and the main Settings and System settings rows.
- **Inputs** entry in the rail on a TV with inputs: on the TCL it presses the TV's own INPUT
  key through the debugging link (brought up while the item has focus), with Homestead's list
  as the fallback.
- **Simple mode is gone** (setting, setup step, Menu x5). Menu and holding Select always hide
  a tile; the update pop-up follows the "check for updates" choice only.
- **Check for updates** reads this fork's releases, says "no release published yet" instead
  of "unavailable" when there is none, and is translated.
- Live TV (the tuner) always comes first, and on a TV with a tuner Canali TV has no tile
  (a remote button bound to it still opens the page).
- **Home screen settings in three pages**: Appearance (background, columns, dock, tile style,
  clock, date, greeting), Content & order (sort, hidden tiles, sections, Live TV first) and
  Behaviour (idle clock).
- **Releases are automatic**: a push to `main` with a new `versionName` builds, signs and
  publishes it (`.github/workflows/release.yml`, `docs/RELEASING.md`).

### Fixes

- A wallpaper drawn at half width, shifted, or black until it was chosen again (a bitmap
  inside a layer is positioned at its own size; the wallpaper now always fills its bounds).
- Focus no longer escapes behind an open panel.
- The Settings tile no longer lands on a do-nothing stub when Amazon's launcher is off.
- The page is redrawn after a wake from standby or the idle clock.

### Numbers (Fire TV Stick, Fire OS 7.7)

- Memory about 70-75 MB; no janky frames over 22 moves of the focus; cold start about
  0.95-1.8 s, most of it loading the 24 tiles.

## 1.6.14.1

- A custom build: the update check looks at this fork's releases.
