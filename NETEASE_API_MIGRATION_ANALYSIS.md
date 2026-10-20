# Ame-Player 网易云音乐 API 内置与轻量化替代方案调研

## 1. 源码现状（本地仓库核对）

- Ame-Player 已克隆至 `/data/Ame-Player`，当前检出提交 `37114e6`。Melodia 参考源码放在 `/data/Ame-Player/references/Melodia`，当前检出 `cefdac2`。
- Ame 是 Android/Kotlin + Ktor Client（OkHttp engine），所有网易云 API 入口集中在 `app/src/main/java/Akari/NCM/player/api/NcmApi.kt`；会话 Cookie 与 API URL 存在 `UserSessionManager.kt`。默认 `DEFAULT_API_BASE_URL` 为空，需确认设置页/构建配置是否另有默认值，不应将“固定依赖某个 3000 端口”视为代码事实。
- 当前 `NcmApi` 保留云端 API Enhanced 的 JSON schema 解析，同时使用 `User-Agent: AmePlayer/1.0` 和 Cookie Header。接口包括：`/search`、`/song/detail`、`/song/url/v1`、`/song/url`、`/lyric`、`/login/qr/key`、`/login/qr/create`、`/login/qr/check`、`/user/account`、`/homepage/dragon/ball`、`/homepage/block/page`、`/recommend/resource`、`/top/playlist/highquality`、`/recommend/songs`、`/user/playlist`、`/playlist/track/all`（分页并回退 detail）、`/like`、`/playlist/tracks`。
- `NcmApi` 的多个函数遇到任何异常就返回空列表/null/false。因此迁移后如果加密、请求体或官方接口字段错了，UI 可能只表现为“没有数据”，要补充安全的状态码/错误日志和测试，避免继续吞掉诊断信息。
- Melodia 是 Kotlin Multiplatform 项目，使用 Ktor `HttpClient` plugin/serialization，并非 OkHttp 拦截器实现。其 `CryptoInterceptor`、`NeteaseCrypto`、`NeteaseApiService` 和 feature API 可作为协议与请求参数参考，但不宜整套复制到 Android 项目。

### 关键校正

本仓库里的 Melodia 实现包含 `weapi`、`linuxapi` 和 `xeapi`（应以当前提交实际代码为准）；接口通过 `NeteaseApiService` 与各 feature API 分层。它提供了直接请求官方 API 的实现样例，但不是一个可直接嵌入 Ame 的“完整 API 服务端”。把 `NcmApi` 的 base URL 简单改为网易云域名不会成功：客户端必须为每个端点构造正确的 POST 表单、加密请求体、Cookie 与响应字段适配。另需逐项核验二维码登录——当前 Ame 处理第三方 API 返回的 `cookie`/`Set-Cookie`；官方二维码登录流程不能假定这些字段、状态码或返回时机完全相同。

## 2. 方案对比总览

| 方案 | 技术实现 | APK体积增加 | 外部服务器依赖 | 接口完整性 | 实现/维护难度 | 推荐指数 |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **方案一：原生 Kotlin 直连（推荐）** | 使用 Android 原生 javax.crypto 实现 weapi/linuxapi/xeapi | **0 MB** | **无** | 基础及进阶全部支持 | 低（已有成熟开源实现可迁移） | ★★★★★ |
| **方案二：嵌入轻量 JS 引擎 (QuickJS)** | 引入 QuickJS 运行 MusicFree 网易云插件 | ~2MB - 3MB | **无** | 支持基础播放、歌单、搜索 | 中（需接入 JNI / JS 桥接） | ★★★☆☆ |
| **方案三：Rust / Go 静态编译 (JNI .so)** | 编译 ncm-api-rs 或 go-musicfox 为动态库 | ~5MB - 15MB | **无** | 完整 | 高（跨平台编译与 ABI 维护成本高） | ★★☆☆☆ |
| **方案四：Cloudflare Worker 无服务器代理** | 将 Node API 简化为单个 Worker 部署至 Edge | **0 MB** | 需 CF 账号 (无自建服务器) | 完整 | 极低 | ★★★★☆ |

---

## 3. 详细方案评估

### 方案一：原生 Kotlin 加密直连（最佳方案，实现真正零外部依赖“内置”）

#### 核心原理
网易云音乐接口本质上是通过预设的加密协议（`weapi`、`eapi`、`linuxapi`、`xeapi`）对 JSON 请求体进行 AES/CBC、AES/ECB 与 RSA 加密，并携带固定的 User-Agent、Referer 和 Cookie 直接与网易云服务器通信。
Android SDK 原生自带 `javax.crypto.Cipher`，支持完整的 AES-128-CBC、AES-128-ECB 与 RSA 加密算法，因此完全可以用纯 Kotlin 代码实现加密层，零新增依赖。

#### 参考开源项目
1. **rinchao0721/Melodia** (https://github.com/rinchao0721/Melodia)
   - **特点**：全 Kotlin Multiplatform 编写的现代开源网易云第三方客户端。
   - **核心参考代码**：
     - `shared/.../core/network/crypto/NeteaseCrypto.kt`：纯 Kotlin 实现的标准 `weapi` 与 `linuxapi` 加解密（约 120 行代码）。
     - `shared/.../core/network/crypto/XeapiCrypto.kt`：新版 `xeapi` 协议实现。
     - `shared/.../core/network/CryptoInterceptor.kt`：OkHttp 拦截器，自动处理请求加密与 Cookie 注入。
   - **功能覆盖**：
     - 音频播放链接：`/api/song/enhance/player/url/v1` (weapi)
     - 歌单详情与歌曲列表：`/api/v6/playlist/detail`, `/api/v3/song/detail`
     - 用户首页发现：`/api/homepage/block/page` (weapi)
     - 歌词获取：`/api/song/lyric/v1`
     - 用户歌单与信息：`/api/user/playlist`, `/api/v1/user/detail`
     - 扫码登录：`/api/login/qrcode/unkey`, `/api/login/qrcode/client/login`
2. **bf2967252373/CloudMelody** (https://github.com/bf2967252373/CloudMelody)
   - **特点**：极简 Android 原生网易云播放器。
   - **核心参考代码**：
     - `app/.../api/NeteaseApi.kt`（单个文件仅 248 行）：将 `weapi` 加密和所有基础接口直接集成在同一个文件内，开箱即用，逻辑清晰直观。

#### 移植改造优势
- **极度轻量**：仅需引入 1~2 个 Kotlin 文件（约 300~500 行），不增加任何第三方二进制或大型库。
- **性能最优**：App 启动即用，直接连接 `music.163.com`，省去所有中间服务跳板。
- **与 Ame-Player 高度契合**：Ame-Player 已有 `NcmApi.kt`，只需将底层发送请求的 URL 和参数改造为调用本地加密方法，上层业务逻辑（ViewModel / State）完全不受影响。

---

### 方案二：嵌入轻量 JS 引擎并复用 MusicFree 插件

#### 核心原理
MusicFree 采用插件化架构，其网易云音乐插件是一段独立的 JavaScript 代码（打包后约数十 KB），负责解析和请求网易云接口。在 Android 内部集成轻量级 JS 引擎（如 QuickJS-Android 或 Duktape），在本地运行该插件脚本。

#### 参考开源项目
- `maotoumao/MusicFreePlugins` (https://github.com/maotoumao/MusicFreePlugins)
- `cher1shu/musicfree-plugin-netease`

#### 评估
- **优点**：若网易云官方接口规则发生调整，只需更新在线 JS 插件脚本即可，无需发布 APK 新版本。
- **缺点**：
  - 需要在 Android 端引入 C++ 动态链接库（.so 文件），增加 2~3MB 的 APK 体积。
  - JS ↔ Kotlin 之间需要建立一套序列化/反序列化通信桥梁，调试与异常捕获相对复杂。
  - 对用户首页等深度定制接口（如 homepage block），通用插件通常不完全支持。

---

### 方案三：原生二进制/JNI 绑定（Go 或 Rust）

#### 参考开源项目
- **Rust**：`SPlayer-Dev/ncm-api-rs` (https://github.com/SPlayer-Dev/ncm-api-rs)
- **Go**：`go-musicfox/netease-music` (https://github.com/go-musicfox/go-musicfox)

#### 评估
- **优点**：性能强，内存低，逻辑严密。
- **缺点**：
  - 需要为多种 CPU 架构（arm64-v8a, armeabi-v7a, x86_64）编译并打包 `.so` 文件，显著增大 APK 体积（通常增加 5~15MB）。
  - 对于纯 Kotlin 项目而言，维护 NDK / JNI 或 UniFFI 绑定会带来额外的维护成本，属于“杀鸡用牛刀”。

---

### 方案四：Edge / Serverless 代理（备选轻量化方案）

#### 核心原理
如果由于特定网络环境（例如 Android 设备遭遇网易云接口严格的 IP 频控或反爬），仍然希望保留中间代理层，可将 Node.js 的大型服务替换为 Cloudflare Workers 脚本。

#### 评估
- 无需自建 Node.js 服务器，免维护、免费额度充裕。
- 代码体积小于 1MB，直接运行在 Cloudflare 全球边缘节点。
- 但仍然属于“外部服务”，并未达到“纯单机内置”的效果。

---

## 4. 推荐改造路径（分阶段，避免一次性替换）

### 4.1 架构

建议保留 Ame 的 `NcmApi` 作为 UI/ViewModel 所依赖的业务门面，新增 `NeteaseDirectClient`（或 `NeteaseApiTransport`）负责：

1. 接收明确的 endpoint 与参数；
2. 按 Melodia 的协议实现组装请求（先选 `weapi`，确有必要再引入 `eapi`/`xeapi`，不要没有需求就复制所有协议）；
3. 向固定 HTTPS 官方域名发送 POST 请求；
4. 附加安全 Cookie、必要请求头；
5. 将官方响应映射回 Ame 现有 `PlayableMedia`、`NcmUserPlaylist`、`QrCheckResult` 等模型。

将 transport 与 endpoint 参数和解析拆开测试。禁止把用户 Cookie、RSA/AES 密钥材料或完整 URL 中的敏感信息写入日志。保留 `NcmApi` 公共方法签名，可逐接口替换实现，便于回滚及不触动 UI。

### 4.2 迁移优先级与风险

| 优先级 | Ame 功能 / 现路径 | 迁移策略与注意点 |
|---|---|---|
| P0 | 搜索、歌曲详情、歌词 | 对照 Melodia 的 service 方法，确认 endpoint、请求体加密方式和官方响应 schema；这三者是播放 UI 的基础。 |
| P0 | 播放 URL `/song/url/v1` 与旧 `/song/url` fallback | 使用 Melodia 的播放 API 作为请求参数参考；保留质量降级，另验证 URL 有效期、VIP/地区限制、无版权返回及 403。不能把“能解析 URL”当成流可播放。 |
| P1 | 歌单详情/歌曲列表、用户歌单、收藏 | 将 `/playlist/track/all` 的分页行为映射到官方分页/详情接口；保留 offset、总数与去重逻辑。喜欢、编辑歌单必须先验证登录 Cookie 和写操作响应。 |
| P1 | 扫码登录 | 独立实现官方二维码 key/create/check 状态机与 Cookie 提取；对照 Melodia 登录代码（若当前 Melodia 版本覆盖该流程），不可沿用 API Enhanced 的字段假设。 |
| P2 | 推荐、首页 block/dragon ball | 首页数据是可变 schema，先以保留旧 API 作为 fallback 或暂不迁移；把适配器与 UI block 类型隔离。每日推荐应验证需要登录及账号权限。 |

> “本地轻量服务”有两种不同含义：若目标是免服务器、直接从 App 请求官方接口，推荐上面的原生 Kotlin transport；若必须要本地 HTTP 服务进程，则需要在设备内启动 Ktor/embedded server，但这会增加生命周期、端口/权限与安全面，通常没有必要。直连官方接口仍然依赖网易云在线服务和其账号/地区/版权政策，不等于离线可用，也不意味着任何云端依赖都消失。

### 4.3 具体实施顺序

1. 对 Ame 与 Melodia 分别记录提交 SHA、许可证与目录；Melodia 是 MIT，Ame 当前仓库含 GPL-3.0。参考协议实现不等于可忽略许可证：若复制或改编 Melodia 源文件，应保留 MIT 版权/许可声明；同时确认 Ame 的分发遵循 GPL-3.0。协议事实/算法可参考，尽量自行实现与编写测试，避免未经记录地整段搬运。
2. 新增 transport 和小型单元测试：覆盖确定性加密向量（固定随机数/参数）、表单字段、Cookie 清洗、错误码与响应解析。加密测试至少用独立已知向量或与 Melodia 输出交叉校验，不能只断言“不抛异常”。
3. 先实现搜索、详情、歌词的只读调用，在调试构建中通过 feature flag 与旧云端 API A/B 验证字段映射。
4. 再迁移播放 URL 和质量降级；用真实设备验证播放、seek、音质选择、URL 失效重试及网络错误。
5. 最后处理扫码登录、个人资料、歌单写操作和首页推荐。登录/写操作失败不能静默当作空数据。
6. 全部通过后，再移除 API Enhanced URL 设置/依赖，或把它保留为用户可选的兼容后端；删除云端配置属于行为变化，应保留明确回退开关。

### 4.4 验收清单

- 未配置旧 API base URL 时，搜索/详情/歌词/播放仍可工作。
- 登录二维码完整走完等待扫码、已扫描、确认成功、超时/过期；Cookie 保存并可重启后使用。
- Cookie 不出现在日志、崩溃报告或明文偏好存储（若现状是明文，迁移时应评估 Android Keystore 加密存储）。
- 无登录时公开接口行为清晰；需要会员/登录/地区授权的歌曲返回可诊断错误。
- 歌单分页不漏曲、不重复，写操作显示服务端失败原因。
- Android 设备/模拟器实测网络安全策略、超时、取消请求与播放链接重定向。
- 评估网易云服务条款、版权和接口使用限制；本方案不绕过付费或访问控制。

## 5. 结论

优先推荐“Android 原生 Kotlin 直接请求官方接口 + Melodia 作为协议和参数参考”，而不是另起一个常驻本地 HTTP 服务。现有 `NcmApi` 已集中封装并可复用其 UI 边界，真正的工作量在请求加密/登录 Cookie 状态机、端点字段适配与回归验证，并非仅修改 base URL。建议先从只读 P0 接口做小步验证，播放、登录与写操作分开迁移。

### 参考源码位置

- Ame: `app/src/main/java/Akari/NCM/player/api/NcmApi.kt`
- Ame: `app/src/main/java/Akari/NCM/player/data/UserSessionManager.kt`
- Melodia: `shared/src/commonMain/kotlin/com/lin0721/linmusic/core/network/crypto/NeteaseCrypto.kt`
- Melodia: `shared/src/commonMain/kotlin/com/lin0721/linmusic/core/network/CryptoInterceptor.kt`
- Melodia: `shared/src/commonMain/kotlin/com/lin0721/linmusic/core/api/NeteaseApiService.kt`
- Melodia: `shared/src/commonMain/kotlin/com/lin0721/linmusic/core/network/NeteaseEndpoints.kt`
- Melodia License: `references/Melodia/LICENSE` (MIT)
- Ame License: `LICENSE` (GPL-3.0)
