# 构建与开发（维护者文档）

> 这份是**给构建 / 改代码的人**看的。想了解「这个启动器是什么、怎么用」请看
> [README.md](../README.md)。

- [构建](#构建)
- [资源](#资源)
- [开发直通口（`dev_*`）](#开发直通口仅-debuggable-构建生效)
- [模块边界](#模块边界对齐桌面版-mdt)
- [文档地图](#文档地图)
- [证据文件与开发笔记的存放策略](#证据文件与开发笔记的存放策略)

## 构建

```bash
./build.sh                # -> mdt-launcher.apk（debuggable=false，发布形态）
./build.sh install        # 构建 + adb install -r
./build.sh clean-trash    # 清空 .build-trash/（每次最多 45 个，重复跑直到清完）
DEBUGGABLE=true ./build.sh   # 调试形态（反射已验证不需要它，只是留着 dev 直通口）
```

> `build.sh` 每次构建会把上一轮 `build/` **归档改名**到 `.build-trash/<时间戳>/`
> （脚本刻意不 `rm -rf`：一次性删除大量文件在部分环境下会被整条拦掉，所以只做改名归档）。
> 攒太多时 `clean-trash` 要跑好几轮 —— 更省事的是整个 mv 出去：
> `mv .build-trash "/d/某处/.build-trash-$(date +%s)"`。

依赖：**Android SDK**（由 `ANDROID_HOME` 指定，需 build-tools 36.0.0 + platforms/android-36）、
JDK 8、`python`（打包 + clean-trash 用；PATH 里那个 WindowsApps 的 `python.exe` 是空壳，build.sh 会自己筛掉，也可用 `PYTHON=` 指定）。四个常见坑（MSYS 路径、cygpath 锁定、
GBK 编码、批量删除）已固化在 build.sh 注释里。

> ⚠️ **签名**：构建用的是调试签名密钥（`debug.keystore`）。自己侧载安装完全没问题；
> 但如果要**正式分发**（尤其是以后做自更新），**必须先换成正式密钥** ——
> 一旦发布过自更新就换不了了（换了老用户收不到升级）。

## 资源

（F1 起，2026-10-01）

`app/res/` 已接上：`build.sh` 的 `[1/4]` 会先 `aapt2 compile` 资源
（★ **先 `cp -r` 到 `/tmp`** —— 该工具的目录参数**不吃非 ASCII 路径**，硬失败）
再 link（res.zip 是**位置参数**，不是 `-R`），并用 `--java` 生成 `R.java`
（javac 的源码列表因此**必须在 link 之后**收集）。
⇒ **以后新增 `layout` / `drawable` 直接丢进 `app/res/` 即可，构建脚本不用再动。**
F2 与 F2b 已按这条路加过 `layout/` + `drawable/` + `values/dimens.xml`，构建脚本**一行没改**。

- 产物基线（**这是 F17d 时、2026-10-01 的快照**，此后轮次已继续增长）**162555 B**（**F17b / F17c / F17d 三轮均 162555**；F15b / 第 27 轮 / F17 三轮是 158459，F16 是 158536，F15 是 150344，
  F6c 是 146177，F6 是 129492，F7 / F7b 是 125396，F5b 是 112812，F3+F5 是 108499，F3b 是 91894，
  F2c / F2b 是 95990，F2 是 78451，F1 是 74131，接入 res 之前 57747）；
  `sources: 24`（**同样是那时**的 23 个源文件 + `R.java`；此后继续新增源文件，构建脚本也**一行没改**）。
  > 🔴🔴 **这个数字没有分辨率 —— 别用它判断任何事**（F17 查明，见 `docs/history/18-F17-验证记录.md`）。
  > `apksigner` 会把 **zip 中央目录的起点对齐到 4096 字节**；只要条目区的变化量**小于到下一个
  > 4096 边界的剩余空间**，差异就被"对齐填充"完全吸收 ⇒ **签名后的 APK 总大小一个字节都不变**。
  >
  > 所以历史上那些"怪事"**全是同一个机制**，不是巧合、也不是"改动太小"：
  > F2c 只改布局 XML、F3b 改了 Java 又删了一个内部类、**F6c 连改四轮全是 146177**、
  > **F6 改三轮逻辑都是 129492**、"F15 / F16 双双相同"、**F15b 与第 27 轮 158459 又是同一个数**，
  > **F17 加了正则 + 2 个方法 + 12 条自检断言后依然 158459**（而 `classes.dex` 如实从 205316 涨到 **207856**），
  > 以及 **F17b 又恰好涨 4096**（`classes.dex` 只涨 1620 ⇒ 跨越了一个对齐边界），
  > **F17c 干脆一个字节都没动**（162555 → 162555，而 `classes.dex` 如实 **209476 → 209644**，+168），
  > **F17d 又是 162555**（`classes.dex` **209644 → 212932**，+3288）。
  > **同一轮里 dev 变体与产品版也同为 162555** —— 对齐的又一次体现。
  >
  > ✅ **想用一个对改动敏感的数字 ⇒ 看 `build/dex/classes.dex` 的大小**，别用 APK 总大小。
  > ✅ 验证内容仍以「读回 APK 资源 / 读 dex / 真机跑行为」为准。
  > 资源文案：`aapt2 dump resources <apk> | grep -A1 <name>`；XML：
  > `python -c "import zipfile; print(zipfile.ZipFile('mdt-launcher.apk').read('res/layout/activity_log.xml').decode())"`；
  > **F6 / F6c / F15 的做法更硬：改完只在真机上验行为**（md5 比对 / 背景像素）；
  > **F16 是这个方向的极端** —— CAS 没有界面，只能靠设备上的报告文件 + 直接读对象数/体积（见 `docs/history/16-F16-验证记录.md`）。
  >
  > ⚠️ 顺带纠正两处旧解读：
  > ① "dev 变体与产品同尺寸" —— **结论碰巧对，理由不对**。`android:debuggable` 确实只改清单里
  >   一个布尔值（实测增量 0 B），但真正让两者同为 158459 的是**上面那个 4096 对齐**。
  > ② F5b 之前"dev 比产品大 4.3KB"曾被当成 debug 标志的成本，**实为误读** ——
  >   那是 F5b 新增资源的体量。
  > ⚠️ F7 又踩一个同类坑：**APK 里的二进制 XML 字符串池编码不统一** ——
  > `AndroidManifest.xml` 是 **UTF-16LE**（`b'LogActivity' in data` 为 False，要
  > `'LogActivity'.encode('utf-16-le') in data` 才 True），而 `res/layout/*.xml` 是 UTF-8。
  > 另外 `@+id/xxx` 一律被编译成整数 ID，**字符串池里根本不会有 id 名**（查不到 ≠ 没编进去）。
- ⚠️ **XML 资源注释里不能出现连续两个减号**（expat 报 not well-formed，且**行号显示 0**）。
- ⚠️ **`theme` 只挂自有 Activity，不挂 `application`** —— 游戏的 `AndroidLauncher` 没有自己的 theme，
  会继承 application 的（官方那是 `@style/ArcTheme`，在官方包内、我们引用不到）。
- 完整证据与配方：`docs/flows/03-阶段-0.md`（F1 节）+ `evidence/`（真机取证）。

## 开发直通口（仅 debuggable 构建生效）

adb 无法驱动弹窗与系统文件选择器，所以留了一组 `dev_*` extras 走**与界面完全相同的代码路径**：

```bash
ADB="$ANDROID_HOME/platform-tools/adb.exe"   # Windows 上写成 D:/…/platform-tools/adb.exe
$ADB shell am start -n io.mdt.launcher/.MainActivity --es dev_import_path <APK路径>
$ADB shell am start -n io.mdt.launcher/.MainActivity --es dev_msav_path <msav> --es dev_msav_slot <槽>
$ADB shell am start -n io.mdt.launcher/.MainActivity --es dev_launch_key 'import:official-159.apk'
$ADB shell am start -n io.mdt.launcher/.MainActivity --es dev_m3_selftest 1
$ADB shell am start -n io.mdt.launcher/.MainActivity --es dev_health 1     # 1 = 顺带清理
$ADB shell am start -n io.mdt.launcher/.MainActivity --es dev_saves 1      # 直接开存档界面（截图用）
$ADB shell am start -n io.mdt.launcher/.MainActivity --es dev_new_slot <槽名>
$ADB shell am start -n io.mdt.launcher/.MainActivity --es dev_assign_key <版本key> --es dev_assign_slot <槽名>
#   ↑ 不带 dev_assign_slot（= 空串）⇒ 清除该版本的槽分配，回落默认槽
#   ↓ F3 设置页 / F5 自动备份（2026-10-01）
$ADB shell am start -n io.mdt.launcher/.MainActivity --ez dev_settings 1            # 直接进设置页
$ADB shell am start -n io.mdt.launcher/.MainActivity --es dev_setting_dump <tag>     # 配置 dump 到 report-settings-<tag>.txt
$ADB shell am start -n io.mdt.launcher/.MainActivity --es dev_default_slot <槽|空>    # 写默认槽（空串=清）
$ADB shell am start -n io.mdt.launcher/.MainActivity --es dev_max_logs <n>           # 写日志保留份数
$ADB shell am start -n io.mdt.launcher/.MainActivity --es dev_policy_set <槽> --es dev_policy_arg <enabled,min,max>
$ADB shell am start -n io.mdt.launcher/.MainActivity --es dev_autobackup_test <槽>   # 立即跑一次备份并写 report
#   ↓ F6c 整槽 zip（2026-10-01）：绕开 SAF 选择器，直接对设备绝对路径干活
$ADB shell am start -n io.mdt.launcher/.MainActivity --es dev_zip_list <zip绝对路径>
$ADB shell am start -n io.mdt.launcher/.MainActivity --es dev_zip_import <zip绝对路径> \
    --es dev_zip_slot <槽> [--es dev_zip_wipe 1]
$ADB shell am start -n io.mdt.launcher/.MainActivity --es dev_zip_export <目标.zip绝对路径> \
    [--es dev_zip_slot <槽>]
#   结果落在 <外部 hub>/report-devtool.txt（本 ROM 滤 logcat ⇒ 只能靠文件）
#   ↓ F15 深浅色（2026-10-01）
$ADB shell am start -n io.mdt.launcher/.MainActivity --es dev_theme <system|light|dark>
#   ↑ 写 theme_mode 后**自己 recreate()**（本页立刻换肤），结果写 report-devtool.txt
#   ★ 唯一一个"改完要重建"的口：它**必须先把 extra 摘掉**再 recreate，
#     否则重建时 onCreate 又读到 dev_theme ⇒ 无限重建（屏幕全黑，像崩了其实是没 idle）
#   ↓ F16 CAS 存档池（2026-10-01）
$ADB shell am start -n io.mdt.launcher/.MainActivity --ez dev_cas_stats true     # 池现状 + 各槽节省率
$ADB shell am start -n io.mdt.launcher/.MainActivity --ez dev_cas_gc true        # 立即 GC 并报删了几个
$ADB shell am start -n io.mdt.launcher/.MainActivity --ez dev_cas_migrate true   # 旧格式清单 → v2（按槽）
$ADB shell am start -n io.mdt.launcher/.MainActivity \
    --es dev_backup_restore <槽> [--es dev_restore_pick <序号，0=最新>]
#   ★ dev_backup_restore 会真覆盖槽内容 ⇒ 只在测试槽上用
#   ↓ F21 地图资源统计（2026-10-03 第 83 轮）
$ADB shell am start -n io.mdt.launcher/.MainActivity --es dev_mapstats <槽> \
    [--es dev_mapstats_file <某个 .msav 绝对路径>]     # 逐图数字 + 真机耗时
#   不带 dev_mapstats_file ⇒ 把该槽能扫到的图（最多 60 张）全跑一遍
#   结果同样落在 <外部 hub>/report-devtool.txt（★ 所有 dev_* 口共用这一个文件；不存在 report-cas.txt）
```

⚠️ **深色/浅色切换**
- **启动器自己的深浅色**：用设置页第一行「深浅色」，或 `--es dev_theme light|dark|system`。
  它是**应用内**的（改 `config.json` + 包一层 Configuration），**不动系统设置**。
- **改系统深浅色**（验"跟随系统"那一档时要动它）：部分 ROM（如 Honor）单发
  `settings put secure ui_night_mode 1|2`（1=亮 / 2=暗）有时不生效，稳定做法是三条一起发：
  `cmd uimode night yes|no` + `settings put secure ui_night_mode 2|1` +
  `am broadcast -a android.intent.action.CONFIGURATION_CHANGED`，然后**必须 `am force-stop` 再起 app**。
- 判据别靠肉眼 —— 直接取背景像素（夜 `#101114` / 昼 `#F3F4F6`）。
  ★ F15 实测：**系统深色 + 启动器选"浅色" ⇒ `#F3F4F6`**，这组"两者不一致"的取值才是真判据
  （两者一致的场景下，分不清是谁在生效）。

报告落在 `<外部 hub>/report-*.txt`；本测试机型的 ROM 会滤掉应用自己的 logcat，
所以**验证一律看报告文件与截图，不看 logcat**。

## 模块边界（对齐桌面版 MDT）

```
Paths           ★路径唯一来源（私有 app_hub / 外部 hub / 数据根 / 槽记录）
Util            最底层工具（原子写 / md5 / 系统栏 inset）
Reflect         非 SDK 接口（dexElements 拼接 / addAssetPath）
Config          唯一配置 app_hub/config.json（未知键保留 / 版本迁移 / 脏值退默认 / 版本→槽分配 / 默认槽 / 日志份数 / 每槽备份策略 / 会话记账）
Versions        版本发现（已装包名白名单 + 用户手填 + 已导入副本）
Data            数据布局与存档槽（改名交换 / 槽增改删 / 数据根体检）
Importer        APK 导入（content:// 流式拷贝 + 只读落盘 + 预热预检）
Backup          存档备份/恢复（口径 =「槽内容 − 排除名单」，见 Data.SLOT_EXCLUDE；CAS 清单记 sha256，恢复前逐对象边写边验）
AutoBackup      自动备份（启动记账 → 回前台按运行时长结算；进程互斥；上限裁剪；失败静默写 report）
Msav            桌面 .msav 迁移（stage → 判 gzip 魔数 → commit）
Injector        ★六步加载管线（预热/dex 注入/native/标志/资产链/startActivity）
SelfTest        M3 自检（只在专用测试槽上做破坏性验证；dev 口触发）
LauncherApp     Application（迁移 + Config，保持轻量）
MainActivity    主进程 UI（版本列表 / 添加包名 / SAF 导入 / 启动 / 版本→槽 / 设置入口）
SavesActivity   存档与备份 UI（槽管理 / 备份恢复 / .msav / 数据根体检 / 每槽自动备份策略）
SettingsActivity 全局设置页（默认槽 / 日志保留份数 + 只读「关于」）
GameSlot        :game 坑位（解析目标 → 换槽 → 跑管线；失败按步骤报）
```

依赖方向单向：`Paths/Util/Reflect → Config → Data/Versions → Importer/Backup/Msav/Injector → UI/GameSlot`，不许反向。
（备份要读数据根、要找槽目录，所以 `Backup` 依赖 `Data` 而不是被它依赖；`Data` 里只有文件级操作，不认识备份。）
`AutoBackup` 是 `Backup` 之上的一层策略（记账 + 判定 + 裁剪），只被 `MainActivity` 调，不反向依赖 UI。

## 文档地图

| 文件 | 写什么 |
|---|---|
| [README.md](../README.md) | **给使用者看的**：是什么 / 怎么用 / 能做什么 / 还没有什么 |
| `DEVELOPING.md`（本文） | 构建、`dev_*` 直通口、模块边界、文档地图 |
| `BACKLOG.md`（**索引**） | 功能池：**是什么 / 为什么 / 已核实了什么**（正文在 `docs/backlog/`，8 片） |
| **`FLOWS.md`**（**索引**） | ★ **怎么动手**：逐个功能的「改动面 → 步骤 → 验收 → 坑」（正文在 `docs/flows/`，14 片） |
| `docs/history/README.md`（**索引**） | 逐轮的**实现与真机验证记录**（2026-10-04 从 README 整节搬出，内容一字未改；正文 23 片） |
| `spike/spike-datadir/RESULT.md` | `mindustry.data.dir` 注入 spike 的完整实验证据（✅ 成立）与实施清单 |
| **`REF §n`**（散见各文档） | 「为什么这么改」的**开发笔记编号** —— 该笔记**不随本仓发布**，出现处只作来源标注 |
| 当前进度 / 下一步 | 不维护**全局里程碑表**（半更新的表比没有更误导）—— 各批次完成状态见 `docs/flows/02-一-总顺序.md` 与 `docs/backlog/07-五-建议的动手顺序.md` |

> ⚠️ **本仓文档与代码注释里的 `MDT-Android-Dev/`、`MDT-Dev/`、`_lab/` 路径，以及
> `REF §n` / `REFERENCE §n` 引用，都只作「来源标注」**（说明那条结论是怎么来的、
> 对应哪份开发笔记），**都不是可点开的链接** —— 那些开发笔记与**探针工程**（八轮真机 /
> 模拟器实测的机制证据）、**桌面版**一样，**都没有随本仓发布**。
> 架构约束（六步加载配方、双 AssetManager、槽交换时机、删除兜底等）以探针工程那份为准，
> `GameSlot.java` 头注释是速查版。本工程只做产品化，不复现实验。

> **为什么文档这么拆**（2026-10-04）：`FLOWS.md`/`BACKLOG.md` 曾是 192 KB / 102 KB 的单文件
> ⇒ 改成"索引 + 分片"，判据 = **分片拼回去与拆分前逐字节相同**（`docs/` 下的分片标题一个字都没改，
> 旧引用照旧有效）。原来那张「状态」里程碑表**已删**：它只更到 F17d（一半都不到）—— 与其留一张
> 半更新的，不如按批次看 `docs/flows/02-一-总顺序.md`（总顺序）与
> `docs/backlog/07-五-建议的动手顺序.md`（动手顺序）。

> 技术选型（2026-09-30 定）：**新建独立工程、纯 framework 控件（零依赖、无 Gradle）、
> v1 仅本地（已装扫描 + 导入），网络下载留 v2。**

## 实现过程 / 历史验证记录

逐轮的实现细节与真机验证记录见 [`docs/history/`](history/README.md)
（按 M/F 编号查）。「为什么这么改」的依据见 `docs/backlog/`（条目里的「为什么 / 已核实了什么」）与 `docs/history/`；
「怎么动手」的过程见 `FLOWS.md`（按 F 号查，正文在 `docs/flows/`）。

## 证据文件与开发笔记的存放策略

为控制仓库体量，下面这些**不随仓库发布**（只作来源标注，不是可点开的文件）：

| 类别 | 现状 | 说明 |
|---|---|---|
| `evidence/*.png` 真机截图（211 张、约 60 MB） | **已移出仓库** | 文档里仍按文件名引用（`evidence/xxx.png`），那是**来源标注**；图集存在本地归档，需要时取回 |
| `evidence/*.txt` / `*.tsv` 文本与数据证据 | **在仓库内**（约 0.8 MB） | 报告、对照表、逐字抄录 —— 体量小、信息密度高，留着 |
| 开发笔记（`REF §n` / `REFERENCE §n` 指向的） | 不在仓库内 | 「为什么这么改」的判决书 |
| 探针工程 `MDT-Android-Dev/`、桌面版 `MDT-Dev/` | 不在仓库内 | 机制证据与对照实现 |
| 本机指令文件（`AGENTS.md`）、笔记目录（`.dsh/`） | 被 `.gitignore` 排除 | 本机个人上下文，换机器要重写 |
