第 369 轮（2026-11-12）导出 / 分享写后校验 —— 真机验收证据
==============================================================

设备：HONOR ELP-AN00 / Android 16（API 36），序列号 AJYMUT4522007370
构建：DEBUGGABLE=true SUFFIX=-dev ./build.sh install（dev 变体，自检用）
当前槽（跑前 / 跑后）：预览验收（跑自检前已确认槽目录存在）

一、自检 selftest-369.txt
  通过 2095 项，失败 0 项（第 3 行）；新增组「第 369 轮：导出 / 分享产物的写后校验」
  14 条全绿，含：
    - 截断 zip 判定为坏包（缺中央目录）
    - 结构仍合法但数据被改 1 字节 ⇒ CRC32 判定为坏包（ZipFile 本身仍能打开）
    - zip 关流失败必须冒泡（closeQuietly 吞掉 = 缺中央目录的坏包仍报成功）
    - ShareOut.stage 收到截断 zip 必须抛且 cache/share 里不留半截
    - 元断言：同一夹具换成合法 zip 必须放行
  另：本地 writeText / writeFile / zipTo 经 `file://` 目标（与 content:// 同一套
  ContentResolver 读写）端到端逐字节 / md5 校验通过。

二、整槽导出的 dev 口（devtool-export-369.txt）
  dev_zip_export 槽「预览验收」→ /sdcard/Android/data/io.mdt.launcher/hub/acc369-final.zip
    files=64 raw=497999 out=383058 落盘大小=383058
  PC 侧（Anaconda python）校验：
    >>> zipfile.ZipFile(...): entries=64  bad=None  size=383058
  说明：目标落盘大小 == 校验过的缓存完整件字节数；testzip() 返回 None 表示 64 条 CRC 全过。

三、分享包的 dev 口（devtool-share-369.txt）
  dev_share_export 槽「默认（default）」→ .../acc369-share.zip
    files=1 out=436 落盘大小=436
  PC 侧：entries=1 bad=None size=436；server_list.json 不在包内、settings.bin 在（脱敏口径未变）。

四、失败路径（devtool-fail-369.txt）
  dev_zip_export 指向不存在的目录 .../hub/no-such-dir-369/x.zip
    FAILED: java.io.IOException: 目标位置不可写（系统没给出可写句柄）
  adb ls 该目录：No such file or directory ⇒ 设备上没留下任何文件。

五、未验到（如实记）
  真 DocumentsProvider（系统「保存到哪」选择器返回的 content://）那条路 adb 自动化不了，
  仍需真机手工点一次导出；本轮用同源 ContentResolver + file:// 目标覆盖了写 / 读 / 删全套代码路径。
  另：整槽 / 快照文件是在游戏未运行时导出的，"游戏正在写"的源侧一致性不在本轮判据内
  （界面既有"游戏在跑可能半写"提示不变）。
