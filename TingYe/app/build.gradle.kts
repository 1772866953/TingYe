import java.util.Properties
import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.kapt")
}
val releaseCredentials = Properties().apply {
    val credentialsFile = providers.environmentVariable("TINGYE_SIGNING_PROPERTIES")
        .orNull?.let { File(it) } ?: rootProject.file("../signing/release.properties")
    if (credentialsFile.isFile) credentialsFile.inputStream().use { load(it) }
}
android {
    namespace = "com.tingye.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.tingye.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 9
        versionName = "0.1.8"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        getByName("debug") {
            val localDebugKeystore = rootProject.file("../signing/debug.keystore")
            if (localDebugKeystore.isFile) storeFile = localDebugKeystore
        }
        create("release") {
            storeFile = releaseCredentials.getProperty("storeFile")?.let { rootProject.file(it) }
            storePassword = releaseCredentials.getProperty("storePassword")
            keyAlias = releaseCredentials.getProperty("keyAlias")
            keyPassword = releaseCredentials.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    // Unminified production code for recording real critical-path ART profiles.
    buildTypes.create("profile") {
        initWith(buildTypes.getByName("release"))
        isMinifyEnabled = false
        isShrinkResources = false
        signingConfig = signingConfigs.getByName("debug")
        matchingFallbacks += "release"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}
kapt { arguments { arg("room.schemaLocation", "$projectDir/schemas") } }
dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.02.00")
    implementation(composeBom)
    implementation("androidx.profileinstaller:profileinstaller:1.4.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.media3:media3-exoplayer:1.10.0")
    implementation("androidx.media3:media3-session:1.10.0")
    implementation("androidx.media3:media3-ui:1.10.0")
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    kapt("androidx.room:room-compiler:2.8.5")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
