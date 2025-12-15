# WingCode - AI Coding Assistant

WingCode 是一款功能全面的 AI 编程助手，通过智能代码补全、AI 驱动的建议和无缝集成本地模型来提升开发者的工作效率。

## ✨ 特性

### 🚀 核心功能
- **智能代码补全**: 实时代码补全，基于 Tabby 提供快速、准确的代码建议
- **AI 驱动建议**: 通过右键操作获取上下文感知的代码建议
- **Git 提交优化**: 自动生成智能的 Git 提交信息
- **多 AI 提供商支持**: 支持 OpenAI、Claude、LM Studio 和自定义 API
- **本地模型集成**: 无缝集成 Ollama 等本地模型，保护隐私

### 🎯 隐私优先
- 所有 API 调用直接发送到您配置的提供商
- 不收集任何用户数据
- 支持本地部署，完全离线使用

### 🌐 多语言支持
- 中文界面
- English interface
- 智能语言检测

### 🔧 兼容性
支持所有 JetBrains IDE：
- IntelliJ IDEA
- PyCharm
- WebStorm
- GoLand
- CLion
- 以及其他基于 IntelliJ 平台的 IDE

## 📦 安装

### 方法一：JetBrains Marketplace（推荐）
1. 打开 JetBrains IDE
2. 进入 `File` > `Settings` (Windows/Linux) / `Preferences` (macOS)
3. 选择 `Plugins`
4. 搜索 "WingCode"
5. 点击 Install

### 方法二：手动安装
1. 从 [JetBrains Plugins](https://plugins.jetbrains.com/plugin/com.wenguang.ai.assistant) 下载插件
2. 在 IDE 中选择 `File` > `Settings` > `Plugins`
3. 点击齿轮图标 > `Install Plugin from Disk...`
4. 选择下载的插件文件

## ⚙️ 配置

安装完成后，通过以下方式配置 WingCode：

1. 打开 IDE 设置：`File` > `Settings` > `WingCode`
2. 选择您偏好的 AI 提供商：
   - OpenAI
   - Claude
   - LM Studio
   - 自定义 API
   - Ollama（本地）
3. 输入您的 API 密钥和配置
4. 根据需要调整代码补全和建议参数

## 🎮 使用指南

### 代码补全
- WingCode 提供实时代码补全
- 支持多种编程语言
- 基于 Tabby 的高效补全引擎

### AI 助手功能
- **右键菜单**: 在编辑器中右键点击访问 AI 助手
- **代码建议**: 获取上下文感知的代码改进建议
- **智能重构**: AI 驱动的代码重构建议

### Git 提交助手
- 在 VCS 提交对话框中点击 "Generate AI Commit Message"
- AI 自动分析更改并生成有意义的提交信息

## 🛠️ 开发环境设置

如果您想为 WingCode 做贡献或自行构建：

```bash
# 克隆仓库
git clone <repository-url>

# 打开项目
# 使用 IntelliJ IDEA 打开项目根目录

# 构建插件
./gradlew buildPlugin

# 在 IDE 中运行插件
./gradlew runIde
```

## 📋 系统要求

- JetBrains IDE (2024.1 或更高版本)
- Java 17 或更高版本
- 稳定的网络连接（用于 AI API 调用）

## 🤝 贡献

欢迎贡献代码！请遵循以下步骤：

1. Fork 本仓库
2. 创建您的特性分支 (`git checkout -b feature/AmazingFeature`)
3. 提交您的更改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 开启一个 Pull Request

## 📄 许可证

本项目采用 MIT 许可证 - 查看 [LICENSE](LICENSE) 文件了解详情

## 🆘 支持

如果您遇到问题或有建议：

- 提交 [Issue](https://github.com/your-repo/issues)
- 查看 [文档](https://github.com/your-repo/wiki)
- 发送邮件至：2071016469@qq.com

## 🙏 致谢

- [Tabby](https://github.com/TabbyML/tabby) - 代码补全引擎
- [JetBrains](https://www.jetbrains.com/) - IntelliJ 平台
- 所有贡献者的支持

## 📊 统计

WingCode 致力于为开发者提供最好的 AI 辅助编程体验。我们不收集任何用户数据，所有信息处理都在您的控制下进行。

---

**WingCode - 让 AI 成为您最好的编程伙伴！** 🚀

Made with ❤️ by WingCode Team****
