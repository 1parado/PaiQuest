# SnapSort（拾集）· Agent 协作规则

本文件对参与本仓库的 AI Agent 与人类协作者均有效。规则按优先级排列，P0 级规则不可违反。

## P0 · 构建规则（最高优先级）

- **严禁本地编译**：禁止在本机执行任何 `gradle` / `gradlew` 的 `assemble*`、`build`、`install*` 等构建命令。
- **所有编译一律在 GitHub CI 进行**：由 `.github/workflows/build.yml` 承担，`push` 或手动 `workflow_dispatch` 触发。
- **构建产物只从 CI 获取**：进入仓库 Actions 页面，下载最新一次构建的 artifact（`SnapSort-apk`），其中包含 debug 与未签名 release APK。
- 本地仅允许：编写/修改代码、git 操作、静态阅读代码、编写文档。

## P0 · 产品定位

- 核心闭环：**拍照/选图 → 端侧自动识别（图像标签 + OCR）→ 自动分类归档（错题本/植物/动物/其他）→ 图库浏览 → 可选 AI 讲解**。
- 识别与分类**必须离线完成**（ML Kit 端侧模型），不依赖网络；AI 讲解是锦上添花的可选项，不是主链路。
- **泛化 = 「分类即配置」**：新增分类只允许修改 `Categories.kt`（归类关键词 + AI 提示词），禁止为新分类编写领域逻辑代码。

## P1 · 依赖规则

- 新增任何第三方依赖，必须在 commit/PR 描述中给出理由；默认倾向零依赖（JDK 与 Android 系统能力优先）。
- 禁止引入重依赖：导航库（Navigation）、OkHttp / Retrofit、Coil / Glide、Room、material-icons-extended 等。
- 网络用 `HttpURLConnection`，JSON 用 `org.json`，存储用「文件夹 + meta.json」，图片解码用 `BitmapFactory` 采样。

## P1 · 产品原则

- **高信息密度、低信息噪音**：AI 输出固定为【结论】【要点】【延伸】式小节，禁用客套话与重复原文。
- **界面越简洁越好**：一个屏幕只做一件事；核心路径两步以内（拍 → 自动进详情；图库 → 点开即看）。
- **体积与内存克制**：release 开 R8 + 资源收缩；缩略图必须采样解码，禁止全尺寸加载进内存。

## P1 · 安全

- API Key / BaseURL / 模型名只保存在用户本机 SharedPreferences，绝不写入仓库，绝不出现任何硬编码密钥。
- 权限最小化：仅申请 `CAMERA` 与 `INTERNET`；相册导入使用系统 Photo Picker（无需存储权限）。

## P2 · 沟通

- 与用户沟通使用简体中文；代码注释使用简洁中文。
- 提交信息格式：`<类型>: <一句话描述>`，类型为 feat / fix / docs / refactor / ci。
