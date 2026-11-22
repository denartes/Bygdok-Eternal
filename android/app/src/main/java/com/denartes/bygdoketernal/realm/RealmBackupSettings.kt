package com.denartes.bygdoketernal.realm

import android.content.Context
import org.json.JSONObject

internal class RealmBackupSettings(private val context: Context) {
    fun read(): JSONObject {
        val setup = context.getSharedPreferences("realm-setup", Context.MODE_PRIVATE)
        val network = RealmNetworkSettingsStore(context).load()
        return JSONObject()
            .put("setup_complete", setup.getBoolean("setup-complete", false))
            .put("realm_name", setup.getString("realm-name", "AzerothCore"))
            .put("realm_address", network.realmAddress)
            .put("local_address", network.localAddress)
    }

    fun validate(settings: JSONObject) {
        require(settings.get("setup_complete") is Boolean &&
            settings.get("realm_name") is String &&
            settings.get("realm_address") is String &&
            settings.get("local_address") is String) { "Invalid backup app settings" }
        require(isValidRealmName(settings.getString("realm_name"))) { "Invalid backup realm name" }
        for (key in listOf("realm_address", "local_address")) {
            val value = settings.getString(key)
            require(value.isEmpty() || isValidRealmAddress(value)) { "Invalid backup network address" }
        }
    }

    fun write(settings: JSONObject) {
        validate(settings)
        check(context.getSharedPreferences("realm-setup", Context.MODE_PRIVATE).edit().clear()
            .putBoolean("setup-complete", settings.getBoolean("setup_complete"))
            .putString("realm-name", settings.getString("realm_name")).commit()) { "Could not restore setup settings" }
        check(context.getSharedPreferences("realm-network-settings", Context.MODE_PRIVATE).edit().clear()
            .putString("realm-address", settings.getString("realm_address"))
            .putString("local-address", settings.getString("local_address")).commit()) { "Could not restore network settings" }
        RealmRuntime.setNetworkSettings(RealmNetworkSettingsStore(context).load())
        val setup = RealmSetupStore(context)
        RealmRuntime.setSetupComplete(setup.isComplete(), setup.realmName())
    }
}
