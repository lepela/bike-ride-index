plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// 저장소 경로(C:\work\99_코드\...)에 한글이 있으면 Windows에서 단위 테스트 워커가
// 테스트 클래스를 찾지 못한다(ClassNotFoundException). 빌드 출력만 ASCII 경로로 옮긴다.
if (rootDir.absolutePath.any { it.code > 127 }) {
    val outRoot = File(System.getProperty("java.io.tmpdir"), "bike-ride-index-build")
    allprojects {
        layout.buildDirectory.set(File(outRoot, project.name))
    }
}
