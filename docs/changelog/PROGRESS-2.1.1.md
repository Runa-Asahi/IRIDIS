# IRIDIS 2.1.1 进度（2026-10-20）

versionCode 179。

## 改动
1. NPU：QNN 运行库新增 libQnnHtpPrepare.so（共 6 个文件，约 113 MB）；旧运行库判为需要修复。NPU 初始化失败时附 QNN/FastRPC logcat 尾。
   - 用户的 gemma-4-E2B-it_qualcomm_sm8750.litertlm 为 2026-04-17 原版（3016294400 B）。Google 于 2026-10-20 04:27 HKT 从 HF 删除了 qualcomm NPU 文件。
2. GenieX 第四引擎（详见 PROGRESS-2.2.0-geniex.md）：原生库外挂下载；GGUF 默认引擎设置 `ggufEngine`，默认 llama。
3. 目录布局：Download/IRIDIS = models/voice/skills/knowledge/config；智能体沙箱 = Download/IRIDIS/workspace；一次性迁移（标记 .iridis-layout-v2）。

## 待真机验证
- LiteRT NPU Gemma 4 E2B sm8750：修复运行库后能否加载；若仍失败，看报错里的原生日志。
- GenieX：运行库下载、Q4_0 GGUF NPU/GPU/CPU、停止、多轮、mmproj 视觉、AI Hub 包。
- 先用 LiteRT NPU 再用 GenieX QAIRT 需重启（两套 QNN 版本）。
- 目录迁移（全部文件访问 / SAF 两种模式）、voice 下载位置、config 导出默认位置。
