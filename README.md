# PerPlayerLoot

PerPlayerLoot is a Paper plugin for Minecraft 26.2 that makes natural loot containers, archaeology blocks, and selected natural item-frame loot per-player.

Chinese documentation: [README.zh-CN.md](README.zh-CN.md)

## Features

- Per-player chests, barrels, and chest minecarts: each player opens an isolated virtual inventory generated from the original loot table.
- Real container open/close animation is preserved.
- Hopper protection for managed natural loot containers.
- Per-player natural item-frame loot, such as Elytra in End Ships.
- Item-frame loot drops as a real item entity for the owner, while other players cannot see or pick it up.
- Real item-frame entity and its displayed item are never removed or cleared by the plugin.
- Per-player suspicious sand and gravel with vanilla brushing animation, particles, and sounds while the block remains visually unbrushed.
- Optional protection for destroying natural loot containers, loot item frames, and suspicious blocks.
- SQLite storage.
- External language files for server-owner translations.

## Requirements

- Paper 26.2
- Java 25
- ProtocolLib is optional but required for personal item-frame and suspicious-block drops.

If ProtocolLib is not installed, the plugin still enables, but personal item-frame and suspicious-block drops are disabled and a warning is logged.

## Installation

1. Build the plugin:
   ```
   ./gradlew build
   ```
2. Copy the generated `build/libs/PerPlayerLoot-<version>.jar` to the server `plugins` folder.
3. Install ProtocolLib if you want personal item-frame or suspicious-block drops.
4. Restart the server.

## Configuration

Default `config.yml`:

```
config-version: 1

protect-natural-loot-containers-from-destruction: true
allow-destroy-natural-loot-containers: false
protect-natural-loot-containers-from-merging: true
allow-merge-natural-loot-containers: false
protect-natural-loot-containers-from-hoppers: true
protect-natural-loot-frames-from-destruction: true
allow-destroy-natural-loot-frames: false
allow-sneak-destroy-natural-loot-frames: false
protect-natural-loot-brushables-from-destruction: true
allow-destroy-natural-loot-brushables: false
allow-sneak-destroy-natural-loot-brushables: false

database:
  password: ""

personal-drop-timeout-seconds: 300
personal-drop-timeout-action: RECOVER

loot-frame-materials:
  - ELYTRA

advanced-logging:
  enabled: false
  max-file-size-mb: 10
  retained-files: 5
```

Options:

- `protect-natural-loot-containers-from-destruction`
  - Master switch for container destruction protection. When disabled, players and environmental damage can destroy containers; related `allow-*` settings and permissions are ignored.

- `allow-destroy-natural-loot-containers`
  - Only applies while container destruction protection is enabled.
  - `true`: everyone and environmental damage can destroy natural loot containers.
  - `false`: only players with `perplayerloot.destroy.containers` can destroy them; environmental damage is blocked.

- `protect-natural-loot-containers-from-merging`
  - Master switch for chest merge protection. Disabling it allows vanilla mixed double chests, which may consume the loot table and clear personal data.

- `allow-merge-natural-loot-containers`
  - Only applies while chest merge protection is enabled.
  - `true`: everyone can merge placed chests with natural loot containers.
  - `false`: only players with `perplayerloot.merge.containers` can merge them.

- `allow-destroy-natural-loot-frames`
  - Only applies while frame destruction protection is enabled.
  - `true`: everyone can destroy natural loot item frames without sneaking, and environmental damage is allowed.
  - `false`: natural loot item frame destruction uses the sneak-destroy rule below.

- `allow-sneak-destroy-natural-loot-frames`
  - Only applies while frame destruction protection is enabled.
  - `true`: players can destroy natural loot item frames while sneaking.
  - `false`: only players with `perplayerloot.destroy.frames` can destroy them while sneaking.

- `protect-natural-loot-frames-from-destruction`
  - Master switch for frame destruction protection. When disabled, attacks destroy the real frame instead of claiming personal loot.

- `protect-natural-loot-brushables-from-destruction`
  - Master switch for suspicious-block destruction protection. When disabled, players, explosions, and entity changes may remove them; normal brushing remains per-player.

- `allow-destroy-natural-loot-brushables`
  - Only applies while suspicious-block destruction protection is enabled.
  - `true`: everyone can directly destroy natural suspicious blocks, and explosions or other entity changes may remove them.
  - `false`: ordinary players use the sneak-destroy rule below; players with `perplayerloot.destroy.brushables` may still destroy them directly.

- `allow-sneak-destroy-natural-loot-brushables`
  - Only applies while suspicious-block destruction protection is enabled.
  - `true`: players can destroy natural suspicious blocks while sneaking.
  - `false`: sneaking does not bypass protection; `perplayerloot.destroy.brushables` still permits direct destruction without sneaking.

- `protect-natural-loot-containers-from-hoppers`
  - Blocks hopper/container transfer involving managed natural loot containers.

- `database.password`
  - Optional SQLite password setting.
  - This is applied when the database connection opens, so changing it requires a server restart.

- `personal-drop-timeout-seconds`
  - Timeout for personal item-frame and suspicious-block drops.

- `personal-drop-timeout-action`
  - `RECOVER`: recover the drop so it can be regenerated for the owner later.
  - `EXPIRE`: remove and mark it expired.
  - Any other value behaves as `RECOVER`.

- `loot-frame-materials`
  - Non-player-managed item frames containing these materials are treated as natural loot frames.

- `advanced-logging.enabled`
  - Writes detailed creation, opening, saving, claiming, pickup, recovery, timeout, and cleanup events to both the console and plugin log directory.

- `advanced-logging.max-file-size-mb`
  - Maximum size of each advanced log file, with a minimum of `1` MiB.

- `advanced-logging.retained-files`
  - Number of rotated files to retain, from `1` to `100`.

Advanced logs are stored at `plugins/PerPlayerLoot/logs/advanced-*.log` and may include player names and UUIDs, world coordinates, block or entity sources, loot tables, and item summaries.

The plugin automatically updates `config.yml`: missing keys are added and removed keys are cleaned up.
Default explanatory comments are preserved and updated. See the generated `config.yml` for the complete accepted values and consequences.

## Commands

```
/perplayerloot reload
/ppl reload
/perplayerloot cleanup containers
/ppl cleanup containers
```

`reload` reloads `config.yml` and external language files. `cleanup containers` removes orphaned container
records for currently loaded chunks and requires `perplayerloot.admin`.

## Permissions

```
perplayerloot.admin
perplayerloot.reload
perplayerloot.destroy.containers
perplayerloot.merge.containers
perplayerloot.destroy.frames
perplayerloot.destroy.brushables
```

- `perplayerloot.admin`: allows orphaned-container cleanup and includes reload access.
- `perplayerloot.reload`: allows using `/perplayerloot reload` and `/ppl reload`.
- `perplayerloot.destroy.containers`: allows player destruction while container protection is enabled and ordinary destruction is disabled.
- `perplayerloot.merge.containers`: allows merging while chest-merge protection is enabled and ordinary merging is disabled.
- `perplayerloot.destroy.frames`: allows sneaking players to destroy natural loot item frames when both global frame destruction and ordinary sneak destruction are disabled.
- `perplayerloot.destroy.brushables`: allows direct destruction of natural suspicious blocks without requiring the player to sneak.

## Language Files

On first startup, default language files are released to:

```
plugins/PerPlayerLoot/lang/en_us.json
plugins/PerPlayerLoot/lang/zh_cn.json
```

The plugin loads all `*.json` files in `plugins/PerPlayerLoot/lang/`.

Server owners can add unsupported languages by creating files such as:

```
plugins/PerPlayerLoot/lang/zh_tw.json
plugins/PerPlayerLoot/lang/ja_jp.json
```

Player messages are selected by the player's client language. If a language or key is missing, English is used as fallback.

## Data Storage

The plugin stores data in SQLite:

```
plugins/PerPlayerLoot/loot-data.sqlite
```

If an old `loot-data.yml` exists, the plugin migrates it to SQLite once and renames it to:

```
plugins/PerPlayerLoot/loot-data.yml.migrated
```

## Notes

- Natural loot containers are identified by their vanilla loot table.
- If a managed container is destroyed or loses its loot table, its stored per-player inventory data is cleaned immediately.
- Player-placed item frames, and item frames where players insert items, are not treated as natural loot frames.
- Personal item-frame drops use real item entities plus ProtocolLib visibility control.
- Natural suspicious blocks are never actually brushed away; progress is tracked per player and loot uses the same personal-drop mechanism.
- Other players cannot see, pick up, or affect another player's personal drop.
