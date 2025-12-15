// ========== 初始化和配置模块 ==========

// ========== DOM加载完成后初始化 ==========
document.addEventListener('DOMContentLoaded', function() {

    // 初始化浏览器默认行为
    initializeBrowserDefaults();

    // 初始化链接处理
    initializeLinkHandling();

    // 初始化滚动监听
    initializeScrollListener();

    // 初始化输入框
    initializeInputBox();

    // 初始化点击外部关闭
    initializeOutsideClick();

    // ========== 主题和配色方案初始化 ==========

    // 初始化国际化文本
    if (window.i18n) {
        // 更新界面文本
        document.getElementById('selectSelected').textContent = window.i18n.loading;
        document.getElementById('userInput').placeholder = window.i18n.inputPlaceholder;
        document.getElementById('welcomeUserInput').placeholder = window.i18n.inputPlaceholder;

        // 更新欢迎界面标题
        const welcomeTitle = document.getElementById('welcomeTitle');
        if (welcomeTitle) {
            welcomeTitle.textContent = window.i18n.title || 'WingCode';
        }

        // 初始化文件选择器文本
        initializeFileSelectorTexts();
    }

    // 检测body的data-theme属性，如果没有则检查localStorage
    const bodyTheme = document.body.getAttribute('data-theme');
    if (bodyTheme) {
        setDarkTheme(bodyTheme); // 直接传递主题字符串
    } else {
        // 降级到localStorage或默认暗色主题
        const savedTheme = localStorage.getItem('theme');
        if (savedTheme && ['light', 'dark', 'dark-old'].includes(savedTheme)) {
            setDarkTheme(savedTheme);
        } else {
            setDarkTheme('dark'); // 默认使用暗色主题
        }
    }

    // 应用默认配色方案
    applyColorScheme('idea-dark');

    // 获取并应用当前配色方案设置
    if (window.sendMessageToKotlin) {
        setTimeout(() => {
            window.sendMessageToKotlin('##GET_COLOR_SCHEMES##');
        }, 1200);

        // 获取并应用当前聊天背景主题设置
        setTimeout(() => {
            window.sendMessageToKotlin('##GET_CHAT_THEMES##');
        }, 1300);
    }

    // ========== 对话会话初始化 ==========

    // 初始化新对话
    currentChatId = 'chat_' + Date.now();

    // 初始化时隐藏底部输入框（显示欢迎界面）
    const inputContainer = document.querySelector('.input-container');
    if (inputContainer) {
        inputContainer.style.display = 'none';
    }

    // ========== 事件监听绑定 ==========

    console.log('[DEBUG] 初始化事件监听...');

    // 发送按钮防抖处理
    let sendButtonProcessing = false;
    document.getElementById('sendButton').addEventListener('click', function(e) {
        if (sendButtonProcessing) {
            return;
        }
        sendButtonProcessing = true;
        setTimeout(() => {
            sendButtonProcessing = false;
        }, 300); // 300ms 防抖
        sendMessage();
    });

    // 绑定@按钮事件
    const atButton = document.getElementById('atButton');
    if (atButton) {
        atButton.addEventListener('click', function(e) {
            e.preventDefault();
            e.stopPropagation();
            showFileSelector('userInput');
        });
    }

    const welcomeAtButton = document.getElementById('welcomeAtButton');
    if (welcomeAtButton) {
        welcomeAtButton.addEventListener('click', function(e) {
            e.preventDefault();
            e.stopPropagation();
            showFileSelector('welcomeUserInput');
        });
    }

    // 初始化文件选择器文本
    initializeFileSelectorTexts();

    // 绑定确认对话框按钮
    document.getElementById('confirmBtn').addEventListener('click', clearHistory);
    document.getElementById('cancelBtn').addEventListener('click', hideConfirmDialog);

    // 使用事件委托处理历史对话列表的点击事件
    document.getElementById('historyList').addEventListener('click', function(event) {
        // 检查是否点击了删除按钮或其子元素
        const deleteButton = event.target.closest('.history-item-delete');
        if (deleteButton) {
            event.preventDefault();
            event.stopPropagation();
            const chatId = deleteButton.dataset.chatId;
            if (chatId) {
                deleteChatSession(chatId);
            }
            return false;
        }

        // 检查是否点击了历史对话项
        const historyItem = event.target.closest('.history-item');
        if (historyItem && !event.target.closest('.history-item-actions')) {
            const chatId = historyItem.dataset.chatId;
            if (chatId) {
                loadChatSession(chatId);
            }
        }
    });

    // ========== 组件初始化 ==========

    // 初始化自定义下拉框
    initializeCustomSelect();

    // 延迟初始化模型列表，等待Kotlin桥接建立
    setTimeout(() => {
        initializeModels();
    }, 1000);
});

// ========== 配置更新 ==========

// 更新配色方案（由Kotlin调用）
window.updateColorSchemes = function(schemesData) {
    const { schemes, selectedScheme } = schemesData;

    // 更新配色方案选择器的选项
    updateColorSchemeOptions(schemes);

    // 应用当前配色方案
    if (selectedScheme) {
        applyColorScheme(selectedScheme);
    }
};

// 更新配色方案选择器的选项
// 配色方案通过CSS类控制，无需UI操作
function updateColorSchemeOptions(schemes) {
    // 配色方案通过applyColorScheme函数应用CSS类控制，无需操作DOM
    // 避免与模型选择器冲突，什么都不做
}

// 选择配色方案
function selectColorScheme(element, selectId, itemsId) {
    const select = document.getElementById(selectId);
    const items = document.getElementById(itemsId);
    // 更安全的ID匹配逻辑，支持新的ID格式
    let selectedId;
    if (selectId === 'colorSchemeSelect') {
        selectedId = 'colorSchemeSelectSelected';
    } else if (selectId === 'welcomeColorSchemeSelect') {
        selectedId = 'welcomeColorSchemeSelectSelected';
    } else {
        // 向后兼容旧格式
        selectedId = selectId.replace('CustomSelect', 'SelectSelected');
    }
    const selected = document.getElementById(selectedId);

    // 移除所有选中状态
    const options = items.querySelectorAll('div');
    options.forEach(opt => opt.classList.remove('select-item-selected'));

    // 添加选中状态
    element.classList.add('select-item-selected');

    // 更新显示文本
    if (selected) {
        selected.textContent = element.textContent;
    }

    // 隐藏选项
    items.style.display = 'none';

    // 发送配色方案到后端
    const schemeId = element.getAttribute('data-value');
    window.sendMessageToKotlin('##UPDATE_COLOR_SCHEME##' + schemeId);
}
