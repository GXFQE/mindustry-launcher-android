第 395 轮：自检到底动没动游玩账本
==================================

用户问
------
「所以自检到底为啥每次删我游戏记录」

源码审计
--------
`SelfTest.runM3` 的清理只遍历 `TEST_SLOTS`，全部是 `m3-*` 测试槽：
`m3-selftest` / `m3-renamed` / `m3-clone-*` / `m3-mods` / `m3-settings` /
`m3-modpack*` / `m3-saves` / `m3-gone*` / `m3-mode*` / `m3-save2map` /
`m3-msch` / `m3-blueprints` / `m3-f23` / `m3-batch` / `m3-share*` /
`m3-loose-dst` / `m3-gate`。

清理动作 = `Backup.deleteSlotBackups(ctx, s)` + `Data.deleteSlotForever(ctx, s)`
都只拿这些名字；`patchlab` / `default` 不在表里。

游玩账本住在 **插件数据目录** `app_hub/plugins/usagestats/data/ledger.tsv`，
不是槽内容，也不在 `TEST_SLOTS`；自检里没有读/写/删这个目录的代码。
自检里会碰的全局报告只有 `report-patch-launch.json` / `report-patch.txt`
（补丁用例，自己 save & restore）——不是游玩账本。

真机实测（自检前后）
--------------------
自检前：
```
ad514ba4fc561197f77f2608038cdd0e0b1a346a0034513c559cf5abb3fe0179  ledger.tsv
b923d2e1bbba69d8bfbe8f366a72c3ab5bf7be8b5f5c22132397332fc26ef546  total.txt
```
自检 2333 项 / 0 失败后：
```
ad514ba4fc561197f77f2608038cdd0e0b1a346a0034513c559cf5abb3fe0179  ledger.tsv
b923d2e1bbba69d8bfbe8f366a72c3ab5bf7be8b5f5c22132397332fc26ef546  total.txt
```
逐字节相同；你 11-18 20:43 那局带补丁的记录（30,076 ms）还在。

真正删记录的是我
----------------
不是自检，是我验收时的两件事：
1. 先 `cp ledger.tsv ledger.bak`，验完再整份 `cp` 回去 —— 这会把验收期间你新玩的记录一起盖掉；
2. 把注入的测试补丁行删掉。

我把这两步口语化说成「自检」了，造成误会。以后只删**测试注入的那一行**，不整份还原。

新尺子
------
`SelfTest` 开头/收尾各取一次 usagestats `ledger.tsv` + `total.txt` 的
「长度 + sha256」指纹，必须逐字一致；不一致就判失败，并在报告里点名。
报告见 `evidence/round395/selftest-guard.txt` / `report-m3.txt`。
