package com.nclaude.app

/**
 * 본문에서 제목을 자동 생성한다.
 *
 * 규칙 (사용자 예시 그대로):
 *  1) 마지막 줄 + (와/과) + 첫 줄  순서로 연결
 *  2) 첫 줄의 단어 중 마지막 줄에 이미 나온 단어는 삭제
 *  3) 단, 유사어 맵에 있으면 삭제 대신 유사어로 치환 (예: 스페이스X → spaceX)
 *
 * 예)
 *  첫 줄   : 블루오리진 뉴글렌 폭발 왜 충격이 클까? 스페이스X 로켓 경쟁 우주산업 관련주 미래전망
 *  마지막 줄: 뉴글렌 사고 이후 누가 웃을까? 우주항공 투자전략 스페이스X 경쟁구도 뜻과 의미
 *  결과    : 뉴글렌 사고 이후 누가 웃을까? 우주항공 투자전략 스페이스X 경쟁구도 뜻과 의미와
 *            블루오리진 폭발 왜 충격이 클까? spaceX 로켓 경쟁 우주산업 관련주 미래전망
 */
object TitleGen {

    /** 중복 단어를 삭제하는 대신 치환할 유사어 */
    private val synonyms: Map<String, String> = mapOf(
        "스페이스X" to "spaceX",
        "스페이스x" to "spaceX",
        "SpaceX" to "spaceX"
    )

    fun generate(content: String): String {
        val lines = content.split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (lines.isEmpty()) return ""
        val firstLine = lines.first()
        val lastLine = lines.last()
        if (lines.size == 1 || firstLine == lastLine) return firstLine

        val lastTokens = lastLine.split(Regex("\\s+")).filter { it.isNotEmpty() }.toHashSet()

        val cleanedFirst = StringBuilder()
        for (tok in firstLine.split(Regex("\\s+"))) {
            if (tok.isEmpty()) continue
            if (lastTokens.contains(tok)) {
                val syn = synonyms[tok]
                if (syn != null) {
                    if (cleanedFirst.isNotEmpty()) cleanedFirst.append(' ')
                    cleanedFirst.append(syn)
                }
                // 유사어가 없으면 중복이므로 삭제
            } else {
                if (cleanedFirst.isNotEmpty()) cleanedFirst.append(' ')
                cleanedFirst.append(tok)
            }
        }

        val rest = cleanedFirst.toString().trim()
        if (rest.isEmpty()) return lastLine
        return lastLine + josaWaGwa(lastLine) + " " + rest
    }

    /** 마지막 글자의 받침 유무로 "과"(받침 O) / "와"(받침 X) 선택 */
    private fun josaWaGwa(s: String): String {
        val c = s.lastOrNull { !it.isWhitespace() } ?: return "와"
        val code = c.code
        if (code in 0xAC00..0xD7A3) {
            val hasJong = (code - 0xAC00) % 28 != 0
            return if (hasJong) "과" else "와"
        }
        // 한글이 아니면 기본 "와"
        return "와"
    }
}
