import java.util.Properties

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

/**
 * 구글 로그인 시크릿을 소스로 굳힌다 — secrets.properties는 저장소에 없다(.gitignore).
 * 파일이 없으면 빈 값이 들어가고, 그때는 앱이 구글 로그인 항목을 감춘다.
 */
val googleClientSecret: String = rootProject.file("secrets.properties").let { file ->
    if (!file.exists()) {
        ""
    } else {
        val properties = Properties()
        file.inputStream().use { properties.load(it) }
        properties.getProperty("desktopGoogleClientSecret").orEmpty().trim()
    }
}

val generateSecrets = tasks.register("generateSecrets") {
    val outDir = layout.buildDirectory.dir("generated/secrets")
    inputs.property("secret", googleClientSecret)
    outputs.dir(outDir)
    doLast {
        val dir = outDir.get().asFile.resolve("com/pbp/desktop")
        dir.mkdirs()
        dir.resolve("Secrets.kt").writeText(
            """
            package com.pbp.desktop

            /** 빌드 때 secrets.properties에서 채워진다. 비어 있으면 구글 로그인이 꺼진다 */
            internal const val GOOGLE_CLIENT_SECRET = "$googleClientSecret"
            """.trimIndent() + "\n"
        )
    }
}

kotlin.sourceSets.named("main") {
    kotlin.srcDir(files(layout.buildDirectory.dir("generated/secrets")).builtBy(generateSecrets))
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
            packageVersion = "0.23.1" // 앱 versionName과 동일 스킴 (리뷰 E)
        }
    }
}
