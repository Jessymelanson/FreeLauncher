import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * Real signing details, if they exist.
 *
 * Looked for in `~/JApps-Signing/keystore.properties` first -- the same key the
 * five JApps sign with, despite this not being one of them. One key per author
 * rather than one per app, because the failure that actually matters here is
 * losing a keystore: every extra key is another thing whose loss would strand
 * an installed app with no way to ever update it. Nothing is shared in the
 * other direction -- this app has no LicenceProvider and no activation.
 *
 * A copy in the project root still wins if this is a fresh clone somewhere
 * else. Absent both, the build falls back to the debug key.
 */
val releaseKeystore = Properties().apply {
    val shared = rootProject.file(
        System.getProperty("user.home") + "/JApps-Signing/keystore.properties")
    val local = rootProject.file("keystore.properties")
    val file = if (shared.exists()) shared else local
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.freelauncher.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.freelauncher.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        // The fallback, so `assembleRelease` works on a fresh clone with nothing
        // set up. A launcher is something you live in, and a debug build is the
        // wrong thing to live in -- ART holds back optimisation on a debuggable
        // APK, which is exactly the stutter you would blame the launcher for.
        create("sideload") {
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }

        if (releaseKeystore.containsKey("storeFile")) {
            create("release") {
                storeFile = rootProject.file(releaseKeystore.getProperty("storeFile"))
                storePassword = releaseKeystore.getProperty("storePassword")
                keyAlias = releaseKeystore.getProperty("keyAlias")
                keyPassword = releaseKeystore.getProperty("keyPassword")

                // All three schemes, stated rather than inferred. AGP decides
                // v1 from minSdk and leaves it off at 26 -- right as far as
                // Android goes, which has preferred v2 since 7.0. But this is
                // sideloaded from a file, and the tools someone checks it with
                // can be older than their phone: jarsigner and a good few
                // file-manager installers read v1 and nothing else.
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            // Off deliberately. R8 strips the Compose-side reflection the widget
            // host and the Nova importer do not use, but it also needs keep rules
            // proving that -- and a launcher that fails to start is not something
            // you can back out of easily, because uninstalling it while it is the
            // default HOME leaves the phone with no home screen until reboot.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")

            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("sideload")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    // For LocalLifecycleOwner. The copy in compose-ui is deprecated as of 1.7
    // and this is where it moved to.
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // The widget host lives in the old View world -- there is no Compose
    // equivalent, because a widget is another process's RemoteViews and has to
    // be inflated into a real ViewGroup. AndroidView bridges the two.
    implementation("androidx.compose.ui:ui-viewbinding")

    testImplementation("junit:junit:4.13.2")
}
