// 포그라운드 서비스. 주행 시작·끝, 알림, 저장 호출
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.driveproof.service"
    compileSdk = 34

    defaultConfig {
        minSdk = 28
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    testBuildType = "demo"

    buildFeatures { buildConfig = true }

    buildTypes {
        release {
            buildConfigField("boolean", "IS_DEMO", "false")
            buildConfigField("double", "MIN_KM", "1.0") // implementation.md "빌드" 최소 주행거리
        }
        create("demo") {
            initWith(getByName("debug"))
            buildConfigField("boolean", "IS_DEMO", "true")
            buildConfigField("double", "MIN_KM", "0.2")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

// buildVariant 구현은 src/release와 src/demo에만 있다. :app처럼 debug는 끈다
androidComponents {
    beforeVariants(selector().withBuildType("debug")) { it.enable = false }
}

dependencies {
    api(project(":source")) // DemoController.player·TripService.engine이 :source 타입을 드러낸다
    implementation(project(":engine"))
    implementation(project(":scoring"))
    implementation(project(":data"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    androidTestImplementation("androidx.test:runner:1.6.2")
}
