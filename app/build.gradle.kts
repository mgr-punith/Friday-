plugins {
  alias(libs.plugins.android.application)
}

android {
  namespace = "com.example.assistant"
  compileSdk = 37
  defaultConfig {
    applicationId = "com.example.assistant"
    minSdk = 26
    targetSdk = 37
    versionCode = 1
    versionName = "1.0"
  }

  buildTypes {
    release {
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
    aidl = false
    compose = false
    buildConfig = false
    dataBinding = false
    prefab = false
    resValues = false
    shaders = false
    viewBinding = false
  }
}

dependencies {}

// Run pure matching/validation checks without adding a test framework to the APK.
val actionLogicTest by tasks.registering(JavaExec::class) {
  group = "verification"
  description = "Runs dependency-free action matching and validation checks."
  dependsOn("compileDebugUnitTestKotlin")
  mainClass.set("com.example.assistant.actions.ActionLogicTests")
  classpath(
    layout.buildDirectory.dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes"),
    layout.buildDirectory.dir("intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes"),
    configurations.named("debugUnitTestRuntimeClasspath"),
  )
}
tasks.matching { it.name == "test" || it.name == "check" }.configureEach {
  dependsOn(actionLogicTest)
}
// There is no JUnit dependency; the JavaExec task above runs our JVM checks.
tasks.withType<Test>().configureEach {
  enabled = false
  dependsOn(actionLogicTest)
}
