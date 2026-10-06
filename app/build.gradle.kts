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
 * Release signing, from an optional keystore.properties at the repository root, whose paths are
 * resolved against that root. Without it a release build is unsigned rather than failing.
 * `hasSigning` gates the `signingConfigs` block as well as the assignment, because a
 * `signingConfig` whose keystore does not exist fails at configure time.
 */
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val keystoreFile = (keystoreProps["storeFile"] as String?)?.let(rootProject::file)
val hasSigning = keystoreFile != null

/**
 * The Garmin application credential, which is not in this repository and never will be: the
 * untracked local/creds.json, or the file `-Psendfpl.credential=/path/to/creds.json` names.
 */
val credentialFile = providers.gradleProperty("sendfpl.credential")
    .map { rootProject.file(it) }
    .getOrElse(rootProject.file("local/creds.json"))

/**
 * A stable debug key, when one is available, so successive builds install over each other instead
 * of forcing an uninstall that takes the stored credential with it. Absent, AGP generates its own.
 */
val debugKeystore = rootProject.file("local/debug.keystore")

/**
 * Let CI's `assembleRelease` build a release APK with no credential: the release assets are then
 * staged without [verifyReleaseCredential]. Refused beside any task that ships a build, below.
 */
val allowMissingCredential = providers.gradleProperty("sendfpl.allowMissingCredential")
    .map(String::toBoolean)
    .getOrElse(false)

android {
    namespace = "app.sendfpl"
    // Required, not chosen: Compose 1.12, core 1.19 and lifecycle 2.11 declare a minimum
    // compileSdk of 37. It is only the API the app is compiled against.
    compileSdk = 37

    defaultConfig {
        // github.com/0intro/loxodrome resolves an explicit intent at this id to hand a route
        // over, so renaming it breaks that silently.
        applicationId = "app.sendfpl"
        minSdk = 26
        // Not following compileSdk: this one opts the app into new runtime behaviour, so it moves
        // only once a handset has been flown against it.
        targetSdk = 36
        versionCode = 7
        versionName = "0.2.3"
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
            // CI asserts the APK stays small enough to prove this is on.
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
        // For BuildConfig.DEBUG, which Trace reads, and the version the About page shows.
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
 * Stage the operator's creds.json into one variant's assets.
 *
 * Absent one the task produces nothing and the app falls back to asking the user to import a
 * credential, which is what keeps a fresh clone building and is the normal state of a clone. For a
 * release, [verifyReleaseCredential] runs first: every release artifact merges these assets, so
 * none is built without a credential.
 */
abstract class StageCredential : DefaultTask() {
    // A file collection rather than an `@InputFile`: a declared `@InputFile` that does not exist
    // fails the build, and an absent creds.json must leave a clone building.
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val source: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    /** Deletes the staged copy when there is no source, which a `Copy` task would leave behind. */
    @TaskAction
    fun stage() {
        val dest = outputDir.get().asFile.apply { mkdirs() }.resolve("creds.json")
        val src = source.files.firstOrNull()?.takeIf { it.isFile }
        if (src != null) src.copyTo(dest, overwrite = true) else dest.delete()
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
        // The same checks the app makes on an imported credential, so a malformed one fails here
        // rather than at the navigator.
        val json = runCatching { groovy.json.JsonSlurper().parseText(source.readText()) as Map<*, *> }
            .getOrElse { throw GradleException("${source.path} is not a JSON object: ${it.message}") }
        val userId = json["user_id"] as? Number
        check(userId != null && userId.toLong() in 0..0xFFFFFFFFL) {
            "creds.json: \"user_id\" must be an integer from 0 to ${0xFFFFFFFFL}"
        }
        fun hex(field: String) = (json[field] as? String)?.filterNot(Char::isWhitespace)
            ?.takeIf { it.isNotEmpty() && it.length % 2 == 0 && it.all { c -> c in "0123456789abcdefABCDEF" } }
        check(hex("token")?.length == 96) { "creds.json: \"token\" must be 48 bytes of hex" }
        check(hex("entitlement") != null) { "creds.json: \"entitlement\" must be hex" }
    }
}

androidComponents {
    onVariants { variant ->
        val stage = tasks.register<StageCredential>(
            "stage${variant.name.replaceFirstChar(Char::uppercase)}Credential"
        ) {
            source.from(credentialFile)
            if (variant.buildType == "release" && !allowMissingCredential) {
                dependsOn(verifyReleaseCredential)
            }
        }
        // Wired through the variant API rather than registered as a source directory, so that
        // every consumer of the generated directory depends on the task that writes it: the asset
        // merge, and also lint's model tasks, which a dependsOn list written by hand misses.
        variant.sources.assets?.addGeneratedSourceDirectory(stage, StageCredential::outputDir)
    }
}

// A bundle is what goes to Play, by hand or through Gradle Play Publisher, and an installed release
// is a release, so neither is built without the credential, whatever allowMissingCredential says.
gradle.taskGraph.whenReady {
    val shipping = allTasks.filter { it.project == project }.map { it.name }.filter {
        it == "bundleRelease" || it == "installRelease" ||
            it.startsWith("publish") || it.startsWith("promote")
    }
    if (allowMissingCredential && shipping.isNotEmpty()) {
        throw GradleException("sendfpl.allowMissingCredential is for CI's assembleRelease, not $shipping")
    }
}

// `StringsTest` reads the strings.xml files, the Play listing, README.md and the home page off the
// disk, so they are declared as inputs: without this, editing one would leave the test task up to
// date and the check would not run.
tasks.withType<Test>().configureEach {
    inputs.files(fileTree("src/main/res") { include("values*/strings.xml") })
        .withPropertyName("stringResources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(
        fileTree("src/main/play") { include("**/*.txt") },
        rootProject.file("README.md"),
        rootProject.file("www/index.html"),
    )
        .withPropertyName("publishedText")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

dependencies {
    implementation(project(":cxp"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)

    // No androidx.security:security-crypto: its API is deprecated in favour of the platform
    // keystore, which CredentialStore uses directly.

    testImplementation(libs.junit)
}

/*
 * Publishing to Play: the store listing under src/main/play, and the bundle. `publishListing`
 * pushes only the text and the graphics and never touches a release. The service account key comes
 * from the command line or the environment, never from the tree; without it only the plugin's
 * tasks fail.
 *
 *   ./gradlew :app:publishListing -Psendfpl.playCredentials=/path/to/play-service-account.json
 *   ./gradlew :app:publishBundle  -Psendfpl.playCredentials=... -Psendfpl.credential=...
 *
 * The track is `internal` and the release a draft, so a build reaches nobody until it is rolled
 * out by hand. Closed testing is a deliberate promotion of a build already uploaded:
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
 * Check the store listing against Play's limits before anything is uploaded. Play reads the
 * metadata only after the credentials, so a listing one character too long fails a publish that
 * has already authenticated.
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

        for (lang in listOf("en-US", "fr-FR")) {
            text("listings/$lang/title.txt", 30)
            text("listings/$lang/short-description.txt", 80)
            text("listings/$lang/full-description.txt", 4000)
        }
        text("release-notes/en-US/default.txt", 500)
        text("release-notes/fr-FR/default.txt", 500)
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

// Gradle Play Publisher takes the bundle and the APK as artifacts, not through bundleRelease or
// assembleRelease, so the checks hang on its own tasks, by the names it documents (its classes are
// internal): the listing before any of them writes to Play, and the credential before a build goes
// up, whatever allowMissingCredential says.
tasks.named { it.startsWith("publish") || it.startsWith("promote") }.configureEach {
    dependsOn(verifyPlayListing)
}
tasks.named { Regex("publish.*(Bundle|Apk)").matches(it) }.configureEach {
    dependsOn(verifyReleaseCredential)
}
tasks.named("check") { dependsOn(verifyPlayListing) }
