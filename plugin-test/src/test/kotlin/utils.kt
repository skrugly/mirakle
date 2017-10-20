import com.instamotor.BuildConfig
import org.gradle.internal.impldep.org.junit.rules.TemporaryFolder
import org.gradle.testkit.runner.*
import org.jetbrains.spek.api.dsl.SpecBody
import java.io.File
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

val MIRAKLE_INIT_WITH_FOLDER = fun(remoteFolder: String) = """
initscript {
    repositories {
        mavenLocal()
        mavenCentral()
    }
    dependencies {
        classpath "com.instamotor:mirakle:${BuildConfig.VERSION}"
    }
}

apply plugin: Mirakle

rootProject {
    mirakle {
        host "localhost"
        remoteFolder "$remoteFolder/mirakle"
    }
}
"""

val MIRAKLE_INIT_WITHOUT_CONFIG = """
initscript {
    repositories {
        mavenLocal()
        mavenCentral()
    }
    dependencies {
        classpath "com.instamotor:mirakle:${BuildConfig.VERSION}"
    }
}

apply plugin: Mirakle
"""

val MIRAKLE_INIT_ASSERT_EXEC_ARGS = fun(remoteFolder: String) = """
initscript {
    repositories {
        mavenLocal()
        mavenCentral()
    }
    dependencies {
        classpath "com.instamotor:mirakle:${BuildConfig.VERSION}"
    }
}

apply plugin: Mirakle

rootProject {
    ${ASSERT_EXEC_ARGS(remoteFolder)}
}
"""

val MIRAKLE_INIT_WITH_FALLBACK_AND_UNRESOLVABLE_HOST = """
initscript {
    repositories {
        mavenLocal()
        mavenCentral()
    }
    dependencies {
        classpath "com.instamotor:mirakle:${BuildConfig.VERSION}"
    }
}

apply plugin: Mirakle

rootProject {
    mirakle {
        host "affsfnjkdasfnajdkfhajlkdfhljkdfhl"
        fallback true
    }
}
"""

val MIRAKLE_GRADLE_ASSERT_EXEC_ARGS = fun(remoteFolder: String) = ASSERT_EXEC_ARGS(remoteFolder)

val ASSERT_EXEC_ARGS = fun(remoteFolder: String) = """
mirakle {
    host "localhost"
    remoteFolder "$remoteFolder/.mirakle"

    excludeLocal += ["foo"]
    excludeRemote += ["bar"]
    excludeCommon += ["foobar"]

    rsyncFromRemoteArgs += ["--stats", "-h"]
    rsyncToRemoteArgs += ["--stats", "-h"]

    sshArgs += ["-p 22"]
}

afterEvaluate {
    def uploadToRemote = getTasksByName("uploadToRemote", false)[0] as Exec

    //contract
    assert uploadToRemote.commandLine[0] == "rsync"
    assert uploadToRemote.args.contains("--rsh")
    assert uploadToRemote.args.contains("ssh -p 22")
    assert uploadToRemote.args.contains(rootDir.path)
    assert uploadToRemote.args.contains("localhost:$remoteFolder/.mirakle")
    assert uploadToRemote.args.contains("--exclude=mirakle.gradle")

    //default args
    assert uploadToRemote.args.contains("--archive")
    assert uploadToRemote.args.contains("--delete")

    //passed args
    assert uploadToRemote.args.contains("--stats")
    assert uploadToRemote.args.contains("-h")

    //default exclude
    assert uploadToRemote.args.contains("--exclude=.gradle")
    assert uploadToRemote.args.contains("--exclude=.idea")
    assert uploadToRemote.args.contains("--exclude=**/.git/")
    assert uploadToRemote.args.contains("--exclude=**/local.properties")
    assert uploadToRemote.args.contains("--exclude=**/build")

    //passed exclude
    assert uploadToRemote.args.contains("--exclude=foo")
    assert uploadToRemote.args.contains("--exclude=foobar")

    ///

    def executeOnRemote = getTasksByName("executeOnRemote", false)[0] as Exec

    //contract
    assert executeOnRemote.commandLine[0] == "ssh"
    assert executeOnRemote.args.contains("localhost")
    assert executeOnRemote.args.contains("$remoteFolder/.mirakle/" + name + "/gradlew")
    assert executeOnRemote.args.contains("-Pmirakle.build.on.remote=true")
    assert executeOnRemote.args.contains("-p $remoteFolder/.mirakle/" + name)

    //passed args
    assert executeOnRemote.args.contains("-p 22")

    ///

    def downloadFromRemote = getTasksByName("downloadFromRemote", false)[0] as Exec

    //contract
    assert downloadFromRemote.commandLine[0] == "rsync"
    assert downloadFromRemote.args.contains("--rsh")
    assert downloadFromRemote.args.contains("ssh -p 22")
    assert downloadFromRemote.args.contains("localhost:$remoteFolder/.mirakle/" + name + "/")
    assert downloadFromRemote.args.contains("./")
    assert downloadFromRemote.args.contains("--exclude=mirakle.gradle")

    //default args
    assert downloadFromRemote.args.contains("--archive")
    assert downloadFromRemote.args.contains("--delete")

    //passed args
    assert downloadFromRemote.args.contains("--stats")
    assert downloadFromRemote.args.contains("-h")

    //default exclude
    assert downloadFromRemote.args.contains("--exclude=.gradle")
    assert downloadFromRemote.args.contains("--exclude=.idea")
    assert downloadFromRemote.args.contains("--exclude=**/.git/")
    assert downloadFromRemote.args.contains("--exclude=**/local.properties")
    assert downloadFromRemote.args.contains("--exclude=**/src/")

    //passed exclude
    assert downloadFromRemote.args.contains("--exclude=bar")
    assert downloadFromRemote.args.contains("--exclude=foobar")
}
"""

const val ASSERT_REMOTE = """if(!hasProperty("$BUILD_ON_REMOTE")) throw Exception("Build must occur on remote")"""
const val ASSERT_NOT_REMOTE = """if(hasProperty("$BUILD_ON_REMOTE")) throw Exception("Build must not occur on remote")"""
const val THROW = """throw Exception()"""

val PRINT_MESSAGE = fun(message: String) = """print("$message")"""

val ASSERT_START_PARAM_IS_TRUE = fun(name: String) =
        """
        if(project.gradle.startParameter.$name != true)
            throw Exception("$name must be true")
        """

val ASSERT_START_PARAM_IS_FALSE = fun(name: String) =
        """
        if(project.gradle.startParameter.$name == true)
            throw Exception("$name must be false")
        """

val ASSERT_START_PARAM_LOG_LEVEL_IS = fun(enumName: String) =
        """
        if(project.gradle.startParameter.logLevel != LogLevel.$enumName)
            throw Exception("LogLevel must be $enumName")
        """

val ASSERT_START_PARAM_SHOW_STACKTRACE_IS = fun(enumName: String) =
        """
        if(project.gradle.startParameter.showStacktrace != ShowStacktrace.$enumName)
            throw Exception("ShowStacktrace must be $enumName")
        """

val ASSERT_START_PARAM_CONSOLE_OUTPUT_IS = fun(enumName: String) =
        """
        if(project.gradle.startParameter.consoleOutput != ConsoleOutput.$enumName)
            throw Exception("ConsoleOutput must be $enumName")
        """

val ASSERT_CONTAINS_TASKS = fun(tasks: List<String>) =
        """
        if(!gradle.startParameter.taskNames.containsAll(listOf(${tasks.joinToString { "\"$it\"" }})))
            throw Exception("Expected tasks $tasks, but was = " + gradle.startParameter.taskNames)
        gradle.startParameter.setTaskNames(emptyList()) // since this tasks are not exist
        """

val ASSERT_CONTAINS_EXCLUDED_TASKS = fun(tasks: List<String>) =
        """
        if(!gradle.startParameter.excludedTaskNames.containsAll(listOf(${tasks.joinToString { "\"$it\"" }})))
            throw Exception("Expected excluded tasks $tasks, but was = " + gradle.startParameter.excludedTaskNames)
        gradle.startParameter.setExcludedTaskNames(emptyList()) // since this tasks are not exist
        """

val ASSERT_CONTAINS_PROJECT_PROPERTIES = fun(properties: Map<String, String>) =
        """
        if(!gradle.startParameter.projectProperties.entries.containsAll(mapOf(${properties.entries.joinToString { "\"${it.key}\" to \"${it.value}\"" }}).entries))
            throw Exception("Expected project properties $properties, but was = " + gradle.startParameter.projectProperties)
        """

val ASSERT_CONTAINS_SYSTEM_PROPERTIES = fun(properties: Map<String, String>) =
        """
        if(!gradle.startParameter.systemPropertiesArgs.entries.containsAll(mapOf(${properties.entries.joinToString { "\"${it.key}\" to \"${it.value}\"" }}).entries))
            throw Exception("Expected system properties $properties, but was = " + gradle.startParameter.systemPropertiesArgs)
        """

fun SpecBody.temporaryFolder(parentDir: File? = null) = object : ReadOnlyProperty<Any?, TemporaryFolder> {
    val folder = TemporaryFolder(parentDir)

    init {
        beforeEachTest(folder::create)
        afterEachTest(folder::delete)
    }

    override fun getValue(thisRef: Any?, property: KProperty<*>) = folder
}

fun GradleRunner.addArgs(vararg args: String) = withArguments(arguments.plus(args))
fun GradleRunner.addArgs(args: String) = withArguments(arguments.plus(args))
fun GradleRunner.addArgs(args: List<String>) = withArguments(arguments.plus(args))

fun GradleRunner.test(): TestResult {
    return try {
        TestResult(build(), false)
    } catch (e: UnexpectedBuildFailure) {
        TestResult(null, true)
    }
}

fun GradleRunner.testFail(): TestResult {
    return try {
        TestResult(buildAndFail(), true)
    } catch (e: UnexpectedBuildSuccess) {
        TestResult(null, false)
    }
}

data class TestResult(val buildResult: BuildResult?, val buildFailed: Boolean)

fun TestResult.assertBuildSuccessful() {
    assertFalse(buildFailed)
}

fun TestResult.assertBuildFailed() {
    assertTrue(buildFailed)
}

fun TestResult.assertOutputContains(text: String) {
    buildResult!!.output.contains(text)
}

fun TestResult.assertTaskSucceed(taskName: String) {
    assertTrue(buildResult!!.task(":$taskName")?.outcome == TaskOutcome.SUCCESS)
}

fun TestResult.assertTaskFailed(taskName: String) {
    assertTrue(buildResult!!.task(":$taskName")?.outcome == TaskOutcome.FAILED)
}

fun TestResult.assertTaskSkipped(taskName: String) {
    assertTrue(buildResult!!.task(":$taskName")?.outcome == TaskOutcome.SKIPPED)
}

fun TestResult.assertNoTask(taskName: String) {
    assertNull(buildResult!!.tasks.firstOrNull { it.path == ":$taskName" })
}