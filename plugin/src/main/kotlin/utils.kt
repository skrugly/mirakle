import groovy.lang.Closure
import org.gradle.BuildAdapter
import org.gradle.BuildResult
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.execution.TaskExecutionListener
import org.gradle.api.invocation.Gradle
import org.gradle.api.tasks.TaskState
import kotlin.properties.Delegates

fun Gradle.logTasks(vararg task: Task) {
    task.forEach { targetTask ->
        addListener(object : TaskExecutionListener {
            var timer by Delegates.notNull<Timer>()

            override fun beforeExecute(task: Task) {
                if (task == targetTask) {
                    timer = Timer()
                }
            }

            override fun afterExecute(task: Task, state: TaskState) {
                if (task == targetTask) {
                    val elapsed = timer.elapsed
                    buildFinished {
                        println("Task ${task.name} took: $elapsed")
                    }
                }
            }
        })
    }
}

fun Gradle.logBuild(clock: Timer) {
    useLogger(object : BuildAdapter() {})
    buildFinished {
        println("Total time : ${clock.elapsed}")
    }
}

fun Gradle.assertNonSupportedFeatures() {
    if (startParameter.isContinuous) throw MirakleException("--continuous is not supported yet")
    if (startParameter.includedBuilds.isNotEmpty()) throw MirakleException("Included builds is not supported yet")
}

class MirakleException(message: String) : RuntimeException(message)

inline fun <reified T : Task> Project.task(name: String, noinline configuration: T.() -> Unit) =
        tasks.create(name, T::class.java, configuration)!!


//void buildFinished(Action<? super BuildResult> action) is available since 3.4
//this is needed to support Gradle 3.3
fun Gradle.buildFinished(body: (BuildResult) -> Unit) {
    buildFinished(closureOf(body))
}

fun <T : Any> Any.closureOf(action: T.() -> Unit): Closure<Any?> =
        KotlinClosure1(action, this, this)

class KotlinClosure1<in T : Any, V : Any>(
        val function: T.() -> V?,
        owner: Any? = null,
        thisObject: Any? = null) : Closure<V?>(owner, thisObject) {

    @Suppress("unused") // to be called dynamically by Groovy
    fun doCall(it: T): V? = it.function()
}
