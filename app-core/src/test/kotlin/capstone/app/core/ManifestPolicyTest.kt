package capstone.app.core

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * 빌드 없이 확인할 수 있는 저장소 정책(D1·D14·D24): 어떤 Android 모듈도 INTERNET 권한을 선언하지 않고,
 * 접근성 서비스는 isAccessibilityTool=false다. APK 병합 결과는 팀 PC에서 aapt로 다시 확인한다(FIRST-DAY).
 */
class ManifestPolicyTest {
    private val root = File(System.getProperty("capstone.rootDir"))

    private fun files(name: String): List<File> = root.walkTopDown()
        .onEnter { it.name != "build" && it.name != ".git" && it.name != ".gradle" }
        .filter { it.isFile && it.name == name }
        .toList()

    @Test
    fun `INTERNET·네트워크 권한을 선언한 매니페스트가 없다`() {
        val manifests = files("AndroidManifest.xml")
        assertTrue(manifests.any { it.invariantSeparatorsPath.endsWith("app/src/main/AndroidManifest.xml") }, manifests.toString())
        manifests.forEach { f ->
            val text = f.readText()
            assertTrue("android.permission.INTERNET" !in text, "${f.path}: INTERNET")
            assertTrue("ACCESS_NETWORK_STATE" !in text, "${f.path}: ACCESS_NETWORK_STATE")
        }
    }

    @Test
    fun `접근성 서비스 설정은 isAccessibilityTool=false`() {
        val configs = root.walkTopDown().onEnter { it.name != "build" && it.name != ".git" }
            .filter { it.isFile && it.parentFile.name == "xml" && "<accessibility-service" in it.readText() }.toList()
        assertTrue(configs.isNotEmpty())
        configs.forEach { assertTrue("android:isAccessibilityTool=\"false\"" in it.readText(), it.path) }
    }
}
