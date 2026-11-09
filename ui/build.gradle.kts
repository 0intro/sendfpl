import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * The screens the Android and iPhone apps share, in Compose Multiplatform, with their strings and
 * icons as Compose resources. What only a platform can do comes in as an expect declaration, in
 * androidMain, or as a callback from the app that hosts the screens.
 */
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    // The library plugin lints androidMain, as lintAndroidMain, only with this one applied.
    alias(libs.plugins.android.lint)
}

kotlin {
    android {
        namespace = "app.sendfpl.ui"
        compileSdk = 37
        minSdk = 26
        androidResources {
            enable = true
        }
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
        // The common tests run on the build machine's JVM, as testAndroidHostTest.
        withHostTestBuilder {}
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":cxp"))
            api(libs.compose.runtime)
            api(libs.compose.foundation)
            api(libs.compose.ui)
            api(libs.compose.material3)
            api(libs.compose.components.resources)
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
        }
    }
}

// Public, so that the Android app's own screens word themselves from the same files.
compose.resources {
    publicResClass = true
    packageOfResClass = "app.sendfpl.ui.resources"
}
