import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.internal.AbstractTask
import org.gradle.api.invocation.Gradle
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.ProjectConnection
import org.gradle.tooling.events.ProgressListener
import org.gradle.tooling.events.internal.DefaultFinishEvent
import org.gradle.tooling.events.internal.DefaultStartEvent
import org.gradle.tooling.events.task.internal.DefaultTaskFinishEvent
import org.gradle.tooling.events.task.internal.DefaultTaskStartEvent
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintWriter
import java.util.*

class MirakleBenchmark : Plugin<Gradle> {
    override fun apply(gradle: Gradle) {
        gradle.rootProject { it.extensions.create("mirakleBenchmark", MirakleBenchmarkExtension::class.java) }

        if (gradle.startParameter.projectProperties.containsKey(BENCHMARK)) return
        if (!gradle.startParameter.taskNames.contains("mirakleBenchmark")) return

        val originalStartParams = gradle.startParameter.newInstance()
        originalStartParams.apply {
            setTaskNames(taskNames.minus("mirakleBenchmark"))
        }

        gradle.startParameter.apply {
            setTaskNames(listOf("mirakleBenchmark"))
            setExcludedTaskNames(listOf("mirakle"))

            buildFile = File(originalStartParams.currentDir, "mirakle.gradle").takeIf(File::exists)
                    ?: //a way to make Gradle not evaluate project's default build.gradle file on local machine
                    File(originalStartParams.currentDir, "mirakle_build_file_stub").also { stub ->
                        stub.createNewFile()
                        gradle.rootProject { it.afterEvaluate { stub.delete() } }
                    }
        }

        gradle.rootProject { project ->
            project.afterEvaluate {
                val benchmarkConfig = project.extensions.getByType(MirakleBenchmarkExtension::class.java)
                val mirakleConfig = project.extensions.getByName("mirakle")

                if (benchmarkConfig.name == null) throw IllegalArgumentException("Mirakle benchmark name is not defined.")
                if (benchmarkConfig.launchNumber == null) throw IllegalArgumentException("Mirakle benchmark launch number is not defined.")
                if (benchmarkConfig.resultsFolder == null) throw IllegalArgumentException("Mirakle benchmark results folder is not defined.")

                project.task<AbstractTask>("mirakleBenchmark") {
                    doFirst {
                        val resultsRoot = File("${benchmarkConfig.resultsFolder}/${benchmarkConfig.name}")

                        resultsRoot.apply {
                            if (exists()) deleteRecursively()
                            mkdirs()
                        }

                        val gradleOutputFile = File("$resultsRoot/gradle_output.txt")
                        val resultsFile = File("$resultsRoot/result.txt")

                        gradleOutputFile.createNewFile()
                        resultsFile.createNewFile()

                        fun printerWriter() = PrintWriter(FileOutputStream(resultsFile, true))

                        val mirakleConfigClass = mirakleConfig::class.java

                        StringBuilder().apply {
                            appendln("Name                  = ${benchmarkConfig.name}")
                            appendln("Project dir           = ${project.rootDir}")
                            appendln("Tasks                 = ${originalStartParams.taskNames}")
                            appendln("Launch number         = ${benchmarkConfig.launchNumber}")
                            appendln("Date                  = ${Date()}")
                            appendln("-----")
                            appendln("Host                  = ${mirakleConfigClass.getMethod("getHost").invoke(mirakleConfig)}")
                            appendln("Rsync to remote       = ${mirakleConfigClass.getMethod("getRsyncToRemoteArgs").invoke(mirakleConfig)}")
                            appendln("Rsync from remote     = ${mirakleConfigClass.getMethod("getRsyncFromRemoteArgs").invoke(mirakleConfig)}")
                            appendln("Download in parallel  = ${mirakleConfigClass.getMethod("getDownloadInParallel").invoke(mirakleConfig)}")
                            appendln("Download interval     = ${mirakleConfigClass.getMethod("getDownloadInterval").invoke(mirakleConfig)}")
                            appendln()
                        }.let {
                            printerWriter().use { logOut ->
                                logOut.append(it)
                            }

                            print(it)
                        }

                        val connection = GradleConnector.newConnector()
                                .forProjectDirectory(gradle.rootProject.projectDir)
                                .connect()

                        try {
                            (1..benchmarkConfig.launchNumber!!).asSequence().map {
                                println("Launching № $it...")
                                runTasks(originalStartParams.taskNames, connection, FileOutputStream(gradleOutputFile, true))
                            }.forEachIndexed { i, (beforeFirstTask, upload, execute, download, total) ->

                                fun format(long: Long) = "${long / 1000.0} secs"

                                StringBuilder().apply {
                                    appendln("----- LAUNCH № ${i + 1} -----")
                                    appendln("Init     : ${format(beforeFirstTask)}")
                                    appendln("Upload   : ${format(upload)}")
                                    appendln("Execute  : ${format(execute)}")
                                    appendln("Download : ${format(download)}")
                                    appendln("Total    : ${format(total)}")
                                    appendln("-----------------------")
                                    appendln()
                                }.let {
                                    printerWriter().use { logOut ->
                                        logOut.append(it)
                                    }

                                    print(it)
                                }
                            }
                        } finally {
                            connection.close()
                        }
                    }
                }
            }
        }
    }
}

const val BENCHMARK = "mirakle.benchmark"

open class MirakleBenchmarkExtension {
    var name: String? = null
    var launchNumber: Int? = null
    var resultsFolder: String? = null
}

fun runTasks(tasks: List<String>, connection: ProjectConnection, output: OutputStream): LogEntry {
    var buildStarted: Long = 0
    var buildFinished: Long = 0

    var firstTaskStarted: Long = 0

    var uploadStarted: Long = 0
    var uploadFinished: Long = 0

    var executeStarted: Long = 0
    var executeFinished: Long = 0

    var downloadStarted: Long = 0
    var downloadFinished: Long = 0

    connection.newBuild()
            .forTasks(*tasks.toTypedArray())
            .withArguments("-P$BENCHMARK=true")
            .setStandardOutput(output)
            .setStandardError(output)
            .addProgressListener(ProgressListener { event ->
                when (event::class.java) {
                    DefaultStartEvent::class.java -> {
                        when (event.displayName) {
                            "Run build started" -> buildStarted = event.eventTime
                            "Run tasks started" -> firstTaskStarted = event.eventTime
                        }
                    }

                    DefaultFinishEvent::class.java -> {
                        when (event.displayName) {
                            "Run build succeeded" -> buildFinished = event.eventTime
                        }
                    }

                    DefaultTaskStartEvent::class.java -> {
                        when (event.displayName) {
                            "Task :uploadToRemote started" -> uploadStarted = event.eventTime
                            "Task :executeOnRemote started" -> executeStarted = event.eventTime
                            "Task :downloadFromRemote started" -> downloadStarted = event.eventTime
                        }
                    }

                    DefaultTaskFinishEvent::class.java -> {
                        when (event.displayName) {
                            "Task :uploadToRemote SUCCESS" -> uploadFinished = event.eventTime
                            "Task :executeOnRemote SUCCESS" -> executeFinished = event.eventTime
                            "Task :downloadFromRemote SUCCESS" -> downloadFinished = event.eventTime
                        }
                    }
                }
            })
            .run()

    return LogEntry(
            beforeFirstTask = firstTaskStarted - buildStarted,
            upload = uploadFinished - uploadStarted,
            execute = executeFinished - executeStarted,
            download = downloadFinished - downloadStarted,
            total = buildFinished - buildStarted)
}

data class LogEntry(val beforeFirstTask: Long,
                    val upload: Long,
                    val execute: Long,
                    val download: Long,
                    val total: Long)


inline fun <reified T : Task> Project.task(name: String, noinline configuration: T.() -> Unit) =
        tasks.create(name, T::class.java, configuration)
