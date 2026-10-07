English · [简体中文](README.zh.md)

# MDT Launcher — a multi-version launcher for Mindustry on Android

The official Android build of Mindustry **only lets you keep one version installed** — install a
second one and it overwrites the first. There is a single set of saves too, so switching versions
pushes the old progress aside. This launcher gives every version its own **save slot**: several
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

| Official Android build | With this launcher |
|---|---|
| One version at a time — a second install overwrites the first | Several versions installed at once (159.7 / 160.4 / MindustryX X37 …), none overwriting the others |
| Every version shares one set of saves, so switching versions replaces them | Each version is assigned to a **slot**; saves / maps / blueprints / mods are fully independent |
| Going back to an old version for an old save means shuffling files by hand | Switch versions with one tap; **Continue** picks up the version you launched last |
| "Why is this mod not taking effect?" — no way to find out | Every mod states whether it will load and why (type, dependencies, version gate, blacklist); conflicts between mods are listed one by one |
| Backups pile up and eat space | Backups are content-addressed and shared between slots — measured on a real device: 3 slots × 20 backups, 9.1 GB of data in **448 MB** |
| A mis-tap deletes a map, a mod or a whole slot for good | Deleting any of them **moves it to the transfer station** first, and it can be put back |

**How it works**: the official APK is loaded **as-is, as a plug-in** — no repacking, no
re-signing, no change to the game's package name. The game's own signature checks, save format and
mod ecosystem are untouched; the launcher's only job is to give each version its own data root and
point the game at that directory when it starts.

## Getting started

1. **Get a game APK yourself** — this repository **does not include** the game. Use the official
   Mindustry from the official channel, or a build such as MindustryX. (Heavily modified packages
   may not load.)
2. **Install the launcher** — for now you have to build it yourself (see
   [Building](docs/DEVELOPING.md#构建)). It requires **Android 8.0 (API 26)** or newer; during
   development it was verified on a real device running **Android 16 (API 36)**.
3. **Import and play**:
   - Open the launcher → **Import APK** and pick your APK (a copy is made into the app's private
     directory), or use **Add package name** to scan game versions already installed on the phone;
   - assign it a **slot** (use a different slot per version and the saves will not overwrite each
     other);
   - tap that version to launch. **Continue** goes straight back to the version you launched last.

The interface **follows your system language**: 简体中文 on a phone set to Chinese, English otherwise
(English and Simplified Chinese are the only translations so far). **Settings → Language** overrides
that, including switching back to following the system.

## What it can do

### Versions
- Scan Mindustry / MindustryX builds that are **already installed**, or **import** a copy of an APK
  (streamed copy, with a pre-check before it lands)
- One row per version; tap the row to launch, tap **Slot** on the right to pick its slot (that dialog
  also offers **See details**), and **long-press** the row for details: source path, size, checksum,
  raw version name, base version, ABI, build number — plus "this slot has N saves" and "last launch"
  (this is also where an imported copy is deleted)
- Assign a slot per version; several versions may share a slot **only when their version numbers
  match** — otherwise the row warns that they will overwrite each other's saves
- **Refuse packages that will not run**: a probe at import time and again before launch, checking
  four capabilities rather than a version number — the game's entry class is in the dex, the arc
  native library is present, the package's ABI matches the device, and the library's load-page
  alignment fits this device
- **Continue**: remembers the version you launched last and goes straight back to it
- Each row also says **how many saves that version's slot holds**

### Slots
- Every version points at a slot; a slot is one complete set of game data (`saves` / `maps` /
  `blueprints` / `mods` / settings)
- **Create / rename / clone / delete**. Cloning copies a slot into a new one (the source is backed up
  first, and the source itself is left alone); deleting a slot moves it — **and its backups** — to the
  transfer station, and it can be put back, under a new name if the old one is taken
- Open a slot and everything inside it is one row away: **Mods**, **Saves**, **Maps**,
  **Blueprints**, **Backup & restore**, **Whole-slot import & export**

### Saves and data
- **Save list with thumbnails**: every save shows a small picture of its map (that one needs a
  version assigned to the slot)
- **See each save**: which map it is, its size, wave, playtime, author and when it was saved;
  unreadable files are listed separately with the reason instead of silently disappearing
  (the save page is read-only — deleting a save is left to the game's own screen)
- **The save list can be searched and sorted**: search by save name; sort by name / **newest first** /
  size (this page has no "problems only" — the unreadable ones already have a row of their own)
- **Import / export `.msav`** saves, one file at a time
- **Backup list**: every backup on its own row (time / how many files / size / what the main save
  inside is), and one tap to **restore** or **delete that one**
- **Automatic backup, per slot**: enable it, set the minimum playtime (a shorter session is skipped)
  and the maximum number of backups (past that the oldest goes); anything you are about to overwrite
  is backed up first, whatever the setting says
- **Content-addressed store**: backups share identical files through a global object pool — measured
  on a real device, 3 slots × 20 backups holding 9.1 GB of data take **448 MB** on disk
- **Whole-slot export / import as one zip** (maps, blueprints, mods and settings included) — move
  everything to a new phone or another slot. Both a slot import and a "restore backup" let you pick
  **how the two sides merge**: **Update** (the new version wins), **Add missing** (what the slot has
  now wins, only missing files are added) or **Replace** (clear the slot first, then write the
  package in full) — and **a backup is taken first**, so a wrong choice can be undone
- **Automatic cleanup** of redundant data-directory leftovers (switchable; a manual "clean up once
  now" stays available when it is off)

### Maps
- Map list (cards with a **preview image**, from this slot / built into the game / shipped inside
  mods) plus a separate detail page
- **Map resource statistics**: ore / ore in walls / mineable floor / bonus floor — the top level
  reports only the "reachable" numbers (wall ore only reports a total, since it needs a wall drill),
  and the evidence (which blocks, how many tiles, and what is covering them) can be expanded
- **Import `.msav`**; **export from any source** — maps built into the game or into a mod live inside
  the APK, and can still be exported directly (the exported file is byte-for-byte identical to the
  source)
- **Turn a save into a map**: pick one of this slot's saves, give it a name, and optionally pick a
  **source map** (this slot / the 114 built into the game / from mods) to fill in the generator
  settings
- Deletion goes through the transfer station (moved aside, not hard-deleted); a name clash asks first
  and moves the old map there too
- **Search and sort**: by map name or file name; sort by name / **problems first** / size;
  "problems only" means the maps whose metadata cannot be read

### Blueprints
- **Thumbnails and a whole-blueprint preview**: the list draws the real sprites, and the detail page
  draws the blueprint pixel by pixel with the same artwork the game uses. Both need a version assigned
  to this slot, because the artwork is read out of that version APK (with no version, nothing is drawn
  rather than something made up)
- Blueprint list (this slot + **shipped inside mods**) with a separate detail page: blocks used,
  missing blocks, and a **technical details** section
- **Missing-block warning**: when the game meets a block it does not know, it silently drops that
  tile — the list row names how many kinds are missing, and the detail page lists them one by one
  (usually because the mod that brings them is not enabled)
- **The warning says so when it may be wrong**: mods that add their blocks in code or scripts are
  invisible to the launcher (it reads the block data shipped inside the mod package), so in a slot
  with such an enabled mod the wording becomes "N kinds were **not recognized** … this may be
  wrong", the detail page carries a visible *may be wrong* marker, and the reason sits one tap away
- **Import `.msch`** (verified before it is put in place; on a name clash you are asked first, and
  the old one moves to the transfer station); **export from any source**; deletion goes through the
  transfer station and can be put back
- The **technical details** section shows the evidence: the name table the file carries, how many
  tiles have a direction, any block names that had to be remapped, and the declared size against the
  tiles actually found
- **Search and sort**: by blueprint name or file name; sort by name / **problems first** / size;
  "problems only" means **missing blocks or an unreadable file** (the uncertain "may be wrong" ones
  count too)

### Mods
- Mods live inside a slot's page (main screen → **Saves & Backups** → a slot → **Mods**), and the page
  can switch to another slot without going back
- **Enable / disable** mods one by one or **in bulk** (this writes the game's own switch file: backed
  up first, atomic write, verified after writing, and refused while the game is running)
- **Every mod states whether it will load and why**: type (Java / scripts / resources), form (jar /
  zip / directory), dependencies — required, soft and circular — the version gate, the game's
  blacklist, whether it is switched on, and whether the last launch ended in a crash that would make
  the game skip all mods
- **Conflict check**: two mods implementing the same thing, duplicate copies of one mod, packages
  whose description file cannot be read, and leftover switches for mods that are gone — listed one by
  one, with who depends on whom
- **Search, filter and sort**: by name / problems first / size, "problems only", and by type; a mixed
  mod shows up under several types — that is a fact, not a duplicate
- Import mod packages; **copy this slot's mods to another slot**

### Transfer station
- Deleted maps, saves, **whole slots**, deleted **blueprints** and replaced mods are **moved here
  first** instead of disappearing
- The list shows what it is (map / save / mod / blueprint / whole slot / other), which slot it came
  from, its size, and when it arrived; you can **put it back** (the slot it came from is preselected)
- A deleted slot brings **its backups** along and they come back with it; if the name is taken you
  can **put it back under another name**
- You can also **delete for good** or **empty** it (both ask again and state the count and size); the
  station keeps at most 20 items (3 whole slots) and pushes out the oldest beyond that

### Logs and settings
- **Runtime log page** (Settings → Runtime log): the launcher's own log, the game log, and crash
  reports as separate sections, each with its age and size; the oldest crash reports can be deleted
  from there, and **Export all logs** writes everything into one file
- **Theme**: follow system / light / dark, and **Language**: follow system / English / 简体中文 —
  both switched inside the app, without touching system settings
- **Settings**: default slot for newly found versions, how many crash reports to keep,
  automatic cleanup (with a manual "clean up once now"), **storage used** (what each slot, the
  backups and the transfer station take, and how much the content-addressed store saves), and the
  transfer station

## What is not there yet

- **Only a pre-release, and the work is not finished** — the interface, the data format and the
  features can all change; there is no stable release yet. If you need stability, wait, or build it
  yourself
- **The launcher has no networking**: map downloads, a forum panel, update checks and launcher
  self-updates **are not implemented** (v1 is deliberately "local only: scan what is installed +
  import your own"). This does not limit the game itself — the internet permission is declared, so
  multiplayer, the server list and in-game downloads work as usual
- **Not on Google Play**: loading another APK as a plug-in does not fit its policies, so it can
  only be distributed by sideloading
- **No in-app file manager**: importing and exporting always goes through the system file picker; this
  is not a general-purpose save manager — deleting a save is still the game's job
- **This repository does not contain the game**: Mindustry and third-party builds such as
  MindustryX are copyright their respective authors, under their own licences

---

To **build or change the code**, see **[docs/DEVELOPING.md](docs/DEVELOPING.md)** (that document is
currently in Chinese: the build chain, the `dev_*` direct entries, module boundaries and a map of
the documentation). The per-round implementation and on-device verification records live in
[`docs/history/`](docs/history/README.md).

## Licence

This project is released under the **GNU General Public License v3.0**; the full text is in
[LICENSE](LICENSE).
Copyright (C) 2026 GXFQE.
