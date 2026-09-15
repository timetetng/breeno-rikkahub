plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.timetetng.breeno.bridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.timetetng.breeno.bridge"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/*.kotlin_module",
            "META-INF/*.version",
        )
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    // Classic XposedBridge API, compile-time only — the framework provides it at runtime.
    compileOnly(files("${rootProject.projectDir}/libs/api-82.jar"))
}
