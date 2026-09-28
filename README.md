# SnapSort（拾集）

一个「拍什么、收什么」的图片整理应用：拍照或从相册选图，端侧自动**提取文字与内容标签**，归类权完全交给用户——默认进入「未分类」，用户自主建立和命名分类，数据与分类逻辑始终由用户掌控。

## 核心闭环

```
拍照 / 相册选图
   ↓
端侧识别：ML Kit 图像标签（内容）+ ML Kit 中文 OCR（文字）   ← 离线，秒级
   ↓
默认进入「未分类」→ 用户在详情页自主归类（内置分类 / 自建分类）
   ↓
图库（搜索 + 分类筛选 + 时间显示 + 长按删除）→ 详情页（滑动或点击切换图片 / 改分类 / 可选 AI 讲解）
```

## 特性

- **泛化强**：分类 = 配置（`Categories.kt`），归类关键词 + AI 提示词一条搞定，新分类零领域代码。
- **离线优先**：识别与分类全部在手机端完成，无网可用；AI 讲解是详情页的可选功能（OpenAI 兼容接口，默认 DeepSeek）。
- **高信息密度、低噪音**：AI 输出固定为【结论】【要点】【延伸】式小节；界面一屏一事。
- **体积小、内存省**：网络用 `HttpURLConnection`、JSON 用系统 `org.json`、存储用「文件夹 + meta.json」、图片用采样解码；刻意不引入 OkHttp / Retrofit / Coil / Glide / Room / 导航库；release 开 R8。
- **数据安全**：删除先进回收站可恢复；保存到相册走 MediaStore（API 29+ 零权限）。
- **隐私**：识别分类端侧完成；相册导入走系统 Photo Picker（不需要存储权限）。

## 构建（仅限 GitHub CI）

遵循 `AGENT.md` 的 P0 规则：**禁止本地编译**。

1. 推送到 GitHub（`main` 分支）或手动触发 `workflow_dispatch`；
2. 进入仓库 **Actions → Android Build → 最新一次运行 → Artifacts**，下载 `SnapSort-apk`；
3. `app-debug.apk` 可直接安装；`app-release-unsigned.apk` 需签名后安装。

## 使用

1. 拍照或点「相册」选图 → 端侧提取文字与标签 → 进入「未分类」，详情页有「去归类」引导，选择分类即完成归档；
2. 详情页：**HorizontalPager 滑动翻页**（可预览相邻图片边缘）或点「上一张 / 下一张」；可改分类、看拍摄/上传时间、看识别文字、点「AI 讲解」生成结构化讲解（需在设置页配置 API）；「删除」移入回收站；「保存」把原图存入系统相册 Pictures/SnapSort；
3. 「图库」：关键词搜索（识别文字 / 标签 / 分类名）、分类筛选、缩略图显示时间；**长按缩略图进入多选**，支持批量归类 / 批量删除；
4. 分类管理：独立的「管理分类」页，新建 / 重命名 / 删除自定义分类（删除后其下图片自动回到「未分类」）；
5. 回收站：删除的图片先进回收站，可单张恢复 / 彻底删除，也可一键清空。

## 分类体系

- **未分类**（默认）：新图片的归宿，识别出的标签与文字仍可用于搜索；
- **内置分类**：错题本 / 植物 / 动物 / 其他（各带专属 AI 提示词）；
- **自定义分类**：应用内随时增、删、改，通用 AI 提示词。

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

**应用内**：图库点「＋ 新分类」创建，「管理」中重命名/删除——自定义分类立即可用于筛选与手动归类。

**代码级**（要让内置分类带专属 AI 提示词时）：只改 `Categories.kt`，例如新增「美食」：

```kotlin
val FOOD = Category(
    id = "food", label = "美食",
    aiPrompt = "你是美食助手……输出格式：【结论】【要点】【注意】……",
)
// 并在 Classifier.decide() 加一行：has("Food", "Dish", "Dessert") -> FOOD.id
```

UI、存储、图库筛选自动适配，无需改动其他代码。
