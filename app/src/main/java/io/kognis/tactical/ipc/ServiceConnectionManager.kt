package io.kognis.tactical.ipc

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import io.kognis.tactical.core.IFieldCallback
import io.kognis.tactical.core.IFieldCore
import io.kognis.tactical.core.FieldAssistantService

/**
 * Manages AIDL bind/unbind lifecycle for FieldAssistantService.
 * Separates service plumbing from Activity UI code.
 */
class ServiceConnectionManager(
    private val context: Context,
    private val onConnected: (IFieldCore) -> Unit,
    private val onDisconnected: () -> Unit
) {
    private val TAG = "ServiceConn"

    var core: IFieldCore? = null
        private set

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            Log.d(TAG, "onServiceConnected")
            val proxy = IFieldCore.Stub.asInterface(binder)
            core = proxy
            onConnected(proxy)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "onServiceDisconnected — service crashed or killed")
            core = null
            onDisconnected()
        }
    }

    fun bind(callback: IFieldCallback) {
        val intent = Intent(context, FieldAssistantService::class.java)
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        Log.d(TAG, "bindService called")
    }

    fun unbind() {
        try {
            context.unbindService(connection)
            core = null
            Log.d(TAG, "unbindService called")
        } catch (e: Exception) {
            Log.w(TAG, "unbindService failed: ${e.message}")
        }
    }
}
