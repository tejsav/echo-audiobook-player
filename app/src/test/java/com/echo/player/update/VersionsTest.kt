package com.echo.player.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionsTest {

    @Test
    fun comparesVersionsTheWayReleasesAreTagged() {
        assertTrue(isNewer("v1.4", "1.3"))
        assertTrue(isNewer("v1.10", "1.9"))
        assertTrue(isNewer("1.3.1", "1.3"))
        assertFalse(isNewer("v1.3", "1.3"))
        assertFalse(isNewer("1.3", "1.3.0"))
        assertFalse(isNewer("1.2", "1.3-debug"))
    }
}
