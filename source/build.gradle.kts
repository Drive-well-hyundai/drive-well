// VehicleDataSource와 Live·Hybrid·Replay 구현. Hybrid·Replay는 src/demo에만 둔다
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.driveproof.source"
    compileSdk = 34
    useLibrary("android.car") // AAOS CarPropertyManager (PERF_VEHICLE_SPEED, GEAR_SELECTION)

    defaultConfig { minSdk = 28 }

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
    api(project(":model"))
    implementation(libs.pleos.vehicle)
    implementation(libs.pleos.navihelper)
    implementation(libs.pleos.fusedlocation)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
