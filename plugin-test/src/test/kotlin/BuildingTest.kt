import com.instamotor.BuildConfig
import org.gradle.testkit.runner.GradleRunner
import org.jetbrains.spek.api.Spek
import org.jetbrains.spek.api.dsl.describe
import org.jetbrains.spek.api.dsl.it
import org.jetbrains.spek.api.dsl.on
import java.io.FileWriter
import java.io.PrintWriter
import java.util.*

object BuildingTest : Spek({
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
                        .use { it.append(MIRAKLE_INIT_WITH_FOLDER(folder.root.canonicalPath)) }
            }

            val gradleRunner by memoized {
                GradleRunner.create()
                        .withProjectDir(folder.root)
                        .withGradleVersion(gradleVersion)
                        .forwardOutput()
                        .withArguments("-I", "mirakle_init.gradle", "tasks")
            }

            val buildFile by memoized { folder.newFile("build.gradle.kts") }
            fun buildFileWriter() = PrintWriter(FileWriter(buildFile, true))

            on("building the project") {
                val uuid = UUID.randomUUID().toString()

                buildFileWriter().use {
                    it.appendln(ASSERT_REMOTE)
                    it.appendln(PRINT_MESSAGE(uuid))
                }

                val testResult = gradleRunner.test()

                it("should be successful") {
                    testResult.assertBuildSuccessful()
                }

                it("should be executed on remote") {
                    testResult.assertTaskSucceed("uploadToRemote")
                    testResult.assertTaskSucceed("executeOnRemote")
                    testResult.assertTaskSucceed("downloadFromRemote")
                }

                it("should evaluate build file") {
                    testResult.assertOutputContains(uuid)
                }
            }

            on("providing -x mirakle") {
                buildFileWriter().use { it.write(ASSERT_NOT_REMOTE) }

                val testResult = gradleRunner.addArgs("-x", "mirakle").test()

                it("should be executed locally") {
                    testResult.assertBuildSuccessful()
                    testResult.assertNoTask("mirakle")
                }
            }

            on("empty tasks") {
                val testResult = gradleRunner.
                        withArguments(gradleRunner.arguments.minus("tasks"))
                        .test()

                it("should not invoke mirakle") {
                    testResult.assertNoTask("mirakle")
                }
            }

            on("property $BUILD_ON_REMOTE=true") {
                val testResult = gradleRunner.addArgs("-P$BUILD_ON_REMOTE=true").test()

                it("should not invoke mirakle") {
                    testResult.assertNoTask("mirakle")
                }
            }

            on("--dry-run") {
                val testResult = gradleRunner.addArgs("--dry-run").test()

                it("should not invoke mirakle") {
                    testResult.assertNoTask("mirakle")
                }
            }

            on("exception occurred on remote side") {
                buildFileWriter().use { it.write(THROW) }
                val testResult = gradleRunner.testFail()

                it("should fail") {
                    testResult.assertBuildFailed()
                }

                it("should execute download task") {
                    testResult.assertTaskSucceed("downloadFromRemote")
                }
            }
        }
    }
})