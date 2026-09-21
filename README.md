# Metronome

一个简单的 Android 节拍器应用。

## Features

* BPM 调节
* 节拍播放
* 简洁界面
* Android 16 支持
* 无广告，不盈利
* BPM 测速（Tap BPM）：按节奏连续点击 Tap 按钮，同时得到实时 BPM 与平均 BPM，选一个直接应用到节拍器
* 调音器：麦克风实时采集 + 本地基频检测（YIN），识别 A3–B6 范围内的单音，显示音名与音分偏差，±10 音分内变绿；A4 基准可在 432–448Hz 之间调整
* 每日时间记录：自动记录每次练习的开始 / 结束时刻与时长，按日期分组统计今日、本周、累计练习时间
* 竖屏 / 横屏分别适配：竖屏保持原有单列布局，横屏主页面、BPM 测速页和调音器页使用专门的三栏布局（横屏页面整屏显示，不需要滚动）

## Screenshots

### Splash

<img src="/screenshots/Screenshot_2026-09-21-15-00-57-456_com.example.m.jpg" width="250">

### Main

<img src="/screenshots/Screenshot_2026-09-21-15-01-01-766_com.example.m.jpg" width="250">

<img src="/screenshots/Screenshot_2026-09-21-15-01-04-736_com.example.m.jpg" width="250">

### Records

主界面右上角"练习记录"进入，展示统计区域和按日期分组的练习明细。

### Tap Tempo

主界面左上角"BPM 测速"进入，显示实时 / 平均两个 BPM，选择其中一个后点"应用到节拍器"即可。

### Tuner

主界面中间"调音器"进入。第一次进入会申请麦克风权限；授权后立即开始实时检测，
中央显示当前音名与音分偏差（例如 `A4` / `+5`），偏差在 ±10 音分内时中央区域变绿。
右上角齿轮进入设置，用 `[-] 440 Hz [+]` 调整 A4 基准（432–448Hz，用于十二平均律计算）。
识别不到可靠单音时右上角 `NOISE` 变蓝，中央保留上一次的识别结果。

## Tech Stack

* Kotlin
* Android SDK
* Jetpack Compose
* 音高检测：全部本地实现，不依赖任何音频识别库或 AI 模型 —— `AudioRecord` 采集单声道 16bit PCM，
  自己实现 YIN 基频检测（差值函数 + 累积均值归一化 + 抛物线插值），配 RMS 与置信度阈值、跨帧中值平滑
* 本地存储：App 私有目录下的 JSON 文件（无第三方依赖）
* 权限：只申请 `RECORD_AUDIO`；没有 `INTERNET` 权限，调音器完全离线、不上传任何音频

## Author

Jonathon Lee

Xiamen University

学号 22920262203493

邮箱 jonathonlee32@gmail.com

## Version

v_3.2
