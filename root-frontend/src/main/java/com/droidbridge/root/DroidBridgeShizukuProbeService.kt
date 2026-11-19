package com.droidbridge.root

import android.system.Os

/** A narrow UserService that verifies the real execution identity without exposing shell access. */
class DroidBridgeShizukuProbeService : IShizukuProbe.Stub() {
    override fun getUid(): Int = Os.getuid()
}
