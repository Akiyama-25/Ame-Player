<div align="center">
  <img src="assets/icon.jpg" width="160" height="160" alt="AmePlayer Icon" style="border-radius: 50%" />
  <h1>AmePlayer</h1>
  <p>基于 Jetpack Compose 与 Media3 (ExoPlayer) 构建的高品质 Android 音乐播放器，内置原生网易云音乐加密直连客户端，支持在线流媒体播放、本地音频管理与 NCM 加密文件解密，深度集成 Material 3 Adaptive 自适应布局、（实验性）系统级实况通知（灵动岛/流体云/超级岛）与 USB DAC 硬件直通输出。</p>
</div>

<p align="center">
  <img src="assets/screenshot_1.jpg" width="22%" />
  <img src="assets/screenshot_2.jpg" width="22%" />
  <img src="assets/screenshot_3.jpg" width="22%" />
  <img src="assets/screenshot_4.jpg" width="22%" />
</p>
<p align="center">
  <img src="assets/screenshot_5.jpg" width="22%" />
  <img src="assets/screenshot_6.jpg" width="22%" />
  <img src="assets/screenshot_7.png" width="22%" />
  <img src="assets/screenshot_8.png" width="22%" />
</p>
<p align="center">
  <img src="assets/Screenshot_2026-10-22-07-58-02-449_com.miui.home-edit.jpg" width="44%" />
  <img src="assets/Screenshot_2026-10-22-07-57-50-136_com.miui.home-edit.jpg" width="44%" />
</p>

- 部分截图来自早期开发版本
- HyperOS 上岛效果不代表实际效果，如需达到，请在设备安装 LSPosed 后，使用 [Hyper Helper](https://github.com/HowieHChen/XiaomiHelper) 修改

---

## 特性概览

- **端侧Netease API**：由 Kotlin 实现的协议加解密层（WeAPI / EAPI），无需自行搭建外部云端服务。支持扫码登录、云端歌单双向同步、每日推荐与全方位曲库搜索（单曲/专辑/歌手/歌词）。
- **本地管理与 NCM 解密**：支持多本地目录批量扫描与智能歌曲匹配（ID / 文本双重比对），原生流式解密 `.ncm` 加密文件并自动补全元数据与双语歌词。
- **（实验性）USB 硬件独占直通**：以方案三架构为基础，支持在界面中实时识别 USB DAC 硬件的 UAC 1.0 / UAC 2.0 传输协议；基于 Android USB Host 接口抢占排他性锁定音频传输端点，脱离系统 AudioFlinger 混音干扰并内置设备断开防爆音安全保护。
- **设计与交互**：Material 3 Adaptive 自适应布局（无缝适配竖屏、横屏、折叠屏及平板）、Monet 动态取色、系统级预测性返回手势（AOSP Predictive Back）跟手缩放与平滑转场动画。
- **实况通知与（实验性）“灵动岛”**：适配 Xiaomi HyperOS（焦点通知/超级岛）、OPPO ColorOS（流体云）、vivo OriginOS（原子岛）、Samsung One UI（Now Bar）及 Flyme 状态栏实时歌词。

---

## 包体说明与“灵动岛”伪装包

本项目所有发布包体均为 **universal 全架构通用包体**（已移除过往 32-bit / 64-bit 独立架构分包，安装包内含全架构支持）。

针对国内部分定制 Android 系统对状态栏胶囊、流体云或原子岛施加的应用白名单限制（默认仅允许特定主流音乐 App 常驻），CI/CD 自动化工作流与 Releases 页面同步提供三种**包名伪装包体**，以便在未开放 Android 16 实况通知特性的系统上无缝触发“灵动岛”交互：

| 包体类型 | 文件名命名格式 | Application ID (包名) | 适用场景 |
|---------|--------------|----------------------|----------|
| **标准通用包** | `Ame-Player-<版本号>-signed.apk` | `Akari.NCM.player` | 官方默认包体，通用各机型与标准 AOSP 系统 |
| **Apple Music 伪装包** | `Ame-Player-<版本号>-Apple_Music-signed.apk` | `com.apple.android.music` | 伪装为 Apple Music，突破系统音乐胶囊白名单 |
| **Spotify 伪装包** | `Ame-Player-<版本号>-Spotify-signed.apk` | `com.spotify.music` | 伪装为 Spotify，适合限制特定国际流媒体白名单的设备 |
| **网易云音乐 伪装包** | `Ame-Player-<版本号>-Netease_Music-signed.apk` | `com.netease.cloudmusic` | 伪装为网易云音乐，原生激活各品牌桌面与状态栏胶囊 |

> **注意事项**：
> 1. 所有包体均使用同一套官方密钥签名，校验指纹与功能完全一致。
> 2. 由于各伪装包包名不同，它们可与标准包共存安装。但如果您的设备上**已安装了对应官方正版 App**（例如官方网易云音乐），则由于签名证书不同，直接安装对应的伪装包会提示签名冲突，此时可优先选择其他未安装的伪装包（例如使用 `-Apple_Music` 或 `-Spotify`）。

---

## 构建与运行

### 环境要求

- Android Studio Ladybug (2024.2) 或更高版本
- JDK 21 / Gradle 9.5
- Android compileSdk 36 / minSdk 26 (Android 8.0) / targetSdk 35

### 本地编译

```bash
# 1. 克隆仓库
git clone https://github.com/Akiyama-25/Ame-Player.git
cd Ame-Player

# 2. 构建 Debug APK
./gradlew assembleDebug

# 3. 构建 Release Universal APK (默认未伪装包)
./gradlew assembleRelease

# 4. （可选）构建指定伪装包 (例如 Apple Music 伪装)
./gradlew assembleRelease -PcustomApplicationId=com.apple.android.music
```

> **提示**：GitHub Actions 工作流（`.github/workflows/build-signed-apk.yml` 与 `release-signed-apk.yml`）依赖仓库配置的签名 Secrets，若在 Fork 仓库中构建，需自行配置对应的签名变量，或使用未签名构建。

---

## 鸣谢

- **[NeteaseCloudMusicApi Enhanced](https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced)** — 提供网易云音乐 API 结构参考与兼容后端支持。
- **[rinchao0721/Melodia](https://github.com/rinchao0721/Melodia)** — 提供了纯 Kotlin 加密直连网易云官方接口的架构思路与协议参考。
- **应用图标来源** — [kanae3792 (Pinterest)](https://jp.pinterest.com/kanae3792/)

---

## 许可声明

本项目采用 [GPL-3.0 License](LICENSE) 开源，仅供技术学习与交流使用，请勿用于商业用途。