plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.github.loganzarov_alt.d325fsdwriter"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.loganzarov_alt.d325fsdwriter"
        minSdk = 25
        targetSdk = 34
        versionCode = 2
        versionName = "0.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val publicationSigning = System.getenv("D325F_KEYSTORE")?.let { keyPath ->
        signingConfigs.create("publication") {
            storeFile = file(keyPath)
            storePassword = requireNotNull(System.getenv("D325F_STORE_PASSWORD"))
            keyAlias = requireNotNull(System.getenv("D325F_KEY_ALIAS"))
            keyPassword = requireNotNull(System.getenv("D325F_KEY_PASSWORD"))
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = publicationSigning ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    testImplementation(libs.junit)
}
