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
- **（实验性）USB独占**：基于 AndroidX Media3 (ExoPlayer)，自动协商最高音质（Hi-Res / 无损），内置 USB DAC 独占模式绕过系统重采样，实现 Bit-Perfect 原始码流直通。
- **设计与交互**：Material 3 Adaptive 自适应布局（无缝适配竖屏、横屏、折叠屏及平板）、Monet 动态取色、系统级预测性返回手势（AOSP Predictive Back）跟手缩放与平滑转场动画。
- **实况通知与（实验性）“灵动岛”**：适配 Xiaomi HyperOS（焦点通知/超级岛）、OPPO ColorOS（流体云）、vivo OriginOS（原子岛）、Samsung One UI（Now Bar）及 Flyme 状态栏实时歌词。

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

# 3. 构建 Release APK（支持启用 ABI 分包：arm64-v8a、armeabi-v7a 与 universal）
./gradlew assembleRelease -PenableAbiSplits=true
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
