package com.nclaude.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * 본문을 분류해 가독성 서식(볼드/색상/하이라이트)을 부여한다.
 *
 *  핵심: analyze(body) 가 '단일 소스'.
 *   - 빈 줄을 제거해 줄간격이 추가로 벌어지지 않게 한다(요청).
 *   - 분류는 원본 줄로 하고, 소제목/중요문장에는 '맥락 이모지'를 입힌 최종 텍스트를 담는다.
 *   - 미리보기(lineSpecs) · 에디터 입력(payload.lines/segs) · HTML 이 모두 같은 텍스트를 쓰므로
 *     에디터에서 줄 서식 매칭(applyLineSegs)이 어긋나지 않는다.
 *
 *  줄 규칙:
 *   - 첫 줄(글머리)        : 파랑 볼드(큰 글씨)
 *   - 소제목              : 볼드 + 연노랑 형광(큰 글씨) + 맥락 이모지(앞)
 *   - # 해시태그          : 파랑
 *   - 키워드 핵심 문장     : 진보라 볼드 + 연보라 배경 + 맥락 이모지(앞·뒤)
 *   - ── 구분선          : 서식 없음
 */
object Formatter {

    data class Seg(
        val text: String,
        val bold: Boolean,
        val color: String?,
        val hilite: String?,
        val size: Int? = null,
        val kind: String = "plain"
    )

    const val WORD_HILITE = "#fff34f"   // (구) 중요 단어 노랑 형광 — JS 경로 호환용
    const val WORD_COLOR = "#d6336c"    // (구) 중요 단어 글자색 — JS 경로 호환용

    // 소제목: 연한 노랑 형광
    const val HILITE_SUBHEAD = "#fff3b0"
    // 핵심 문장: 연한 보라 배경 + 진보라 글자
    const val SENT_BG = "#ede9fe"
    const val SENT_FG = "#5b21b6"
    // 중요 단어: 파스텔 회전(연녹/연분홍/연파랑/연주황) — 단어마다 색이 달라짐
    val WORD_BG = listOf("#dcfce7", "#fce7f3", "#dbeafe", "#ffe8cc")
    val WORD_FG = listOf("#166534", "#9d174d", "#1e40af", "#9a3412")

    private fun wordPaletteIndex(word: String): Int {
        val i = IMPORTANT_WORDS.indexOf(word)
        val base = if (i >= 0) i else (word.hashCode() and 0x7fffffff)
        return base % WORD_BG.size
    }
    fun wordBg(word: String) = WORD_BG[wordPaletteIndex(word)]
    fun wordFg(word: String) = WORD_FG[wordPaletteIndex(word)]

    // 핵심 '문장' 판정 키워드
    private val HILITE_KEYWORDS = listOf(
        "가장", "결국", "핵심", "중요", "주의", "반드시",
        "수혜", "피해", "승자", "진짜 의미", "왜"
    )

    // 본문 어디에 나오든 인라인 강조할 '단어'(임팩트어)
    val IMPORTANT_WORDS = listOf(
        "급등", "급락", "폭발", "충격", "역대", "최초", "최대", "최고", "사상",
        "수혜", "피해", "승자", "경쟁", "전망", "핵심", "주의", "반드시",
        "논란", "호재", "악재", "변수", "기회", "위기"
    )

    // ---------------------------------------------------------------
    //  맥락 이모지: 줄 내용에 등장하는 키워드로 어울리는 이모지를 고른다.
    //  (소제목은 앞에 1개, 중요 문장은 앞·뒤에 1개씩)
    // ---------------------------------------------------------------
    private val EMOJI_RULES: List<Pair<List<String>, String>> = listOf(
        listOf("급등", "상승", "호재", "수혜", "승자", "최고", "최대", "돌파", "신고가", "랠리", "훈풍") to "🚀",
        listOf("급락", "하락", "악재", "피해", "폭락", "손실", "약세", "경고", "적자") to "📉",
        listOf("주의", "반드시", "경계", "위험", "리스크", "조심") to "⚠️",
        listOf("핵심", "중요", "가장", "결정적", "포인트") to "🎯",
        listOf("전망", "예상", "미래", "향후", "계획", "기대") to "🔮",
        listOf("수익", "투자", "매수", "매도", "시장", "실적", "매출", "금리", "환율", "가격") to "💰",
        listOf("기회", "찬스", "호기", "반등") to "✨",
        listOf("질문", "왜", "어떻게", "무엇", "Q.") to "❓",
        listOf("정답", "해답", "결론", "답은") to "💡",
        listOf("방법", "전략", "노하우", "활용", "팁") to "🛠️",
        listOf("결국", "정리", "요약", "마무리") to "✅",
        listOf("비교", "대결", "경쟁", "승부") to "⚖️",
        listOf("성장", "증가", "확대", "개선", "회복") to "📈",
        listOf("뉴스", "발표", "공개", "출시", "소식") to "📰",
        listOf("일정", "날짜", "기간", "마감", "시간") to "📅",
        listOf("고객", "유저", "사용자", "소비자", "사람들") to "👥"
    )

    private fun pickEmoji(text: String, fallback: String): String {
        for ((keys, emoji) in EMOJI_RULES) {
            if (keys.any { text.contains(it) }) return emoji
        }
        return fallback
    }

    /** 소제목/문장 줄에 맥락 이모지를 입힌다. subhead=앞 1개, sentence=앞·뒤 1개씩. */
    private fun decorate(raw: String, kind: String): String = when (kind) {
        "subhead" -> {
            val core = stripHeadMarker(raw)
            val e = pickEmoji(core, "📌")
            "$e $core"
        }
        "sentence" -> {
            val core = raw.trim()
            val e = pickEmoji(core, "✨")
            "$e $core $e"
        }
        else -> raw.trim()
    }

    /** 소제목 줄 앞의 기호 마커(❝ 【 ▶ ■ ◆ ✔ … Q. A.)를 떼어낸다(이모지가 대신함). */
    private fun stripHeadMarker(s: String): String {
        var t = s.trim()
        val markers = listOf("❝", "❞", "【", "】", "▶", "▷", "■", "◆", "◇", "✔", "✅", "※")
        var changed = true
        while (changed) {
            changed = false
            for (m in markers) {
                if (t.startsWith(m)) { t = t.substring(m.length).trim(); changed = true }
            }
            if (t.startsWith("Q.")) { t = t.substring(2).trim(); changed = true }
            if (t.startsWith("A.")) { t = t.substring(2).trim(); changed = true }
        }
        return t
    }

    /** 소제목 마커 — ❝ 외에 【 ▶ ■ ◆ ✔ Q. A. 로 시작하는 줄 */
    private fun isHead(s: String): Boolean {
        val t = s.trim()
        return t.startsWith("❝") || t.startsWith("【") || t.startsWith("▶") ||
            t.startsWith("▷") || t.startsWith("■") || t.startsWith("◆") ||
            t.startsWith("◇") || t.startsWith("✔") || t.startsWith("✅") ||
            t.startsWith("※") || t.startsWith("Q.") || t.startsWith("A.")
    }

    // 종결어미로 끝나면 '문장'으로 보고 소제목 추정에서 제외(명사/구ㆍ제외 단어가 노출되지 않게 보수적으로)
    private val SENTENCE_ENDERS = listOf(
        "다", "요", "죠", "까", "네", "군", "함", "음", "됨", "임", "해", "봐", "워"
    )

    /**
     * 마커 없는 '짧은 명사형 단독 줄'을 소제목으로 추정한다(보수적).
     *  - 앞 줄이 비어 있거나 글 시작( prevBlank )
     *  - 길이 2..22, 공백(단어 구분) 3개 이하
     *  - 종결부호/종결어미로 끝나지 않음
     */
    private fun isHeuristicHead(line: String, prevBlank: Boolean): Boolean {
        val t = line.trim()
        if (t.isEmpty() || t.startsWith("#")) return false
        if (!prevBlank) return false
        if (t.length < 2 || t.length > 22) return false
        if (t.count { it == ' ' } > 3) return false
        val last = t.last()
        if (last == '.' || last == '!' || last == '?' || last == '~' || last == '…' ||
            last == ',' || last == ':' || last == ';' || last == '。' ||
            last == '”' || last == '"' || last == ')' || last == '」' || last == '』'
        ) return false
        for (e in SENTENCE_ENDERS) if (t.endsWith(e)) return false
        return true
    }

    private fun isDivider(s: String): Boolean =
        s.isNotEmpty() && s.all { it == '─' || it == '—' || it == '-' || it == 'ㅡ' || it.isWhitespace() }

    /** 원본 줄을 분류해 kind/서식을 매긴다(텍스트는 원본 trim, 이모지는 decorate 에서 입힘). */
    private fun classifyKind(line: String, isFirst: Boolean, prevBlank: Boolean): Seg {
        val t = line.trim()
        return when {
            isDivider(t) -> Seg(t, false, null, null, null, "divider")
            isFirst -> Seg(t, true, "#1d4ed8", null, 19, "first")
            t.startsWith("❝") || isHead(t) || isHeuristicHead(t, prevBlank) ->
                Seg(t, true, "#111111", HILITE_SUBHEAD, 22, "subhead")
            t.startsWith("#") -> Seg(t, false, "#2563eb", null, null, "hashtag")
            t.length <= 60 && HILITE_KEYWORDS.any { t.contains(it) } ->
                Seg(t, true, SENT_FG, SENT_BG, null, "sentence")
            else -> Seg(t, false, null, null, null, "plain")
        }
    }

    /**
     * 본문 → 줄 세그먼트(단일 소스).
     *  - 빈 줄 제거(줄간격 추가 방지)
     *  - 분류는 원본 줄로, 텍스트는 맥락 이모지를 입힌 최종본으로.
     */
    fun analyze(body: String): List<Seg> {
        val raw = body.split('\n')
        val out = ArrayList<Seg>()
        var firstDone = false
        for ((i, line) in raw.withIndex()) {
            val t = line.trim()
            if (t.isEmpty()) continue                       // 빈 줄 제거
            val prevBlank = i == 0 || raw[i - 1].trim().isEmpty()
            val isFirst = !firstDone
            firstDone = true
            val seg = classifyKind(t, isFirst, prevBlank)
            out.add(seg.copy(text = decorate(t, seg.kind)))
        }
        return out
    }

    /** 줄 단위 서식 세그먼트(서식 있는 줄만) — 에디터 줄 서식 주입용 */
    fun segments(content: String): List<Seg> =
        analyze(content).filter { it.kind != "plain" && it.kind != "divider" }

    /** 본문에 실제로 등장하는 중요 단어만 인라인 강조 대상으로(파스텔 색까지 함께 전달). */
    fun words(content: String): List<Seg> {
        val out = ArrayList<Seg>()
        for (w in IMPORTANT_WORDS) {
            if (content.contains(w)) out.add(Seg(w, true, wordFg(w), wordBg(w), null, "word"))
        }
        return out
    }

    /** 미리보기 렌더용: 모든 줄(빈 줄 제거됨)의 줄 서식 정보. */
    fun lineSpecs(content: String): List<Seg> = analyze(content)

    /** JS payload: { title, lines:[빈 줄 제거·이모지 포함], segs:[...], words:[...] } */
    fun payload(title: String, body: String): JSONObject {
        val all = analyze(body)
        val segs = JSONArray()
        for (s in all) if (s.kind != "plain" && s.kind != "divider") segs.put(segJson(s))
        val words = JSONArray()
        for (w in words(body)) words.put(segJson(w))
        val lines = JSONArray()
        for (s in all) lines.put(s.text)
        return JSONObject()
            .put("title", title)
            .put("lines", lines)
            .put("segs", segs)
            .put("words", words)
    }

    private fun segJson(s: Seg) = JSONObject()
        .put("text", s.text)
        .put("bold", s.bold)
        .put("color", s.color ?: JSONObject.NULL)
        .put("hilite", s.hilite ?: JSONObject.NULL)
        .put("size", s.size ?: JSONObject.NULL)

    // ---------------------------------------------------------------
    //  클립보드 붙여넣기 폴백용 HTML (서식 포함 본문 전체)
    // ---------------------------------------------------------------
    fun bodyHtml(body: String): String {
        val sb = StringBuilder()
        for (seg in analyze(body)) sb.append(paragraphHtml(seg))
        return sb.toString()
    }

    private fun paragraphHtml(seg: Seg): String {
        val styled = seg.bold || seg.color != null || seg.hilite != null || seg.size != null
        return if (styled) {
            val style = buildString {
                if (seg.bold) append("font-weight:bold;")
                seg.color?.let { append("color:").append(it).append(";") }
                seg.hilite?.let { append("background-color:").append(it).append(";padding:1px 3px;") }
                seg.size?.let { append("font-size:").append(it).append("px;") }
            }
            "<p style=\"$style\">${esc(seg.text)}</p>"
        } else {
            "<p>${inlineWrap(esc(seg.text))}</p>"
        }
    }

    private fun inlineWrap(escaped: String): String {
        var out = escaped
        for (w in IMPORTANT_WORDS) {
            if (out.contains(w)) {
                out = out.replace(
                    w,
                    "<b><span style=\"color:#d6336c;background-color:$WORD_HILITE;padding:1px 2px;\">$w</span></b>"
                )
            }
        }
        return out
    }

    // ---------------------------------------------------------------
    //  '간단(볼드) 서식' 클립보드용 HTML — 색/형광 없이 굵게만.
    // ---------------------------------------------------------------
    fun bodyHtmlBold(body: String): String {
        val sb = StringBuilder()
        for (seg in analyze(body)) {
            val inner = inlineBold(esc(seg.text))
            if (seg.bold) {
                sb.append("<p><b><strong>").append(inner).append("</strong></b></p>")
            } else {
                sb.append("<p>").append(inner).append("</p>")
            }
        }
        return sb.toString()
    }

    private fun inlineBold(escaped: String): String {
        var out = escaped
        for (w in IMPORTANT_WORDS) if (out.contains(w))
            out = out.replace(w, "<b><strong>$w</strong></b>")
        return out
    }

    // ---------------------------------------------------------------
    //  네이버 에디터 '붙여넣기' 전용 최소 서식 HTML
    // ---------------------------------------------------------------
    fun bodyHtmlNaver(body: String): String {
        val sb = StringBuilder()
        for (seg in analyze(body)) {
            if (seg.bold || seg.color != null || seg.hilite != null) {
                sb.append("<p>").append(naverSpan(esc(seg.text), seg)).append("</p>")
            } else {
                sb.append("<p>").append(inlineNaver(esc(seg.text))).append("</p>")
            }
        }
        return sb.toString()
    }

    /**
     * 한 줄 전체에 줄 서식(굵게/글자색/형광)을 감싼다.
     * 안드로이드 수신 앱은 CSS(style)를 무시하고 레거시 태그(b/font)만 받는 경우가 많아
     * <b> + <font color> + <span style>(배경색) 를 함께 써서 호환성을 최대화한다.
     */
    private fun naverSpan(escaped: String, seg: Seg): String {
        var inner = escaped
        seg.hilite?.let { inner = "<span style=\"background-color:$it;\">$inner</span>" }
        seg.color?.let { inner = "<font color=\"$it\"><span style=\"color:$it;\">$inner</span></font>" }
        if (seg.bold) inner = "<b><strong>$inner</strong></b>"
        return inner
    }

    /** 서식 없는 줄: 중요 단어를 단어별 파스텔(연녹/연분홍/연파랑/연주황)로 인라인 강조. */
    private fun inlineNaver(escaped: String): String {
        var out = escaped
        for (w in IMPORTANT_WORDS) {
            if (out.contains(w)) out = out.replace(
                w,
                "<b><strong><font color=\"${wordFg(w)}\">" +
                    "<span style=\"color:${wordFg(w)};background-color:${wordBg(w)};\">$w</span>" +
                    "</font></strong></b>"
            )
        }
        return out
    }

    private fun esc(s: String) =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
