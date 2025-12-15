// ========== 消息处理模块 ==========

// ========== 消息发送 ==========

// 发送消息
function sendMessage() {
    // 防抖机制，防止重复调用
    if (sendMessage.isProcessing) {
        return;
    }
    sendMessage.isProcessing = true;

    const userInput = document.getElementById('userInput');
    const message = userInput.value.trim();

    // 如果正在等待响应，检查是否真的需要停止
    if (isWaitingForResponse) {
        // 只有当有有效的请求ID时才停止
        if (currentRequestId) {
            stopCurrentRequest();
            sendMessage.isProcessing = false;
            return;
        } else {
            // 如果没有请求ID但状态显示等待中，说明状态不一致，强制重置
            isWaitingForResponse = false;
            isAiStreaming = false;
            updateSendButton();
        }
    }

    if (message) {
        isWaitingForResponse = true;
        isAiStreaming = true;
        currentRequestId = 'req_' + Date.now();
        updateSendButton();

        // 将用户消息添加到聊天窗口（包含文件引用和代码引用信息）
        const allReferences = [
            ...selectedFiles.map(f => ({ type: 'file', name: f.name, path: f.path })),
            ...codeReferences.map(c => ({
                type: 'code',
                name: c.lineRange ? `${c.fileName} (${c.lineRange})` : c.fileName,
                path: c.fileName
            }))
        ];

        const messageWithFiles = {
            type: 'user',
            content: message,
            files: allReferences.length > 0 ? allReferences : undefined
        };
        window.addMessage(messageWithFiles);

        // 获取对话历史
        const history = getConversationHistory();

        // 准备文件信息和代码引用
        const filesInfo = selectedFiles.map(file => ({
            path: file.path,
            content: file.content || ''
        }));

        // 添加代码引用到文件信息中
        const codeFilesInfo = codeReferences.map(code => ({
            path: code.lineRange ? `${code.fileName} (${code.lineRange})` : code.fileName,
            content: `\`\`\`${code.language}\n${code.text}\n\`\`\``,
            isCodeReference: true
        }));

        const allFilesInfo = [...filesInfo, ...codeFilesInfo];

        // 发送消息到Kotlin，包含历史记录和文件信息
        if (window.sendMessageToKotlin) {
            window.sendMessageToKotlin(JSON.stringify({
                prompt: message,
                history: history,
                files: allFilesInfo,
                requestId: currentRequestId
            }));
        }

        // 清除文件引用和代码引用
        clearFileReferences();
        clearCodeReferences();

        // 确保输入框被清空
        setTimeout(() => {
            userInput.value = '';
            userInput.style.height = '100px'; // 重置高度
            userInput.focus();
            sendMessage.isProcessing = false; // 重置防抖标志
        }, 0);
    } else {
        sendMessage.isProcessing = false; // 重置防抖标志
    }
}

// 停止当前请求
function stopCurrentRequest() {
    if (currentRequestId && window.sendMessageToKotlin) {
        window.sendMessageToKotlin('##STOP_REQUEST##' + currentRequestId);
    }

    // 添加中断消息（在重置状态之前）
    if (currentAiMessage) {
        window.addMessage({
            type: 'ai_chunk',
            content: '\n\n[' + (window.i18n?.actionUserInterrupted || 'User interrupted reply') + ']'
        });
        // 手动触发ai_done的处理逻辑，但不通过addMessage（避免状态冲突）
        const contentDiv = currentAiMessage.querySelector('.message-content');
        if (contentDiv) {
            const cursor = contentDiv.querySelector('.streaming-cursor');
            if (cursor) cursor.remove();
            addCopyButtons(currentAiMessage);
        }
        currentAiMessage = null;
    }

    // 强制重置所有状态
    isWaitingForResponse = false;
    isAiStreaming = false;
    currentRequestId = null;
    updateSendButton();
}

// 获取对话历史
function getConversationHistory() {
    // 优先从localStorage获取当前对话的完整历史
    const currentChat = chatHistory.find(c => c.id === currentChatId);
    if (currentChat && currentChat.messages) {
        // 转换为API格式
        const apiHistory = currentChat.messages.map(msg => {
            let content = msg.content;

            // 如果是AI消息且不是原始内容，需要处理HTML
            if (msg.type === 'ai' && !msg.isRawContent && msg.content) {
                const tempDiv = document.createElement('div');
                tempDiv.innerHTML = msg.content;
                content = tempDiv.textContent || tempDiv.innerText || msg.content;
            }

            return {
                role: msg.type === 'user' ? 'user' : 'assistant',
                content: content
            };
        }).filter(msg => msg.content && msg.content.trim()); // 过滤空内容

        // 返回最近20条记录，增加语境长度
        return apiHistory.slice(-20);
    }

    // 降级到当前DOM解析
    const history = [];
    const messages = document.querySelectorAll('.chat-message');
    messages.forEach(message => {
        const role = message.classList.contains('user-message') ? 'user' : 'assistant';
        const contentDiv = message.querySelector('.message-content');
        const content = contentDiv.dataset.rawContent || contentDiv.innerText;
        if (content) {
            history.push({ role, content });
        }
    });

    // 增加到20条历史记录，防止过长
    return history.slice(-20);
}

// ========== 消息渲染 ==========

// 渲染Markdown内容
function renderMarkdown(content) {
    if (typeof content !== 'string') content = '';

    // 先保存<think>标签内容，防止被marked处理
    const thinkMatches = [];
    let processedContent = content.replace(/<think>([\s\S]*?)<\/think>/g, (match, thinkContent, index) => {
        // 这里使用特殊格式标记，确保marked不会将其视为markdown语法
        const placeholder = `THINKSTART${index}THINKEND`;
        thinkMatches.push(thinkContent);
        return placeholder;
    });

    // 使用marked解析Markdown
    let htmlContent = marked.parse(processedContent);

    // 还原<think>标签内容并转换为details元素
    thinkMatches.forEach((thinkContent, index) => {
        // 将think内容也解析为markdown
        const parsedThinkContent = marked.parse(thinkContent);

        // 使用不含特殊字符的占位符，减少被marked处理的可能性
        const placeholder = `THINKSTART${index}THINKEND`;

        if (htmlContent.includes(placeholder)) {
            htmlContent = htmlContent.replace(
                placeholder,
                `<details class="thinking-details">
                    <summary>${window.i18n?.chatThinking || '思考过程'}</summary>
                    <div class="thinking-content">${parsedThinkContent}</div>
                </details>`
            );
        }
    });

    return htmlContent;
}

/**
 * 全局消息处理函数
 * @param {object} message - 消息对象, e.g., { type: 'user' | 'ai_start' | 'ai_chunk' | 'ai_done', content: '...' }
 */
window.addMessage = function(message) {
    const chatContainer = document.getElementById('chatContainer');
    const welcomeEmptyState = document.getElementById('welcomeEmptyState');
    const inputContainer = document.querySelector('.input-container');

    // 如果有消息，隐藏欢迎界面，显示底部输入框和工具栏
    if (welcomeEmptyState) {
        welcomeEmptyState.style.display = 'none';
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
    }

    if (message.type === 'user') {
        const userMessage = document.createElement('div');
        userMessage.className = 'chat-message user-message';

        let messageHTML = `<div class="message-content">${escapeHtml(message.content)}</div>`;

        // 如果有文件引用，添加文件信息
        if (message.files && message.files.length > 0) {
            const filesHTML = message.files.map(file => {
                if (file.type === 'code') {
                    // 代码引用使用代码图标
                    return `<div class="message-file-ref">
                        <svg class="file-ref-icon" viewBox="0 0 16 16" fill="currentColor">
                            <path d="M5.854 4.854a.5.5 0 10-.708-.708l-3.5 3.5a.5.5 0 000 .708l3.5 3.5a.5.5 0 10.708-.708L2.707 8l3.147-3.146zm4.292 0a.5.5 0 01.708-.708l3.5 3.5a.5.5 0 010 .708l-3.5 3.5a.5.5 0 01-.708-.708L13.293 8l-3.147-3.146z"/>
                        </svg>
                        <span class="file-ref-name" title="${escapeHtml(file.path)}">${escapeHtml(file.name)}</span>
                    </div>`;
                } else {
                    // 文件引用使用文件图标
                    return `<div class="message-file-ref">
                        <svg class="file-ref-icon" viewBox="0 0 16 16" fill="currentColor">
                            <path d="M4 0h8a2 2 0 012 2v12a2 2 0 01-2 2H4a2 2 0 01-2-2V2a2 2 0 012-2zM4 1a1 1 0 00-1 1v12a1 1 0 001 1h8a1 1 0 001-1V2a1 1 0 00-1-1H4z"/>
                            <path d="M6 3h4v1H6V3zm0 2h4v1H6V5zm0 2h4v1H6V7zm0 2h4v1H6V9z"/>
                        </svg>
                        <span class="file-ref-name" title="${escapeHtml(file.path)}">${escapeHtml(file.name)}</span>
                    </div>`;
                }
            }).join('');
            messageHTML = `<div class="message-files">${filesHTML}</div>` + messageHTML;
        }

        userMessage.innerHTML = messageHTML;
        chatContainer.appendChild(userMessage);
        // 用户发送消息时总是滚动到底部，重置滚动状态
        userScrolledUp = false;
        requestAnimationFrame(() => {
            chatContainer.scrollTop = chatContainer.scrollHeight;
        });
        return;
    }

    if (message.type === 'ai_start') {
        isWaitingForResponse = true;
        isAiStreaming = true; // 开始流式输出
        updateSendButton();
        currentAiMessage = document.createElement('div');
        currentAiMessage.className = 'chat-message ai-message';
        currentAiMessage.innerHTML = `<div class="message-content"><span class="streaming-cursor"></span></div>`;
        chatContainer.appendChild(currentAiMessage);
        const contentDiv = currentAiMessage.querySelector('.message-content');
        contentDiv.dataset.rawContent = '';
        // AI开始回复时，重置用户滚动状态，准备自动滚动
        userScrolledUp = false;
        return;
    }

    if (message.type === 'ai_chunk' && currentAiMessage) {
        const contentDiv = currentAiMessage.querySelector('.message-content');

        let existingContent = contentDiv.dataset.rawContent || '';
        existingContent += message.content;
        contentDiv.dataset.rawContent = existingContent;

        contentDiv.innerHTML = renderMarkdown(existingContent) + '<span class="streaming-cursor"></span>';

        contentDiv.querySelectorAll('pre code').forEach(block => {
            if (!block.dataset.highlighted) {
                Prism.highlightElement(block);
                block.dataset.highlighted = 'true';
            }
        });

        // 在流式输出期间也添加代码块样式（不包含按钮）
        addStreamingCodeBlockStyles(contentDiv);

        // 在流式输出期间，强制保持滚动到底部，除非用户主动向上滚动
        if (isAiStreaming) {
            // 检查是否接近底部（容忍度更大）
            const isNearBottom = chatContainer.scrollTop + chatContainer.clientHeight >= chatContainer.scrollHeight - 50;

            if (isNearBottom || !userScrolledUp) {
                // 使用 setTimeout 确保DOM更新后再滚动
                setTimeout(() => {
                    chatContainer.scrollTop = chatContainer.scrollHeight;
                }, 0);
            }
        }
        return;
    }

    if (message.type === 'ai_done') {
        isWaitingForResponse = false;
        isAiStreaming = false; // 结束流式输出
        currentRequestId = null; // 清除请求ID
        updateSendButton();
        if (currentAiMessage) {
            const contentDiv = currentAiMessage.querySelector('.message-content');

            // 移除游标
            const cursor = contentDiv.querySelector('.streaming-cursor');
            if (cursor) {
                cursor.remove();
            }

            // 不重新渲染，只是为代码块添加按钮
            addCopyButtons(currentAiMessage);

            // 确保所有代码块都被高亮
            contentDiv.querySelectorAll('pre code').forEach(block => {
                if (!block.dataset.highlighted) {
                    Prism.highlightElement(block);
                    block.dataset.highlighted = 'true';
                }
            });
        }
        currentAiMessage = null;
        // AI完成回复后，最终滚动到底部
        setTimeout(() => {
            chatContainer.scrollTop = chatContainer.scrollHeight;
        }, 100);
        return;
    }
};

// ========== 代码块处理 ==========

// 为流式输出期间的代码块添加基本样式（不包含按钮）
function addStreamingCodeBlockStyles(contentDiv) {
    const preElements = contentDiv.querySelectorAll('pre');
    preElements.forEach(pre => {
        // 如果已经有.code-block包装器，跳过
        if (pre.closest('.code-block')) return;

        // 创建代码块包装器
        const wrapper = document.createElement('div');
        wrapper.className = 'code-block streaming-code-block';
        pre.parentNode.insertBefore(wrapper, pre);
        wrapper.appendChild(pre);

        // 添加简单的头部（只显示语言，无按钮）
        const header = document.createElement('div');
        header.className = 'code-header';

        const lang = [...pre.querySelector('code').classList].find(c => c.startsWith('language-'))?.replace('language-', '') || 'text';
        const langSpan = document.createElement('span');
        langSpan.className = 'language-name';
        langSpan.textContent = lang;

        header.appendChild(langSpan);
        wrapper.insertBefore(header, pre);
    });
}

// 为代码块添加复制和插入按钮
function addCopyButtons(messageElement) {
    const codeBlocks = messageElement.querySelectorAll('.code-block');
    codeBlocks.forEach(wrapper => {
        // 如果已经有按钮，跳过
        if (wrapper.querySelector('.button-container')) return;

        const header = wrapper.querySelector('.code-header');
        const pre = wrapper.querySelector('pre');

        if (!header || !pre) return;

        const copyButton = document.createElement('button');
        copyButton.className = 'copy-button';
        copyButton.textContent = window.i18n?.copy || 'Copy';
        copyButton.onclick = () => copyCode(copyButton);

        const insertButton = document.createElement('button');
        insertButton.className = 'copy-button'; // 使用相同的样式
        insertButton.textContent = window.i18n?.insert || 'Insert';
        insertButton.onclick = () => insertCode(insertButton);

        const buttonContainer = document.createElement('div');
        buttonContainer.className = 'button-container';
        buttonContainer.appendChild(insertButton);
        buttonContainer.appendChild(copyButton);

        header.appendChild(buttonContainer);

        // 移除流式样式类
        wrapper.classList.remove('streaming-code-block');
    });
}

// 复制代码
function copyCode(button) {
    const codeBlock = button.closest('.code-block');
    const code = codeBlock.querySelector('code').textContent;
    navigator.clipboard.writeText(code).then(() => {
        button.textContent = window.i18n?.copied || 'Copied';
        setTimeout(() => {
            button.textContent = window.i18n?.copy || 'Copy';
        }, 2000);
    });
}

// 插入代码到编辑器
function insertCode(button) {
    const codeBlock = button.closest('.code-block');
    const code = codeBlock.querySelector('code').textContent;

    if (window.sendMessageToKotlin) {
        window.sendMessageToKotlin('##INSERT_CODE##' + code);
        button.textContent = window.i18n?.inserted || 'Inserted';
        setTimeout(() => {
            button.textContent = window.i18n?.insert || 'Insert';
        }, 2000);
    }
}

// ========== 发送按钮状态 ==========

// 更新发送按钮状态
function updateSendButton() {
    const sendButton = document.getElementById('sendButton');
    if (!sendButton) return;

    if (isWaitingForResponse) {
        if (!sendButton.classList.contains('stop-button')) {
            sendButton.disabled = false;
            sendButton.classList.add('stop-button');
            sendButton.innerHTML = `
                <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="currentColor">
                    <rect x="4" y="4" width="16" height="16" rx="2"/>
                </svg>
            `;
            sendButton.title = window.i18n?.actionStopReply || 'Stop reply';
        }
    } else {
        if (sendButton.classList.contains('stop-button')) {
            sendButton.disabled = false;
            sendButton.classList.remove('stop-button');
            sendButton.innerHTML = `<svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#4282FF" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <line x1="22" y1="2" x2="11" y2="13"></line>
                <polygon points="22,2 15,22 11,13 2,9 22,2"></polygon>
            </svg>`;
            sendButton.title = window.i18n?.actionSendMessage || 'Send message';
        }
    }
}
