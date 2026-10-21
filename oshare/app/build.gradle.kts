import java.util.Properties

plugins {
    id("com.android.application")
}

val moduleProperties = Properties().apply {
    rootProject.file("../module/module.prop").reader(Charsets.UTF_8).use { load(it) }
}

android {
    namespace = "com.tlsu.oshareairdrop"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.tlsu.oshareairdrop"
        minSdk = 35
        targetSdk = 37
        versionCode = moduleProperties.getProperty("versionCode").toInt()
        versionName = moduleProperties.getProperty("version")
    }

    buildFeatures { buildConfig = true }

    buildTypes {
        release {
            isMinifyEnabled = false
            isDebuggable = false
            // Existing deployments use this identity; retain it for in-place upgrades.
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs.useLegacyPackaging = false
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("org.luckypray:dexkit:2.3.0")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.21")
}
