package capstone.snapshot

/**
 * 개인정보 패턴 검사 전에 화면 글자를 접는다(I-84). 기기 안 마스킹(DumpMasker)과 커밋 전 린트(MaskLint)가
 * 같은 규칙을 써야 한쪽만 뚫리는 일이 없어서 둘의 공용 모듈인 여기에 둔다.
 *
 * - [stripInvisible]: 제로폭·단어 결합·BOM·소프트 하이픈을 지운다. 보이지 않으니 화면 의미는 그대로다.
 * - [fold]: 글자 하나를 글자 하나로만 바꾼다(전각 ASCII → ASCII, 대시 변형 → '-', 특수 공백 → ' ').
 *   길이가 같으므로 접은 문자열에서 찾은 위치를 원문에 그대로 쓸 수 있다.
 */
object PiiFold {
    private val INVISIBLE = Regex("[​-‍⁠﻿­]")

    fun stripInvisible(s: String): String = INVISIBLE.replace(s, "")

    fun fold(s: String): String = buildString(s.length) { s.forEach { append(foldChar(it)) } }

    private fun foldChar(c: Char): Char = when (c) {
        in '！'..'～' -> c - 0xFEE0 // 전각 ASCII(０-９, ＠, －, Ａ…)
        in '‐'..'―', '−', '﹘', '﹣' -> '-'
        ' ', in ' '..' ', ' ', ' ', '　' -> ' '
        else -> c
    }
}
