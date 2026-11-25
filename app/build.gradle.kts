plugins {
  alias(libs.plugins.android.application)
}

android {
  namespace = "com.puneet.friday"
  compileSdk = 37
  defaultConfig {
    applicationId = "com.puneet.friday"
    minSdk = 26
    targetSdk = 37
    versionCode = 1
    versionName = "1.0"
    buildConfigField("String", "BACKEND_URL", "\"https://friday-backend-seven.vercel.app\"")
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
  // The owner's local wake model enters only the debug APK.
  providers.gradleProperty("fridayWakeModelDir").orNull?.let { modelDir ->
    sourceSets.getByName("debug").assets.srcDir(rootProject.file(modelDir))
  }
  // Legacy native trial artifacts for comparison builds.
  providers.gradleProperty("wakeTrialNativeDir").orNull?.let { nativeDir ->
    sourceSets.getByName("debug").jniLibs.srcDir(file(nativeDir))
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    aidl = false
    compose = false
    buildConfig = true
    dataBinding = false
    prefab = false
    resValues = false
    shaders = false
    viewBinding = false
  }
  lint {
    fatal += "CredentialManagerMisuse"
  }
}

dependencies {
  implementation("androidx.credentials:credentials:1.6.0")
  implementation("androidx.credentials:credentials-play-services-auth:1.6.0")
  implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
}

// Run pure matching/validation checks without adding a test framework to the APK.
val actionLogicTest by tasks.registering(JavaExec::class) {
  group = "verification"
  description = "Runs dependency-free action matching and validation checks."
  dependsOn("compileDebugUnitTestKotlin")
  mainClass.set("com.puneet.friday.actions.ActionLogicTests")
  classpath(
    layout.buildDirectory.dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes"),
    layout.buildDirectory.dir("intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes"),
    configurations.named("debugUnitTestRuntimeClasspath"),
  )
}
val voiceLogicTest by tasks.registering(JavaExec::class) {
  group = "verification"
  description = "Runs dependency-free voice audio and protocol checks."
  dependsOn("compileDebugUnitTestKotlin")
  mainClass.set("com.puneet.friday.voice.VoiceLogicTests")
  classpath(
    layout.buildDirectory.dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes"),
    layout.buildDirectory.dir("intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes"),
    configurations.named("debugUnitTestRuntimeClasspath"),
  )
}
val wakeTrialLogicTest by tasks.registering(JavaExec::class) {
  group = "verification"
  description = "Runs dependency-free checks for the debug wake-detector trial."
  dependsOn("compileDebugUnitTestKotlin")
  mainClass.set("com.puneet.friday.debug.wake.WakeTrialLogicTests")
  classpath(
    layout.buildDirectory.dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes"),
    layout.buildDirectory.dir("intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes"),
    configurations.named("debugUnitTestRuntimeClasspath"),
  )
}
// Exercise real Android voice lifecycles with test-only platform doubles in a separate JVM.
// These classes do not enter an Android source set or an APK.
val compileSpeakerTests by tasks.registering(JavaCompile::class) {
  dependsOn("compileDebugKotlin")
  source(fileTree("src/testSpeaker/java") { include("**/*.java") })
  destinationDirectory.set(layout.buildDirectory.dir("speaker-tests/classes"))
  classpath = files(
    layout.buildDirectory.dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes"),
    configurations.named("debugUnitTestRuntimeClasspath"),
  )
  sourceCompatibility = "17"
  targetCompatibility = "17"
}
val confirmationSpeakerTest by tasks.registering(JavaExec::class) {
  group = "verification"
  description = "Runs real confirmation-speaker lifecycle regressions with a fake Android clock and TTS engine."
  dependsOn(compileSpeakerTests)
  mainClass.set("com.puneet.friday.voice.ConfirmationSpeakerTests")
  classpath(
    compileSpeakerTests.flatMap { it.destinationDirectory },
    layout.buildDirectory.dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes"),
    configurations.named("debugUnitTestRuntimeClasspath"),
  )
}
val commandAudioFocusTest by tasks.registering(JavaExec::class) {
  group = "verification"
  description = "Runs command-recording audio focus and interruption checks."
  dependsOn(compileSpeakerTests)
  mainClass.set("com.puneet.friday.voice.CommandAudioFocusTests")
  classpath(
    compileSpeakerTests.flatMap { it.destinationDirectory },
    layout.buildDirectory.dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes"),
    configurations.named("debugUnitTestRuntimeClasspath"),
  )
}
val previousTrackSessionTest by tasks.registering(JavaExec::class) {
  group = "verification"
  description = "Runs previous-track media service discovery and lifecycle checks."
  dependsOn(compileSpeakerTests)
  mainClass.set("com.puneet.friday.actions.PreviousTrackMediaSessionTests")
  classpath(
    compileSpeakerTests.flatMap { it.destinationDirectory },
    layout.buildDirectory.dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes"),
    configurations.named("debugUnitTestRuntimeClasspath"),
  )
}
tasks.matching { it.name == "test" || it.name == "check" }.configureEach {
  dependsOn(actionLogicTest, voiceLogicTest, wakeTrialLogicTest, confirmationSpeakerTest, commandAudioFocusTest, previousTrackSessionTest)
}
// There is no JUnit dependency; the JavaExec task above runs our JVM checks.
tasks.withType<Test>().configureEach {
  enabled = false
  dependsOn(actionLogicTest, voiceLogicTest, wakeTrialLogicTest, confirmationSpeakerTest, commandAudioFocusTest, previousTrackSessionTest)
}
