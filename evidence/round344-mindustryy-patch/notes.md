# 第 344 轮：MindustryY 客户端补丁跑通（2026-11-05）

## 结论

- 用户提供的桌面产物 `Mindustry-shared-campaign-1604-e564f37.jar` 对应 **Mindustry v160.4**
  （分支 `shared-campaign/v160.4/a1`，commit e564f37；`archash=68a04fab6e` 与上游 v160.4 一致）。
- 官方 v160.4 没有现成 APK：**本机从上游源码重建成功**（R8 开启，versionName `8-official-160.4`）。
- 用桌面 jar 做 **全量 `mindustry/**` + `arc/**` 类覆盖 + 素材差量**，在官方 160.4 基座上
  成功进入 **MindustryY 主菜单**：`custom build` + 「共享战役」按钮（截图 `menu-mindustryy-patch.png`）。
- 仍**没有** `:sharedhost` 的 Android Service / 第二进程 ⇒ 本机托管 Shared Campaign 不可用；
  当前是**客户端形态**补丁（启动 / 单机 / 连别人）。

## 基座：官方 160.4 APK（自建）

- 来源：`Anuken/Mindustry` v160.4 源码（用 GitHub API 逐文件重建）+ `Anuken/Arc` commit `68a04fab6e`。
- 工具：Temurin JDK 17.0.20.1 + Gradle 9.3.1 + Android SDK 36；Maven 走 Aliyun 镜像；
  构建目录必须是 ASCII 路径（`C:\mdt-build\official\Mindustry`）。
- 两个构建夹具：
  1. `core/assets/version.properties` 仓库不跟踪，配置阶段就会读 ⇒ 先写种子文件；
  2. AGP 8.2.2 + Gradle 9 在「无 Java 资源」时不生成 `merged_java_res/debug/base.jar`，
     R8 仍把它当输入 ⇒ 在 `android/build.gradle` 里补一个空 jar（只影响本机构建）。
- 产物：`C:\mdt-build\artifacts\official-160.4-debug.apk`
  - 80,492,750 B
  - `versionName=8-official-160.4`，`versionCode=1`，`compileSdk=36`，`minSdk=21`
  - sha256 见 `artifacts.txt`

## 补丁：桌面 jar → dex + 素材

- 类：**全量 `mindustry/**`（3352 个）+ `arc/**`（859 个）**，共 4211 个 class，d8 → `classes.dex` **8,524,036 B**。
- 素材：jar 根目录 vs 官方 APK `assets/` 做 sha256 差量，**29 新增 / 86 改动**，zip **4,728,443 B**。
- 为什么不是最小差量：
  - 最小差量（只挑改动/新增类 + `mindustry/gen`）启动到 UI 时崩：
    `NoSuchMethodError ... mindustry/core/UI$$ExternalSyntheticLambda7.<init>(BaseDialog)` ——
    fork 桌面 jar 与官方 APK 的 **synthetic lambda 编号撞车**，覆盖了官方同名但签名不同的合成类。
  - 改成 `mindustry/**` 全量覆盖后，再缺 `arc.Events$EventBus`（ArcY 新增类）⇒ 把 `arc/**` 也全量覆盖。
  - 全量覆盖后没有官方 caller 再引用 fork 的合成类，问题消失。
- 设备验证（HONOR ELP-AN00 / Android 16）：
  - 官方 160.4 APK 放进 `app_hub/import/official1604.apk`；
  - `hub/patch-lab.txt` → 补丁 dex，`hub/asset-lab.txt` → 素材 zip；
  - `GameSlot` 启动后焦点在 `mindustry.android.AndroidLauncher`，无 FATAL；
  - 主菜单显示 `custom build` + 「共享战役」。

## 产物路径

| 文件 | 路径 | 大小 |
|---|---|---|
| 官方 160.4 APK | `C:\mdt-build\artifacts\official-160.4-debug.apk` | 80,492,750 B |
| 补丁 dex | `C:\mdt-build\patch-dex-full2\classes.dex` | 8,524,036 B |
| 素材补丁 | `C:\mdt-build\patch-assets.zip` | 4,728,443 B |
| 桌面 jar（已移入仓库根） | `Mindustry-shared-campaign-1604-e564f37.jar` | 89,616,912 B |

## 边界 / 未做

- 没有 `android/src/**` 里的 8 个新类 + `AndroidLauncher` 的 `AndroidSharedHostClient.install(this)`：
  桌面 jar 不含 Android 类；补丁用的是官方 `AndroidLauncher`。
- 因此「共享战役」的**本机托管**仍不可用（缺 `:sharedhost` 组件 + 第二进程注入）。
- 补丁还没有产品化的「补丁包格式 / 导入 / 启用 / 冲突检测」，本轮仍走 lab 口。
- fork APK 本身（Gradle + R8）仍构建失败：R8 8.2/8.9/8.13 都报
  `Cannot ensure java.lang.Record as a synthetic program class`；官方 160.4 没有 record，R8 正常。
  这也是本轮改走「桌面 jar 全量覆盖」的原因。
