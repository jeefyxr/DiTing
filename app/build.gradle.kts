plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.jeefyxr.diting"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.jeefyxr.diting"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        // The offline speech engine ships ~30 MB of native code per ABI; every phone since ~2017 is arm64.
        ndk { abiFilters += "arm64-v8a" }
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
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

tasks.withType<Test>().configureEach {
    // Robolectric downloads Android runtime jars itself; a mirror can be set in ~/.gradle/gradle.properties.
    providers.gradleProperty("robolectric.dependency.repo.url").orNull?.let {
        systemProperty("robolectric.dependency.repo.url", it)
    }
}

dependencies {
    implementation("com.k2fsa:sherpa-onnx:2.0.1@aar")
    // Only used to read .tar.bz2 voice model archives.
    implementation("org.apache.commons:commons-compress:1.21")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.17")
}
