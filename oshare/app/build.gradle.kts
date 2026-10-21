plugins {
    id("com.android.application")
}

android {
    namespace = "com.tlsu.oshareairdrop"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.tlsu.oshareairdrop"
        minSdk = 35
        targetSdk = 37
        versionCode = 10
        versionName = "0.10-fixed149-discovery"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
}
