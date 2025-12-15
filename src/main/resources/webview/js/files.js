// ========== 文件选择器模块 ==========

// ========== 文件引用管理 ==========

// 更新文件引用显示（保持兼容性）
function updateFileReferences() {
    updateCodeReferences();
}

// 清除文件引用
function clearFileReferences() {
    selectedFiles = [];
    updateCodeReferences();
}

// 清除所有代码引用
function clearCodeReferences() {
    codeReferences = [];
    updateCodeReferences();
}

// 更新代码引用显示
function updateCodeReferences() {
    // 根据当前活动输入框选择正确的引用区域
    const fileReferencesId = currentInputId === 'welcomeUserInput' ? 'welcomeFileReferences' : 'fileReferences';
    const fileReferences = document.getElementById(fileReferencesId);

    // 合并文件引用和代码引用
    const allReferences = [
        ...selectedFiles.map(file => ({ type: 'file', ...file })),
        ...codeReferences.map(code => ({ type: 'code', ...code }))
    ];

    if (allReferences.length === 0) {
        if (fileReferences) {
            fileReferences.style.display = 'none';
            fileReferences.innerHTML = '';
        }
        return;
    }

    if (fileReferences) {
        fileReferences.style.display = 'flex';
        fileReferences.innerHTML = allReferences.map((ref, index) => {
            if (ref.type === 'file') {
                return `
                    <div class="file-reference">
                        <svg class="file-reference-icon" viewBox="0 0 16 16" fill="currentColor">
                            <path d="M4 0h8a2 2 0 012 2v12a2 2 0 01-2 2H4a2 2 0 01-2-2V2a2 2 0 012-2zM4 1a1 1 0 00-1 1v12a1 1 0 001 1h8a1 1 0 001-1V2a1 1 0 00-1-1H4z"/>
                            <path d="M6 3h4v1H6V3zm0 2h4v1H6V5zm0 2h4v1H6V7zm0 2h4v1H6V9z"/>
                        </svg>
                        <span class="file-reference-name" title="${escapeHtml(ref.path)}">${escapeHtml(ref.name)}</span>
                        <span class="file-reference-close" onclick="removeReference(${index}, 'file')">×</span>
                    </div>
                `;
            } else {
                const displayName = ref.lineRange ? `${ref.fileName} (${ref.lineRange})` : ref.fileName;
                return `
                    <div class="file-reference code-reference">
                        <svg class="file-reference-icon" viewBox="0 0 16 16" fill="currentColor">
                            <path d="M5.854 4.854a.5.5 0 10-.708-.708l-3.5 3.5a.5.5 0 000 .708l3.5 3.5a.5.5 0 10.708-.708L2.707 8l3.147-3.146zm4.292 0a.5.5 0 01.708-.708l3.5 3.5a.5.5 0 010 .708l-3.5 3.5a.5.5 0 01-.708-.708L13.293 8l-3.147-3.146z"/>
                        </svg>
                        <span class="file-reference-name" title="${escapeHtml(ref.text.substring(0, 100))}...">${escapeHtml(displayName)}</span>
                        <span class="file-reference-close" onclick="removeReference(${index}, 'code')">×</span>
                    </div>
                `;
            }
        }).join('');
    }
}

// 统一的引用移除函数
function removeReference(index, type) {
    const allReferences = [
        ...selectedFiles.map(file => ({ type: 'file', ...file })),
        ...codeReferences.map(code => ({ type: 'code', ...code }))
    ];

    const ref = allReferences[index];
    if (ref.type === 'file') {
        const fileIndex = selectedFiles.findIndex(f => f.name === ref.name && f.path === ref.path);
        if (fileIndex !== -1) {
            selectedFiles.splice(fileIndex, 1);
        }
    } else {
        const codeIndex = codeReferences.findIndex(c => c.id === ref.id);
        if (codeIndex !== -1) {
            codeReferences.splice(codeIndex, 1);
        }
    }

    updateCodeReferences();
}

// ========== 文件选择器UI ==========

// 显示文件选择器
function showFileSelector(inputId = 'userInput') {
    const fileSelector = inputId === 'welcomeUserInput'
        ? document.getElementById('welcomeFileSelector')
        : document.getElementById('fileSelector');
    const userInput = document.getElementById(inputId);

    // 更新当前活动输入框ID
    currentInputId = inputId;

    // 隐藏另一个文件选择器
    const otherFileSelector = inputId === 'welcomeUserInput'
        ? document.getElementById('fileSelector')
        : document.getElementById('welcomeFileSelector');
    if (otherFileSelector) {
        otherFileSelector.style.display = 'none';
    }

    isShowingFileSelector = true;
    if (fileSelector) {
        fileSelector.style.display = 'flex';
    }

    if (userInput) {
        userInput.classList.add('showing-file-selector');
    }

    // 聚焦到搜索框
    const searchInput = inputId === 'welcomeUserInput'
        ? document.getElementById('welcomeFileSearchInput')
        : document.getElementById('fileSearchInput');

    setTimeout(() => {
        if (searchInput) {
            searchInput.focus();
        }
    }, 100);

    // 加载最近打开的文件（当前文件 + 最近4个）
    loadRecentFiles();

    // 立即更新文件引用显示，确保当前输入框的引用正确显示
    updateCodeReferences();
}

// 加载最近打开的文件
function loadRecentFiles() {
    if (window.sendMessageToKotlin) {
        window.sendMessageToKotlin('##GET_RECENT_FILES##');
    }
}

// 处理最近文件列表（由Kotlin调用）
window.handleRecentFiles = function(data) {
    const results = currentInputId === 'welcomeUserInput'
        ? document.getElementById('welcomeFileSelectorResults')
        : document.getElementById('fileSelectorResults');

    if (!data || !data.files || data.files.length === 0) {
        results.innerHTML = '<div class="file-selector-loading">' + (window.i18n?.fileSelectorHint || 'Enter filename to search or click "Browse..." to select files') + '</div>';
        return;
    }

    let html = '';

    // 如果有当前文件，显示在最前面
    if (data.currentFile) {
        const ext = data.currentFile.extension || '';
        const fileIcon = getFileIcon(ext);
        html += `
            <div class="file-item file-item-current" onclick="window.selectFile({name: '${escapeHtml(data.currentFile.name)}', path: '${escapeHtml(data.currentFile.path)}', content: ''})">
                ${fileIcon}
                <div class="file-item-info">
                    <div class="file-item-name">${escapeHtml(data.currentFile.name)}</div>
                    <div class="file-item-path">${escapeHtml(data.currentFile.path)}</div>
                </div>
            </div>
        `;
    }

    // 显示最近打开的文件
    data.files.forEach(file => {
        const ext = file.extension || '';
        const fileIcon = getFileIcon(ext);
        html += `
            <div class="file-item" onclick="window.selectFile({name: '${escapeHtml(file.name)}', path: '${escapeHtml(file.path)}', content: ''})">
                ${fileIcon}
                <div class="file-item-info">
                    <div class="file-item-name">${escapeHtml(file.name)}</div>
                    <div class="file-item-path">${escapeHtml(file.path)}</div>
                </div>
            </div>
        `;
    });

    // 如果没有任何文件，显示提示
    if (!html) {
        html = '<div class="file-selector-loading">' + (window.i18n?.fileSelectorHint || 'Enter filename to search or click "Browse..." to select files') + '</div>';
    }

    results.innerHTML = html;
};

// 隐藏文件选择器
function hideFileSelector() {
    const fileSelector = currentInputId === 'welcomeUserInput'
        ? document.getElementById('welcomeFileSelector')
        : document.getElementById('fileSelector');

    isShowingFileSelector = false;
    if (fileSelector) {
        fileSelector.style.display = 'none';
    }

    // 清空搜索框（但不清空结果，保留浏览状态）
    const searchInput = currentInputId === 'welcomeUserInput'
        ? document.getElementById('welcomeFileSearchInput')
        : document.getElementById('fileSearchInput');
    if (searchInput) {
        searchInput.value = '';
    }

    // 移除当前活动输入框的showing-file-selector类
    const userInput = document.getElementById(currentInputId);
    if (userInput) {
        userInput.classList.remove('showing-file-selector');
    }

    // 确保文件引用正确显示在当前输入框上
    updateCodeReferences();

    // 重置浏览路径
    currentBrowsePath = '';
}

// 移除文件引用（保持兼容性）
function removeFileReference(index) {
    selectedFiles.splice(index, 1);
    updateCodeReferences();
}

// ========== 文件搜索与选择 ==========

// 搜索文件（防抖）
function searchFilesDebounced(query) {
    clearTimeout(fileSearchTimeout);
    fileSearchTimeout = setTimeout(() => {
        searchFiles(query);
    }, 300);
}

// 搜索文件
function searchFiles(query) {
    const results = currentInputId === 'welcomeUserInput'
        ? document.getElementById('welcomeFileSelectorResults')
        : document.getElementById('fileSelectorResults');

    if (!query.trim()) {
        results.innerHTML = '<div class="file-selector-loading">' + (window.i18n?.searchFilePlaceholder || 'Search filename...') + '</div>';
        return;
    }

    results.innerHTML = '<div class="file-selector-loading">' + (window.i18n?.searchSearching || 'Searching...') + '</div>';

    // 修正：使用正确的命令格式
    if (window.sendMessageToKotlin) {
        window.sendMessageToKotlin('##SEARCH_FILE##' + query);
    }
}

// 浏览文件 - 使用自定义文件树浏览器
function browseFiles() {
    // 显示文件树浏览模式
    showFileBrowser('');
}

// 当前浏览的目录路径
let currentBrowsePath = '';

// 显示文件浏览器（加载指定目录）
function showFileBrowser(path) {
    currentBrowsePath = path;
    const results = currentInputId === 'welcomeUserInput'
        ? document.getElementById('welcomeFileSelectorResults')
        : document.getElementById('fileSelectorResults');

    results.innerHTML = '<div class="file-selector-loading">' + (window.i18n?.loading || 'Loading...') + '</div>';

    if (window.sendMessageToKotlin) {
        window.sendMessageToKotlin('##GET_DIRECTORY##' + path);
    }
}

// 处理目录内容（由Kotlin调用）
window.handleDirectoryContents = function(data) {
    const results = currentInputId === 'welcomeUserInput'
        ? document.getElementById('welcomeFileSelectorResults')
        : document.getElementById('fileSelectorResults');

    if (!data || !data.items) {
        results.innerHTML = '<div class="file-selector-loading">' + (window.i18n?.searchNoFilesFound || 'No files found') + '</div>';
        return;
    }

    let html = '';

    // 添加返回上级目录按钮（如果不在根目录）
    if (data.path !== '') {
        html += `
            <div class="file-item file-item-back" onclick="showFileBrowser('${escapeHtml(data.parentPath)}')">
                <svg class="file-item-icon" viewBox="0 0 16 16" fill="currentColor">
                    <path d="M8 0a8 8 0 100 16A8 8 0 008 0zm3.5 7.5a.5.5 0 010 1H5.707l2.147 2.146a.5.5 0 01-.708.708l-3-3a.5.5 0 010-.708l3-3a.5.5 0 11.708.708L5.707 7.5H11.5z"/>
                </svg>
                <div class="file-item-info">
                    <div class="file-item-name">..</div>
                    <div class="file-item-path">${window.i18n?.backToParent || 'Back to parent directory'}</div>
                </div>
            </div>
        `;
    }

    // 当前路径提示
    if (data.path !== '') {
        html += `<div class="file-browser-path">/${escapeHtml(data.path)}</div>`;
    }

    // 渲染目录和文件列表
    data.items.forEach(item => {
        if (item.isDirectory) {
            // 目录项
            html += `
                <div class="file-item file-item-folder" onclick="showFileBrowser('${escapeHtml(item.path)}')">
                    <svg class="file-item-icon folder-icon" viewBox="0 0 16 16" fill="currentColor">
                        <path d="M1 3.5A1.5 1.5 0 012.5 2h2.764c.958 0 1.76.56 2.311 1.184C7.985 3.648 8.48 4 9 4h4.5A1.5 1.5 0 0115 5.5v7a1.5 1.5 0 01-1.5 1.5h-11A1.5 1.5 0 011 12.5v-9z"/>
                    </svg>
                    <div class="file-item-info">
                        <div class="file-item-name">${escapeHtml(item.name)}</div>
                    </div>
                    <svg class="file-item-arrow" viewBox="0 0 16 16" fill="currentColor">
                        <path d="M6 12.796V3.204L11.481 8 6 12.796zm.659.753l5.48-4.796a1 1 0 000-1.506L6.66 2.451C6.011 1.885 5 2.345 5 3.204v9.592a1 1 0 001.659.753z"/>
                    </svg>
                </div>
            `;
        } else {
            // 文件项
            const fileIcon = getFileIcon(item.extension);
            html += `
                <div class="file-item" onclick="window.selectFile({name: '${escapeHtml(item.name)}', path: '${escapeHtml(item.path)}', content: ''})">
                    ${fileIcon}
                    <div class="file-item-info">
                        <div class="file-item-name">${escapeHtml(item.name)}</div>
                    </div>
                    ${item.size ? `<div class="file-item-size">${formatFileSize(item.size)}</div>` : ''}
                </div>
            `;
        }
    });

    if (data.items.length === 0 && data.path === '') {
        html = '<div class="file-selector-loading">' + (window.i18n?.searchNoFilesFound || 'No files found') + '</div>';
    }

    results.innerHTML = html;
};

// 根据文件扩展名返回对应图标
function getFileIcon(ext) {
    const iconColors = {
        // 编程语言
        'kt': '#A97BFF', 'java': '#E76F00', 'js': '#F7DF1E', 'ts': '#3178C6',
        'py': '#3776AB', 'go': '#00ADD8', 'rs': '#DEA584', 'cpp': '#00599C',
        'c': '#A8B9CC', 'cs': '#239120', 'swift': '#FA7343', 'rb': '#CC342D',
        'php': '#777BB4', 'dart': '#0175C2', 'scala': '#DC322F',
        // 标记语言
        'html': '#E34F26', 'css': '#1572B6', 'scss': '#CC6699', 'xml': '#F80',
        'json': '#000000', 'yaml': '#CB171E', 'yml': '#CB171E', 'md': '#083FA1',
        // 其他
        'sql': '#336791', 'sh': '#4EAA25', 'bat': '#4D4D4D', 'gradle': '#02303A'
    };

    const color = iconColors[ext] || 'currentColor';

    return `<svg class="file-item-icon" viewBox="0 0 16 16" fill="${color}">
        <path d="M4 0h8a2 2 0 012 2v12a2 2 0 01-2 2H4a2 2 0 01-2-2V2a2 2 0 012-2zM4 1a1 1 0 00-1 1v12a1 1 0 001 1h8a1 1 0 001-1V2a1 1 0 00-1-1H4z"/>
        <path d="M6 3h4v1H6V3zm0 2h4v1H6V5zm0 2h4v1H6V7zm0 2h4v1H6V9z"/>
    </svg>`;
}

// 格式化文件大小
function formatFileSize(bytes) {
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
}

// 选择文件（由Kotlin调用）
window.selectFile = function(file) {
    // 检查是否已经选择过
    if (selectedFiles.find(f => f.path === file.path)) {
        return;
    }

    selectedFiles.push(file);
    updateFileReferences();
    hideFileSelector();

    // 读取文件内容
    if (window.sendMessageToKotlin) {
        window.sendMessageToKotlin('##READ_FILE##' + file.path);
    }

    // 清除当前活动输入框中的@文件名
    clearAtSymbolFromInput(currentInputId);
};

// 清除输入框中的@符号及其后面的内容
function clearAtSymbolFromInput(inputId = 'userInput') {
    const userInput = document.getElementById(inputId);
    const text = userInput.value;
    const cursorPos = userInput.selectionStart;
    const atIndex = findLastAtSymbol(text, cursorPos);

    if (atIndex !== -1) {
        const beforeAt = text.substring(0, atIndex);
        const afterCursor = text.substring(cursorPos);
        userInput.value = beforeAt + afterCursor;

        // 设置光标位置到@符号原来的位置
        userInput.setSelectionRange(atIndex, atIndex);
        autoResize(userInput);
    }
}

// ========== Kotlin回调函数 ==========

// 更新文件搜索结果（由Kotlin调用）
window.updateFileSearchResults = function(files) {
    const results = currentInputId === 'welcomeUserInput'
        ? document.getElementById('welcomeFileSelectorResults')
        : document.getElementById('fileSelectorResults');

    if (!files || files.length === 0) {
        results.innerHTML = '<div class="file-selector-loading">' + (window.i18n?.searchNoFilesFound || 'No matching files found') + '</div>';
        return;
    }

    results.innerHTML = files.map(file => `
        <div class="file-item" onclick="window.selectFile({name: '${escapeHtml(file.name)}', path: '${escapeHtml(file.path)}', content: ''})">
            <svg class="file-item-icon" viewBox="0 0 16 16" fill="currentColor">
                <path d="M4 0h8a2 2 0 012 2v12a2 2 0 01-2 2H4a2 2 0 01-2-2V2a2 2 0 012-2zM4 1a1 1 0 00-1 1v12a1 1 0 001 1h8a1 1 0 001-1V2a1 1 0 00-1-1H4z"/>
                <path d="M6 3h4v1H6V3zm0 2h4v1H6V5zm0 2h4v1H6V7zm0 2h4v1H6V9z"/>
            </svg>
            <div class="file-item-info">
                <div class="file-item-name">${escapeHtml(file.name)}</div>
                <div class="file-item-path">${escapeHtml(file.path)}</div>
            </div>
            ${file.size ? `<div class="file-item-size">${file.size}</div>` : ''}
        </div>
    `).join('');
};

// 处理文件搜索结果（由Kotlin调用）
window.handleFileSearchResult = function(data) {
    if (data && data.files) {
        window.updateFileSearchResults(data.files);
    }
};

// 处理选择的文件（由Kotlin调用）
window.handleFilesSelected = function(data) {
    if (data && data.files && data.files.length > 0) {
        // 对于浏览选择的文件，添加到已选择文件列表
        data.files.forEach(file => {
            if (!selectedFiles.find(f => f.path === file.path)) {
                selectedFiles.push({
                    name: file.name,
                    path: file.path,
                    content: '' // 内容将在需要时加载
                });
            }
        });
        updateFileReferences();
        hideFileSelector();

        // 清除当前活动输入框中的@内容
        clearAtSymbolFromInput(currentInputId);
    }
};

// 处理文件内容（由Kotlin调用）
window.handleFileContent = function(data) {
    if (data && data.file) {
        // 更新已选择文件的内容
        const existingFile = selectedFiles.find(f => f.path === data.file.path);
        if (existingFile) {
            existingFile.content = data.file.content;
            // 刷新文件引用显示，确保界面更新
            updateFileReferences();
        }
    }
};

// 处理文件错误（由Kotlin调用）
window.handleFileError = function(data) {
    if (data && data.message) {
        showErrorToast(data.message);
    }
};

// ========== 输入处理 ==========

// 处理输入变化（去掉@文件自动触发）
function handleInputChange(textarea) {
    autoResize(textarea);
    // 移除了@文件自动触发逻辑，现在通过@按钮手动触发
}
