plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(libs.coroutines.core)
    // Dispatchers.Main = AWT 이벤트 스레드. 이 모듈이 없으면 Dispatchers.Main을 쓰는
    // 순간 "Module with the Main dispatcher is missing"으로 죽는다 — 코루틴 안에서
    // 나는 예외라 화면에는 아무 일도 일어나지 않은 것처럼 보인다
    implementation(libs.coroutines.swing)
    implementation(libs.gson)

    implementation(project(":shared"))

    testImplementation(libs.junit)
}

compose.desktop {
    application {
        mainClass = "com.pbp.desktop.MainKt"
        nativeDistributions {
            packageName = "PbP"
            packageVersion = "0.20.0" // 앱 versionName과 동일 스킴 (리뷰 E)
        }
    }
}
