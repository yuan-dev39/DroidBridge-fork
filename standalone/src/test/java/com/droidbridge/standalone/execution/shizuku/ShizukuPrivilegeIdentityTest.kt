package com.droidbridge.standalone.execution.shizuku

import com.droidbridge.standalone.execution.android.RegisteredCapabilityState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuPrivilegeIdentityTest {
    private val nativeDirectory = "/data/app/com.example/lib/arm64"
    private val guardPath = "$nativeDirectory/${ShizukuLaunchPolicy.GUARD_NAME}"

    @Test
    fun rootAndShellAreRecognizedAsDistinctRealIdentities() {
        assertTrue(ShizukuLaunchPolicy.isSupportedUid(ShizukuLaunchPolicy.ROOT_UID))
        assertTrue(ShizukuLaunchPolicy.isSupportedUid(ShizukuLaunchPolicy.SHELL_UID))
        assertEquals("root", ShizukuLaunchPolicy.privilegeMode(ShizukuLaunchPolicy.ROOT_UID))
        assertEquals("shell", ShizukuLaunchPolicy.privilegeMode(ShizukuLaunchPolicy.SHELL_UID))
        assertEquals("unsupported", ShizukuLaunchPolicy.privilegeMode(10_000))
    }

    @Test
    fun rootAndShellMayAttachOnlyForTheRealApplicationCaller() {
        assertTrue(ShizukuLaunchPolicy.isAdmitted(0, 12_345, 12_345, nativeDirectory, guardPath, 0))
        assertTrue(ShizukuLaunchPolicy.isAdmitted(2_000, 12_345, 12_345, nativeDirectory, guardPath, 0))
        assertFalse(ShizukuLaunchPolicy.isAdmitted(10_000, 12_345, 12_345, nativeDirectory, guardPath, 0))
        assertFalse(ShizukuLaunchPolicy.isAdmitted(0, 12_346, 12_345, nativeDirectory, guardPath, 0))
    }

    @Test
    fun admissionStillEnforcesGuardPathAndResourceBounds() {
        assertFalse(
            ShizukuLaunchPolicy.isAdmitted(
                0, 12_345, 12_345, nativeDirectory, "/tmp/${ShizukuLaunchPolicy.GUARD_NAME}", 0,
            ),
        )
        assertFalse(
            ShizukuLaunchPolicy.isAdmitted(
                0, 12_345, 12_345, nativeDirectory, guardPath, ShizukuLaunchPolicy.MAX_GUARDS,
            ),
        )
    }

    @Test
    fun capabilityProjectionAcceptsUidZeroAndShellButRejectsOtherUids() {
        fun observation(uid: Int) = ShizukuObservation(
            managerInstalled = true,
            binderAlive = true,
            authorized = true,
            userServiceUid = uid,
            connecting = false,
        )
        for (uid in listOf(0, 2_000)) {
            val projection = ShizukuCapabilityProjector.project(observation(uid))
            assertEquals(ShizukuProviderState.Connected, projection.providerState)
            assertEquals(RegisteredCapabilityState.Available, projection.registrationState)
            assertTrue(projection.exposesExecutor)
        }
        val unsupported = ShizukuCapabilityProjector.project(observation(10_000))
        assertEquals(ShizukuProviderState.IncompatibleIdentity, unsupported.providerState)
        assertEquals(RegisteredCapabilityState.Unavailable, unsupported.registrationState)
        assertFalse(unsupported.exposesExecutor)
    }
}
