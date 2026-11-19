package com.droidbridge.root

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.system.Os

/**
 * A narrow Shizuku UserService that verifies the actual process identity. It exposes one Binder
 * transaction only; it does not provide a shell, command runner, or filesystem interface.
 */
class DroidBridgeShizukuProbeService : Binder() {
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == INTERFACE_TRANSACTION) {
            reply?.writeString(ShizukuProbeProtocol.DESCRIPTOR)
            return true
        }
        if (code != ShizukuProbeProtocol.TRANSACTION_GET_UID) return super.onTransact(code, data, reply, flags)
        data.enforceInterface(ShizukuProbeProtocol.DESCRIPTOR)
        reply?.writeNoException()
        reply?.writeInt(Os.getuid())
        return true
    }
}

internal object ShizukuProbeProtocol {
    const val DESCRIPTOR = "com.droidbridge.root.IShizukuProbe"
    const val TRANSACTION_GET_UID = IBinder.FIRST_CALL_TRANSACTION
}
