import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

/*
 * The Connext protocol and the route model: everything the app does that is not Android. There is
 * deliberately no Android plugin here, so an Android import in this module does not compile.
 *
 * Java 17 is set directly rather than through `jvmToolchain(17)`, which would ask Gradle to find or
 * download a JDK 17 even when the build is already running on a newer one. It has to match :app, or
 * AGP rejects the library.
 */
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    testImplementation(libs.junit)
}
