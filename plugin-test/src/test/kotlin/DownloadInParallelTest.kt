import com.instamotor.BuildConfig
import org.gradle.testkit.runner.GradleRunner
import org.jetbrains.spek.api.Spek
import org.jetbrains.spek.api.dsl.describe
import org.jetbrains.spek.api.dsl.it
import org.jetbrains.spek.api.dsl.on
import java.io.FileWriter
import java.io.PrintWriter

object DownloadInParallelTest : Spek({
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
                        .use { it.append(MIRAKLE_INIT_WITH_DOWNLOAD_IN_PARALLEL(folder.root.canonicalPath)) }
            }

            val gradleRunner by memoized {
                GradleRunner.create()
                        .withProjectDir(folder.root)
                        .withGradleVersion(gradleVersion)
                        .forwardOutput()
                        .withArguments("-I", "mirakle_init.gradle", "tasks")
            }

            val mirakleFile by memoized { folder.newFile("mirakle.gradle") }
            fun mirakleFileWriter() = PrintWriter(FileWriter(mirakleFile, true))

            on("building the project with enabled download in parallel") {
                mirakleFileWriter().use {
                    it.appendln(ASSERT_DOWNLOAD_IN_PARALLEL_AFTER_UPLOAD)
                }

                mirakleFileWriter().use {
                    it.appendln(ASSERT_DOWNLOAD_IN_PARALLEL_BEFORE_EXECUTE)
                }

                mirakleFileWriter().use {
                    it.appendln(ASSERT_DOWNLOAD_AFTER_DOWNLOAD_IN_PARALLEL)
                }

                val testResult = gradleRunner.test()

                it("should execute downloadInParallel task") {
                    testResult.assertTaskSucceed("downloadInParallel")
                }

                it("should execute downloadInParallel after uploadToRemote") {
                    testResult.assertBuildSuccessful()
                }

                it("should execute downloadInParallel before executeOnRemote") {
                    testResult.assertBuildSuccessful()
                }

                it("should execute downloadFromRemote after downloadInParallel") {
                    testResult.assertBuildSuccessful()
                }

                it("should execute downloadFromRemote") {
                    testResult.assertTaskSucceed("downloadFromRemote")
                }
            }

            on("failed uploadToRemote") {
                folder.root.listFiles().first { it.name == "mirakle_init.gradle" }
                        .outputStream()
                        .let(::PrintWriter)
                        .use { it.write(MIRAKLE_INIT_WITH_DOWNLOAD_IN_PARALLEL_AND_UNRESOLVABLE_HOST(folder.root.canonicalPath)) }

                val testResult = gradleRunner.testFail()

                it("should not execute downloadInParallel") {
                    testResult.assertNoTask("downloadInParallel")
                }
            }
        }
    }
})
