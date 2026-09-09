import java.util.Properties
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
}

val backendUrl = providers.gradleProperty("zisee.backendUrl").orElse("").get()
val localBackend = providers.gradleProperty("zisee.localBackend").orElse("false").get().toBooleanStrict()
require(backendUrl.isEmpty() || backendUrl.matches(Regex("https://[A-Za-z0-9.-]+(:[0-9]+)?/?"))) {
    "zisee.backendUrl must be an HTTPS origin without credentials, path or query"
}

android {
    namespace = "com.lazydoglab.zisee"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lazydoglab.zisee"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-dev"
        testInstrumentationRunner = "com.lazydoglab.zisee.rtc.RtcSmokeInstrumentation"
        buildConfigField("String", "BACKEND_URL", "\"$backendUrl\"")
    }
    signingConfigs {
        // Checked into the repo on purpose: every developer and every CI run must
        // produce debug APKs with the same signature, so builds stay upgrade-installable
        // across machines. Debug only — release signing never uses this key.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".dev"
            signingConfig = signingConfigs.getByName("debug")
            if (localBackend) buildConfigField("String", "BACKEND_URL", "\"http://127.0.0.1:8080\"")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint { abortOnError = true }
}

dependencies {
    implementation(libs.arcore)
    implementation(libs.webrtc)
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.preview)
    debugImplementation(libs.compose.tooling)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.datastore.preferences)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.play.services)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    testImplementation(libs.junit)
    implementation(libs.okhttp)
    implementation(libs.zxing)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.json.test)
    testImplementation(libs.coroutines.test)
}

// Committed native artifacts keep ordinary Android builds independent of Rust/WSL.
// Fail if source/model/binaries drift; rebuild with audio-native/build.py to refresh the manifest.
val verifyAudioNative by tasks.registering {
    val manifest = rootProject.file("audio-native/artifacts.properties")
    inputs.file(manifest)
    doLast {
        val entries = Properties().apply { manifest.inputStream().use { load(it) } }
        entries.forEach { path, checksum ->
            val file = rootProject.file(path.toString())
            check(file.isFile) { "Missing audio artifact: $path. See audio-native/README.md" }
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            check(actual == checksum) { "Audio artifact changed: $path. Rebuild with audio-native/build.py" }
        }
    }
}
tasks.named("preBuild") { dependsOn(verifyAudioNative) }
