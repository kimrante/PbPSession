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
    implementation(libs.gson)

    implementation(project(":shared"))

    testImplementation(libs.junit)
}

compose.desktop {
    application {
        mainClass = "com.pbp.desktop.MainKt"
        nativeDistributions {
            packageName = "PbP"
            packageVersion = "0.18.1" // 앱 versionName과 동일 스킴 (리뷰 E)
        }
    }
}
