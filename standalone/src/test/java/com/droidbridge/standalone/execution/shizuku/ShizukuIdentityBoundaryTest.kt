package com.droidbridge.standalone.execution.shizuku

import com.droidbridge.standalone.execution.android.RegisteredCapabilityState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuIdentityBoundaryTest {
    @Test
    fun shellUserServiceRequiresShellUidAndTheOwningAppCaller() {
        val packageUid = 10_123
        val nativeDir = "/data/app/~~example/lib/arm64"
        val guardPath = "$nativeDir/${ShizukuLaunchPolicy.GUARD_NAME}"

        assertTrue(
            ShizukuLaunchPolicy.isAdmitted(
                effectiveUid = 2_000,
                callerUid = packageUid,
                packageUid = packageUid,
                nativeDirectory = nativeDir,
                guardPath = guardPath,
                activeCount = 0,
            ),
        )
        assertFalse(
            ShizukuLaunchPolicy.isAdmitted(
                effectiveUid = 0,
                callerUid = packageUid,
                packageUid = packageUid,
                nativeDirectory = nativeDir,
                guardPath = guardPath,
                activeCount = 0,
            ),
        )
        assertFalse(
            ShizukuLaunchPolicy.isAdmitted(
                effectiveUid = 2_000,
                callerUid = 20_123,
                packageUid = packageUid,
                nativeDirectory = nativeDir,
                guardPath = guardPath,
                activeCount = 0,
            ),
        )
        assertFalse(
            ShizukuLaunchPolicy.isAdmitted(
                effectiveUid = 2_000,
                callerUid = packageUid,
                packageUid = packageUid,
                nativeDirectory = nativeDir,
                guardPath = "/tmp/${ShizukuLaunchPolicy.GUARD_NAME}",
                activeCount = 0,
            ),
        )
    }

    @Test
    fun onlyVerifiedShellIdentityProjectsAnAvailableCapability() {
        val shell = ShizukuCapabilityProjector.project(
            ShizukuObservation(
                managerInstalled = true,
                binderAlive = true,
                authorized = true,
                userServiceUid = 2_000,
                connecting = false,
            ),
        )
        val appIdentity = ShizukuCapabilityProjector.project(
            ShizukuObservation(
                managerInstalled = true,
                binderAlive = true,
                authorized = true,
                userServiceUid = 10_123,
                connecting = false,
            ),
        )

        assertEquals(ShizukuProviderState.Connected, shell.providerState)
        assertEquals(RegisteredCapabilityState.Available, shell.registrationState)
        assertTrue(shell.exposesExecutor)
        assertEquals(ShizukuProviderState.IncompatibleIdentity, appIdentity.providerState)
        assertEquals(RegisteredCapabilityState.Unavailable, appIdentity.registrationState)
        assertFalse(appIdentity.exposesExecutor)
    }
}
