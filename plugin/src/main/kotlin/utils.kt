
import org.apache.tools.ant.taskdefs.condition.Os
import org.gradle.BuildAdapter
import org.gradle.BuildResult
import org.gradle.api.GradleException
import org.gradle.StartParameter
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.execution.TaskExecutionListener
import org.gradle.api.invocation.Gradle
import org.gradle.api.tasks.TaskState
import org.gradle.internal.service.ServiceRegistry
import java.io.File
import java.lang.reflect.Field
import java.util.*

fun Gradle.logTasks(tasks: List<Task>) = logTasks(*tasks.toTypedArray())

fun Gradle.logTasks(vararg task: Task) {
    task.forEach { targetTask ->
        addListener(object : TaskExecutionListener {
            var startTime: Long = 0

            override fun beforeExecute(task: Task) {
                if (task == targetTask) {
                    startTime = System.currentTimeMillis()
                }
            }

            override fun afterExecute(task: Task, state: TaskState) {
                if (task == targetTask && task.didWork) {
                    val finishTime = System.currentTimeMillis()
                    buildFinished {
                        val taskName = if (task.isMirakleTask()) task.name else task.path.drop(1)
                        println("Task $taskName took : ${prettyTime(finishTime - startTime)}")
                    }
                }
            }
        })
    }
}

fun Gradle.logBuild(startTime: Long, mirakle: Task) {
    // override default logger as soon as mirakle starts
    mirakle.doFirst {
        useLogger(object : BuildAdapter() {})
    }
    buildFinished {
        println("Total time : ${prettyTime(System.currentTimeMillis() - startTime)}")
    }
}

fun Gradle.assertNonSupportedFeatures() {
    if (startParameter.isContinuous) throw MirakleException("--continuous is not supported yet")
    if (startParameter.includedBuilds.isNotEmpty()) throw MirakleException("Included builds is not supported yet")
}

private const val MS_PER_MINUTE: Long = 60000
private const val MS_PER_HOUR = MS_PER_MINUTE * 60

fun prettyTime(timeInMs: Long): String {
    val result = StringBuffer()
    if (timeInMs > MS_PER_HOUR) {
        result.append(timeInMs / MS_PER_HOUR).append(" hrs ")
    }
    if (timeInMs > MS_PER_MINUTE) {
        result.append(timeInMs % MS_PER_HOUR / MS_PER_MINUTE).append(" mins ")
    }
    result.append(timeInMs % MS_PER_MINUTE / 1000.0).append(" secs")
    return result.toString()
}

class MirakleException(message: String? = null) : GradleException(message)

inline fun <reified T : Task> Project.task(name: String, noinline configuration: T.() -> Unit) =
        tasks.create(name, T::class.java, configuration)

val Task.services: ServiceRegistry
    get() {
        try {
            fun findField(java: Class<*>, field: String): Field {
                return try {
                    java.getDeclaredField(field)
                } catch (e: NoSuchFieldException) {
                    java.superclass?.let { findField(it, field) } ?: throw e
                }
            }

            val field = findField(DefaultTask::class.java, "services")
            field.isAccessible = true
            return field.get(this) as ServiceRegistry
        } catch (e: Throwable) {
            e.printStackTrace()
            throw e
        }
    }

fun Task.isMirakleTask() = name == "mirakle" || name == "uploadToRemote" || name == "executeOnRemote" || name == "downloadFromRemote" || name == "downloadInParallel" || name == "fallback"

/*
* On Windows rsync is used under Cygwin environment
* and classical Windows path "C:\Users" must be replaced by "/cygdrive/c/Users"
* */
fun fixPathForWindows(path: String) = if (Os.isFamily(Os.FAMILY_WINDOWS)) {
    val windowsDisk = path.first().toLowerCase()
    val windowsPath = path.substringAfter(":\\").replace('\\', '/')
    "/cygdrive/$windowsDisk/$windowsPath"
} else path

fun StartParameter.copy() = newInstance().also { copy ->
    copy.isBuildScan = this.isBuildScan
    copy.isNoBuildScan = this.isNoBuildScan
}

fun findGradlewRoot(root: File): File? {
    val gradlew = File(root, "gradlew")
    return if (gradlew.exists()) {
        gradlew.parentFile
    } else {
        root.parentFile?.let(::findGradlewRoot)
    }
}

fun loadProperties(file: File) = file.takeIf(File::exists)?.let {
    Properties().apply { load(it.inputStream()) }.toMap() as Map<String, String>
} ?: emptyMap()

fun Gradle.afterMirakleEvaluate(callback: () -> Unit) {
    var wasCallback = false

    gradle.rootProject {
        it.afterEvaluate {
            if (!wasCallback) {
                wasCallback = true
                callback()
            }
        }
    }

    // in case if build failed before project evaluation
    addBuildListener(object : BuildAdapter() {
        override fun buildFinished(p0: BuildResult) {
            if (p0.failure != null && !wasCallback) {
                wasCallback = true
                callback()
            }
        }
    })
}