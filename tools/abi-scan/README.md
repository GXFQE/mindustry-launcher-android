# abi-scan —— 判定「某版本能不能被同名类替换覆盖」

`tools/poolspike/` 的兼容性尺子。核心问题：游戏 APK 里的 `arc.backend.android.AndroidFi`
ABI 是不是每个版本都一样，我们的 `override.dex` 能不能静态链接上去。

## 判据（三条全过才 eligible）

1. 该版本有 `Larc/backend/android/AndroidFi;`，父类是 `Larc/files/Fi;`；
2. 该版本 `AndroidFi` 声明的**每个**方法都被 `override.dex` 覆盖（多出来就说明会静默落到 `Fi` 的文件实现）；
3. `override.dex` 引用的每个 `Fi` 方法，该版本 `Fi` 都有（少了运行时就 `NoSuchMethodError`）。

## 文件

| 文件 | 作用 |
|---|---|
| `dexabi.py` | 最小 DEX 解析器；`python dexabi.py <apk目录> <输出.txt>` 逐包 dump 目标类的方法签名 + 指纹 |
| `compare.py` | 跨版本 diff（按 direct / virtual 分开，打印 ±） |
| `linkage.py` | 把 `override.dex` 的 method_ids/field_ids 里指向 `Fi` 的引用列出来，逐版本比对 |
| `scan_samples.py` | **主力**：递归扫样本目录下 ≥15 MB 的 apk，按上面三条判据给 OK / fallthrough |
| `scan_file_method.py` | 解析 `AndroidFi.file()` 的真实语义（是否按 `getLocalStoragePath()` 重定位 local） |
| `abi.txt` | 34 个样本的 dump 结果（`dexabi.py` 输出，留档） |

## 用法

```bash
PY="C:/ProgramData/Anaconda3/python.exe"
$PY tools/abi-scan/scan_samples.py          # 全量扫，按版本列 OK / fallthrough
$PY tools/abi-scan/dexabi.py "D:/.../Mindustry/安卓版" out.txt
$PY tools/abi-scan/compare.py a.txt b.txt   # 或按脚本内说明
```

样本目录与 override 默认指向本机布局，可覆盖：

```bash
MDT_SAMPLES="D:/别的/样本目录" MDT_OVERRIDE="D:/别的/override.dex" python tools/abi-scan/scan_samples.py
```

先构建 `override.dex`（见 `tools/poolspike/README.md`），否则 `scan_samples.py` 会读不到。

## 结论（2026-11-21）

- `AndroidFi` 指纹 `1c2914d5ac42`：2 direct + 14 virtual = 16 个方法，**34 个样本全一致**
- 差异只有两处，都不在覆盖集合里：v104.10~126.2 多一个 `lastModified()J`（v127 起消失，且只是 `invoke-super`）；
  `mindustry-146` 的 `AndroidFiles` 少 `getInternalStoragePath()`、`Fi` 少 `compareTo×2 + sha256()`
- 唯一不可用样本：`Mindustry_41汉化`（libGDX 时代，没有 arc）
