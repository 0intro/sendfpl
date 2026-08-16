import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

// No `kotlin.android` plugin: AGP 9 compiles Kotlin itself, and applying the old plugin alongside
// it is an error rather than a redundancy.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.triplet.play)
}

/*
 * Release signing, from an optional keystore.properties at the repository root. Paths in it are
 * resolved against that root, not against this module.
 *
 * With the file absent the release build stays unsigned rather than failing, so a fresh clone and
 * every debug build still work. That is why `hasSigning` gates the `signingConfigs` block and the
 * `buildTypes` assignment separately: declaring a `signingConfig` whose keystore does not exist
 * fails at configure time, not at signing time.
 */
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val keystoreFile = (keystoreProps["storeFile"] as String?)?.let(rootProject::file)
val hasSigning = keystoreFile != null

/**
 * The Garmin application credential, which is not in this repository and never will be.
 *
 * It defaults to the untracked local/creds.json. `-Psendfpl.credential=/path/to/creds.json` points
 * the build at one elsewhere, which is what lets someone build a working release from a credential
 * they recovered themselves without it ever entering the tree.
 */
val credentialFile = providers.gradleProperty("sendfpl.credential")
    .map { rootProject.file(it) }
    .getOrElse(rootProject.file("local/creds.json"))

/**
 * A stable debug key, when one is available, so successive builds install over each other instead
 * of forcing an uninstall that takes the stored credential with it.
 *
 * Absent, AGP falls back to its own generated key, which is what a fresh clone and a fork's pull
 * request get. Nothing breaks there; the builds are simply not mutually upgradable.
 */
val debugKeystore = rootProject.file("local/debug.keystore")

/**
 * Let a release **APK** be built with no credential, for CI only. It loosens `assembleRelease` and
 * nothing else: `bundleRelease` is the Play path and always verifies.
 */
val allowMissingCredential = providers.gradleProperty("sendfpl.allowMissingCredential")
    .map(String::toBoolean)
    .getOrElse(false)

android {
    namespace = "app.sendfpl"
    // Required by the AndroidX stack, not chosen: Compose 1.12, core 1.19 and lifecycle 2.11 all
    // declare a minimum compileSdk of 37, and AGP refuses the build rather than degrading. This is
    // only the API the app is compiled against.
    compileSdk = 37

    defaultConfig {
        // github.com/0intro/notam-viewer resolves an explicit intent at this id to hand a route
        // over, so renaming it breaks that silently.
        applicationId = "app.sendfpl"
        minSdk = 26
        // Deliberately not following compileSdk to 37: this is the one that opts the app into new
        // runtime behaviour, so it moves when a handset has been flown against it.
        targetSdk = 36
        versionCode = 3
        versionName = "0.1.1"
    }

    signingConfigs {
        getByName("debug") {
            if (debugKeystore.isFile) {
                storeFile = debugKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }

        if (hasSigning) {
            create("release") {
                require(keystoreFile!!.isFile) {
                    "keystore.properties says storeFile=${keystoreProps["storeFile"]}, which " +
                        "resolves to ${keystoreFile.path} and is not there. Paths are resolved " +
                        "against the repository root: use local/android/sendfpl-upload.jks."
                }
                storeFile = keystoreFile
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            if (hasSigning) signingConfig = signingConfigs.getByName("release")
            // On, and worth the line it costs: without it this app is 12.7 MB, of which 45 MB of
            // uncompressed dex is material-icons-extended shipping about two thousand icon
            // builders for the seven that are used. With it the APK is 1.4 MB and the bundle 3.6.
            // See proguard-rules.pro for what has and has not been run on a handset.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // For BuildConfig.DEBUG, which the setup sheet reads.
        buildConfig = true
    }
}

// The Kotlin half of the `compileOptions` above, outside the `android` block because AGP 9 dropped
// `android { kotlinOptions { } }`. It has to match :cxp, because a library built for a newer target
// than the application is rejected at the AGP level, not at run time.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

/**
 * Stage the operator's creds.json into the build's assets.
 *
 * Absent one the task produces nothing and the app falls back to asking the user to import a
 * credential, which is what keeps a fresh clone building and is the normal state of a clone.
 * [verifyReleaseCredential] is what stops that silence reaching a release.
 */
abstract class StageCredential : DefaultTask() {
    // A file collection rather than an `@InputFile`: a declared `@InputFile` that does not exist
    // fails the build, and an absent creds.json must leave a clone building.
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val source: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    /**
     * Always runs, and deletes when there is no source. A plain `Copy` with `onlyIf { exists() }`
     * never removes stale output, so deleting creds.json would leave the last copy in place and
     * keep shipping it.
     */
    @TaskAction
    fun stage() {
        val dest = outputDir.get().asFile.apply { mkdirs() }.resolve("creds.json")
        val src = source.files.firstOrNull()?.takeIf { it.isFile }
        if (src != null) src.copyTo(dest, overwrite = true) else dest.delete()
    }
}

val stageCredential = tasks.register<StageCredential>("stageCredential") {
    source.from(credentialFile)
}

androidComponents {
    onVariants { variant ->
        // Wired through the variant API rather than registered as a source directory, so that
        // every consumer of the generated directory depends on the task that writes it: the asset
        // merge, and also lint's model tasks, which a dependsOn list written by hand misses.
        variant.sources.assets?.addGeneratedSourceDirectory(stageCredential, StageCredential::outputDir)
    }
}

/**
 * Refuse to build a release that cannot authenticate: it would look perfectly healthy and fail at
 * the navigator, and Play never lets a versionCode be reused. Debug builds are deliberately not
 * gated, since there an absent credential is a developer without one and the import sheet is the
 * right answer.
 */
val verifyReleaseCredential = tasks.register("verifyReleaseCredential") {
    val source = credentialFile
    doLast {
        check(source.isFile) {
            "no creds.json at ${source.path}. A release built without it authenticates to nothing, " +
                "and Play never lets a versionCode be reused. Put one there, or point at one with " +
                "-Psendfpl.credential=/path/to/creds.json."
        }
        val text = source.readText()
        for (field in listOf("user_id", "token", "entitlement")) {
            check(text.contains("\"$field\"")) { "creds.json has no \"$field\" field" }
        }
    }
}

tasks.matching { it.name == "assembleRelease" }.configureEach {
    if (!allowMissingCredential) dependsOn(verifyReleaseCredential)
}
tasks.matching { it.name == "bundleRelease" }.configureEach { dependsOn(verifyReleaseCredential) }

// `StringsTest` reads the strings.xml files off the disk, which Gradle cannot see, so without this
// the unit test task is up to date after a change to exactly the files that check exists to
// compare and the check silently does not run. Declaring them as inputs is what makes editing a
// translation re-run it.
tasks.withType<Test>().configureEach {
    inputs.files(fileTree("src/main/res") { include("values*/strings.xml") })
        .withPropertyName("stringResources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

dependencies {
    implementation(project(":cxp"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // No androidx.security:security-crypto: it deprecated its entire API in 1.1.0 in favour of the
    // platform keystore, which CredentialStore uses directly.

    testImplementation(libs.junit)
}

/*
 * Publishing to Play: the store listing under src/main/play, and the bundle.
 *
 * The listing lives in the repository rather than in a web form, so a wording change is a diff
 * and a review like any other. `publishListing` pushes only the text and the graphics and never
 * touches a release, which makes it the safe task to run.
 *
 * The service account key is deliberately not defaulted to a path in the tree. It arrives from
 * the command line or the environment, and when it is absent the plugin's tasks are the only
 * thing that fails, at execution, saying so. Everything else builds as before.
 *
 *   ./gradlew :app:publishListing -Psendfpl.playCredentials=/path/to/play-service-account.json
 *   ./gradlew :app:publishBundle  -Psendfpl.playCredentials=... -Psendfpl.credential=...
 *
 * The track is `internal` and the release a draft, so a build reaches nobody until it is rolled
 * out by hand and can never land on `production` by accident. Closed testing is a promotion of a
 * build already uploaded, done deliberately rather than on every publish:
 *
 *   ./gradlew :app:promoteReleaseArtifact --from-track internal --promote-track alpha
 */
play {
    val key = providers.gradleProperty("sendfpl.playCredentials")
        .orElse(providers.environmentVariable("PLAY_SERVICE_ACCOUNT_JSON"))
    if (key.isPresent) {
        serviceAccountCredentials.set(rootProject.file(key.get()))
    }
    defaultToAppBundles.set(true)
    releaseName.set(android.defaultConfig.versionName)
    track.set("internal")
    releaseStatus.set(com.github.triplet.gradle.androidpublisher.ReleaseStatus.DRAFT)
}

/**
 * Check the store listing against Play's limits before anything is uploaded.
 *
 * Every one of these is enforced by Play at upload time, which is the worst moment to learn about
 * it: the metadata is only read after the credentials are, so a listing that is one character too
 * long fails a publish that has already authenticated. Checked here it is a diff that fails in
 * CI, and the numbers stop being folklore.
 */
val verifyPlayListing = tasks.register("verifyPlayListing") {
    val play = layout.projectDirectory.dir("src/main/play")
    inputs.dir(play).withPathSensitivity(PathSensitivity.RELATIVE)
    doLast {
        val dir = play.asFile
        val problems = mutableListOf<String>()

        fun text(path: String, max: Int) {
            val f = dir.resolve(path)
            if (!f.isFile) return problems.plusAssign("$path is missing")
            val n = f.readText().trim().length
            if (n == 0) problems += "$path is empty"
            if (n > max) problems += "$path is $n characters, Play allows $max"
        }

        text("listings/en-US/title.txt", 30)
        text("listings/en-US/short-description.txt", 80)
        text("listings/en-US/full-description.txt", 4000)
        text("release-notes/en-US/default.txt", 500)
        text("contact-email.txt", 320)

        fun image(path: String, w: Int, h: Int) {
            val f = dir.resolve(path)
            if (!f.isFile) return problems.plusAssign("$path is missing")
            val img = javax.imageio.ImageIO.read(f)
                ?: return problems.plusAssign("$path is not readable as an image")
            if (img.width != w || img.height != h) {
                problems += "$path is ${img.width}x${img.height}, Play wants ${w}x$h"
            }
        }

        image("listings/en-US/graphics/icon/icon.png", 512, 512)
        image("listings/en-US/graphics/feature-graphic/feature-graphic.png", 1024, 500)

        // Two is Play's minimum for a phone, and eight its maximum.
        val shots = dir.resolve("listings/en-US/graphics/phone-screenshots")
            .listFiles { f -> f.extension.lowercase() in setOf("png", "jpg", "jpeg") }
            ?.sorted()
            .orEmpty()
        if (shots.size !in 2..8) {
            problems += "phone-screenshots holds ${shots.size} images, Play wants 2 to 8"
        }
        shots.forEach { f ->
            val img = javax.imageio.ImageIO.read(f)
            if (img == null) {
                problems += "${f.name} is not readable as an image"
            } else if (minOf(img.width, img.height) < 320 || maxOf(img.width, img.height) > 3840) {
                problems += "${f.name} is ${img.width}x${img.height}, each side must be 320 to 3840"
            }
        }

        check(problems.isEmpty()) {
            "the Play listing under app/src/main/play is not publishable:\n" +
                problems.joinToString("\n") { "  - $it" }
        }
    }
}

// The listing is part of what a release is, so it is verified with the credential rather than
// discovered to be wrong once the bundle is already built and the versionCode already spent.
tasks.matching { it.name == "bundleRelease" }.configureEach { dependsOn(verifyPlayListing) }
tasks.named("check") { dependsOn(verifyPlayListing) }
