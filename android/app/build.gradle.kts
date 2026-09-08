plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val backendUrl = providers.gradleProperty("zisee.backendUrl").orElse("").get()
val localBackend = providers.gradleProperty("zisee.localBackend").orElse("false").get().toBooleanStrict()
require(backendUrl.isEmpty() || backendUrl.matches(Regex("https://[A-Za-z0-9.-]+(:[0-9]+)?/?"))) {
    "zisee.backendUrl must be an HTTPS origin without credentials, path or query"
}

android {
    namespace = "com.zisee.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.zisee.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-dev"
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
            applicationIdSuffix = ".debug"
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
    implementation(libs.webrtc)
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
    testImplementation(libs.junit)
    implementation(libs.okhttp)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.json.test)
    testImplementation(libs.coroutines.test)
}
