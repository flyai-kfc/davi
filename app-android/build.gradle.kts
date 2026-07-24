// 루트 빌드 스크립트. 플러그인은 여기서 선언만 하고 적용은 모듈(app)에서.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
