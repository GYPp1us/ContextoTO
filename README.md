# ContextoTO

面向中文母语者的安卓篇章英语阅读应用。预置 48 篇考研英语一风格文章，以及 3,000 主背词、4,801 考纲词与 319 零语料词。短按词查看本句义项，长按词查看所在句的整句释义与从句结构；文章目录与查询层均覆盖整页磨砂。

## 开发

需要 JDK 17+、Android SDK API 36、Android SDK Build Tools 36、Python 3.10+。构建使用 Gradle Wrapper：

```powershell
python tools/import_content.py
.\gradlew.bat testDebugUnitTest assembleDebug
```

`tools/import_content.py` 从仓库中的两个原始压缩包生成 `app/src/main/assets/articles.json` 和 `lexicon.json`。生成过程检查 48 篇、323 段、三份词表行数；APK 只打包阅读所需的正文和词表，不打包源压缩包与审校材料。

本地磁盘空间紧张时可把缓存与构建结果放到另一磁盘：

```powershell
$env:GRADLE_USER_HOME='E:\ContextoTO-build\gradle'
$env:CONTEXTOTO_BUILD_ROOT='E:\ContextoTO-build\output'
.\gradlew.bat assembleDebug
```

模拟器参考比例为 1080×2376、440 dpi，与参考图的 1440×3168 等比。可通过参数调整模拟器尺寸与密度，`-Reset` 恢复默认值；脚本只接受 `emulator-*` 设备：

```powershell
.\tools\set_emulator_scale.ps1 -Width 1080 -Height 2376 -Density 440
```

应用内「接口与外观设置」的「正文比例」可分别调整字号（16–28 sp）、行距（125%–200%）、左右边距（12–44 dp）与段落间距（12–72 dp），「参考图」可一键恢复本次截图使用的组合。句子浮层英文原句随字号按 1.12 倍显示。界面顶部额外留出约 0.8 行正文高度；文章列表可从正文横向拖出。设置页可手动检查 GitHub 最新正式版并前往下载。

## 模型设置与数据

应用默认使用 DeepSeek 官方 OpenAI Chat Completions 接口：`https://api.deepseek.com`、`deepseek-flash`。句子分析使用中等推理强度、单词分析使用较高推理强度。测试环境可在应用设置中切换为 Command Code GOAT；两者的模型 ID 分别配置，不写入 API Key。首次使用在应用内输入自己的密钥，密钥经 Android Keystore 加密后保存在设备上。密钥不会加入仓库或 APK。模型服务不可用时，文章和预置词典仍可离线阅读。

英文正文当前随 APK 提供 [Tinos](https://github.com/googlefonts/tinos)，这是与 Times New Roman 版心尺寸兼容的开源衬线字体；许可见 `licenses/TINOS-OFL.txt`。如需在公开 APK 中使用原版 Times New Roman，须另取得可再分发的字体授权。

上下文义项按原文词位缓存，通用义项按词元单独缓存；句子分析按句子内容哈希缓存。缓存键包含服务、模型与提示词版本。查询成功后才记录已查词/句子。模型返回的从句区间会与原文逐字校验，不匹配时不着色。

## 发布

`v*` 标签触发 GitHub Actions 构建与发布 APK。签名材料通过仓库 Actions Secrets 提供，构建日志和产物均不包含 API Key。签名密钥的持续保管很重要：后续版本必须使用同一密钥才能覆盖安装。

详见 [PRODUCT_PLAN.md](PRODUCT_PLAN.md)。
