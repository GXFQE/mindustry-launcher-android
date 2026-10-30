English · [简体中文](README.zh.md)

# Mindustry Manager — a multi-version manager for Mindustry on Android

> **Looking for the desktop build?** That is a separate project → **[Mindustry Launcher (Windows)](https://github.com/GXFQE/mindustry-launcher)**.

The official Android build of Mindustry **only lets you keep one version installed** — install a
second one and it overwrites the first. There is a single set of saves too, so switching versions
pushes the old progress aside. This manager gives every version its own **save slot**: several
versions installed side by side, each with separate **saves / maps / blueprints / mods**.

> ⚠️ **Release candidate (1.0.0-rc1) — the feature set is frozen.** The APK is on
> [Releases](https://github.com/GXFQE/mindustry-launcher-android/releases) (the one marked
> Pre-release); you can also build it yourself — see [Building](docs/DEVELOPING.md#构建)
> *(in Chinese for now)*. "Candidate" means no new features go in: only fixes, until a full RC
> cycle passes with no blocking bugs — **until then it is not a stable release.**
>
> The feature list below describes the **current source code**; the RC APK can lag a little behind it.

## What it solves

| Official Android build | With this manager |
|---|---|
| One version at a time — a second install overwrites the first; going back means reinstalling and moving saves by hand | Several versions installed side by side (159.7 / 160.4 / MindustryX X37 …), switch with one tap; **Continue** resumes the one you launched last |
| Every version shares one set of data (saves / maps / mods / settings), so switching versions replaces it | Each version gets a **slot**: saves / maps / blueprints / mods / settings are fully independent |
| Backing up means copying the whole data directory yourself | **Automatic backups** (per slot: when to trigger, how many to keep), content-addressed and shared between slots — measured on a real device: 3 slots × 20 backups, 9.1 GB of data in **448 MB** |
| Deleting a save, a map or a blueprint is final | Deleting any of them **moves it to the transfer station** first, and it can be put back |
| The game does say why a mod failed to load (missing dependency / version too low / blacklisted), but it never checks whether **two mods override the same content** | The **mods-health plug-in** checks who bundles whom, structure problems, dependencies, duplicates, blacklist and version thresholds, and what the last game log said |
| A crash leaves a log (which can be exported), but nothing says **which mod did it** | **Crash analysis** gives a verdict and its evidence; the report can be exported with that verdict, optionally **scrubbed first** |
| How much ore a map holds and where you can build can only be counted inside the game | The map page reports **ore / wall ore / buildable tiles / core zone**, and can export it as JSON / JSONL |
| A blueprint only reports its size and block count; which blocks are missing you find out by building it | The list names the **missing blocks** (and where it is unsure) |
| Exporting the whole data set takes the settings and login identities with it (the game does not separate them) | The **shareable package** strips login identities, server records and everything only useful to you |
| New features only arrive when the official build ships them | Plug-ins add abilities: three ship built in, others install by hand |

**How it works**: the official APK is loaded **as-is, as a plug-in** — no repacking, no
re-signing, no change to the game's package name. The game's own signature checks, save format and
mod ecosystem are untouched; the manager's only job is to give each version its own data root and
point the game at that directory when it starts.

## Getting started

1. **Get a game APK yourself** — this repository **does not include** the game. Use the official
   Mindustry from the official channel, or a build such as MindustryX. (Heavily modified packages
   may not load.)
2. **Install the manager** — download it from
   [Releases](https://github.com/GXFQE/mindustry-launcher-android/releases) (the one marked
   Pre-release), or build it yourself (see [Building](docs/DEVELOPING.md#构建)). It requires
   **Android 8.0 (API 26)** or newer; during development it was verified on a real device running
   **Android 16 (API 36)**.
3. **Import and play**:
   - Open the manager → **Add game** and pick one of the two: **Import APK** to choose your APK
     (a copy is made into the app's private directory), or **Add package name** to scan game
     versions already installed on the phone;
   - assign it a **slot** (use a different slot per version and the saves will not overwrite each
     other);
   - tap that version to launch. **Continue** goes straight back to the version you launched last.

Files from other apps come in too: opening a `.msav` / `.msch` / slot or mod zip from a file manager
or a chat hands it to the manager — with more than one slot it asks where to import.

The interface **follows your system language**: 简体中文 on a phone set to Chinese, English otherwise
(English and Simplified Chinese are the only translations so far). **Settings → Language** (the gear at
the top right) overrides that, including switching back to following the system; to translate it
yourself — even just a few lines — see **Translation file** below.

## What it can do

### Versions
- Scan games **already installed**, or **import** a copy of an APK
- One row per launch entry: tap to launch; **Manage** opens the version page (slot / clone / details / rename / assets / delete), and long-pressing anywhere else on the row does the same
- **To reorder**: long-press the icon at the left of a row and drag the row where you want it; the order is
  saved on release (rows you never moved, and newly imported versions, stay in their usual order at the end)
- **One version can be bound to several slots**: **Clone…** on its page adds a row that shares the same game
  files (no APK copy), while saves / settings / mods live in their own slots
- That page also has details, change-slot, name, assets and delete. Delete drops one entry at a time, and the
  whole version only on the last one (an imported package first goes to the transfer station); a version
  installed on the system keeps its last entry — uninstall it there
- Details: source, size, checksum, raw version name, ABI, build number, save count
- Version page → **Assets**: export the game package resources by type (sprites / sounds / music / maps / text / fonts and more); only the checked types go into the zip
- Two versions sharing a slot get a warning that their saves will overwrite each other
- **Packages that will not run are refused** — checked at import and again before launch
- **Continue** resumes the row you launched last (when one version has several slots, it comes back to the slot it used)
- The **?** at the top right (and the one next to the summary row) opens a short glossary: version,
  slot, launch entry, current / default slot, backup, transfer station, whole-slot import / export

### Slots
- A slot is one complete set of data: saves / maps / blueprints / mods / settings
- Create it from the row; rename / clone / delete live in the **⋮** menu
- Cloning backs up the source first; a deleted slot takes its backups to the transfer station and can be put back
- **Copy settings from another slot** (⋮ → Copy settings…): progress and identity keys are skipped, and
  slots on different game versions are refused
- Playtime (**official plug-in `usagestats`**): per-game-version breakdown on the slot page, total on
  the main summary card (game foreground time only; no plug-in, no line / card)

### Saves
- Thumbnails in the list; an unreadable file gets its own row with the reason
- Search and sort: name / newest first / size
- Import / export `.msav`: several at once, or a whole zip
- Deleting moves it to the transfer station; refused while the game is running
- Automatic backups: set when to trigger and how many to keep; a backup is taken before any overwrite
- Deduplicated: 3 slots × 20 backups, 9.1 GB of data in **448 MB**
- Whole-slot export / import: one zip, three merge modes (update / add missing / replace)
- Shareable package: identity data is stripped before you hand it to someone
- Every export can be sent straight to the share sheet without saving a file

### Maps
- Cards with previews (this slot / built into the game / from mods)
- Resource statistics (**official plugin `mapstats`**): ore / wall ore / mineable floor / bonus floor,
  buildable area, core zone (planet-aware) - plus JSON export (one map = `.json`, several = `.jsonl`,
  full or brief, save or share)
- Import / export `.msav`: several at once; maps built into the game or a mod can be exported too
- Turn a save into a map (an optional source map fills in the generator settings)
- Search and sort; deletion goes through the transfer station

### Blueprints
- Thumbnails in the list, a tile-by-tile preview on the detail page
- Details: blocks used, missing blocks, technical details
- Missing-block warnings; a "may be wrong" marker when it cannot be sure
- Import / export `.msch`: several at once
- Search and sort; deletion goes through the transfer station

### Mods
- Enable / disable one by one or in bulk (writes the game's own switch; backed up first)
- Every mod says whether it will load and why
- Mod health (**plug-in**, `plugins/mods-health`): bundles, structure, dependencies, duplicates,
  blacklist, version thresholds, two mods overriding the same vanilla content, and what the last log said
- Its verdicts drive the row badges and the extra **filter / sort** entries on this page
- Health is checked **at import time** (single and batch): the result dialog / batch report says whether the package will load - warnings only, never blocks
- Search, filter and sort; import / export; delete; copy to another slot

### Plug-ins
- The entry is the icon at the top right (right of the gear); install an APK to add abilities
- **Three built in** (shipped with the app, no install needed, cannot be deleted, can be disabled): `mapstats` resource statistics, `mods-health` mod health, `usagestats` play time
- Takes effect after a restart; the page offers **Restart the manager now**
- One row each: name · state · origin · what happens if it crashes
- Open its page, enable / disable, read its info; ones you installed can be deleted (built-in three cannot)
- **Plug-ins can update themselves**: the address sits in the plug-in's `plugin.json`; checked once a day
  (or on demand) and compared by sha256 (not version numbers); same signing key installs as pending
  (restart), a different one asks first
- Can add an entry row, a card or an action to almost every page (the plug-ins page itself is the exception)
- Can put its conclusions on rows and cards (severity-coloured), offer its own filter / sort entries, and supply the pass / fail checks in a mod's technical details
- Can read slots and content, settings, logs, the transfer station and the version list, and open files
- A crash does not take the manager down: retry / roll back / quarantine, and the page explains it

### Transfer station
- Deleted things land here instead of disappearing
- Shows what it is, which slot it came from, and its size
- Put it back (source slot preselected), or delete / empty it for good
- Ordinary items: at most 20; whole slots / whole versions: 3 each (configurable in Settings)

### Logs and settings
- Export logs unchanged, or scrubbed (paths / device / account details removed)
- Runtime log page: manager log, game log and crash reports in sections
- Crash analysis: names the mod with its evidence, and says so when it cannot tell
- Theme, language, translation file (import / remove / share), **screen orientation** (follow system / force landscape / force portrait)
- **Check for updates**: downloads and installs its own update; **Auto-check** picks what it accepts
  (off / stable only / stable and preview), **Update line** picks the mirror and can speed-test it.
  Builds up to 0.7.0 have no updater, so that one step is still manual
- Settings: default slot, crash alerts, crash reports to keep, trash retention, automatic cleanup, storage used
- About page: version / credits / data locations / GitHub repository, issues, GPL-3.0

## What is not there yet

- **A plug-in store**: no browsing, no one-click install. A plug-in that declares its own update address
  can update itself; everything else is still install by hand
- **Plug-in data**: the in-game side is not exposed yet (computed stats can already be shown on host pages — see playtime under Slots)

## What is not planned

- **Downloading game content in the app** (map / blueprint / mod browser, map sources, forum) —
  not planned for now. Mindustry has no Chinese game licence, and in October 2026 Chinese launchers
  dropped game downloads for compliance. This manager only imports files you already have.
- **Google Play**: loading another APK as a plug-in does not fit its policies; sideload only
- **A built-in file manager**: imports and exports go through the system file picker (and this is not
  a general-purpose save manager)
- **A plug-in sandbox**: plug-ins share the manager's process; "what it touches" is a declaration,
  not a permission wall — only install what you trust
- **Bundling the game**: copyright stays with its authors — bring your own APK

## More detail

- **[FEATURE.md](FEATURE.md)** — how this manager compares with the three other Android launchers of
  the period (22 items, judged from source rather than their READMEs)
- **[docs/DEVELOPING.md](docs/DEVELOPING.md)** — build chain, `dev_*` direct entries, module
  boundaries and a map of the documentation (in Chinese for now)
- **[docs/history/](docs/history/README.md)** — per-round implementation and on-device verification records

## Credits

This launcher builds on other people's work:

- **[Mindustry](https://github.com/Anuke/Mindustry)** by Anuke — GPL-3.0. The manager loads the
  official APK you provide; it does not bundle or redistribute the game.
- **[arc](https://github.com/Anuke/Arc)** by Anuke — Apache-2.0. Reference for asset injection, ABI
  and FreeType behaviour. `Hjson.java` is a hand port of `arc.util.serialization.Jval` (the HJSON
  read branch and the plain writer only; the two upstream versions and the changes are documented
  in that file).
- **[libGDX](https://libgdx.com/)** — Apache-2.0. The graphics layer under arc.
- **[Material Design Icons](https://github.com/google/material-design-icons)** by Google —
  Apache-2.0. Path data of the UI icons.
- **[MindustryX](https://github.com/TinyLake/MindustryX)** by TinyLake and contributors — GPL-3.0.
  A community fork of the game; this manager supports it and uses it as a behaviour reference
  (no code from it is bundled).

Tables generated from the game data (`BlockTable`, `ClassRotateTable`, `MschConfigTable`) follow
Mindustry's licence; the generators and the versions they were built from are recorded in the
source comments.

Projects that were only studied during feasibility work are **not** listed — they go in once a
feature actually ships. Rule and history: [docs/REFERENCES.md](docs/REFERENCES.md).

---

## Licence

This project is released under the **GNU General Public License v3.0**; the full text is in
[LICENSE](LICENSE).
Copyright (C) 2026 GXFQE.
