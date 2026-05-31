package com.nclaude.app

import android.content.Context
import android.webkit.CookieManager

/**
 * 두 개의 네이버 계정(dicajohn / macdcross)을 쿠키 스냅샷으로 관리한다.
 *
 * 한 WebView 의 CookieManager 는 한 번에 한 네이버 세션만 들고 있으므로,
 * 계정 전환 시 (1) 현재 계정 쿠키를 저장 → (2) 전체 쿠키 삭제 →
 * (3) 대상 계정의 저장된 쿠키 복원 순으로 처리해 두 로그인을 모두 유지한다.
 *
 * NID_AUT / NID_SES 등 httpOnly 인증 쿠키도 CookieManager(네이티브 저장소)에서는
 * 읽을 수 있으므로 스냅샷이 가능하다.
 */
object Accounts {

    val IDS = listOf("dicajohn", "macdcross")

    // 모바일 글쓰기(터치 입력/붙여넣기가 더 잘 됨). PC 에디터가 입력이 안 돼서 모바일로 전환.
    fun writeUrl(id: String) = "https://m.blog.naver.com/$id?Redirect=Write&"
    fun homeUrl(id: String) = "https://m.blog.naver.com/$id"

    private const val PREFS = "nclaude_accounts"
    private val HOSTS = listOf(
        "https://naver.com",
        "https://www.naver.com",
        "https://nid.naver.com",
        "https://blog.naver.com"
    )

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 현재 CookieManager 에 들어있는 네이버 쿠키를 "n=v; n2=v2" 문자열로 병합 */
    fun snapshotCurrent(): String {
        val cm = CookieManager.getInstance()
        val map = LinkedHashMap<String, String>()
        for (h in HOSTS) {
            val ck = cm.getCookie(h) ?: continue
            for (pair in ck.split(";")) {
                val p = pair.trim()
                val eq = p.indexOf('=')
                if (eq > 0) map[p.substring(0, eq)] = p.substring(eq + 1)
            }
        }
        return map.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /** NID 인증 쿠키 존재 여부로 로그인 상태 판단 */
    fun isLoggedIn(): Boolean {
        val snap = snapshotCurrent()
        return snap.contains("NID_SES") || snap.contains("NID_AUT")
    }

    fun saveCurrentFor(ctx: Context, id: String) {
        val snap = snapshotCurrent()
        if (snap.isNotBlank() && (snap.contains("NID_SES") || snap.contains("NID_AUT"))) {
            prefs(ctx).edit().putString("ck_$id", snap).apply()
        }
    }

    fun load(ctx: Context, id: String): String? = prefs(ctx).getString("ck_$id", null)

    fun hasSession(ctx: Context, id: String): Boolean = !load(ctx, id).isNullOrBlank()

    /**
     * 대상 계정으로 전환: 전체 쿠키 삭제 후 저장된 쿠키를 복원.
     * @param done (hadSession) -> Unit  저장된 세션이 있었는지 콜백
     */
    fun applyTo(ctx: Context, id: String, done: (Boolean) -> Unit) {
        val cm = CookieManager.getInstance()
        cm.removeAllCookies {
            val snap = load(ctx, id)
            val had = !snap.isNullOrBlank()
            if (had) {
                for (pair in snap!!.split(";")) {
                    val p = pair.trim()
                    if (p.isEmpty()) continue
                    cm.setCookie("https://www.naver.com", "$p; Domain=.naver.com; Path=/; Secure")
                }
            }
            cm.flush()
            done(had)
        }
    }
}
