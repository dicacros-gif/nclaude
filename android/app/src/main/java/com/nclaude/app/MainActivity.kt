package com.nclaude.app

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.bumptech.glide.Glide
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var form: ScrollView
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var titleInput: EditText
    private lateinit var contentInput: EditText
    private lateinit var photoStrip: LinearLayout
    private lateinit var photoCount: TextView
    private lateinit var btnClearPhotos: Button
    private lateinit var postedUrl: TextView
    private lateinit var accountGroup: MaterialButtonToggleGroup
    private lateinit var debugLog: TextView
    private lateinit var autoPublishSwitch: SwitchCompat

    private var currentAccount = Accounts.IDS[0]
    private val selectedPhotos = mutableListOf<Uri>()
    private var titleEdited = false

    // 포스팅 상태
    private var posting = false
    private var filled = false
    private var publishedUrl: String? = null

    // 사진 순차 삽입(일정 간격)
    private var photoSeq = false
    private var photoFeed: Uri? = null

    // SNS 일괄 공유 큐
    private val batchQueue = ArrayDeque<String>()
    private var batchActive = false
    private var awaitingReturn = false

    private val logLines = ArrayDeque<String>()

    private val pickPhotos =
        registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris ->
            if (uris.isNotEmpty()) {
                selectedPhotos.clear()
                selectedPhotos.addAll(uris)
                renderPhotos()
            }
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        web = findViewById(R.id.web)
        form = findViewById(R.id.form)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)
        titleInput = findViewById(R.id.titleInput)
        contentInput = findViewById(R.id.contentInput)
        photoStrip = findViewById(R.id.photoStrip)
        photoCount = findViewById(R.id.photoCount)
        btnClearPhotos = findViewById(R.id.btnClearPhotos)
        postedUrl = findViewById(R.id.postedUrl)
        accountGroup = findViewById(R.id.accountGroup)
        debugLog = findViewById(R.id.debugLog)
        debugLog.movementMethod = android.text.method.ScrollingMovementMethod()
        autoPublishSwitch = findViewById(R.id.autoPublishSwitch)

        setupWeb()
        setupForm()
        setupSns()
        setupAccounts()
    }

    // ---------------------------------------------------------------
    //  계정 전환
    // ---------------------------------------------------------------
    private fun setupAccounts() {
        findViewById<MaterialButton>(R.id.btnAcc1).text = Accounts.IDS[0]
        findViewById<MaterialButton>(R.id.btnAcc2).text = Accounts.IDS[1]
        accountGroup.check(R.id.btnAcc1)
        Accounts.applyTo(this, currentAccount) { had ->
            if (had) setStatus("${currentAccount} 세션 복원됨")
        }
        accountGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val target = if (checkedId == R.id.btnAcc1) Accounts.IDS[0] else Accounts.IDS[1]
            switchAccount(target)
        }
    }

    private fun switchAccount(target: String) {
        if (target == currentAccount) return
        Accounts.saveCurrentFor(this, currentAccount) // 나가는 계정 쿠키 저장
        currentAccount = target
        Accounts.applyTo(this, target) { had ->
            setStatus(if (had) "${target} 계정으로 전환됨" else "${target} : 포스팅 시 로그인이 필요합니다")
        }
    }

    // ---------------------------------------------------------------
    //  입력 폼
    // ---------------------------------------------------------------
    private fun setupForm() {
        findViewById<Button>(R.id.btnGenTitle).setOnClickListener {
            titleInput.setText(TitleGen.generate(contentInput.text.toString()))
            titleEdited = false
        }
        findViewById<Button>(R.id.btnPickPhotos).setOnClickListener {
            pickPhotos.launch(
                PickVisualMediaRequest.Builder()
                    .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    .build()
            )
        }
        btnClearPhotos.setOnClickListener {
            selectedPhotos.clear(); renderPhotos()
        }
        findViewById<Button>(R.id.btnPost).setOnClickListener { startPosting() }

        // 본문이 바뀌면 (사용자가 제목을 직접 수정하지 않았을 때) 제목 자동 갱신
        contentInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (!titleEdited) {
                    titleInput.setText(TitleGen.generate(contentInput.text.toString()))
                }
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        titleInput.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) titleEdited = true }
    }

    private fun renderPhotos() {
        photoStrip.removeAllViews()
        for (uri in selectedPhotos) {
            val iv = ImageView(this)
            val sz = dp(64)
            val lp = LinearLayout.LayoutParams(sz, sz)
            lp.marginEnd = dp(6)
            iv.layoutParams = lp
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            Glide.with(this).load(uri).into(iv)
            photoStrip.addView(iv)
        }
        if (selectedPhotos.isEmpty()) {
            photoCount.text = "선택된 사진 없음"
            btnClearPhotos.visibility = View.GONE
        } else {
            photoCount.text = "사진 ${selectedPhotos.size}장 선택됨"
            btnClearPhotos.visibility = View.VISIBLE
        }
    }

    // ---------------------------------------------------------------
    //  WebView / 포스팅
    // ---------------------------------------------------------------
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWeb() {
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(web, true)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.databaseEnabled = true
        web.settings.allowContentAccess = true
        web.settings.loadWithOverviewMode = true
        web.settings.useWideViewPort = true
        web.settings.builtInZoomControls = true
        web.settings.displayZoomControls = false
        // PC 스마트에디터(.se-*)를 받기 위해 데스크톱 UA 강제
        web.settings.userAgentString =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        web.addJavascriptInterface(AndroidPoster(), "AndroidPoster")

        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (url == null) return
                if (Accounts.isLoggedIn()) Accounts.saveCurrentFor(this@MainActivity, currentAccount)
                if (!posting) return
                if (isPublishedUrl(url)) { onPublished(url); return }
                if (filled) return
                // 로그인/리다이렉트를 거쳐 에디터 페이지가 뜰 때마다 (재)주입.
                // 실제 에디터 탐색·로그인 감지는 JS(__NB_run)가 자체 폴링으로 처리.
                if (isEditorUrl(url)) {
                    dbg("에디터 페이지: ${shortUrl(url)}")
                    injectFill()
                } else {
                    dbg("대기 페이지: ${shortUrl(url)}")
                }
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: WebChromeClient.FileChooserParams?
            ): Boolean {
                // 순차 모드면 1장씩, 아니면(폴백) 선택한 전체를 한 번에 공급
                val uris: Array<Uri> = if (photoSeq) {
                    photoFeed?.let { arrayOf(it) } ?: emptyArray()
                } else {
                    selectedPhotos.toTypedArray()
                }
                callback?.onReceiveValue(if (uris.isEmpty()) null else uris)
                dbg("파일선택창 → ${uris.size}장 공급")
                return true
            }
        }
    }

    private fun startPosting() {
        val content = contentInput.text.toString().trim()
        if (content.isEmpty()) {
            toast("본문을 입력하세요"); return
        }
        if (titleInput.text.isNullOrBlank()) {
            titleInput.setText(TitleGen.generate(content))
        }
        posting = true
        filled = false
        publishedUrl = null
        photoSeq = false
        photoFeed = null
        stageClipboardHtml(content)          // 자동 입력 실패 대비 '서식 포함 붙여넣기' 준비
        logLines.clear(); debugLog.text = ""
        debugLog.visibility = View.VISIBLE
        form.visibility = View.GONE
        web.visibility = View.VISIBLE
        progress.visibility = View.VISIBLE
        setStatus("${currentAccount} 글쓰기 페이지 여는 중…")
        dbg("포스팅 시작 · 계정 ${currentAccount}")
        web.loadUrl(Accounts.writeUrl(currentAccount))
    }

    /** 서식 포함 본문 HTML 을 클립보드에 올려둔다(자동 입력이 약할 때 길게 눌러 붙여넣기). */
    private fun stageClipboardHtml(content: String) {
        try {
            val html = Formatter.bodyHtml(content)
            val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cb.setPrimaryClip(ClipData.newHtmlText("post", content, html))
            dbg("서식 본문 클립보드 복사됨(붙여넣기 폴백)")
        } catch (e: Exception) {
            dbg("클립보드 준비 실패 ${e.message}")
        }
    }

    private fun injectFill() {
        if (!posting) return
        setStatus("에디터 탐색·입력 중…")
        val payload: JSONObject =
            Formatter.payload(titleInput.text.toString(), contentInput.text.toString())
        web.evaluateJavascript(EditorJs.SCRIPT) {
            web.evaluateJavascript("window.__NB_run($payload)", null)
        }
    }

    /** JS → Kotlin 콜백 */
    inner class AndroidPoster {
        @JavascriptInterface
        fun onFilled(json: String) = runOnUiThread { handleFilled(json) }

        @JavascriptInterface
        fun onImageButton(ok: Boolean) =
            runOnUiThread { dbg(if (ok) "사진창 호출됨" else "사진버튼 실패") }

        @JavascriptInterface
        fun log(msg: String) = runOnUiThread { dbg(msg) }

        @JavascriptInterface
        fun onNeedLogin() = runOnUiThread {
            progress.visibility = View.GONE
            setStatus("로그인이 필요합니다 — 로그인하면 자동으로 이어집니다")
            dbg("로그인 대기")
        }

        @JavascriptInterface
        fun onPublishClicked(ok: Boolean) =
            runOnUiThread { dbg(if (ok) "발행 확정 클릭됨" else "발행 버튼 확인 필요(수동 발행)") }
    }

    private fun handleFilled(json: String) {
        val report = try { JSONObject(json) } catch (e: Exception) { JSONObject() }
        if (!report.optBoolean("found")) {
            progress.visibility = View.GONE
            setStatus("에디터를 찾지 못함 — 로그인/구조 확인. 본문은 클립보드에 있으니 길게 눌러 붙여넣기 가능")
            dbg("found=false")
            return
        }
        filled = true
        val titleOk = report.optBoolean("titleOk")
        val bodyOk = report.optBoolean("bodyOk")
        val bodyLen = report.optInt("bodyLen")
        val paraCount = report.optInt("paraCount")
        val fmt = report.optInt("lineSegs") + report.optInt("words")
        setStatus("입력: 제목 ${yn(titleOk)} · 본문 ${yn(bodyOk)}(${bodyLen}자) · 서식 ${fmt}곳")
        if (bodyLen < 5) {
            setStatus("자동 입력이 약합니다 — 에디터 본문을 길게 눌러 '붙여넣기'(서식 포함) 하세요")
            dbg("본문 거의 비어있음 → 클립보드 붙여넣기 권장")
        }
        if (selectedPhotos.isNotEmpty()) {
            web.postDelayed({ insertPhotosSequentially(paraCount) }, 1000)
        } else {
            afterPhotos()
        }
    }

    /** 사진을 본문 문단에 일정 간격으로 1장씩 순차 삽입 */
    private fun insertPhotosSequentially(paraCount: Int) {
        val n = selectedPhotos.size
        if (n == 0) { afterPhotos(); return }
        val base = if (paraCount < 1) 1 else paraCount
        val indices = (0 until n).map { ((it + 1) * base) / (n + 1) }
        photoSeq = true
        dbg("사진 ${n}장 · 위치 ${indices}")
        fun step(i: Int) {
            if (!posting) { photoSeq = false; return }
            if (i >= n) {
                photoSeq = false; photoFeed = null
                dbg("사진 삽입 완료")
                afterPhotos()
                return
            }
            photoFeed = selectedPhotos[i]
            setStatus("사진 ${i + 1}/${n} 삽입 중…")
            web.evaluateJavascript("window.__NB_imageAt(${indices[i]})", null)
            web.postDelayed({ step(i + 1) }, 4000)
        }
        step(0)
    }

    /** 입력·사진 후 단계: 자동발행이면 발행 클릭, 아니면 사용자 발행 대기 */
    private fun afterPhotos() {
        progress.visibility = View.GONE
        if (autoPublishSwitch.isChecked) {
            setStatus("발행 시도 중… (실패 시 직접 '발행')")
            dbg("자동발행 ON → __NB_publish")
            web.postDelayed({ web.evaluateJavascript("window.__NB_publish()", null) }, 1000)
        } else {
            setStatus("입력 완료 — 검토 후 '발행'을 눌러주세요")
        }
    }

    private fun onPublished(url: String) {
        posting = false
        filled = false
        publishedUrl = url
        progress.visibility = View.GONE
        web.visibility = View.GONE
        form.visibility = View.VISIBLE
        postedUrl.text = url
        setStatus("게시 완료! SNS 공유를 진행하세요")
        toast("블로그 게시 완료")
    }

    private fun isEditorUrl(url: String) =
        url.contains("PostWriteForm") || url.contains("Redirect=Write") ||
            url.contains("/postwrite") || url.contains("editor")

    private fun isPublishedUrl(url: String): Boolean {
        if (posting && Regex("blog\\.naver\\.com/${Regex.escape(currentAccount)}/\\d{5,}")
                .containsMatchIn(url)
        ) return true
        return posting && url.contains("logNo=") && !url.contains("Write")
    }

    // ---------------------------------------------------------------
    //  SNS 공유
    // ---------------------------------------------------------------
    private fun setupSns() {
        findViewById<Button>(R.id.btnFb).setOnClickListener { shareOne("facebook") }
        findViewById<Button>(R.id.btnLi).setOnClickListener { shareOne("linkedin") }
        findViewById<Button>(R.id.btnIg).setOnClickListener { shareOne("instagram") }
        findViewById<Button>(R.id.btnTh).setOnClickListener { shareOne("threads") }
        findViewById<Button>(R.id.btnX).setOnClickListener { shareOne("x") }
        findViewById<Button>(R.id.btnPostAll).setOnClickListener { shareAll() }
    }

    private fun hookText(): String {
        val src = titleInput.text?.toString()?.takeIf { it.isNotBlank() }
            ?: contentInput.text.toString()
        return SnsShare.buildHook(src, publishedUrl ?: "")
    }

    private fun copyHook(text: String) {
        val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText("hook", text))
    }

    private fun shareOne(platformId: String) {
        if (publishedUrl == null) toast("먼저 블로그 포스팅을 완료하면 URL 이 포함됩니다")
        val text = hookText()
        copyHook(text)
        val intent = SnsShare.textIntent(this, platformId, text)
        try {
            startActivity(intent ?: SnsShare.chooser(text))
        } catch (e: Exception) {
            startActivity(SnsShare.chooser(text))
        }
    }

    private fun shareAll() {
        val text = hookText()
        copyHook(text)
        batchQueue.clear()
        batchQueue.addAll(SnsShare.PLATFORMS.map { it.id })
        batchActive = true
        toast("후킹 요약을 클립보드에 복사했습니다. 앱마다 순서대로 열립니다")
        launchNextBatch()
    }

    private fun launchNextBatch() {
        if (batchQueue.isEmpty()) {
            batchActive = false
            setStatus("SNS 일괄 공유 완료")
            return
        }
        val id = batchQueue.removeFirst()
        val text = hookText()
        val intent = SnsShare.textIntent(this, id, text)
        awaitingReturn = true
        try {
            startActivity(intent ?: SnsShare.chooser(text))
        } catch (e: Exception) {
            try { startActivity(SnsShare.chooser(text)) } catch (_: Exception) {}
        }
    }

    override fun onResume() {
        super.onResume()
        if (batchActive && awaitingReturn) {
            awaitingReturn = false
            web.postDelayed({ if (batchActive) launchNextBatch() }, 400)
        }
    }

    // ---------------------------------------------------------------
    //  유틸
    // ---------------------------------------------------------------
    private fun setStatus(msg: String) {
        status.text = msg
        status.visibility = View.VISIBLE
    }

    /** 화면 디버그 로그(마지막 60줄 유지, 자동 스크롤) */
    private fun dbg(msg: String) {
        logLines.addLast(msg)
        while (logLines.size > 60) logLines.removeFirst()
        debugLog.text = logLines.joinToString("\n")
        val layout = debugLog.layout ?: return
        val y = layout.getLineTop(debugLog.lineCount) - debugLog.height
        debugLog.scrollTo(0, if (y > 0) y else 0)
    }

    private fun shortUrl(url: String): String =
        if (url.length <= 60) url else url.substring(0, 60) + "…"

    private fun yn(b: Boolean) = if (b) "O" else "X"
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (posting && web.visibility == View.VISIBLE) {
            if (web.canGoBack()) {
                web.goBack()
            } else {
                posting = false
                web.visibility = View.GONE
                progress.visibility = View.GONE
                form.visibility = View.VISIBLE
                setStatus("포스팅 취소됨")
            }
            return
        }
        super.onBackPressed()
    }

    override fun onPause() {
        super.onPause()
        if (Accounts.isLoggedIn()) Accounts.saveCurrentFor(this, currentAccount)
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        web.removeJavascriptInterface("AndroidPoster")
        web.destroy()
        super.onDestroy()
    }
}
