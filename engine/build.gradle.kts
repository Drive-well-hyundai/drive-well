// 순수 Kotlin. Android에 의존하지 않는다 (implementation.md "모듈")
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(17) }

// 골든 JSON(app/golden)은 tools/golden/export.js가 demo-sim.js에서 뽑는다
sourceSets["test"].resources.srcDir(rootProject.file("golden"))

dependencies {
    implementation(project(":model"))
    implementation(libs.kotlinx.serialization.json) // cardJson을 만든다. 테스트는 골든 JSON을 읽는다
    testImplementation(libs.junit)
}
