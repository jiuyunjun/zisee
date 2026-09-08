plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val backendUrl = providers.gradleProperty("zisee.backendUrl").orElse("").get()
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
    buildTypes {
        debug { applicationIdSuffix = ".debug" }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
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
