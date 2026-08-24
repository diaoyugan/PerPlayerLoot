# PerPlayerLoot

PerPlayerLoot 是一个面向 Minecraft 26.2 Paper 服务端的插件，用于让自然生成的战利品容器、考古方块和指定自然展示框战利品按玩家独立领取。

英文文档：[README.md](README.md)

## 功能

- 玩家独立战利品箱、桶和箱子矿车：每个玩家打开的是根据原版 loot table 生成的独立虚拟容器。
- 保留真实箱子的开盖和关盖动画。
- 支持对受管理的自然战利品容器进行漏斗保护。
- 支持玩家独立的自然展示框战利品，例如末地船鞘翅。
- 展示框战利品会以真实掉落物形式生成，但只有所属玩家可见和拾取。
- 插件不会删除真实展示框实体，也不会清空真实展示物品。
- 可疑的沙子和可疑的沙砾按玩家独立刷取；原版刷取动画、粒子和声音保留，方块始终显示为未刷取状态。
- 可配置自然战利品容器、展示框和可疑方块的破坏权限。
- 使用 SQLite 存储数据。
- 支持外部语言文件，服主可以自行翻译。

## 需求

- Paper 26.2
- Java 25
- ProtocolLib 可选，但个人展示框和可疑方块掉落物功能需要 ProtocolLib。

如果未安装 ProtocolLib，插件仍会正常启用，但个人展示框和可疑方块掉落物功能会被禁用并在日志中输出警告。

## 安装

1. 构建插件：
   ```
   ./gradlew build
   ```
2. 将生成的 `build/libs/PerPlayerLoot-<version>.jar` 放入服务端 `plugins` 文件夹。
3. 如果需要个人展示框或可疑方块掉落物功能，请安装 ProtocolLib。
4. 重启服务端。

## 配置

默认 `config.yml`：

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

配置项说明：

- `protect-natural-loot-containers-from-destruction`
  - 容器破坏保护的总开关。关闭后所有玩家和环境都能破坏，`allow-destroy-*` 与相关权限不再生效。

- `allow-destroy-natural-loot-containers`
  - 仅在容器破坏保护开启时生效。
  - `true`：所有玩家和环境伤害都可以破坏自然战利品容器。
  - `false`：只有拥有 `perplayerloot.destroy.containers` 权限的玩家可以破坏，环境伤害会被阻止。

- `protect-natural-loot-containers-from-merging`
  - 箱子拼接保护的总开关。关闭后允许原版混合大箱子，并可能消耗 loot table、清除独立数据。

- `allow-merge-natural-loot-containers`
  - 仅在箱子拼接保护开启时生效。
  - `true`：所有玩家都可以将放置的箱子与自然战利品箱拼接。
  - `false`：只有拥有 `perplayerloot.merge.containers` 权限的玩家可以进行拼接。

- `allow-destroy-natural-loot-frames`
  - 仅在展示框破坏保护开启时生效。
  - `true`：所有玩家都可以不蹲下直接破坏自然战利品展示框，同时允许环境伤害。
  - `false`：自然战利品展示框破坏行为使用下面的蹲下破坏规则。

- `allow-sneak-destroy-natural-loot-frames`
  - 仅在展示框破坏保护开启时生效。
  - `true`：玩家蹲下时可以破坏自然战利品展示框。
  - `false`：只有拥有 `perplayerloot.destroy.frames` 权限的玩家可以蹲下破坏。

- `protect-natural-loot-frames-from-destruction`
  - 展示框破坏保护的总开关。关闭后攻击会破坏真实展示框，不再触发个人领取。

- `protect-natural-loot-brushables-from-destruction`
  - 可疑方块破坏保护的总开关。关闭后玩家、爆炸及实体变化都能移除方块，但正常刷取仍为玩家独立。

- `allow-destroy-natural-loot-brushables`
  - 仅在可疑方块破坏保护开启时生效。
  - `true`：所有玩家都可以直接破坏自然生成的可疑方块，爆炸和其他实体变化也可以移除它们。
  - `false`：普通玩家使用下面的蹲下破坏规则；拥有 `perplayerloot.destroy.brushables` 权限的玩家仍可直接破坏。

- `allow-sneak-destroy-natural-loot-brushables`
  - 仅在可疑方块破坏保护开启时生效。
  - `true`：玩家蹲下时可以破坏自然生成的可疑方块。
  - `false`：蹲下不能绕过保护；`perplayerloot.destroy.brushables` 权限仍允许玩家无需蹲下直接破坏。

- `protect-natural-loot-containers-from-hoppers`
  - 阻止漏斗或其他容器搬运受管理自然战利品容器中的物品。

- `database.password`
  - SQLite 密码配置，可留空。
  - 该配置在数据库连接打开时应用，修改后需要重启服务端。

- `personal-drop-timeout-seconds`
  - 个人展示框和可疑方块掉落物的超时时间。

- `personal-drop-timeout-action`
  - `RECOVER`：回收掉落物，之后可为所属玩家重新生成。
  - `EXPIRE`：移除并标记为过期。
  - 其他值均按 `RECOVER` 处理。

- `loot-frame-materials`
  - 未被标记为玩家管理、且包含这些物品的展示框会被视为自然战利品展示框。

- `advanced-logging.enabled`
  - 开启后将独立战利品的创建、打开、保存、领取、拾取、回收、超时和数据清理详情同时输出到控制台与插件日志目录。

- `advanced-logging.max-file-size-mb`
  - 每个高级日志文件的大小上限，最小为 `1` MiB。

- `advanced-logging.retained-files`
  - 轮转日志保留数量，可填写 `1` 至 `100`。

高级日志文件位于 `plugins/PerPlayerLoot/logs/advanced-*.log`。记录可能包含玩家名称、UUID、世界坐标、方块或实体来源、loot table 与物品摘要。

插件会自动更新 `config.yml`：缺失的配置项会自动补全，已删除的旧配置项会被移除。
默认配置中的说明注释也会被保留和更新；每项的完整取值与后果请直接查看生成的 `config.yml`。

## 命令

```
/perplayerloot reload
/ppl reload
/perplayerloot cleanup containers
/ppl cleanup containers
```

`reload` 用于重载 `config.yml` 和外部语言文件。`cleanup containers` 用于清理当前已加载区块中的孤立容器记录，
并要求 `perplayerloot.admin` 权限。

## 权限

```
perplayerloot.admin
perplayerloot.reload
perplayerloot.destroy.containers
perplayerloot.merge.containers
perplayerloot.destroy.frames
perplayerloot.destroy.brushables
```

- `perplayerloot.admin`：允许清理孤立容器记录，并包含重载权限。
- `perplayerloot.reload`：允许使用 `/perplayerloot reload` 和 `/ppl reload`。
- `perplayerloot.destroy.containers`：容器保护开启且普通破坏关闭时，允许玩家破坏自然战利品容器。
- `perplayerloot.merge.containers`：箱子拼接保护开启且普通拼接关闭时，允许进行拼接。
- `perplayerloot.destroy.frames`：当全局展示框破坏和普通玩家蹲下破坏都关闭时，允许蹲下破坏自然战利品展示框。
- `perplayerloot.destroy.brushables`：允许玩家无需蹲下直接破坏自然可疑方块。

## 语言文件

首次启动时，默认语言文件会释放到：

```
plugins/PerPlayerLoot/lang/en_us.json
plugins/PerPlayerLoot/lang/zh_cn.json
```

插件会加载 `plugins/PerPlayerLoot/lang/` 下的所有 `*.json` 文件。

服主可以通过创建类似下面的文件来添加未内置支持的语言：

```
plugins/PerPlayerLoot/lang/zh_tw.json
plugins/PerPlayerLoot/lang/ja_jp.json
```

玩家提示会根据客户端语言选择。若对应语言或文本键不存在，则默认回退到英语。

## 数据存储

插件使用 SQLite 存储数据：

```
plugins/PerPlayerLoot/loot-data.sqlite
```

如果存在旧的 `loot-data.yml`，插件会自动迁移到 SQLite，并将旧文件重命名为：

```
plugins/PerPlayerLoot/loot-data.yml.migrated
```

## 注意事项

- 自然战利品容器通过原版 loot table 识别。
- 受管理容器被破坏或丢失 loot table 属性时，对应玩家数据会立即清理。
- 玩家放置的展示框，以及玩家往展示框中放入物品后的展示框，不会被视为自然战利品展示框。
- 个人展示框掉落物使用真实掉落物实体，并通过 ProtocolLib 控制可见性。
- 自然可疑方块不会被实际刷掉；插件按玩家记录刷取进度，并通过同一套个人掉落物机制发放战利品。
- 其他玩家不能看到、拾取或影响不属于自己的个人掉落物。
