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

    private val URL_RE = Regex("https?://\\S+")

    /**
     * 후킹 텍스트 생성.
     *  입력  "A? B? C"  +  url
     *  출력  "A?\n\nB?\n\nC\n\n\n👇 전체 내용 보기\n{url}"
     * 물음표/느낌표 경계로 블록을 나눠 줄바꿈, CTA 한 줄 + URL 을 끝에 붙인다.
     */
    fun buildHook(source: String, url: String): String {
        val resolvedUrl = if (url.isNotBlank()) url else extractUrl(source)
        val lead = leadText(source, resolvedUrl)
        val blocks = splitHookBlocks(lead)

        val sb = StringBuilder()
        if (blocks.isEmpty()) {
            if (lead.isNotBlank()) sb.append(lead)
        } else {
            for ((i, b) in blocks.withIndex()) {
                if (i > 0) sb.append("\n\n")
                sb.append(b)
            }
        }
        if (resolvedUrl.isNotBlank()) {
            if (sb.isNotEmpty()) sb.append("\n\n\n")
            if (CTA.isNotBlank()) sb.append(CTA).append('\n')
            sb.append(resolvedUrl)
        }
        return sb.toString()
    }

    private fun extractUrl(s: String): String = URL_RE.find(s)?.value ?: ""

    /**
     * URL/CTA 줄을 제외한 의미있는 줄을 모두 모아 한 덩어리로 만든다.
     * 원문(제목 한 줄 + URL)이든, 이미 후킹 변환된 본문(블록 여러 줄)이든
     * 동일하게 블록 분리가 되도록(= 재변환 멱등) 공백으로 합치는 게 핵심.
     */
    private fun leadText(source: String, url: String): String {
        val parts = source.split('\n').map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("http") && it != CTA }
        if (parts.isNotEmpty()) return parts.joinToString(" ")
        return if (url.isNotBlank()) source.replace(url, "").trim() else source.trim()
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
