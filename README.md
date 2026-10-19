English · [简体中文](README.zh.md)

# MDT Manager — a multi-version manager for Mindustry on Android

The official Android build of Mindustry **only lets you keep one version installed** — install a
second one and it overwrites the first. There is a single set of saves too, so switching versions
pushes the old progress aside. This manager gives every version its own **save slot**: several
versions installed side by side, each with separate **saves / maps / blueprints / mods**.

> ⚠️ **Pre-release only — the feature set is not finished.** The APK is on
> [Releases](https://github.com/GXFQE/mindustry-launcher-android/releases) (the one marked
> Pre-release); you can also build it yourself — see [Building](docs/DEVELOPING.md#构建)
> *(in Chinese for now)*. Pre-release means the interface, the data format and
> the features can all still change — **do not treat it as a stable release.**
>
> The feature list below describes the **current source code**; the newest pre-release APK can lag
> a little behind it.

## What it solves

| Official Android build | With this manager |
|---|---|
| One version at a time — a second install overwrites the first; going back means reinstalling and moving saves by hand | Several versions installed side by side (159.7 / 160.4 / MindustryX X37 …), switch with one tap; **Continue** resumes the one you launched last |
| Every version shares one set of data (saves / maps / mods / settings), so switching versions replaces it | Each version gets a **slot**: saves / maps / blueprints / mods / settings are fully independent |
| Backing up means copying the whole data directory yourself | **Automatic backups** (per slot: when to trigger, how many to keep), content-addressed and shared between slots — measured on a real device: 3 slots × 20 backups, 9.1 GB of data in **448 MB** |
| Deleting a save, a map or a blueprint is final | Deleting any of them **moves it to the transfer station** first, and it can be put back |
| The game does say why a mod failed to load (missing dependency / version too low / blacklisted), but it never checks whether **two mods override the same content** | A **conflict check** lists who overrides what, duplicate copies of one mod, unreadable packages and leftover switches |
| A crash leaves a log (which can be exported), but nothing says **which mod did it** | **Crash analysis** gives a verdict and its evidence; the report can be exported with that verdict, optionally **scrubbed first** |
| How much ore a map holds and where you can build can only be counted inside the game | The map page reports **ore / wall ore / buildable tiles / core zone**, and can export it as JSON / JSONL |
| A blueprint only reports its size and block count; which blocks are missing you find out by building it | The list names the **missing blocks** (and where it is unsure) |
| Exporting the whole data set takes the settings and login identities with it (the game does not separate them) | The **shareable package** strips login identities, server records and everything only useful to you |
| New features only arrive when the official build ships them | Plug-ins can add abilities to the manager (young: for now you install an APK by hand) |

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

The interface **follows your system language**: 简体中文 on a phone set to Chinese, English otherwise
(English and Simplified Chinese are the only translations so far). **Settings → Language** (the gear at
the top right) overrides that, including switching back to following the system; to translate it
yourself — even just a few lines — see **Translation file** below.

## What it can do

### Versions
- Scan games **already installed**, or **import** a copy of an APK
- One row per version: tap to launch, **Slot** to change its slot, long-press for details
- Details: source, size, checksum, raw version name, ABI, build number, save count
- Long-press a version → **Assets**: export the game package resources by type (sprites / sounds / music / maps / text / fonts and more); only the checked types go into the zip
- Two versions sharing a slot get a warning that their saves will overwrite each other
- **Packages that will not run are refused** — checked at import and again before launch
- **Continue** resumes the version you launched last

### Slots
- A slot is one complete set of data: saves / maps / blueprints / mods / settings
- Create it from the row; rename / clone / delete live in the **⋮** menu
- Cloning backs up the source first; a deleted slot takes its backups to the transfer station and can be put back

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
- Resource statistics: ore / wall ore / mineable floor / bonus floor
- Export statistics: `.json` for one map, `.jsonl` for several; full or brief; save or share
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
- Conflict check: who overrides what, duplicate copies, unreadable packages, leftover switches
- Search, filter and sort; import / export; delete; copy to another slot

### Plug-ins
- The entry is the icon at the top right (right of the gear); install an APK to add abilities
- Takes effect after a restart; the page offers **Restart the manager now**
- One row each: name · state · origin · what happens if it crashes
- Open its page, enable / disable, delete, read its info
- Can add an action row to almost every page (not the launch page or the plug-ins page)
- Can read slots and content, settings, logs, the transfer station and the version list, and open files
- A crash does not take the manager down: retry / roll back / quarantine, and the page explains it

### Transfer station
- Deleted things land here instead of disappearing
- Shows what it is, which slot it came from, and its size
- Put it back (source slot preselected), or delete / empty it for good
- Keeps at most 20 items (3 whole slots)

### Logs and settings
- Export logs unchanged, or scrubbed (paths / device / account details removed)
- Runtime log page: manager log, game log and crash reports in sections
- Crash analysis: names the mod with its evidence, and says so when it cannot tell
- Theme, language, translation file
- Settings: default slot, crash alerts, crash reports to keep, trash retention, automatic cleanup, storage used
- About page: version / data locations / GitHub repository, issues, GPL-3.0

## What is not there yet

- **Networking**: map downloads, a forum panel, update checks, manager self-updates — v1 is
  local-only (scan what is installed, import your own). The game itself is unaffected: multiplayer,
  the server list and in-game downloads work as usual
- **Plug-in distribution**: install an APK by hand; no store, no downloads, no update checks
- **Plug-in data**: computed stats and the in-game side are not exposed yet

## What is not planned

- **Google Play**: loading another APK as a plug-in does not fit its policies; sideload only
- **A built-in file manager**: imports and exports go through the system file picker (and this is not
  a general-purpose save manager)
- **A plug-in sandbox**: plug-ins share the manager's process; "what it touches" is a declaration,
  not a permission wall — only install what you trust
- **Bundling the game**: copyright stays with its authors — bring your own APK

---

To **build or change the code**, see **[docs/DEVELOPING.md](docs/DEVELOPING.md)** (that document is
currently in Chinese: the build chain, the `dev_*` direct entries, module boundaries and a map of
the documentation). The per-round implementation and on-device verification records live in
[`docs/history/`](docs/history/README.md).

## Licence

This project is released under the **GNU General Public License v3.0**; the full text is in
[LICENSE](LICENSE).
Copyright (C) 2026 GXFQE.
