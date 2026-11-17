第 388 轮证据：游玩时长把「本体 + 补丁」单独算
================================================

用户
----
「游玩时长需要解决一个统计问题 游戏本体+补丁≠游戏本体（应该算是半个改端）」

宿主
----
- `game-exit` 新增 `patch` / `patchLabel` / `patchCount`：
  真源 = 这一局**实际注入**的补丁（`Patches.readLaunchInfo(ctx, slot)` 读
  `report-patch-launch.json`），不是"当前启用了哪些"。
- refs 排序拼成身份（`a@1,b@2`）；显示名按 ref 顺序拼；`patchCount`。
- 启动记录时间早于这一局开始（+5 秒容差）⇒ 当上一局，**不拿来充数**。
  纯函数 `PluginEvents.patchFields` 由自检钉住（排序 / 显示名 / 旧记录）。

插件 usagestats v7 → v8
------------------------
- `core/Playtime`：账本 8 列 → 10 列（末尾加 `patch` / `patchLabel`）；
  行身份 = 槽 + key + tag + **patch**（本体与带补丁的局永不合并）；旧 8 列照读当本体。
- `UsageStats`：从 `game-exit` 取补丁字段写账本；槽页行名 =
  `版本 · 本体+补丁（补丁名）`，补丁名快照丢了写「半改端」；note 改成"分开算"。
- `plugin.json` 的 version = 8；接口仍 44（只是往 v44 的 `game-exit` 加键，未发版）。

判据
----
- `plugins/usagestats/build.sh check`（`usagestats-check.txt`）：覆盖 10 列 / 旧 8 列 /
  本体与补丁分行 / 同补丁覆盖 / 换补丁第三条 / 老账本不算补丁 → **CHECK OK**。
- 自检 `selftest-388.txt` = **2321 / 0**（新增 2 条 `patchFields`）。
- 真机：把测试行塞进 `ledger.tsv`（先备份），槽页展开实际显示：
```
游玩时长
本槽累计：29 分钟
v1607  原端  160.7 · 本体+补丁（MDTX 160.7 补丁）
12 分钟
official1604.apk
7 分钟
v160  原端  160
5 分钟
v1607  原端  160.7
2 分钟
收起
只算游戏在前台的时间；进程被杀最多丢 20 秒。本体与带补丁的局分开算。
```
  ⇒ 同一个 `v1607` 版本，带补丁 12 分钟 / 本体 2 分钟，**没有并成一行**。
  收尾 `ledger.tsv` / `total.txt` 已按备份还原。
