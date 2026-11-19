package com.denartes.bygdoketernal.realm

import java.io.File

object RealmExecutableBinary {
    fun nativeLibraryPath(nativeLibraryDir: String, name: String): String {
        return File(nativeLibraryDir, name).absolutePath
    }
}
