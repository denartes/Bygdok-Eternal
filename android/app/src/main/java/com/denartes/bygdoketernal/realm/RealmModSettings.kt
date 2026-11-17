package com.denartes.bygdoketernal.realm

import android.content.Context
import java.io.File

fun realmModConfigStore(context: Context): RealmModConfigStore = RealmModConfigStore(
    File(context.filesDir, "mod-settings")
) { mod ->
    val assets = context.assets
    val files = assets.list("modules").orEmpty()
    val template = "${mod.configFile}.dist"
    check(template in files) { "No bundled configuration template for ${mod.title}" }
    val baseline = assets.open("modules/$template").bufferedReader().use { it.readText() }
    val projectDefaults = if (mod.configFile in files) {
        assets.open("modules/${mod.configFile}").bufferedReader().use { it.readText() }
    } else {
        null
    }
    RealmModConfig.merge(baseline, projectDefaults)
}
