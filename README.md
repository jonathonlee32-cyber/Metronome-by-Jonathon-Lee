# Metronome

一个简单的 Android 节拍器应用。

## Features

* BPM 调节
* 节拍播放
* 简洁界面
* Android 16 支持
* 无广告，不盈利
* BPM 测速（Tap BPM）：按节奏连续点击 Tap 按钮，同时得到实时 BPM 与平均 BPM，选一个直接应用到节拍器
* 调音器入口：首页已预留入口，页面提示"此功能暂未开放"，功能尚未实现
* 每日时间记录：自动记录每次练习的开始 / 结束时刻与时长，按日期分组统计今日、本周、累计练习时间

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

## Tech Stack

* Kotlin
* Android SDK
* Jetpack Compose
* 本地存储：App 私有目录下的 JSON 文件（无第三方依赖）

## Author

Jonathon Lee

Xiamen University

学号 22920262203493

邮箱 jonathonlee32@gmail.com

## Version

v2.1
