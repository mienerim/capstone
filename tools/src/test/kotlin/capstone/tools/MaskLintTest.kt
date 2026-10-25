package capstone.tools

import capstone.tools.MaskLint.Kind
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class MaskLintTest {
    private fun kinds(line: String) = MaskLint.scanLine(line).map { it.first }

    @Test
    fun `휴대폰·주민번호·카드·이메일·생년월일·병원명을 잡는다`() {
        assertEquals(listOf(Kind.PHONE), kinds("""{"text":"010-1234-5678"}"""))
        assertEquals(listOf(Kind.PHONE), kinds("연락처 01012345678"))
        assertEquals(listOf(Kind.RRN), kinds("550101-1234567"))
        assertEquals(listOf(Kind.CARD), kinds("카드 1234-5678-9012-3456"))
        assertEquals(listOf(Kind.EMAIL), kinds("hong.gildong@naver.com"))
        assertEquals(listOf(Kind.BIRTHDATE), kinds("생년월일 1955.03.07"))
        assertEquals(listOf(Kind.BIRTHDATE), kinds("1948년 12월 1일"))
        assertEquals(listOf(Kind.HOSPITAL), kinds("""{"text":"서울중앙내과의원"}"""))
        assertEquals(listOf(Kind.HOSPITAL), kinds("경희대학교병원 진료과"))
    }

    @Test
    fun `치환본·열차 데이터·타임스탬프는 통과`() {
        listOf(
            "테스트병원A 정형외과",
            "목업병원 예약",
            "2026-10-26 KTX 105 서울→부산 08:00",
            """{"ts":1761400000000,"hash":"0103456789012345"}""",
            "noreply@example.com",
            "[MASK]",
            "호차 5 좌석 12A 59800원",
            "병원 앱 읽기 전용",
        ).forEach { assertTrue(kinds(it).isEmpty(), "$it → ${kinds(it)}") }
    }

    @Test
    fun `해시·16진수·영숫자 id 속 숫자열은 잡지 않는다`() {
        listOf(
            """{"tree_hash":"a3f01012345678bc"}""",
            "sha256 e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            """{"snap_id":"s20261023_165100_0001"}""",
            "build 0x01012345678",
            "id=ab800101-2345678cd",
        ).forEach { assertTrue(kinds(it).isEmpty(), "$it → ${kinds(it)}") }
        // 한글·문장부호와 붙은 번호는 그대로 잡는다.
        assertEquals(listOf(Kind.PHONE), kinds("연락처01012345678입니다"))
        assertEquals(listOf(Kind.PHONE), kinds("tel:010-1234-5678"))
    }

    @Test
    fun `병원 종류·직함 같은 일반 낱말은 통과, 고유 이름은 잡는다`() {
        listOf("대학병원 예약", "근처 종합병원", "요양병원", "동물병원 아님", "국회의원 선거").forEach {
            assertTrue(kinds(it).isEmpty(), "$it → ${kinds(it)}")
        }
        assertEquals(listOf(Kind.HOSPITAL), kinds("서울대학병원"))
        assertEquals(listOf(Kind.HOSPITAL), kinds("행복요양병원 3층"))
        // D70: 약국·보건소도 기관명. 치환본 접두어는 통과.
        assertEquals(listOf(Kind.HOSPITAL), kinds("행복약국 수령"))
        assertEquals(listOf(Kind.HOSPITAL), kinds("동대문구보건소"))
        assertTrue(kinds("테스트약국 수령").isEmpty())
    }

    @Test
    fun `주민번호는 한 번만 보고하고 칸 번호를 준다`() {
        val r = MaskLint.scanLine("x 800101-2345678")
        assertEquals(listOf(Kind.RRN to 3), r)
    }

    @Test
    fun `데이터 확장자만 훑고 결과에 값이 없다`() {
        val dir = Files.createTempDirectory("masklint").toFile()
        try {
            File(dir, "dump.jsonl").writeText("ok\n{\"text\":\"010-9876-5432\"}\n")
            File(dir, "notes.md").writeText("010-9876-5432\n")
            File(dir, "build").mkdirs()
            File(dir, "build/out.json").writeText("010-9876-5432")
            val f = MaskLint.scan(dir)
            assertEquals(listOf(MaskLint.Finding("dump.jsonl", 2, 10, Kind.PHONE)), f)

            val out = StringBuilder()
            assertFalse(maskLint(listOf(dir), out))
            assertFalse(out.contains("9876"), out.toString())
            // 파일을 직접 주면 확장자와 관계없이 본다.
            assertFalse(maskLint(listOf(File(dir, "notes.md")), StringBuilder()))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `없는 경로는 실패`() {
        assertFalse(maskLint(listOf(File("/nonexistent/masklint")), StringBuilder()))
    }

    /** 저장소 자체(픽스처·측정 시트)가 깨끗해야 한다. CI의 mask-lint 단계와 같은 검사. */
    @Test
    fun `저장소 데이터 파일에 개인정보 패턴 없음`() {
        val repo = File(System.getProperty("capstone.fixtures")).parentFile.parentFile
        val f = MaskLint.scan(repo)
        assertTrue(f.isEmpty(), f.joinToString("\n"))
    }
}
