plugins {
    id("com.android.application")
}

android {
    namespace = "es.grupomds.mdsprint"
    compileSdk = 36

    defaultConfig {
        applicationId = "es.grupomds.mdsprint"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "2.1.0-smart-ipp-pwg"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}
