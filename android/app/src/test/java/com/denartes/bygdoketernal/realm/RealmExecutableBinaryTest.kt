package com.denartes.bygdoketernal.realm

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class RealmExecutableBinaryTest {
    @Test
    fun nativeLibraryPath_usesAndroidExtractedLibraryDir() {
        val nativeLibraryDir = "/data/app/~~abc/lib/arm64-v8a"
        val name = "libauthserver.so"

        val expected = File(nativeLibraryDir, name).absolutePath
        val actual = RealmExecutableBinary.nativeLibraryPath(nativeLibraryDir, name)

        assertEquals(expected, actual)
    }
}
