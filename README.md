# PerPlayerLoot

PerPlayerLoot is a Paper plugin for Minecraft 1.21.11 that makes natural loot containers and selected natural item-frame loot per-player.

Chinese documentation: [README.zh-CN.md](README.zh-CN.md)

## Features

- Per-player loot containers: each player opens an isolated virtual inventory generated from the original loot table.
- Real container open/close animation is preserved.
- Hopper protection for managed natural loot containers.
- Per-player natural item-frame loot, such as Elytra in End Ships.
- Item-frame loot drops as a real item entity for the owner, while other players cannot see or pick it up.
- Real item-frame entity and its displayed item are never removed or cleared by the plugin.
- Optional protection for destroying natural loot containers and loot item frames.
- SQLite storage.
- External language files for server-owner translations.

## Requirements

- Paper 1.21.11
- Java 21
- ProtocolLib is optional but required for personal item-frame drops.

If ProtocolLib is not installed, the plugin still enables, but personal item-frame drops are disabled and a warning is logged.

## Installation

1. Build the plugin:
   ```bash
   gradle build
   ```
2. Copy `build/libs/PerPlayerLoot-1.0-SNAPSHOT.jar` to the server `plugins` folder.
3. Install ProtocolLib if you want personal item-frame drops.
4. Restart the server.

## Configuration

Default `config.yml`:

```yaml
config-version: 1

allow-destroy-natural-loot-containers: false
allow-merge-natural-loot-containers: false
allow-destroy-natural-loot-frames: false
allow-sneak-destroy-natural-loot-frames: false
protect-natural-loot-containers-from-hoppers: true

database:
  password: ""

personal-drop-timeout-seconds: 300
personal-drop-timeout-action: RECOVER

loot-frame-materials:
  - ELYTRA
```

Options:

- `allow-destroy-natural-loot-containers`
  - `true`: everyone can destroy natural loot containers.
  - `false`: only players with `perplayerloot.destroy.containers` can destroy them.

- `allow-merge-natural-loot-containers`
  - `true`: everyone can merge placed chests with natural loot containers.
  - `false`: only players with `perplayerloot.merge.containers` can merge them.

- `allow-destroy-natural-loot-frames`
  - `true`: everyone can destroy natural loot item frames without sneaking.
  - `false`: natural loot item frame destruction uses the sneak-destroy rule below.

- `allow-sneak-destroy-natural-loot-frames`
  - `true`: players can destroy natural loot item frames while sneaking.
  - `false`: only players with `perplayerloot.destroy.frames` can destroy them while sneaking.

- `protect-natural-loot-containers-from-hoppers`
  - Blocks hopper/container transfer involving managed natural loot containers.

- `database.password`
  - Optional SQLite password setting.
  - This is applied when the database connection opens, so changing it requires a server restart.

- `personal-drop-timeout-seconds`
  - Timeout for personal item-frame drops.

- `personal-drop-timeout-action`
  - `RECOVER`: recover the drop so it can be regenerated for the owner later.
  - `EXPIRE`: remove and mark it expired.

- `loot-frame-materials`
  - Non-player-managed item frames containing these materials are treated as natural loot frames.

The plugin automatically updates `config.yml`: missing keys are added and removed keys are cleaned up.

## Commands

```text
/perplayerloot reload
/ppl reload
```

Reloads `config.yml` and external language files.

## Permissions

```text
perplayerloot.reload
perplayerloot.destroy.containers
perplayerloot.merge.containers
perplayerloot.destroy.frames
```

- `perplayerloot.reload`: allows using `/perplayerloot reload` and `/ppl reload`.
- `perplayerloot.destroy.containers`: allows destroying natural loot containers when global destroy is disabled.
- `perplayerloot.merge.containers`: allows merging placed chests with natural loot containers when global merging is disabled.
- `perplayerloot.destroy.frames`: allows sneaking players to destroy natural loot item frames when both global frame destruction and ordinary sneak destruction are disabled.

## Language Files

On first startup, default language files are released to:

```text
plugins/PerPlayerLoot/lang/en_us.json
plugins/PerPlayerLoot/lang/zh_cn.json
```

The plugin loads all `*.json` files in `plugins/PerPlayerLoot/lang/`.

Server owners can add unsupported languages by creating files such as:

```text
plugins/PerPlayerLoot/lang/zh_tw.json
plugins/PerPlayerLoot/lang/ja_jp.json
```

Player messages are selected by the player's client language. If a language or key is missing, English is used as fallback.

## Data Storage

The plugin stores data in SQLite:

```text
plugins/PerPlayerLoot/loot-data.sqlite
```

If an old `loot-data.yml` exists, the plugin migrates it to SQLite once and renames it to:

```text
plugins/PerPlayerLoot/loot-data.yml.migrated
```

## Notes

- Natural loot containers are identified by their vanilla loot table.
- If a managed container is destroyed or loses its loot table, its stored per-player inventory data is cleaned immediately.
- Player-placed item frames, and item frames where players insert items, are not treated as natural loot frames.
- Personal item-frame drops use real item entities plus ProtocolLib visibility control.
- Other players cannot see, pick up, or affect another player's personal drop.
