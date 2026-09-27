import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val generatedModuleConfigAssetsDir = layout.buildDirectory.dir("generated/module-config-assets")

android {
    namespace = "com.denartes.bygdoketernal"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.denartes.bygdoketernal"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets {
        getByName("main") {
            // The canonical authserver/worldserver config templates live at
            // the repository root; expose them as assets without copying.
            assets.srcDirs("src/main/assets", "../../configs")
            assets.srcDir(generatedModuleConfigAssetsDir)
        }
    }

    packaging {
        // The embedded native binaries are ELF executables, not real shared
        // libraries; keep their original bytes uncompressed and unmodified.
        jniLibs.useLegacyPackaging = true
    }
}

// Stages the compiled server-runtime artifact (authserver, worldserver,
// mariadbd, and their .so dependencies) into jniLibs so Android installs
// them into the app's executable nativeLibraryDir. Renamed to the
// lib<name>.so convention because only files named that way, under
// jniLibs/<abi>/, are extracted to a location the OS will execute from
// (Android blocks executing arbitrary files from app-writable storage).
// The application is self-contained; do not permit dashboard-only APKs.
val nativeRuntimeDir = file("../../runtime/build/bygdok-runtime-arm64")
val mariaDbRuntimeDir = file("../../runtime/build/mariadb-arm64")
val jniLibsDir = file("src/main/jniLibs/arm64-v8a")
val assetsSqlDir = file("src/main/assets/sql")
val assetsMariaDbShareDir = file("src/main/assets/mariadb-share")
val assetsMariaDbLibDir = file("src/main/assets/mariadb-libs")
val repoModuleConfigDirs = file("../../modules").listFiles()
    ?.map { File(it, "conf") }
    ?.filter { it.isDirectory }
    .orEmpty()

tasks.register("stageModuleConfigTemplates") {
    repoModuleConfigDirs.forEach { inputs.dir(it) }
    inputs.dir(file("../../configs/modules"))
    outputs.dir(generatedModuleConfigAssetsDir)

    doLast {
        val modulesAssetDir = generatedModuleConfigAssetsDir.get().file("modules").asFile
        modulesAssetDir.deleteRecursively()
        modulesAssetDir.mkdirs()

        repoModuleConfigDirs.forEach { configDir ->
            configDir.listFiles { candidate ->
                candidate.isFile && candidate.name.endsWith(".conf.dist")
            }?.forEach { template ->
                val canonicalTemplate = file("../../configs/modules/${template.name}")
                if (!canonicalTemplate.exists()) {
                    template.copyTo(File(modulesAssetDir, template.name), overwrite = true)
                }
            }
        }
    }
}

tasks.register("embedNativeRuntime") {
    if (nativeRuntimeDir.exists()) {
        inputs.dir(nativeRuntimeDir)
    }
    if (mariaDbRuntimeDir.exists()) {
        inputs.dir(mariaDbRuntimeDir)
    }
    outputs.dir(jniLibsDir)
    outputs.dir(assetsSqlDir)
    outputs.dir(assetsMariaDbShareDir)
    outputs.dir(assetsMariaDbLibDir)

    doLast {
        if (!nativeRuntimeDir.exists() && !mariaDbRuntimeDir.exists()) {
            error("[embedNativeRuntime] Complete runtime artifact missing at $nativeRuntimeDir")
        }

        jniLibsDir.deleteRecursively()
        assetsSqlDir.deleteRecursively()
        assetsMariaDbShareDir.deleteRecursively()
        assetsMariaDbLibDir.deleteRecursively()
        jniLibsDir.mkdirs()

        if (nativeRuntimeDir.exists()) {
            val serverExecutables = mapOf(
                "authserver" to "libauthserver.so",
                "worldserver" to "libworldserver.so"
            )
            val binDir = File(nativeRuntimeDir, "bin")
            serverExecutables.forEach { (sourceName, destName) ->
                val sourceFile = File(binDir, sourceName)
                if (sourceFile.exists()) {
                    sourceFile.copyTo(File(jniLibsDir, destName), overwrite = true)
                }
            }
        }

        val databaseRuntimeDir = if (mariaDbRuntimeDir.exists()) mariaDbRuntimeDir else nativeRuntimeDir
        mapOf(
            "mariadbd" to "libpocket_mariadbd.so",
            "mariadb" to "libpocket_mariadb_client.so",
            "mariadb_client" to "libpocket_mariadb_client.so"
        ).forEach { (sourceName, destName) ->
            val sourceFile = File(databaseRuntimeDir, "bin/$sourceName")
            if (sourceFile.exists()) {
                sourceFile.copyTo(File(jniLibsDir, destName), overwrite = true)
            }
        }

        listOf(File(nativeRuntimeDir, "lib"), File(mariaDbRuntimeDir, "lib"))
            .filter { it.exists() }
            .forEach { libDir ->
                libDir.listFiles { candidate -> candidate.name.contains(".so") }?.forEach { library ->
                    library.copyTo(File(jniLibsDir, library.name), overwrite = true)
                }
            }

        val sqlDir = File(nativeRuntimeDir, "sql")
        if (sqlDir.exists()) {
            copyRuntimeTree(sqlDir, assetsSqlDir)

            val legacyPlayerbotsSqlDir = File(assetsSqlDir, "modules/mod-playerbots/sql")
            val playerbotsSqlDir = File(assetsSqlDir, "modules/mod-playerbots/data/sql")
            if (File(legacyPlayerbotsSqlDir, "playerbots/base").isDirectory && !playerbotsSqlDir.exists()) {
                legacyPlayerbotsSqlDir.copyRecursively(playerbotsSqlDir, overwrite = true)
                legacyPlayerbotsSqlDir.deleteRecursively()
            }
        }

        val mariaDbShareDir = File(databaseRuntimeDir, "share")
        if (mariaDbShareDir.exists()) {
            copyRuntimeTree(mariaDbShareDir, assetsMariaDbShareDir)
        }

        val providerLibDir = File(nativeRuntimeDir, "lib")
        if (providerLibDir.exists()) {
            copyRuntimeTree(providerLibDir, assetsMariaDbLibDir)
        }

        logger.lifecycle("[embedNativeRuntime] Embedded native runtime from $nativeRuntimeDir")
    }
}

fun copyRuntimeTree(source: File, destination: File) {
    source.walkTopDown().forEach { sourceFile ->
        val target = File(destination, sourceFile.relativeTo(source).path)
        if (sourceFile.isDirectory) {
            target.mkdirs()
        } else {
            target.parentFile?.mkdirs()
            sourceFile.copyTo(target, overwrite = true)
        }
    }
}

tasks.named("preBuild") {
    dependsOn("embedNativeRuntime")
    dependsOn("stageModuleConfigTemplates")
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
}