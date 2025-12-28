# E-control (For Car) 🚗💨

> **下一代车载投屏控制终端 | Next-Gen Car Screen Mirroring & Control**
>
> 基于 Scrcpy 核心，专为车载环境打造的“液态玻璃”风格高性能控制客户端。

[在此处插入一张新的主界面截图]

## 📖 项目简介 | Introduction

**E-control** 是基于 [Easycontrol_For_Car](原项目链接请替换这里) 的深度重构版本。
原项目提供了一个基础的控制思路，而 **E-control** 致力于将其打造为一款**真正可用的、现代化的、符合车规级审美**的商业级应用。

本项目解决了原版在 Android 15+ 上的兼容性问题，大幅优化了触控延迟，并引入了全新的 **"Liquid Glass" (液态玻璃)** 设计语言，使其完美融入现代智能座舱的夜间驾驶环境。

---

## 🚀 核心改动与动机 | Key Changes & Motivations

我们不仅仅是修补了 Bug，而是重新定义了体验：

### 1. 🎨 UI/UX 全面重构：Liquid Glass 风格
* **改动**：抛弃了原版简陋的白色列表风格，采用了类似 iOS/CarPlay 的深色磨砂玻璃设计（Dark Glassmorphism）。
* **动机**：
    * **夜间驾驶安全**：纯黑深邃背景有效降低车载屏幕的光污染，夜间开车不刺眼。
    * **视觉层级**：通过悬浮卡片和霓虹强调色（Electric Blue），让驾驶员/副驾能一眼识别关键操作。
    * **现代化**：告别“工程机”界面，带来符合 2025 年审美的科技感。

### 2. ⚡ 核心内核升级：Scrcpy v3.1
* **改动**：将底层 Scrcpy Server 从 v2.x 升级至最新的 **v3.1**。
* **动机**：
    * **修复崩溃**：彻底解决了在 **Android 15** 及部分 Xiaomi HyperOS 设备上因 `SurfaceControl` API 变更导致的启动即崩溃（Aborted）问题。
    * **更强性能**：享受 Scrcpy 最新版带来的编码效率提升和稳定性。

### 3. 🎮 极致性能与低延迟
* **改动**：
    * 启用 `TCP_NODELAY` 禁用 Nagle 算法。
    * 强制 H.264 编码与文件推送校验逻辑。
    * 新增触控点显示（Show Touches）。
* **动机**：
    * **跟手度提升**：原版触控有明显的“肉感”，优化后触控响应如丝般顺滑，接近本地操作。
    * **连接稳定性**：解决了“Server 文件丢失”导致的连接失败，确保每次上车都能 100% 连接成功。

### 4. 🎵 独家功能：纯音频模式 (Audio Only)
* **改动**：新增“黑屏仅音频”模式开关。
* **动机**：
    * **隐私与节能**：只想听手机里的歌或导航语音，不想让画面干扰视线？开启此模式，传输音频的同时保持黑屏，且依然支持触控操作。

### 5. ↔️ 人性化交互
* **改动**：全屏控制栏新增“左/右侧切换”功能。
* **动机**：无论你是主驾操作，还是副驾娱乐，一键切换侧边栏位置，不再遮挡视线，适应不同坐姿习惯。

---

## 🛠️ 技术细节 | Technical Details

* **Architecture**: Android Native (Java)
* **Core**: Scrcpy Server v3.1 (Embedded Assets)
* **Video Codec**: H.264 (Forced for compatibility)
* **Audio**: Oboe / Opus encoding
* **Design System**: Custom Liquid Glass Resources (No heavyweight Material dependencies)

---

## 🤝 致谢与声明 | Credits

本项目是站在巨人肩膀上的成果。特别感谢以下开源项目：

1.  **[Genymobile/scrcpy](https://github.com/Genymobile/scrcpy)**:
    Android 投屏控制技术的基石。本项目使用了其 Server 端程序 (v3.1) 实现核心投屏与控制功能。
    *All hail the scrcpy developers!*

2.  **[原作者项目链接] (Original Repo)**:
    感谢原作者提供的 Easycontrol 基础架构与思路，本项目在其基础上进行了 Fork 与深度改造。
