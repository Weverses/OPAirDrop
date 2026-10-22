import java.util.Properties

plugins {
    id("com.android.application")
}

val moduleProperties = Properties().apply {
    rootProject.file("../module/module.prop").reader(Charsets.UTF_8).use { load(it) }
}
val signingProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
fun signingValue(name: String): String? = signingProperties.getProperty(name) ?: System.getenv(name)

android {
    namespace = "com.tlsu.oshareairdrop"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.tlsu.oshareairdrop"
        minSdk = 35
        targetSdk = 37
        versionCode = providers.gradleProperty("opairshareVersionCode").orNull?.toInt()
            ?: moduleProperties.getProperty("versionCode").toInt()
        versionName = providers.gradleProperty("opairshareVersionName").orNull
            ?: moduleProperties.getProperty("version")
    }

    buildFeatures { buildConfig = true }

    signingConfigs {
        create("release") {
            signingValue("KEYSTORE_PATH")?.let { storeFile = file(it) }
            storePassword = signingValue("KEYSTORE_PASS")
            keyAlias = signingValue("KEY_ALIAS")
            keyPassword = signingValue("KEY_PASSWORD")
            enableV3Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isDebuggable = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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
