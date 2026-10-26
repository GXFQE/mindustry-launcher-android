# usagestats（官方插件，v30，第 255 轮）

游戏**前台时长**：探针自己记账，数据落在插件自己的 `data/`；宿主不写流水、不解析账本。
展示放在宿主自己的页面里（2026-10-26 用户：「尽量不要放插件页」）：

- **槽页**（`slot: section` + `page: slot`）：一张「游玩时长」卡 —— 本槽合计 + **按游戏版本**分列；
- **主界面摘要卡**：总时长一行（`plugin.json` 的 `playtimeTotal` 声明一个整数文件，宿主只排版）。

## 组成

| 文件 | 作用 |
|---|---|
| `PlaytimeProbe` | `inProcess` 探针（20 秒采样 + `onForeground`）：累计本局前台毫秒，写**账本**与总数 |
| `UsageStats.createView` | 槽页的 section（本槽合计 + 按版本分列）；同时是插件自述的默认入口（插件页长按「打开」看总计 + 按槽分列） |
| `core/Playtime` | 纯函数：账本解析 / 写回 / 求和 / 按槽分组 / 毫秒→时分（PC 侧可测） |
| `tools/Check.java` | PC 侧判据自检（`build.sh check`，16 条） |

## 数据（都在 `<插件>/data/`，`ProbeEnv.dataDir()`）

```
ledger.tsv   每行：<槽>\t<版本 key>\t<毫秒>\t<更新时间>
total.txt    一个整数毫秒（所有槽合计；主界面摘要卡读它）
```

- **两份都走「临时文件 + 改名」**（读到的永远是完整文件）；`total.txt` 由探针每次写账本时一起刷新。
- 账本按 **(槽, 版本 key)** 一行；版本 key 与 `PluginApi.versions()` 的 `extra["key"]` 同一个值
  （已装 = 包名；导入 = `import:<文件名>`）。槽 / 版本里的控制字符写入时换成空格。
- 旧版（第 254 轮）的"每局一个 `playtime.txt` 再求和"已废弃：读一次账本是 O(1)，主界面那一行
  才能在 UI 线程上读。

## 口径

- 只算**游戏 activity 在前台**的时间（主菜单 / 暂停 / 编辑器都算，切到别的 App 不算）；
- 进程被杀最多丢一次采样间隔（20 秒），**只少不多**；
- 不读存档的 `playtime`（那个是"存档内已保存时长"，回档/删档/复制都会乱）。

## 已知边界

- **槽改名**：账本里记的是槽名，改名后旧数字留在旧名下（总数仍对，那个槽的分项会断）；
- 槽删除后它的数字仍在总数里（"总游玩时长"不因删档回落）；
- 只有**装了本插件之后**启动的游戏才有数据（宿主的 `UsageLog` 已在第 252 轮删掉，没有旧流水可回填）。

## 构建 / 自检

```bash
ANDROID_HOME=D:/AndroidSDK bash plugins/usagestats/build.sh          # 产出 usagestats.apk
ANDROID_HOME=D:/AndroidSDK bash plugins/usagestats/build.sh check    # PC 侧判据自检
```

签名默认用 `debug.keystore`（与本地宿主同一把钥匙 ⇒ 宿主判「官方」）。
