// ========== 模型管理模块 ==========

// ========== 初始化 ==========

// 初始化模型列表
function initializeModels() {
    if (window.sendMessageToKotlin) {
        window.sendMessageToKotlin('##GET_MODELS##');
    } else {
        setTimeout(initializeModels, 1000);
    }
}

// 初始化自定义下拉框
function initializeCustomSelect() {
    // 防止重复初始化
    if (initializeCustomSelect.initialized) {
        return;
    }
    initializeCustomSelect.initialized = true;

    // 同时初始化底部和欢迎界面的模型选择器
    initializeSingleSelect('customSelect', 'selectSelected', 'selectItems');
    initializeSingleSelect('welcomeCustomSelect', 'welcomeSelectSelected', 'welcomeSelectItems');

    // 点击其他地方时关闭所有下拉框（只添加一次事件监听器）
    document.addEventListener('click', function(event) {
        const allSelects = document.querySelectorAll('.custom-select');
        let clickedInsideSelect = false;

        allSelects.forEach(select => {
            if (select.contains(event.target)) {
                clickedInsideSelect = true;
            }
        });

        // 只有当没有点击任何下拉框时，才关闭所有下拉框
        if (!clickedInsideSelect) {
            allSelects.forEach(select => {
                const selectItems = select.querySelector('.select-items');
                const selectSelected = select.querySelector('.select-selected');
                if (selectItems) selectItems.style.display = 'none';
                if (selectSelected) selectSelected.classList.remove('select-arrow-active');
            });
        }
    });
}

// 初始化单个下拉选择器
function initializeSingleSelect(selectId, selectedId, itemsId) {
    const customSelect = document.getElementById(selectId);
    const selectSelected = document.getElementById(selectedId);
    const selectItems = document.getElementById(itemsId);

    if (!customSelect || !selectSelected || !selectItems) {
        console.warn(`[WARN] Model selector elements not found: ${selectId}, ${selectedId}, ${itemsId}`);
        return;
    }

    // 点击选中的项目时切换下拉框
    selectSelected.addEventListener('click', function(event) {
        event.stopPropagation();

        // 先关闭其他下拉框
        const allSelectItems = document.querySelectorAll('.select-items');
        const allSelectSelected = document.querySelectorAll('.select-selected');

        allSelectItems.forEach(item => {
            if (item !== selectItems) {
                item.style.display = 'none';
            }
        });

        allSelectSelected.forEach(selected => {
            if (selected !== this) {
                selected.classList.remove('select-arrow-active');
            }
        });

        // 切换当前下拉框
        const isCurrentlyOpen = selectItems.style.display === 'block';
        selectItems.style.display = isCurrentlyOpen ? 'none' : 'block';
        this.classList.toggle('select-arrow-active', !isCurrentlyOpen);
    });
}

// ========== 供应商图标管理 ==========

// 获取供应商图标
function getProviderIcon(provider) {
    if (!provider || provider === 'unknown') {
        provider = 'custom';
    }

    // 将供应商名称映射到图标文件名
    let iconName;
    switch(provider.toUpperCase()) {
        case 'CHATGPT':
            iconName = 'chatgpt';
            break;
        case 'CLAUDE':
            iconName = 'claude';
            break;
        case 'GEMINI':
            iconName = 'gemini';
            break;
        case 'DEEPSEEK':
            iconName = 'deepseek';
            break;
        case 'MISTRAL':
            iconName = 'mistral';
            break;
        case 'META':
            iconName = 'meta';
            break;
        case 'QWEN':
        case '通义千问':
            iconName = 'qwen';
            break;
        case 'DOUBAO':
        case '豆包':
            iconName = 'doubao';
            break;
        case 'WENXIN':
        case '文心一言':
            iconName = 'yiyan';
            break;
        case 'HUNYUAN':
        case '腾讯混元':
            iconName = 'hunyuan';
            break;
        case 'SPARK':
        case '讯飞星火':
            iconName = 'xfyun';
            break;
        case 'STEPFUN':
        case '阶跃星辰':
            iconName = 'stepfun';
            break;
        case 'KIMI':
            iconName = 'kimi';
            break;
        case 'MINIMAX':
        case 'MiniMax':
            iconName = 'minimax';
            break;
        case 'HUGGINGFACE':
        case 'HUGGING FACE':
            iconName = 'huggingface';
            break;
        case 'MODELSCOPE':
        case 'ModelScope':
            iconName = 'modelscope';
            break;
        case 'ZHIPU':
        case '智谱AI':
            iconName = 'zhipu';
            break;
        case 'COHERE':
            iconName = 'cohere';
            break;
        case 'SILICONFLOW':
        case '硅基流动':
            iconName = 'siliconflow';
            break;
        case 'GROK':
            iconName = 'grok';
            break;
        case 'OLLAMA':
            iconName = 'ollama';
            break;
        case 'LMSTUDIO':
        case 'LM STUDIO':
            iconName = 'lmstudio';
            break;
        case 'VLLM':
            iconName = 'vllm';
            break;
        case 'OPENROUTER':
            iconName = 'openrouter';
            break;
        case 'CUSTOM_API':
        case '自定义API':
        case 'CUSTOM':
        default:
            iconName = 'custom';
    }

    // 先检查缓存
    if (iconCache[iconName]) {
        return iconCache[iconName];
    }

    // 如果缓存中没有，请求Kotlin获取图标内容
    if (window.sendMessageToKotlin) {
        window.sendMessageToKotlin('##GET_ICON##' + iconName);
    }

    // 返回占位符，稍后会被替换
    return `<div class="provider-icon-placeholder" data-icon="${iconName}">${provider.substring(0, 2).toUpperCase()}</div>`;
}

// 处理图标响应（由Kotlin调用）
window.handleIcon = function(data) {
    if (data && data.iconName && data.iconContent) {
        // 缓存图标内容
        iconCache[data.iconName] = data.iconContent;

        // 替换所有使用该图标的占位符
        const placeholders = document.querySelectorAll(`[data-icon="${data.iconName}"]`);
        placeholders.forEach(placeholder => {
            placeholder.outerHTML = data.iconContent;
        });
    }
};

// ========== 模型切换 ==========

// 处理模型切换
function handleModelChange(modelId, modelName) {
    if (modelId && window.sendMessageToKotlin) {
        window.sendMessageToKotlin('##SWITCH_MODEL##' + modelId);

        // 同时更新底部和欢迎界面的模型选择器
        const selectors = [
            { selectSelected: document.getElementById('selectSelected'), selectItems: document.getElementById('selectItems') },
            { selectSelected: document.getElementById('welcomeSelectSelected'), selectItems: document.getElementById('welcomeSelectItems') }
        ];

        // 从模型映射中获取供应商信息
        const model = modelsMap.get(modelId);
        const selectedProvider = model ? (model.provider || 'custom') : 'custom';
        const selectedIcon = getProviderIcon(selectedProvider);

        // 更新所有选择器
        selectors.forEach(({ selectSelected, selectItems }) => {
            if (!selectSelected || !selectItems) return;

            // 更新选中状态
            selectItems.querySelectorAll('div').forEach(item => {
                if (item.dataset.value === modelId) {
                    item.classList.add('selected');
                } else {
                    item.classList.remove('selected');
                }
            });

            // 正确更新选中显示，包含图标
            selectSelected.innerHTML = `${selectedIcon}<span class="selected-model-name">${modelName}</span>`;

            selectItems.style.display = 'none';
            selectSelected.classList.remove('select-arrow-active');
        });
    }
}

// ========== 模型列表更新 ==========

// 更新模型列表（由Kotlin调用）
window.updateModels = function(modelsData) {
    const { models, selectedModelId } = modelsData;

    // 同时更新底部和欢迎界面的模型选择器
    const selectors = [
        { selectSelected: document.getElementById('selectSelected'), selectItems: document.getElementById('selectItems') },
        { selectSelected: document.getElementById('welcomeSelectSelected'), selectItems: document.getElementById('welcomeSelectItems') }
    ];

    selectors.forEach(({ selectSelected, selectItems }) => {
        if (!selectSelected || !selectItems) return;

        // 清空现有选项和模型映射
        selectItems.innerHTML = '';
    });

    modelsMap.clear();

    let selectedModelName = window.i18n?.actionSelectModel || 'Please select model';
    let selectedProvider = '';

    // 添加模型选项
    models.forEach(model => {
        // 存储模型信息到映射中
        modelsMap.set(model.id, model);

        const cleanName = cleanModelName(model.name);
        const providerIcon = getProviderIcon(model.provider || 'custom');

        if (model.id === selectedModelId) {
            selectedModelName = cleanName;
            selectedProvider = model.provider || 'custom';
        }

        // 为两个选择器都添加选项
        selectors.forEach(({ selectItems }) => {
            if (!selectItems) return;

            const item = document.createElement('div');
            item.dataset.value = model.id;
            item.innerHTML = `${providerIcon}<span class="model-name">${cleanName}</span>`;

            if (model.id === selectedModelId) {
                item.classList.add('selected');
            }

            // 点击选项时切换模型
            item.addEventListener('click', function() {
                handleModelChange(model.id, cleanName);
            });

            selectItems.appendChild(item);
        });
    });

    // 更新两个选择器当前选中的显示
    const selectedIcon = getProviderIcon(selectedProvider);
    selectors.forEach(({ selectSelected }) => {
        if (selectSelected) {
            selectSelected.innerHTML = `${selectedIcon}<span class="selected-model-name">${selectedModelName}</span>`;
        }
    });
};
