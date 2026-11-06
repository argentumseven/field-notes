package org.fieldnotes.app

import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.launch

/** Observe Android's permitted default network. Never bind around VPN/lockdown,
 * create a VPN service, alter routes, or replace the enrolled phone identity. */
internal class DefaultNetworkMonitor(private val app:FieldNotesApplication):ConnectivityManager.NetworkCallback() {
    @Volatile private var current:Network?=null
    override fun onAvailable(network:Network) {
        if(current==network)return
        current=network
        app.scope.launch {
            app.initialized.await()
            if(current==network)app.model.launch{
                val prefs=app.model.state.value.cache.preferences
                app.model.networkChanged(refresh=app.uiVisible||prefs.liveUpdates||prefs.backgroundSync)
            }
        }
    }
    override fun onLost(network:Network){if(current==network)current=null}
    fun start() {
        try{app.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(this)}
        catch(_:Exception){app.deliveryError.value="Network-change monitoring is unavailable. Open-app refresh and manual sync remain available."}
    }
}
