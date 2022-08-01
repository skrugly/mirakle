import com.googlecode.streamflyer.core.ModifyingWriter
import com.googlecode.streamflyer.regex.fast.FastRegexModifier
import com.instamotor.mirakle.BuildConfig
import org.apache.commons.io.output.WriterOutputStream
import org.gradle.StartParameter
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Task
import org.gradle.api.artifacts.verification.DependencyVerificationMode
import org.gradle.api.execution.TaskExecutionListener
import org.gradle.api.invocation.Gradle
import org.gradle.api.logging.LogLevel
import org.gradle.api.logging.configuration.ConsoleOutput
import org.gradle.api.logging.configuration.ShowStacktrace
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskState
import org.gradle.process.internal.ExecAction
import org.gradle.process.internal.ExecActionFactory
import org.gradle.process.internal.ExecException
import org.gradle.tooling.GradleConnector
import org.gradle.workers.IsolationMode
import org.gradle.workers.WorkerExecutor
import java.io.File
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.file.Files
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern
import javax.inject.Inject

open class Mirakle : Plugin<Gradle> {
    override fun apply(gradle: Gradle) {
        gradle.rootProject { it.extensions.create("mirakle", MirakleExtension::class.java) }

        if (gradle.startParameter.taskNames.isEmpty()) return
        if (gradle.startParameter.projectProperties.containsKey(BUILD_ON_REMOTE)) return
        if (gradle.startParameter.projectProperties.containsKey(FALLBACK)) return
        if (gradle.startParameter.excludedTaskNames.remove("mirakle")) return
        if (gradle.startParameter.isDryRun) return

        val gradlewRoot = findGradlewRoot(gradle.startParameter.currentDir)
            ?: throw MirakleException("gradlew executable file is not found.")

        if (loadProperties(File(gradlewRoot, "local.properties"))["mirakle.enabled"] == "false") return

        gradle.assertNonSupportedFeatures()

        val startTime = System.currentTimeMillis()

        val startParamsCopy = gradle.startParameter.copy()
        val breakMode = startParamsCopy.projectProperties.let {
            (it[BREAK_MODE]?.toBoolean() ?: false) || (it[BREAK_TASK]?.isNotBlank() ?: false)
        }

        gradle.startParameter.apply {
            currentDir = gradlewRoot
            projectDir = gradlewRoot

            if (breakMode) {
                // pass all the tasks alongside with "mirakle" to let Gradle calculate taskGraph
                setTaskNames(listOf("mirakle") + taskNames)
            } else {
                setTaskNames(listOf("mirakle"))
                setExcludedTaskNames(emptyList())
            }

            if (!breakMode) {
                //a way to make Gradle not evaluate build.gradle, settings.gradle and dependency catalog files on local machine

                val settingsFile = gradlewRoot.listFiles()?.firstOrNull { it.name.startsWith("settings.gradle") }
                val settingsBackup = settingsFile?.let { File(gradlewRoot, "backup_${it.name}") }

                val buildFile = gradlewRoot.listFiles()?.firstOrNull { it.name.startsWith("build.gradle") }
                val buildFileBackup = buildFile?.let { File(gradlewRoot, "backup_${it.name}") }

                val versionCatalog = File(gradlewRoot, "gradle").listFiles()?.filter {
                    it.name.endsWith("versions.toml")
                }

                settingsFile?.renameTo(settingsBackup)
                buildFile?.renameTo(buildFileBackup)

                val emptySettings = settingsFile?.let {
                    // it makes Gradle stop looking for a settings.gradle file in parent dir
                    File(it.parentFile, "settings.gradle").apply { createNewFile() }
                }

                val versionCatalogBackup = versionCatalog?.map {
                    val backup = File(it.parent, "${it.name}_backup")
                    it.renameTo(backup)
                    backup
                }

                gradle.afterMirakleEvaluate {
                    emptySettings?.delete()

                    settingsFile?.let {
                        settingsBackup?.renameTo(it)
                    }

                    buildFile?.let {
                        buildFileBackup?.renameTo(it)
                    }

                    versionCatalogBackup?.zip(versionCatalog)?.forEach { (backup, original) ->
                        backup.renameTo(original)
                    }
                }
            }

            // mirakle.gradle is the only Gradle build file which is evaluated on local machine.
            if (File(gradlewRoot, "mirakle.gradle").exists()) {
                gradle.rootProject { project ->
                    project.apply(mutableMapOf("from" to "mirakle.gradle"))
                }
            }

            // disable build scan on local machine, but it will be enabled on remote if flag is set
            isBuildScan = false
        }

        gradle.rootProject { project ->
            project.afterEvaluate {
                val config = kotlin.run {
                    val mirakleConfig = project.extensions.getByType(MirakleExtension::class.java)

                    getMainframerConfigOrNull(gradlewRoot, mirakleConfig)?.also {
                        println("Mainframer config is applied, Mirakle config is ignored.")
                    } ?: mirakleConfig
                }

                if (config.host == null) throw MirakleException("Mirakle host is not defined.")

                println("Here's Mirakle ${BuildConfig.VERSION}. All tasks will be executed on ${config.host}.")

                val breakTaskFromCLI = startParamsCopy.projectProperties[BREAK_TASK]
                val breakOnTasks = breakTaskFromCLI?.takeIf(String::isNotBlank)?.let(::listOf) ?: config.breakOnTasks

                val upload = project.task<Exec>("uploadToRemote") {
                    setCommandLine("rsync")
                    args(
                        fixPathForWindows(gradlewRoot.toString()),
                        "${config.host}:${config.remoteFolder}",
                        "--rsh",
                        "ssh ${config.sshArgs.joinToString(separator = " ")}",
                        "--exclude=mirakle.gradle"
                    )
                    args(config.buildRsyncToRemoteArgs())
                }

                val execute = project.task<ExecuteOnRemoteTask>("executeOnRemote") {
                    this.config = config
                    this.gradlewRoot = gradlewRoot
                    this.startParams = startParamsCopy

                    if (!breakMode || breakOnTasks.isEmpty()) {
                        setTaskList(startParamsCopy.taskNames)
                    } // else in break mode tasks arguments will be set later when Gradle::taskGraph is ready

                    isIgnoreExitValue = true

                    // disable remote stream modification since it breaks Idea and Android Studio test result processing
                    // https://github.com/Adambl4/mirakle/issues/83
                    if (!gradle.containsIjTestInit()) {
                        standardOutput = modifyOutputStream(
                            standardOutput ?: System.out,
                            "${config.remoteFolder}/${gradlewRoot.name}",
                            gradlewRoot.path
                        )
                        errorOutput = modifyOutputStream(
                            errorOutput ?: System.err,
                            "${config.remoteFolder}/${gradlewRoot.name}",
                            gradlewRoot.path
                        )
                    }
                }.mustRunAfter(upload) as ExecuteOnRemoteTask

                val download = project.task<Exec>("downloadFromRemote") {
                    setCommandLine("rsync")
                    args(
                        "${config.host}:${config.remoteFolder}/${gradlewRoot.name}/",
                        "./",
                        "--rsh",
                        "ssh ${config.sshArgs.joinToString(separator = " ")}",
                        "--exclude=mirakle.gradle"
                    )
                    args(config.buildRsyncFromRemoteArgs())
                }.mustRunAfter(execute) as Exec

                if (config.downloadInParallel) {
                    if (config.downloadInterval <= 0) throw MirakleException("downloadInterval must be >0")

                    val downloadInParallel = project.task<Task>("downloadInParallel") {
                        doFirst {
                            val downloadExecAction = services.get(ExecActionFactory::class.java).newExecAction().apply {
                                commandLine = download.commandLine
                                args = download.args
                                standardOutput = download.standardOutput ?: System.out
                                standardInput = download.standardInput ?: System.`in`
                            }

                            //It's impossible to pass this as serializable params to worker
                            //God forgive us
                            DownloadInParallelWorker.downloadExecAction = downloadExecAction
                            DownloadInParallelWorker.gradle = gradle

                            services.get(WorkerExecutor::class.java).submit(DownloadInParallelWorker::class.java) {
                                it.isolationMode = IsolationMode.NONE
                                it.setParams(config.downloadInterval)
                            }
                        }

                        onlyIf {
                            config.downloadInParallel && upload.executionResult.get().exitValue == 0 && !execute.didWork
                        }
                    }

                    downloadInParallel.mustRunAfter(upload)
                    download.mustRunAfter(downloadInParallel)

                    gradle.startParameter.setTaskNames(listOf("downloadInParallel", "mirakle"))
                }

                val mirakle = project.task("mirakle").dependsOn(upload, execute, download)

                try {
                    mirakle.doNotTrackState("Mirakle is never up-to-date")
                } catch (ignore: NoSuchMethodError) {
                    // Gradle <7.3
                }

                execute.onlyIf { upload.executionResult.orNull?.exitValue == 0 }
                download.onlyIf { upload.executionResult.orNull?.exitValue == 0 }

                if (!config.fallback) {
                    mirakle.doLast {
                        execute.executionResult.get().assertNormalExitValue()
                    }
                } else {
                    val fallback = project.task<DefaultTask>("fallback") {
                        onlyIf { upload.executionResult.get().exitValue != 0 }

                        doFirst {
                            println("Upload to remote failed. Continuing with fallback.")

                            val connection = GradleConnector.newConnector()
                                .forProjectDirectory(gradle.rootProject.projectDir)
                                .connect()

                            connection.use { connection ->
                                connection.newBuild()
                                    .withArguments(startParamsToArgs(startParamsCopy).plus("-P$FALLBACK=true"))
                                    .setStandardInput(upload.standardInput ?: System.`in`)
                                    .setStandardOutput(upload.standardOutput ?: System.out)
                                    .setStandardError(upload.errorOutput ?: System.err)
                                    .run()
                            }
                        }
                    }

                    upload.isIgnoreExitValue = true
                    upload.finalizedBy(fallback)

                    mirakle.doLast {
                        if (execute.didWork) {
                            execute.executionResult.get().assertNormalExitValue()
                        }
                    }
                }

                if (breakMode && breakOnTasks.isNotEmpty()) {
                    if (config.downloadInParallel) {
                        throw MirakleException("Mirakle break mode doesn't work with download in parallel yet.")
                    }

                    gradle.taskGraph.whenReady { taskGraph ->
                        val breakTaskPatterns = breakOnTasks.filter(String::isNotBlank).map(Pattern::compile)

                        val graphWithoutMirakle = taskGraph.allTasks.filterNot(Task::isMirakleTask)

                        val breakTasks = graphWithoutMirakle.filter { task ->
                            breakTaskPatterns.any { it.matcher(task.name).find() }
                        }

                        when {
                            breakTasks.isEmpty() -> {
                                execute.setTaskList(startParamsCopy.taskNames.minus("mirakle"))
                                graphWithoutMirakle.forEach {
                                    it.enabled = false
                                }
                            }

                            breakTasks.size == 1 -> {
                                val breakTask = breakTasks.first()

                                val tasksForRemoteExecution = graphWithoutMirakle
                                    .takeWhile { it != breakTask }
                                    .onEach { it.enabled = false }

                                execute.setTaskList(tasksForRemoteExecution.map(Task::getPath))

                                breakTask.apply {
                                    enabled = true
                                    mustRunAfter(download)
                                    onlyIf { execute.didWork && execute.executionResult.get().exitValue == 0 }

                                    if (inputs.files.files.isNotEmpty()) {
                                        val inputsNotInProjectDir = inputs.files.files.filter {
                                            !it.path.startsWith(gradlewRoot.path)
                                        }

                                        if (inputsNotInProjectDir.isNotEmpty()) {
                                            throw MirakleException(
                                                "${breakTask.toString().capitalize()} declares input not from project dir. That is not supported by Mirakle yet. Tasks:\n${inputsNotInProjectDir.joinToString { it.path }}"
                                            )
                                        }

                                        // Need to include all of the parent directories down to the desired directory
                                        val includeRules = inputs.files.files.map { file ->
                                            val pathToInclude = file.path.substringAfter(gradlewRoot.path).drop(1)
                                            val split = pathToInclude.split("/")

                                            (1 until split.size).mapIndexed { _, i ->
                                                split.take(i).joinToString(separator = "/")
                                            }.map {
                                                "--include=$it"
                                            }.let {
                                                val isFile = split.last().contains('.')
                                                if (isFile) {
                                                    it.plus("--include=$pathToInclude")
                                                } else {
                                                    it.plus("--include=$pathToInclude/***")
                                                }
                                            }
                                        }.flatten().toSet()

                                        //val excludeAllRule = "--exclude=/**" // TODO does it make sense to exclude all except the inputs?

                                        download.setArgs(includeRules /*+ excludeAllRule */ + download.args!!.toList())
                                    }
                                }

                                execute.doFirst {
                                    println("Mirakle will break remote execution on $breakTask")
                                }

                                mirakle.dependsOn(breakTask)
                                gradle.logTasks(graphWithoutMirakle - tasksForRemoteExecution)
                            }

                            else -> {
                                throw MirakleException("Task execution graph contains more than 1 task to break on. That is not supported by Mirakle yet. Tasks:\n${breakTasks.joinToString { it.path }}")
                            }
                        }
                    }
                } else {
                    gradle.startParameter.apply {
                        if (taskNames.contains("downloadInParallel")) {
                            setTaskNames(listOf("downloadInParallel", "mirakle"))
                        } else {
                            setTaskNames(listOf("mirakle"))
                        }
                        setExcludedTaskNames(emptyList())
                    }
                }

                gradle.uploadInitScripts(upload, execute, download)

                gradle.logTasks(upload, execute, download)
                gradle.logBuild(startTime, mirakle)
            }
        }
    }
}

open class ExecuteOnRemoteTask : Exec() {
    @Internal lateinit var config: MirakleExtension
    @Internal lateinit var gradlewRoot: File
    @Internal lateinit var startParams: StartParameter
    private val tasksList = mutableListOf<String>()
    private val customArgs = mutableListOf<String>()

    override fun exec() {
        val startParamsArgs = startParams.copy()
            .apply { setTaskNames(tasksList) }
            .let { mergeStartParamsWithProperties(it, gradlewRoot) }
            .let(::startParamsToArgs)

        val taskArgs = startParamsArgs.plus(customArgs).joinToString(separator = " ") { "\"$it\"" }
        val remoteFolder = "${config.remoteFolder}/${gradlewRoot.name}"
        val additionalCommand = config.remoteBashCommand?.ifBlank { null }?.let { "&& $it" } ?: ""
        val remoteGradleCommand = "./gradlew -P$BUILD_ON_REMOTE=true $taskArgs"
        val remoteBashCommand = "echo 'set -e $additionalCommand && cd \"$remoteFolder\" && $remoteGradleCommand' | bash"

        setCommandLine(config.sshClient)
        args(config.sshArgs)
        args(config.host)
        args(remoteBashCommand)

        super.exec()
    }

    internal fun setTaskList(tasks: List<String>) {
        tasksList.clear()
        tasksList.addAll(tasks)
    }

    internal fun addCustomArgs(list: List<String>) {
        customArgs.addAll(list)
    }
}

class MirakleBreakMode : Mirakle() {
    override fun apply(gradle: Gradle) {
        gradle.startParameter.apply {
            if (!projectProperties.containsKey(BREAK_MODE)) {
                projectProperties = projectProperties.plus(BREAK_MODE to "true")
            }
        }
        super.apply(gradle)
    }
}

open class MirakleExtension {
    var host: String? = null

    var remoteFolder: String = "~/mirakle"

    var excludeLocal = setOf(
        "**/build"
    )

    var excludeRemote = setOf(
        "**/src/"
    )

    var excludeCommon = setOf(
        ".gradle",
        ".idea",
        "**/.git/",
        "**/local.properties",
        "**/mirakle.properties",
        "**/mirakle_local.properties"
    )

    var rsyncToRemoteArgs = setOf(
        "--archive",
        "--delete"
    )

    var rsyncFromRemoteArgs = setOf(
        "--archive",
        "--delete"
    )

    var sshArgs = emptySet<String>()

    var sshClient = "ssh"

    var fallback = false

    var downloadInParallel = false
    var downloadInterval = 2000L

    var breakOnTasks = emptySet<String>()

    var remoteBashCommand: String? = null

    internal fun buildRsyncToRemoteArgs(): Set<String> =
        rsyncToRemoteArgs + excludeLocal.mapToRsyncExcludeArgs()

    internal fun buildRsyncFromRemoteArgs(): Set<String> =
        rsyncFromRemoteArgs + excludeRemote.mapToRsyncExcludeArgs()

    private fun Set<String>.mapToRsyncExcludeArgs(): Set<String> =
        this.plus(excludeCommon).map { "--exclude=$it" }.toSet()
}

fun startParamsToArgs(params: StartParameter) = with(params) {
    emptyList<String>()
        .plus(taskNames.minus("mirakle"))
        .plus(excludedTaskNames.map { "--exclude-task \"$it\"" })
        .plus(booleanParamsToOption.map { (param, option) -> if (param(this)) option else null })
        .plus(negativeBooleanParamsToOption.map { (param, option) -> if (!param(this)) option else null })
        .plus(projectProperties.minus(excludedProjectProperties).flatMap { (key, value) -> listOf("--project-prop", "$key=$value") })
        .plus(systemPropertiesArgs.flatMap { (key, value) -> listOf("--system-prop", "$key=$value") })
        .plus(logLevelToOption.firstOrNull { (level, _) -> logLevel == level }?.second)
        .plus(showStacktraceToOption.firstOrNull { (show, _) -> showStacktrace == show }?.second)
        .plus(consoleOutputToOption.firstOrNull { (console, _) -> consoleOutput == console }?.second ?: emptyList())
        .plus(verificationModeToOption.firstOrNull { (verificationMode, _) -> dependencyVerificationMode == verificationMode }?.second ?: emptyList())
        .plus(writeDependencyVerifications.joinToString(",").ifBlank { null }?.let { listOf(writeDependencyVerificationParam, it) } ?: emptyList())
        .filterNotNull()
}

val booleanParamsToOption = listOf(
    StartParameter::isProfile to "--profile",
    StartParameter::isRerunTasks to "--rerun-tasks",
    StartParameter::isRefreshDependencies to "--refresh-dependencies",
    StartParameter::isContinueOnFailure to "--continue",
    StartParameter::isOffline to "--offline",
    StartParameter::isParallelProjectExecutionEnabled to "--parallel",
    StartParameter::isConfigureOnDemand to "--configure-on-demand",
    StartParameter::isBuildScan to "--scan",
    StartParameter::isNoBuildScan to "--no-scan"
)

val negativeBooleanParamsToOption = listOf(
    StartParameter::isBuildProjectDependencies to "--no-rebuild"
)

val excludedProjectProperties = listOf(
    "android.injected.attribution.file.location" // disable Android Studio Build Analyzer collection on remote machine
)

val logLevelToOption = listOf(
    LogLevel.DEBUG to "--debug",
    LogLevel.INFO to "--info",
    LogLevel.WARN to "--warn",
    LogLevel.QUIET to "--quiet"
)

val showStacktraceToOption = listOf(
    ShowStacktrace.ALWAYS to "--stacktrace",
    ShowStacktrace.ALWAYS_FULL to "--full-stacktrace"
)

val consoleOutputToOption = listOf(
    //ConsoleOutput.Auto to "--console auto", //default, no need to pass
    ConsoleOutput.Plain to listOf("--console", "plain"),
    ConsoleOutput.Rich to listOf("--console", "rich")
)

// related to gradle dependency verification
// https://docs.gradle.org/current/userguide/dependency_verification.html
val verificationModeToOption = listOf(
    //DependencyVerificationMode.STRICT to "--dependency-verification strict", //default, no need to pass
    DependencyVerificationMode.LENIENT to listOf("--dependency-verification", "lenient"),
    DependencyVerificationMode.OFF to listOf("--dependency-verification", "off")
)

const val writeDependencyVerificationParam = "--write-verification-metadata"

//since build occurs on a remote machine, console output will contain remote directories
//to let IDE and other tools properly parse the output, mirakle need to replace remote dir by local one
fun modifyOutputStream(target: OutputStream, remoteDir: String, localDir: String): OutputStream {
    val modifier = FastRegexModifier("\\/.*?\\/${remoteDir.replace("~/", "")}", 0, localDir, 0, 1)
    val modifyingWriter = ModifyingWriter(OutputStreamWriter(target), modifier)
    return WriterOutputStream(modifyingWriter)
}

fun getMainframerConfigOrNull(projectDir: File, mirakleConfig: MirakleExtension): MirakleExtension? {
    val mainframerDir = File(projectDir, ".mainframer")
    return mainframerDir.takeIf(File::exists)?.let {
        MirakleExtension().apply {
            val config = mainframerDir.listFiles().firstOrNull { it.name == "config" }
            val commonIgnore = mainframerDir.listFiles().firstOrNull { it.name == "ignore" }
            val localIgnore = mainframerDir.listFiles().firstOrNull { it.name == "localignore" }
            val remoteIgnore = mainframerDir.listFiles().firstOrNull { it.name == "remoteignore" }

            if (config == null) throw MirakleException("Mainframer folder is detected but config is missed.")

            remoteFolder = "~/mainframer"
            excludeCommon = emptySet()
            excludeLocal = emptySet()
            excludeRemote = emptySet()

            Properties().apply {
                load(config.inputStream())

                host = getProperty("remote_machine")
                rsyncToRemoteArgs += "--compress-level=${getProperty("local_compression_level") ?: "1"}"
                rsyncFromRemoteArgs += "--compress-level=${getProperty("remote_compression_level") ?: "1"}"
            }

            if (commonIgnore != null && commonIgnore.exists()) {
                rsyncToRemoteArgs += "--exclude-from=${commonIgnore.path}"
                rsyncFromRemoteArgs += "--exclude-from=${commonIgnore.path}"
            }

            if (localIgnore != null && localIgnore.exists()) {
                rsyncToRemoteArgs += "--exclude-from=${localIgnore.path}"
            }

            if (remoteIgnore != null && remoteIgnore.exists()) {
                rsyncFromRemoteArgs += "--exclude-from=${remoteIgnore.path}"
            }

            // Mainframer doesn't have these config params, use Mirakle's parameters instead
            fallback = mirakleConfig.fallback
            downloadInParallel = mirakleConfig.downloadInParallel
            downloadInterval = mirakleConfig.downloadInterval
            sshArgs = mirakleConfig.sshArgs
            sshClient = mirakleConfig.sshClient
            breakOnTasks = mirakleConfig.breakOnTasks
        }
    }
}

const val BUILD_ON_REMOTE = "mirakle.build.on.remote"
const val FALLBACK = "mirakle.build.fallback"
const val BREAK_MODE = "mirakle.break.mode"
const val BREAK_TASK = "mirakle.break.task"

fun Gradle.uploadInitScripts(upload: Exec, execute: ExecuteOnRemoteTask, download: Exec) {
    if (startParameter.initScripts.isEmpty()) return

    val initScriptsFolder = File(gradle.rootProject.rootDir, "mirakle_init_scripts")
    initScriptsFolder.mkdirs()

    startParameter.initScripts.forEach { script ->
        val initScriptCopy = File(initScriptsFolder, script.name)

        if (initScriptCopy.exists()) initScriptCopy.delete()

        upload.doFirst {
            Files.copy(script.toPath(), initScriptCopy.toPath())
        }

        execute.doFirst {
            execute.addCustomArgs(listOf("--init-script", "${initScriptsFolder.name}/${initScriptCopy.name}"))
        }
    }

    gradle.taskGraph.afterTask { task ->
        if (task == upload) {
            if (initScriptsFolder.exists()) {
                initScriptsFolder.deleteRecursively()
            }
        }
    }

    download.doFirst {
        download.args("--exclude=**/${initScriptsFolder.name}/")
    }
}

class DownloadInParallelWorker @Inject constructor(val downloadInterval: Long) : Runnable {
    override fun run() {
        val mustInterrupt = AtomicBoolean()

        gradle.addListener(object : TaskExecutionListener {
            override fun afterExecute(task: Task, state: TaskState?) {
                if (task.name == "executeOnRemote") {
                    gradle.removeListener(this)
                    mustInterrupt.set(true)
                }
            }

            override fun beforeExecute(task: Task) {}
        })

        while (!mustInterrupt.get()) {
            try {
                Thread.sleep(downloadInterval)
                downloadExecAction.execute()
            } catch (e: ExecException) {
                println("Parallel download failed with exception $e")
            }
        }
    }

    companion object {
        lateinit var gradle: Gradle
        lateinit var downloadExecAction: ExecAction
    }
}

/*
* If an option is configured in multiple locations, the first one wins:
*   1. mirakle.properties + mirakle_local.properties
*   2. args from CLI
*   3. GRADLE_USER_HOME/gradle.properties
* */
fun mergeStartParamsWithProperties(
    startParameter: StartParameter,
    gradlewRoot: File
): StartParameter {
    fun addPropertiesToStartParams(properties: Map<String, String>, startParameter: StartParameter) {
        properties.onEach { (key, value) ->
            if (key.startsWith("systemProp.")) {
                startParameter.systemPropertiesArgs[key.removePrefix("systemProp.")] = value
            } else {
                startParameter.projectProperties[key] = value
            }
        }
    }

    val mirakleProperties = loadProperties(File(gradlewRoot, "mirakle.properties"))
    val mirakleLocalProperties = loadProperties(File(gradlewRoot, "mirakle_local.properties"))
    val gradleHomeDirProperties = loadProperties(File(startParameter.gradleUserHomeDir, "gradle.properties"))

    return startParameter.copy().apply {
        projectProperties = mutableMapOf()
        systemPropertiesArgs = mutableMapOf()

        addPropertiesToStartParams(gradleHomeDirProperties, this)

        addPropertiesToStartParams(startParameter.projectProperties, this)
        addPropertiesToStartParams(startParameter.systemPropertiesArgs.mapKeys { (key, _) -> "systemProp.$key" }, this)

        addPropertiesToStartParams(mirakleProperties + mirakleLocalProperties, this)
    }
}