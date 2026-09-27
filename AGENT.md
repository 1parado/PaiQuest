# SnapAsk（拍问）· Agent 协作规则

本文件对参与本仓库的 AI Agent 与人类协作者均有效。规则按优先级排列，P0 级规则不可违反。

## P0 · 构建规则（最高优先级）

- **严禁本地编译**：禁止在本机执行任何 `gradle` / `gradlew` 的 `assemble*`、`build`、`install*` 等构建命令。
- **所有编译一律在 GitHub CI 进行**：由 `.github/workflows/build.yml` 承担，`push` 或手动 `workflow_dispatch` 触发。
- **构建产物只从 CI 获取**：进入仓库 Actions 页面，下载最新一次构建的 artifact（`SnapAsk-apk`），其中包含 debug 与未签名 release APK。
- 本地仅允许：编写/修改代码、git 操作、静态阅读代码、编写文档。

## P1 · 依赖规则

- 新增任何第三方依赖，必须在 commit/PR 描述中给出理由；默认倾向零依赖（JDK 与 Android 系统能力优先）。
- 禁止引入重依赖：导航库（Navigation）、OkHttp / Retrofit、Coil / Glide、material-icons-extended 等。
- 网络用 `HttpURLConnection`，JSON 用 `org.json`，图标用自带 vector drawable。

## P1 · 产品原则

- **高信息密度、低信息噪音**：模型输出结构固定为【结论】【要点】【延伸】式小节，禁用客套话与重复原文。
- **界面越简洁越好**：一个屏幕只做一件事；核心交互路径不超过两步（拍摄 → 看讲解）。
- **泛化能力 = 「场景即提示词」**：新增使用场景（如更多领域）只允许修改 `Scenes.kt` 中的提示词配置，禁止为新场景编写领域逻辑代码。

## P1 · 安全

- API Key / BaseURL / 模型名只保存在用户本机 SharedPreferences，绝不写入仓库，绝不出现任何硬编码密钥。
- 权限最小化：仅申请 `CAMERA` 与 `INTERNET`，新增权限必须说明理由。

## P2 · 沟通

- 与用户沟通使用简体中文；代码注释使用简洁中文。
- 提交信息格式：`<类型>: <一句话描述>`，类型为 feat / fix / docs / refactor / ci。
