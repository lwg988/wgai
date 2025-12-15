// ========== 历史记录管理模块 ==========

// ========== 历史对话面板 ==========

// 显示历史对话面板
function showHistoryPanel() {
    if (isShowingHistoryPanel) return;

    isShowingHistoryPanel = true;
    loadChatHistory();

    const overlay = document.getElementById('historyOverlay');
    const panel = document.getElementById('historyPanel');

    overlay.style.display = 'block';
    panel.style.display = 'flex';

    // 隐藏欢迎界面和底部输入框
    const welcomeEmptyState = document.getElementById('welcomeEmptyState');
    if (welcomeEmptyState) {
        welcomeEmptyState.style.display = 'none';
    }
    const inputContainer = document.querySelector('.input-container');
    if (inputContainer) {
        inputContainer.style.display = 'none';
    }
}

// 隐藏历史对话面板
function hideHistoryPanel() {
    if (!isShowingHistoryPanel) return;

    isShowingHistoryPanel = false;

    const overlay = document.getElementById('historyOverlay');
    const panel = document.getElementById('historyPanel');

    overlay.style.display = 'none';
    panel.style.display = 'none';

    // 检查是否有消息内容，决定显示欢迎界面还是底部输入框
    const chatContainer = document.getElementById('chatContainer');
    const hasMessages = chatContainer.querySelectorAll('.chat-message').length > 0;

    const welcomeEmptyState = document.getElementById('welcomeEmptyState');
    const inputContainer = document.querySelector('.input-container');

    if (hasMessages) {
        // 有消息时显示底部输入框
        if (welcomeEmptyState) {
            welcomeEmptyState.style.display = 'none';
        }
        if (inputContainer) {
            inputContainer.style.display = 'flex';
        }
        // 显示工具栏
        const toolbar = document.querySelector('.top-toolbar');
        if (toolbar) {
            toolbar.style.display = 'flex';
        }
        // 显示工具栏按钮
        const toolbarButtons = document.querySelector('.toolbar-buttons');
        if (toolbarButtons) {
            toolbarButtons.classList.add('visible');
        }
    } else {
        // 无消息时显示欢迎界面
        if (welcomeEmptyState) {
            welcomeEmptyState.style.display = 'flex';
        }
        if (inputContainer) {
            inputContainer.style.display = 'none';
        }
        // 隐藏工具栏
        const toolbar = document.querySelector('.top-toolbar');
        if (toolbar) {
            toolbar.style.display = 'none';
        }
        // 隐藏工具栏按钮
        const toolbarButtons = document.querySelector('.toolbar-buttons');
        if (toolbarButtons) {
            toolbarButtons.classList.remove('visible');
        }
    }
}

// 加载历史对话列表
function loadChatHistory() {
    const historyList = document.getElementById('historyList');

    // 从localStorage加载历史对话
    const savedHistory = localStorage.getItem('chatHistory');
    if (savedHistory) {
        chatHistory = JSON.parse(savedHistory);
    }

    if (chatHistory.length === 0) {
        historyList.innerHTML = '<div class="history-empty">' + (window.i18n?.historyEmpty || 'No chat history') + '</div>';
        return;
    }

    // 渲染历史对话列表
    const historyHTML = chatHistory.map((chat, index) => `
        <div class="history-item" data-chat-id="${chat.id}">
            <div class="history-item-content">
                <div class="history-item-title">${escapeHtml(chat.title)}</div>
                <div class="history-item-time">${formatTime(chat.lastUpdate)}</div>
            </div>
            <div class="history-item-actions">
                <button class="history-item-delete" data-chat-id="${chat.id}" title="${window.i18n?.buttonDelete || 'Delete'}">
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                        <circle cx="12" cy="12" r="10"/>
                        <line x1="15" y1="9" x2="9" y2="15"/>
                        <line x1="9" y1="9" x2="15" y2="15"/>
                    </svg>
                </button>
            </div>
        </div>
    `).join('');

    historyList.innerHTML = historyHTML;
}

// ========== 对话会话管理 ==========

// 创建新对话
function createNewChat() {
    // 首先保存当前对话
    saveChatToHistory();

    // 停止当前请求
    if (isWaitingForResponse) {
        stopCurrentRequest();
    }

    // 生成新的对话ID
    currentChatId = 'chat_' + Date.now();

    // 清空所有状态
    isWaitingForResponse = false;
    isAiStreaming = false;
    currentRequestId = null;
    currentAiMessage = null;
    userScrolledUp = false;

    // 清空UI
    const chatContainer = document.getElementById('chatContainer');
    chatContainer.innerHTML = '';

    // 重新添加欢迎界面
    const welcomeEmptyState = document.createElement('div');
    welcomeEmptyState.className = 'welcome-empty-state';
    welcomeEmptyState.id = 'welcomeEmptyState';
    const appTitle = window.i18n?.title || 'WingCode';
    welcomeEmptyState.innerHTML = `
        <div class="welcome-content">
            <h1 class="welcome-title" id="welcomeTitle">${appTitle}</h1>
            <div class="input-wrapper" style="max-width: 700px; margin: 0 auto;">
                <textarea
                    class="input-box welcome-input-box"
                    id="welcomeUserInput"
                    placeholder=""
                    onkeydown="handleWelcomeKeyDown(event)"
                    oninput="handleWelcomeInputChange(this)"
                ></textarea>
                <!-- 文件引用按钮 - 欢迎界面版本 -->
                <button class="at-button" id="welcomeAtButton" onclick="showFileSelector('welcomeUserInput')" title="">
                    <span class="at-symbol">@</span>
                </button>
                <!-- 文件选择器 - 欢迎界面版本 -->
                <div class="file-selector welcome-file-selector" id="welcomeFileSelector" style="display: none;">
                    <div class="file-selector-header">
                        <span id="welcomeFileSelectorTitle"></span>
                        <button class="file-selector-close" onclick="hideFileSelector()">×</button>
                    </div>
                    <div class="file-selector-search">
                        <input type="text" id="welcomeFileSearchInput" placeholder="" oninput="searchFiles(this.value)">
                        <button class="file-selector-browse" onclick="browseFiles()" id="welcomeBrowseBtnText"></button>
                    </div>
                    <div class="file-selector-results" id="welcomeFileSelectorResults">
                        <div class="file-selector-loading" id="welcomeFileSelectorLoading"></div>
                    </div>
                </div>
                <!-- 模型选择器 - 欢迎界面版本 -->
                <div class="custom-select input-model-select welcome-model-select" id="welcomeCustomSelect">
                    <div class="select-selected" id="welcomeSelectSelected"></div>
                    <div class="select-items" id="welcomeSelectItems">
                        <div data-value="" id="welcomeSelectLoading"></div>
                    </div>
                </div>
                <button class="send-button welcome-send-button" id="welcomeSendButton" onclick="sendWelcomeMessage()">
                    <svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#4282FF" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                        <line x1="22" y1="2" x2="11" y2="13"></line>
                        <polygon points="22,2 15,22 11,13 2,9 22,2"></polygon>
                    </svg>
                </button>
            </div>
        </div>
    `;
    chatContainer.appendChild(welcomeEmptyState);

    const userInput = document.getElementById('userInput');
    userInput.value = '';
    userInput.style.height = '100px'; // 重置输入框高度

    // 设置欢迎界面输入框的placeholder
    const welcomeUserInput = document.getElementById('welcomeUserInput');
    if (welcomeUserInput) {
        welcomeUserInput.placeholder = window.i18n?.inputPlaceholder || '输入你的问题';
    }

    // 隐藏底部输入框
    const inputContainer = document.querySelector('.input-container');
    if (inputContainer) {
        inputContainer.style.display = 'none';
    }

    // 隐藏工具栏
    const toolbar = document.querySelector('.top-toolbar');
    if (toolbar) {
        toolbar.style.display = 'none';
    }

    // 隐藏工具栏按钮
    const toolbarButtons = document.querySelector('.toolbar-buttons');
    if (toolbarButtons) {
        toolbarButtons.classList.remove('visible');
    }

    // 清空引用
    clearFileReferences();
    clearCodeReferences();

    // 更新发送按钮状态
    updateSendButton();

    // 关闭历史面板
    hideHistoryPanel();

    // 重新初始化欢迎界面的模型选择器
    setTimeout(() => {
        initializeCustomSelect();
        initializeModels(); // 重新加载模型列表

        // 重新绑定@按钮事件
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

        // 重新初始化文件选择器的文本
        initializeFileSelectorTexts();
    }, 100);
}

// 初始化文件选择器的文本
function initializeFileSelectorTexts() {
    // 如果window.i18n不存在，使用默认值
    const i18n = window.i18n || {};

    // 设置@按钮标题
    const atButton = document.getElementById('atButton');
    if (atButton) {
        atButton.title = i18n.attachFile || 'Attach File';
    }

    const welcomeAtButton = document.getElementById('welcomeAtButton');
    if (welcomeAtButton) {
        welcomeAtButton.title = i18n.attachFile || 'Attach File';
    }

    // 设置文件选择器标题
    const fileSelectorTitle = document.getElementById('fileSelectorTitle');
    if (fileSelectorTitle) {
        fileSelectorTitle.innerText = i18n.selectFile || 'Select File';
    }

    const welcomeFileSelectorTitle = document.getElementById('welcomeFileSelectorTitle');
    if (welcomeFileSelectorTitle) {
        welcomeFileSelectorTitle.innerText = i18n.selectFile || 'Select File';
    }

    // 设置搜索框placeholder
    const fileSearchInput = document.getElementById('fileSearchInput');
    if (fileSearchInput) {
        fileSearchInput.placeholder = i18n.searchFilePlaceholder || 'Search filename...';
    }

    const welcomeFileSearchInput = document.getElementById('welcomeFileSearchInput');
    if (welcomeFileSearchInput) {
        welcomeFileSearchInput.placeholder = i18n.searchFilePlaceholder || 'Search filename...';
    }

    // 设置浏览按钮文本
    const browseBtnText = document.getElementById('browseBtnText');
    if (browseBtnText) {
        browseBtnText.innerText = i18n.browse || 'Browse...';
    }

    const welcomeBrowseBtnText = document.getElementById('welcomeBrowseBtnText');
    if (welcomeBrowseBtnText) {
        welcomeBrowseBtnText.innerText = i18n.browse || 'Browse...';
    }

    // 设置文件选择器加载文本
    const fileSelectorLoading = document.getElementById('fileSelectorLoading');
    if (fileSelectorLoading) {
        fileSelectorLoading.innerText = i18n.fileSelectorHint || 'Enter filename to search or click "Browse..." to select files';
    }

    const welcomeFileSelectorLoading = document.getElementById('welcomeFileSelectorLoading');
    if (welcomeFileSelectorLoading) {
        welcomeFileSelectorLoading.innerText = i18n.fileSelectorHint || 'Enter filename to search or click "Browse..." to select files';
    }
}

// 加载指定的对话会话
function loadChatSession(chatId) {
    // 首先保存当前对话
    saveChatToHistory();

    const chat = chatHistory.find(c => c.id === chatId);
    if (!chat) return;

    // 停止当前请求（如果有的话）
    if (isWaitingForResponse) {
        stopCurrentRequest();
    }

    // 更新当前对话ID
    currentChatId = chatId;

    // 清空当前对话
    const chatContainer = document.getElementById('chatContainer');
    chatContainer.innerHTML = '';

    // 正确加载对话内容
    chat.messages.forEach(msg => {
        if (msg.type === 'user') {
            window.addMessage({
                type: 'user',
                content: msg.content
            });
        } else if (msg.type === 'ai') {
            // 重建AI消息
            window.addMessage({ type: 'ai_start' });

            // 如果是原始内容，直接使用；否则需要从HTML中提取文本
            let content = msg.content;
            if (!msg.isRawContent && msg.content) {
                // 如果保存的是HTML，尝试提取纯文本内容用于重新渲染
                const tempDiv = document.createElement('div');
                tempDiv.innerHTML = msg.content;
                content = tempDiv.textContent || tempDiv.innerText || msg.content;
            }

            window.addMessage({
                type: 'ai_chunk',
                content: content
            });
            window.addMessage({ type: 'ai_done' });
        }
    });

    // 清除当前的文件引用和代码引用
    clearFileReferences();
    clearCodeReferences();

    // 重新初始化文件选择器的文本
    initializeFileSelectorTexts();

    hideHistoryPanel();
}

// 删除对话会话
function deleteChatSession(chatId) {
    // 直接删除，不显示确认对话框
    chatHistory = chatHistory.filter(c => c.id !== chatId);
    localStorage.setItem('chatHistory', JSON.stringify(chatHistory));

    // 如果删除的是当前对话，创建新对话
    if (currentChatId === chatId) {
        createNewChat();
    }

    // 刷新历史列表
    loadChatHistory();
}

// ========== 保存与恢复 ==========

// 保存当前对话到历史记录
function saveChatToHistory() {
    if (!currentChatId) return;

    const chatContainer = document.getElementById('chatContainer');
    const messages = Array.from(chatContainer.children).map(element => {
        const contentDiv = element.querySelector('.message-content');

        // 如果元素没有.message-content（如欢迎界面元素），跳过
        if (!contentDiv) {
            console.log('[DEBUG] 跳过非消息元素:', element);
            return null;
        }

        const type = element.classList.contains('user-message') ? 'user' : 'ai';

        // 保存完整内容
        let content = '';
        let isRawContent = false;

        if (type === 'user') {
            content = contentDiv.textContent || '';
        } else {
            // AI消息优先保存rawContent，其次保存innerHTML
            if (contentDiv.dataset.rawContent) {
                content = contentDiv.dataset.rawContent;
                isRawContent = true;
            } else {
                content = contentDiv.innerHTML || '';
            }
        }

        return {
            type: type,
            content: content,
            timestamp: Date.now(),
            isRawContent: isRawContent
        };
    }).filter(msg => msg !== null); // 过滤掉null值

    if (messages.length === 0) return;

    const chatTitle = messages.find(m => m.type === 'user')?.content?.substring(0, 50) || (window.i18n?.newChatBtn || 'New Chat');

    const existingIndex = chatHistory.findIndex(c => c.id === currentChatId);
    const chatData = {
        id: currentChatId,
        title: chatTitle,
        messages: messages,
        lastUpdate: Date.now()
    };

    if (existingIndex >= 0) {
        chatHistory[existingIndex] = chatData;
    } else {
        chatHistory.unshift(chatData);
    }

    // 限制历史记录数量
    if (chatHistory.length > 50) {
        chatHistory = chatHistory.slice(0, 50);
    }

    localStorage.setItem('chatHistory', JSON.stringify(chatHistory));
}

// ========== 欢迎消息 ==========

// 显示欢迎消息的统一函数
function showWelcomeMessage(customMessage) {
    window.addMessage({
        type: 'ai_start'
    });
    window.addMessage({
        type: 'ai_chunk',
        content: customMessage || window.i18n?.welcome
    });
    window.addMessage({
        type: 'ai_done'
    });
}

// 清除历史记录
function clearHistory() {
    hideConfirmDialog();
    createNewChat();

    if (window.sendMessageToKotlin) {
        window.sendMessageToKotlin('##CLEAR_HISTORY##');
    }
}

// ========== 确认对话框 ==========

// 显示确认对话框
function showConfirmDialog() {
    document.getElementById('confirmDialog').style.display = 'block';
}

// 隐藏确认对话框
function hideConfirmDialog() {
    document.getElementById('confirmDialog').style.display = 'none';
}

// ========== 消息历史拦截器 ==========

// 在消息添加后保存到历史记录
const originalAddMessage = window.addMessage;
window.addMessage = function(message) {
    const result = originalAddMessage(message);

    // 在消息添加后保存到历史记录
    if (message.type === 'ai_done' || message.type === 'user') {
        setTimeout(() => {
            saveChatToHistory();
        }, 100);
    }

    return result;
};
