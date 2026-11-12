第 369 轮（2026-11-12）导出 / 分享写后校验 —— 真机验收证据
==============================================================

设备：HONOR ELP-AN00 / Android 16（API 36），序列号 AJYMUT4522007370
构建：DEBUGGABLE=true SUFFIX=-dev ./build.sh install（dev 变体，自检用）
当前槽（跑前 / 跑后）：预览验收（跑自检前已确认槽目录存在）

一、自检 selftest-369.txt
  通过 2102 项，失败 0 项（第 3 行）；「第 369 轮：导出 / 分享产物的写后校验」组 22 条全绿，含：
    - 本地：合法 zip 能过 / 截断 zip 判死 / 0 字节判死 / 空 zip 合法 /
      结构仍合法但数据被改 1 字节 ⇒ CRC32 判死（ZipFile 本身仍能打开）
    - 关流：zip 关流失败必须冒泡（closeQuietly 吞掉 = 缺中央目录的坏包仍报成功）；
      ShareOut.stage 收到截断 zip 必须抛且 cache/share 里不留半截；元断言（合法 zip 必须放行）
    - 端到端：writeText / writeFile / zipTo 经 file:// 目标逐字节 / md5 校验通过
    - 真 ContentResolver + 仅 dev 构建的 FakeExportProvider（build.sh 注入清单）：
      正常档（写/读/逐字节/fsync）通过；close 时少写 10 字节 ⇒ 判死 + 删新建目标；
      close 后 0 字节 ⇒ 判死 + 删新建目标；不 truncate + 目标原本非空 ⇒ 判死但**不删**原有文件；
      读 fd 是 pipe ⇒ 内容照过、fsync 层识别为 false；file:// 真实 fd fsync 返回 true。
      fsync 首选**写 fd**（`getFD().sync()`；`openOutputStream` 返回的就是 FileOutputStream 子类），
      pipe provider 再用读 fd 兜底。

二、整槽导出的 dev 口（devtool-export-369.txt，补丁后的最终跑）
  dev_zip_export 槽「预览验收」→ /sdcard/Android/data/io.mdt.launcher/hub/acc369b.zip
    files=64 raw=497999 out=383058 落盘大小=383058
  PC 侧（Anaconda python）校验：
    >>> zipfile.ZipFile(...): entries=64  bad=None  size=383058
  说明：目标落盘大小 == 校验过的缓存完整件字节数（fsync + 读回比对路径已在新代码里）；
  testzip() 返回 None 表示 64 条 CRC 全过。

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
  仍需真机手工点一次导出；本轮用同源 ContentResolver + file:// 目标 + dev 假 provider
  覆盖了同类故障语义（close 才提交 / 不 truncate / 0 字节 / pipe fd）。
  另：整槽 / 快照文件是在游戏未运行时导出的，"游戏正在写"的源侧一致性不在本轮判据内
  （界面既有"游戏在跑可能半写"提示不变）。

六、i18n 门禁
  DSH 这边 `bash ./build.sh` 的 [0/4] 全过（i18n-check --selftest + 正式检查都 OK）；
  用户 WorkBuddy 环境会卡在清 .tmp-i18n-selftest/case-16 的 51 文件自删保护，与本轮改动无关。
