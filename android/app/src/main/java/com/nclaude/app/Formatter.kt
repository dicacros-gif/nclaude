package com.nclaude.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * 본문 줄을 분류해 가독성 서식(볼드/색상/하이라이트)을 부여한다.
 * 결과 세그먼트는 JS 로 전달되어 에디터 문단에 적용된다.
 *
 *  - 첫 줄(글머리)         : 파랑 볼드
 *  - ❝ 로 시작하는 소제목   : 초록 볼드 + 연초록 형광
 *  - # 해시태그 줄          : 파랑
 *  - 키워드 포함 핵심 문장   : 자홍 볼드 + 노랑 형광
 *  - ── 구분선             : 서식 없음(그대로)
 */
object Formatter {

    data class Seg(
        val text: String,
        val bold: Boolean,
        val color: String?,
        val hilite: String?
    )

    private val HILITE_KEYWORDS = listOf(
        "가장", "결국", "핵심", "중요", "주의", "반드시",
        "수혜", "피해", "승자", "진짜 의미", "왜"
    )

    fun segments(content: String): List<Seg> {
        val lines = content.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return emptyList()
        val out = ArrayList<Seg>()
        for ((i, ln) in lines.withIndex()) {
            when {
                isDivider(ln) -> { /* 구분선: 서식 없음 */ }
                i == 0 -> out.add(Seg(ln, true, "#1d4ed8", null))
                ln.startsWith("❝") -> out.add(Seg(ln, true, "#0a8f3c", "#e9fbef"))
                ln.startsWith("#") -> out.add(Seg(ln, false, "#2563eb", null))
                ln.length <= 60 && HILITE_KEYWORDS.any { ln.contains(it) } ->
                    out.add(Seg(ln, true, "#d6336c", "#fff3bf"))
                else -> { /* 일반 문단 */ }
            }
        }
        return out
    }

    private fun isDivider(s: String): Boolean =
        s.isNotEmpty() && s.all { it == '─' || it == '—' || it == '-' || it == 'ㅡ' || it.isWhitespace() }

    /** JS 로 넘길 payload: { title, lines:[...빈 줄 포함...], segs:[{text,bold,color,hilite}] } */
    fun payload(title: String, body: String): JSONObject {
        val segs = JSONArray()
        for (s in segments(body)) {
            segs.put(
                JSONObject()
                    .put("text", s.text)
                    .put("bold", s.bold)
                    .put("color", s.color ?: JSONObject.NULL)
                    .put("hilite", s.hilite ?: JSONObject.NULL)
            )
        }
        // 빈 줄까지 보존해 문단 간격을 그대로 살린다
        val lines = JSONArray()
        for (ln in body.split('\n')) lines.put(ln.replace("\r", ""))
        return JSONObject()
            .put("title", title)
            .put("lines", lines)
            .put("segs", segs)
    }
}
