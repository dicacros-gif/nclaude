package com.nclaude.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * 본문을 분류해 가독성 서식(볼드/색상/하이라이트)을 부여한다.
 *  - 줄 단위(segs)     : 에디터 문단 통째 서식 → JS applyLineSegs
 *  - 단어 단위(words)  : 중요 단어를 본문 어디에 나오든 인라인 하이라이트 → JS applyWords
 *  - bodyHtml()        : 자동 입력이 실패할 때를 대비한 '서식 포함 전체 붙여넣기'용 HTML(클립보드)
 *
 * 줄 규칙:
 *  - 첫 줄(글머리)        : 파랑 볼드
 *  - ❝ 소제목            : 볼드 + 노랑 형광(사용자 요청)
 *  - # 해시태그          : 파랑
 *  - 키워드 핵심 문장     : 자홍 볼드 + 연노랑 형광
 *  - ── 구분선          : 서식 없음
 */
object Formatter {

    data class Seg(
        val text: String,
        val bold: Boolean,
        val color: String?,
        val hilite: String?,
        val size: Int? = null
    )

    const val WORD_HILITE = "#fff34f"   // (구) 중요 단어 노랑 형광 — JS 경로 호환용
    const val WORD_COLOR = "#d6336c"    // (구) 중요 단어 글자색 — JS 경로 호환용

    // 소제목: 연한 노랑 형광(요청 — 더 연하게)
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

    // 본문 어디에 나오든 인라인 강조할 '단어'(임팩트어) — 기기에서 조정 가능
    val IMPORTANT_WORDS = listOf(
        "급등", "급락", "폭발", "충격", "역대", "최초", "최대", "최고", "사상",
        "수혜", "피해", "승자", "경쟁", "전망", "핵심", "주의", "반드시",
        "논란", "호재", "악재", "변수", "기회", "위기"
    )

    private fun classify(line: String, isFirst: Boolean): Seg? = when {
        isDivider(line) -> null
        isFirst -> Seg(line, true, "#1d4ed8", null, 19)
        line.startsWith("❝") || isHead(line) -> Seg(line, true, "#111111", HILITE_SUBHEAD, 22)
        line.startsWith("#") -> Seg(line, false, "#2563eb", null)
        line.length <= 60 && HILITE_KEYWORDS.any { line.contains(it) } ->
            Seg(line, true, SENT_FG, SENT_BG)
        else -> null
    }

    /** 소제목 마커 — ❝ 외에 【 ▶ ■ ◆ ✔ Q. A. 로 시작하는 줄도 소제목 처리 */
    private fun isHead(s: String): Boolean {
        val t = s.trim()
        return t.startsWith("【") || t.startsWith("▶") || t.startsWith("■") ||
            t.startsWith("◆") || t.startsWith("✔") || t.startsWith("Q.") || t.startsWith("A.")
    }

    /** 줄 단위 서식 세그먼트(서식 없는 줄은 제외) */
    fun segments(content: String): List<Seg> {
        val lines = content.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return emptyList()
        val out = ArrayList<Seg>()
        for ((i, ln) in lines.withIndex()) {
            classify(ln, i == 0)?.let { out.add(it) }
        }
        return out
    }

    /** 본문에 실제로 등장하는 중요 단어만 인라인 강조 대상으로 */
    fun words(content: String): List<Seg> {
        val out = ArrayList<Seg>()
        for (w in IMPORTANT_WORDS) {
            if (content.contains(w)) out.add(Seg(w, true, null, WORD_HILITE))
        }
        return out
    }

    /**
     * 미리보기 렌더용: 모든 줄(빈 줄 포함)의 줄 서식 정보.
     * 서식 없는 줄은 plain Seg(텍스트만)로 반환 → 화면에 Spannable 로 그릴 때 사용.
     */
    fun lineSpecs(content: String): List<Seg> {
        val out = ArrayList<Seg>()
        var firstDone = false
        for (raw in content.split('\n')) {
            val ln = raw.trim()
            if (ln.isEmpty()) { out.add(Seg("", false, null, null, null)); continue }
            val isFirst = !firstDone
            firstDone = true
            out.add(classify(ln, isFirst) ?: Seg(ln, false, null, null, null))
        }
        return out
    }

    private fun isDivider(s: String): Boolean =
        s.isNotEmpty() && s.all { it == '─' || it == '—' || it == '-' || it == 'ㅡ' || it.isWhitespace() }

    /** JS payload: { title, lines:[빈 줄 포함], segs:[...], words:[...] } */
    fun payload(title: String, body: String): JSONObject {
        val segs = JSONArray()
        for (s in segments(body)) segs.put(segJson(s))
        val words = JSONArray()
        for (w in words(body)) words.put(segJson(w))
        val lines = JSONArray()
        for (ln in body.split('\n')) lines.put(ln.replace("\r", ""))
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
    //  자동 입력이 안 될 때 에디터 본문을 길게 눌러 '붙여넣기' 하면 그대로 들어간다.
    // ---------------------------------------------------------------
    fun bodyHtml(body: String): String {
        val sb = StringBuilder()
        var firstDone = false
        for (raw in body.split('\n')) {
            val ln = raw.trim()
            if (ln.isEmpty()) {
                sb.append("<p><br></p>")
                continue
            }
            val isFirst = !firstDone
            firstDone = true
            sb.append(paragraphHtml(ln, classify(ln, isFirst)))
        }
        return sb.toString()
    }

    private fun paragraphHtml(line: String, seg: Seg?): String {
        val styled = seg != null &&
            (seg.bold || seg.color != null || seg.hilite != null || seg.size != null)
        return if (styled) {
            val style = buildString {
                append("line-height:1.7;")
                if (seg!!.bold) append("font-weight:bold;")
                seg.color?.let { append("color:").append(it).append(";") }
                seg.hilite?.let { append("background-color:").append(it).append(";padding:1px 3px;") }
                seg.size?.let { append("font-size:").append(it).append("px;") }
            }
            "<p style=\"$style\">${esc(line)}</p>"
        } else {
            "<p style=\"line-height:1.7\">${inlineWrap(esc(line))}</p>"
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
    //  소제목·글머리·핵심 문장 줄은 통째로 볼드, 중요 단어는 인라인 볼드.
    // ---------------------------------------------------------------
    fun bodyHtmlBold(body: String): String {
        val sb = StringBuilder()
        var firstDone = false
        for (raw in body.split('\n')) {
            val ln = raw.trim()
            if (ln.isEmpty()) { sb.append("<p><br></p>"); continue }
            val isFirst = !firstDone
            firstDone = true
            val seg = classify(ln, isFirst)
            val inner = inlineBold(esc(ln))
            if (seg != null && seg.bold) {
                sb.append("<p><strong style=\"font-weight:700\">").append(inner).append("</strong></p>")
            } else {
                sb.append("<p>").append(inner).append("</p>")
            }
        }
        return sb.toString()
    }

    private fun inlineBold(escaped: String): String {
        var out = escaped
        for (w in IMPORTANT_WORDS) if (out.contains(w))
            out = out.replace(w, "<strong style=\"font-weight:700\">$w</strong>")
        return out
    }

    // ---------------------------------------------------------------
    //  네이버 에디터 '붙여넣기' 전용 최소 서식 HTML
    //  SmartEditor(모바일/PC)가 안전하게 받아주는 태그만 사용 → 굵게/글자색/형광이
    //  '실제 서식'으로 들어가고, 태그가 글자로 노출되지 않게 한다.
    //  허용 범위만 사용: <p> 줄 구분, <b>/<strong> 굵게, <span style="color/background-color">.
    //  (line-height·padding·font-size 등은 일부 에디터가 통째로 무시하거나
    //   거꾸로 텍스트로 노출시키는 경우가 있어 의도적으로 제외)
    //
    //  ※ 검증(웹 리서치): ClipData.newHtmlText(plain, html) 는 text/plain + text/html 을
    //    함께 올리는 '정상' 방식이며 데스크톱 브라우저 붙여넣기에선 서식이 유지된다.
    //    그러나 안드로이드 WebView/크로미움은 contenteditable 붙여넣기에서 text/html 을
    //    무시하고 text/plain 으로 강등하는 사례가 많다(크로미움 이슈 382393144 등).
    //    → 갤럭시에서 '확실한' 서식 입력 경로는 클립보드가 아니라 에디터 DOM 에
    //      execCommand(bold/foreColor/hiliteColor)로 '직접' 주입하는 것(EditorJs).
    //      이 HTML 은 (a)외부 앱 붙여넣기 폴백, (b)서식 유지하는 앱(삼성노트 등)용이다.
    // ---------------------------------------------------------------
    fun bodyHtmlNaver(body: String): String {
        val sb = StringBuilder()
        var firstDone = false
        for (raw in body.split('\n')) {
            val ln = raw.trim()
            if (ln.isEmpty()) { sb.append("<p><br></p>"); continue }
            val isFirst = !firstDone
            firstDone = true
            val seg = classify(ln, isFirst)
            if (seg != null && (seg.bold || seg.color != null || seg.hilite != null)) {
                sb.append("<p>").append(naverSpan(esc(ln), seg)).append("</p>")
            } else {
                sb.append("<p>").append(inlineNaver(esc(ln))).append("</p>")
            }
        }
        return sb.toString()
    }

    /** 한 줄 전체에 줄 서식(굵게/글자색/형광)을 감싼다. 볼드는 태그+인라인 두 경로로 보장. */
    private fun naverSpan(escaped: String, seg: Seg): String {
        val style = buildString {
            if (seg.bold) append("font-weight:700;")
            seg.color?.let { append("color:").append(it).append(";") }
            seg.hilite?.let { append("background-color:").append(it).append(";") }
        }
        var inner = if (style.isNotEmpty()) "<span style=\"$style\">$escaped</span>" else escaped
        if (seg.bold) inner = "<strong>$inner</strong>"
        return inner
    }

    /** 서식 없는 줄: 중요 단어를 단어별 파스텔(연녹/연분홍/연파랑/연주황)로 인라인 강조. */
    private fun inlineNaver(escaped: String): String {
        var out = escaped
        for (w in IMPORTANT_WORDS) {
            if (out.contains(w)) out = out.replace(
                w,
                "<strong><span style=\"font-weight:700;color:${wordFg(w)};" +
                    "background-color:${wordBg(w)};\">$w</span></strong>"
            )
        }
        return out
    }

    private fun esc(s: String) =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
