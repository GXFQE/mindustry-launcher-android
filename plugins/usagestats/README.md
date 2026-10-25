# usagestats（官方插件，v29，第 254 轮）

游戏**前台时长**：探针自己记账，页面读自己的文件求和 —— **宿主不写流水、不聚合、不展示**。

## 组成

| 文件 | 作用 |
|---|---|
| `PlaytimeProbe` | 探针（`inProcess: true`，20 秒采样）：收 `onForeground` 事件累计前台毫秒，写 `usage/telemetry/<会话>/usagestats/playtime.txt` |
| `UsageStats.createView` | 页面（`slot: plugins`）：遍历 `usage/telemetry/*/usagestats/playtime.txt` 求和（前台累计 + 本会话） |
| `core/Playtime` | 纯函数：解析 `k=v` / 求和 / 毫秒→时分（PC 侧可测） |

## 口径

- 只算**游戏 activity 在前台**的时间（主菜单 / 暂停 / 编辑器都算，切到别的 App 不算）；
- 进程被杀最多丢一次采样间隔（20 秒），**只少不多**；
- 不读存档的 `playtime`（那个是"存档内已保存时长"，回档/删档/复制都会乱）。

## 构建 / 自检

```bash
ANDROID_HOME=D:/AndroidSDK bash plugins/usagestats/build.sh          # 产出 usagestats.apk
ANDROID_HOME=D:/AndroidSDK bash plugins/usagestats/build.sh check    # PC 侧判据自检
```

签名默认用 `debug.keystore`（与本地宿主同一把钥匙 ⇒ 宿主判「官方」）。
