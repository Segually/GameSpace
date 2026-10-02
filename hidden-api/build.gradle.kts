// Compile-only stubs for the handful of @hide framework classes the app touches.
// Never packaged into the APK; the real classes come from the device framework.
import java.util.Properties

plugins {
    id("java-library")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

val sdkDir: String = Properties().run {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
    getProperty("sdk.dir") ?: System.getenv("ANDROID_HOME")
    ?: error("Set sdk.dir in local.properties or ANDROID_HOME")
}

dependencies {
    compileOnly(files("$sdkDir/platforms/android-36/android.jar"))
}
