import java.util.Properties

plugins {
    id("com.android.application")
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.isFile) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val hasReleaseSigning = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    .all { !keystoreProperties.getProperty(it).isNullOrBlank() }

android {
    namespace = "app.wireroute.release263"
    compileSdk = 36
    buildFeatures { buildConfig = true }

    defaultConfig {
        applicationId = "app.wireroute.release263"
        if (project.hasProperty("usbTest")) { applicationIdSuffix = ".usbtest" }
        if (project.hasProperty("uiTest")) { applicationIdSuffix = ".uitest" }
        minSdk = 24
        targetSdk = 36
        val stationRouteRollback = project.hasProperty("stationRouteRollback")
        versionCode = if (stationRouteRollback) 322 else 323
        versionName = if (stationRouteRollback) "3.1.12-route-rollback" else "3.1.13-usb-handover"
        buildConfigField("boolean", "STATION_ROUTE_SUBTRACTION_ENABLED", (!stationRouteRollback).toString())

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Keep the previously supported architectures. Java GoBackend comes
        // from the official AAR; the local native adapter preserves its ABI.
        ndk { abiFilters.addAll(listOf("arm64-v8a", "x86_64")) }

    }

    signingConfigs {
        if (hasReleaseSigning) create("release") {
            storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
            storePassword = keystoreProperties.getProperty("storePassword")
            keyAlias = keystoreProperties.getProperty("keyAlias")
            keyPassword = keystoreProperties.getProperty("keyPassword")
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        release {
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    testBuildType = "release"
    packaging { jniLibs { pickFirsts += "**/libwg-go.so" } }

}

dependencies {
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    implementation("com.wireguard.android:tunnel:1.0.20260102")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("junit:junit:4.13.2")
}
