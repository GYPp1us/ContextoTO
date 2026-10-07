# RC6 预置数据

## 通用词库

来源：[skywind3000/ECDICT](https://github.com/skywind3000/ECDICT)，仓库 [MIT 许可](https://github.com/skywind3000/ECDICT/blob/master/LICENSE) 原文保存在 `licenses/ECDICT-MIT.txt`。项目由多种上游词典资料汇集；保留来源说明，不把仓库许可扩大解释为所有上游资料的独立授权声明。

2026-10-06 下载的 `ecdict.csv` 原文件只保留在 E 盘，不打包、不提交。锁定快照的 SHA-256 位于 `general_10000.json.source_sha256`，以内容哈希而非可变的 master URL 标识实际输入。

`tools/build_general_bank.py` 选择含中文义且具有词频的 10,000 个独立词元：优先 `frq`，缺失时用 BNC 顺位；词元关系表中属于其他原型的变形不重复计数。常规屈折 / 比较级关系构建词形索引；单套字典 IPA 标为 UK，不伪造第二套 US。完整拼写集合仅用于派生词存在性校验，不作为已释义词库或学习队列。

复现需要自行取得相同源快照：

```powershell
python tools/build_general_bank.py --source E:\ContextoTO-build\rc6-data\ecdict.csv --output app/src/main/assets/general_10000.json
python tools/validate_presets.py
```

原考纲库、自定义库保持独立，通用库只读。生成内容和源哈希随 APK 发布；源大文件、临时结果及构建缓存不占 C/D 盘。

## 前两篇解析

N01 / N02 标题和正文共 120 个 Android 原生句子、2,582 个词位。由用户授权的 CC 测试提供商 `deepseek/deepseek-v4.1-flash` 独立模块生成；不是原先多模块模板的实验结果。IPA / 原型 / 义项 / 本句义 OFF，派生词 / 翻译 / 结构 MAX。

原型按具体出现确定；通用义和派生词按原型去重、音标按实际拼写去重、本句义按原句及词位保留。句子 gloss 覆盖全部词位，原文范围逐字校验。只保存最终 JSON、计时与用量；不保存推理原文或凭据。可恢复缓存保存在 E 盘。

最终 `first_two_analyses.zip` 约 400 KiB，内部只有：

- `manifest.json`：格式 / 版本及解析 JSON 的 SHA-256。
- `analyses.json`：标题、正文、句子和单词模块。

解析 JSON SHA-256：`80243f37cb205eff818e6fcc1c0a1589157332da91f0ebadf777ec0a184caf77`。

不包含学习队列、评分、热度、书签、阅读位置、查询事件、API 密钥或配置。首次成功安装按包哈希标记，事务内补缺；已有有效用户解析优先。重复启动不重复导入、不发模型请求、不计学习活动。

AI 解析经过结构校验，不等于全部语义已被人工审校；应用保留局部重新生成入口。离线使用预制内容不消耗额度。
