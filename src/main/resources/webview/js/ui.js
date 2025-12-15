// ========== UI交互模块 ==========

// ========== 滚动监听 ==========

// 监听聊天容器的滚动事件
function initializeScrollListener() {
    const chatContainer = document.getElementById('chatContainer');

    chatContainer.addEventListener('scroll', function() {
        const now = Date.now();
        lastScrollTime = now;

        // 检查用户是否向上滚动了（更严格的检测）
        const isAtBottom = chatContainer.scrollTop + chatContainer.clientHeight >= chatContainer.scrollHeight - 20;

        // 只有在用户主动向上滚动时才设置userScrolledUp为true
        if (!isAtBottom && !isAiStreaming) {
            userScrolledUp = true;
        } else if (isAtBottom) {
            userScrolledUp = false;
        }

        // 在AI流式输出期间，如果用户向上滚动超过一定距离，则允许停止自动滚动
        if (isAiStreaming) {
            const scrollFromBottom = chatContainer.scrollHeight - chatContainer.scrollTop - chatContainer.clientHeight;
            if (scrollFromBottom > 100) { // 距离底部超过100px
                userScrolledUp = true;
            }
        }
    });
}

// ========== 禁用浏览器默认行为 ==========

// 禁用浏览器默认行为，让页面更像原生应用
function initializeBrowserDefaults() {
    // 禁用右键菜单
    document.addEventListener('contextmenu', function(e) {
        e.preventDefault();
        return false;
    });

    // 禁用F12开发者工具（部分情况）
    document.addEventListener('keydown', function(e) {
        // 禁用F12、Ctrl+Shift+I、Ctrl+Shift+J、Ctrl+U
        if (e.key === 'F12' ||
            (e.ctrlKey && e.shiftKey && (e.key === 'I' || e.key === 'J')) ||
            (e.ctrlKey && e.key === 'U')) {
            e.preventDefault();
            return false;
        }
    });

    // 禁用文本选中（除了输入框和消息内容）
    document.addEventListener('selectstart', function(e) {
        const target = e.target;
        const allowSelect = target.tagName === 'INPUT' ||
                          target.tagName === 'TEXTAREA' ||
                          target.contentEditable === 'true' ||
                          target.closest('.message-content') ||
                          target.closest('.code-block');

        if (!allowSelect) {
            e.preventDefault();
            return false;
        }
    });

    // 禁用拖拽（除了输入框）
    document.addEventListener('dragstart', function(e) {
        const target = e.target;
        if (target.tagName !== 'INPUT' && target.tagName !== 'TEXTAREA') {
            e.preventDefault();
            return false;
        }
    });

    // 防止图片拖拽
    document.addEventListener('dragstart', function(e) {
        if (e.target.tagName === 'IMG') {
            e.preventDefault();
            return false;
        }
    });
}

// ========== 链接点击处理 ==========

// 处理网址链接点击事件
function initializeLinkHandling() {
    // 禁用所有链接的默认行为，改为复制功能
    document.addEventListener('click', function(e) {
        const link = e.target.closest('a[href]');
        if (link) {
            e.preventDefault();
            e.stopPropagation();

            // 获取链接地址
            const url = link.href;

            // 复制到剪贴板
            if (navigator.clipboard) {
                navigator.clipboard.writeText(url).then(() => {
                    // 可以添加一个简单的提示
                    showUrlCopiedNotification(url);
                }).catch(err => {
                    console.error('Failed to copy URL:', err);
                });
            } else {
                // 降级方案：使用传统的复制方法
                try {
                    const textArea = document.createElement('textarea');
                    textArea.value = url;
                    document.body.appendChild(textArea);
                    textArea.select();
                    document.execCommand('copy');
                    document.body.removeChild(textArea);
                    showUrlCopiedNotification(url);
                } catch (err) {
                    console.error('Failed to copy URL (fallback):', err);
                }
            }

            return false;
        }
    });
}

// 显示URL复制成功的通知
function showUrlCopiedNotification(url) {
    const notification = document.createElement('div');
    notification.style.cssText = `
        position: fixed;
        top: 20px;
        right: 20px;
        background-color: var(--primary-color);
        color: white;
        padding: 8px 16px;
        border-radius: 4px;
        font-size: 12px;
        z-index: 10000;
        opacity: 0;
        transition: opacity 0.3s ease;
        max-width: 300px;
        word-break: break-all;
    `;
    notification.textContent = (window.i18n?.notificationLinkCopied || 'Link copied') + ': ' + url;

    document.body.appendChild(notification);

    // 显示通知
    setTimeout(() => {
        notification.style.opacity = '1';
    }, 100);

    // 隐藏通知
    setTimeout(() => {
        notification.style.opacity = '0';
        setTimeout(() => {
            if (notification.parentNode) {
                document.body.removeChild(notification);
            }
        }, 300);
    }, 2000);
}

// ========== 点击外部关闭 ==========

// 点击其他地方时隐藏文件选择器
function initializeOutsideClick() {
    document.addEventListener('click', function(event) {
        // 如果正在显示文件选择器
        if (isShowingFileSelector) {
            // 检查点击是否在文件选择器内部
            const welcomeFileSelector = document.getElementById('welcomeFileSelector');
            const bottomFileSelector = document.getElementById('fileSelector');

            const isInsideWelcomeSelector = welcomeFileSelector && welcomeFileSelector.contains(event.target);
            const isInsideBottomSelector = bottomFileSelector && bottomFileSelector.contains(event.target);
            const isInsideAnySelector = isInsideWelcomeSelector || isInsideBottomSelector;

            // 检查点击是否在文件项目上（文件夹或文件）
            const isOnFileItem = event.target.closest('.file-item');

            // 检查点击是否在输入框或@按钮上
            const userInput = document.getElementById(currentInputId);
            const isOnInput = userInput && userInput.contains(event.target);
            const atButton = currentInputId === 'welcomeUserInput'
                ? document.getElementById('welcomeAtButton')
                : document.getElementById('atButton');
            const isOnAtButton = atButton && atButton.contains(event.target);

            // 只有点击在选择器外部、且不在文件项目上、不在输入框上、不在@按钮上时才关闭
            if (!isInsideAnySelector && !isOnFileItem && !isOnInput && !isOnAtButton) {
                hideFileSelector();
            }
        }
    });
}

// ========== 输入框处理 ==========

// 初始化输入框高度和事件
function initializeInputBox() {
    const userInput = document.getElementById('userInput');

    // 初始化输入框高度 - 设置默认高度
    userInput.style.height = '100px';

    // 监听输入事件
    userInput.addEventListener('input', function() {
        handleInputChange(this);
    });

    // 监听按键事件
    userInput.addEventListener('keydown', function(event) {
        // 如果正在显示文件选择器，处理特殊按键
        if (isShowingFileSelector) {
            if (event.key === 'Escape') {
                event.preventDefault();
                hideFileSelector();
                return;
            }
        }

        handleKeyDown(event);
    });
}
