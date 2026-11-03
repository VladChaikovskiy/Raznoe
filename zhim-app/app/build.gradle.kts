plugins {
    id("com.android.application")
}

android {
    namespace = "com.raznoe.zhim"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.raznoe.zhim"
        minSdk = 26
        targetSdk = 34
        versionCode = 5
        versionName = "1.4"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}
