package com.nclaude.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * SNS 교차 공유. 블로그 글 핵심을 후킹 요약으로 만들고 각 앱으로 공유 인텐트를 보낸다.
 *
 * 현실적 한계(인텐트 방식):
 *  - X(트위터)·스레드: 텍스트 프리필 잘 됨
 *  - 링크드인: 텍스트/URL 공유 가능(앱이 URL 위주로 처리하기도 함)
 *  - 페이스북: 정책상 EXTRA_TEXT 프리필을 무시하고 URL 위주로만 처리됨
 *  - 인스타그램: 피드 텍스트 공유 미지원(이미지 공유 위주) → 클립보드 복사로 보완
 *  보완책으로 항상 후킹 텍스트를 클립보드에 복사한다(앱에서 붙여넣기 가능).
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

    /** 본문에서 후킹 요약 생성 + 끝에 글 URL */
    fun buildHook(content: String, url: String): String {
        val lines = content.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return url
        val headline = lines.first()

        val hookLine = lines.drop(1).firstOrNull {
            !it.startsWith("❝") && !it.startsWith("#") && !isDivider(it) && it.length > 12
        }

        val tagLine = lines.lastOrNull { it.startsWith("#") }
        val tags = tagLine?.split(Regex("\\s+"))
            ?.filter { it.startsWith("#") }
            ?.take(5)
            ?.joinToString(" ")

        val sb = StringBuilder(headline)
        if (hookLine != null) sb.append("\n\n").append(trimTo(hookLine, 90))
        if (!tags.isNullOrBlank()) sb.append("\n\n").append(tags)
        if (url.isNotBlank()) sb.append("\n\n👉 ").append(url)
        return sb.toString()
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

    private fun isDivider(s: String) =
        s.isNotEmpty() && s.all { it == '─' || it == '—' || it == '-' || it == 'ㅡ' || it.isWhitespace() }

    private fun trimTo(s: String, n: Int) = if (s.length <= n) s else s.substring(0, n).trimEnd() + "…"
}
