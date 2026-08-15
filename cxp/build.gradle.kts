import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

/*
 * The Connext protocol and the route model, which the apps and the trainer harness share. It
 * applies the Multiplatform plugin alone, so an Android import here fails to compile. The JVM
 * target is what the Android app consumes, and it has to be Java 17 bytecode, as the app is, or
 * AGP rejects it. linuxX64 runs the common tests natively on a Linux machine.
 */
kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    linuxX64()

    sourceSets {
        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}
