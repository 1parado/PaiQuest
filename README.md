# SnapSort（拾集）

一个「拍什么、收什么」的自动归档相册应用：拍照或从相册选图，**端侧自动识别内容并分类归档**——错题进错题本、花草进植物、动物进动物，全程离线、秒级完成。

## 核心闭环

```
拍照 / 相册选图
   ↓
端侧识别：ML Kit 图像标签（内容）+ ML Kit 中文 OCR（文字）   ← 离线，秒级
   ↓
自动分类：错题本 / 植物 / 动物 / 其他（可在详情页手动改）
   ↓
图库浏览（分类筛选 + 网格缩略图）→ 详情页（原图 / 标签 / 识别文字 / 可选 AI 讲解）
```

## 特性

- **泛化强**：分类 = 配置（`Categories.kt`），归类关键词 + AI 提示词一条搞定，新分类零领域代码。
- **离线优先**：识别与分类全部在手机端完成，无网可用；AI 讲解是详情页的可选功能（OpenAI 兼容接口，默认 DeepSeek）。
- **高信息密度、低噪音**：AI 输出固定为【结论】【要点】【延伸】式小节；界面一屏一事。
- **体积小、内存省**：网络用 `HttpURLConnection`、JSON 用系统 `org.json`、存储用「文件夹 + meta.json」、图片用采样解码；刻意不引入 OkHttp / Retrofit / Coil / Glide / Room / 导航库；release 开 R8。
- **隐私**：识别分类端侧完成；相册导入走系统 Photo Picker（不需要存储权限）。

## 构建（仅限 GitHub CI）

遵循 `AGENT.md` 的 P0 规则：**禁止本地编译**。

1. 推送到 GitHub（`main` 分支）或手动触发 `workflow_dispatch`；
2. 进入仓库 **Actions → Android Build → 最新一次运行 → Artifacts**，下载 `SnapSort-apk`；
3. `app-debug.apk` 可直接安装；`app-release-unsigned.apk` 需签名后安装。

## 使用

1. 拍照或点「相册」选图 → 自动识别并归档 → 直接进入详情页；
2. 详情页可改分类（含自建分类）、看拍摄/上传时间、看识别文字、点「AI 讲解」生成结构化讲解（需在设置页配置 API）；
3. 「图库」支持关键词搜索（识别文字 / 标签 / 分类名）、按分类筛选、缩略图显示时间；任意页面点「＋ 新分类」即可创建自定义分类。

## 分类规则（`Classifier.kt`，可解释的规则引擎）

| 信号 | 判定 |
|---|---|
| OCR 文字 ≥30 字且标签含 Text/Document/Book 等，或文字 ≥120 字 | 错题本 |
| 标签命中 Plant/Flower/Tree/Leaf… | 植物 |
| 标签命中 Animal/Cat/Dog/Bird/Insect… | 动物 |
| 其余 | 其他 |

## 目录结构

```
SnapSort/
├── AGENT.md                  # Agent 协作规则（P0：编译只在 CI）
├── .github/workflows/build.yml
├── app/src/main/java/com/paradox/snapsort/
│   ├── MainActivity.kt       # 全部 UI：拍摄 / 图库 / 详情 / 设置
│   ├── Categories.kt         # 分类 = 关键词 + AI 提示词（泛化核心）
│   ├── Classifier.kt         # 端侧分类引擎（图像标签 + OCR + 规则）
│   ├── RecordStore.kt        # 零依赖本地存储（photo.jpg + meta.json）
│   ├── SettingsStore.kt      # 本地设置
│   └── net/LlmClient.kt      # OpenAI 兼容客户端（零依赖实现）
└── app/build.gradle.kts      # 依赖最小化清单
```

## 新增一个分类

**应用内**：图库或详情页点「＋ 新分类」，输入名称即可——自定义分类立即可用于筛选与手动归类。

**代码级**（要让自动分类命中新分类时）：只改 `Categories.kt`，例如新增「美食」：

```kotlin
val FOOD = Category(
    id = "food", label = "美食",
    aiPrompt = "你是美食助手……输出格式：【结论】【要点】【注意】……",
)
// 并在 Classifier.decide() 加一行：has("Food", "Dish", "Dessert") -> FOOD.id
```

UI、存储、图库筛选自动适配，无需改动其他代码。
