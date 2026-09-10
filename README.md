# AppVolumeBarHook

<p align="center">
  <img src="app/src/main/res/drawable/ic_app_volume.xml" width="96" height="96" alt="AppVolumeBarHook Logo" />
</p>

<p align="center">
  <strong>专为 Xiaomi HyperOS 打造的原生风格应用独立音量调节模块</strong>
</p>

<p align="center">
  <a href="https://github.com/Leaf-lsgtky/AppVolumeBarHook/releases"><img src="https://img.shields.io/github/v/release/Leaf-lsgtky/AppVolumeBarHook?style=flat-square" alt="Release" /></a>
  <a href="https://github.com/Leaf-lsgtky/AppVolumeBarHook/blob/main/LICENSE"><img src="https://img.shields.io/badge/License-GPL--3.0-blue.svg?style=flat-square" alt="License" /></a>
  <img src="https://img.shields.io/badge/Support-HyperOS%201.0%20%2F%202.0-orange?style=flat-square" alt="HyperOS Support" />
  <img src="https://img.shields.io/badge/LSPosed-Required-success?style=flat-square" alt="LSPosed Required" />
</p>

---

## 📖 项目介绍 (Introduction)

**AppVolumeBarHook** 是一款基于 Xposed / LSPosed 框架的 Xiaomi HyperOS 模块。

在展开系统的原生音量控制面板时，模块会自动检测当前后台正在播放声音的媒体应用程序（如音乐播放器、视频软件、游戏等），并在音量面板旁以**HyperOS 原生设计风格**注入独立的音量调节滑块与快捷管理入口。

从此听歌、刷视频、打游戏多任务同时进行时，无需来回切换 App，一键滑动即可调整各自的音量大小。

---

## ✨ 核心特性 (Features)

- 🎵 **智能活跃音源检测**：通过监听系统音频焦点（Audio Focus）与活跃音轨（Playback Track），只在有应用播放声音时动态展示该应用的音量条，轻量纯净不占地。
- 🎨 **HyperOS 原生级无缝融合**：
  - 严格适配 HyperOS 音量面板的视觉规范，采用与原生相同的圆角、高斯模糊、阴影层次与背景材质。
  - 按钮与滑块间距完美对齐，原生动画曲线跟手顺滑，与自带的静音/勿扰按钮和谐一体。
  - 音量面板展开/收起时平滑过渡，无任何突兀闪烁或遮挡。
- 📑 **多音频流分页与指示器**：
  - 当后台存在多个发声应用（>2个）时，自动启用水平滑动卡片分页。
  - 底部配有跟随滑动的优雅圆点指示器，当前页白色高亮，支持双向平滑切换。
- 🔊 **底层多音频流精准控制**：
  - 深度联动小米声音（MiSound）独立音频流引擎，实现精准的音量分贝与增益控制。

---

## 🎯 LSPosed 推荐作用域 (LSPosed Scope)

在 LSPosed 管理器中激活模块时，**仅需勾选以下两个应用作用域**：

| 应用名称 | 包名 | 说明 |
| :--- | :--- | :--- |
| **系统界面** | `com.android.systemui` | 负责原生音量面板视图的 Hook、按钮注入与展开收起动画联动 |
| **声音** / **小米声音** | `com.miui.misound` | 负责底层应用独立音量流（App Volume Stream）管理与音量调节控制 |

> ⚠️ **提示**：无需勾选「系统框架（Android System）」或其它三方应用。

---

## 📲 下载与安装 (Installation)

1. 从 [GitHub Releases](https://github.com/Leaf-lsgtky/AppVolumeBarHook/releases) 下载最新发布的 `AppVolumeBarHook-v*.apk` 安装包。
2. 安装后打开 **LSPosed** 作用域管理器，启用本模块并勾选 **系统界面** (`com.android.systemui`) 与 **声音** (`com.miui.misound`)。
3. 重启 **系统界面**（推荐使用 LSPosed 重启 SystemUI，或直接重启手机）。
4. 打开音乐或视频 App 播放声音，按下物理音量键展开音量面板，即可在面板左侧看到独立的应用音量条！

---

## 🛠️ 编译与开发 (Build from Source)

### 本地编译

本项目使用 Gradle 构建：

```bash
# 克隆仓库
git clone https://github.com/Leaf-lsgtky/AppVolumeBarHook.git
cd AppVolumeBarHook

# 编译 Debug APK
./gradlew assembleDebug

# 编译 Release APK (支持通过环境变量配置签名)
./gradlew assembleRelease
```

### 签名环境变量（可选）

如需打出签名包，可在环境变量中注入：
- `SIGNING_KEY`：Keystore 文件的 Base64 编码字符串
- `KEYSTORE_PASSWORD`：Keystore 密码
- `ALIAS`：密钥别名
- `KEY_PASSWORD`：密钥密码

### GitHub Actions 云端编译

仓库已配置完整的自动化构建工作流：
- 推送带有 `v*` 格式的 tag（例如 `git tag v1.0.0 && git push origin v1.0.0`），GitHub Actions 将自动拉取 Secrets 签名编译，并直接在 Releases 页面发布签名好的 APK！
- 也可以在 GitHub Actions 页面通过 `workflow_dispatch` 手动触发编译。

---

## 📄 开源许可 (License)

本项目采用 [GPL-3.0 License](LICENSE) 协议开源。
