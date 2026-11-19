package com.droidbridge.root

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.droidbridge.ui.client.CapabilityAction
import com.droidbridge.ui.client.CapabilityRow
import com.droidbridge.ui.client.CapabilityRowKey
import com.droidbridge.ui.client.CapabilityRowState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku
import java.util.concurrent.atomic.AtomicLong

/**
 * Shizuku discovery, permission request and UserService identity handshake for the Root edition.
 * This is a probe-only bridge: the Magisk daemon remains the active Runtime host until its
 * execution surface is explicitly migrated to the Shizuku provider.
 */
class ShizukuBackendController(context: Context) {
    private val app = context.applicationContext
    private val mutableRow = MutableStateFlow(CapabilityRow(CapabilityRowKey.Shizuku, CapabilityRowState.Unknown, CapabilityAction.Recheck))
    val row: StateFlow<CapabilityRow> = mutableRow.asStateFlow()
    private var started = false
    private var connection: ServiceConnection? = null
    private var args: Shizuku.UserServiceArgs? = null
    private val generation = AtomicLong(0)

    private val binderReceived = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDead = Shizuku.OnBinderDeadListener { disconnect(); refresh() }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
        if (requestCode == PERMISSION_REQUEST_CODE) refresh()
    }

    @Synchronized fun start() {
        if (started) return
        started = true
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
        refresh()
    }

    @Synchronized fun stop() {
        if (!started) return
        started = false
        disconnect()
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
    }

    fun recheck() { synchronized(this) { disconnect(); refresh() } }

    fun requestAuthorization(): Boolean {
        if (!managerInstalled() || !runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            refresh(); return false
        }
        if (runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)) {
            refresh(); return true
        }
        return runCatching { Shizuku.requestPermission(PERMISSION_REQUEST_CODE); true }.getOrDefault(false)
    }

    fun openManager(): Boolean {
        val packageName = when {
            installed(LEGACY_PACKAGE) -> LEGACY_PACKAGE
            installed(PLUS_PACKAGE) -> PLUS_PACKAGE
            else -> return false
        }
        val intent = app.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { app.startActivity(intent); true }.getOrDefault(false)
    }

    @Synchronized private fun refresh() {
        if (!started) return
        val legacy = installed(LEGACY_PACKAGE)
        val plus = installed(PLUS_PACKAGE)
        if (!legacy && !plus) { disconnect(); publish(CapabilityRowState.NotInstalled, CapabilityAction.InstallShizuku); return }
        if (!legacy && plus) { disconnect(); publish(CapabilityRowState.CompatRequired, CapabilityAction.OpenShizuku); return }
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            disconnect(); publish(CapabilityRowState.NotRunning, CapabilityAction.OpenShizuku); return
        }
        val authorized = runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
        if (!authorized) { disconnect(); publish(CapabilityRowState.NotAuthorized, CapabilityAction.Authorize); return }
        if (connection != null) {
            if (mutableRow.value.state != CapabilityRowState.Connected) publish(CapabilityRowState.Connecting, null)
            return
        }
        bindProbe()
    }

    @Synchronized private fun bindProbe() {
        val currentGeneration = generation.incrementAndGet()
        val serviceArgs = Shizuku.UserServiceArgs(ComponentName(app, DroidBridgeShizukuProbeService::class.java))
            .daemon(false).processNameSuffix("droidbridge_probe").tag(USER_SERVICE_TAG).version(USER_SERVICE_VERSION)
        val serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                val uid = runCatching { IShizukuProbe.Stub.asInterface(binder).uid }.getOrNull()
                synchronized(this@ShizukuBackendController) {
                    if (currentGeneration != generation.get() || connection !== this) return
                    when (uid) {
                        0, SHELL_UID -> publish(CapabilityRowState.Connected, null)
                        null -> publish(CapabilityRowState.NotRunning, CapabilityAction.Recheck)
                        else -> publish(CapabilityRowState.IncompatibleIdentity, CapabilityAction.Recheck)
                    }
                }
            }
            override fun onServiceDisconnected(name: ComponentName) = onProbeDisconnected(currentGeneration)
            override fun onBindingDied(name: ComponentName) = onProbeDisconnected(currentGeneration)
            override fun onNullBinding(name: ComponentName) = onProbeDisconnected(currentGeneration)
        }
        args = serviceArgs; connection = serviceConnection
        publish(CapabilityRowState.Connecting, null)
        runCatching { Shizuku.bindUserService(serviceArgs, serviceConnection) }.onFailure {
            if (currentGeneration == generation.get()) { connection = null; args = null; publish(CapabilityRowState.NotRunning, CapabilityAction.Recheck) }
        }
    }

    @Synchronized private fun onProbeDisconnected(expectedGeneration: Long) {
        if (expectedGeneration != generation.get()) return
        connection = null; args = null
        publish(CapabilityRowState.NotRunning, CapabilityAction.Recheck)
    }

    @Synchronized private fun disconnect() {
        generation.incrementAndGet()
        val oldArgs = args; val oldConnection = connection
        args = null; connection = null
        if (oldArgs != null && oldConnection != null) runCatching { Shizuku.unbindUserService(oldArgs, oldConnection, true) }
    }

    private fun publish(state: CapabilityRowState, action: CapabilityAction?) {
        mutableRow.value = CapabilityRow(CapabilityRowKey.Shizuku, state, action)
    }

    private fun installed(packageName: String): Boolean = runCatching {
        app.packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0)); true
    }.getOrDefault(false)
    private fun managerInstalled() = installed(LEGACY_PACKAGE) || installed(PLUS_PACKAGE)

    private companion object {
        const val LEGACY_PACKAGE = "moe.shizuku.privileged.api"
        const val PLUS_PACKAGE = "af.shizuku.plus.api"
        const val SHELL_UID = 2000
        const val PERMISSION_REQUEST_CODE = 4107
        const val USER_SERVICE_TAG = "droidbridge-root-shizuku-probe"
        const val USER_SERVICE_VERSION = 1
    }
}
