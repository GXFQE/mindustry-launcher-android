# MDT 启动器 —— 国际化（i18n）可行性研究

> 结论：**可行，而且基础比预想的好**。构建链、资源机制、"包一层 Configuration"的应用内主题钩子
> **全都已经就绪**，不需要动 `build.sh` 的编译流程；真正的工作量集中在两处 ——
> ① 把**默认语言从中文翻成英文**（559 条）② 把**散落在 Java 里的 344 条用户可见中文**搬进资源
> （A 305 条 + A′ 40 条），并修掉**四条"用中文串做判据"**的隐性依赖。
>
> 本文件只做研究，**没有改动任何产品代码**。所有"已验证"的结论都附了可复现的命令或文件位置。

---

## 0. 摘要（给决策看的十行）

| 问题 | 结论 |
|---|---|
| 构建链支持多语言吗？ | **支持，已亲手跑通**。`aapt2 compile --dir` 会自己吃 `values-xx/`，`build.sh` **一行都不用改** |
| 现在非中文用户看到什么？ | **满屏中文**。`app/res/` 只有 `values/`（中文）与 `values-night/`（只有颜色主题）⇒ **零翻译资源** |
| 界面文案有没有硬编码在布局里？ | **没有，0 处**。19 个布局全部走 `@string/`；`hint` / `contentDescription` 也是 0 处 |
| 有没有固定宽度把长译文截断？ | **没有**。TextView/Button 写死 dp **0 处**，无 `maxWidth`/`minWidth`/`minHeight` |
| 换个长语言的译文会不会丢字？ | **基本不会**，只有 2 处要动：`item_mod` 的状态徽标、`item_slot` 的 `slot_meta`（`maxLines=3`） |
| 支持 RTL 吗？ | 布局**已经准备好了**（方向写死 **0 处**，`start/end` 用了 **131 处**），但 manifest 没开 `supportsRtl`。**但建议不做 RTL** —— 见 §4.4 |
| 应用内能自己选语言吗？（不做成跟随系统） | **能，而且有现成的正确做法**：F15 的 `ThemeMode.wrap` 就是同一个机制，改的是 `Configuration` |
| 最大的一块硬骨头？ | **用户可见的中文有 A 305 条 + A′ 40 条 = 344 条**在 Java 里，**71% 集中在 6 个文件**；产出地是**"没有 Context 的静态层"**（`Mods` / `MsavMeta` / `SettingsBin` / `MapStats` / `Cas` / `Versions`）⇒ 拿不到 `getString()`，要改成"错误码 + 参数"或由调用方注入文案 |
| 有没有"一翻译就坏"的地方？ | **曾经有 4 处（5 命中），已全部修完**（P0.1）：`MapsActivity` 改判新的 `MapFiles.Result.nameTaken`、`Backup`/`SlotOps` 改判新的 `Backup.RestoreResult.ok`、`MapStats` 那个**发现是空体死条件、直接删**。**真机自检 514 通过 / 0 失败。** 现在由门禁 `SRC-01` 常驻盯着（见 §5.2） |
| 要不要替游戏决定语言？ | **不要**。游戏自己的语言是 `settings.bin` 里的 `locale` 键，游戏内有语言选择器。但我们应该**读它**来决定地图页显示哪套译名 |
| **做到哪了？** | **P0 门禁** ✅（12 条规则 + 25 条元断言；台账；`build.sh` 的 `[0/4]`）→ **P0.1** ✅（5 处"中文当判据"全修，真机 514/0）→ **P1-A** ✅（**默认语言已翻成英文 560 条**，中文进 `values-zh/`）→ **P2** ✅（**设置页能切语言**：跟随系统 / English / 简体中文；真机逐条验过，且**英文界面下自检仍 514/0**）。**剩下 P1-B（自检语言无关化）与 P3（硬编码迁资源）** |
| 一句话建议 | **P1-B 是可选的**了 —— P2 用"把自检语言钉死"换掉了"用户切英文就红 15 条"这个坑，所以**现在可以发版**。P1-B 做的是"让自检在两套语言下都有意义"，属于把安全网补厚；P3 是长尾 |

---

## 1. 现状盘点

### 1.1 已经就绪的部分（这部分决定了"可行性"的上限）

| 维度 | 实测数字 | 依据 |
|---|---|---|
| 资源文案条数 | **559 条**，全在一个 `strings.xml` 里 | `app/res/values/strings.xml`（856 行 / 64 KB） |
| 带位置参数的条数 | **204 条**，**裸 `%s` / `%d` = 0 条** | XML 解析统计 ⇒ **翻译时不会出现"参数顺序不可控"的经典坑** |
| 引用点 | **662 处**，分布在 **17 个文件** | `R.string.*` / `getString(R.string.*)` 计数 |
| 布局硬编码文字 | **0 处**（`android:text` / `hint` / `contentDescription` / `tools:` 全 0） | 19 个布局逐属性审计 |
| 布局方向写死 | **0 处**；`start/end` 系列 **131 处 / 全 19 个布局** | 同上 |
| 文本控件的固定宽度 | **0 处**；`maxWidth`/`minWidth`/`minHeight` 各 **0 处** | 同上（写死 dp 的 41 处全是 ImageView/View/分割线/`maxHeight`） |
| `<plurals>` / `<string-array>` | 各 **0 条** | 没有复数/数组资源 ⇒ 追加语言目录是纯增量 |
| `translatable="false"` | **0 处** | 值得补：`GameSlot` / `dev_assign_key` 这类不该翻的要标上 |
| 资源引用覆盖率 | **511 / 559 被引用，48 条是孤儿** | 没人引用的 48 条要么是历史残留、要么是"本来该用却写死了中文"的证据；P1 之前先过一遍 |
| 应用内换肤的钩子 | `BaseActivity.attachBaseContext` → `ThemeMode.wrap(base, mode)` | **9 个页面全部继承 `BaseActivity`**（自检 ㊱ 钉着），⇒ 换语言只要改这**一个点** |
| 设置页的交互样式 | 点一行 → 弹单选列表 → 立刻落盘 + `recreate()` | 照 `SettingsActivity.pickTheme()` 抄一行即可，无需新控件 |

### 1.2 缺口（这就是全部工作量）

| # | 缺口 | 量级 | 影响 |
|---|---|---|---|
| 1 | **默认语言是中文** | 559 条 | 所有非中文设备（今天 = 全世界）都显示中文 |
| 2 | **Java 里的中文没进资源** | 除 `SelfTest` 外共 **757 处**含中文字面量 ⇒ **A 主文案 354 处（去重 305 条）/ A′ 技术细节 48 处（40 条）/ B 落盘 268 处 / C 内部 87 处**；A∪A′ **71% 集中在 6 个文件** | 就算翻完资源，这些地方仍是中文 |
| 3 | **`*Reason()` 系列的产出地没有 Context** | `Mods` 138 / `MsavMeta` 40 / `SettingsBin` 33 / `MapStats` 10 / `Cas` 8 / `Versions` 5（A+A′ 处数）；`MsavMeta` / `SettingsBin` / `MapStats` **完全不含 `android.*`** | 结构性障碍，见 §5.3 |
| 3b | ✅ ~~🔴 有 4 处用中文串做判据~~ **已修（P0.1）** | `MapsActivity.java:384` / `Backup.java:437` / `SlotOps.java:170` / `MapStats.java:752` | 曾"一翻译就静默失效"；改成标志位/结构体/删死条件，真机 514/0 通过 —— 见 §5.2 |
| 4 | **地图页译名硬编码 `bundle_zh_CN`** | `BundleNames.java:60`、`MapStats.java:364` | 英文用户在地图统计页看到的方块名是中文 |
| 5 | **`（墙）` 兜底写死在代码里** | `BundleNames.java:138-139` | 同上 |
| 6 | ~~没有翻译基础设施~~ ✅ **P0 已补** | —— | `tools/i18n-check.py` + `tools/i18n-known.txt` + `build.sh [0/4]`：key 对齐、参数索引、空白、标签、**禁用文案当判据**全都有门了（§8 P0 落地记录） |
| 7 | 仓库文档只有中文 | README 等 | 面向国际用户还差一层（但这不算 i18n 的技术问题） |

---

## 2. 构建链实证（免 Gradle 的手工链，已亲手跑过）

`build.sh` 的 `[1/4]` 是 `aapt2 compile --dir <res 镜像> -o res.zip` 再 `aapt2 link`。
**资源限定符目录是 aapt2 自己的机制，脚本完全无感** —— `docs/DEVELOPING.md:57` 早就写了
"以后新增 `layout` / `drawable` 直接丢进 `app/res/` 即可，构建脚本不用再动"。

为了不停留在"应该可以"，我在 `%TEMP%` 里做了一次**不动工程任何文件**的实证
（copy 一份 `app/res`，加 `values-en` / `values-de` / `values-ar` / `values-zh`，用工程**同版本**的
`build-tools/36.0.0/aapt2.exe` + `platforms/android-36/android.jar`，参数与 `build.sh` 一致）。

### 2.1 ✅ 多语言目录编译 + 链接通过，5 套 locale 都在

```
aapt2 compile --dir <res> -o res.zip            → rc=0
aapt2 link -o out.apk -I android.jar --manifest ... \
      --min-sdk-version 26 --target-sdk-version 36 res.zip   → rc=0
aapt2 dump badging out.apk
  locales: '--_--' 'ar' 'de' 'en' 'zh'
aapt2 dump resources out.apk
  resource 0x7f070012 string/app_name
    () "MDT 启动器"
    (de) "MDT-Startprogramm fuer Mindustry"
    (zh) "MDT 启动器"
    (en) "MDT Launcher"
    (ar) "MDT"
```

⇒ 同一个资源 ID 下多份 config，**默认目录就是兜底**。这正是我们要的语义。

### 2.2 ✅ 部分翻译是合法的（缺的 key 自动回落默认）

只翻 2 条的 `values-en` 与完整的 559 条默认并存，`act_add_title` 的 dump 只有 `()` 与 `(zh)` 两份
—— **没有 `(en)` 也不会报错**，运行时回落默认。⇒ 语言目录可以**增量补**，不必一次翻完。

### 2.3 ✅ API 33+ 的「应用指定语言」也能走通

`res/xml/locales_config.xml` + `<application android:localeConfig="@xml/locales_config">`
在**这套免 Gradle 链**里同样编译链接通过（`out2.apk`，rc=0）。想接系统设置里的
「应用 → 语言」时用得上（但见 §3 的路线选择）。

### 2.4 🔴 最重要的一个坑：**只存在于语言目录、默认目录里没有的 key，会被 aapt2 静默移除**

```
warn: removing resource io.mdt.spike:string/only_in_en_test without required default value.
link rc = 0          （退出码是 0！）
R.java 里没有 only_in_en_test
```

后果分两种：
- Java 里引用了它 ⇒ **javac 报 cannot find symbol**（编译期拦住，安全）；
- 另外**没人引用**（比如译者手抖把 key 名打错，或在语言目录里多加了一条）⇒ **只有一行 warn**，
  这条翻译**永远不生效**，而 APK 照样构建成功。

⇒ **必须有一道构建期的 key 对齐门**（见 §8 的 P0）。这条也是"翻译漂移"的根因，
不能靠人盯。

### 2.5 每多一套语言的体积成本：**≈ 11 KB**

```
baseline（只有默认一套）        = 94675 B
+2 套完整语言（整份 559 条复制）= 117203 B   ⇒ 每套 ≈ 11 KB
```

（`resources.arsc` 基本不压缩，这个数就是实际落盘增量；英文比中文 UTF-8 更省字节，实际会更小。）
10 套语言 ≈ **110 KB**，相对产品版 `mdt-launcher.apk` 的 372801 B 是 +30%。
**结论：体积不是障碍，但可以按需只带主流语言。**

### 2.6 既有坑（照旧有效，加语言目录不会引入新的）

| 坑 | 现状 | 与 i18n 的关系 |
|---|---|---|
| `aapt2` 的目录参数**不吃非 ASCII 路径** | `build.sh:202` 已 `cp -r` 到 `/tmp` 镜像 | 无影响（镜像会一起带上语言目录） |
| `aapt2` **剥掉字符串的前导/尾随空白** | 已有 2 条用 `\u0020` 绕（`slot_current_suffix`、`mods_kind_s_plus_res`） | 翻译时**不许用"空格 + 拼接"**留白，沿既有纪律 |
| `rm -rf "$RES_DIR"` 注释说"只有个位数文件" | **已过期**：`app/res` 现在 **57 个文件**，加 10 套语言 ≈ 67 | 见下方风险 |
| `aapt2 compile` 输出 zip、`link` 里是**位置参数** | 不变 | 无影响 |

> ⚠️ **一处需要顺手修的隐患**：`build.sh:194-196` 的注释写着"当前 `app/res` 只有个位数文件，
> 远低于那个删除闸"，而实测**已经是 57 个文件**。加语言目录会继续往上走。
> 这条注释的警示（"逼近 50 就要改成 `mv` 归档或分批删"）**已经过期**，
> 应当把 `rm -rf "$RES_DIR"` 改成 `mv` 归档（rename 不触发删除闸），或者直接删掉那段过时注释另作处理。

---

## 3. 应用内语言切换的技术路线

### 3.1 三条路，选第二条

| 路线 | 做法 | 评价 |
|---|---|---|
| ① 只跟随系统 | 什么都不做，加 `values-en/` 就完了 | **最省事**，但中文设备想用英文界面、或英文设备想用中文界面时没办法；而且启动器将来若有 zh-TW/ru 用户，只能跟着系统走 |
| ② **自建应用内语言**（推荐） | `config.json` 存 `app_language`；`attachBaseContext` 里包一层 `Configuration`（改 `locale`） | **与 F15 深浅色完全同构**，零依赖、API 26+ 通吃、不写系统设置；`attachBaseContext` 已经是全工程唯一的注入点 |
| ③ 用 API 33+ 的 `LocaleManager` + `localeConfig` | 让系统设置里出现「应用 → 语言」 | 只有 API 33+ 有（minSdk 26 ⇒ 还要给 26~32 另写一套 ⇒ **两套代码**），而且它写的是**系统层面**的 per-app 语言。`ThemeMode.java:14-20` 已经就"要不要用 `UiModeManager`"给过同样的结论：**太重、不想让启动器偏好渗到系统**。★ 实测补充：`adb shell cmd locale set-app-locales` 在本机（API 36）**不声明 `localeConfig` 也生效** —— 即"系统那条路"真的能走通，但**上面两条理由与它无关**，所以 P2 仍然选自建、仍然不声明 `localeConfig`（两条并存只会制造"在系统里改了没反应"） |

⇒ 选 ②，与 F15 保持一致。**也建议不要声明 `localeConfig`** —— 一旦声明，系统设置里会出现
一个入口，而我们的包装会盖住它（用户在那里改了没反应），反而制造困惑。

### 3.2 具体怎么改（一个类 + 两个方法）

```
新增 LocaleMode.java（照 ThemeMode.java 写）：
    public static final String SYSTEM = "";                 // 跟随系统
    public static String of(Context ctx);                   // 读 Config
    public static Context wrap(Context base, String lang);  // 改 cfg.setLocale(...)
    public static int labelRes(String lang);                // 设置页列表项

BaseActivity：
  attachBaseContext：把 ThemeMode.wrap 与 LocaleMode.wrap 【合并成一次】createConfigurationContext
                     （同一个 cfg 上既改 uiMode 又 setLocale）
                     ⚠️ 嵌套两次 wrap 也能工作（第二次读的是第一次的 Configuration），
                        但没必要付两次 Resources 实例化的代价，而且"哪个先"会变成隐性依赖
  onResume        ：快照从 int mAppliedMode 扩成 (mode, lang)，任一变化就 recreate()
                     （⚠️ 注意 BaseActivity 类注释里那条：比对的是**建实例时的快照**，
                       不是当前资源 —— 因为包装之后当前资源永远等于期望值）

SettingsActivity：照 pickTheme() 加一行「语言 / Language」，列表 = 跟随系统 + 已翻译的那些
Config          ：加 K_LANG = "app_language"，坏值退 "system" + WARNING 点名键名（沿既有纪律）
```

### 3.3 🔴 两个必须知道的陷阱

**（一）绝对不要在 `:game` 进程里调 `Locale.setDefault()`。**

依据（游戏源码 `Mindustry-master/core/src/mindustry/Vars.java:536-554`）：

```java
Fi handle = Core.files.internal("bundles/bundle");
String loc = settings.getString("locale");     // 游戏自己的设置，默认 "default"
if(loc.equals("default")){
    locale = Locale.getDefault();              // ← 跟随【进程默认 locale】
}else{ ... 用游戏设置里那个 ... }
Locale.setDefault(locale);
Core.bundle = I18NBundle.createBundle(handle, locale);
```

`GameSlot` 跑在 `:game` 进程（`AndroidManifest.xml`：`android:process=":game"`），
游戏 Activity 与它**同进程**。如果我们为了"应用内语言"顺手调了
`Locale.setDefault(启动器选的语言)`，那么**用户没在游戏里显式选过语言时，
游戏会跟着启动器走** —— 这可能是想要的，也可能是"我明明选的日语怎么变英文"的事故。

⇒ 正确做法：**只包 `Configuration`，不碰 `Locale.setDefault`**。
（副作用：`SimpleDateFormat` 之类用 `Locale.getDefault()` 的地方不跟随 —— 而我们在 §7.3 本来就要统一掉它。）

**（二）系统级文案**（桌面图标名、最近任务卡片标题、系统"应用信息"页）走的是
`PackageManager` + 系统 locale，**不受应用内包装影响**。这是预期行为，
但要在文档里写清楚，免得被当成 bug。顺带：`AndroidManifest.xml:125`
把 `SlotActivity` 的 label 写成了**带占位符的** `@string/slot_page_title_fmt`（`槽「%1$s」`）
⇒ 最近任务里会显示字面的 `%1$s`。这是**中文下就已存在的缺陷**，顺手修（换一条无占位符的串）。

---

## 4. 游戏侧的边界（已从游戏源码定案，不是猜的）

启动器是"把官方 APK 当插件加载"，所以**游戏 UI 的语言不是我们的事**，但有两个交界面必须搞清楚。

### 4.1 游戏的语言由谁决定

| 事实 | 出处 |
|---|---|
| 游戏自己有 `locale` 设置，出厂默认 `"default"` = 跟随系统 | `Vars.java:501` `settings.defaults("locale", "default", ...)` |
| `"default"` ⇒ `Locale.getDefault()`；否则按 `"zh_CN"` 这种串构造 `Locale` | `Vars.java:538-551` |
| 游戏里有语言选择对话框 `LanguageDialog` | `core/src/mindustry/ui/dialogs/LanguageDialog.java` |
| 这套设置存在**槽的数据根**里的 `settings.bin` | 游戏 `Settings` 落盘位置由数据根决定 |

⇒ **推论：不要替游戏决定语言，也不要写它的 `locale` 键。**
用户想改游戏语言，游戏内有入口；我们插手只会制造"两个地方都能改、互相打架"。

### 4.2 游戏自带 **35 套**译文（+1 套基础包）

`[Android][v160]Mindustry.apk` 的 `assets/bundles/` 实测 **36 个文件**：
`bundle.properties`（= **英文基础包**）+ `be bg ca cs da de es et eu fi fil fr hu id_ID it ja ko lt
nl nl_BE pl pt_BR pt_PT ro ru sr sv th tk tr uk_UA vi zh_CN zh_TW`。

这是**启动器语言覆盖面的天然参照系**：
- 游戏支持的市场，才是有真实用户的市场；
- **没有任何一套 RTL 语言**（无 `ar` / `he` / `fa` / `ur`）⇒ 见 §4.4。

### 4.3 地图统计页的译名解析（这里有一条必须修的硬编码）

现状（`BundleNames.java`）是**两层 bundle、层内模组优先**：

```
语言层 = 版本 APK 的 assets/bundles/bundle_zh_CN.properties  ⨁ 各模组的 bundles/bundle_zh_CN.properties
基础层 = 版本 APK 的 assets/bundles/bundle.properties        ⨁ 各模组的 bundles/bundle.properties
```

语义是对的（与游戏 `Mods` 的字节码一致），但**语言层文件名写死了 `zh_CN`**：

| 写死的地方 | 行 |
|---|---|
| 版本 APK 的语言包名 | `BundleNames.java:60` |
| 模组 bundle 的文件名常量 | `MapStats.java:364` `BUNDLE_LOCALE_FILE = "bundle_zh_CN.properties"` |
| 找不到时的兜底后缀 | `BundleNames.java:138` `pick("wallore", "（墙）")` |

**修法（成本很低，因为能力已经有了）**：
1. 从**这个槽的 `settings.bin`** 读 `locale` 键 —— `SettingsBin.Values.getString(key, def)` **已经存在**
   （`SettingsBin.java:129`），而且整套读写/校验/备份纪律都是现成的；
2. `"default"` ⇒ 用**启动器的生效 locale**；否则用它（形如 `zh_CN`）；
3. 拼成 `bundle_<loc>.properties`；**不存在就退回基础包**（英文），而不是退回中文；
4. `（墙）` 改成资源。

**三个名字体系的对映**（容易记混，建议写进文档 + 做成一个纯 Java 工具方法）：

| 场景 | 简体中文的写法 |
|---|---|
| Android 资源目录 | `values-zh-rCN/` |
| arc / 游戏的 bundle 文件名 | `bundle_zh_CN.properties` |
| 游戏 `settings.bin` 的 `locale` 值 | `zh_CN` |
| Java `Locale.toString()` | `zh_CN` |

⚠️ arc 的 `I18NBundle` 用的是 `ResourceBundle` 那套 `BaseName_<语言>_<国家>` 约定
（已核 `Arc-master/arc-core/src/arc/util/I18NBundle.java` 头注释），所以 `Locale.toString()`
能直接拼出文件名 —— **不需要自己写映射表**，但要把"默认/非法值"的兜底写对。

### 4.4 🚫 建议**不做 RTL**

理由不是"难"，而是**没有受众**：游戏自己的 36 套语言包里**一套 RTL 都没有**。
而且布局这边已经**几乎免费就绪**（方向写死 0 处、`start/end` 131 处）。

⇒ 做不做只差三件小事，建议**先不做，但在 P0 里一次做掉这三件**（成本几分钟，将来要用时不用回头补）：

1. `AndroidManifest.xml` 加 `android:supportsRtl="true"`
   （现在整个 RTL 是关的，131 处 `start/end` 等于白写；不开也没有副作用）；
2. `ic_chevron.xml`（6 处引用）与 `ic_backup.xml` 加 `android:autoMirrored="true"`
   （否则 RTL 下箭头指反）；
3. 记住一条：**新的方向性 drawable 一律带 `autoMirrored`**。

---

## 5. 硬编码中文审计

### 5.1 三个类别与实测条数

对 **47 个源文件**做了字面量级扫描（剥掉注释），除 `SelfTest.java` 外共 **757 处**含中文字面量
（不含纯标点碎片）。**判定口径 = 「这个字面量能不能到达用户可见面」**，不看它写在哪一行：

| 类 | 判定口径 | 处数 | 去重后 | 要不要进资源 |
|---|---|---|---|---|
| **A 用户可见（主文案）** | 经**列表行 / 弹窗正文 / Toast / 页面控件**显示；或作为**用户能读到的失败原因** —— 含 `userReason()`/`metaReason()` **透传**的中文、直接写进 `error`/`changeDesc` 的中文、被 `msgOf(e)` / `getMessage()` 拼进弹窗正文的**异常消息** | **354** | **305 条** | **必须** |
| **A′ 次要可见** | 只在「技术细节」**第二层**弹窗里出现（`SettingsBin.Result.report()`、`ModsActivity.showTech`、`MapDetailActivity.addTech` 的依据行） | **48** | **40 条** | 建议（见 §5.3 第 4 条） |
| **B 半可见（落盘）** | 写进 `hub/report-*.txt` / `last_log.txt` / `crashes/*.txt` / 自检报告 ⇒ 在「运行日志」页可看/可导出；含 `dev_*` 口（仅 debuggable 构建可达） | **268** | —— | 建议；至少要**明确决定** |
| **C 内部** | logcat、**被 `userReason()` 整句替换掉**的异常原文、内部常量/键名/文件名、PC 侧 lab、死代码返回值 | **87** | —— | 不用 |
| （`SelfTest.java`） | 自检报告正文（dev 口弹窗 + `hub/report-m3.txt`） | **930** | —— | **不建议翻** |

⇒ **真正要翻的是 A∪A′ = 344 条不同的字符串**（354+48 处）。

> ⚠️⚠️ **一个方法论上的坑，我自己第一版就踩了，值得记下来。**
> 我一开始用的是"**位置口径**"：字面量出现在界面 API 调用里 / `return` 语句里 / 赋给 `*error*` 字段 ⇒ 判 A。
> 这样算出来 A=154 / B=284 / **C=328**。**A 少算了一半以上，C 多算了近 4 倍。**
> 原因：本工程的 UI **大量通过异常消息把文案带上去** ——
> `msgOf(e)`、`errs.append(t.getMessage())`、`failedList.add(t.getMessage())`。
> 于是 `SlotZip` 20 / `Exporter` 6 / `Cas` 8 / `Importer` 8 / `Msav` 5 / `MapFiles` 14 / `Util` 1
> 这些**写在 `throw new IOException(...)` 里的中文，其实是弹窗正文**。
> ⇒ **只能按"可达性"判，不能按"写在哪"判。** 这也解释了为什么 `MainActivity` 的 126 处
> 全是 B（全部在 `dev_*` 口）而 `Mods` 却有 138 处 A。

分文件（**A + A′ 降序**，这就是改造量的落点）：

| 文件 | A+A′ | 一句话 |
|---|---|---|
| `Mods.java` | **138** | 模组扫描/启停/导入复制/冲突体检/加载门，全经 `ModsActivity` 弹窗与展开卡显示 |
| `MsavMeta.java` | **40** | `userReason()` 白名单**透传**我们自己的中文 ⇒ `error` 原文即用户文案 |
| `Backup.java` | **35** | `create()` 异常与 `restore()`/`cloneSlot()` 返回值**就是弹窗正文** |
| `SettingsBin.java` | **33**（16+17） | 第一层走 `userReason()`；`report()`/`changeDesc` 在「技术细节」层 |
| `Data.java` | **20** | 槽增删改名的弹窗文案 |
| `SlotZip.java` | **20** | 整槽导入的 stage/inspect/extract 异常 → `msgOf(e)` → 弹窗 |
| `Injector.java` | **14** | 步骤名 + 两条口语化失败原因进 `GameSlot` 失败弹窗 |
| `MapFiles.java` / `Maps.java` | 各 **14** | 地图导入名校检 / 地图列表行与详情页 |
| `Cas.java` / `Importer.java` | 各 **8** | 对象池异常 / APK 导入失败原因 → 弹窗 |
| `Exporter.java` / `Msav.java` / `Versions.java` | 各 **5~6** | 导出失败 / `.msav` 落盘失败 / 「等 N 个版本」 |
| `BundleNames.java` / `MapStatsMods.java` | 各 **12** | 主体是「技术细节」的依据行 |
| `MainActivity.java` | **0**（126 全 B） | 全在 `dev_*` 口 —— 仅 debuggable 构建可达 |
| `Compat.java` / `AutoBackup.java` / `Crash.java` | **0**（共 43 全 B） | 只进 `report-devtool.txt` / `report-autobackup.txt` / 崩溃报告 |
| **界面页 6 个**（`SlotActivity` / `MapDetailActivity` / `SettingsActivity` / `LogActivity` / `SavesActivity` / `SlotOps`） | **0** | **中文全在注释里** ⇒ 界面层已经全量资源化了 |

**三点直接读出来的结论：**

1. **界面层（`*Activity`）已经被清干净了** —— `MainActivity` 的 A 类 **0 处**、B 类 126 处；
   6 个界面页的中文**全部只出现在注释里**。⇒ **中文不在 UI 调用点，而在逻辑层往上传的字符串里。**
   这也正是上面那个"位置口径会算错"的根因。
2. **A 类高度集中**：`Mods` / `MsavMeta` / `Backup` / `SettingsBin` / `Data` / `SlotZip` 六个文件
   占了 **286 / 402 = 71%**。⇒ 改造是"六个文件的专项"，不是"全工程撒网"。
3. **B 类（268 处）是个"要不要翻"的决策点**，不是技术问题。见 §5.5 案例 3。

> ⚠️ 去重后 A 类 305 条是**不同的字符串**；但它们**绝大多数是 `"槽「" + n + "」已存在"` 型的碎片**，
> 按本工程既有硬规矩（aapt2 连尾随空白都剥 ⇒ **不许前缀/尾巴串拼接**），
> 要重写成约 **200 条整句资源 + `%1$s`**（见 §5.4）。
>
> ⚠️ `strings.xml:5` 现在写着"写进 `hub/report-*.txt` 的取证文本不进资源（留在代码里）"。
> **国际化要把这条改掉或写清例外** —— 因为那些报告在「运行日志」页是**给人看的**。

---

### 5.2 🔴🔴 最严重的一类：**中文同时是「控制流判据」和「数据模型的值」**

> ✅ **2026-10-04 P0.1 已全部修完**（4 个位置 / 5 处命中），**真机自检 514 通过 / 0 失败**。
> 改动落点与验证见本节末尾的"修复记录"。

这一类**不是"文案没翻译"的问题，而是"一翻译就改行为"的问题**。它们都**不报错、不崩溃**，
只是**功能静默失效**。四条已逐条核实，并由构建期门禁 `SRC-01` 自动钉住：

| # | 位置 | 代码 | 一翻译会发生什么 |
|---|---|---|---|
| **1** | `MapsActivity.java:384` | `if (!overwrite && r.error != null && r.error.contains("已经有"))` | 判据的针是 `MapFiles.java:129` 的中文。⇒ **译名一改，「同名地图替换」确认框再也不弹**，用户看到的是"导入失败：这个槽的 maps/ 里已经有「X」了 —— 替换会盖掉原文件，需要先确认"（自己解释自己，但**替换按钮没了**） |
| **2** | `Backup.java:437` + `SlotOps.java:170` | `if (!report.startsWith("✅")) return report;` / `a.getString(report.startsWith("✅") ? R.string.restore_done : …)` | 判据的针是 `Backup.java:380` 的 `"✅ 已从快照恢复到槽「"`。⇒ **本地化必须保住 `✅` 前缀，否则「恢复成功」会被显示成「未执行」** |
| **3** | `MapStats.java:67-68 / 752` | `SRC_VANILLA = "原版"`、`SRC_MOD = "模组"`；`if (!SRC_VANILLA.equals(old.source) && old.source.startsWith(SRC_MOD))` | 这不只是文案 —— **它是数据模型的取值**，而且**参与合并判据**（决定"内容补丁"叠不叠）。⇒ 中文常量一旦变成资源字符串，判据必须改成**枚举**，不能改成中文比中文 |
| **4** | `MainActivity.java:1496` | 把 `Versions.conflictPeerDesc()` 的「「A」等 N 个版本」当 `%s` 塞进 `row_conflict_fmt` | **嵌套拼接**：`「A」` 的书名号**含在内层返回值里**（`Versions.java:112-114` 的注释专门解释过为什么）。⇒ 两层都要整句资源化，且**不能各自独立翻**，否则会出现「与"Shares with A"共用此槽」这种中英混排。自检 ⑥c 钉着这个形状 |

**处理原则**：
- **凡是"用中文串做判据"的地方，判据必须换成「错误码 / 枚举 / 布尔标志」**，中文只留在资源里；
- 这条**无论做不做 i18n 都该修** —— 今天任何一次"改个措辞"都会静默破坏它们，而且**没有任何自检会红**；
- 建议加进 §8 的 P0：**一条"禁止用中文串做判据"的自检**（扫 `.contains("中文")` / `.startsWith("中文")`
  / `.equals("中文")`，白名单只允许"自己造的 fixture"）。

我全库扫了一遍判据/改写类调用（`.contains(` / `.startsWith(` / `.equals(` / `.indexOf(` / `.matches(` …）
带中文字面量的地方：**命中 120 处**，其中**绝大多数在 `SelfTest.java`（fixture，无害）**，
**产品代码里真正危险的就是上表这 4 条**（清单见 `.tmp-i18n/cjk-judgements.txt`）。

> ★★ **这一条已经从"人工清单"升级成"构建期门禁"**（P0 落地，规则 `SRC-01`）：
> `tools/i18n-check.py` 会自动扫出**中文 / 状态符号 / 值为文案的常量**三类判据，
> 现在实测 **5 处命中、落在上表那 4 个位置**（`MapStats` 一条判据吃到两个常量，所以是两处）。
> 它顺带还量出第三个常量 **`SRC_PATCH = "地图补丁"`**（`MapStats.java:69`）——
> 它没被判据引用，但会作为参数拼进「技术细节」层的依据句，整句资源化时要一并处理。
> ⇒ **这 5 处曾登记在 `tools/i18n-known.txt` 里；P0.1 修完后台账已清空**（脚本会报 `STALE` 逼你删）。

#### ✅ 修复记录（P0.1，2026-10-04）

| 位置 | 改成了什么 | 依据 / 备注 |
|---|---|---|
| `MapsActivity.java:384` | 改判 **`MapFiles.Result.nameTaken`**（新加的权威标志位，在"目标名已被占"那个分支里置位） | 不用"UI 再去问一次文件系统"，因为结果对象已经知道答案、且没有竞态。邻居 `ModsActivity:311` 用的是 `dest.exists()` 那一套 —— 两种写法都对，这里选前者因为**权威且 diff 最小** |
| `Backup.java:437`、`SlotOps.java:170` | `restore()` 返回 **`Backup.RestoreResult`**（`ok` 布尔 + `report` 文本）；两处改判 **`rr.ok`** | 一根针扎两处，**必须一起改**。`report` 里的 ✅ 前缀保留，但降级成"只是给人看的" |
| `MapStats.java:752` | **直接删掉那个 `if`** —— 它是**空体条件**（体内只有一句注释），**从来没有生效过** | 🔴 这是门禁第一次跑就意外照出来的：一条"拿 `SRC_VANILLA`/`SRC_MOD` 两个中文常量当判据"的条件，其实是个死条件。留着只会让下一个人以为它在起作用；它想表达的意思已改写成一句纯注释 |

**真机验证**（dev 变体 + `dev_m3_selftest` 口，报告 `hub/report-m3.txt` 已拉回留证）：

- **通过 514 项，失败 0 项** —— 与改动前的基线**同一个数**（我只改了判据的取法，没增删断言）。
- 三条被改过的断言逐条对上了：`✅ 恢复返回成功` / `✅ 从迁移后的快照恢复成功` /
  `✅ 其余项照常恢复（rr.ok = true，只有坏的那一项失败）`。
- ★ **最关键的一条负例**：报告原文是 `文件：5 / 6`（坏了一个对象）而 `rr.ok` 仍为 `true` ——
  说明 `ok` 与原来 `startsWith("✅")` 的语义**逐字等价**（"部分成功"仍算成功）。
- `nameTaken` 那条路径由 ⑥ 的两条断言覆盖（`同名 + 不允许覆盖 ⇒ 拒绝，且原文件一个字节没动` /
  `同名 + 明确覆盖 ⇒ 替换成功`）；补丁叠加那一段由 ㉜ 覆盖（改动数 1、元断言判死照旧）。
  ⚠️ **诚实说明**：`MapsActivity:384` 的 **UI 分支本身**没有自动化判据（自检不测 UI 流程），
  它由 javac + 人工复核保证 —— 数据层那一半是真机验过的。
- ⚠️ **一处对研究阶段的更正**：§7 里记的"文档说 514、静态展开 ≈540，对不上"——
  **真机跑出来就是 514**，所以是那次静态展开算错了（它把循环和重复调用估多了），文档的 514 是准的。

**其它验证**：`./build.sh` 的 `[0/4]` 门禁 `error=0 / known=2 / stale=0`；javac 通过（6 个调用点全部改齐）；
测试后设备已**装回产品版**（`run-as: package not debuggable` 为证），当前槽记录仍是 `test`（**没有**重演
第四十七轮那个"当前槽留在已删测试槽上"的坑），自检的测试槽与夹具已自行清理干净。

### 5.3 🔴 结构性障碍：`*Reason()` 系列与"没有 Context 的静态层"（最难的一块，最值得先设计）

第 86 轮刚立的规矩（`AGENTS.md` 第 86 轮续第 5 条）是：
**给用户看的失败原因走 `userReason()` / `metaReason()`，`error` 原文一个字都不改**（落盘报告与自检看它）。

这个设计的两半，一半对 i18n 友好、一半是障碍：

```
                    ┌─ error / metaError 原文 ──── 落盘报告 + 自检 + 「技术细节」弹窗  → 中文，B 类
  原始错误 ─────────┤
                    └─ userReason() / metaReason() ─ 界面第一层弹窗 / 列表行  → 中文，A 类，必须翻
```

障碍在于：**这些文案的产出地是"没有 Context 的静态层"**，这是刻意的设计
（能在 PC 上用产品代码跑验收 —— PC 侧 lab 在**探针工程** `MDT-Android-Dev/_lab/msav/f21/`，
如 `NameAudit.java`，**不在本仓库里**）。⇒ 它们**拿不到 `Context` / `Resources`，不能 `getString()`**。

| 无 Context 的产出地 | A+A′ 处数 | 备注 |
|---|---|---|
| `Mods.Info.metaReason()` / `State` / `Gate` / `checkPackName()` / `Conflict.report()` | **138** | 最大的一块 |
| `MsavMeta` | **40** | 实测 `import` 里**没有一个 `android.*`** |
| `SettingsBin.Result` | **33** | 类注释明确"本类不碰 Context" |
| `MapStats` | **10** | 纯 Java，PC 验收台拿 **460 语料**逐项对照；`MapStatsTable` 是**生成物** |
| `Cas` / `Versions` | 各 8 / 5 | —— |

> ★ **更正一个容易搞错的判断**：`GameSlot` / `Injector` 跑在 `:game` 进程，这**不是**障碍 ——
> 同一个 APK 的资源两个进程都取得到。它们只是"步骤名"要一起资源化
> （`Injector.LaunchError.step` 与 `GameSlot.fail()` 的标题）。
> **真正的瓶颈是"没有 Context 的静态层"，不是进程边界。**

而且 `error` 字段里存的**不是一句干净的话，而是"中文句子 + 拼接的数字"**，例如
（`MsavMeta.java:149 / 155 / 207 / 212`）：

```java
m.error = "格式版本不合理（" + m.version + "）—— 可能不是 .msav，或是我们没见过的版本";
m.error = "meta 块声明了 " + size + " 项，只读到 " + read + " 项（块被截断或损坏）";
```

**这种拼接式句子在别的语言里语序会变，不能直译**，必须变成 `%1$d` 形式的整句资源。
同一个模式在六个文件里重复出现（见 §5.1 的表）：`error` / `metaError` / `r.error` /
`changeDesc` / `problems` / `notes` / `Gate.note` / `State.label` 之类的字段**存的是"中文原句"**。

**两个必须在设计时一起决定的问题：**

1. **「技术细节」第二层会把异常原文放出来**：`SettingsBin.Result.report()` 第 406 行**原样打印 `error`**
   ⇒ 那些"被 `userReason()` 整句替换掉"的 `类名: 消息`（我判 C 的部分）**仍可能出现在技术细节弹窗里**。
   要么接受（技术细节就是给维护者看的），要么 `report()` 改印 `userReason()`。**必须明确选一个。**
2. **`MapStats` 的 `SRC_*` 会成为可见值**：`"原版"` / `"模组"` 作为参数拼进技术细节的
   `原版方块表 N 条` / `地图补丁：改了 N 条` ⇒ 整句资源化时这三个常量**要么一起资源化、要么换成枚举**
   （而且它们**同时还是合并判据**，见 §5.2 第 3 条）。

**推荐解法（保住"纯 Java 可在 PC 上验收"这个性质）**：把"原因"从**字符串**改成**错误码 + 参数**。

```java
// 现在
public String userReason() { ... return "读这个文件的时候出错了"; }
public String error;            // "meta 块声明了 3 项，只读到 1 项（块被截断或损坏）"

// 改成
public enum Reason { UNKNOWN, NOT_MSAV, BAD_FORMAT_VERSION, META_COUNT_MISMATCH, ... }
public Reason reason;  public Object[] reasonArgs;   // 参数照旧是纯数据，PC 上可断言
// UI 层（有 Context 的那一层）：
public static CharSequence text(Context c, Reason r, Object[] args) {
    return c.getString(resOf(r), args);      // 一张 Reason → R.string 的映射表
}
```

好处：① 纯 Java 性质不变，PC 验收照跑；② 自检从"断言中文串"改成"断言错误码"
（**分辨力更强**，而且这正是 §7.3 里那 25 条"级 B"断言的根因）；
③ 参数化后语序问题自动消失；④ **顺手把 §5.2 那四条"中文当判据"一起改成枚举**。

**另一条路（更省事但要接受一个瑕疵）**：照 `MapStatsMods.attrLabels(ctx)` 的既有做法 ——
**由有 Context 的调用方把文案取好，当普通数据传进静态层**。
这条路的成本低得多，但只适合"数量少、结构固定"的场合（属性词只有 7 个）；
`Mods` 那 138 处用它会把参数列表撑爆。⇒ **`Mods`/`MsavMeta`/`SettingsBin` 走错误码，
`MapStats` 的属性词继续走注入。**

代价：`Mods` / `MsavMeta` / `SettingsBin` / `Data` / `Backup` / `Cas` / `Versions` 的调用点要跟着改，
`SelfTest` 里对应的断言也要从"比字符串"改成"比错误码"。**这是 P3 的主要工作量。**

### 5.4 拼接：A 类整体是碎片 + 14 处资源拼接（翻译上下文最容易出错的地方）

**先说更重要的那个**：A 类那 **354 处**里，**绝大多数是 `"槽「" + n + "」已存在"` 型的碎片** ——
也就是说，它们**即便进了资源，也是"半句话"**。按本工程既有的硬规矩
（`AGENTS.md` 第 85 轮：aapt2 连尾随空白都剥 ⇒ **不许用"前缀/尾巴串 + 拼接"留空白，
一律整句资源 + `%1$s`**），这 354 处要**重写成约 200 条整句资源 + 参数**。
⇒ **"搬进资源"和"重写成整句"是同一件事，不能分两步做**（只搬不重写 = 翻译出来语序全是错的）。

另外还有 **14 处**把资源串与其它东西拼起来的地方 —— 这些是"整句资源"纪律的**残留**，
译者拿到的是一条**碎片**而不是一句完整的话，最容易翻错：

| 位置 | 拼的是什么 | 风险 |
|---|---|---|
| `MainActivity.java:1585` | `name + getString(R.string.import_progress_suffix)` | **碎片**（以 `\n` 开头的后缀） |
| `MainActivity.java:1398` | `plain + getString(R.string.row_upstream_fmt, note)` | **碎片**（`（基于 %1$s）`） |
| `MainActivity.java:1836 / 1856 / 1859` | `head + getString(detail_slot_hint / detail_installed_note / detail_probing)` | 碎片 |
| `MainActivity.java:1776`、`ModsActivity.java:375 / 524` | `"  " + getString(R.string.slot_entry_fmt, ...)` | Java 里的 `"  "`（aapt2 不管 Java，所以合法）但**布局假设写死在代码里** |
| `MainActivity.java:1138` | `getString(selftest_result) + " · " + ...` | Java 里写死 `·` 分隔符 |
| `ModsActivity.java:666-669` | `(tick) + getString(mods_filter_*)` | 前缀勾选记号，碎片 |
| `ModsActivity.java:728` | `+ getString(R.string.mods_sub_fmt, ...)` | 碎片 |

另外 `slot_current_suffix`（`[当前]`）也是**贴在槽名后面的碎片**。
⇒ 这些要么改成**整句资源 + `%1$s`**，要么在资源文件里给注释（说明它会贴在什么后面）。
**没有第三种选择** —— 直接交给译者必然翻错位置。

### 5.5 三个代表性案例（都是"改起来要想一下"的类型）

**案例 1 —— `Versions.conflictPeerDesc()`（纯函数，但中文 + 标点位置都在里面）**

```java
// Versions.java:120-122   ★ 静态纯函数，无 Context
public static String conflictPeerDesc(String firstName, int total) {
    return "「" + firstName + "」" + (total > 2 ? "等 " + (total - 1) + " 个版本" : "");
}
```

它自己的注释就写着"⚠️ 书名号**含在返回值里**（不在 `row_conflict_fmt` 里）—— 否则
『等 N 个版本』会跑到书名号**外面**去"。这正是 i18n 最典型的坑：
**中文书名号 + 语序 都被硬编码进了函数**。别的语言里可能是
`Shares this slot with %1$s and %2$d other versions` —— 整句必须搬到**一条**资源里，
变成 `row_conflict_peer_fmt`（`%1$s` / `%2$d`），函数只负责算 `total`。
（自检 ⑥c 有 **6 条**断言钉在这个函数的输出上，要一起改。）

**案例 2 —— `MapStatsMods.attrLabels(ctx)`（把资源当"数据"传给纯 Java 层）**

这是个**做对了**的模式：属性词（含水/含油/…）走 `strings.xml`，由有 Context 的那一层取好、
当**普通 Map** 传进纯 Java 的 `BundleNames`。⇒ 换语言时**这一层自动跟着走**，
而且自检可以在 PC 上用假 Map 跑。
**唯一的缺口**是自检没覆盖它（§7.5）⇒ 英文下会变而没人知道。这是"结构对、判据缺"的典型。

**案例 3 —— `Injector.java` 的 19 条 / `Compat.java` 的 22 条（全是 B 类）**

逐条看下来，`Injector` 的 19 条**全部**是 `log.append(...)`（启动管线日志），
`Compat` 的 22 条**全部**是兼容性探针报告（`probeReport`）的 `append`。
⇒ 它们**不是** A 类，属于"用户能在运行日志页看到"的 B 类。
**这类最容易被误判成"必须翻"而白白翻译几百句内部取证文本** ——
正确的做法是先按 `strings.xml:5` 的既有纪律**明确划界**，再决定要不要翻。

> ⚠️ 反过来说，`Backup.java` 的 25 条是**混着的**：既有 `return "「" + dst + "」是当前槽，不能作为克隆的目标。"`
> （A 类，直接弹给用户），也有 `sb.append("    快照：")` （B 类，落盘报告）。
> ⇒ **必须逐条看，不能按文件一刀切。**

### 5.6 建门禁时量出来的三处既有缺陷（与语言无关，但会被 i18n 放大）

> 这一节的三条**不是**读代码读出来的，是 **P0 的门禁第一次跑起来时自己报出来的**
> （见 §8 的 P0 落地说明）—— 正好演示了"判据对 ≠ 清单全"。

1. **`AndroidManifest.xml:125`**：`SlotActivity` 的 label 用了**带占位符**的 `slot_page_title_fmt`
   ⇒ 最近任务显示字面 `槽「%1$s」`。由 `RES-08` 报出。
2. **`strings.xml:375` `export_pick_head_fmt`**：`槽「%1$s」· 共 %2$d 份` —— **分隔符两侧都没空格**。
   同文件的 `snapshot_entry_fmt`（`%1$d 个文件 · %2$s`）与 `export_item_fmt`（`%1$s  ·  %2$s`）
   都是带空格的 ⇒ **这是漏网，不是口径不同**。由 `RES-09` 报出。
   ★ 工程自己的自检里**有**这条规则（`SelfTest.sepsOk`），但它只对**抽样**的几个串跑 ——
   `strings.xml` 里 53 条含 `·`，这条漏网了几十轮都没人发现。**这就是"构建期全量跑一遍"的价值。**
3. **`MsavMeta.java:366-367`**：`SimpleDateFormat` 用 `Locale.getDefault()`，而工程里其它 8 处
   日期/数字格式化**一律 `Locale.US`**。这是**唯一一处不一致** ——
   在阿拉伯语等使用本地数字的设备上，它会输出本地数字而其它数字是西文数字。
   i18n 时要统一：要么全 `Locale.US`（推荐，与既有做法一致），要么全本地化。
   （这条门禁查不出来，是逐行读出来的。）

⚠️ **顺带一个关于"规则"本身的发现**：`SelfTest.sepsOk` 的判据是"每个 `·` 两侧都必须是空格"，
**照搬到全部 559 条资源上会误杀 `restore_confirm_msg_fmt`** —— 它里面是
`…\n\n· 只覆盖备份里有的文件…\n· 写之前先检查内容有没有坏`，那个 `·` 是**行首项目符号**，
左边只能是换行。⇒ 门禁里的 `RES-09` 比 `sepsOk` 精确一点（**行首 + 右边是空格 = 项目符号，放行**），
并且专门配了一条**阴性元断言**钉住这个差别。**"规则能跑"不等于"规则能照搬"。**

---

## 6. 布局与视觉：抗长译文（逐属性审计结果）

方法：`python` XML 解析 + 逐 dp 算式；基准屏幕 **1224px @520dpi**（density 3.25 ⇒ 短边 **376.6 dp**
—— 与 `app/res/layout/activity_main.xml` / `item_version.xml` 里记的是同一块屏）；
`page_pad 16 / row_icon_box 34 / row_icon 22 / row_chevron 18`。

### 6.1 结论：布局的可翻译性非常好

| 检查项 | 结果 |
|---|---|
| TextView/Button 写死 dp 宽度 | **0 处** |
| `maxWidth` / `minWidth` / `minHeight` | 各 **0 处** |
| `android:text` / `hint` / `contentDescription` 硬编码 | **0 处** |
| 方向写死（`marginLeft` 等全系列） | **0 处 / 0 个文件** |
| `start/end` 系列 | **131 处 / 全 19 个布局** |
| `<Button>` 控件 | **0 个**（按钮都是挂背景的 TextView） |

### 6.2 只有 2 处真风险 + 若干处余量偏紧

| 位置 | 问题 | 量化 |
|---|---|---|
| `item_mod.xml:46-75`（**最危险**） | 右侧"状态徽标"是**译文**且**没有 `maxLines`/`ellipsize`**；中文「读不出说明文件」约 93dp，德/俄文约 122dp | `mod_title` 从 **181.6 dp 压到 ≈152.6 dp**（15sp ≈ 20 字符/行 × 2 行） |
| `item_slot.xml:81-82` | `slot_meta` `maxLines=3` 余量最小 | 德文 `Automatische Sicherung: nach 30 Min. · max. 10 Kopien`（≈325dp）折 2 行 + 第 1 行 = **正好顶满**，再折就丢掉"最多留 N 份" |
| `item_stat.xml:37` | `singleLine` **但没 `ellipsize`** ⇒ **硬裁字**（全工程唯一一个非输入框的此类） | 俄文 `stat_card_title` 215.6 → 182.6 dp |
| `item_stat.xml:16` | 横向并排的 count 无 `ellipsize` | —— |
| `row_action.xml:47-48` | 27 个入口行共用，`singleLine+ellipsize` | 可用 244.6~248.6 dp ≈ 33 拉丁字符；最长 `act_*` 标题约 23 字符 ⇒ **余量约 10 字符** |

**已确认安全的**：`item_version` 的 `ver_title` ≈ **170.6 dp**（与该文件注释里的真机实测
569px = 175dp **吻合到 2.5%**，可作为回归基准）；`ver_sub` 的 `row_sub_fmt` 是
`"%1$s · %2$s"`（包名 · 体积）**本身就无中文** ⇒ 译文不影响。
7 处"横向并排多 TextView"全是"主文本 `0dp+weight=1` + 副文本 `wrap_content`"的正确结构。

### 6.3 要改的清单（**11 个属性 + 3 行 manifest + 2 个 drawable 属性**）

按工作量排序：

**第 0 档（3 处，收益最大）**
1. `AndroidManifest.xml` 加 `android:supportsRtl="true"`（若决定不做 RTL 可跳过，见 §4.4）
2. `ic_chevron.xml` / `ic_backup.xml` 加 `android:autoMirrored="true"`
3. `SlotActivity` 的 manifest label 换成无占位符的串（§3.3 二）

**第 1 档（会丢字，必须改）**
4. `item_slot.xml:81` `maxLines` 3 → 4
5. `item_stat.xml:37` 补 `ellipsize="end"`
6. `item_mod.xml:64-75` 徽标加 `maxLines="1"` + `ellipsize="end"` + 合理 `maxWidth`
7. `item_mod.xml:57` 评估 `maxLines` 2 → 3

**第 2 档（逐语言真机验收）**：`item_version`（作回归基准）、`row_action`（27 行）、日志卡标题行。

---

## 7. 自检（`SelfTest`）的耦合（**逐条审计，这一节是本次研究最有价值的发现**）

`SelfTest.java` 是 **4612 行**、`ok(stat, L, cond, msg)` 调用点 **510 处**，
**真机实测 514 通过 / 0 失败**（2026-10-04 复跑，含 P0.1 改动后）。

> ⚠️ **一处研究阶段的自我更正**：本文初稿曾说"文档记的 514 与静态展开 ≈540 对不上"，
> 并据此怀疑文档陈旧。**P0.1 真机复跑证伪了它** —— 跑出来就是 **514**。
> ⇒ 那次"静态展开 ≈540"是**估错了**（把循环体与重复调用摊多了）。**文档的 514 是准的，初稿的怀疑是错的。**
> 教训与 §5.1 的"口径"那条同族：**静态数出来的东西，别当实测用。**

它同时含中文字面量 **1268 处**（去重 1106）—— 这是"改默认语言"时
**最容易连带崩掉、也最容易悄悄失去分辨力**的一块。

### 7.1 ★ 最重要的时间性判断：风险不在今天，在「加 `values-en/` 的那一刻」

| 情形 | 自检会怎样 |
|---|---|
| **P1 之前**（`app/res/` 只有 `values/` + `values-night/`，默认就是中文） | **不会变**。任何 locale 都回落默认 `values/` = 中文，受影响的断言**照样全绿** |
| **P1-A 之后**（`values/` = 英文、`values-zh/` = 中文） | **中文设备仍全绿**（走 `values-zh`）；**英文环境立刻 15 条判红** —— 见下面 §7.3 的**实测** |

⇒ **这两件事必须一起做**：改默认语言的同一次改动里，把自检的判据形态也换掉。

> ★★ **这一段原先是"预测"，2026-10-04 已被实测取代**（P1-A 落地后，
> 用 `adb shell cmd locale set-app-locales io.mdt.launcher --locales en` 把本应用切成英文，
> 再跑 `dev_m3_selftest`）：
>
> | 环境 | 结果 |
> |---|---|
> | 中文（`values-zh`） | **514 通过 / 0 失败** |
> | 英文（`values/`） | **499 通过 / 15 失败** |
>
> ⇒ **不是"一大批"、也不是"只在英文设备上才会发生"** —— 它是**可切换、可复现、可量化**的 15 条。
> 而且这条路子说明：**P2 的应用内语言切换一旦做出来，这 15 条就会在用户手里现形**，
> 所以 P1-B 不是可选项。

### 7.2 自检的 Context 与语言跟随性（已定案）

| 事实 | 证据 |
|---|---|
| 自检跑在**主进程** | `MainActivity` 在清单里**没有** `android:process`；`SelfTest.java:2691` 自己断言了 `Util.isMainProcess(ctx)` |
| 传进去的是 **Activity 本身**（不是 Application，也不是包装过的 Context） | `MainActivity.java:1132` `SelfTest.runM3(MainActivity.this)` |
| `ThemeMode.wrap` **不碰语言**（只改 `uiMode` 的 night 位，locale 原样保留） | `ThemeMode.java:59-66`；全工程 `createConfigurationContext` **只有这一处** |
| `MainActivity` 没有 `configChanges="locale\|layoutDirection"` ⇒ 语言切换会**重建 Activity** ⇒ 新 Context 拿新语言 | `AndroidManifest.xml`（只有游戏 Activity 声明了 `configChanges`） |

### 7.3 受影响断言的确切数字（**实测 15 条红**）

`ok()` 条件里写了中文的断言 = **75 条**（+1 条判据函数绑死中文标记）= **76 条 / 510 = 14.9%**。按"期望值是谁产出的"分级：

| 级 | 含义 | 条数 | 后果 |
|---|---|---|---|
| **A** | 期望值来自 `R.string` 资源 | **14** | **切到英文后立刻判红** |
| **B** | 期望值来自**启动器 Java 硬编码中文** | **25**（其中真正用户可见的约 18） | 只改资源**不会红**；一旦把 Java 字面量也搬进资源才红 |
| **C** | 期望值来自**测试自造的 fixture** | **25** | 与语言无关，永恒安全 |
| **D** | **游戏侧**中文（bundle 译名 / `（墙）`） | **10** | 与启动器 UI 语言是两回事，见 §4.3 |
| **E** | 期望值是**纯格式串**（`%1$s · %2$s`） | **1** | 安全 |

> 🔴 **上表是静态分析；下面是实测，两者有出入，以实测为准。**
> 我把应用切成英文跑了一遍自检，报告里逐条列出了失败项 —— **15 条**，
> 而且其中 **2 条被我静态分析归错了级**（`hasCjkSpace` 那一族我判"变恒真"，实测是**判红**）。
> **教训与 §5.1 的"口径"、§7 的"514 vs 540"完全同族：静态数出来/静态推断出来的东西，别当实测用。**

**实测的 15 条红**（`hub/report-m3.txt`，英文环境；同一份也拉回在
`.tmp-i18n/report-m3-en.txt`）：

| # | 断言（报告原文摘要） | 静态分析当时怎么归的 |
|---|---|---|
| 1 | `2 个版本：中文与插入值之间无多余空格` | ❌ 归成"变恒真"，**实际判红** |
| 2 | `3 个版本：「等 N 个版本」两侧紧贴中文` | 级 B（`conflictPeerDesc` 的 Java 中文 vs 英文资源） |
| 3 | `★ 列表行实拼 ⇒ MindustryX  2026.09.X37 (based on 160.1)` | 级 A |
| 4 | `★ 去掉空白后恰好是 [当前]（实得「[current]」）` | 级 A |
| 5 | `★ 实拼（当前槽）三段边界都有空白 ⇒ default  [current]  52 files · 208.3 KB` | 级 A |
| 6 | `★ 实拼（非当前槽）⇒ test  0 files · 0 B` | 级 A |
| 7 | `★ 坏档文案：7 saves · ⚠ 2 unreadable` | 级 A |
| 8 | `★ 坏档文案（带最近那份）：… latest: 好档 …` | 级 A |
| 9 | `★ 导入项副标题逐字：Imported · official-159.apk · 74.7 MB` | 级 A |
| 10 | `★ 日志/模组三条整句：游戏日志 not generated yet ｜ … duplicate name` | 级 A |
| 11 | `★ 启停确认框按一个参数实拼：…The game is not running (safe to rewrite)` | 级 A |
| 12 | `★ 启停结果弹窗三条整句实拼：Turned 蓝钢拓展 关闭. ｜ …` | 级 A |
| 13 | `★ 同名存档覆盖确认两句实拼：Slot default already has a 困难模式.msav. …` | 级 A |
| 14 | `★ 启动进度提示实拼：MindustryX  2026.09.X37 preparing the save slot · checking this package…` | 级 A |
| 15 | `★ 地图页空态实拼：Slot default has no maps yet. …` | 级 A |

★ 注意第 10、12、13、14 条的样子：**中英混排**（`游戏日志 not generated yet`、
`Turned 蓝钢拓展 关闭.`）—— 因为期望值里既有资源（已英文化）又有**测试自己传进去的中文 fixture**。
这正是"级 C fixture 是安全的、级 A 资源是危险的"这条分界在真实输出里的样子。

**P1-B 要做的两件事**（顺序不能反）：
1. 把这 15 条里的**资源侧期望值**改成"与资源同源"（同一次 `ctx.getString` 取），
   或者改成**结构判据**（`·` 两侧空格、参数个数、首尾空白 —— 与语言无关）；
   ⚠️ 改完必须补**元断言**（故意把一条资源改错，要求判死），否则就变成自证同义反复。
2. 给自检加**双跑**：同一轮里用 `zh` 与 `en` 两个 `createConfigurationContext` 各跑一遍，**两遍都要求全绿**。
   `runM3(Context)` 的 Context 本来就是**方法入参**（`SelfTest.java:120`），改造点很集中。
   双跑还有个附带好处：**能自动发现"某一遍在真空跑"**（两遍通过数完全相同 ⇒ 值得怀疑）。

**14 条会红的**（期望值经 `ctx.getString` 取得，而资源现在只有中文）：

| 行号 | 测什么 | 判据里的中文 |
|---|---|---|
| `:1180` | 版本行带基座备注的**逐字**拼装 | `（基于 160.1）` |
| `:1220` / `:1226` / `:1229` | 槽选项三段边界空白 / 当前标记 | `[当前]` / `0 个文件` |
| `:3244` / `:3247` | 槽页副标题"有几份读不出来" | `读不出来` / `最近：` / ` · ⚠ ` |
| `:3280` | 导入项副标题逐字 | `已导入 · official-159.apk · 74.7 MB` |
| `:3284` | 日志/模组三条整句逐字 | `游戏日志 还没生成` / `游戏日志 · 无` / `tmi · 重名` |
| `:3290` | "标签与话之间的空格还在" | **拿中文末字 `志 ` 当锚点 `indexOf`** ⇒ ⚠️ **这条任何语言下都是坏判据** |
| `:3306` / `:3311` | 启停确认框 / 结果弹窗**整句等于** | `游戏没在运行` / `已把「蓝钢拓展」关闭。` … |
| `:3316` | 同名存档覆盖确认 | `找不回来` |
| `:3320` / `:3323` | 启动进度 / 地图页空态 | `正在准备存档槽` / `给某个版本分配这个槽` |

**7 条会"变恒真、失去分辨力"（报告照样全绿，但已不检查任何东西 —— 这一类最危险）**：

`:3249`（负向 `!contains("读不出来")`）、`:940` `:941`（`!hasCjkSpace(...)`，而 `hasCjkSpace` 的白名单
（` 「` / `」 ` / ` 共` / `版本 `）是**中文专属** ⇒ 英文下恒 `false`）、
`:1233` `:1235` `:945` `:947`（四条**元断言**，守的规则在英文下已不存在 ⇒ 元断对对子失效）。

### 7.4 已知纪律的机器判据对账 —— **四条纪律完全没有判据**

| 纪律（`AGENTS.md` §五） | 有没有机器判据 |
|---|---|
| ① **每个 `·` 两侧必须是空格 + 元断言** | ✅ **有，而且语言无关**（`sepsOk()` `SelfTest.java:3332-3343`，元断言 `:3293` 拿真机坏串喂进去要求判死）。**这是全工程最抗 i18n 的一条，应当当作模板推广** |
| ①′ 中文与插入值之间不许有空格 | ⚠️ 有，但**判据本身中文绑定**（`hasCjkSpace()` `:1316-1319`）⇒ 英文下正向两条变恒真 |
| ② **不许出现 markdown 标记**（`**` / 反引号 / `#`） | ❌ **完全没有机器判据** |
| ③ **带占位符的文案按真参数实拼一遍** | ⚠️ **有，但是抽样**：**22 / 204 = 10.8%**，而且**没有元断言**保证"新加的带参文案必须进这张表" |
| ④ 不许出现术语 / 类名 / 文件名 | ❌ **没有机器判据**（只有 `:3628` / `:3633` 两个个案负向） |
| ⑤ 不许自问自答 | ❌ **完全没有** |
| ⑥ 不许开发者自我说明 | ❌ **完全没有** |

⇒ **这是本次审计里"纪律写了但没人守"的最大缺口**，而且它**正好是 i18n 最需要的那几条**
（换成英文后，措辞、术语、markdown 都要重新守一遍，而守的人不可能逐条背）。

**资源覆盖率也就是分母**：`SelfTest` 只碰了 **27 / 559 = 4.8%** 的资源，
带参资源只实拼过 **10.8%**。

### 7.5 一个覆盖盲区（现在就存在）

`MapStatsMods.attrLabels(ctx)`（`MapStatsMods.java:205-215`）把 `stats_attr_water`(含水) / `_oil`(含油) /
`_heat` / `_spores` / `_steam` / `_light` / `_sand` **7 条 `strings.xml` 资源**当属性词传给 `BundleNames`
（调用点 `MapDetailActivity.java:128`、`MainActivity.java:555`）。
而自检走的是 `fakeNames()`（`SelfTest.java:4284-4307`）⇒ **这 7 条资源在自检里零覆盖**。
英文下地图详情页的「含水/含油」会变，**不会有人知道**。

### 7.6 游戏侧那 10 条：**与启动器 UI 语言无关**

`:4154 :4156 :4158 :4160 :4162 :4181 :4183 :4185 :4217 :4222` 期望值**全部来自测试现场造的 fixture**
（`SelfTest.java:4132-4140` 造 `bundle_zh_CN.properties`、`:4146-4152` 现场 zip 出假 APK、
`:4196-4212` 现场写 `localizedName`），**没有一条读真实版本 APK 的 bundle**。

- 只新增 `values-en/`、不动 `BundleNames` ⇒ 这 10 条**一条都不受影响**；
- 一旦按 §4.3 把 `bundle_zh_CN` 改成"跟随 locale" ⇒ **会挂 8 条**
  （`:4162` 的 `（墙）` 默认值取不到 + 7 条 fixture 文件名写的就是 `bundle_zh_CN.properties`，
  改名后 fixture 根本不被读，期望值全变空串）。**P4 要和这 8 条一起改。**

### 7.7 建议做法（按代价从低到高）

| 方案 | 做法 | 代价 | 残留风险 |
|---|---|---|---|
| **① 判据改为"与资源同源比对"** | 把 14 条里右手的死字面量换成**同一时刻 `ctx.getString(同一条资源, 同样的参数)`**（多数行已经有一半是这么写的）；`:1220` 改成比对资源自身形态；`:3290` 的 `indexOf("志 ")` **必须删掉** | 小：约 14 处编辑，**不动资源、不动产品代码** | 会变成**自证同义反复**（左右同源 ⇒ 恒真）⇒ **必须同时补元断言**：手工把一条资源故意改错，要求判死（工程已有 `:3293` 这个套路） |
| **② 判据改为"结构判据"（语言无关化）** | 不比对具体文案，只比对结构与参数：`·` 两侧空格（`sepsOk` 已是这样，直接推广）、占位符个数与顺序、首尾空白、`%n$` 与实参个数匹配、**markdown 黑名单**、**术语黑名单** | 中：写 3~4 个新判据函数 + 逐条替换 | 抓不到"文案写错了"，但**抗 i18n，且是唯一在所有语言下都有效的形态**；**顺手把 §7.4 那四条没判据的纪律补上** |
| **③ 把资源清单当唯一真源，自检遍历** | 自检不手写期望值，把 `R.string.*` **全量**过一遍（实拼 + 结构判据 + 黑名单） | 大：无 Gradle ⇒ 要在 `build.sh` 里加一步 `aapt2 dump resources` 生成清单，或让自检读 `resources.arsc` | **覆盖率 4.8% → 接近 100%**，并根治"新加的带参文案没人管"。**唯一能根治的方案** |
| **④ 给自检装"语言锁"** | 强制在固定 locale（`zh_CN`）下取资源再跑现有断言 | 小 | ⚠️ **只是把问题藏起来**：产品在英文下仍可能崩（`MissingFormatArgumentException` 这类），自检不再反映真机。**只能当过渡** |
| **⑤ 双跑（建议与 ② 组合）** | 同一轮里跑两遍：一遍 `zh_CN` 的 Configuration、一遍 `en` 的，**两遍都要求全绿** | 中：`runM3(Context)` 的 Context 已经是**方法入参**（`SelfTest.java:120`）⇒ 改造点非常集中 | 覆盖最真实；还能**顺带把"英文下变恒真"暴露出来** —— 可以断言"两遍的通过数不同 ⇒ 确实有事发生" |

**建议落地顺序**：先修 §7.3 里那几条"坏判据"（`:3290` 的 `indexOf("志 ")`、`hasCjkSpace` 的元断对对子）
—— **无论做不做 i18n 都该修**；再做 ②（顺带补 ②④⑤⑥ 四条纪律的判据）；然后**加 `values-en/` 的同时做 ⑤**。

### 7.8 顺带发现的两个"自检可信度"问题（与 i18n 无关）

1. ✅ **已复核为真**：`SelfTest.java:170-171` 的 `modsFilter(L, stat);` **连着写了两遍**（无条件、非分支），
   而 ⑲ 的注释只写了一行 ⇒ 那一组断言被**重复计入**，通过数里含它们两遍。
   ⚠️ **但不要顺手删**：文档与 `AGENTS.md` 记的基线就是 **514**（实测），删掉一行会让基线变成 ~504，
   得**同时**改 `AGENTS.md` / REF 里的数字。⇒ 这是个"决定 + 同步更新基线"的动作，不是清扫。
   （顺带：这一条也说明 514 里天然含了这组重复 —— 与"514 是准的"并不矛盾。）
2. `SelfTest.PAGES`（`SelfTest.java:111-114`）这类"名单"自己注释明写"漏了不会有任何症状"
   ⇒ **新增的 `values-en/strings.xml` 也应当有一条"两份资源的 key 集合必须相等"的自检**
   （否则漏译一条在英文下**静默回落中文**，没有任何症状）。
   ✅ **构建期那一半已在 P0 落地**（`RES-01` / `RES-07`，见 §8）；设备上那一半留到 P3。

### 7.9 数字格式化也要收口（不是自检问题，但同属"语言相关的不一致"）

`MsavMeta.java:367` 是全工程唯一一处用 `Locale.getDefault()` 做格式化；
其余 8 处一律 `Locale.US` / `Locale.ROOT`（`Backup` / `LogActivity` / `MapFiles` /
`SlotIo` / `SettingsBin` / `Util.formatSize` / `MapStatsMods.num` / `Injector`）。
i18n 时**必须明确选一种**并统一 —— 否则同一个界面上会出现两套数字格式
（阿拉伯语等使用本地数字的设备上尤其明显）。

---

## 8. 分期方案与工作量

> 估工以"熟悉本工程的人"为单位，**不含翻译本身的等待时间**。
> 559 条短串 ≈ 4000 中文字 ≈ 2500 英文词，专业译者约 1~2 天；社区众包另算。

| 期 | 内容 | 产出 | 判据 | 估工 |
|---|---|---|---|---|
| **P0 判据先立** ✅ **已落地 2026-10-04** | `tools/i18n-check.py`：`RES-01`~`RES-09` + `SRC-01` 共 10 条规则；`--selftest` 20 条元断言（12 阳性要求判死 / 6 阴性要求放过 / 2 台账）；`tools/i18n-known.txt` 已知缺陷台账（**修好忘删会报 STALE 并失败**）；挂进 `build.sh` 的 **`[0/4]`**，先证尺子再量 | 一个脚本 + 一份台账 + 一道门 | **元断言 20/20 通过**；实测把 `values-en/` 里"少一个 `%2$s`"和"译者编的 key"两条都判死，并且**构建在 `[0/4]` 就中止、到不了 aapt2** | 0.5 天（实际约半天） |
| **P0.1 修 5 处"中文当判据"** ✅ **已落地 2026-10-04** | `MapsActivity:384` → `MapFiles.Result.nameTaken`（新权威标志）；`Backup:437` + `SlotOps:170` → `Backup.RestoreResult.ok`（新结构体）；`MapStats:752` → **发现是空体死条件，直接删**；台账清空 | 消除"一翻译就静默失效" | **真机自检 514 通过 / 0 失败**（与改动前同数）；最关键负例 `文件：5 / 6` 而 `rr.ok=true` ⇒ 语义逐字等价；⑥ 与 ㉜ 分别覆盖另两处改动 | 0.5 天（实际约 1 小时 + 一轮真机） |
| **P1-A 默认语言翻英** ✅ **已落地 2026-10-04** | `git mv app/res/values/strings.xml → app/res/values-zh/`；新建 `app/res/values/strings.xml` = **英译全 560 条**（**默认目录必须有全部 key**，见 §2.4）。用一次性生成器逐行替换 ⇒ 键集/顺序/注释/缩进/转义风格全保真 | 英文本体 | 门禁一次通过（键集 + 参数索引 + 空白 + `·` + `\u0020`）；`aapt2 dump badging` 出 `locales: '--_--' 'zh'` 与 `application-label-zh`；**真机把应用切成英文，界面真的是英文**；中文设备自检 **514/0** | 1 天（实际约 2 小时） |
| **P1-B 自检语言无关化** 🔜 **下一步** | 修 §7.3 实测的 **15 条红**（改成与资源同源 / 结构判据 + 补元断言）；给自检加 **zh/en 双跑** | 两套语言下自检都有意义 | **两遍都要求全绿**；并断言"两遍通过数不同 ⇒ 确实有事发生"（防某一遍真空跑） | 1~2 天 |
| **P2 应用内语言切换** ✅ **已落地 2026-10-04** | `LocaleMode` + `Config.app_language` + 设置页一行（三项：跟随系统 / English / 简体中文）；`BaseActivity` **合成一次** Configuration 包装 + 快照扩到 (深浅色, 语言)；`SelfTest` 把自检语言**钉死**在 zh | 可切换 | 真机逐条走过 UI：选中即整页切换 / 杀进程重启仍生效 / **英文界面下自检仍 514-0** / 切回跟随系统回中文；`grep Locale.setDefault` 只命中注释 | 1 天（实际约 3 小时） |
| **P3 硬编码迁资源** | ① **A 305 条 + A′ 40 条**进资源，并**重写成约 200 条整句 + `%1$s`**（§5.4）② `Mods`/`MsavMeta`/`SettingsBin`/`Cas`/`Versions` 改成"错误码 + 参数"（§5.3）③ **修掉 §5.2 那 4 处"中文当判据"**（换成枚举/布尔标志）④ 决定 `report()` 与 B 类那 268 处要不要翻 ⑤ `SelfTest` 的 14 条红 + 7 条变恒真一起改（§7.7 的做法 ②+⑤） | 完全可翻译 | 自检两遍（zh / en）**都全绿** + 新增"两份资源 key 集合相等""结构判据""禁止用中文串做判据"的元断言 | 5~8 天 |
| **P4 内容译名跟上** | `BundleNames` / `MapStats` 的语言层文件名不再写死 `zh_CN`；从槽的 `settings.bin` 读 `locale`；`（墙）` 进资源 | 地图页跟着语言走 | PC 侧 `MDT-Android-Dev/_lab/msav/f21/NameAudit.java` 回归 + `SelfTest` 那 8 条 fixture 一起改名（§7.6）+ 真机中/英地图页对照 | 1 天 |
| **P5 仓库/发布国际化** | 英文 `README.md`（中文挪 `README.zh-CN.md`）、`app_name` 英文、Release notes | 面向国际用户 | —— | 0.5 天 |

**最小可用路径 = P0 + P1 + P2 ≈ 2.5~4 人天 + 翻译**。
P3 是长尾：不做的话，能翻译的部分是 559 条资源 + 全部布局，
**但 344 条（A 305 + A′ 40）用户可见中文仍是中文** ——
它们分布在"错误提示 / 失败原因 / 列表行"这类**不常看但一定会看到**的地方。

**建议先只带 `en` + `zh`（+ 可选 `zh-TW`：游戏有 `bundle_zh_TW`）**，
其余语言等社区贡献 —— 因为 §2.5 的 11 KB/语言 不是问题，**维护 559×N 的漂移才是问题**。
✅ P1-A 落地的就是 **`en`（默认）+ `zh`（`values-zh/`）** 这一对。

### P1-A 落地记录（2026-10-04）

| 交付物 | 说明 |
|---|---|
| `app/res/values/strings.xml` | **英文，560 条 —— 新的默认语言** |
| `app/res/values-zh/strings.xml` | 中文（原文件 `git mv` 过去，历史不断） |
| `.tmp-i18n/en{1,2,3}.py` + `gen-en.py` | 一次性生成器（**不入库**，`.tmp*` 已忽略）：逐行替换值、注释与排版一字不动 |

**为什么用生成器而不是手写整个文件**：默认目录**少一条 key，aapt2 会静默移除它**
（§2.4 实测）⇒ 逐行替换能保证"键集、顺序、注释、缩进、转义风格"全保真，
不会因为手抄漏一条而静默出事。生成器自带生成前/生成后的机械校验（键集、参数索引、空白、
`·`、`\u0020` 白名单、生成结果里不许剩 CJK、`<string` 数量必须相等）。

**五道验证**：

1. **门禁一次通过** —— `default values -> 560 keys` / `locales values-zh` / `error=0 known=0 stale=0`。
   这一次跑，`RES-01`（语言目录独有 key）与 `RES-02`（参数索引）是**真的在起作用**，
   不再是"没有语言目录所以空跑"。
2. **产物层**：`aapt2 dump badging` → `locales: '--_--' 'zh'`、`application-label:'MDT Launcher'`、
   `application-label-zh:'MDT 启动器'`。
3. **`\u0020` 真的活下来了**（这是 aapt2 最容易吃掉的东西）：
   `row_upstream_fmt` = `" (based on %1$s)"`、`slot_current_suffix` = `"  [current]"`、
   `mods_kind_s_plus_res` = `" + resources"`。
4. **真机 · 中文环境**：自检 **514 通过 / 0 失败**（走 `values-zh`，与改前同数）。
5. **真机 · 英文环境**：`adb shell cmd locale set-app-locales io.mdt.launcher --locales en`
   ⇒ 界面真的变英文（`3 versions · current slot test` / `Continue · Mindustry  159.7` / `Import APK`），
   **` · ` 两侧的空格也对**。

> ★ **顺手得到的一个副产品**：切到英文再跑自检 ⇒ **499 通过 / 15 失败** ——
> 把 §7.3 那条"加语言目录后会有多少条红"从**预测**变成了**实测**，而且推翻了我自己的 2 处静态判断。
> 这条实测就是 **P1-B 的待办清单**（见 §7.3）。
>
> ⚠️ **一处 `\u0020` 白名单的扩展**：`row_upstream_fmt`（贴在被比较的版本号后面的**后缀碎片**）
> 中文用全角 `（` 自带视觉间距，英文的 `(` 不加空格会跟版本号粘在一起 ⇒ 给它加了前导 `\u0020`
> 并**显式加进 `tools/i18n-check.py` 的 `U0020_WHITELIST`**（加白名单 = 一次有意识的决定，
> 不是消音器；迟早该改成整句资源，那时要撤掉这条）。
>
> ⚠️ **P1-A 与 P1-B 之间有一个已知窗口**：现在默认语言是英文，而**英文环境下自检有 15 条红**。
> 中文设备不受影响（本项目自己的设备就是），但**任何人在英文环境跑自检都会看到红**。
> ⇒ P1-B 应当紧随其后，**不要在这个窗口里发版**。

### P2 落地记录（2026-10-04）：应用内语言设置

| 交付物 | 说明 |
|---|---|
| `LocaleMode.java` | 新增。**只改 `Configuration` 的 locale 位**，与 `ThemeMode` 在 `BaseActivity` 里**合成一次**包装（不是套两层） |
| `Config.appLanguage()` | `config.json` 的 `app_language`（空串 = 跟随系统），沿用"坏值退默认 + WARNING 点名键名" |
| `SettingsActivity` + `activity_settings.xml` + `ic_language.xml` | 设置页第二行「语言 / Language」，紧跟深浅色（这两项是在同一个 Configuration 上生效的） |
| `R.array/app_languages` + `R.array/app_language_names` + `R.string.app_default_language` | 语言名单 / 语言**用自己的语言写的名字** / 默认目录是哪门语言（都 `translatable="false"`） |
| `SelfTest.runM3` | 一进来就 `LocaleMode.force(passed, SELFTEST)` —— **自检的语言与用户的设置解耦** |

**真机验证（逐条走过 UI，不是只改 config）**：

1. 设置页出现「语言 · 当前：跟随系统」，点开是**三项**：跟随系统 / **English** / **简体中文**
   —— 语言名**用它自己的语言写**（看不懂当前界面也认得出母语）✓
2. 选 **English** ⇒ **整页立刻变英文**（`Theme / Language / Current: English / Default slot`），
   `config.json` 写入 `"app_language": "en"` ✓（`recreate()` 那条路走通了）
3. **杀进程重启**后仍是英文（持久化）✓
4. ★★ **最关键**：应用语言是**英文**时跑自检 ⇒ **514 通过 / 0 失败**，
   报告里写着 `自检语言 = zh（锁定，与界面语言设置无关）` ✓
   —— 这就是 `SelfTest` 那次 `force()` 的作用：**用户怎么切都不会把自检弄红**。
5. 切回「跟随系统」⇒ 界面回到中文，`"app_language": ""` ✓
6. 🔴 **红线复核**：`grep -n 'Locale.setDefault' app/src/**/*.java` ——
   命中的**全是注释**（`LocaleMode` 的类注释、`SelfTest` 的说明）或无关同名方法
   （`Config.setDefaultSlot` / `Thread.setDefaultUncaughtExceptionHandler`）⇒
   **没有任何代码动进程默认 locale**，`:game` 进程里的游戏不受影响。

**两个从实测里学到的、值得记下来的东西**：

- ★ **`adb shell cmd locale set-app-locales <pkg> --locales en` 在本机（API 36）是生效的**，
  即使应用**没有**声明 `android:localeConfig` —— P1-A 那次就是靠它把界面切成英文来验收的。
  ⇒ 结论：**"系统 per-app 语言"这条路在真机上已经能走**，我们仍然坚持自建（路线 ②）的理由
  变成了两条：① minSdk 26 要覆盖 26~32，系统那条只有 33+；② 我们自己包 Configuration 会盖住系统设置，
  两者并存只会制造"在系统里改了没反应"的困惑。**所以继续不声明 `localeConfig`。**
- 🔴 **`values*/` 的 XML 注释里不能出现「星号+斜杠」** —— aapt2 会把 values 的注释
  **原样搬进 `R.java` 当 Javadoc**，注释被提前结束，javac 报 **96 个 cascading error 且行号指向 `R.java`**。
  实测：`R.java` 里 764 个资源条目只有 77 个带注释（正好是 `strings.xml` 那批），
  **drawable / layout 的注释不会被搬** ⇒ 新判据 `RES-11` 只扫 `values*/`。
  （这条与既有的"注释里不能有连续两个减号"是同一族，但那一条 expat 会拦、**这一条只有 javac 会拦，而且报错位置极具误导性**。）

### P0 落地记录（2026-10-04）

| 交付物 | 说明 |
|---|---|
| `tools/i18n-check.py` | 检查器。资源层 `RES-01`~`RES-09`、源码层 `SRC-01`；`--selftest` / `--utf8` / `--verbose` / `--root` |
| `tools/i18n-known.txt` | 已知缺陷台账（7 条 = 建门禁时量出来的全部既有缺陷，**没有一条是 P0 引入的**） |
| `build.sh` 的 `[0/4]` | 门禁。先 `--selftest` 再真检查；不通过就 `exit 1`，**在 aapt2 之前** |

**三道实证**（都不是"看代码觉得对"）：

1. **尺子有牙**：`--selftest` 20 条元断言全过 —— 10 条规则**各自**都拿一个"已知坏的输入"验过会判死；
   另有 6 条"不许响"的阴性用例（参数**换序**/参数**重复**、技术性判据如 `startsWith(".")`、
   注释里的中文、行首项目符号 `·`）验过**不会被误杀**。
2. **真工程上能抓住**：造一个 `values-en/`，里面写"少一个 `%2$s`" + "译者自己编的 key"，
   门禁分别报 `RES-02`（默认 `[1,2]` vs 本语言 `[1]`）与 `RES-01`（aapt2 会静默移除）；
   **构建在 `[0/4]` 就中止，从没跑到 `[1/4]` aapt2**。删掉后立刻恢复 `OK`。
3. **不误伤**：`values-en` 里那条 `\u0020\u0020[current]`（在 `RES-05` 白名单里）**没有**被报错 ——
   说明白名单对译文同样有效，翻译不用为了过门禁去改结构。

**顺带量出来的 3 条既有缺陷**（门禁第一次跑就报出来的，见 §5.6）：`RES-08` ×1、`RES-09` ×1、
`SRC-01` ×5（4 个位置）。⇒ **门禁本身就把研究阶段的"人工清单"变成了机器判据。**

> ⚠️ **一处与 i18n 无关的构建环境观察**：本机 Git Bash 的 PATH 里 `python` / `python3`
> **都是 WindowsApps 的空壳**，所以 `./build.sh` 会在自己的 Python 探测处就退出
> （build.sh 早已把这条写成显式错误并给了做法）。跑构建要显式给解释器：
> `PYTHON=<真 python 的路径> ./build.sh`。**这不是门禁引入的依赖** —— build.sh 本来就需要 Python
> （dex 注入那一步）。

---

## 9. 判据总览（怎么知道做对了）

| 层次 | 判据 | 有分辨力吗（元断言） | 状态 |
|---|---|---|---|
| 构建期 | `tools/i18n-check.py` 过 | **喂一个故意坏的语言目录，要求判死** | ✅ 已落地（元断言 20 条） |
| 构建期 | 各语言目录与默认目录的 **key 集合**（`RES-01`）；**默认目录必须含 Java 引用的全部 key**（`RES-07`） | 故意漏一条 / 只在语言目录里加一条，要求判死 | ✅ 已落地并实测判死 |
| 构建期 | **每条 key 的 `%n$` 参数索引集合**（`RES-02`） | 故意少写一个 `%2$s` ⇒ 判死；参数**换序/重复**必须**不响** | ✅ 已落地并实测判死 |
| 构建期 | **禁止用文案串做判据**（`SRC-01`） | 拿 `MapsActivity:384` 那条原样喂进去，**要求判死** | ✅ 已落地（实测 5 处命中） |
| 构建期 | **语言名单 ↔ `values-xx/` 目录一一对应**（`RES-10`，双向） | ① 有目录没名单 ② 有名单没目录 —— 两个方向各一个阳性用例 + 一个阴性用例 | ✅ 已落地（P2 加） |
| 构建期 | **`values*/` 注释里不许出现「星号+斜杠」/连续两个减号**（`RES-11`） | 喂一段含它的注释，要求判死；再喂正常的 `values-xx/`，要求**不响** | ✅ 已落地（P2 加，踩过一次 96 个 cascading error） |
| 构建期 | 台账里的条目**修好忘删必须报 STALE** | 喂一个假条目，要求报 `STALE` | ✅ 已落地 |
| 构建期 | `aapt2 dump resources <apk>` 里每个 key 的 config 数与语言目录数**对得上** | 同上 | ⏳ P1（现在还没有语言目录） |
| 资源层 | `aapt2 dump badging` 的 `locales:` 行列出预期语言 | —— | ⏳ P1 |
| 设备层 | **同一个自检跑两遍**（`zh_CN` 的 Configuration / `en` 的），**两遍都要求全绿** | 断言"两遍的通过数不同 ⇒ 确实有事发生"（防"英文下整段真空跑"） | ⏳ P3（§7.7 做法⑤） |
| 设备层 | 强制 locale 的 Context 里逐条 `getString(id, 真参数)` 实拼 | 故意少传一个参数，要求抛 `MissingFormatArgumentException` | ⏳ P3（§7.7 做法③） |
| 设备层 | 真机切语言后逐页对照（9 个页面） | 切到英文后**机器判"可见文本里不出现 CJK"**（遍历可见 TextView） | ⏳ P2 |
| 设备层 | `:game` 进程语言未被带偏 | 启动器设英文 + 设备 zh + 游戏 `locale=default` ⇒ 游戏仍应是中文 | ⏳ P2 |
| 结构层（新，语言无关） | `·` 两侧空格（`RES-09`）/ 首尾空白（`RES-04`）/ 裸 `%s`（`RES-03`）/ `\u0020` 白名单（`RES-05`） | 每条都有阳性 + 阴性元断言 | ✅ 已落地 |
| 边界 | `adb shell cmd locale get-app-locales io.mdt.launcher` | —— | ⏳ P2 |

**设备与工具已就绪**：真机在线（Android 16 / API 36，当前系统语言 `zh-Hans-CN`），
`io.mdt.launcher` 已安装；`adb shell cmd locale set-app-locales/get-app-locales` 可用。
（★ 具体机型与序列号见作者本地笔记 `AGENTS.md` —— 那份**有意不进仓库**，这里不写。）

---

## 10. 风险清单

| 风险 | 等级 | 说明 / 对策 |
|---|---|---|
| **🔴 中文当判据 ⇒ 一翻译就静默失效** | **最高** | 4 处已核实（`MapsActivity:384` / `Backup:437` / `SlotOps:170` / `MapStats:67,752`）。**不报错、不崩溃、没有自检会红**。对策：判据换枚举/布尔标志（§5.2）。**这一条无论做不做 i18n 都该修** |
| **翻译漂移** | 🔴 高 | 344 条 × N 语言，新增/改写文案时各语言目录一定落后。**只能靠 P0 的门**，不能靠人 |
| **自检悄悄失去分辨力** | 🔴 高 | 加 `values-en/` 会让 **7 条断言变恒真**（报告照样全绿，实际不再检查任何东西）。⇒ §7.7 的"结构判据 + 双跑 + 元断言"必须与加语言目录**同一次**做 |
| **A 类碎片不重写就翻** | 🔴 高 | 354 处里绝大多数是 `"槽「" + n + "」…"` 型半句话；直接翻译会得到语序错乱的译文。⇒ "搬进资源"与"重写成整句"必须同一步 |
| **"没有 Context 的静态层"改造牵连自检** | 🟠 中 | 6 个类 + 调用点 + `SelfTest` 那 25 条"级 B"断言。建议**先加错误码、保留旧字符串作过渡**，分两步走 |
| **长译文撑破 2 个布局** | 🟠 中 | `item_mod` 徽标 / `item_slot` `slot_meta`。第 1 档 4 个属性先改，再逐语言真机看 |
| **`:game` 进程语言被带偏** | 🟠 中 | 只要遵守"**不调 `Locale.setDefault`**"就没有这个问题；但这是一条**必须写进注释的红线**，很容易被后人"顺手加上" |
| **`MapStatsMods.attrLabels` 的 7 条资源零覆盖** | 🟠 中 | 英文下地图详情页的「含水/含油」会变，自检不知道（§7.5）。做 P3 时顺手补判据 |
| **`report()` 会把异常原文放进技术细节** | 🟡 低 | `SettingsBin.Result.report():406` 原样打印 `error`（§5.3）。必须明确"接受"还是"改印 `userReason()`" |
| **两套数字/日期格式混用** | 🟡 低 | 统一到 `Locale.US` |
| **发布渠道** | 🟡 低 | 插件化加载其他 APK **不符合 Google Play 政策** ⇒ 国际分发只能走 GitHub Releases / F-Droid / 社区。这会影响"国际化"能触达谁，但不影响技术可行性 |
| **体积** | 🟢 可忽略 | ≈ 11 KB/语言 |
| **商标/命名** | 🟡 低 | 名字里的「MDT / 启动器」对英文用户不透明；`Mindustry` 是 Anuken 的商标，本项目只是**加载**用户自备的 APK，不打包、不再分发（与现状一致，国际化不改变这一点） |

---

## 11. 建议的下一步最小动作（按顺序）

**0. ✅ 已完成 —— P0 门禁落地**（`tools/i18n-check.py` + `tools/i18n-known.txt` + `build.sh [0/4]`）。
   它第一次跑就量出 7 条既有缺陷，全部登记在台账里，构建通过但**每次都把它们打出来**。

**1. ✅ 已完成 —— P0.1 修掉 5 处"中文当判据"**（2026-10-04）。
   台账里那 5 条已清空；**真机自检 514 通过 / 0 失败**，设备已装回产品版。
   ★ 其中 `MapStats:752` 是个意外：门禁照出来那是**空体死条件**，直接删了（不是"换成枚举"）。

**2. ✅ 已完成 —— 修掉要用户点头的两处，门禁归零**（2026-10-04）。
   - `strings.xml` `export_pick_head_fmt` 的分隔符补空格（`RES-09`）；
   - `AndroidManifest.xml` 的 `SlotActivity` label 换成**不带占位符**的新串
     `slot_page_title`（`RES-08`）—— 产物层验过：label 指向 `@0x7f0701ed`，且再无 label 指向带参那条。
   台账现在**零豁免**（`known=0`）。

**3. ✅ 已完成 —— P1-A 默认语言翻英**（2026-10-04）：`values/` = 英文 560 条，`values-zh/` = 中文。
   门禁一次通过；真机中文环境自检 514/0、英文环境界面已验。**详见 §8 的 P1-A 落地记录。**

**4. ✅ 已完成 —— P2 应用内语言设置**（2026-10-04）：设置页「语言」三项可切（跟随系统 / English / 简体中文），
   真机逐条走过 UI；**并且把自检的语言钉死在 zh**，于是"用户切成英文 ⇒ 自检红 15 条"这个坑**没有了**
   （实测：英文界面下自检 **514 通过 / 0 失败**）。详见 §8 的 P2 落地记录。

**5. 🔜 下一步候选（都不再阻塞发版）**
   - **P1-B 自检语言无关化**：把 §7.3 那 15 条改成与语言无关 / 中英双跑。
     ⚠️ 它**不再是发版阻塞**（P2 已用"语言锁"把坑堵上）；做它是为了**让自检在英文下也有意义** ——
     现在的语言锁意味着"英文那半边只有构建期门禁在管，自检一句都没验"。
   - **P3 硬编码迁资源**：344 条用户可见中文，先从 `*Reason()` → 错误码 开始。
   - **P5 仓库/发布国际化**：英文 `README.md`、Release notes。

**6. 小尾巴**：`--verbose` 列出的 **~40 条孤儿 key** 过一遍、
   把 `dev_*` 标 `translatable="false"`（它们只在 debuggable 构建可达，没必要翻）。

---

## 附录 A：本文用到的可复现命令

```bash
# 1) 多语言资源实证（不碰工程文件，全程在 %TEMP%）
cp -r app/res "$TMP/mdt-i18n-spike/res"
mkdir -p "$TMP/mdt-i18n-spike/res/values-en"
# ... 写入 values-en/strings.xml ...
aapt2 compile --dir "$TMP/mdt-i18n-spike/res" -o "$TMP/mdt-i18n-spike/res.zip"
aapt2 link -o "$TMP/mdt-i18n-spike/out.apk" -I <android.jar> \
      --manifest <manifest> --min-sdk-version 26 --target-sdk-version 36 res.zip
aapt2 dump badging   out.apk    # locales: '--_--' 'ar' 'de' 'en' 'zh'
aapt2 dump resources out.apk    # 同一 resource id 下的多份 config

# 2) "只在语言目录里有的 key" 会被静默移除
aapt2 link ... --java gen ...   # 只会打印一行 warn，rc=0，R.java 里没有该 id

# 3) 游戏侧语言链路（源码级定案）
python -c "import zipfile;z=zipfile.ZipFile(r'素材/Mindustry本体/Mindustry-master.zip');\
print(z.read('Mindustry-master/core/src/mindustry/Vars.java').decode())" | sed -n '500,560p'
python -c "import zipfile;z=zipfile.ZipFile(r'素材/Mindustry本体/[Android][v160]Mindustry.apk');\
print('\n'.join(n for n in z.namelist() if n.startswith('assets/bundles/')))"
```

## 附录 B：证据文件（本次研究产出，均在 `.tmp-i18n/`，已被 `.gitignore` 的 `/.tmp*` 覆盖）

| 文件 | 内容 |
|---|---|
| `.tmp-i18n/cjk-literals.txt` | 47 个源文件的 CJK 字面量逐条清单（含行号，**含注释文字 ⇒ 偏大**） |
| `.tmp-i18n/classify-A.md` | **A/A′ 逐条清单（402 行）+ 逐文件统计 + B 汇总 + C 明细**（36 KB，按"可达性"口径） |
| `.tmp-i18n/cjk-judgements.txt` | **判据/改写类调用里带中文字面量的 120 处**（§5.2 那 4 条的出处） |
| `.tmp-i18n/reason-strings.txt` | `error`/`*Error`/`report`/`return` 类中文字面量，按文件分组 |
| `.tmp-i18n/abc-estimate.txt` | 我自己的 A / B / C 分文件条数（**"位置口径"，已被上面的"可达性口径"取代，留作方法论对照**） |
| `.tmp-i18n/string-groups.txt` | 559 条资源的分组统计（`mods_*` 156 条最大） |
| `.tmp-i18n/callsites.txt` | `R.string` 引用点统计 + 各 Activity 的父类 |
| `.tmp-i18n/apk-bundles.txt` | 官方 v160 APK 里的 36 个 bundle 文件清单 |
| `.tmp-i18n/game-locale.txt` | 游戏源码里 locale 相关命中（`Vars` / `Control` / `NetClient` / `LanguageDialog`） |
| `.tmp-i18n/arc-i18nbundle.txt` | arc `I18NBundle` 源码（文件名约定） |

> 外层的 aapt2 实证产物在 `%TEMP%/mdt-i18n-spike/`（`out.apk` / `dump-resources.txt` /
> `dump3.txt` 等），**没有写进本仓库**。

## 附录 C：三份专项审计的分工与已并入的位置

| 审计 | 覆盖范围 | 结论并入 |
|---|---|---|
| **布局抗长译文 / RTL** | 19 个布局 + `AndroidManifest.xml` 逐属性（XML 解析 + 逐 dp 算式） | **§6** |
| **硬编码用户可见文案** | 47 个源文件的 CJK 字面量逐条分类 | **§5**（汇总口径由我独立复算，见 `.tmp-i18n/abc-estimate.txt`） |
| **`SelfTest` 与语言耦合** | 510 处断言的词法级解析 + 逐条分级 | **§7** |

**三份审计里"交叉验证一致"的关键数字**（可当作已复核）：字符串 559 条 / 带参 204 条 /
引用点 662 处 / 布局硬编码文字 0 处 / 方向写死 0 处 / `item_version` 的 `ver_title ≈ 170.6 dp` /
6 个界面页的中文**只出现在注释里**。

**两处"我自己算错、被专项审计纠正"的地方**（留着当方法论记录）：

1. **A 类口径**：我先用"字面量写在哪"（界面 API / `return` / `*error*` 赋值）判，得 A=154；
   正确口径是"**能不能到达用户可见面**"（含被 `msgOf(e)` / `getMessage()` 拼进弹窗的异常消息），
   得 **A=354**。差在主因：`throw new IOException("…")` 里的中文**其实是弹窗正文**。
2. **行号的可靠性**：本机 `pwsh` 的 `Get-Content` **按 GBK 解码 UTF-8**，中文字符尾字节会吞掉换行
   ⇒ **行号整体偏移**（`activity_log.xml` 曾偏 15 行）。⇒ 本报告的行号一律来自 `read` 工具或
   显式 UTF-8 读取，**不要用 `Get-Content` 复核行号**。
