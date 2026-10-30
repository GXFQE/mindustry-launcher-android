# 这里是 **dev 专用** 的内置插件夹具

`probe.apk` 就是 `tools/plugtest/plugtest-v1.apk`（同一个变体：自述 `name=plugtest-v1`、
入口 `io.mdt.plugtest.v1.PlugPage`、同名宿主类 tag `PLUGIN-v1`），**只用来在 dev 构建里验内置插件那条链路**。

- 它**只进 dev 包**：`build.sh` 在 `DEBUGGABLE=true` 时把 `app/assets-dev` 叠到 `app/assets` 上一起喂 `aapt2 -A`
  ⇒ 产品包里的内置插件只有 `app/assets/plugins/` 那几份（**2026-10-30 起 = 官方三件套
  mapstats / mods-health / usagestats**，见那一侧的 README），这份夹具**不在**产品包里。
- ⚠️ 因为是"内置"，装了 dev 变体就会在插件页看到一个 **`plugtest-v1`**（`probe.apk` 的自述名）、
  **不能卸载**、每次启动又被放回。用完 dev 变体记得清掉：
  `run-as <pkg> sh -c 'cd app_hub/plugins && rm -rf probe && sed -i /^probe=/d active.txt known.txt pending.txt'`
  —— 然后装回产品版。
- `manifest.txt` **不手写**：`build.sh` 每次调 `tools/builtin-manifest.py` 现算 sha256 生成（手抄哈希会让
  运行时把它当"被改过的内置插件"、静默回落成外部插件 —— 那种 bug 极难查）。
- 重新生成这个 APK：

  ```bash
  ANDROID_HOME=<sdk> ./tools/plugtest/build.sh
  cp "$TEMP/mdt-plugtest/plugtest-v1.apk" app/assets-dev/plugins/probe.apk
  ```

- 真机上验过的行为（判据见 `evidence/plugin-builtin.txt`）：首启自动释放到 `app_hub/plugins/probe/<sha8>.apk`
  并注入；删掉整个目录后下次启动**自动放回**；界面副行显示「内置 · 处置 L1」且**没有「删除」**；
  装一个第三方插件后**第三方能盖住它**（内置在注入顺序里最低）。
