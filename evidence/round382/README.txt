第 382 轮（2026-11-15）：单实例阶段 0（0.1~0.5）真机证据
==========================================================

设备：HONOR ELP-AN00 / Android 16（API 36），序列号 AJYMUT4522007370
构建：DEBUGGABLE=true SUFFIX=-dev（`mdt-launcher-dev.apk`，17:1x）
路线：docs/plans/2026-11-14-多实例实施路线.md 阶段 0（用户：「先处理单实例的问题吧」）

一、自检
--------
selftest-382.txt：**2304 项通过 / 0 失败**（第 381 轮基线 2279，本轮 +25 条：
  HubTmp 6 · Natives 8 · CacheEpoch 7 · CacheClean 3 · Storage tmp 1）。
新增段全绿：
  · 第 382 轮 · HUB 临时件搬出系统 cache（阶段 0.1）①~⑥
  · 第 382 轮 · native 按版本目录 + 原子发布（阶段 0.3）①~⑧
  · 第 382 轮 · 游戏 cache 身份 + 整目录清（阶段 0.2）①~⑦
  · 第 382 轮 · 清游戏缓存（阶段 0.4）①~③
  · 存储占用：HUB 临时文件单独一行（阶段 0.5）

二、0.1 落点（真机）
--------------------
`run-as io.mdt.launcher find app_hub/tmp -maxdepth 2`：
  app_hub/tmp/export、app_hub/tmp/batch、app_hub/tmp/share
系统 cache 里不再出现 HUB 的 export / batch / patch-import / plugin-import / lang / share。
（旧位置由 Recovery.legacyTmp 一次性清；cache/share 只按 24h prune，防接收方异步读。）

三、0.2 缓存身份（真机）
------------------------
第一次启动（本构建）：
  17:08:58  cache epoch: import:official-159.apk / 预览验收  sig=a8088c9cb33b
            身份变了 ⇒ 清掉 cache 下 1110 项 / 130.4 MB
  之后 cache 27,864 KB（游戏本次启动重新生成音乐），epoch 文件见 cache-epoch.txt
同一版本 / 同一槽再启动：
  17:09:17  sig=a8088c9cb33b  身份未变，保留 cache
换版本 / 换补丁：
  v1607（带 1 条补丁，patchRes.apkSha 参与签名）⇒ 身份变了；补丁注入完成 1/1
  v146（probe146b）⇒ 身份变了 ⇒ 清 16 项 / 27.2 MB
  official-159（预览验收）⇒ 身份变了 ⇒ 清 16 项 / 27.1 MB
cache-report.txt = 最近一次（17:15:54）。

四、0.3 native 按版本（真机）
-----------------------------
native-dirs.txt：三个版本各一份 libarc.so，互不覆盖：
  550,584 B  import_mindustry-146.apk-70ef2b34   md5 c0eab82d…
  802,496 B  import_official-159.apk-ce1d53ee    md5 1fbdf51b…
  753,520 B  import_v1607.apk-862cfb14           md5 40bd0134…
146 / 159 的 mtime 仍是 17:04（第二次启动**没有重写** —— 同内容跳过），
v1607 是 17:15 首次发布。三份都是 `-r--------`（只读）。
旧布局（`natives/arm64-v8a/*.so`）已被 Recovery 清空；版本子目录不动。

五、0.4 / 0.5 界面（真机）
--------------------------
storage-card.png：存储占用卡片
  · 「缓存（临时文件）：27.2 MB」与「临时文件（导出、导入中转）：7 B」分开两行
  · 三个按钮：清理游戏缓存 / 清理分享缓存 / 关闭
点「清理游戏缓存」→ 确认卡（标题/说明/「返回」「清理」）→ 点「清理」：
  logcat: cache clean: cleared 23 entr(ies) / 28493843 B
  之后 `du -sk cache` = 4 KB、`du -sk code_cache` = 4 KB（两个根都空，根本身保留）
  （Toast 走 storage_clear_game_done_fmt；文案见 strings.xml / values-zh）

六、没在本轮真机验的（留给 0.6 收尾 / 阶段 1）
----------------------------------------------
· 「模组改动 ⇒ 清缓存」只在自检里用 modsSig + prepare 注入验过；真机没有往测试槽加模组。
· 「还有别的实例在跑 ⇒ 跳过」只在自检注入验过（单实例阶段没有第二个实例可造）。
· 系统 `pm trim-caches` 没有真跑（担心清到别的 App 的缓存）；0.1 的结构判据
  （HUB 临时件不在 getCacheDir 下）+ 自检已覆盖。
