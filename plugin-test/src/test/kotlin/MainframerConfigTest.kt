import org.jetbrains.spek.api.Spek
import org.jetbrains.spek.api.dsl.describe
import org.jetbrains.spek.api.dsl.it
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

object MainframerConfigTest: Spek({
    describe("mainframer config") {
        val mirakleConfig = MirakleExtension().apply {
            fallback = true
            downloadInParallel = true
            downloadInterval = 123
        }

        val mainframerFolder = javaClass.classLoader.getResource(".mainframer").file
        val config = getMainframerConfigOrNull(File(mainframerFolder.replace("/.mainframer", "")), mirakleConfig)

        it("should be parsed correctly") {
            config.apply {
                assertNotNull(config)

                assertEquals("sample@remotebuildmachine", config.host)
                assertEquals("~/mainframer", config.remoteFolder)

                assertTrue(config.excludeCommon.isEmpty())
                assertTrue(config.excludeLocal.isEmpty())
                assertTrue(config.excludeRemote.isEmpty())
                assertTrue(config.sshArgs.isEmpty())

                assertEquals(setOf(
                        "--archive",
                        "--delete",
                        "--compress-level=2",
                        "--exclude-from=$mainframerFolder/ignore",
                        "--exclude-from=$mainframerFolder/localignore"
                ), config.rsyncToRemoteArgs)

                assertEquals(setOf(
                        "--archive",
                        "--delete",
                        "--compress-level=3",
                        "--exclude-from=$mainframerFolder/ignore",
                        "--exclude-from=$mainframerFolder/remoteignore"
                ), config.rsyncFromRemoteArgs)

                assertTrue(config.fallback)
                assertTrue(config.downloadInParallel)
                assertEquals(123, config.downloadInterval)
            }
        }
    }
})