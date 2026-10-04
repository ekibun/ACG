# 测试媒体：silent-black-25s.mp4

- **来源**：androidx/media（Media3）`libraries/test_data/src/test/assets/media/mp4/silent-black-25s.mp4`，
  `release` 分支（tree `8c6678b657ede1e7883fc164ef73ed483c7796c3`），Apache License 2.0。
- **抓取**：2026-10-02。
- **用途**：`FFPlayerSeekConvergenceTest` —— TODO B8（seek 后丢帧收敛的真路径回归）与
  B9（关键帧落点上 `stepBack`）的测试素材。
- **ffprobe 核定**：h264 High 320×240 30fps、25.000s、750 帧（tb 1/15360，**解码序 PTS 严格
  单调，无 B 帧**）+ AAC 44.1kHz 单声道；关键帧在 0 / 8.333 / 16.667s。
- 画面全黑 + 静音音轨：测试断言全部基于 **PTS**（与参考解码逐帧对帧），不比像素。
