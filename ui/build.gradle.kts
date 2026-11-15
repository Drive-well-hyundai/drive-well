// 화면. :source와 :service를 모른다. 저장된 값만 그린다 (implementation.md "모듈")
// 화면 프레임워크(Compose 또는 View)는 U-2 화면 골격에서 정한다
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.driveproof.ui"
    compileSdk = 34

    defaultConfig {
        minSdk = 28
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    testBuildType = "demo"

    buildTypes {
        create("demo") { initWith(getByName("debug")) }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":data"))
    implementation(project(":scoring"))
    implementation("com.google.zxing:core:3.5.3")
    testImplementation(libs.junit)
    androidTestImplementation("androidx.test:runner:1.6.2")
}
