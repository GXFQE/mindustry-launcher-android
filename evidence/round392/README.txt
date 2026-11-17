第 392 轮证据：主界面 / 版本页也统一说「半改端」
================================================

用户
----
「咱先统一下说法吧，主页又是原端+补丁」——槽页游玩时长已经写「半改端」，
主界面行却还是 `v1607 原端 160.7` + 副标题 `补丁 1`。

修法
----
- `Versions.typeFor(ctx, entry, patchCount)`：**唯一判据**，有补丁 ⇒ `半改端`。
- `Entry.displayName(..., typeOverride)` 允许覆盖类型词；主界面 `buildTitle`、
  版本页标题都走 `typeFor`。
- 新增宿主字符串 `version_type_half`：半改端 / Half-modified。
- 副标题仍保留 `补丁 N`（说明启用了几条），但标题不再同时出现「原端」。

真机（`main-half.png`）
------------------------
```
v1607  原端   160.7   [default]     （没补丁的启动项）
...
v1607  半改端 160.7   [patchlab]   ← 导入 · 备份 1 份 · 补丁 1
```
同一个版本的两行：default 原端、patchlab 半改端；不再「原端 + 补丁」叠着。

验证
----
- 自检 **2323 / 0**（新增 typeFor / displayName 两条）。
- 产品构建 OK。
- 主界面截图 `main-half.png`；槽页行名见 `evidence/round390/slot-row.png`。
