// APK를 묶는 모듈. 빌드 종류는 release(출시), demo(시연), submit(공모전 제출) 셋이다 (implementation.md "빌드")
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// 제출 서명 키는 저장소에 넣지 않는다. local.properties의 submit.* 값을 읽는다
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val submitStore = localProps.getProperty("submit.storeFile")

android {
    namespace = "com.driveproof"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.driveproof"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }
    buildFeatures { buildConfig = true }

    signingConfigs {
        if (submitStore != null) {
            create("submit") {
                storeFile = file(submitStore)
                storePassword = localProps.getProperty("submit.storePassword")
                keyAlias = localProps.getProperty("submit.keyAlias")
                keyPassword = localProps.getProperty("submit.keyPassword")
            }
        }
    }

    buildTypes {
        release {
            buildConfigField("boolean", "IS_DEMO", "false")
            buildConfigField("boolean", "AUTO_SEED", "false")
            isMinifyEnabled = false
        }
        create("demo") {
            initWith(getByName("debug"))
            buildConfigField("boolean", "IS_DEMO", "true")
            buildConfigField("boolean", "AUTO_SEED", "false")
            // demo에만 src/demo(시연 진입 Activity, NAVI_ROUTE_SEARCH)가 들어간다
        }
        // 공모전 제출 APK(결정 36). release 설정(서명, 디버그 끔)에 demo 기능을 넣고, 첫 실행 때 6개월 시연 기록을 넣는다
        create("submit") {
            initWith(getByName("release"))
            buildConfigField("boolean", "IS_DEMO", "true")
            buildConfigField("boolean", "AUTO_SEED", "true")
            matchingFallbacks += listOf("demo")
            signingConfigs.findByName("submit")?.let { signingConfig = it }
        }
    }
    sourceSets {
        getByName("submit") {
            java.srcDir("src/demo/kotlin")
            res.srcDir("src/demo/res")
            assets.srcDir("src/demo/assets")
            manifest.srcFile("src/demo/AndroidManifest.xml")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

// 빌드 종류를 release, demo, submit 셋으로 맞춘다. 개발 중에는 demo를 쓴다
androidComponents {
    beforeVariants(selector().withBuildType("debug")) { it.enable = false }
}

// 제출 APK를 app/apk/에 공모전 파일명으로 둔다(contest.md "GitHub와 APK", 블라인드라 팀명은 넣지 않는다).
// 제출용 저장소는 app/만 옮기므로(결정 27) 그 저장소의 apk/가 된다
// Copy 작업은 이미 있는 대상 파일을 보고 건너뛴 적이 있어서, 매번 새 빌드로 덮어쓴다
tasks.register("submitApk") {
    dependsOn("assembleSubmit")
    val built = layout.buildDirectory.file("outputs/apk/submit/app-submit.apk")
    val target = rootProject.file("apk/DriveWell_release.apk")
    outputs.upToDateWhen { false }
    doLast {
        target.parentFile.mkdirs()
        built.get().asFile.copyTo(target, overwrite = true)
        logger.lifecycle("제출 APK: ${target.path}")
    }
}

dependencies {
    implementation(project(":ui"))
    implementation(project(":service"))
    implementation(project(":data"))
    implementation(libs.kotlinx.coroutines.android)
}
