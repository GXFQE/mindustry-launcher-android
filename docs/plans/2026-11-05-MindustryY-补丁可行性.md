# MindustryY 能不能做成「原版 + 补丁」？（2026-11-05 评估）

> 结论先说：**类 + 素材这一半能；它的核心功能（Shared Campaign 本机托管）不能**，
> 因为那条路依赖一个**新的 Android Service + 独立进程**，而补丁机制只做
> `prepend dex` + `AssetManager.addAssetPath`，改不了安装包的清单 / 组件 / 第二进程。
> 若只做「客户端形态」（进游戏、单机、连别人的 Shared Campaign），**2026-11-05 深夜已实测跑通**：
> 官方 v160.4 基座（自建）+ 桌面 jar 全量类覆盖 + 素材差量，能进 fork 主菜单；本机托管仍不可用（见文末实测）。
> 项目没有 release / loader jar / CI 产物，但用户提供的桌面 jar 可以绕过 Gradle/R8 直接当类源。

## 这个项目是什么

- `UniverseEmbedded/MindustryY`，**GPL-3.0**，基于 **Mindustry v160.4** 的完整源码 fork
  （默认分支就叫 `shared-campaign/v160.4/a1`；`master` 基本等于上游 v160.4，只有 2 个文件差异）。
- 核心增量是 **Shared Campaign**：多人在同一张战略地图上各自托管 Sector（`mindustry.campaign.shared`）、
  以及为此做的 `mindustry.runtime` GameContext 隔离重构。
- 仓库无 tag / 无 release / 无 GitHub Actions 构建产物（`actions/runs` 与 `artifacts` 都是 0）；
  要拿到 APK 只能自己构建。

## 与上游 v160.4 的差异（GitHub tree 逐文件 sha 对比）

| 范围 | 新增 | 改动 |
|---|---|---|
| `core/src/**`（.java） | 95 | 296 |
| `android/src/**`（.java） | 8 | 1（`AndroidLauncher`） |
| `android/AndroidManifest.xml` | — | 只加了一个 Service |
| `core/assets/bundles/**` | 0 | 2（`bundle.properties` / `bundle_zh_CN.properties`） |
| `android/res/**` | 0 | 0 |
| native（`.so` / jni / C） | 0 | 0 |
| 另：`ArcY`（fork 的 Arc） | 0 | 33 个 Java（无 native） |

Java 改动/新增源码合计约 **6.25 MB**（不含 `tests/`）；按这个量级估，dex 补丁大概 **3–6 MB**，
素材只有两个 bundle（几百 KB）——比 MDTX 的 7.3 MB dex + 7.6 MB 图集差分包还小。

## 关键差异：唯一一个补丁覆盖不到的东西

`android/AndroidManifest.xml` 与上游逐行对比，**只多了这一个组件**：

```xml
<service android:name="mindustry.android.shared.AndroidSharedCampaignHostService"
         android:process=":sharedhost"
         android:exported="false"
         android:stopWithTask="false" />
```

`AndroidLauncher` 与上游相比也只多了一行（+ import）：

```java
// Keep authoritative Shared Campaign worlds out of the graphical GL process.
AndroidSharedHostClient.install(this);
```

这一行只安装一个 `SectorRuntimeFactory`（懒绑定，不在启动时连服务）：

- 启动、单机、普通联机、UI 都不依赖这个 Service；
- 一旦要**在本机开一个 Shared Campaign Action**，`AndroidSharedHostSectorRuntime.start()` 会
  `bindService` + `startService` 到 `:sharedhost`；补丁包里没有这个清单组件 ⇒ 绑定/启动失败，
  该 Action 起不来（异常抛回上层，进程一般不会崩）。

### 能不能"绕过"？

- `SharedCampaignService` 的默认工厂是 `SectorRuntimeFactory.jvmProcess()`（同进程
  `InProcessSectorRuntime`）。看起来"去掉 `install` 那一行就能退回同进程"——**但 fork 明确禁止**：
  `InProcessSectorRuntime.start()` 里有守卫：

  ```java
  if(OS.isAndroid && !Boolean.getBoolean("mindustry.uiTest") && !RuntimeExecutionBudget.androidHostDaemon()){
      throw new IOException(SectorRuntimeFactory.hostLocalAuthoritativeBlockedMessage());
  }
  ```

  报错原文就是「Authoritative Shared Campaign Actions cannot run in-process inside a mobile
  graphical client. Host the Action from a desktop/server process, join a remote Action, or run a
  dedicated shared-host daemon.」——即 Android 图形进程里**故意**不允许跑权威世界。
- 硬绕可行但违背设计：跳过 `install` + 启动时 `System.setProperty("mindustry.runtime.androidHostDaemon","true")`，
  就能让同进程 runtime 通过守卫。代价是把世界 tick / GC 放回 GL 进程（fork 专门拆出去的东西），
  卡顿与稳定性风险自负，不建议作为产品行为。
- 真正的完整支持要给补丁机制加能力：**清单组件注册 + 第二进程的 patch dex/native 注入**
  （启动器先声明一个通用服务，:sharedhost 进程自己注入补丁）。这是比 MDTX 那条路高一档的工程。

## 若要做「客户端形态」的补丁，需要什么

1. **构建 fork APK**：JDK 17 + Gradle 9.3.1（wrapper）+ Android SDK（已有 36）；
   还要 `ArcY` 放在 `MindustryY` 同级目录（`build.gradle` 的 `localArc` 探测，
   否则会去拿上游 Arc 的 `archash=68a04fab6e`，而 fork 用了 ArcY 的 33 个改动，编译不过）。
2. **官方 160.4 基座**：必须精确匹配。注意 Anuken 的 GitHub release **只有桌面/服务端 jar**，
   没有 Android APK；需要 F-Droid 的 160.4 或自己构建官方 160.4（本机 `素材/` 只有 160 / 160.7）。
3. **做 dex 差分**：按类名 + 内容比对 fork APK 与官方 160.4 APK，取「新增 + 改动」的类
   （含内部类 / 合成类 / `mindustry.gen` 生成的类），打成 `patch.dex`。
4. **素材差分**：只有两个 bundle，直接取 fork 那份。
5. **测试边界**：能进主菜单、单机、UI；Shared Campaign 只能「加入远端」，不能本机开房。

## 判断

| 目标 | 结论 |
|---|---|
| 原版 160.4 + 补丁 = MindustryY 的**界面 / 单机 / 客户端** | **已实测跑通**（第 344 轮：自建官方 160.4 + 全量类覆盖 + 素材差量；见文末） |
| 原版 + 补丁 = **完整 Shared Campaign（本机托管）** | **现机制不行**：缺 `:sharedhost` 清单组件与第二进程注入；硬绕同进程违背 fork 的显式守卫 |
| 作为一个普通完整 APK 导入启动器 | 可行（前提是构建出 APK；启动器本来就支持整包），只是没有"补丁省体积"的意义 |

## 与 MDTX 的对比（为什么这次更难）

- MDTX 提供**官方 per-version loader jar**（本身就是按版本做的类差分），且功能全在 dex + assets 里；
- MindustryY 是**源码 fork**：没有 loader、没有 release、必须自己构建 + 差分；
- 更关键：它把核心功能做进了 **Android 组件/进程模型**，这不在补丁的能力范围内。

## 2026-11-05 深夜实测（第 344 轮）

- **官方 160.4 APK 已自建**：上游源码逐文件重建 + 上游 Arc `68a04fab6e`，AGP 8.2.2 + Gradle 9.3.1 + JDK 17，
  R8 开启；产物 `8-official-160.4` / 80,492,750 B。两个构建夹具：
  `core/assets/version.properties` 种子、`merged_java_res/debug/base.jar` 补空 jar。
- **补丁已跑通**：桌面 jar 的 **全量 `mindustry/**` + `arc/**`**（4211 class → 8.5 MB dex）+
  素材差量（29 新增 / 86 改，4.7 MB），在官方 160.4 上进入 fork 主菜单
  （`custom build` + 「共享战役」），无 FATAL。
- **最小差量不可行**：fork 桌面 jar 与官方 APK 的 synthetic lambda 编号撞车
  （`UI$$ExternalSyntheticLambda7` 构造签名不同），只覆盖改动类会 `NoSuchMethodError`；
  全量覆盖后还需要补 `arc.Events$EventBus`（ArcY 新增）⇒ `arc/**` 也全量。
- **本机托管仍不可用**：桌面 jar 没有 `android/**`（`AndroidSharedCampaignHostService` /
  `AndroidLauncher` 的 install 行），补丁仍缺 `:sharedhost` 清单组件与第二进程注入。
  当前只能做客户端形态。
- 证据：`evidence/round344-mindustryy-patch/`；产物在 `C:\mdt-build\artifacts\`。
- **加测**：共享战役**创建 + 战略层可用**（建出「好友战役」、邀请码、塞普罗战略地图），
  不依赖 `:sharedhost`；「发射」点了无可见反应，权威 Sector 动作 / 本机托管边界仍未钉死。
