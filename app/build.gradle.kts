plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.android.devtools)
    alias(libs.plugins.dagger.hilt)
}

android {
    namespace = "com.mantz_it.rfanalyzer"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.loganzarov_alt.d325fsdr"
        minSdk = 25
        targetSdk = 34
        versionCode = 2
        versionName = "1.0.1"

        ndk {
            abiFilters += listOf("armeabi-v7a")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs.getByName("debug") {
        System.getenv("LG_SIGNING_KEY")?.let { storeFile = file(it) }
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
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    buildFeatures {
        compose = true
        buildConfig = true
    }

    useLibrary("android.test.base")
    useLibrary("android.test.runner")
    useLibrary("android.test.mock")
}

// Build static site from /docs and make it available in the app's assets:
tasks.register<Exec>("generateDocs") {
    description = "Builds the MkDocs static website"
    group = "documentation"
    workingDir = file("$projectDir/../")
    commandLine("mkdocs", "build", "--clean", "--no-directory-urls", "--site-dir", "build_site")
}
tasks.register<Copy>("copyDocsToAssets") {
    dependsOn("generateDocs")
    from("$projectDir/../build_site") {
        exclude("sitemap.xml.gz")  // Exclude the gz file as it causes problems when merging assets
    }
    into("$projectDir/src/main/assets/docs")
}
tasks.named("preBuild") {
    if (providers.gradleProperty("generateManual").orNull == "true") dependsOn("copyDocsToAssets")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.localbroadcastmanager)
    implementation(project(":nativedsp"))
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.foundation.android)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.material3.android)
    implementation(libs.androidx.datastore.core.android)
    implementation(libs.androidx.datastore.preferences)
    annotationProcessor(libs.androidx.room.compiler)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.common)
    implementation(libs.androidx.room.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    debugImplementation(libs.androidx.ui.tooling)
    implementation(libs.androidx.security.crypto)
    implementation(libs.dagger.hilt)
    ksp(libs.dagger.hilt.compiler)
}

// Every project-owned native asset is built from C/ASM; no base ELF is used.
android.sourceSets.getByName("main") {
    assets.srcDir(layout.buildDirectory.dir("generated/native-assets"))
    java.srcDir(layout.buildDirectory.dir("generated/source/nativeHelpers"))
}
val buildNativeHelpers by tasks.registering(Exec::class) {
    workingDir = rootProject.projectDir
    environment("ANDROID_NDK_HOME", android.sdkDirectory.resolve("ndk/27.2.12479018").absolutePath)
    commandLine("python3", "native/build_helpers.py")
}
tasks.named("preBuild") { dependsOn(buildNativeHelpers) }
