package com.wenguang.ai.assistant.git

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ContentRevision
import com.wenguang.ai.assistant.api.AIClientManager
import com.wenguang.ai.assistant.i18n.I18nManager
import com.wenguang.ai.assistant.settings.AppSettings

/**
 * Git提交注释生成器
 */
class GitCommitMessageGenerator {
    // 使用调度器来避免阻塞UI线程
    private val aiClientManager = AIClientManager.getInstance()

    /**
     * 生成提交注释
     */
    fun generateCommitMessage(
        project: Project,
        changes: List<Change>,
        onResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        try {

            // 获取代码变更信息
            val changesText = extractChangesText(changes)
            if (changesText.isBlank()) {
                onError(I18nManager.getMessage("git.no.changes"))
                return
            }

            // 获取提交注释模板
            val template = AppSettings.instance.state.getEffectiveCommitMessageTemplate()

            // 构建AI提示词
            val prompt = template + "\n\n" + changesText

            // 获取选中的模型
            val selectedModel = AppSettings.instance.state.getSelectedChatModel()
            if (selectedModel == null) {
                onError(I18nManager.getMessage("git.no.model"))
                return
            }

            // 调用AI生成提交注释
            var fullResponse = ""
            aiClientManager.sendChatMessage(
                modelConfig = selectedModel,
                userMessage = prompt,
                historyMessages = emptyList(),
                onChunk = { chunk ->
                    fullResponse += chunk
                },
                onDone = {
                    val cleanedMessage = cleanCommitMessage(fullResponse)
                    onResult(cleanedMessage)
                }
            )

        } catch (e: Exception) {
            println("${I18nManager.getMessage("git.generator.failed")}: $e")
            onError(I18nManager.getMessage("git.generator.failed.message", e.message ?: ""))
        }
    }

    /**
     * 提取代码变更文本
     */
    private fun extractChangesText(changes: List<Change>): String {
        val sb = StringBuilder()
        val analysisResults = mutableListOf<FileChangeAnalysis>()

        // 分析每个文件的变更
        changes.forEach { change ->
            val analysis = analyzeFileChange(change)
            analysisResults.add(analysis)
        }

        // 格式化输出
        sb.append(I18nManager.getMessage("git.analysis.header.overview") + "\n")
        val summary = generateChangeSummary(analysisResults)
        sb.append("$summary\n\n")

        sb.append(I18nManager.getMessage("git.analysis.header.details") + "\n")
        analysisResults.forEach { analysis ->
            sb.append(formatFileAnalysis(analysis))
            sb.append("\n")
        }

        return sb.toString()
    }

    /**
     * 分析代码内容
     */
    private fun analyzeContent(content: String, filePath: String): String {
        val summary = mutableListOf<String>()

        // 根据文件类型和内容分析
        when {
            filePath.endsWith(".java") -> {
                if (content.contains("class ")) summary.add(I18nManager.getMessage("git.content.class.definition"))
                if (content.contains("interface ")) summary.add(I18nManager.getMessage("git.content.interface.definition"))
                if (content.contains("@RestController") || content.contains("@Controller")) summary.add(
                    I18nManager.getMessage(
                        "git.content.spring.controller"
                    )
                )
                if (content.contains("@Service")) summary.add(I18nManager.getMessage("git.content.spring.service"))
                if (content.contains("@Repository")) summary.add(I18nManager.getMessage("git.content.spring.repository"))
                if (content.contains("@Component")) summary.add(I18nManager.getMessage("git.content.spring.component"))
                if (content.contains("@Aspect")) summary.add(I18nManager.getMessage("git.content.spring.aspect"))
            }

            filePath.endsWith(".kt") -> {
                if (content.contains("class ")) summary.add(I18nManager.getMessage("git.content.class.definition"))
                if (content.contains("interface ")) summary.add(I18nManager.getMessage("git.content.interface.definition"))
                if (content.contains("fun ")) summary.add(I18nManager.getMessage("git.content.function.definition"))
                if (content.contains("object ")) summary.add(I18nManager.getMessage("git.content.object.definition"))
            }

            filePath.endsWith(".js") || filePath.endsWith(".ts") -> {
                if (content.contains("function ")) summary.add(I18nManager.getMessage("git.content.function.definition"))
                if (content.contains("class ")) summary.add(I18nManager.getMessage("git.content.class.definition"))
                if (content.contains("export ")) summary.add(I18nManager.getMessage("git.content.module.export"))
                if (content.contains("import ")) summary.add(I18nManager.getMessage("git.content.module.import"))
            }
        }

        return summary.joinToString(", ")
    }

    /**
     * 文件变更分析结果
     */
    data class FileChangeAnalysis(
        val filePath: String,
        val changeType: ChangeType,
        val fileType: String,
        val beforeLines: Int = 0,
        val afterLines: Int = 0,
        val addedLines: Int = 0,
        val deletedLines: Int = 0,
        val modifiedLines: Int = 0,
        val addedMethods: Set<String> = emptySet(),
        val deletedMethods: Set<String> = emptySet(),
        val modifiedMethods: Set<String> = emptySet(),
        val addedImports: Set<String> = emptySet(),
        val deletedImports: Set<String> = emptySet(),
        val addedClasses: Set<String> = emptySet(),
        val deletedClasses: Set<String> = emptySet(),
        val significantChanges: List<String> = emptyList(),
        val contentSample: String = "",
        val methodChangesDetail: Map<String, String> = emptyMap() // 方法变更详情
    )

    enum class ChangeType {
        ADDED, DELETED, MODIFIED
    }

    /**
     * 分析单个文件的变更
     */
    private fun analyzeFileChange(change: Change): FileChangeAnalysis {
        val beforeRevision = change.beforeRevision
        val afterRevision = change.afterRevision

        return when {
            beforeRevision == null && afterRevision != null -> {
                // 新增文件
                analyzeAddedFile(afterRevision)
            }

            beforeRevision != null && afterRevision == null -> {
                // 删除文件
                analyzeDeletedFile(beforeRevision)
            }

            beforeRevision != null && afterRevision != null -> {
                // 修改文件
                analyzeModifiedFile(beforeRevision, afterRevision)
            }

            else -> {
                FileChangeAnalysis(
                    filePath = "unknown",
                    changeType = ChangeType.MODIFIED,
                    fileType = "unknown"
                )
            }
        }
    }

    /**
     * 分析新增文件
     */
    private fun analyzeAddedFile(revision: ContentRevision): FileChangeAnalysis {
        val filePath = revision.file.path
        val content = getRevisionContent(revision)
        val lines = content.lines()

        return FileChangeAnalysis(
            filePath = filePath,
            changeType = ChangeType.ADDED,
            fileType = getFileType(filePath),
            afterLines = lines.size,
            addedLines = lines.count { it.trim().isNotEmpty() },
            addedMethods = extractMethods(content, filePath),
            addedImports = extractImports(content),
            addedClasses = extractClasses(content, filePath),
            significantChanges = analyzeContentFeatures(content, filePath),
            contentSample = if (content.length > 500) content.take(500) + "..." else content
        )
    }

    /**
     * 分析删除文件
     */
    private fun analyzeDeletedFile(revision: ContentRevision): FileChangeAnalysis {
        val filePath = revision.file.path
        val content = getRevisionContent(revision)
        val lines = content.lines()

        return FileChangeAnalysis(
            filePath = filePath,
            changeType = ChangeType.DELETED,
            fileType = getFileType(filePath),
            beforeLines = lines.size,
            deletedLines = lines.count { it.trim().isNotEmpty() },
            deletedMethods = extractMethods(content, filePath),
            deletedImports = extractImports(content),
            deletedClasses = extractClasses(content, filePath)
        )
    }

    /**
     * 分析修改文件
     */
    private fun analyzeModifiedFile(
        beforeRevision: ContentRevision,
        afterRevision: ContentRevision
    ): FileChangeAnalysis {
        val filePath = afterRevision.file.path
        val beforeContent = getRevisionContent(beforeRevision)
        val afterContent = getRevisionContent(afterRevision)

        val beforeLines = beforeContent.lines()
        val afterLines = afterContent.lines()

        // 计算行级别变更
        val lineDiff = calculateLineDifferences(beforeLines, afterLines)

        // 分析结构变更
        val beforeMethods = extractMethods(beforeContent, filePath)
        val afterMethods = extractMethods(afterContent, filePath)
        val beforeImports = extractImports(beforeContent)
        val afterImports = extractImports(afterContent)
        val beforeClasses = extractClasses(beforeContent, filePath)
        val afterClasses = extractClasses(afterContent, filePath)

        // 生成详细的diff内容
        val detailedDiff = generateDetailedDiff(beforeContent, afterContent, filePath)

        return FileChangeAnalysis(
            filePath = filePath,
            changeType = ChangeType.MODIFIED,
            fileType = getFileType(filePath),
            beforeLines = beforeLines.size,
            afterLines = afterLines.size,
            addedLines = lineDiff.added,
            deletedLines = lineDiff.deleted,
            modifiedLines = lineDiff.modified,
            addedMethods = afterMethods - beforeMethods,
            deletedMethods = beforeMethods - afterMethods,
            modifiedMethods = findModifiedMethods(beforeContent, afterContent, filePath),
            addedImports = afterImports - beforeImports,
            deletedImports = beforeImports - afterImports,
            addedClasses = afterClasses - beforeClasses,
            deletedClasses = beforeClasses - afterClasses,
            significantChanges = identifySignificantChanges(beforeContent, afterContent, filePath),
            contentSample = detailedDiff
        )
    }

    /**
     * 计算行级别差异
     */
    data class LineDifference(val added: Int, val deleted: Int, val modified: Int)

    private fun calculateLineDifferences(beforeLines: List<String>, afterLines: List<String>): LineDifference {
        val maxLines = maxOf(beforeLines.size, afterLines.size)
        var added = 0
        var deleted = 0
        var modified = 0

        for (i in 0 until maxLines) {
            val beforeLine = beforeLines.getOrNull(i)?.trim() ?: ""
            val afterLine = afterLines.getOrNull(i)?.trim() ?: ""

            when {
                beforeLine.isEmpty() && afterLine.isNotEmpty() -> added++
                beforeLine.isNotEmpty() && afterLine.isEmpty() -> deleted++
                beforeLine != afterLine && beforeLine.isNotEmpty() && afterLine.isNotEmpty() -> modified++
            }
        }

        return LineDifference(added, deleted, modified)
    }

    /**
     * 获取文件类型
     */
    private fun getFileType(filePath: String): String {
        return when {
            filePath.endsWith(".kt") -> "Kotlin"
            filePath.endsWith(".java") -> "Java"
            filePath.endsWith(".js") -> "JavaScript"
            filePath.endsWith(".ts") -> "TypeScript"
            filePath.endsWith(".py") -> "Python"
            filePath.endsWith(".xml") -> "XML"
            filePath.endsWith(".json") -> "JSON"
            filePath.endsWith(".properties") -> "Properties"
            filePath.endsWith(".md") -> "Markdown"
            filePath.endsWith(".yml") || filePath.endsWith(".yaml") -> "YAML"
            filePath.endsWith(".gradle") -> "Gradle"
            else -> filePath.substringAfterLast('.', "Unknown")
        }
    }

    /**
     * 提取方法名
     */
    private fun extractMethods(content: String, filePath: String): Set<String> {
        val methods = mutableSetOf<String>()

        try {
            when {
                filePath.endsWith(".kt") -> {
                    // Kotlin函数
                    Regex("""\bfun\s+(\w+)\s*\(""").findAll(content).forEach {
                        methods.add(it.groupValues[1])
                    }
                }

                filePath.endsWith(".java") -> {
                    // Java方法
                    Regex("""(?:public|private|protected)\s+(?:static\s+)?(?:\w+\s+)*(\w+)\s*\(""").findAll(content)
                        .forEach {
                            val methodName = it.groupValues[1]
                            if (!methodName.matches(Regex("if|for|while|switch|catch"))) {
                                methods.add(methodName)
                            }
                        }
                }

                filePath.endsWith(".js") || filePath.endsWith(".ts") -> {
                    // JavaScript/TypeScript函数
                    Regex("""(?:function\s+(\w+)|const\s+(\w+)\s*=|let\s+(\w+)\s*=|var\s+(\w+)\s*=)""").findAll(content)
                        .forEach {
                            val name = it.groupValues.find { group -> group.isNotEmpty() && group != it.value }
                            name?.let { methods.add(it) }
                        }
                }
            }
        } catch (e: Exception) {
            println(I18nManager.getMessage("git.warn.method.extract", e.message ?: ""))
        }

        return methods
    }

    /**
     * 提取导入语句
     */
    private fun extractImports(content: String): Set<String> {
        val imports = mutableSetOf<String>()

        try {
            // Java/Kotlin import
            Regex("""import\s+([^\s;]+)""").findAll(content).forEach {
                val importPath = it.groupValues[1]
                val simpleName = importPath.substringAfterLast('.')
                if (simpleName.isNotEmpty()) {
                    imports.add(simpleName)
                }
            }

            // JavaScript ES6 import
            Regex("""import\s+.*?from\s+['"]([^'"]+)['"]""").findAll(content).forEach {
                val moduleName = it.groupValues[1].substringAfterLast('/')
                if (moduleName.isNotEmpty()) {
                    imports.add(moduleName)
                }
            }
        } catch (e: Exception) {
            println(I18nManager.getMessage("git.warn.import.extract", e.message ?: ""))
        }

        return imports
    }

    /**
     * 提取类名
     */
    private fun extractClasses(content: String, filePath: String): Set<String> {
        val classes = mutableSetOf<String>()

        try {
            when {
                filePath.endsWith(".kt") || filePath.endsWith(".java") -> {
                    Regex("""\b(?:class|interface|enum|object)\s+(\w+)""").findAll(content).forEach {
                        classes.add(it.groupValues[1])
                    }
                }

                filePath.endsWith(".js") || filePath.endsWith(".ts") -> {
                    Regex("""\bclass\s+(\w+)""").findAll(content).forEach {
                        classes.add(it.groupValues[1])
                    }
                }
            }
        } catch (e: Exception) {
            println(I18nManager.getMessage("git.warn.class.extract", e.message ?: ""))
        }

        return classes
    }

    /**
     * 分析内容特征
     */
    private fun analyzeContentFeatures(content: String, filePath: String): List<String> {
        val features = mutableListOf<String>()

        when {
            filePath.endsWith(".kt") -> {
                if (content.contains("@Controller") || content.contains("@RestController")) features.add(
                    I18nManager.getMessage(
                        "git.feature.spring.controller"
                    )
                )
                if (content.contains("@Service")) features.add(I18nManager.getMessage("git.feature.spring.service"))
                if (content.contains("@Repository")) features.add(I18nManager.getMessage("git.feature.spring.repository"))
                if (content.contains("@Component")) features.add(I18nManager.getMessage("git.feature.spring.component"))
                if (content.contains("@Test")) features.add(I18nManager.getMessage("git.feature.unit.test"))
                if (content.contains("suspend fun")) features.add(I18nManager.getMessage("git.feature.coroutine.function"))
            }

            filePath.endsWith(".java") -> {
                if (content.contains("@Controller") || content.contains("@RestController")) features.add(
                    I18nManager.getMessage(
                        "git.feature.spring.controller"
                    )
                )
                if (content.contains("@Service")) features.add(I18nManager.getMessage("git.feature.spring.service"))
                if (content.contains("@Repository")) features.add(I18nManager.getMessage("git.feature.spring.repository"))
                if (content.contains("@Component")) features.add(I18nManager.getMessage("git.feature.spring.component"))
                if (content.contains("@Test")) features.add(I18nManager.getMessage("git.feature.unit.test"))
            }

            filePath.endsWith(".properties") -> {
                if (content.contains("spring.")) features.add(I18nManager.getMessage("git.feature.spring.config"))
                if (content.contains("logging.")) features.add(I18nManager.getMessage("git.feature.log.config"))
                if (content.contains("server.")) features.add(I18nManager.getMessage("git.feature.server.config"))
            }

            filePath.endsWith(".xml") -> {
                if (content.contains("<bean")) features.add(I18nManager.getMessage("git.feature.spring.bean"))
                if (content.contains("<dependency")) features.add(I18nManager.getMessage("git.feature.maven.dependency"))
                if (content.contains("<plugin")) features.add(I18nManager.getMessage("git.feature.maven.plugin"))
            }
        }

        return features
    }

    /**
     * 查找修改的方法并分析具体变更
     */
    private fun findModifiedMethods(beforeContent: String, afterContent: String, filePath: String): Set<String> {
        val beforeMethods = extractMethodBodies(beforeContent, filePath)
        val afterMethods = extractMethodBodies(afterContent, filePath)

        val modifiedMethods = mutableSetOf<String>()

        beforeMethods.keys.intersect(afterMethods.keys).forEach { methodName ->
            val beforeBody = beforeMethods[methodName] ?: ""
            val afterBody = afterMethods[methodName] ?: ""

            if (beforeBody.trim() != afterBody.trim()) {
                modifiedMethods.add(methodName)
                // 记录方法变更的简要描述
            }
        }

        return modifiedMethods
    }

    /**
     * 提取方法体
     */
    private fun extractMethodBodies(content: String, filePath: String): Map<String, String> {
        val methods = mutableMapOf<String, String>()

        try {
            when {
                filePath.endsWith(".kt") -> {
                    val regex = Regex("""fun\s+(\w+)\s*\([^)]*\)\s*\{([^}]*)\}""")
                    regex.findAll(content).forEach {
                        methods[it.groupValues[1]] = it.groupValues[2].trim()
                    }
                }

                filePath.endsWith(".java") -> {
                    val regex =
                        Regex("""(?:public|private|protected)\s+(?:static\s+)?(?:\w+\s+)*(\w+)\s*\([^)]*\)\s*\{([^}]*)\}""")
                    regex.findAll(content).forEach {
                        val methodName = it.groupValues[1]
                        if (!methodName.matches(Regex("if|for|while|switch|catch"))) {
                            methods[methodName] = it.groupValues[2].trim()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println(I18nManager.getMessage("git.warn.method.body.extract", e.message ?: ""))
        }

        return methods
    }

    /**
     * 识别重要变更
     */
    private fun identifySignificantChanges(
        beforeContent: String,
        afterContent: String,
        filePath: String
    ): List<String> {
        val changes = mutableListOf<String>()

        // 检查版本变更
        if (filePath.contains("version") || filePath.contains("gradle") || filePath.contains("pom.xml")) {
            changes.add(I18nManager.getMessage("git.change.version"))
        }

        // 检查配置变更
        if (filePath.endsWith(".properties") || filePath.endsWith(".xml") || filePath.endsWith(".yml")) {
            changes.add(I18nManager.getMessage("git.change.config"))
        }

        // 检查API接口变更
        val beforeApis = extractApiEndpoints(beforeContent)
        val afterApis = extractApiEndpoints(afterContent)
        if (beforeApis != afterApis) {
            changes.add(I18nManager.getMessage("git.change.api"))
        }

        // 检查数据库相关变更
        if (beforeContent.contains("@Entity") != afterContent.contains("@Entity") ||
            beforeContent.contains("@Table") != afterContent.contains("@Table") ||
            beforeContent.contains("CREATE TABLE") != afterContent.contains("CREATE TABLE")
        ) {
            changes.add(I18nManager.getMessage("git.change.database"))
        }

        return changes
    }

    /**
     * 提取API端点
     */
    private fun extractApiEndpoints(content: String): Set<String> {
        val endpoints = mutableSetOf<String>()

        val patterns = listOf(
            Regex("""@(?:Get|Post|Put|Delete|Patch)Mapping\s*\(\s*["']([^"']+)["']\s*\)"""),
            Regex("""@RequestMapping\s*\([^)]*value\s*=\s*["']([^"']+)["'][^)]*\)""")
        )

        patterns.forEach { pattern ->
            pattern.findAll(content).forEach {
                endpoints.add(it.groupValues[1])
            }
        }

        return endpoints
    }

    /**
     * 生成变更概览
     */
    private fun generateChangeSummary(analyses: List<FileChangeAnalysis>): String {
        val summary = StringBuilder()

        val addedFiles = analyses.count { it.changeType == ChangeType.ADDED }
        val deletedFiles = analyses.count { it.changeType == ChangeType.DELETED }
        val modifiedFiles = analyses.count { it.changeType == ChangeType.MODIFIED }

        summary.append(I18nManager.getMessage("git.summary.file.changes"))
        if (addedFiles > 0) summary.append(I18nManager.getMessage("git.summary.files.added", addedFiles))
        if (deletedFiles > 0) summary.append(I18nManager.getMessage("git.summary.files.deleted", deletedFiles))
        if (modifiedFiles > 0) summary.append(I18nManager.getMessage("git.summary.files.modified", modifiedFiles))
        summary.append(I18nManager.getMessage("git.summary.files.suffix") + "\n")

        val totalAddedLines = analyses.sumOf { it.addedLines }
        val totalDeletedLines = analyses.sumOf { it.deletedLines }
        val totalModifiedLines = analyses.sumOf { it.modifiedLines }

        summary.append(
            I18nManager.getMessage(
                "git.summary.code.changes",
                totalAddedLines,
                totalDeletedLines,
                totalModifiedLines
            ) + "\n"
        )

        val allAddedMethods = analyses.flatMap { it.addedMethods }.toSet()
        val allDeletedMethods = analyses.flatMap { it.deletedMethods }.toSet()
        val allModifiedMethods = analyses.flatMap { it.modifiedMethods }.toSet()

        if (allAddedMethods.isNotEmpty() || allDeletedMethods.isNotEmpty() || allModifiedMethods.isNotEmpty()) {
            summary.append(I18nManager.getMessage("git.summary.method.changes"))
            if (allAddedMethods.isNotEmpty()) summary.append(
                I18nManager.getMessage(
                    "git.summary.methods.added",
                    allAddedMethods.size
                )
            )
            if (allDeletedMethods.isNotEmpty()) summary.append(
                I18nManager.getMessage(
                    "git.summary.methods.deleted",
                    allDeletedMethods.size
                )
            )
            if (allModifiedMethods.isNotEmpty()) summary.append(
                I18nManager.getMessage(
                    "git.summary.methods.modified",
                    allModifiedMethods.size
                )
            )
            summary.append(I18nManager.getMessage("git.summary.methods.suffix") + "\n")
        }

        return summary.toString()
    }

    /**
     * 格式化文件分析结果
     */
    private fun formatFileAnalysis(analysis: FileChangeAnalysis): String {
        val sb = StringBuilder()

        sb.append("${analysis.changeType.name}: ${analysis.filePath} (${analysis.fileType})\n")

        when (analysis.changeType) {
            ChangeType.ADDED -> {
                sb.append(
                    I18nManager.getMessage(
                        "git.detail.added.lines",
                        analysis.afterLines,
                        analysis.addedLines
                    ) + "\n"
                )
                if (analysis.addedMethods.isNotEmpty()) {
                    sb.append(
                        I18nManager.getMessage(
                            "git.detail.added.methods",
                            analysis.addedMethods.joinToString(", ")
                        ) + "\n"
                    )
                }
                if (analysis.addedClasses.isNotEmpty()) {
                    sb.append(
                        I18nManager.getMessage(
                            "git.detail.added.classes",
                            analysis.addedClasses.joinToString(", ")
                        ) + "\n"
                    )
                }
                if (analysis.significantChanges.isNotEmpty()) {
                    sb.append(
                        I18nManager.getMessage(
                            "git.detail.features",
                            analysis.significantChanges.joinToString(", ")
                        ) + "\n"
                    )
                }
                // 添加关键内容样本
                if (analysis.contentSample.isNotEmpty()) {
                    sb.append(I18nManager.getMessage("git.detail.key.content") + "\n")
                    sb.append(formatCodeSample(analysis.contentSample))
                }
            }

            ChangeType.DELETED -> {
                sb.append(I18nManager.getMessage("git.detail.deleted.lines", analysis.beforeLines) + "\n")
                if (analysis.deletedMethods.isNotEmpty()) {
                    sb.append(
                        I18nManager.getMessage(
                            "git.detail.deleted.methods",
                            analysis.deletedMethods.joinToString(", ")
                        ) + "\n"
                    )
                }
                if (analysis.deletedClasses.isNotEmpty()) {
                    sb.append(
                        I18nManager.getMessage(
                            "git.detail.deleted.classes",
                            analysis.deletedClasses.joinToString(", ")
                        ) + "\n"
                    )
                }
            }

            ChangeType.MODIFIED -> {
                sb.append(
                    I18nManager.getMessage(
                        "git.detail.line.count",
                        analysis.beforeLines,
                        analysis.afterLines
                    ) + "\n"
                )
                sb.append(
                    I18nManager.getMessage(
                        "git.detail.changes",
                        analysis.addedLines,
                        analysis.deletedLines,
                        analysis.modifiedLines
                    ) + "\n"
                )

                if (analysis.addedMethods.isNotEmpty()) {
                    sb.append(
                        I18nManager.getMessage(
                            "git.detail.added.methods",
                            analysis.addedMethods.joinToString(", ")
                        ) + "\n"
                    )
                }
                if (analysis.deletedMethods.isNotEmpty()) {
                    sb.append(
                        I18nManager.getMessage(
                            "git.detail.deleted.methods",
                            analysis.deletedMethods.joinToString(", ")
                        ) + "\n"
                    )
                }
                if (analysis.modifiedMethods.isNotEmpty()) {
                    sb.append(
                        I18nManager.getMessage(
                            "git.detail.modified.methods",
                            analysis.modifiedMethods.joinToString(", ")
                        ) + "\n"
                    )
                }
                if (analysis.addedImports.isNotEmpty()) {
                    sb.append(
                        I18nManager.getMessage(
                            "git.detail.added.imports",
                            analysis.addedImports.take(5).joinToString(", ")
                        ) + "\n"
                    )
                }
                if (analysis.significantChanges.isNotEmpty()) {
                    sb.append(
                        I18nManager.getMessage(
                            "git.detail.important.changes",
                            analysis.significantChanges.joinToString(", ")
                        ) + "\n"
                    )
                }

                // 添加详细的diff信息
                if (analysis.contentSample.isNotEmpty()) {
                    sb.append(I18nManager.getMessage("git.detail.main.diff") + "\n")
                    sb.append(formatCodeSample(analysis.contentSample))
                }
            }
        }

        return sb.toString()
    }

    /**
     * 生成详细的diff信息
     */
    private fun generateDetailedDiff(beforeContent: String, afterContent: String, filePath: String): String {
        val beforeLines = beforeContent.lines()
        val afterLines = afterContent.lines()

        val diff = StringBuilder()
        val maxLines = maxOf(beforeLines.size, afterLines.size)
        var diffCount = 0
        val maxDiffLines = 50 // 限制diff行数
        var contextLines = 0
        val maxContextLines = 3 // 最多显示3行上下文

        for (i in 0 until maxLines) {
            if (diffCount >= maxDiffLines) {
                diff.append("\n    " + I18nManager.getMessage("git.diff.more.changes") + "\n")
                break
            }

            val beforeLine = beforeLines.getOrNull(i) ?: ""
            val afterLine = afterLines.getOrNull(i) ?: ""
            val beforeTrimmed = beforeLine.trim()
            val afterTrimmed = afterLine.trim()

            when {
                beforeTrimmed != afterTrimmed && beforeTrimmed.isNotEmpty() && afterTrimmed.isNotEmpty() -> {
                    // 修改的行
                    addContextIfNeeded(diff, beforeLines, i, contextLines, maxContextLines)
                    diff.append("    -${i + 1}: $beforeLine\n")
                    diff.append("    +${i + 1}: $afterLine\n")
                    diffCount += 2
                    contextLines = 0
                }

                beforeTrimmed.isNotEmpty() && afterTrimmed.isEmpty() -> {
                    // 删除的行
                    addContextIfNeeded(diff, beforeLines, i, contextLines, maxContextLines)
                    diff.append("    -${i + 1}: $beforeLine\n")
                    diffCount++
                    contextLines = 0
                }

                beforeTrimmed.isEmpty() && afterTrimmed.isNotEmpty() -> {
                    // 新增的行
                    addContextIfNeeded(diff, afterLines, i, contextLines, maxContextLines)
                    diff.append("    +${i + 1}: $afterLine\n")
                    diffCount++
                    contextLines = 0
                }

                else -> {
                    // 未变更的行，作为上下文
                    contextLines++
                }
            }
        }

        return diff.toString()
    }

    /**
     * 添加上下文行
     */
    private fun addContextIfNeeded(
        diff: StringBuilder,
        lines: List<String>,
        currentIndex: Int,
        contextLines: Int,
        maxContextLines: Int
    ) {
        if (contextLines > 0 && contextLines <= maxContextLines) {
            val contextStart = maxOf(0, currentIndex - contextLines)
            for (j in contextStart until currentIndex) {
                val line = lines.getOrNull(j) ?: ""
                if (line.trim().isNotEmpty()) {
                    diff.append("     ${j + 1}: $line\n")
                }
            }
        } else if (contextLines > maxContextLines) {
            diff.append("    " + I18nManager.getMessage("git.diff.context.omit") + "\n")
        }
    }

    /**
     * 格式化代码样本
     */
    private fun formatCodeSample(content: String): String {
        val lines = content.lines()
        val formattedLines = lines.take(20).mapIndexed { index, line ->
            "    ${index + 1}: $line"
        }

        val result = formattedLines.joinToString("\n")
        return if (lines.size > 20) {
            "$result\n    " + I18nManager.getMessage("git.diff.content.omit") + "\n"
        } else {
            "$result\n"
        }
    }

    /**
     * 获取版本内容
     */
    private fun getRevisionContent(revision: ContentRevision): String {
        return try {
            revision.content ?: ""
        } catch (e: Exception) {
            println(I18nManager.getMessage("git.content.failed", e.message ?: ""))
            ""
        }
    }

    /**
     * 清理提交注释
     */
    private fun cleanCommitMessage(rawMessage: String): String {
        if (rawMessage.isBlank()) {
            return I18nManager.getMessage("git.default.commit")
        }

        return rawMessage
            .trim()
            // 移除AI思考标签
            .replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "")
            // 移除代码块符号
            .replace(Regex("```[a-zA-Z]*\\n?"), "")
            .replace("```", "")
            // 移除"解释："等说明性文本
            .replace(Regex("解释[：:].{0,200}"), "")
            .replace(Regex("说明[：:].{0,200}"), "")
            .replace(Regex("这条提交信息.{0,200}"), "")
            .replace(Regex("^Explanation[：:].{0,200}", RegexOption.MULTILINE), "")
            .replace(Regex("^This commit.{0,200}", RegexOption.MULTILINE), "")
            // 移除多余的空白行
            .replace(Regex("\n{3,}"), "\n\n")
            // 移除行首行尾空格
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .trim()
    }

    companion object {
        @Volatile
        private var INSTANCE: GitCommitMessageGenerator? = null

        fun getInstance(): GitCommitMessageGenerator {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: GitCommitMessageGenerator().also { INSTANCE = it }
            }
        }
    }
} 