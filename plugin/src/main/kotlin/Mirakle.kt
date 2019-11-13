import com.googlecode.streamflyer.core.ModifyingWriter
import com.googlecode.streamflyer.regex.RegexModifier
import com.googlecode.streamflyer.regex.addons.tokens.Token
import com.googlecode.streamflyer.regex.addons.tokens.TokenProcessor
import com.googlecode.streamflyer.regex.addons.tokens.TokensMatcher
import com.instamotor.BuildConfig
import org.apache.commons.io.output.WriterOutputStream
import org.gradle.StartParameter
import org.gradle.api.Plugin
import org.gradle.api.Task
import org.gradle.api.execution.TaskExecutionListener
import org.gradle.api.internal.AbstractTask
import org.gradle.api.invocation.Gradle
import org.gradle.api.logging.LogLevel
import org.gradle.api.logging.configuration.ConsoleOutput
import org.gradle.api.logging.configuration.ShowStacktrace
import org.gradle.api.tasks.Exec
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
import javax.inject.Inject

class Mirakle : Plugin<Gradle> {
    override fun apply(gradle: Gradle) {
        gradle.rootProject { it.extensions.create("mirakle", MirakleExtension::class.java) }

        if (gradle.startParameter.taskNames.isEmpty()) return
        if (gradle.startParameter.projectProperties.containsKey(BUILD_ON_REMOTE)) return
        if (gradle.startParameter.projectProperties.containsKey(FALLBACK)) return
        if (gradle.startParameter.excludedTaskNames.remove("mirakle")) return
        if (gradle.startParameter.isDryRun) return

        val startTime = System.currentTimeMillis()

        gradle.assertNonSupportedFeatures()

        val originalStartParams = gradle.startParameter.newInstance()

        gradle.startParameter.apply {
            setTaskNames(listOf("mirakle"))
            setExcludedTaskNames(emptyList())
            useEmptySettings()
            buildFile = File(originalStartParams.currentDir, "mirakle.gradle").takeIf(File::exists)
                    ?: //a way to make Gradle not evaluate project's default build.gradle file on local machine
                    File(originalStartParams.currentDir, "mirakle_build_file_stub").also { stub ->
                        stub.createNewFile()
                        gradle.rootProject { it.afterEvaluate { stub.delete() } }
                    }
        }

        gradle.rootProject { project ->
            project.afterEvaluate {
                val config = getMainframerConfigOrNull(project.rootDir)?.also {
                    println("Mainframer config is applied, Mirakle config is ignored.")
                } ?: project.extensions.getByType(MirakleExtension::class.java)

                if (config.host == null) throw MirakleException("Mirakle host is not defined.")

                println("Here's Mirakle ${BuildConfig.VERSION}. All tasks will be executed on ${config.host}.")

                val upload = project.task<Exec>("uploadToRemote") {
                    setCommandLine("rsync")
                    args(
                            project.rootDir,
                            "${config.host}:${config.remoteFolder}",
                            "--rsh",
                            "ssh ${config.sshArgs.joinToString(separator = " ")}",
                            "--exclude=mirakle.gradle"
                    )
                    args(config.rsyncToRemoteArgs)
                }

                val execute = project.task<Exec>("executeOnRemote") {
                    setCommandLine("ssh")
                    args(config.sshArgs)
                    args(
                            config.host,
                            "${config.remoteFolder}/${project.name}/gradlew",
                            "-P$BUILD_ON_REMOTE=true",
                            "-p ${config.remoteFolder}/${project.name}"
                    )
                    args(startParamsToArgs(originalStartParams))

                    isIgnoreExitValue = true

                    standardOutput = modifyOutputStream(
                            standardOutput,
                            "${config.remoteFolder}/${project.name}",
                            project.rootDir.path
                    )
                    errorOutput = modifyOutputStream(
                            errorOutput,
                            "${config.remoteFolder}/${project.name}",
                            project.rootDir.path
                    )
                }.mustRunAfter(upload) as Exec

                val download = project.task<Exec>("downloadFromRemote") {
                    setCommandLine("rsync")
                    args(
                            "${config.host}:${config.remoteFolder}/${project.name}/",
                            "./",
                            "--rsh",
                            "ssh ${config.sshArgs.joinToString(separator = " ")}",
                            "--exclude=mirakle.gradle"
                    )
                    args(config.rsyncFromRemoteArgs)
                }.mustRunAfter(execute) as Exec

                if (config.downloadInParallel) {
                    if (config.downloadInterval <= 0) throw MirakleException("downloadInterval must be >0")

                    val downloadInParallel = project.task<AbstractTask>("downloadInParallel") {
                        doFirst {
                            val downloadExecAction = services.get(ExecActionFactory::class.java).newExecAction().apply {
                                setCommandLine(download.commandLine)
                                setArgs(download.args)
                                standardOutput = download.standardOutput
                                standardInput = download.standardInput
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
                            config.downloadInParallel && upload.execResult.exitValue == 0 && !execute.didWork
                        }
                    }

                    downloadInParallel.mustRunAfter(upload)
                    download.mustRunAfter(downloadInParallel)

                    gradle.startParameter.setTaskNames(listOf("downloadInParallel", "mirakle"))
                }

                val mirakle = project.task("mirakle").dependsOn(upload, execute, download)

                if (!config.fallback) {
                    mirakle.doLast {
                        execute.execResult.assertNormalExitValue()
                    }
                } else {
                    val fallback = project.task<AbstractTask>("fallback") {
                        onlyIf { upload.execResult.exitValue != 0 }

                        doFirst {
                            println("Upload to remote failed. Continuing with fallback.")

                            val connection = GradleConnector.newConnector()
                                    .forProjectDirectory(gradle.rootProject.projectDir)
                                    .connect()

                            try {
                                connection.newBuild()
                                        .withArguments(startParamsToArgs(originalStartParams).plus("-P$FALLBACK=true"))
                                        .setStandardInput(upload.standardInput)
                                        .setStandardOutput(upload.standardOutput)
                                        .setStandardError(upload.errorOutput)
                                        .run()
                            } finally {
                                connection.close()
                            }
                        }
                    }

                    upload.isIgnoreExitValue = true
                    upload.finalizedBy(fallback)

                    execute.onlyIf { upload.execResult.exitValue == 0 }
                    download.onlyIf { upload.execResult.exitValue == 0 }
                }

                gradle.supportAndroidStudioAdvancedProfiling(config, upload, execute, download)

                gradle.logTasks(upload, execute, download)
                gradle.logBuild(startTime)
            }
        }
    }
}

open class MirakleExtension {
    var host: String? = null

    var remoteFolder: String = "mirakle"

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
            "**/local.properties"
    )

    var rsyncToRemoteArgs = setOf(
            "--archive",
            "--delete"
    )
        get() = field.plus(excludeLocal.plus(excludeCommon).map { "--exclude=$it" })

    var rsyncFromRemoteArgs = setOf(
            "--archive",
            "--delete"
    )
        get() = field.plus(excludeRemote.plus(excludeCommon).map { "--exclude=$it" })

    var sshArgs = emptySet<String>()

    var fallback = false

    var downloadInParallel = false
    var downloadInterval = 2000L
}

fun startParamsToArgs(params: StartParameter) = with(params) {
    emptyList<String>()
            .plus(taskNames)
            .plus(excludedTaskNames.map { "--exclude-task $it" })
            .plus(buildFile?.let { "-b $it" })
            .plus(booleanParamsToOption.map { (param, option) -> if (param(this)) option else null })
            .plus(negativeBooleanParamsToOption.map { (param, option) -> if (!param(this)) option else null })
            .plus(projectProperties.flatMap { (key, value) -> listOf("--project-prop", "$key=$value") })
            .plus(systemPropertiesArgs.flatMap { (key, value) -> listOf("--system-prop", "$key=$value") })
            .plus(logLevelToOption.firstOrNull { (level, _) -> logLevel == level }?.second)
            .plus(showStacktraceToOption.firstOrNull { (show, _) -> showStacktrace == show }?.second)
            .plus(consoleOutputToOption.firstOrNull { (console, _) -> consoleOutput == console }?.second)
            .filterNotNull()
}

val booleanParamsToOption = listOf(
        StartParameter::isProfile to "--profile",
        StartParameter::isRerunTasks to "--rerun-tasks",
        StartParameter::isRefreshDependencies to "--refresh-dependencies",
        StartParameter::isContinueOnFailure to "--continue",
        StartParameter::isOffline to "--offline",
        StartParameter::isParallelProjectExecutionEnabled to "--parallel",
        StartParameter::isConfigureOnDemand to "--configure-on-demand"
)

val negativeBooleanParamsToOption = listOf(
        StartParameter::isBuildProjectDependencies to "--no-rebuild"
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
        ConsoleOutput.Plain to "--console plain",
        //ConsoleOutput.Auto to "--console auto", //default, no need to pass
        ConsoleOutput.Rich to "--console rich"
)

//since build occurs on a remote machine, console output will contain remote directories
//to let IDE and other tools properly parse the output, mirakle need to replace remote dir by local one
fun modifyOutputStream(target: OutputStream, remoteDir: String, localDir: String): OutputStream {
    val tokenList = listOf(
            Token("1", "\\/.*?\\/$remoteDir", localDir)
            //Token("2", "(?<=\\n)BUILD FAILED", "REMOTE BUILD FAILED"),
            //Token("3", "(?<=\\n)BUILD SUCCESSFUL", "REMOTE BUILD SUCCESSFUL")
    )
    val modifier = RegexModifier(TokensMatcher(tokenList), TokenProcessor(tokenList), 0, 1)
    val modifyingWriter = ModifyingWriter(OutputStreamWriter(target), modifier)
    return WriterOutputStream(modifyingWriter)
}

fun getMainframerConfigOrNull(projectDir: File): MirakleExtension? {
    val mainframerDir = File(projectDir, ".mainframer")
    return if (mainframerDir.exists()) {
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
        }
    } else null
}

const val BUILD_ON_REMOTE = "mirakle.build.on.remote"
const val FALLBACK = "mirakle.build.fallback"

//TODO test
fun Gradle.supportAndroidStudioAdvancedProfiling(config: MirakleExtension, upload: Exec, execute: Exec, download: Exec) {
    if (startParameter.projectProperties.containsKey("android.advanced.profiling.transforms")) {
        println("Android Studio advanced profilling enabled. Profiler files will be uploaded to remote project dir.")

        val studioProfilerJar = File(startParameter.projectProperties["android.advanced.profiling.transforms"])
        val studioProfilerProp = File(startParameter.systemPropertiesArgs["android.profiler.properties"])

        val jarInRootProject = gradle.rootProject.file(studioProfilerJar.name)
        val propInRootProject = gradle.rootProject.file(studioProfilerProp.name)

        if (jarInRootProject.exists()) jarInRootProject.delete()
        if (propInRootProject.exists()) propInRootProject.delete()

        upload.doFirst {
            Files.copy(studioProfilerJar.toPath(), jarInRootProject.toPath())
            Files.copy(studioProfilerProp.toPath(), propInRootProject.toPath())
        }

        upload.doLast {
            jarInRootProject.delete()
            propInRootProject.delete()
        }

        execute.doFirst {
            val profilerJarPathArg = "android.advanced.profiling.transforms=${studioProfilerJar.toPath()}"
            val profilerPropPathArg = "android.profiler.properties=${studioProfilerProp.toPath()}"

            val rootProfilerJarArg = "android.advanced.profiling.transforms=${config.remoteFolder}/${gradle.rootProject.name}/${jarInRootProject.name}"
            val rootProfilerPropArg = "android.profiler.properties=${config.remoteFolder}/${gradle.rootProject.name}/${propInRootProject.name}"

            execute.args = execute.args!!.apply {
                set(indexOf(profilerJarPathArg), rootProfilerJarArg)
                set(indexOf(profilerPropPathArg), rootProfilerPropArg)
            }
        }

        download.doFirst {
            download.args("--exclude=${jarInRootProject.name}")
            download.args("--exclude=${propInRootProject.name}")
        }
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
