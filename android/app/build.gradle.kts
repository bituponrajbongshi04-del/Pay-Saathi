plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "in.paysaathi.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "in.paysaathi.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.0.2"
        buildConfigField("String", "SUPABASE_URL", "\"https://hhqcycwfvxoxjizlbivv.supabase.co\"")
        buildConfigField("String", "SUPABASE_KEY", "\"sb_publishable_NdrUA-ubf67oW5hiS-Yw4A_-r2xbJRV\"")
    }
    signingConfigs {
        create("release") {
            val keyFile = System.getenv("PAYSAATHI_KEYSTORE_PATH")
            if (!keyFile.isNullOrBlank()) {
                storeFile = file(keyFile)
                storePassword = System.getenv("PAYSAATHI_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("PAYSAATHI_KEY_ALIAS")
                keyPassword = System.getenv("PAYSAATHI_KEY_PASSWORD")
            }
        }
    }
    buildTypes { getByName("release") { if (!System.getenv("PAYSAATHI_KEYSTORE_PATH").isNullOrBlank()) signingConfig = signingConfigs.getByName("release") } }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
