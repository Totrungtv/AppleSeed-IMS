plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "vn.appleseed.volte"
    compileSdk { version = release(37) }
    defaultConfig {
        applicationId = "vn.appleseed.volte"
        minSdk = 23
        targetSdk = 35
        versionCode = 11
        versionName = "1.1.9"
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.activity:activity-compose:1.12.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("com.anggrayudi:android-hidden-api:35.0")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
