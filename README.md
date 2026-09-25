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

应用内「接口与外观设置」的「正文比例」可分别调整字号（16–28 sp）、行距（125%–200%）、左右边距（12–44 dp）与段落间距（12–72 dp），「参考图」可一键恢复本次截图使用的组合。句子浮层从顶部安全留白后开始排版，英文原句随字号按 1.12 倍显示。界面顶部额外留出约 0.8 行正文高度；文章列表可从正文横向拖出。正文标题采用 small caps，完整显示且支持短按单词和长按句子分析；目录标题最多显示两行。设置页全屏磨砂、底部固定保存，可检查 GitHub 最新版本（含预发布版）并在应用内下载，下载进度以浮层展示；取消、网络失败或应用进入后台会删除未完成文件，校验成功后交给系统安装器。「正文标记已查询词」与「静默推理」开关均默认关闭；前者不影响查询记录或缓存，后者关闭时不会发起后台模型请求。右上角百分比显示当前文章的句子缓存进度，点击仍可查看已有缓存。

「碎片专注」可选填 408 Dashboard 的 HTTPS 上报链接。应用读取 `/catalog` 后展示科目、事项及其真实数字 ID；仅当阅读页在前台时，以固定来源 `contextoto` 每 20 秒向 `/frame` 发送一次 focus，离开阅读页立即发送 idle。409 冲突或当日结算时停止上报；未配置链接时不会访问该服务。含访问令牌的链接由 Android Keystore 加密保存在本机，不预置于 APK 或仓库。

## 模型设置与数据

应用默认使用 DeepSeek 官方 OpenAI Chat Completions 接口：`https://api.deepseek.com`、`deepseek-flash`。句子分析使用中等推理强度、单词分析使用较高推理强度。测试环境可在应用设置中切换为 Command Code GOAT；两者的模型 ID 分别配置，不写入 API Key。首次使用在应用内输入自己的密钥，密钥经 Android Keystore 加密后保存在设备上。密钥不会加入仓库或 APK。模型服务不可用时，文章和预置词典仍可离线阅读。

英文正文当前随 APK 提供 [Tinos](https://github.com/googlefonts/tinos)，这是与 Times New Roman 版心尺寸兼容的开源衬线字体；许可见 `licenses/TINOS-OFL.txt`。如需在公开 APK 中使用原版 Times New Roman，须另取得可再分发的字体授权。

上下文义项按原文词位缓存，通用义项按词元单独缓存；句子分析按句子内容哈希缓存。缓存键包含服务、模型与提示词版本。启用静默推理后，当前文章的句子会在后台逐句串行分析，命中缓存的句子不会重复请求；出错后停止，需在进度浮层中手动重试。切换文章会取消当前请求；后台处理仍会消耗所选模型服务的额度。静默缓存不计作用户主动查询，只有用户打开句子时才记录已查句。模型返回的从句区间会与原文逐字校验，不匹配时不着色。

## 发布

`v*` 标签触发 GitHub Actions 构建与发布 APK。签名材料通过仓库 Actions Secrets 提供，构建日志和产物均不包含 API Key。签名密钥的持续保管很重要：后续版本必须使用同一密钥才能覆盖安装。

详见 [PRODUCT_PLAN.md](PRODUCT_PLAN.md)。
