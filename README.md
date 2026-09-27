# SnapAsk（拍问）

一个泛化的「拍文字 → AI 讲解」安卓应用：**ML Kit 端侧中文 OCR** 识别取景框里的文字，交给任意 **OpenAI 兼容大模型** 按当前场景输出结构化讲解。考研考公刷题、植物铭牌识读、展板公告理解、外语翻译……场景由提示词驱动，不写死任何领域逻辑。

## 特性

- **泛化强**：场景 = 提示词（见 `Scenes.kt`），新增场景只需加一条 `Scene`，零领域代码。
- **高信息密度、低噪音**：输出固定为【结论】【要点】【延伸】式小节，禁客套话；界面一屏一事，拍摄 → 讲解两步完成。
- **端侧 OCR**：ML Kit 中文识别模型随 APK 打包，离线可用，不依赖 Google Play 服务。
- **体积小**：单 Activity + Compose，网络用 `HttpURLConnection`、JSON 用系统 `org.json`，刻意不引入 OkHttp / Retrofit / Coil / 导航库；release 开启 R8 + 资源收缩。
- **隐私**：OCR 全程端侧；API Key 只存本机 SharedPreferences；除你主动发起讲解外无任何网络请求。

## 构建（仅限 GitHub CI）

遵循 `AGENT.md` 的 P0 规则：**禁止本地编译**。

1. 推送到 GitHub（`main` 分支）或手动触发 `workflow_dispatch`；
2. 进入仓库 **Actions → Android Build → 最新一次运行 → Artifacts**，下载 `SnapAsk-apk`；
3. `app-debug.apk` 可直接安装；`app-release-unsigned.apk` 需签名后安装。

## 使用

1. 安装后进入「设置」，填入 OpenAI 兼容的 API 地址、Key 与模型名（默认指向 DeepSeek）；
2. 取景 → 选场景（通用讲解 / 题目精讲 / 植物铭牌 / 翻译）→ 按快门；
3. 结果页可直接继续追问，上下文自动保留。

## 目录结构

```
SnapAsk/
├── AGENT.md                  # Agent 协作规则（P0：编译只在 CI）
├── .github/workflows/build.yml  # CI 构建（debug + unsigned release）
├── app/src/main/java/com/paradox/snapask/
│   ├── MainActivity.kt       # 全部 UI：相机页 / 结果页 / 设置页
│   ├── Scenes.kt             # 场景 = 提示词（泛化核心）
│   ├── SettingsStore.kt      # 本地设置存储
│   ├── ocr/OcrAnalyzer.kt    # ML Kit 端侧 OCR（按需触发）
│   └── net/LlmClient.kt      # OpenAI 兼容客户端（零依赖实现）
└── app/build.gradle.kts      # 依赖最小化清单
```

## 扩展一个新场景

只改 `Scenes.kt`，例如新增「药品说明书」：

```kotlin
Scene(
    id = "medicine",
    label = "药品说明",
    systemPrompt = "你是用药助手……输出格式：【结论】【要点】【注意】……",
)
```

无需改动任何 UI 或业务代码——新场景自动出现在相机页顶部的场景栏。
