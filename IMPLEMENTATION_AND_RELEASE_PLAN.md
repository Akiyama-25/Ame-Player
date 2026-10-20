# Ame-Player 本地化网易云 API 与签名发布实施方案

## 目标与边界

目标：将 Ame-Player 对自建/外部 NeteaseCloudMusicApi Enhanced 的依赖，逐步替换为 App 内 Kotlin 网络客户端直连网易云接口；保留现有 UI 和 NcmApi 公共接口，降低迁移风险。同时提供两条 GitHub Actions：一条生成签名 APK Artifact，一条为版本 Tag 构建并发布已签名 APK。

“本地轻量服务”在本方案中指 App 内的 Kotlin API 适配层，不另起本机 HTTP Server。请求仍需联网访问网易云；不承诺离线使用，也不绕过版权、会员或地区限制。

已检查的源码基线：Ame-Player `37114e6`；参考 Melodia `cefdac2`。具体行为以这些检出版本为准。项目目录包含 Melodia 参考源码，属于未跟踪目录；是否纳入 Ame 仓库由维护者决定。

## 1. 当前架构与差距

Ame 通过 `app/src/main/java/Akari/NCM/player/api/NcmApi.kt` 中 Ktor Client（OkHttp engine）访问配置的 `apiBaseUrl`。默认 URL 为空，配置存于 `UserSessionManager`。上层已有 `NcmApi` 业务门面和响应模型解析，适合保持调用方稳定，仅替换 transport 和 endpoint mapping。

当前调用范围：

- 搜索、歌曲详情、播放 URL（质量逐档尝试及旧接口 fallback）、歌词。
- 二维码 key/create/check 与 Cookie 保存。
- 当前账户/用户详情、主页 dragon ball、首页 block。
- 每日推荐歌曲/歌单、高品质歌单、用户歌单。
- 歌单曲目分页与详情 fallback、喜欢歌曲、歌单增删曲目。

Melodia 的实现不是可直接复制的一整个 HTTP 服务。它以 Kotlin Multiplatform + Ktor/Retrofit API 分层，当前代码包含 `NeteaseCrypto`（weapi/LinuxApi/eapi）、`XeapiCrypto`、`CryptoInterceptor`、主机路由及 HeaderInterceptor。Melodia 实际播放和歌单接口偏向 eapi，例如 `/eapi/song/enhance/player/url/v1`、`/eapi/v3/song/detail`、`/eapi/song/lyric/v1`、`/eapi/v6/playlist/detail`。加密 URL 路径、请求 JSON 参数、主机、设备头和 Cookie 必须作为一个整体正确配置。

## 2. 推荐目标架构

保持 `NcmApi` 对 UI / 播放引擎暴露的方法签名不变，逐步将具体请求委托给新组件：

- `NeteaseEndpoints`：固定、集中管理 host 与 API path。参考 Melodia 的 endpoint 常量；根据所选协议分别路由到 music.163.com / interface.music.163.com，避免 URL 字符串散落。
- `NeteaseCrypto`：移植或独立实现实际需要的最小协议子集，优先覆盖 eapi 与 weapi。只有功能实际需要时再加 xeapi。不得假设加密本身不需要协议头、Cookie 或响应解密。
- `NeteaseDirectClient`：使用现有 Ktor/OkHttp 依赖发送 POST、注入请求头和 Cookie、解析状态码；不引入 Node、QuickJS、JNI 或本地监听端口。
- `NcmApi`：保留对上层的 Kotlin 模型映射；每个 API endpoint 独立实现和测试，明确返回业务错误，不再将所有失败静默映射成空结果。
- `UserSessionManager`：逐步保留 Cookie/用户资料；API Base URL 设置仅在兼容后端开关仍保留时显示。评估 Cookie 的安全存储，避免写日志和上传到 crash report。

## 3. 接口迁移矩阵

| 阶段 | 当前 Ame 功能 | Melodia 可借鉴位置 | 迁移验证重点 |
|---|---|---|---|
| P0 | 搜索 | `feature/search/data/SearchApi.kt`（cloudsearch） | 搜索类型、分页参数、歌曲/专辑/艺人字段映射。 |
| P0 | 歌曲详情 | `core/player/data/PlaybackApi.kt` | ids/c 参数、歌曲字段 ar/artists、al/album、dt/duration 兼容。 |
| P0 | 歌词 | `PlaybackApi.kt` | id/cp/tv/lv/rv/kv/yv 等参数；兼容 lrc、tlyric、yrc/romalrc 与缺字段。 |
| P0 | 播放 URL | `PlaybackApi.kt` | ids、level、encodeType；最高音质降级、试听/会员/不可用、空 URL、重定向、过期重试。 |
| P1 | 歌单详情/曲目 | `feature/playlist/data/PlaylistApi.kt`、PlaylistRepository | playlist/detail 曲目可能截断；使用完整 trackIds 分页 song/detail，不得依赖单页 tracks。 |
| P1 | 用户歌单/账号资料 | `core/userplaylist/UserPlaylistApi.kt`、ProfileApi/NeteaseApiService | uid 缺省语义、分页/limit，账户 profile schema。 |
| P1 | 二维码登录 | `core/api/NeteaseApiService.kt` 与登录实现 | 800/801/802/803 状态机、key 参数、二维码 URL/图片、Set-Cookie 多头提取、Cookie 清洗和保存；不能继续依赖 API Enhanced 的 cookie JSON 字段假设。 |
| P1 | 收藏/歌单增删 | `core/songlike/SongLikeApi.kt`、PlaylistApi | 写操作请求体、登录态、重复操作、失败码；只有确认需要时迁移。 |
| P2 | 首页 block/dragon ball/推荐 | `feature/home/data/HomeApi.kt` 等 | 首页 JSON 是动态 schema；先加 fixture 回归，安全处理未知 block。Dragon Ball 当前应单独核对是否有 Melodia 等价 endpoint。 |
| 暂缓 | 云端特定 fallback / 扩展接口 | 逐项确认 API Enhanced 的 endpoint | 找不到官方可用 API 时，明确标为不支持或保留兼容后端，不能用错误映射伪装完成。 |

## 4. 分阶段交付和验收

### 阶段 A：接口契约与测试基线

1. 为每个 NcmApi 方法记录当前 API Enhanced 请求/响应样例、上层调用点和行为。
2. 从 Melodia 对应 API DTO 抽取字段和参数参考，记录源码路径及 MIT notice。
3. 建立 JSON fixture 测试，覆盖可选字段、空数组、错误 code、会员/版权错误及未知字段。
4. 给加密实现建立固定测试向量，验证参数序列化、AES/Base64/Hex、RSA 字段、表单编码、csrf_token 注入和 endpoint path。随机密钥测试应通过注入测试随机源实现可重复性。

### 阶段 B：只读核心 API

1. 新增 direct-client 与 endpoint constants；旧 API 后端暂时留作可切换 fallback。
2. 迁移搜索、详情、歌词，逐项对照返回模型。
3. 用调试构建跑真实设备测试和 fixture 单测；记录网络失败、登录要求、地理限制。
4. 不通过则仅回退该接口，不回退全部 NcmApi。

### 阶段 C：播放和列表

1. 迁移播放 URL：音质顺序以用户设置/现有行为明确决定，尊重实际响应 level；无 URL 或试听信息时给出可见错误。
2. 迁移歌单详情和曲目分页，确保长歌单完整、无重复、可取消并可报告进度。
3. 迁移用户歌单/账户信息；做退出、Cookie 失效和冷启动状态测试。

### 阶段 D：登录与写操作

1. 单独实现二维码流程与 Set-Cookie 捕获；轮询过程中隔离旧 Cookie，成功后保存新 Cookie 并验证账户。
2. 再迁移 like/add/del。写请求必须有服务端确认，失败时不乐观伪报成功。
3. 对 API 错误码作类型化处理，避免现有空列表 catch 隐藏问题。

### 阶段 E：首页、移除旧服务配置

1. 迁移首页 block 和推荐接口，先保存脱敏响应 fixture。
2. 若仍有无法直连的功能，评估保留“兼容 API Enhanced”选项；确认功能覆盖后，才移除 API URL 设置和对应 UI。
3. 更新 README、隐私说明、故障诊断文档；跑完整 unit test、release 构建与设备 smoke test。

### 发布验收

- 无外部 API Base URL 时核心搜索、歌词、播放路径可用。
- 未登录和已登录流程分别验证，二维码过期、Cookie 更新、退出登录均正确。
- VIP、不可播放、地区限制与 URL 失效能区分错误。
- 长歌单曲目数量与官方详情一致。
- Home unknown block 不导致页面失败。
- 签名 APK 的签名证书 SHA-256 与本地私钥对应；升级安装能覆盖之前版本（applicationId、versionCode 和证书一致）。
- Actions 日志不输出密码、Base64 keystore、Cookie 或完整签名命令中的敏感值。

## 5. GitHub Actions 工作流

已新增：

1. `.github/workflows/build-signed-apk.yml`
   - PR：运行 release unit tests 和 assembleRelease，上传短期 unsigned APK 供验证，不访问签名 Secrets。
   - main push / workflow_dispatch：构建后在独立 sign job 从 GitHub Secrets 注入私有 keystore，zipalign、apksigner sign/verify，并上传签名 APK Artifact。
2. `.github/workflows/release-signed-apk.yml`
   - 推送 `v*` tag 或 workflow_dispatch 指定已有 tag 时，checkout 该 tag、测试、构建、签名、校验，再将 APK 与 SHA256SUMS 发布为 GitHub Release 附件。
   - build job 仅读取代码；只有 publish job 获得 `contents: write`。沿用本地 rename-app 的“签名后上传/Release”流程思路，但不把密码作为 workflow_dispatch 输入，避免凭据进入运行记录。

所需 Repository/Environment Secrets：

- `SIGNING_KEYSTORE_BASE64`：Ame-Player 专用 PKCS12 keystore 的 Base64 单行文本。
- `SIGNING_STORE_PASSWORD`：Ame-Player 专用 keystore 密码。
- `SIGNING_KEY_ALIAS`：新 keystore 的 alias。
- `SIGNING_KEY_PASSWORD`：私钥密码；若与 store password 相同，可留空，工作流会回退使用 store password。

keystore Base64 生成例：`base64 -w0 ame-player-release.p12`（macOS 可用 `base64 < file | tr -d '\n'`）。仅把 Base64 文本及密码录入 GitHub Actions Secrets；不得提交 keystore、密码或私钥到 Ame-Player 仓库，也不要在聊天中发送 Base64 值。rename-app 本地存在已被仓库跟踪的签名文件；不要复用该文件或其密码给 Ame-Player。应为 Ame-Player 单独生成新的 PKCS12 keystore，安全备份并用它设置 Secrets。

## 6. 当前仓库约束与后续事项

本次检出时仓库缺少 `gradlew` 和 `gradle/wrapper/gradle-wrapper.jar`，仅有 wrapper properties；Actions 因此使用 `gradle/actions/setup-gradle` 安装 Gradle 9.5.0 并执行 Gradle。正式合并前最好补全标准 Gradle Wrapper 并固定 wrapper distribution checksum；如果补 wrapper，工作流可改用 `./gradlew`。当前 release 版本 `versionCode=1`、`versionName=release-0.1`，发布新版本前需要维护者按升级顺序递增 versionCode，并决定正式版本命名。

工作流已基于源码静态检查；当前执行环境没有可用 Gradle/Android SDK 构建链，尚未真正跑过 GitHub Actions、Gradle 构建或签名验证。首次配置 Secrets 后，先用 workflow_dispatch 在私有仓库验证证书指纹、APK 安装/升级，再创建正式 tag。

## 7. 许可证提醒

Ame 仓库包含 GPL-3.0；Melodia 仓库为 MIT。引用/复制 Melodia 代码时保留 MIT copyright 和 license notice，并评估改动后作品的 GPL-3.0 合规。更稳妥的方式是基于协议和接口行为独立实现，再保留参考链接、版本与代码归属说明。
