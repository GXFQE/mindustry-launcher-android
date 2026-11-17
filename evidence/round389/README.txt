第 389 轮证据：补丁注入成功、启动记录却是空（游玩时长不分的真因）
==================================================================

现象
----
`patchlab` 槽的 `v1607` 已在补丁页启用 `mdtx-1607`，游戏也显示走
`MindustryX (原版 + 补丁)`，但：

- `report-launch.txt` 写着：
```
补丁数 = 1
补丁[1] MindustryX (原版 + 补丁) 注入 @1 elements 2→3 (18 ms) | makeDexElements(4 args) -> 1 elements, suppressed=0
补丁注入完成 1/1
```
- `hub/report-patch-launch.json` 的 `patches` 却是 `[]`：

```json
{"v":2,"records":{"import:v1607.apk\u0000patchlab":{
  "at":...,"slot":"patchlab","ikey":"import:v1607.apk\u0000patchlab",
  "handled":false,"patches":[]}}}
```

⇒ `usagestats` 拿不到补丁身份，只能按本体记；即第 388 轮的补丁统计一直没生效。

根因
----
`GameSlot` 把 `Patches.Loaded` 搬成 `Injector.Patch` 时**漏了 `ref`**：

```java
Injector.Patch p = new Injector.Patch();
p.dex = ...; p.assets = ...; p.label = ...;
plan.patches.add(p);          // p.ref 还是 ""
```

注入只读 `dex/assets/label`，所以补丁确实打上了；但启动记录只收
`ap.ref` 非空的补丁（`Patches.writeLaunchRecord` 里 `if (l.ref.isEmpty()) continue;`）
⇒ `patches: []`。崩溃归因 / 自动停用补丁也读同一份记录，同一条线受影响。

修法
----
`GameSlot` 建 `Injector.Patch` 时补：

```java
p.ref = l.record.ref();
```

（`Injector.Patch.ref` 本来就有这个字段，只是没赋值。）

验证
----
- 修复后同一实例启动，`report-patch-launch.json`：
```json
"patches":[{"ref":"mdtx-1607@2026.10.10.B509",
 "label":"MindustryX (原版 + 补丁)",
 "dex":"/data/user/0/io.mdt.launcher/app_hub/patches/...dex"}]
```
- 退出一局后，`ledger.tsv` 实际出现两行：
```
patchlab  import:v1607.apk  160.7  381111  ...             （本体，历史）
patchlab  import:v1607.apk  160.7   19123  ...  mdtx-1607@2026.10.10.B509  MindustryX (原版 + 补丁)
```
  ⇒ 本体与带补丁的局分开了。
- 该 19 秒是测试局，验收后已从账本里删掉、`total` 重新求和还原到 6740573。
- 自检 **2321 / 0**；产品构建 OK。
