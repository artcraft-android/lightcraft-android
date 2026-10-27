package ai.storyteller.lightcraft.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The cloud library's connection helpers: the config's remotes, the device name and the remote path. */
class CloudConfigTest {
    @Test
    fun remotesAreTheSectionsOfTheConfig() {
        val text = "# rclone\n[phone]\ntype = drive\ntoken = {\"a\":[1]}\n\n  [ other ]  \ntype = s3\n[phone]\n[]\n"
        assertEquals(listOf("phone", "other"), CloudConfig.remotes(text))
        assertEquals(emptyList<String>(), CloudConfig.remotes("no sections here"))
    }

    @Test
    fun deviceNamesAreMadeValid() {
        assertEquals("Pixel-9-Pro", CloudConfig.sanitiseDevice("Pixel 9 Pro"))
        assertEquals("SM-S918B", CloudConfig.sanitiseDevice("SM-S918B"))
        assertEquals("phone", CloudConfig.sanitiseDevice("   "))
        assertEquals("phone", CloudConfig.sanitiseDevice("..."))
        val long = CloudConfig.sanitiseDevice("x".repeat(100))
        assertEquals(64, long.length)
        listOf("Pixel 9 Pro", "Galaxy S24+", "ñandú", ".hidden", "a".repeat(90)).forEach { assertTrue(it, CloudConfig.validDevice(CloudConfig.sanitiseDevice(it))) }
        assertFalse(CloudConfig.validDevice("a b"))
        assertFalse(CloudConfig.validDevice(".a"))
    }

    @Test
    fun theRemotePathJoinsRemoteAndFolder() {
        assertEquals("phone:", CloudConfig.remotePath("phone", ""))
        assertEquals("phone:LightCraft Cloud", CloudConfig.remotePath("phone", " LightCraft Cloud "))
        assertEquals("phone:a/b", CloudConfig.remotePath("phone", "/a/b/"))
    }
}
