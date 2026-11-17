第 387 轮证据：宿主升级后重置接口拒绝（重新试被拒的那一版）
================================================================

用户
----
「记得更新宿主以后重置（）」→ 随后贴出第 386 轮机制截图：「我说你的这个机制」。
⇒ 要重置的是**这套接口回退机制自己的状态**，不是只重置更新检查时间戳。

改动
----
- `Plugins.prepareApiRejects(base, d)`：**在 `promotePending` 之前**读 `api-reject.txt`：
  · 宿主接口确实升级了（`PluginHost.API > 记录里的 host`）且已经够那一版要求的接口
    （`API >= required`）⇒ 把当初被拒的版本 `was` **放回 pending**，本次启动重新判一次；
  · 记录的现场被用户改过（active 不再是当初回退到的那版 / 停用记录下 active 已被填上 /
    用户已经排了别的 pending）⇒ **不动**；
  · 宿主没升级 ⇒ **保留**记录，警告卡继续挂着，不每程重试；
  · 被拒版本的文件已经不在了 ⇒ 丢掉这条过时记录。
- `rollbackTarget` 不再只认 `known.txt`：`known` 正好是被拒那版（例如宿主降级）时，
  会在**已装版本**里再找一份 `plugin.json api <= 当前宿主` 的（取文件最新的一份）回退。
- 纯函数 `Plugins.apiRejectRetryable(hostApi, rejectHost, required)` + 自检 1 条。

真机夹具（一次性 id `zzapitest`）
--------------------------------
- `old1.apk` = usagestats APK（有效、meta api=44）；
  `new999.apk` = usagestats APK 改 `plugin.json`：`api=44`、`entry=io.mdt.apitest.Missing`
  （**故意不存在**，用来分辨"有没有重试"：重试后它会失败、active 被摘掉；不重试则 active 停在 old1）。
- 写入：
  ```
  active.txt:  ... zzapitest=old1
  known.txt:   ... zzapitest=old1
  api-reject.txt:
  zzapitest	new999	old1	44	43	rollback      ← 记的是旧宿主 43
  ```
- 冷启动后：
  ```
  active.txt 里 zzapitest 消失（先被 promote 成 new999，注入时 Missing 类失败、被摘掉）
  pending.txt 为空
  api-reject.txt 为空（这条已被"宿主升级"消费掉）
  report-plugin-boot.txt:
    active = ... zzapitest=new999
    - zzapitest=new999 ... FAILED ClassNotFoundException: Didn't find class "io.mdt.apitest.Missing"
  ```
  ⇒ 证明 **宿主 44 > 记录 host 43 时确实重新试了 new999**，不是把记录静默清掉。
- 收尾：active/known/pending 已按备份还原、`zzapitest` 目录与记录删除、临时文件清理。

自检
----
`selftest-387.txt` = **2319 / 0**（第 386 轮 2318；+1 = `apiRejectRetryable` 闸门）。
