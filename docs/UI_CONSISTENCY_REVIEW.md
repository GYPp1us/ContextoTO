# 前端交互与布局审查

审查基线为 `feat/landscape-adaptive-layout` 的 `7d6315e`。交互问题记录于 2026-11-15，补充布局走查于 2026-11-16。

## 交互审查发现

| 编号 | 发现 | 修复方向 |
| --- | --- | --- |
| P1.1 | 横屏下在 A 库打开删除确认，再切到 B 库，点击原确认区实际删除 B。已用两份测试词库复现。 | 打开确认时锁定词库 ID，按钮显示库名，切换词库即关闭确认。 [词库管理](https://github.com/GYPp1us/ContextoTO/blob/7d6315ece565d296aafe9db0054fc9f8544a24a5/app/src/main/java/site/arcol/contextoto/BankManagement.kt#L92) |
| P1.2 | N03 单词详情的「设置接口」关闭当前查询，返回只到正文。相同缺 Key 状态下，句子详情的「重试解析」重复原提示。已复现。 | 词、句和任务共用缺配置的恢复路径，直达模型与连接设置，保存后回到原词或原句。 [查词入口](https://github.com/GYPp1us/ContextoTO/blob/7d6315ece565d296aafe9db0054fc9f8544a24a5/app/src/main/java/site/arcol/contextoto/MainActivity.kt#L948) · [句子失败界面](https://github.com/GYPp1us/ContextoTO/blob/7d6315ece565d296aafe9db0054fc9f8544a24a5/app/src/main/java/site/arcol/contextoto/MainActivity.kt#L1604) |
| P2.1 | 未点击「保存并返回」时，深色主题开关已写入，正文字号草稿随返回丢弃。实测字号从 21 sp 恢复为 20 sp。静默推理开关也即时提交。 | 统一设置的提交时机，明确区分预览与持久化，返回时处理未保存的改动。 [即时写入](https://github.com/GYPp1us/ContextoTO/blob/7d6315ece565d296aafe9db0054fc9f8544a24a5/app/src/main/java/site/arcol/contextoto/MainActivity.kt#L1019) · [保存入口](https://github.com/GYPp1us/ContextoTO/blob/7d6315ece565d296aafe9db0054fc9f8544a24a5/app/src/main/java/site/arcol/contextoto/SettingsPage.kt#L272) |
| P2.2 | 生词详情跳原文后，系统返回能恢复详情，正文缺少对应入口。词库导入完成回到复习页，需重新查找新库。两条路径均已实测。 | 在原文提供「返回生词详情」，在导入结果提供「打开文章」或「查看词库」。 [原文跳转](https://github.com/GYPp1us/ContextoTO/blob/7d6315ece565d296aafe9db0054fc9f8544a24a5/app/src/main/java/site/arcol/contextoto/MainActivity.kt#L888) · [导入结果](https://github.com/GYPp1us/ContextoTO/blob/7d6315ece565d296aafe9db0054fc9f8544a24a5/app/src/main/java/site/arcol/contextoto/ImportAndGuide.kt#L81) |
| P2.3 | 顶栏百分比会在句子缓存、今日复习和查询进度之间切换。纯本地导入复用了「等待首字返回」等模型阶段文案。 | 给不同指标加固定名称，本地与模型任务各显示实际阶段。 [阅读进度](https://github.com/GYPp1us/ContextoTO/blob/7d6315ece565d296aafe9db0054fc9f8544a24a5/app/src/main/java/site/arcol/contextoto/MainActivity.kt#L332) · [生词进度](https://github.com/GYPp1us/ContextoTO/blob/7d6315ece565d296aafe9db0054fc9f8544a24a5/app/src/main/java/site/arcol/contextoto/StudyPage.kt#L113) · [导入反馈](https://github.com/GYPp1us/ContextoTO/blob/7d6315ece565d296aafe9db0054fc9f8544a24a5/app/src/main/java/site/arcol/contextoto/ImportAndGuide.kt#L97) |
| P2.4 | 专注上报的启停条件漏掉生词主页面。已配置时，由阅读切到复习或列表仍可能累计阅读时间。此项来自源码检查。 | 由当前页面统一驱动专注状态和实际请求。 [专注启停](https://github.com/GYPp1us/ContextoTO/blob/7d6315ece565d296aafe9db0054fc9f8544a24a5/app/src/main/java/site/arcol/contextoto/MainActivity.kt#L484) |
| P2.5 | 浅色纸面上，操作绿约为 4.06∶1，错误提示使用的暖色约为 3.42∶1。离线词条缺少本句释义时，详情直接从「02 MEANINGS」开始。 | 按文字用途校准操作色与错误色，普通文字以 4.5∶1 为参考。用章节名称组织可选内容。 [配色](https://github.com/GYPp1us/ContextoTO/blob/7d6315ece565d296aafe9db0054fc9f8544a24a5/app/src/main/java/site/arcol/contextoto/MainActivity.kt#L154) · [词条章节](https://github.com/GYPp1us/ContextoTO/blob/7d6315ece565d296aafe9db0054fc9f8544a24a5/app/src/main/java/site/arcol/contextoto/MainActivity.kt#L1704) · [对比度参考](https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html) |

## 布局与横滑走查

子代理独立比对了四面板的布局、仓库截图和拖动代码。设备复验采用离线 Android 35 模拟器，分辨率为 1080 × 2340、密度为 440 dpi。同一篇正文和同一道复习题在横竖屏分别采集了起点、拖动中和吸附后的画面，另录制了两个设置入口的过渡。

1. **优先修复 · 设置返回可停在裁切状态。** 横屏从目录进设置，按系统返回后趁滑出动画尚未结束立刻右拖，松手后实测左边是裁掉开头的设置表单，右边是正文，目录未显示。目录锚点会保留原本等待阅读锚点清除的 `settingsRetained`，从而继续隐藏目录。应在手势接管和吸附改目标时同步结束旧面板退场，并验证连滑后画面与目标一致。 [面板保留状态](https://github.com/GYPp1us/ContextoTO/blob/7d6315ece565d296aafe9db0054fc9f8544a24a5/app/src/main/java/site/arcol/contextoto/MainActivity.kt#L275) · [目录显示条件](https://github.com/GYPp1us/ContextoTO/blob/7d6315ece565d296aafe9db0054fc9f8544a24a5/app/src/main/java/site/arcol/contextoto/MainActivity.kt#L583) · [手势接管](https://github.com/GYPp1us/ContextoTO/blob/7d6315ece565d296aafe9db0054fc9f8544a24a5/app/src/main/java/site/arcol/contextoto/MainActivity.kt#L614)

2. **设置进场的两条路径缺少一致的层级。** 录屏中，目录的标题、文章列表和底栏仍清晰可读时，设置的标题、开关和保存栏已从其下方滑入，产生叠字。生词菜单进入同一设置页会短暂扫过复习和正文。源码中两个入口沿用同一个 340 ms 吸附时长，移动距离分别为 0.4 和 2.6 个屏宽。建议给进出场内容各自的裁切或纸面层，并让设置直接承接来源页面。 [目录退场](https://github.com/GYPp1us/ContextoTO/blob/7d6315ece565d296aafe9db0054fc9f8544a24a5/app/src/main/java/site/arcol/contextoto/MainActivity.kt#L898) · [设置进场](https://github.com/GYPp1us/ContextoTO/blob/7d6315ece565d296aafe9db0054fc9f8544a24a5/app/src/main/java/site/arcol/contextoto/MainActivity.kt#L1010) · [吸附时长](https://github.com/GYPp1us/ContextoTO/blob/7d6315ece565d296aafe9db0054fc9f8544a24a5/app/src/main/java/site/arcol/contextoto/Interaction.kt#L157)

3. **阅读与生词顶栏的对齐基准随页面变动。** 竖屏关闭沉浸状态栏时，阅读的专注状态在第二行，生词的在第一行。同一设备上两处状态的垂直位置相差约 97 dp，半滑时能在同一画面看到两处状态分列两条水平线。横屏阅读还把可调正文边距和限宽传给顶栏，生词顶栏使用固定 24 dp。建议共用顶部内边距、状态行基线和状态槽，正文宽度单独计算。 [阅读页边距](https://github.com/GYPp1us/ContextoTO/blob/7d6315ece565d296aafe9db0054fc9f8544a24a5/app/src/main/java/site/arcol/contextoto/MainActivity.kt#L647) · [阅读顶栏](https://github.com/GYPp1us/ContextoTO/blob/7d6315ece565d296aafe9db0054fc9f8544a24a5/app/src/main/java/site/arcol/contextoto/MainActivity.kt#L728) · [生词顶栏](https://github.com/GYPp1us/ContextoTO/blob/7d6315ece565d296aafe9db0054fc9f8544a24a5/app/src/main/java/site/arcol/contextoto/StudyPage.kt#L202)

4. **生词菜单拖动时，次要工具先于主导航变清楚。** 两组共用侧面板淡入，只有复习、列表、词库再乘一层分段淡入。源码在半程的透明度约为工具 50%、主导航 20%–25%，采帧也能看到主次先后。目录把同类工具压在底部，生词菜单横屏把工具与导航一起放在上半区，两列行距各自累加。建议统一工具组锚点和两列行网格，拖动时保证主导航先建立层级。 [目录截图](images/landscape/06-directory-wide.png) · [生词菜单截图](images/landscape/12-word-menu-wide.png) · [显隐与间距](https://github.com/GYPp1us/ContextoTO/blob/7d6315ece565d296aafe9db0054fc9f8544a24a5/app/src/main/java/site/arcol/contextoto/StudyPage.kt#L328)

5. **横屏复习的题词和右栏操作不共享重心。** 题词按左栏可用视口居中，短选项从右栏顶部排起。仓库示例中，题词中心比四个选项与三个自评按钮组成的操作组中心低约 35 dp。复习使用 38%／62% 分栏，词库使用 44%／56%，同尺寸下右栏起点切换约 44 dp。建议短题词与操作组对齐，长内容使用统一顶线，并给复习和词库共用一条右栏起线。 [复习截图](images/landscape/08-review-wide.png) · [词库截图](images/landscape/13-libraries-wide.png) · [复习布局](https://github.com/GYPp1us/ContextoTO/blob/7d6315ece565d296aafe9db0054fc9f8544a24a5/app/src/main/java/site/arcol/contextoto/StudyPage.kt#L463) · [双列布局](https://github.com/GYPp1us/ContextoTO/blob/7d6315ece565d296aafe9db0054fc9f8544a24a5/app/src/main/java/site/arcol/contextoto/AdaptiveLayout.kt#L54)

6. **双列目录的底边会随标题行数错位。** 源码只限制标题最多两行。同排一行标题和两行标题的词、句统计以及当前卡的高亮底边会差一行。可用短标题导入专项复验，双列给标题预留两行的高度或对齐统计区底线。 [双列目录卡片](https://github.com/GYPp1us/ContextoTO/blob/7d6315ece565d296aafe9db0054fc9f8544a24a5/app/src/main/java/site/arcol/contextoto/MainActivity.kt#L1305)

## 修复

- 删除确认绑定词库 ID，设置以独立动画从来源页滑入，查询对象和来源详情在设置期间保留。开关与表单一起保存，放弃前会询问。
- 导入结果直达新增文章或目标词库，原文可直接返回生词详情。顶栏进度按任务命名，离开阅读会暂停专注上报。
- 阅读与生词使用共用顶栏，左右侧面板工具固定在相同底部位置。横屏复习与词库共用列线，双列目录为标题预留两行。
