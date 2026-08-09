package com.agentkosticka.amply.shizuku.client

import org.junit.Assert.assertEquals
import org.junit.Test

class ShizukuComponentCompatibilityTest {
    @Test
    fun userServiceKeepsUpgradeSafeComponentName() {
        assertEquals(
            "com.agentkosticka.amply.shizuku.VolumeUserService",
            STABLE_VOLUME_USER_SERVICE_CLASS_NAME
        )
    }

    @Test
    fun userServiceVersionRecreatesPreCleanupInstances() {
        assertEquals(8, VOLUME_USER_SERVICE_VERSION)
    }
}
