// ========== 代码选择模块 ==========

// 获取选中的代码
function getSelectedCode() {
    if (window.sendMessageToKotlin) {
        window.sendMessageToKotlin('##GET_SELECTED_CODE##');
    }
}

// 处理选中代码的结果（由Kotlin调用）
window.handleSelectedCode = function(data) {
    if (data && data.code) {
        if (data.code.hasSelection) {
            selectedCodeInfo = data.code;
            addSelectedCodeToInput();
        } else {
            showErrorToast(data.code.message || (window.i18n?.fileNoCodeSelected || 'No code selected'));
        }
    }
};

// 将选中的代码添加为引用（不直接插入输入框）
function addSelectedCodeToInput() {
    if (!selectedCodeInfo) return;

    // 创建代码引用对象
    const codeRef = {
        id: Date.now(),
        fileName: selectedCodeInfo.fileName || 'unknown',
        language: selectedCodeInfo.language || 'text',
        text: selectedCodeInfo.text,
        lineRange: selectedCodeInfo.lineRange || null // 如果有行号范围信息
    };

    // 添加到代码引用列表
    codeReferences.push(codeRef);

    // 更新显示
    updateCodeReferences();

    // 清空选中代码信息
    selectedCodeInfo = null;
}
