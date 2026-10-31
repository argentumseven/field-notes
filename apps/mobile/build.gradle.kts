import org.jetbrains.kotlin.gradle.dsl.JvmTarget
plugins {
    id("org.jetbrains.kotlin.multiplatform") version "2.1.21"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.21"
    id("org.jetbrains.compose") version "1.8.2"
    id("com.android.application") version "8.10.1"
}
kotlin {
    androidTarget { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
    jvm("host") { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
    sourceSets {
        getByName("androidMain").kotlin.srcDir("src/jvmMain/kotlin")
        getByName("hostMain").kotlin.srcDir("src/jvmMain/kotlin")
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation("org.jetbrains.compose.material:material-icons-core:1.7.3")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.2")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }
        androidInstrumentedTest.dependencies {
            implementation("androidx.test:runner:1.6.2")
            implementation("androidx.test.ext:junit:1.2.1")
            implementation("androidx.compose.ui:ui-test-junit4:1.8.2")
            implementation("junit:junit:4.13.2")
        }
        androidMain.dependencies {
            implementation("androidx.activity:activity-compose:1.10.1")
            implementation("androidx.camera:camera-camera2:1.4.2")
            implementation("androidx.camera:camera-lifecycle:1.4.2")
            implementation("androidx.camera:camera-view:1.4.2")
            implementation("com.google.zxing:core:3.5.3")
            implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
            implementation("androidx.work:work-runtime-ktx:2.10.1")
        }
    }
}
android {
    namespace = "org.fieldnotes.app"
    compileSdk = 36
    ndkVersion = "28.2.13676358"
    defaultConfig { applicationId = "org.fieldnotes.app"; minSdk = 26; targetSdk = 36; versionCode = 3; versionName = "0.3.0"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"; ndk { abiFilters += (findProperty("fieldNotesAbis")?.toString()?.split(",") ?: listOf("arm64-v8a")) } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    sourceSets["main"].jniLibs.srcDir("src/androidMain/jniLibs")
    packaging { jniLibs.useLegacyPackaging = false; resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    signingConfigs {
        create("operator") {
            val location = System.getenv("FIELD_NOTES_KEYSTORE")
            if (location != null) {
                storeFile = file(location)
                storePassword = System.getenv("FIELD_NOTES_STORE_PASSWORD")
                keyAlias = System.getenv("FIELD_NOTES_KEY_ALIAS")
                keyPassword = System.getenv("FIELD_NOTES_KEY_PASSWORD")
            }
        }
    }
    buildTypes { getByName("release") { isMinifyEnabled = false; if (System.getenv("FIELD_NOTES_KEYSTORE") != null) signingConfig = signingConfigs.getByName("operator") } }
    lint { abortOnError = true }
}
dependencyLocking { lockAllConfigurations() }

// Exercise the exact JVM/Android JNI adapter against a disposable real server.
val hostUnit = tasks.named<Test>("hostTest") {
    exclude("**/NativeStackTest.class")
}
tasks.register<Test>("nativeIntegrationTest") {
    dependsOn("hostTestClasses")
    testClassesDirs = hostUnit.get().testClassesDirs
    classpath = hostUnit.get().classpath
    include("**/NativeStackTest.class")
    systemProperty("java.library.path", file("../../dist/native/linux-x64").absolutePath)
}
