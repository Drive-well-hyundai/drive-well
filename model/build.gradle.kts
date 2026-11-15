// 순수 Kotlin. Android에 의존하지 않는다 (implementation.md "모듈")
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(17) }

// 골든 JSON(app/golden)은 tools/golden/export.js가 demo-sim.js에서 뽑는다
sourceSets["test"].resources.srcDir(rootProject.file("golden"))

dependencies {
    implementation(libs.kotlinx.serialization.json) // 시나리오 파서. 트리 API만 써서 컴파일러 플러그인은 필요 없다
    testImplementation(libs.junit)
}
