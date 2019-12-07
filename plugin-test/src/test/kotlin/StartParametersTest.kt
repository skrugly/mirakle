import com.instamotor.BuildConfig
import org.gradle.StartParameter
import org.jetbrains.spek.api.Spek
import org.gradle.testkit.runner.GradleRunner
import org.jetbrains.spek.api.dsl.*
import java.io.*

object StartParametersTest : Spek({
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

            describe("boolean start params") {
                booleanParamsToOption
                        .filter {
                            //param is passed but downloadFromRemote failed TODO figure out
                            //rsync warning: some files vanished before they could be transferred (code 24) at main.c(1721) [generator=3.1.2]
                            it.first != StartParameter::isProfile
                        }
                        .onEach { (param, option) ->
                            given("param ${param.name}") {
                                on("passing $option") {

                                    buildFileWriter().use {
                                        it.write(ASSERT_START_PARAM_IS_TRUE(param.name))
                                    }

                                    it("should receive ${param.name}") {
                                        gradleRunner.addArgs(option).build()
                                    }
                                }
                            }
                        }
            }

            describe("negative boolean start params") {
                negativeBooleanParamsToOption.onEach { (param, option) ->
                    given("param ${param.name}") {
                        on("passing $option") {

                            buildFileWriter().use {
                                it.write(ASSERT_START_PARAM_IS_FALSE(param.name))
                            }

                            it("should receive ${param.name}") {
                                gradleRunner.addArgs(option).build()
                            }
                        }
                    }
                }
            }

            describe("log level start params") {
                logLevelToOption.onEach { (param, option) ->
                    given("param ${param.name}") {
                        on("passing $option") {

                            buildFileWriter().use {
                                it.write(ASSERT_START_PARAM_LOG_LEVEL_IS(param.name))
                            }

                            it("should receive ${param.name}") {
                                gradleRunner.addArgs(option).build()
                            }
                        }
                    }
                }
            }

            describe("show stacktrace start params") {
                showStacktraceToOption.onEach { (param, option) ->
                    given("param ${param.name}") {
                        on("passing $option") {

                            buildFileWriter().use {
                                it.write(ASSERT_START_PARAM_SHOW_STACKTRACE_IS(param.name))
                            }

                            it("should receive ${param.name}") {
                                gradleRunner.addArgs(option).build()
                            }
                        }
                    }
                }
            }

            describe("console output start params") {
                consoleOutputToOption.onEach { (param, option) ->
                    given("param ${param.name}") {
                        on("passing $option") {

                            buildFileWriter().use {
                                it.write(ASSERT_START_PARAM_CONSOLE_OUTPUT_IS(param.name))
                            }

                            it("should receive ${param.name}") {
                                gradleRunner.addArgs(option.split(" ")[0], option.split(" ")[1]).build()
                            }
                        }
                    }
                }
            }

            on("passing tasks") {
                val tasks = listOf("task1", "task2", "task3")

                buildFileWriter().use {
                    it.append(ASSERT_CONTAINS_TASKS(tasks))
                }

                it("should receive the same tasks") {
                    gradleRunner.addArgs(tasks).build()
                }
            }

            on("passing mirakle task") {
                val tasks = listOf("task1", "mirakle")

                buildFileWriter().use {
                    it.append(ASSERT_NOT_CONTAINS_TASK("mirakle"))
                }

                it("should not receive mirakle task on remote machine") {
                    gradleRunner.addArgs(tasks).build()
                }
            }

            on("passing excluded tasks") {
                val tasks = listOf("task1", "task2", "task3")

                buildFileWriter().use {
                    it.append(ASSERT_CONTAINS_EXCLUDED_TASKS(tasks))
                }

                it("should receive the same excluded tasks") {
                    gradleRunner.addArgs(tasks.flatMap { listOf("--exclude-task", it) }).build()
                }
            }


            on("passing project properties") {
                val properties = mapOf(
                        "prop1" to "true",
                        "prop2" to "2",
                        "prop3" to "foobar"
                )

                buildFileWriter().use {
                    it.append(ASSERT_CONTAINS_PROJECT_PROPERTIES(properties))
                }

                it("should receive the same properties") {
                    gradleRunner.addArgs(properties.flatMap { (k, v) -> listOf("--project-prop", "$k=$v") }).build()
                }
            }

            on("passing system properties") {
                val properties = mapOf(
                        "prop1" to "true",
                        "prop2" to "2",
                        "prop3" to "foobar"
                )

                buildFileWriter().use {
                    it.append(ASSERT_CONTAINS_SYSTEM_PROPERTIES(properties))
                }

                it("should receive the same properties") {
                    gradleRunner.addArgs(properties.flatMap { (k, v) -> listOf("--system-prop", "$k=$v") }).build()
                }
            }
        }
    }
})