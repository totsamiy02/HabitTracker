package com.yaroslav.habittracker.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReleaseAssetTest {
    @Test
    fun identifiesPublishedVersionAndBuildNumber() {
        assertEquals(1L, releaseVersionCode("HabitTracker-0.0.1-build1.apk", "v0.0.1"))
        assertEquals(27L, releaseVersionCode("HabitTracker-0.4.2-build27.apk", "v0.4.2"))
    }

    @Test
    fun rejectsMismatchedTagAndInvalidNames() {
        assertNull(releaseVersionCode("HabitTracker-0.0.2-build2.apk", "v0.0.1"))
        assertNull(releaseVersionCode("app-debug.apk", "v0.0.1"))
        assertNull(releaseVersionCode("HabitTracker-0.0.1-build0.apk", "v0.0.1"))
        assertNull(releaseVersionCode("HabitTracker-0.0.1-build1Xapk", "v0.0.1"))
    }
}
