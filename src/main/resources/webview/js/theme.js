// ========== 主题管理模块 ==========

// 设置主题（支持 light, dark, dark-old）
function setDarkTheme(theme) {
    // 兼容旧的布尔值参数
    if (typeof theme === 'boolean') {
        theme = theme ? 'dark' : 'light';
    }

    // 验证主题值
    const validThemes = ['light', 'dark', 'dark-old'];
    if (!validThemes.includes(theme)) {
        theme = 'light';
    }

    isDarkTheme = theme !== 'light';
    document.body.setAttribute('data-theme', theme);

    // 更新代码高亮主题 - 代码块跟随主题变化
    const prismTheme = document.getElementById('prism-theme');
    if (theme === 'light') {
        prismTheme.setAttribute('href', 'lib/css/prism.min.css');
    } else {
        prismTheme.setAttribute('href', 'lib/css/prism-tomorrow.min.css');
    }

    // 根据主题自动应用对应的默认代码配色方案
    const defaultScheme = (theme === 'light') ? 'idea-light' : 'idea-dark';
    applyColorScheme(defaultScheme);

    // 重新应用代码高亮
    Prism.highlightAll();
}

// 切换主题
function toggleTheme() {
    const currentTheme = document.body.getAttribute('data-theme') || 'light';
    let newTheme;

    // 简单的亮色/暗色切换
    if (currentTheme === 'light') {
        newTheme = window.ideaTheme || 'dark';
    } else {
        newTheme = 'light';
    }

    setDarkTheme(newTheme);

    // 保存用户手动选择的主题到localStorage
    localStorage.setItem('theme', newTheme);

    // 同步到Kotlin后端设置
    if (window.sendMessageToKotlin) {
        window.sendMessageToKotlin('##UPDATE_CHAT_THEME##' + newTheme);
    }

    // 重新应用代码高亮
    Prism.highlightAll();
}

// 打开插件设置
function openSettings() {
    if (window.sendMessageToKotlin) {
        window.sendMessageToKotlin('##OPEN_SETTINGS##');
    }
}

// 应用配色方案
function applyColorScheme(schemeId) {
    // 移除之前的配色方案类
    document.body.classList.remove('color-scheme-idea-dark', 'color-scheme-idea-light',
        'color-scheme-vscode', 'color-scheme-sublime-text');

    // 添加新的配色方案类
    document.body.classList.add('color-scheme-' + schemeId);

    // 重新应用代码高亮
    setTimeout(() => {
        Prism.highlightAll();
    }, 100);
}

// 应用聊天背景主题
function applyChatBackgroundTheme(themeId) {
    // 移除之前的聊天背景主题类
    document.body.classList.remove('chat-theme-light', 'chat-theme-dark', 'chat-theme-dark-old');

    // 添加新的聊天背景主题类
    document.body.classList.add('chat-theme-' + themeId);

    // 更新基础主题属性（保持兼容性）
    if (themeId === 'light') {
        document.body.setAttribute('data-theme', 'light');
        isDarkTheme = false;
    } else {
        document.body.setAttribute('data-theme', themeId === 'dark-old' ? 'dark-old' : 'dark');
        isDarkTheme = true;
    }

    // 更新代码高亮主题 - 代码块跟随主题变化
    const prismTheme = document.getElementById('prism-theme');
    if (themeId === 'light') {
        prismTheme.setAttribute('href', 'lib/css/prism.min.css');
    } else {
        prismTheme.setAttribute('href', 'lib/css/prism-tomorrow.min.css');
    }

    // 根据聊天背景主题自动应用对应的默认代码配色方案
    const defaultScheme = (themeId === 'light') ? 'idea-light' : 'idea-dark';
    applyColorScheme(defaultScheme);

    // 重新应用代码高亮
    Prism.highlightAll();
}
