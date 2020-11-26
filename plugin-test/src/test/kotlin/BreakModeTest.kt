import com.instamotor.BuildConfig
import org.gradle.testkit.runner.GradleRunner
import org.jetbrains.spek.api.Spek
import org.jetbrains.spek.api.dsl.describe
import org.jetbrains.spek.api.dsl.it
import org.jetbrains.spek.api.dsl.on
import java.io.FileWriter
import java.io.PrintWriter
import java.util.*

object BreakModeTest : Spek({
    BuildConfig.TESTED_GRADLE_VERSIONS.forEach { gradleVersion ->
        describe("project with gradle version $gradleVersion") {
            val folder by temporaryFolder()

            beforeEachTest {
                GradleRunner.create()
                        .withProjectDir(folder.root)
                        .withGradleVersion(gradleVersion)
                        .withArguments("wrapper")
                        .build()
            }

            beforeEachTest {
                folder.newFile("mirakle_init.gradle")
                        .outputStream()
                        .let(::PrintWriter)
                        .use { it.append(MIRAKLE_INIT_WITH_BREAK_MODE(folder.root.canonicalPath, "")) }
            }

            val gradleRunner by memoized {
                GradleRunner.create()
                        .withProjectDir(folder.root)
                        .withGradleVersion(gradleVersion)
                        .forwardOutput()
                        .withArguments("-I", "mirakle_init.gradle", "mainTask")
            }

            val buildFile by memoized { folder.newFile("build.gradle.kts") }
            fun buildFileWriter() = PrintWriter(FileWriter(buildFile, true))

            on("building the project without breaking") {
                val uuid = UUID.randomUUID().toString()

                buildFileWriter().use {
                    it.appendln(BUILD_FILE_WITH_TASKS_GRAPH)
                    it.appendln(PRINT_MESSAGE_ON_LOCAL(uuid))
                }

                val testResult = gradleRunner.test()

                it("should be successful") {
                    testResult.assertBuildSuccessful()
                }

                it("should evaluate build file on local machine to calculate task graph") {
                    testResult.assertOutputContains(uuid)
                }

                it("mirakle tasks should be executed ") {
                    testResult.assertTaskSucceed("uploadToRemote")
                    testResult.assertTaskSucceed("executeOnRemote")
                    testResult.assertTaskSucceed("downloadFromRemote")
                }

                it("'mainTask' task should be skipped on local machine") {
                    testResult.assertTaskSkipped("mainTask")
                }
            }

            on("breaking on testTask2 defined in mirakle config") {
                folder.root.listFiles().first { it.name == "mirakle_init.gradle" }.delete()

                folder.newFile("mirakle_init.gradle")
                        .outputStream()
                        .let(::PrintWriter)
                        .use { it.append(MIRAKLE_INIT_WITH_BREAK_MODE(folder.root.canonicalPath, "testTask2")) }

                buildFileWriter().use {
                    it.appendln(BUILD_FILE_WITH_TASKS_GRAPH)
                }

                val testResult = gradleRunner.test()

                it("'testTask1' tasks should be SKIPPED on local machine") {
                    testResult.assertTaskSkipped("testTask1")
                }

                it("'testTask2' and 'testTask3' and 'mainTask' tasks should be EVALUATED on local machine") {
                    testResult.assertTaskSucceed("testTask2")
                    testResult.assertTaskSucceed("testTask3")
                    testResult.assertTaskSucceed("mainTask")
                }
            }

            on("breaking on testTask3 passed through CLI") {
                buildFileWriter().use {
                    it.appendln(BUILD_FILE_WITH_TASKS_GRAPH)
                }

                val testResult = gradleRunner
                        .addArgs("-P$BREAK_TASK=testTask3")
                        .test()

                it("'testTask1' and 'testTask2' tasks should be SKIPPED on local machine") {
                    testResult.assertTaskSkipped("testTask1")
                    testResult.assertTaskSkipped("testTask2")
                }

                it("'testTask3' and 'mainTask' tasks should be EVALUATED on local machine") {
                    testResult.assertTaskSucceed("testTask3")
                    testResult.assertTaskSucceed("mainTask")
                }
            }

            on("building the project with mirakle.gradle") {
                val uuid = UUID.randomUUID().toString()

                buildFileWriter().use {
                    it.appendln(BUILD_FILE_WITH_TASKS_GRAPH)
                }

                folder.newFile("mirakle.gradle")
                        .outputStream()
                        .let(::PrintWriter)
                        .use {
                            it.append(PRINT_MESSAGE(uuid))
                        }

                val testResult = gradleRunner.test()

                it("should evaluate mirakle.gradle build file") {
                    testResult.assertOutputContains(uuid)
                }
            }
        }
    }
})