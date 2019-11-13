import com.instamotor.BuildConfig
import org.gradle.testkit.runner.GradleRunner
import org.jetbrains.spek.api.Spek
import org.jetbrains.spek.api.dsl.describe
import org.jetbrains.spek.api.dsl.it
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.util.*
import kotlin.test.assertTrue

object MirakleInitTest : Spek({
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

            val buildFile by memoized { folder.newFile("build.gradle.kts") }
            fun buildFileWriter() = PrintWriter(FileWriter(buildFile, true))

            describe("mirakle init with self exec assertion") {
                beforeEachTest {
                    folder.newFile("mirakle_init.gradle")
                            .outputStream()
                            .let(::PrintWriter)
                            .use { it.append(MIRAKLE_INIT_ASSERT_EXEC_ARGS(folder.root.canonicalPath)) }
                }

                val testResult by memoized {
                    GradleRunner.create()
                            .withProjectDir(folder.root)
                            .withGradleVersion(gradleVersion)
                            .forwardOutput()
                            .withArguments("-I", "mirakle_init.gradle", "tasks")
                            .test()
                }

                it("should not fail") {
                    testResult.assertBuildSuccessful()
                }
            }

            describe("mirakle.gradle with self exec assertion") {
                val uuid = UUID.randomUUID().toString()

                beforeEachTest {
                    folder.newFile("mirakle_init.gradle")
                            .outputStream()
                            .let(::PrintWriter)
                            .use { it.append(MIRAKLE_INIT_WITHOUT_CONFIG) }
                }

                beforeEachTest {
                    folder.newFile("mirakle.gradle")
                            .outputStream()
                            .let(::PrintWriter)
                            .use {
                                it.append(PRINT_MESSAGE(uuid))
                                it.appendln(MIRAKLE_GRADLE_ASSERT_EXEC_ARGS(folder.root.canonicalPath))
                            }

                }

                val testResult by memoized {
                    GradleRunner.create()
                            .withProjectDir(folder.root)
                            .withGradleVersion(gradleVersion)
                            .forwardOutput()
                            .withArguments("-I", "mirakle_init.gradle", "tasks")
                            .test()
                }

                it("should evaluate mirakle.gradle") {
                    testResult.assertOutputContains(uuid)
                }

                it("should not fail") {
                    testResult.assertBuildSuccessful()
                }

                it("mirakle.gradle should still exist after build") {
                    assertTrue(File(folder.root, "mirakle.gradle").exists())
                }
            }

            describe("mirakle init without host") {
                beforeEachTest {
                    folder.newFile("mirakle_init.gradle")
                            .outputStream()
                            .let(::PrintWriter)
                            .use {
                                val MIRAKLE_INIT_WITHOUT_HOST = MIRAKLE_INIT_WITH_FOLDER(folder.root.canonicalPath).replace("host \"localhost\"", "")
                                it.append(MIRAKLE_INIT_WITHOUT_HOST)
                            }
                }

                val testResult by memoized {
                    GradleRunner.create()
                            .withProjectDir(folder.root)
                            .withGradleVersion(gradleVersion)
                            .forwardOutput()
                            .withArguments("-I", "mirakle_init.gradle", "tasks")
                            .testFail()
                }

                it("should fail") {
                    testResult.assertBuildFailed()
                }

                it("should print error message") {
                    testResult.assertOutputContains("Mirakle host is not defined.")
                }
            }
        }
    }
})