# 单词关闭思考实验

日期：2026-10-06。基线为 `v1.0.0-rc5`。只做实验，没有修改应用、数据库、发布标签或预置缓存；没有使用 DeepSeek 官方密钥。

后续纠正：用户明确要求每个模块独立请求、限定最小输入输出。本文测试的仍是 rc5 的合并模板 / 字段补缺，耗时与 token 数据不能直接代表 rc6 的独立模块。新范式和初始思考策略以 [RC6 实现约定](RC6_REQUIREMENTS.md) 为准；以下保留当时实验事实，不倒改测量结果。

## 结论

CC 上的单词查询可以真正关闭思考，速度和输出 token 用量明显降低。本组语境释义人工阅读未见明显错义，但完整词汇数据仍有原型混入派生词、重复派生词、释义不够稳定等问题。开启思考也出现类似问题，不能把它等同于质量保证。

建议 rc6 的单词查询采用可选择的快速模式，默认关闭思考，保留深度模式；无论哪种模式都做模块校验。句子分析保持现有策略，本次没有测试句子关闭思考，不作外推。先修正出现位置相关的原型识别与派生词校验，再生成两篇文章的预制解析。

你指定的 `you are a helpful assistent.` 首行已按原文测试，大小写和拼写均未更改。暂未看到足够证据证明它能稳定改善质量或延迟，不建议仅依赖此前缀解决问题。

## 请求与参数

- 提供商：Command Code GOAT；模型固定为 `deepseek/deepseek-v4.1-flash`，没有自动换模型。实时目录与所有成功回复的模型 ID 均一致。
- 接口：CC `/provider/v1/chat/completions`。沿用应用的中文 system 指令、完整原段、目标词位、所在句及预置词典；只改变推理参数，或在附加实验中只增加指定首行。
- 依照 OpenAI Docs 核对流式完成状态与最终 usage；使用 JSON object 格式、SSE、`include_usage`，未设置温度。为限制单次实验，统一设置 `max_tokens=8192`；所有成功请求均正常 stop，无截断。
- 记录请求开始至首个答案正文和完整返回的时间。推理文本不保存、不展示，只记录字符数量与服务端 reasoning token 计数。
- CC 密钥仅传入临时进程环境，未写入脚本、结果文件或仓库。第一次 Python 默认客户端标识被 Cloudflare 拦截，改用应用相同的 `okhttp/4.12.0` 标识后成功；不把网关 403 当成模型质量结果。

流式字段参照 [OpenAI Chat Completions](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create)；CC 的接口和流式用量约定见 [CC Provider API](https://commandcode.ai/docs/provider)。

单个 `Charges` 完整查询的参数探针结果：

| 参数 | HTTP | 是否真正关闭思考 | 服务端推理 token |
| --- | --- | --- | ---: |
| `reasoning_effort=high` | 200 | 否 | 2457 |
| `reasoning_effort=none` | 400 | 请求被拒绝 | — |
| 仅 `thinking.type=disabled` | 200 | 否 | 3365 |
| `none` + `thinking.type=disabled` | 400 | 请求被拒绝 | — |
| `reasoning_effort=off` | 200 | 是 | 0 |
| `off` + `thinking.type=disabled` | 200 | 是 | 0 |

CC 的错误返回明确列出 `off/low/medium/high/xhigh/max`，因此此次用 `off` 作对照。这是 CC 网关行为，不应把 `off` 原样推广到所有提供商。[DeepSeek 官方文档](https://api-docs.deepseek.com/api/create-chat-completion/)另外支持 `thinking.type=disabled` 和 `reasoning_effort=none`；本次仅查文档，没有向官方模型发请求。

## 主对照：11 组，22 次成功请求

完整查询 8 组：N01 的 revived、advanced、Charges、grounds；N02 的 Justice、bench、bearing、adopted。另有 Charges、bench、bearing 的本句释义补缺 3 组。每组 high/off 各一次，交替先后顺序，串行运行。

| 查询范围 | 模式 | 样本数 | 首个答案正文中位数 | 总耗时中位数 | 合计输出 token（含推理） | 合计推理 token |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| 完整四模块 | high | 8 | 18.37 s | 19.43 s | 20567 | 18656 |
| 完整四模块 | off | 8 | 2.98 s | 4.79 s | 2243 | 0 |
| 只补本句释义 | high | 3 | 6.43 s | 6.43 s | 1400 | 1253 |
| 只补本句释义 | off | 3 | 2.26 s | 2.72 s | 114 | 0 |

完整查询总耗时中位数约为原来的 25%，输出 token 合计减少约 89%；只补本句的总耗时中位数约为原来的 42%。这些不是服务延迟承诺或计费金额估算。

22/22 为完整、应用可接收的 JSON。high/off 各 10/11 通过额外的短词性标签、证据连续子串等检查；这些检查比当前应用接收规则更严格，但仍不是语义正确率。

人工抽查：

- `Justice Clarence Thomas` 两种模式均解释为大法官，而非机械选择“正义”。
- `work on the bench` 两种模式均识别法官职位 / 司法工作，而非长凳。
- `security grounds are advanced` 两种模式均识别“提出 / 援引理由”。
- `when it was adopted` 两种模式均识别宪法正式通过，而非收养。
- `bearing his name` 两种模式均识别“以他的名字命名”。
- off 的 advanced 连续列了两个相同 `advancement`，并把原形 `advance` 放进派生词。high 也把 `advance` 放进派生词，并列出罕用项；是否适合教学需要另行字典核验，不能只按数量验收。
- off 的 revived 证据包含 `...`，不是可精确定位的连续原文；high 的 bearing 补缺把词性写成 `v.（现在分词）`，不是纯缩写。两者需要局部规范化，不需要重查全部模块。
- 初始参数探针里，off / both_off 的 `charger` 中文义项包含未翻译的 `charger`；说明格式合法并不能保证释义完整。

额外发现输入侧问题：当前本地候选算法会优先命中已有条目 `advanced`、`bearing`，而两处动词出现的原型应分别为 `advance`、`bear`。模型仍能解对本句义，但通用义与派生词会混入形容词 / 名词条目。此问题不能归因于是否关闭思考；原型应结合当前出现来确定，不能只靠拼写命中顺序。

## 指定英文首行：2 组，4 次成功请求

只在 system 最前面加 `you are a helpful assistent.` 和换行，其余指令、原文、模块、模型、参数均不变。复测 advanced 完整查询及 bearing 本句补缺，每组 high/off 各一次。

| 样本 | 模式 | 无首行总耗时 | 加首行总耗时 | 观察 |
| --- | --- | ---: | ---: | --- |
| advanced 全模块 | high | 26.87 s | 56.92 s | 派生词返回空数组，推理 token 从 3164 增至 7566 |
| advanced 全模块 | off | 4.49 s | 7.58 s | 两个相同 advancement 仍然存在 |
| bearing 本句 | high | 4.82 s | 4.35 s | 词性从含中文说明的标签变为 v. |
| bearing 本句 | off | 2.72 s | 2.51 s | 仍是正确的命名语境与 v. 标签 |

附加 4 次全部可接收，off 的 reasoning token 仍为 0。每个条件仅一次，生成有随机性；不能把上述变化证明为前缀的因果效果，也不能证明它对其他单词或句子有稳定收益。

## 建议的后续策略（未实现）

1. 推理开关按提供商适配；CC 的 off 与官方的 disabled / none 分开映射，不改变已有缓存身份。
2. 单词快速模式优先补缺，校验失败只重试失败模块；保留手动深度重新生成，不能暗中清空模块或提高费用。
3. 原型、当前拼写音标、本句义和词元通用义分开；先解决出现相关原型，再写入正确词元的共享缓存。保留旧条目与历史出现，不把含混合词性的旧义项直接搬到另一词元。
4. 派生词做去重、原型排除与词典存在性检查，允许空数组；不要为了凑四个而填充。英文词性标签在本地统一缩写。
5. 此次报告不代替 rc6 功能实现、迁移、截图验收及发布。两篇文章全量预制、通用词库和导入导出均尚未开始。

复现实验脚本：`tools/experiment_word_reasoning.py`。原始最终答案和计时保留在 E 盘 `ContextoTO-build/rc6-word-experiment/`，不含推理原文或密钥。建议每个条件重复更多次，并独立核验字典信息后再扩大到全量预制。
