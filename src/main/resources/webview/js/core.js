// ========== 全局变量 ==========
let isWaitingForResponse = false;
let currentAiMessage = null;
let isDarkTheme = true; // 默认为暗色主题，匹配大部分IDE设置
let userScrolledUp = false; // 用户是否向上滚动了
let lastScrollTime = 0; // 上次滚动时间
let isAiStreaming = false; // AI是否正在流式输出
let currentRequestId = null; // 当前请求ID，用于中断

// 文件引用相关变量
let selectedFiles = []; // 当前选中的文件列表
let isShowingFileSelector = false; // 是否正在显示文件选择器
let fileSearchResults = []; // 文件搜索结果
let fileSearchTimeout = null; // 文件搜索防抖定时器
let currentInputId = 'userInput'; // 当前活动的输入框ID

// 选中代码相关变量
let selectedCodeInfo = null; // 当前选中的代码信息
let codeReferences = []; // 代码引用列表

// 历史对话相关变量
let currentChatId = null; // 当前对话ID
let chatHistory = []; // 历史对话列表
let isShowingHistoryPanel = false; // 是否正在显示历史面板

// 模型相关变量
let modelsMap = new Map(); // 存储模型ID到模型信息的映射

// 全局变量存储图标缓存
let iconCache = {};

// 确保文档编码正确
document.charset = 'UTF-8';

// ========== 核心工具函数 ==========

// HTML转义
function escapeHtml(text) {
    if (typeof text !== 'string') return '';
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

// 自动调整文本框高度
function autoResize(textarea) {
    textarea.style.height = 'auto';
    const scrollHeight = textarea.scrollHeight;
    const newHeight = Math.min(Math.max(scrollHeight, 58), 120);
    textarea.style.height = newHeight + 'px';
}

// ========== 欢迎界面输入处理 ==========

// 处理欢迎界面输入框按键事件
function handleWelcomeKeyDown(event) {
    if (event.key === 'Enter' && !event.shiftKey) {
        event.preventDefault();
        sendWelcomeMessage();
    }
}

// 处理欢迎界面输入框内容变化
function handleWelcomeInputChange(textarea) {
    autoResize(textarea);
}

// 发送欢迎界面的消息
function sendWelcomeMessage() {
    const welcomeInput = document.getElementById('welcomeUserInput');
    const message = welcomeInput.value.trim();

    if (message) {
        // 将消息传递给原始输入框并发送
        const userInput = document.getElementById('userInput');
        userInput.value = message;
        sendMessage();

        // 清空欢迎界面输入框
        welcomeInput.value = '';
        welcomeInput.style.height = '56px';
    }
}

// 显示错误Toast
function showErrorToast(message) {
    const toast = document.createElement('div');
    toast.className = 'error-toast';
    toast.textContent = message;
    document.body.appendChild(toast);

    setTimeout(() => {
        toast.classList.add('show');
    }, 100);

    setTimeout(() => {
        toast.classList.remove('show');
        setTimeout(() => {
            document.body.removeChild(toast);
        }, 300);
    }, 3000);
}

// 格式化时间
function formatTime(timestamp) {
    const date = new Date(timestamp);
    const now = new Date();
    const diff = now - date;

    if (diff < 60000) {
        return window.i18n?.timeJustNow || 'Just now';
    } else if (diff < 3600000) {
        return Math.floor(diff / 60000) + (window.i18n?.timeMinutesAgo || ' minutes ago');
    } else if (diff < 86400000) {
        return Math.floor(diff / 3600000) + (window.i18n?.timeHoursAgo || ' hours ago');
    } else {
        return date.toLocaleDateString();
    }
}

// 从模型名称中提取供应商信息（移除括号内容）
function cleanModelName(modelName) {
    return modelName.replace(/\s*\([^)]*\)\s*/g, '').trim();
}

// 关闭所有下拉框
function closeAllSelect(element) {
    const selectItems = document.querySelectorAll('.select-items');
    const selectSelected = document.querySelectorAll('.select-selected');

    selectItems.forEach(item => {
        item.style.display = 'none';
    });

    selectSelected.forEach(selected => {
        selected.classList.remove('select-arrow-active');
    });
}

// 查找最后一个@符号的位置
function findLastAtSymbol(text, cursorPos) {
    for (let i = cursorPos - 1; i >= 0; i--) {
        if (text[i] === '@') {
            // 找到@符号就返回，不再检查前面是否是空格或换行
            return i;
        } else if (text[i] === ' ' || text[i] === '\n') {
            break;
        }
    }
    return -1;
}

// ========== 通用事件处理 ==========

// 防止重复按键的标志
let isProcessingKeyDown = false;

// 处理Enter键发送
function handleKeyDown(event) {
    if (event.key === 'Enter' && !event.shiftKey) {
        event.preventDefault();

        // 防抖：防止快速重复按键
        if (isProcessingKeyDown) {
            return;
        }
        isProcessingKeyDown = true;
        setTimeout(() => {
            isProcessingKeyDown = false;
        }, 300); // 300ms 防抖

        // 如果AI正在回复且有有效的请求ID，则停止当前请求
        if ((isWaitingForResponse || isAiStreaming) && currentRequestId) {
            stopCurrentRequest();
        } else {
            // 否则发送消息（sendMessage会处理状态不一致的情况）
            sendMessage();
        }
    }
}

// ========== 兼容旧版API ==========

// 兼容旧版 `sendMessageToJs`
window.sendMessageToJs = (message) => {
    // 假设旧版格式是纯文本或简单的JSON
    try {
        const parsed = JSON.parse(message);
        window.addMessage(parsed);
    } catch (e) {
        // 否则视为纯文本块
        window.addMessage({ type: 'ai_chunk', content: message });
    }
};

// ========== 全局桥接函数 ==========

// 设置输入框内容并发送消息（供Kotlin Action调用）
window.setInputAndSend = function(message) {
    const userInput = document.getElementById('userInput');
    if (userInput && message) {
        userInput.value = message;
        userInput.dispatchEvent(new Event('input'));
        // 自动调整输入框高度
        autoResize(userInput);
        // 延迟发送，确保内容已设置
        setTimeout(() => {
            sendMessage();
        }, 100);
    }
};
