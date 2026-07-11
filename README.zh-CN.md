# PerPlayerLoot

PerPlayerLoot 是一个面向 Minecraft 1.21.11 Paper 服务端的插件，用于让自然生成的战利品箱和指定自然展示框战利品按玩家独立领取。

英文文档：[README.md](README.md)

## 功能

- 玩家独立战利品箱：每个玩家打开的是根据原版 loot table 生成的独立虚拟容器。
- 保留真实箱子的开盖和关盖动画。
- 支持对受管理的自然战利品容器进行漏斗保护。
- 支持玩家独立的自然展示框战利品，例如末地船鞘翅。
- 展示框战利品会以真实掉落物形式生成，但只有所属玩家可见和拾取。
- 插件不会删除真实展示框实体，也不会清空真实展示物品。
- 可配置自然战利品箱和展示框的破坏权限。
- 使用 SQLite 存储数据。
- 支持外部语言文件，服主可以自行翻译。

## 需求

- Paper 1.21.11
- Java 21
- ProtocolLib 可选，但个人展示框掉落物功能需要 ProtocolLib。

如果未安装 ProtocolLib，插件仍会正常启用，但个人展示框掉落物功能会被禁用并在日志中输出警告。

## 安装

1. 构建插件：
   ```bash
   gradle build
   ```
2. 将 `build/libs/PerPlayerLoot-1.0-SNAPSHOT.jar` 放入服务端 `plugins` 文件夹。
3. 如果需要个人展示框掉落物功能，请安装 ProtocolLib。
4. 重启服务端。

## 配置

默认 `config.yml`：

```yaml
config-version: 1

allow-destroy-natural-loot-containers: false
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

配置项说明：

- `allow-destroy-natural-loot-containers`
  - `true`：所有玩家都可以破坏自然战利品容器。
  - `false`：只有拥有 `perplayerloot.destroy.containers` 权限的玩家可以破坏。

- `allow-destroy-natural-loot-frames`
  - `true`：所有玩家都可以不蹲下直接破坏自然战利品展示框。
  - `false`：自然战利品展示框破坏行为使用下面的蹲下破坏规则。

- `allow-sneak-destroy-natural-loot-frames`
  - `true`：玩家蹲下时可以破坏自然战利品展示框。
  - `false`：只有拥有 `perplayerloot.destroy.frames` 权限的玩家可以蹲下破坏。

- `protect-natural-loot-containers-from-hoppers`
  - 阻止漏斗或其他容器搬运受管理自然战利品容器中的物品。

- `database.password`
  - SQLite 密码配置，可留空。
  - 该配置在数据库连接打开时应用，修改后需要重启服务端。

- `personal-drop-timeout-seconds`
  - 个人展示框掉落物的超时时间。

- `personal-drop-timeout-action`
  - `RECOVER`：回收掉落物，之后可为所属玩家重新生成。
  - `EXPIRE`：移除并标记为过期。

- `loot-frame-materials`
  - 未被标记为玩家管理、且包含这些物品的展示框会被视为自然战利品展示框。

插件会自动更新 `config.yml`：缺失的配置项会自动补全，已删除的旧配置项会被移除。

## 命令

```text
/perplayerloot reload
/ppl reload
```

重载 `config.yml` 和外部语言文件。

## 权限

```text
perplayerloot.reload
perplayerloot.destroy.containers
perplayerloot.destroy.frames
```

- `perplayerloot.reload`：允许使用 `/perplayerloot reload` 和 `/ppl reload`。
- `perplayerloot.destroy.containers`：当全局破坏开关关闭时，允许破坏自然战利品容器。
- `perplayerloot.destroy.frames`：当全局展示框破坏和普通玩家蹲下破坏都关闭时，允许蹲下破坏自然战利品展示框。

## 语言文件

首次启动时，默认语言文件会释放到：

```text
plugins/PerPlayerLoot/lang/en_us.json
plugins/PerPlayerLoot/lang/zh_cn.json
```

插件会加载 `plugins/PerPlayerLoot/lang/` 下的所有 `*.json` 文件。

服主可以通过创建类似下面的文件来添加未内置支持的语言：

```text
plugins/PerPlayerLoot/lang/zh_tw.json
plugins/PerPlayerLoot/lang/ja_jp.json
```

玩家提示会根据客户端语言选择。若对应语言或文本键不存在，则默认回退到英语。

## 数据存储

插件使用 SQLite 存储数据：

```text
plugins/PerPlayerLoot/loot-data.sqlite
```

如果存在旧的 `loot-data.yml`，插件会自动迁移到 SQLite，并将旧文件重命名为：

```text
plugins/PerPlayerLoot/loot-data.yml.migrated
```

## 注意事项

- 自然战利品容器通过原版 loot table 识别。
- 受管理容器被破坏或丢失 loot table 属性时，对应玩家数据会立即清理。
- 玩家放置的展示框，以及玩家往展示框中放入物品后的展示框，不会被视为自然战利品展示框。
- 个人展示框掉落物使用真实掉落物实体，并通过 ProtocolLib 控制可见性。
- 其他玩家不能看到、拾取或影响不属于自己的个人掉落物。
