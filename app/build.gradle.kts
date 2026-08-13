plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.pbp.app"
    compileSdk = 37
    compileSdkMinor = 1

    defaultConfig {
        applicationId = "com.pbp.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 49
        versionName = "0.22.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // 개인 테스트용 디버그 서명 — 스토어 배포 시 실제 키스토어로 교체할 것
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
    }

    lint {
        // Fragment 미사용 앱에서 registerForActivityResult에 뜨는 오탐
        disable += "InvalidFragmentVersionForActivityResult"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    // 화면이 백그라운드로 내려가면 읽음 확인 구독을 끊기 위해 (R5)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.navigation.compose)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.coil.compose)
    implementation(libs.gson) // ccfolia 캐릭터 코드(JSON) 파싱
    implementation(libs.exifinterface) // 카메라 사진 회전 보정 (P2-3)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.auth) // 익명 인증 (P0-1 보안 규칙 전제)
    implementation(libs.firebase.messaging)
    // 구글 로그인 (기기 간 이어하기) — 계정 선택은 시스템 UI가 띄운다
    implementation(libs.credentials)
    implementation(libs.credentials.play.services)
    implementation(libs.googleid)
    implementation(libs.coroutines.play.services)

    implementation(project(":shared"))

    testImplementation(libs.junit)
}

// Room 스키마를 파일로 내보낸다 (I1) — 마이그레이션이 10개인데 스키마 JSON이 없어
// MigrationTestHelper로 검증할 수가 없었다. schemas/는 저장소에 함께 커밋한다
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
