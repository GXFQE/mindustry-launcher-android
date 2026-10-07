English · [简体中文](README.zh.md)

# MDT Launcher — a multi-version launcher for Mindustry on Android

The official Android build of Mindustry **only lets you keep one version installed** — install a
second one and it overwrites the first. There is a single set of saves too, so switching versions
pushes the old progress aside. This launcher gives every version its own **save slot**: several
versions installed side by side, each with separate **saves / maps / mods**.

> ⚠️ **Pre-release only — the feature set is not finished.** The APK is on
> [Releases](https://github.com/GXFQE/mindustry-launcher-android/releases) (the one marked
> Pre-release); you can also build it yourself — see [Building](docs/DEVELOPING.md#构建)
> *(in Chinese for now)*. Pre-release means the interface, the data format and
> the features can all still change — **do not treat it as a stable release.**
>
> The feature list further down describes the **current source code**; the newest pre-release APK can
> lag a little behind it.

## What it solves

| Official Android build | With this launcher |
|---|---|
| One version at a time — a second install overwrites the first | Several versions installed at once (159.7 / 160.4 / MindustryX X37 …), none overwriting the others |
| Every version shares one set of saves, so switching versions replaces them | Each version is assigned to a **slot**; saves / maps / mods are fully independent |
| Going back to an old version for an old save means shuffling files by hand | Switch versions with one tap; **Continue** picks up the version you played last |
| Exporting or importing a save means opening the game and going through its own save menu, one save at a time | Export / import saves right in the launcher (the game does not have to be started); pack a whole slot as a zip |
| "Why is this mod not taking effect?" — no way to find out | Every mod states whether it will load and why; conflicts between mods are pointed out |
| Backups pile up and eat space | Backups are deduplicated by content (measured on a real device: 74.4% saved) |

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
     directory), or use **Add package name** to scan games already installed on the phone;
   - assign it a **slot** (use a different slot per version and the saves will not overwrite each
     other);
   - tap that version to launch. **Continue** goes straight back to the version you played last.

## What it can do

### Versions
- Scan Mindustry / MindustryX builds that are **already installed**, or **import** a copy of an APK
  (streamed copy, with a pre-check before it lands)
- One row per version; tap the row to launch, tap **Slot** on the right to pick its slot (that dialog
  also offers **See details**), and **long-press** the row for details (source path, size, MD5,
  original version name, architecture / build number — this is also where an imported copy is deleted)
- Assign a slot per version; several versions may share a slot **only when their version numbers
  match** — otherwise the launcher warns about a conflict
- **Refuse packages that will not run**: two checks (ABI / dex / version thresholds), at import
  time and again before launch, so you do not find out only after getting in
- **Continue**: remembers the version you played last and goes straight back to it
- Each row also says **how many saves that version's slot holds**; long-press for the details and
  you also get "this slot has N saves" and "last launch"

### Saves and data
- **Save slots**: create / rename / clone / delete (a deleted slot goes to the **transfer station**
  along with its backups, and can be put back); each slot has its own saves, maps and mods
- **Thumbnails in the list**: every save shows a small picture of its map, drawn the same way (that one
  also needs a version assigned to the slot)
- **See each save**: tap one to see which map it is, its size, wave, playtime and when it was saved
  (read-only, with an "export this save" button right there)
- **Backup list**: every backup listed on its own (time / how many files / size / what the main save
  inside is), and one tap to **restore** or **delete that one**
- **Automatic backup**: whether to back up is decided by how long the session ran; past the limit
  the oldest is dropped; can be switched on or off per slot
- **Content-addressed store for saves**: backups are deduplicated by content — measured on a real
  device, 4 backups of 99.5 MB → 25.5 MB (74.4% saved)
- **Export / import**: a single `.msav` save, or **a whole slot as one zip** (including maps, mods
  and settings) — move everything to a new phone or another slot. Both a slot import and a
  "restore backup" let you pick **how the two sides merge**: **Update** (the new version wins),
  **Add missing** (what the slot has now wins, only missing files are added) or **Replace**
  (clear the slot first, then write the package in full) — and **a backup is taken first**,
  so a wrong choice can be undone
- **Data directory check**: redundant directories are found and cleaned up automatically

### Maps
- Map list (cards with a **preview image**) plus a separate detail page
- **Map resource statistics**: ore / ore in walls / mineable floor / bonus floor — the top level
  reports only the "reachable" numbers, and the evidence (which blocks, how many tiles) can be
  expanded
- **Import `.msav`**; **export from any source** — maps built into the game or into a mod live
  inside the APK, and can still be exported directly (the exported file is byte-for-byte identical
  to the source)
- Add and remove: imports are parsed and verified first; deletion goes through the transfer station
  (moved aside, not hard-deleted)

### Blueprints
- **Thumbnails and a whole-blueprint preview**: the list draws the real sprites, and the detail page
  draws the blueprint pixel by pixel with the same artwork the game uses. Both need a version assigned
  to this slot, because the artwork is read out of that version APK
- Blueprint list (this slot + **shipped inside mods**) with a separate detail page: which blocks are
  used and how many tiles each takes
- **Missing-block warning**: when the game meets a block it does not know, it silently drops that
  tile — the list row names how many kinds are missing, and the detail page lists them one by one
  (usually because the mod that brings them is not enabled)
- **The warning says so when it may be wrong**: mods that add their blocks in code or scripts are
  invisible to the launcher (it reads the block data shipped inside the mod package), so in a slot
  with such an enabled mod the wording becomes "N kinds were **not recognized** … this may be
  wrong", the detail page carries a visible *may be wrong* marker, and the reason sits one tap away
- **Import `.msch`** (verified before it is put in place; on a name clash you are asked first, and
  the old one moves to the transfer station); **export from any source**
- Deletion goes through the transfer station (moved aside, not hard-deleted) and can be put back
- The **technical details** section shows the evidence: the name table the file carries, how many
  tiles have a direction, and any block names that had to be remapped

### Mods
- Scan the mods in a slot and enable or disable them one by one or **in bulk** (this writes the
  game's own switches: back up first, atomic write, verify after writing)
- **Conflict check**: points out when two mods implement the same thing
- Import mod packages; **copy this slot's mods to another slot**
- Every mod states **whether it can load and why**: type (Java / JS / data mod), dependencies,
  version thresholds, multiplayer support
- The list can **filter by type** (has Java code / has scripts / has resources); a mixed mod shows
  up under both — that is a fact, not a duplicate

### Transfer station
- Deleted maps, **deleted whole slots**, replaced mods or saves, and deleted **blueprints** are
  **moved here first** instead of disappearing
- The list shows what it is, which slot it came from, its size, and when it arrived; you can **put
  it back** (the slot it came from is preselected)
- A deleted slot brings **its backups** along and they come back with it; if the name is taken you
  can **put it back under another name**
- You can also **delete for good** or **empty** it (both ask again and state the count and size);
  the station keeps at most 20 items (3 whole slots) and pushes out the oldest beyond that

### Other
- **Runtime log page**: launcher log + game log + crash stack; crashes are written to disk
  automatically and can be exported as a single file
- **Theme**: follow system / light / dark (switched inside the app, without touching system
  settings)
- **Language**: follow system / English / 简体中文 (switched inside the app, without touching system
  settings)
- **Settings**: default slot, how many log files to keep, **storage used** (how much each slot,
  the backups and the transfer station take, and how much the backups save), the transfer station

## What is not there yet

- **Only a pre-release, and the work is not finished** — the interface, the data format and the
  features can all change; there is no stable release yet. If you need stability, wait, or build it
  yourself
- **No networking at all**: map downloads, a forum panel, update checks and launcher self-updates
  **are not implemented** (v1 is deliberately "local only: scan what is installed + import your
  own")
- **Not on Google Play**: loading another APK as a plug-in does not fit its policies, so it can
  only be distributed by sideloading
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
