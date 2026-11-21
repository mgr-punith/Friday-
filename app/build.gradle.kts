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
  // Opt-in native trial artifacts. No library or model enters the release source set.
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
tasks.matching { it.name == "test" || it.name == "check" }.configureEach {
  dependsOn(actionLogicTest, voiceLogicTest, wakeTrialLogicTest)
}
// There is no JUnit dependency; the JavaExec task above runs our JVM checks.
tasks.withType<Test>().configureEach {
  enabled = false
  dependsOn(actionLogicTest, voiceLogicTest, wakeTrialLogicTest)
}
