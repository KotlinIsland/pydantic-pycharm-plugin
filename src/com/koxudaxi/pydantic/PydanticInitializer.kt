package com.koxudaxi.pydantic

import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.NoAccessDuringPsiEvents
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.AsyncFileListener
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.events.*
import com.intellij.psi.util.QualifiedName
import com.intellij.serviceContainer.AlreadyDisposedException
import com.jetbrains.python.psi.PyQualifiedNameOwner
import com.jetbrains.python.psi.types.TypeEvalContext
import org.apache.tuweni.toml.Toml
import org.apache.tuweni.toml.TomlArray
import org.apache.tuweni.toml.TomlParseResult
import org.apache.tuweni.toml.TomlTable
import org.ini4j.Ini
import org.ini4j.IniPreferences
import java.io.File

class PydanticInitializer : ProjectActivity {
    private fun getDefaultPyProjectTomlPath(project: Project): String {
        return project.basePath + File.separator + "pyproject.toml"
    }

    private fun getDefaultMypyIniPath(project: Project): String {
        return project.basePath + File.separator + "mypy.ini"
    }

    fun initializeFileLoader(project: Project?) {
        if (project == null) return
        val configService = PydanticConfigService.getInstance(project)
        val defaultPyProjectToml = getDefaultPyProjectTomlPath(project)
        val defaultMypyIni = getDefaultMypyIniPath(project)

        invokeAfterPsiEvents {
            LocalFileSystem.getInstance()
                .findFileByPath(configService.pyprojectToml ?: defaultPyProjectToml)
                ?.also { loadPyprojectToml(project, it, configService) }
                ?: run { clearPyProjectTomlConfig(configService) }
            LocalFileSystem.getInstance()
                .findFileByPath(configService.mypyIni ?: defaultMypyIni)
                ?.also { loadMypyIni(it, configService) }
                ?: run { clearMypyIniConfig(configService) }
        }

        VirtualFileManager.getInstance().addAsyncFileListener(
            { events ->
                object : AsyncFileListener.ChangeApplier {
                    override fun afterVfsChange() {
                        if (project.isDisposed) return
                        try {
                            val projectFiles = events
                                .asSequence()
                                .filter {
                                    it is VFileContentChangeEvent || it is VFileMoveEvent || it is VFileCopyEvent || it is VFileCreateEvent || it is VFileDeleteEvent
                                }.mapNotNull { it.file }
                                .filter {
                                    try {
                                        ProjectFileIndex.getInstance(project).isInContent(it)
                                    } catch (e:Exception) {
                                        false
                                    }
                                }
                            if (projectFiles.count() == 0) return
                            val pyprojectToml = configService.pyprojectToml ?: defaultPyProjectToml
                            val mypyIni = configService.mypyIni ?: defaultMypyIni
                            invokeAfterPsiEvents {
                                projectFiles
                                    .forEach {
                                        when (it.path) {
                                            pyprojectToml -> loadPyprojectToml(project, it, configService)
                                            mypyIni -> loadMypyIni(it, configService)
                                        }
                                    }
                            }

                        } catch (_: AlreadyDisposedException) {
                        }
                    }
                }
            },
            {}
        )
    }

    private fun clearMypyIniConfig(configService: PydanticConfigService) {
        configService.mypyInitTyped = null
        configService.mypyWarnUntypedFields = null
    }

    private fun clearPyProjectTomlConfig(configService: PydanticConfigService) {
        configService.parsableTypeMap = emptyMap()
        configService.acceptableTypeMap = emptyMap()
        configService.parsableTypeHighlightType = ProblemHighlightType.WARNING
        configService.acceptableTypeHighlightType = ProblemHighlightType.WEAK_WARNING
    }

    private fun fromIniBoolean(text: String?): Boolean? {
        return when (text) {
            "True" -> true
            "False" -> false
            else -> null
        }
    }

    private fun loadMypyIni(config: VirtualFile, configService: PydanticConfigService) {
        try {
            val ini = Ini(config.inputStream)
            val prefs = IniPreferences(ini)
            val pydanticMypy = prefs.node("pydantic-mypy")
            configService.mypyInitTyped = fromIniBoolean(pydanticMypy["init_typed", null])
            configService.mypyWarnUntypedFields = fromIniBoolean(pydanticMypy["warn_untyped_fields", null])
        } catch (t: Throwable) {
            clearMypyIniConfig(configService)
        }
    }

    private fun loadPyprojectToml(project: Project, config: VirtualFile, configService: PydanticConfigService) {
        val result: TomlParseResult = Toml.parse(config.inputStream)

        val table = result.getTableOrEmpty("tool.pydantic-pycharm-plugin")
        if (table.isEmpty) {
            clearPyProjectTomlConfig(configService)
            return
        }

        TypeEvalContext.codeAnalysis(project, null).let {
            configService.parsableTypeMap = getTypeMap(project, "parsable-types", table, it)
            configService.acceptableTypeMap = getTypeMap(project, "acceptable-types", table, it)
        }

        configService.parsableTypeHighlightType =
            getHighlightLevel(table, "parsable-type-highlight", ProblemHighlightType.WARNING)
        configService.acceptableTypeHighlightType =
            getHighlightLevel(table, "acceptable-type-highlight", ProblemHighlightType.WEAK_WARNING)

        configService.ignoreInitMethodArguments = table.getBoolean("ignore-init-method-arguments") ?: false
    }

    private fun getHighlightLevel(table: TomlTable, path: String, default: ProblemHighlightType): ProblemHighlightType {
        return when (table.get(path) as? String) {
            "warning" -> ProblemHighlightType.WARNING
            "weak_warning" -> ProblemHighlightType.WEAK_WARNING
            "disable" -> ProblemHighlightType.INFORMATION
            else -> default
        }
    }

    private fun getTypeMap(
        project: Project,
        path: String,
        table: TomlTable,
        context: TypeEvalContext,
    ): Map<String, List<String>> {
        return table.getTableOrEmpty(path).toMap().mapNotNull { entry ->
            getPsiElementByQualifiedName(QualifiedName.fromDottedString(entry.key), project, context)
                .let { psiElement -> (psiElement as? PyQualifiedNameOwner)?.qualifiedName ?: entry.key }
                .let { name ->
                    (entry.value as? TomlArray)
                        ?.toList()
                        ?.filterIsInstance<String>()
                        .takeIf { it?.isNotEmpty() == true }
                        ?.let { name to it }
                }
        }.toMap()
    }

    override suspend fun execute(project: Project) {
        if (ApplicationManager.getApplication().isUnitTestMode) return
        if (project.isDisposed) return
        DumbService.getInstance(project).smartInvokeLater {
            try {
                initializeFileLoader(project)
            } catch (_: AlreadyDisposedException) {
            }
        }
    }

    private fun invokeAfterPsiEvents(runnable: () -> Unit) {
        val wrapper = {
            when {
                NoAccessDuringPsiEvents.isInsideEventProcessing() -> invokeAfterPsiEvents(runnable)
                else -> runnable()
            }
        }
        ApplicationManager.getApplication().invokeLater(wrapper) { false }
    }
}
