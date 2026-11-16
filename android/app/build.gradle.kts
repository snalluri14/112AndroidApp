import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

fun loadProps(name: String) = Properties().apply {
    val f = rootProject.file(name)
    if (f.exists()) f.inputStream().use { load(it) }
}

val localProps = loadProps("local.properties")
val keystoreProps = loadProps("keystore.properties")
fun local(key: String, default: String) = localProps.getProperty(key)?.takeIf { it.isNotBlank() } ?: default

android {
    namespace = "com.ambulance.dispatch"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ambulance.dispatch"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        manifestPlaceholders["MAPS_API_KEY"] = local("MAPS_API_KEY", "")
        buildConfigField("String", "SERVER_URL", "\"${local("SERVER_URL", "http://192.168.1.10:8000")}\"")
        buildConfigField("String", "ADMIN_PIN", "\"${local("ADMIN_PIN", "1234")}\"")
    }

    // One keystore signs both debug and release builds, so the Google Maps key needs only one SHA-1.
    val hasKeystore = keystoreProps.getProperty("storeFile") != null
    signingConfigs {
        if (hasKeystore) {
            create("shared") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            manifestPlaceholders["usesCleartext"] = "true"
            if (hasKeystore) signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = false
            manifestPlaceholders["usesCleartext"] = local("ALLOW_HTTP", "false")
            if (hasKeystore) signingConfig = signingConfigs.getByName("shared")
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
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("com.google.maps.android:maps-compose:6.4.0")
    implementation("com.google.android.gms:play-services-maps:19.0.0")
}
