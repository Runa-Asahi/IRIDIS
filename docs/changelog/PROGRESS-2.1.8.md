# 2.1.8（vc186，2026-10-23）

release/2.1.8 = release/2.1.7（43c1ae5）依次合并 fix/voice-echo、fix/send-hang、fix/search-query、perf/imagegen。

## 语音：外放回声
- 外放（扬声器）时麦克风半双工：朗读期间及结束后短暂尾音窗口（450 ms，按残留播放时长最多再延 1.5 s）内关闭收音，耳机 / 蓝牙不受影响（`HalfDuplexGate`、`OutputRoute`）。
- 回声文本过滤：识别结果若与刚朗读的内容高度重合，判定为自己的回声并丢弃，不再被当成用户的话（`EchoTextGuard`）。

## 聊天：一发送就卡住
- 模型未就绪时发送：立即显示回答气泡「正在加载模型…」，可点「停止」；加载失败在该气泡上显示「模型加载失败」。
- 回复看门狗（`ReplyWatchdog`）：90 s 内无任何输出（长提示按每 token +30 ms 放宽，最多 600 s；等模型加载阶段 180 s）即停止，气泡显示「长时间没有输出，已停止。可重试或换模型」，同时取消引擎和任务。
- 预热超时：`EngineManager` 预热经 `WarmUpGuard` 包裹，60 s 未完成即取消并提示，取消可正常传递。
- busy 状态修正：`busy()` = 有正在流式的气泡或任务仍在运行（`ReplyBusy`）；各清理流程只清除自己那条回复的 streamingId，不会误清别的回复。

## 智能体：搜索参数解析加固
- 单引号的值 / 对象（`'query': '…'`）先修复再宽松解析。
- 无引号的值（`"query": 诡秘之主 小说`）自动补引号，直到 `,` 或 `}`（`JsonRepair.repair` 与 `gemmaArgs`）。
- 数组形式的 query 用空格拼接后搜索（web_search）。
- 未转义的内嵌引号保留（`""三体" 小说"`）。
- 中文查询经每个搜索 URL 构造器往返测试。

## 生图：NPU 性能与内存
- 去噪循环前后发 HTP DCVS v3 性能投票（burst / relax，`nativeSetPerf`、`HtpPerf`），生成时拉满、结束后放松。
- 最后一次生成后空闲 60 s 自动释放 NPU 计算图（`IdleReleaser`，生图页与 `generate_image` 共用）；离开生图页也会安排释放。
- 仍**未真机验证**：性能投票与释放时机只在 JVM 上测过。

## 合并说明
- 四个分支都基于 43c1ae5，改动文件互不重叠，自动合并无冲突；新增测试位于 `v218/`（imagegen 测试在 `v217/imagegen/`）。
- gitignored 依赖（`third_party/` 含 qnn-api、`app/libs/*.aar`）从 wt-217 复制，不提交。

## 测试
- 新增 V218ReleaseTest（版本号 186 / 2.1.8、四个分支的关键改动都在、文档存在）。
- V217ReleaseTest 版本号检查改为 ≥ 185。
- 各分支自带：EchoGuardTest、SendHangTest、WarmUpGuardTest、ReplyBusyTest、SearchQueryTest、IdleReleaserTest、QnnSdPipelineTest（更新）。
