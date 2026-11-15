// 순수 Kotlin. Android에 의존하지 않는다 (implementation.md "모듈")
plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(17) }

// 골든 JSON(app/golden)은 tools/golden/export.js가 demo-sim.js에서 뽑는다
sourceSets["test"].resources.srcDir(rootProject.file("golden"))

dependencies {
    api(project(":model"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.serialization.json) // 골든 JSON을 읽는다
}
