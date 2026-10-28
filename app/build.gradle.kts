plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.glass.launcher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.glass.launcher"
        minSdk = 21
        targetSdk = 34
        versionCode = 6
        versionName = "3.3"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so the release APK can be sideloaded straight away.
            signingConfig = signingConfigs.getByName("debug")
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
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/versions/**")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.palette:palette:1.0.0")
    // Talks to the TV's own ADB daemon for debloating / setting the default Home app.
    implementation("dev.mobile:dadb:1.2.10")
}
