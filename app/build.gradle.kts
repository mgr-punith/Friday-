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
