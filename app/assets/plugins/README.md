# 内置插件（官方三件套）—— **随本体发放**

> 2026-10-30（第 326 轮，用户：「把现在这三个官方插件改成内置」）。

这里三份 APK 是**产品版**内置的官方插件：`mapstats` / `mods-health` / `usagestats`。
它们在启动器**进程最早点**（`LauncherApp.attachBaseContext` → `Plugins.injectEarly`）被注入宿主
ClassLoader，所以用户不需要手动装；界面上标「内置」、**没有「删除」**（可以停用）。

## 三条机制（细节见 `docs/PLUGIN-API.md` 与 `Plugins` 的类注释）

1. **清单不手写**：`build.sh` 每次调 `tools/builtin-manifest.py` 现算 sha256 生成
   `assets/plugins/manifest.txt`（`id \t 文件名 \t sha256`）。手抄哈希会让运行时把包当成
   "被改过的内置插件"而静默回落成外部插件 —— 那种 bug 极难查。
2. **版本键 = sha256 前 8 位**（`Plugins.builtinVersion`）⇒ 换了内容就是新版本：
   `builtinRegisterAction` 会判 `enable`（从没见过）/ `upgrade`（内容变了）/ `leave`（用户手动停用过、或正在换版本）。
3. **不能卸载 ≠ 不能停用**：用户停用过的内置插件不会被下一次释放偷偷开回来；
   失败处置封顶 L2（回滚，不做 L3 隔离 —— 隔离对"下次还会被放回"的文件没有意义）。

## 怎么更新这里的三份 APK

```bash
export ANDROID_HOME=<sdk>
bash plugins/mapstats/build.sh      # 产物：%TEMP%/mdt-mapstats/mapstats.apk
bash plugins/mods-health/build.sh   # 产物：%TEMP%/mdt-mods-health/mods-health.apk
bash plugins/usagestats/build.sh    # 产物：%TEMP%/mdt-usagestats/usagestats.apk
cp "$TEMP/mdt-mapstats/mapstats.apk"       app/assets/plugins/mapstats.apk
cp "$TEMP/mdt-mods-health/mods-health.apk" app/assets/plugins/mods-health.apk
cp "$TEMP/mdt-usagestats/usagestats.apk"   app/assets/plugins/usagestats.apk
bash ./build.sh install             # 构建会自动重算 manifest.txt
```

⚠️ **文件名 = 插件 id**（`builtin-manifest.py` 的规则），别改名。
⚠️ 三份都按 `debug.keystore` 签名 —— 与本地宿主的签名一致（判「官方」用）；发版宿主用的是另一把钥匙，
但内置那一路**不看签名**（清单 + 哈希定来源），所以不影响。
