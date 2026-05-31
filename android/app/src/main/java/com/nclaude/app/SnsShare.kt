package com.nclaude.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * SNS 교차 공유. 제목(런온 문장)을 후킹 블록으로 줄바꿈하고 끝에 글 URL 을 붙여 각 앱으로 보낸다.
 *
 * 현실적 한계(인텐트 방식 — 앱 샌드박스상 다른 앱 세션을 직접 못 씀):
 *  - X(트위터)·스레드·링크드인: 텍스트 프리필 잘 됨
 *  - 페이스북: 정책상 EXTRA_TEXT 프리필을 무시하고 URL 위주로만 처리
 *  - 인스타그램: 피드 텍스트 공유 미지원(이미지 위주) → 클립보드 복사로 보완
 *  보완책으로 항상 후킹 텍스트를 클립보드에 복사한다(어느 앱이든 붙여넣기 가능).
 */
object SnsShare {

    data class Platform(val id: String, val label: String, val pkg: String)

    val PLATFORMS = listOf(
        Platform("facebook", "페이스북", "com.facebook.katana"),
        Platform("linkedin", "링크드인", "com.linkedin.android"),
        Platform("instagram", "인스타그램", "com.instagram.android"),
        Platform("threads", "스레드", "com.instagram.barcelona"),
        Platform("x", "X", "com.twitter.android")
    )

    fun platform(id: String): Platform = PLATFORMS.first { it.id == id }

    // CTR 후킹 문구 — 기기에서 자유롭게 조정 가능
    private const val CTA = "👇 전체 내용 보기"

    // 각 핵심 줄 앞에 붙일 이모지 세트(‘다시 생성’ 때마다 세트가 회전 → 글 중간중간 이모지)
    private val MID_SETS = listOf(
        listOf("🔥", "✨", "💡", "👀", "✅", "🎯"),
        listOf("💥", "👉", "⭐", "🙌", "📌", "❤️"),
        listOf("😮", "🚀", "💬", "✔️", "👍", "🌟"),
        listOf("⚡", "💪", "🎁", "📣", "🤩", "🙆")
    )
    // 짧은 클릭 유도 한 줄(회전)
    private val CTAS = listOf(
        "👇 전체 내용 보기",
        "👉 자세히 보러가기",
        "👀 지금 확인하세요",
        "🔗 본문에서 계속",
        "👇 놓치지 마세요"
    )

    // 맨 앞 후킹 오프너 — '다시 생성' 때마다 회전(이모지뿐 아니라 '문구'가 바뀜)
    private val HOOK_LINES = listOf(
        "🚨 이거 모르면 진짜 손해예요",
        "😱 지금 안 보면 후회합니다",
        "💡 아는 사람만 아는 꿀팁",
        "🔥 다들 놓치는 핵심만 정리했어요",
        "👀 딱 30초, 끝까지 보세요",
        "⚠️ 모르면 큰일나는 정보",
        "✅ 이건 꼭 알고 가세요",
        "🤯 알고 나면 생각이 바뀝니다"
    )

    private val URL_RE = Regex("https?://\\S+")

    /**
     * 후킹 텍스트 생성(핵심만, 짧게 + 줄마다 이모지).
     *  구조:  {이모지} 핵심줄1 / {이모지} 핵심줄2 …  +  {짧은 CTA}  +  URL
     *  variant 가 바뀌면 이모지 세트 + CTA 가 회전 → ‘다시 생성’이 매번 다르게 동작.
     */
    fun buildHook(source: String, url: String, variant: Int = 0): String {
        val resolvedUrl = if (url.isNotBlank()) url else extractUrl(source)
        val lead = leadText(source, resolvedUrl)
        val blocks = splitHookBlocks(lead)
        val v = if (variant < 0) -variant else variant
        val emo = MID_SETS[v % MID_SETS.size]
        val cta = CTAS[v % CTAS.size]
        val hook = HOOK_LINES[v % HOOK_LINES.size]

        val sb = StringBuilder()
        sb.append(hook)                       // 회전 후킹 오프너 → 문구가 매번 달라짐
        if (blocks.isEmpty()) {
            if (lead.isNotBlank()) sb.append('\n').append(emo[0]).append(' ').append(lead)
        } else {
            for ((i, b) in blocks.withIndex()) {
                sb.append('\n').append(emo[i % emo.size]).append(' ').append(b)
            }
        }
        if (resolvedUrl.isNotBlank()) {
            if (sb.isNotEmpty()) sb.append("\n\n")
            sb.append(cta).append('\n').append(resolvedUrl)
        }
        return sb.toString()
    }

    // ───────────────────────────────────────────────────────────────
    //  플랫폼별 맞춤 문구 — 각 SNS 형식이 달라 따로 생성(복사·붙여넣기용)
    //   facebook : 길게(블록+이모지+CTA+URL · 링크 미리보기)
    //   threads  : 캐주얼·짧게(블록 3개+이모지+CTA+URL+해시태그 소량)
    //   x        : 아주 짧게(핵심 줄+해시태그+URL · 280자 목표)
    //   linkedin : 전문적(이모지 없이 문단+CTA+URL+해시태그)
    //   instagram: 캡션형(블록+이모지+'링크는 프로필'+해시태그 다수)
    // ───────────────────────────────────────────────────────────────
    fun buildFor(platformId: String, source: String, url: String, variant: Int = 0): String {
        val resolvedUrl = if (url.isNotBlank()) url else extractUrl(source)
        val lead = leadText(source, resolvedUrl)
        val blocks = splitHookBlocks(lead).ifEmpty {
            if (lead.isNotBlank()) listOf(lead) else emptyList()
        }
        if (blocks.isEmpty()) return ""
        val v = if (variant < 0) -variant else variant
        val emo = MID_SETS[v % MID_SETS.size]
        val cta = CTAS[v % CTAS.size]
        val hook = HOOK_LINES[v % HOOK_LINES.size]
        val tags = hashtags(lead)
        return when (platformId) {
            "x" -> buildX(blocks, emo, tags, resolvedUrl)
            "instagram" -> buildInstagram(blocks, emo, cta, tags, resolvedUrl, hook)
            "threads" -> buildThreads(blocks, emo, cta, tags, resolvedUrl, hook)
            "linkedin" -> buildLinkedIn(blocks, cta, tags, resolvedUrl, stripLeadEmoji(hook).trim())
            else -> buildFacebook(blocks, emo, cta, resolvedUrl, hook)
        }
    }

    private fun joinWithEmoji(blocks: List<String>, emo: List<String>): String {
        val sb = StringBuilder()
        for ((i, b) in blocks.withIndex()) {
            if (i > 0) sb.append('\n')
            sb.append(emo[i % emo.size]).append(' ').append(b)
        }
        return sb.toString()
    }

    private fun buildFacebook(
        blocks: List<String>, emo: List<String>, cta: String, url: String, hook: String
    ): String {
        val sb = StringBuilder(hook).append('\n').append(joinWithEmoji(blocks, emo))
        if (url.isNotBlank()) sb.append("\n\n").append(cta).append('\n').append(url)
        return sb.toString()
    }

    private fun buildThreads(
        blocks: List<String>, emo: List<String>, cta: String, tags: List<String>, url: String, hook: String
    ): String {
        val sb = StringBuilder(hook).append('\n').append(joinWithEmoji(blocks.take(3), emo))
        if (url.isNotBlank()) sb.append("\n\n").append(cta).append('\n').append(url)
        if (tags.isNotEmpty()) sb.append('\n').append(tags.take(3).joinToString(" "))
        return sb.toString()
    }

    private fun buildLinkedIn(
        blocks: List<String>, cta: String, tags: List<String>, url: String, hook: String
    ): String {
        // 링크드인은 이모지 없이 전문적으로 — 후킹 문구는 이모지를 떼고 맨 앞 한 줄로
        val sb = StringBuilder()
        if (hook.isNotBlank()) sb.append(hook).append("\n\n")
        sb.append(blocks.joinToString("\n\n"))
        if (url.isNotBlank()) sb.append("\n\n").append(cta).append('\n').append(url)
        if (tags.isNotEmpty()) sb.append("\n\n").append(tags.take(5).joinToString(" "))
        return sb.toString()
    }

    private fun buildX(blocks: List<String>, emo: List<String>, tags: List<String>, url: String): String {
        val limit = 270
        val tagStr = if (tags.isNotEmpty()) tags.take(2).joinToString(" ") else ""
        val reserve = (if (url.isNotBlank()) url.length + 1 else 0) +
            (if (tagStr.isNotEmpty()) tagStr.length + 1 else 0)
        val budget = (limit - reserve).coerceAtLeast(20)
        val sb = StringBuilder()
        for ((i, b) in blocks.withIndex()) {
            val piece = (if (i > 0) "\n" else "") + emo[i % emo.size] + " " + b
            if (sb.length + piece.length > budget) break
            sb.append(piece)
        }
        if (sb.isEmpty()) {
            val first = emo[0] + " " + blocks[0]
            sb.append(if (first.length > budget) first.substring(0, budget - 1) + "…" else first)
        }
        if (tagStr.isNotEmpty()) sb.append('\n').append(tagStr)
        if (url.isNotBlank()) sb.append('\n').append(url)
        return sb.toString()
    }

    private fun buildInstagram(
        blocks: List<String>, emo: List<String>, cta: String, tags: List<String>, url: String, hook: String
    ): String {
        val sb = StringBuilder(hook).append('\n').append(joinWithEmoji(blocks, emo))
        sb.append("\n\n").append(cta)
        if (url.isNotBlank()) sb.append("\n🔗 링크는 프로필에서 확인하세요\n").append(url)
        if (tags.isNotEmpty()) sb.append("\n\n").append(tags.joinToString(" "))
        return sb.toString()
    }

    // 핵심 텍스트에서 해시태그 자동 추출(카드에서 직접 수정 가능)
    private val STOP = hashSetOf(
        "그리고", "하지만", "그런데", "그래서", "우리", "오늘", "정말", "지금", "너무",
        "위해", "대한", "했다", "한다", "하는", "있는", "없는", "에서", "으로", "까지"
    )
    private val JOSA = listOf(
        "으로", "에서", "에게", "까지", "부터", "이라", "라고", "처럼", "보다", "마다",
        "은", "는", "이", "가", "을", "를", "의", "에", "도", "로", "과", "와"
    )
    private fun stripJosa(w: String): String {
        for (j in JOSA) if (w.length > j.length + 1 && w.endsWith(j)) return w.dropLast(j.length)
        return w
    }
    private fun hashtags(lead: String, max: Int = 8): List<String> {
        val seen = LinkedHashSet<String>()
        for (raw in lead.split(Regex("[\\s,./!?\"'()\\[\\]{}·:;~\\-…]+"))) {
            var w = raw.trim().trim('#')
            if (w.length < 2) continue
            w = stripJosa(w)
            if (w.length < 2 || w in STOP) continue
            if (!w.any { it.isLetter() }) continue
            if (w.length > 12) w = w.substring(0, 12)
            seen.add("#$w")
            if (seen.size >= max) break
        }
        return seen.toList()
    }

    private fun extractUrl(s: String): String = URL_RE.find(s)?.value ?: ""

    /**
     * URL/CTA 줄을 제외한 의미있는 줄을 모두 모아 한 덩어리로 만든다.
     * 원문(제목 한 줄 + URL)이든, 이미 후킹 변환된 본문(블록 여러 줄)이든
     * 동일하게 블록 분리가 되도록(= 재변환 멱등) 공백으로 합치는 게 핵심.
     */
    private fun leadText(source: String, url: String): String {
        val ctaSet = (CTAS + CTA).toHashSet()
        val hookSet = HOOK_LINES.map { stripLeadEmoji(it).trim() }.toHashSet()
        val parts = source.split('\n').map { it.trim() }
            .filter { line ->
                line.isNotEmpty() && !line.startsWith("http") && line !in ctaSet
            }
            .map { stripLeadEmoji(it).trim() }
            .filter { it.isNotEmpty() && !it.startsWith("http") && it !in hookSet }
        if (parts.isNotEmpty()) return parts.joinToString(" ")
        return if (url.isNotBlank()) source.replace(url, "").trim() else source.trim()
    }

    /** 줄 맨 앞의 이모지(변형 선택자·ZWJ 포함)와 공백을 제거 → 재변환 시 이모지 중복 방지. */
    private fun stripLeadEmoji(s: String): String {
        var i = 0
        val n = s.length
        while (i < n) {
            val c = s[i]
            when {
                c == ' ' || c == '\t' -> i++
                c.code == 0xFE0F || c.code == 0x200D -> i++
                Character.isHighSurrogate(c) && i + 1 < n && Character.isLowSurrogate(s[i + 1]) -> i += 2
                c.code in 0x2190..0x21FF -> i++
                c.code in 0x2300..0x27BF -> i++
                c.code in 0x2900..0x297F -> i++
                c.code in 0x2B00..0x2BFF -> i++
                else -> return s.substring(i)
            }
        }
        return s.substring(i)
    }

    /** 물음표/느낌표(전각 포함) 경계로 블록 분리(기호 유지), 마지막 잔여도 블록으로. */
    private fun splitHookBlocks(text: String): List<String> {
        val blocks = ArrayList<String>()
        val sb = StringBuilder()
        for (ch in text) {
            sb.append(ch)
            if (ch == '?' || ch == '!' || ch == '？' || ch == '！') {
                val b = sb.toString().trim()
                if (b.isNotEmpty()) blocks.add(b)
                sb.setLength(0)
            }
        }
        val rest = sb.toString().trim()
        if (rest.isNotEmpty()) blocks.add(rest)
        return blocks
    }

    fun isInstalled(ctx: Context, pkg: String): Boolean = try {
        ctx.packageManager.getPackageInfo(pkg, 0); true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /** 특정 플랫폼 공유 인텐트(앱 미설치 시 null → 호출측에서 chooser 폴백) */
    fun textIntent(ctx: Context, platformId: String, text: String): Intent? {
        val p = platform(platformId)
        if (!isInstalled(ctx, p.pkg)) return null
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            setPackage(p.pkg)
        }
    }

    /** 폴백: 시스템 공유 시트 */
    fun chooser(text: String): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        return Intent.createChooser(send, "공유")
    }
}
