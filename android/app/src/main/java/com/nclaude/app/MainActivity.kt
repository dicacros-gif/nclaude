package com.nclaude.app

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
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
    private lateinit var previewBold: TextView
    private lateinit var previewRich: TextView
    private lateinit var photoStrip: LinearLayout
    private lateinit var photoCount: TextView
    private lateinit var btnClearPhotos: Button
    private lateinit var postedUrl: TextView
    private lateinit var accountGroup: MaterialButtonToggleGroup
    private lateinit var debugLog: TextView
    private lateinit var autoPublishSwitch: SwitchCompat
    private lateinit var snsInput: EditText
    private lateinit var navBar: LinearLayout
    private lateinit var manualBar: LinearLayout
    private lateinit var postBar: LinearLayout
    private lateinit var btnCollapseChrome: Button
    private lateinit var hookOutput: EditText

    private var currentAccount = Accounts.IDS[0]
    private val selectedPhotos = mutableListOf<Uri>()
    private var titleEdited = false
    private var suppressTitleWatcher = false
    private var chromeCollapsed = false      // 포스팅 중 상단 도구·로그 접힘 상태
    private var guideShown = false           // 포스팅 1회당 사용법 안내 1번만 자동 표시
    private var hookVariant = 0              // ‘다시 생성’ 누를 때마다 후킹 조합 회전

    private var blogGuideShown = false       // 블로그 앱 붙여넣기 안내 1회
    private val snsCardInputs = HashMap<String, EditText>()  // SNS별 카드 입력칸

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
        previewBold = findViewById(R.id.previewBold)
        previewRich = findViewById(R.id.previewRich)
        photoStrip = findViewById(R.id.photoStrip)
        photoCount = findViewById(R.id.photoCount)
        btnClearPhotos = findViewById(R.id.btnClearPhotos)
        postedUrl = findViewById(R.id.postedUrl)
        accountGroup = findViewById(R.id.accountGroup)
        debugLog = findViewById(R.id.debugLog)
        debugLog.movementMethod = android.text.method.ScrollingMovementMethod()
        autoPublishSwitch = findViewById(R.id.autoPublishSwitch)
        snsInput = findViewById(R.id.snsInput)
        navBar = findViewById(R.id.navBar)
        manualBar = findViewById(R.id.manualBar)
        postBar = findViewById(R.id.postBar)
        btnCollapseChrome = findViewById(R.id.btnCollapseChrome)
        hookOutput = findViewById(R.id.hookOutput)

        setupWeb()
        setupForm()
        setupSns()
        setupAccounts()
        setupBars()
        showPostingChrome(false)
    }

    // ---------------------------------------------------------------
    //  상단 바: 폼 빠른 이동(홈) + 수동 입력(포스팅 중, 자동 실패 대비)
    // ---------------------------------------------------------------
    private fun setupBars() {
        findViewById<Button>(R.id.btnScrollTop).setOnClickListener {
            form.smoothScrollTo(0, 0)
        }
        findViewById<Button>(R.id.btnScrollBottom).setOnClickListener {
            form.post { form.fullScroll(View.FOCUS_DOWN) }
        }
        findViewById<Button>(R.id.btnFillTitle).setOnClickListener { manualFill(title = true) }
        findViewById<Button>(R.id.btnTitleCopy).setOnClickListener { copyTitleToClipboard() }
        findViewById<Button>(R.id.btnTitlePaste).setOnClickListener { manualPaste(title = true) }
        findViewById<Button>(R.id.btnFillBody).setOnClickListener { manualFill(title = false) }
        findViewById<Button>(R.id.btnBodyCopy).setOnClickListener { copyBodyToClipboard() }
        findViewById<Button>(R.id.btnBodyPaste).setOnClickListener { manualPaste(title = false) }
        findViewById<Button>(R.id.btnFillPhoto).setOnClickListener { manualPhoto() }
        btnCollapseChrome.setOnClickListener {
            chromeCollapsed = !chromeCollapsed
            applyChromeCollapsed()
        }
        findViewById<Button>(R.id.btnGuide).setOnClickListener { showManualGuide() }
    }

    /** 포스팅 중 상단 도구·로그를 접거나 펼친다(웹뷰 공간 확보). */
    private fun applyChromeCollapsed() {
        if (!posting) return
        debugLog.visibility = if (chromeCollapsed) View.GONE else View.VISIBLE
        manualBar.visibility = if (chromeCollapsed) View.GONE else View.VISIBLE
        btnCollapseChrome.text =
            if (chromeCollapsed) "▸ 입력도구·로그 펼치기" else "▾ 입력도구·로그 접기"
    }

    /** 자동/수동 입력이 안 될 때 ‘어디를 눌러 복사·붙여넣기’ 하는지 단계별 안내 */
    private fun showManualGuide() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("수동 입력 가이드 (복사 → 붙여넣기)")
            .setMessage(
                "자동 입력이 안 되면 복사 → 붙여넣기가 가장 확실해요.\n\n" +
                    "① 상단 '제목 복사'를 누르세요\n" +
                    "    → 제목이 클립보드에 복사됩니다\n" +
                    "② 네이버 '제목' 칸을 손가락으로 꾹(1~2초) 길게 누르세요\n" +
                    "③ 떠오르는 메뉴에서 '붙여넣기'를 누르세요\n\n" +
                    "④ 상단 '내용 복사'를 누르고\n" +
                    "⑤ 네이버 '본문' 칸을 길게 눌러 '붙여넣기'\n\n" +
                    "⑥ 사진은 '사진 입력' 버튼으로 올리세요\n\n" +
                    "※ 앱의 '제목 붙여넣기/내용 붙여넣기' 버튼은 에디터에 바로 넣어줘요(되면 더 편함)."
            )
            .setPositiveButton("확인", null)
            .show()
    }

    /** 제목을 일반 텍스트로 클립보드에 복사 */
    private fun copyTitleToClipboard() {
        val t = titleInput.text?.toString()?.takeIf { it.isNotBlank() }
        if (t == null) { toast("먼저 제목을 입력/생성하세요"); return }
        val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText("title", t))
        toast("제목 복사됨")
        setStatus("제목을 클립보드에 복사했어요")
    }

    /** 본문을 서식 포함 HTML 로 클립보드에 복사 */
    private fun copyBodyToClipboard() {
        val c = contentInput.text?.toString()?.takeIf { it.isNotBlank() }
        if (c == null) { toast("먼저 본문을 입력하세요"); return }
        val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val html = Formatter.bodyHtmlNaver(c)
        cb.setPrimaryClip(ClipData.newHtmlText("body", c, html))
        toast("강조 서식 복사됨")
        setStatus("강조 서식을 복사했어요")
    }

    /** 본문을 '볼드만' 적용한 HTML 로 클립보드에 복사 */
    private fun copyBoldBody() {
        val c = contentInput.text?.toString()?.takeIf { it.isNotBlank() }
        if (c == null) { toast("먼저 본문을 입력하세요"); return }
        val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val html = Formatter.bodyHtmlBold(c)
        cb.setPrimaryClip(ClipData.newHtmlText("body", c, html))
        toast("볼드 서식 복사됨")
        setStatus("볼드 서식을 복사했어요")
    }

    /** 본문 입력이 바뀔 때마다 두 미리보기 박스를 다시 그린다(볼드 전용 / 강조 서식). */
    private fun refreshPreviews() {
        val c = contentInput.text?.toString().orEmpty()
        if (c.isBlank()) {
            val ph = "본문을 입력하면 여기에 미리보기가 표시됩니다"
            previewBold.text = ph
            previewRich.text = ph
            return
        }
        previewBold.text = buildPreview(c, rich = false)
        previewRich.text = buildPreview(c, rich = true)
    }

    /**
     * 본문을 Spannable 로 렌더(미리보기). rich=false 면 볼드만, rich=true 면 색/형광/크기까지.
     * 줄 단위 서식(소제목·글머리·핵심문장) + 단어 단위 강조를 함께 적용한다.
     * → Html.fromHtml 로는 배경 형광색이 칠해지지 않으므로 직접 Span 으로 그린다.
     */
    private fun buildPreview(content: String, rich: Boolean): CharSequence {
        val sb = SpannableStringBuilder()
        val specs = Formatter.lineSpecs(content)
        for ((i, seg) in specs.withIndex()) {
            val start = sb.length
            sb.append(seg.text)
            val end = sb.length
            if (end > start) {
                if (seg.bold) {
                    sb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                if (rich) {
                    seg.color?.let {
                        runCatching {
                            sb.setSpan(ForegroundColorSpan(Color.parseColor(it)),
                                start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                    }
                    seg.hilite?.let {
                        runCatching {
                            sb.setSpan(BackgroundColorSpan(Color.parseColor(it)),
                                start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                    }
                    seg.size?.let {
                        sb.setSpan(AbsoluteSizeSpan(it, true),
                            start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
            }
            // 줄 안에 등장하는 중요 단어를 인라인 강조(볼드, rich 면 글자색+형광)
            applyWordSpans(sb, start, seg.text, rich)
            if (i < specs.size - 1) sb.append('\n')
        }
        return sb
    }

    /** 한 줄(lineText) 안의 중요 단어를 모두 찾아 볼드(+rich 면 색/형광) 스팬을 더한다. */
    private fun applyWordSpans(
        sb: SpannableStringBuilder, lineStart: Int, lineText: String, rich: Boolean
    ) {
        for (w in Formatter.IMPORTANT_WORDS) {
            var from = 0
            while (true) {
                val idx = lineText.indexOf(w, from)
                if (idx < 0) break
                val s = lineStart + idx
                val e = s + w.length
                sb.setSpan(StyleSpan(Typeface.BOLD), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (rich) {
                    runCatching {
                        sb.setSpan(ForegroundColorSpan(Color.parseColor(Formatter.WORD_COLOR)),
                            s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    runCatching {
                        sb.setSpan(BackgroundColorSpan(Color.parseColor(Formatter.WORD_HILITE)),
                            s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
                from = idx + w.length
            }
        }
    }

    /** 붙여넣기(append): 에디터의 기존 내용을 지우지 않고 커서 끝에 제목/본문을 추가 */
    private fun manualPaste(title: Boolean) {
        if (web.visibility != View.VISIBLE) {
            toast("먼저 '블로그 포스팅'으로 글쓰기 페이지를 여세요"); return
        }
        val payload: JSONObject =
            Formatter.payload(titleInput.text.toString(), contentInput.text.toString())
        val fn = if (title) "__NB_pasteTitle" else "__NB_pasteBody"
        setStatus(if (title) "제목 붙여넣기 시도…" else "내용 붙여넣기 시도…")
        dbg("붙여넣기 호출 $fn")
        web.evaluateJavascript(EditorJs.SCRIPT) {
            web.evaluateJavascript("window.$fn($payload)", null)
        }
    }

    /** 포스팅 중이면 수동 입력 바 + 접기/사용법 바, 아니면 폼 이동 바를 보여준다. */
    private fun showPostingChrome(posting: Boolean) {
        navBar.visibility = if (posting) View.GONE else View.VISIBLE
        postBar.visibility = if (posting) View.VISIBLE else View.GONE
        if (posting) {
            chromeCollapsed = false
            applyChromeCollapsed()
        } else {
            manualBar.visibility = View.GONE
            debugLog.visibility = View.GONE
        }
    }

    /** 자동 입력이 실패했을 때 사용자가 직접 누르는 제목/내용 단독 입력 */
    private fun manualFill(title: Boolean) {
        if (web.visibility != View.VISIBLE) {
            toast("먼저 '블로그 포스팅'으로 글쓰기 페이지를 여세요"); return
        }
        val payload: JSONObject =
            Formatter.payload(titleInput.text.toString(), contentInput.text.toString())
        val fn = if (title) "__NB_fillTitle" else "__NB_fillBody"
        setStatus(if (title) "제목 수동 입력 시도…" else "내용 수동 입력 시도…")
        dbg("수동 호출 $fn")
        web.evaluateJavascript(EditorJs.SCRIPT) {
            web.evaluateJavascript("window.$fn($payload)", null)
        }
    }

    /** 자동 사진 삽입이 실패했을 때 선택한 사진 전체로 파일 선택창을 직접 띄운다. */
    private fun manualPhoto() {
        if (web.visibility != View.VISIBLE) {
            toast("먼저 '블로그 포스팅'으로 글쓰기 페이지를 여세요"); return
        }
        if (selectedPhotos.isEmpty()) { toast("먼저 '사진 선택'으로 사진을 고르세요"); return }
        photoSeq = false   // 순차 모드 해제 → onShowFileChooser 가 선택한 전체를 공급
        photoFeed = null
        setStatus("사진 수동 업로드 — 파일 선택창 호출")
        dbg("수동 사진 호출 __NB_images (${selectedPhotos.size}장)")
        web.evaluateJavascript(EditorJs.SCRIPT) {
            web.evaluateJavascript("window.__NB_images()", null)
        }
    }

    // ---------------------------------------------------------------
    //  계정 전환
    // ---------------------------------------------------------------
    private fun setupAccounts() {
        val lastId = Accounts.getLastAccount(this)
        if (lastId != null && lastId in Accounts.IDS) {
            currentAccount = lastId
        }
        val btnAcc1 = findViewById<MaterialButton>(R.id.btnAcc1)
        val btnAcc2 = findViewById<MaterialButton>(R.id.btnAcc2)
        btnAcc1.text = Accounts.IDS[0]
        btnAcc2.text = Accounts.IDS[1]
        
        // 선택된 아이디 녹색 강조 (네이버 그린)
        val greenCsl = android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(android.graphics.Color.parseColor("#03c75a"), android.graphics.Color.parseColor("#333333"))
        )
        btnAcc1.backgroundTintList = greenCsl
        btnAcc2.backgroundTintList = greenCsl
        btnAcc1.setTextColor(android.graphics.Color.WHITE)
        btnAcc2.setTextColor(android.graphics.Color.WHITE)
        
        if (currentAccount == Accounts.IDS[1]) {
            accountGroup.check(R.id.btnAcc2)
        } else {
            accountGroup.check(R.id.btnAcc1)
        }

        Accounts.applyTo(this, currentAccount) { had ->
            updateSessionStatus(currentAccount, had)
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
        Accounts.saveLastAccount(this, target) // 마지막 사용 계정 저장
        Accounts.applyTo(this, target) { had ->
            updateSessionStatus(target, had)
        }
    }

    private fun updateSessionStatus(id: String, hasSession: Boolean) {
        if (hasSession) {
            setStatus("● ${id} 로그인 세션 활성")
        } else {
            setStatus("○ ${id} 세션 없음 (포스팅 시 로그인 필요)")
        }
    }

    // ---------------------------------------------------------------
    //  입력 폼
    // ---------------------------------------------------------------
    @SuppressLint("ClickableViewAccessibility")
    private fun setupForm() {
        findViewById<Button>(R.id.btnGenTitle).setOnClickListener {
            setTitleProgrammatically(TitleGen.generate(contentInput.text.toString()))
            titleEdited = false
            regenHook()
        }
        findViewById<Button>(R.id.btnCopyTitleHome).setOnClickListener { copyTitleToClipboard() }
        findViewById<Button>(R.id.btnDeleteContent).setOnClickListener {
            contentInput.setText("")
            toast("본문이 삭제되었습니다")
        }
        findViewById<Button>(R.id.btnOpenBlogApp).setOnClickListener { openNaverBlogApp() }
        findViewById<Button>(R.id.btnCopyBold).setOnClickListener { copyBoldBody() }
        findViewById<Button>(R.id.btnCopyRich).setOnClickListener { copyBodyToClipboard() }
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
                    setTitleProgrammatically(TitleGen.generate(contentInput.text.toString()))
                }
                refreshPreviews()
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        // 사용자가 제목을 직접 고치면 그 값을 유지(자동 갱신 중단) + 후킹 문구 자동 갱신
        titleInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (!suppressTitleWatcher) titleEdited = true
                regenHook()
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        enableInnerScroll(contentInput)
        refreshPreviews()
    }

    /** 제목을 코드로 채울 때(자동생성 등) — TextWatcher 가 '사용자 수정'으로 오인하지 않게 */
    private fun setTitleProgrammatically(t: String) {
        suppressTitleWatcher = true
        titleInput.setText(t)
        suppressTitleWatcher = false
    }

    /** maxLines 로 고정된 칸이 바깥 ScrollView 에 스크롤을 뺏기지 않고 내부 스크롤되게 */
    @SuppressLint("ClickableViewAccessibility")
    private fun enableInnerScroll(v: View) {
        v.setOnTouchListener { view, ev ->
            view.parent?.requestDisallowInterceptTouchEvent(true)
            if (ev.actionMasked == android.view.MotionEvent.ACTION_UP ||
                ev.actionMasked == android.view.MotionEvent.ACTION_CANCEL
            ) {
                view.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
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
    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
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
        // PC 버전 글쓰기(스마트에디터 ONE) 자동입력을 위해 데스크톱 UA 강제
        web.settings.userAgentString =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        web.addJavascriptInterface(AndroidPoster(), "AndroidPoster")

        // 웹뷰 터치 포커스 버그 보정: 입력창을 탭해도 포커스/키보드/커서가
        // 안 잡히는 문제 → 탭 시 직접 requestFocus + 터치모드 포커스 허용.
        // (커서 깜빡임은 하드웨어 가속 필요 → Manifest 에서 명시 활성화)
        web.isFocusable = true
        web.isFocusableInTouchMode = true
        web.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN,
                android.view.MotionEvent.ACTION_UP ->
                    if (!v.hasFocus()) v.requestFocus()
            }
            false
        }

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
            setTitleProgrammatically(TitleGen.generate(content))
        }

        // 로그인 세션 여부 표시
        val hasSession = Accounts.hasSession(this, currentAccount)
        updateSessionStatus(currentAccount, hasSession)
        
        posting = true
        filled = false
        publishedUrl = null
        photoSeq = false
        photoFeed = null
        guideShown = false
        stageClipboardHtml(content)          // 자동 입력 실패 대비 '서식 포함 붙여넣기' 준비
        logLines.clear(); debugLog.text = ""
        debugLog.visibility = View.VISIBLE
        form.visibility = View.VISIBLE
        web.visibility = View.VISIBLE

        // 스플릿 뷰 설정: 폼과 웹뷰가 공간을 반씩 차지하게 함 (weight 적용)
        (form.layoutParams as LinearLayout.LayoutParams).weight = 1f
        (web.layoutParams as LinearLayout.LayoutParams).weight = 1f
        form.requestLayout()
        web.requestLayout()

        showPostingChrome(true)
        progress.visibility = View.VISIBLE
        setStatus("${currentAccount} 글쓰기 페이지 여는 중…")
        dbg("포스팅 시작 · 계정 ${currentAccount}")
        web.loadUrl(Accounts.writeUrl(currentAccount))
    }

    /** 서식 포함 본문 HTML 을 클립보드에 올려둔다(자동 입력이 약할 때 길게 눌러 붙여넣기). */
    private fun stageClipboardHtml(content: String) {
        try {
            val html = Formatter.bodyHtmlNaver(content)
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
        fun onManualResult(kind: String, ok: Boolean, len: Int, found: Boolean) =
            runOnUiThread { handleManualResult(kind, ok, len, found) }

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
        dbg("입력결과 제목=${yn(titleOk)} 본문=${yn(bodyOk)} ${bodyLen}자 서식=${fmt}")

        // 본문이 실제로 들어가지 않았으면(자동 입력 약함) 사진 단계로 진행하지 않는다.
        // → '사진 입력 중' 같은 잘못된 진행 메시지를 막고, 수동 복사/붙여넣기를 안내.
        if (bodyLen < 5) {
            progress.visibility = View.GONE
            setStatus("자동 입력이 안 됐어요 — 상단 '내용 복사' 후 본문칸을 길게 눌러 붙여넣기 (❓사용법)")
            dbg("본문 ${bodyLen}자 → 사진 단계 중단, 수동 입력 안내")
            return
        }

        setStatus("입력: 제목 ${yn(titleOk)} · 본문 ${yn(bodyOk)}(${bodyLen}자) · 서식 ${fmt}곳")
        if (selectedPhotos.isNotEmpty()) {
            web.postDelayed({ insertPhotosSequentially(paraCount) }, 1000)
        } else {
            afterPhotos()
        }
    }

    /** 수동 입력/붙여넣기 결과를 정직하게 표시(읽기 검증된 글자수 기반) */
    private fun handleManualResult(kind: String, ok: Boolean, len: Int, found: Boolean) {
        val label = when (kind) {
            "title" -> "제목 입력"
            "titlePaste" -> "제목 붙여넣기"
            "body" -> "내용 입력"
            "bodyPaste" -> "내용 붙여넣기"
            else -> kind
        }
        if (!found) {
            setStatus("$label 실패 — 글쓰기 페이지(에디터)를 먼저 여세요")
            dbg("$label: 입력칸 못찾음")
            return
        }
        if (ok && len > 0) {
            setStatus("$label 확인: O (${len}자 입력됨)")
            dbg("$label 성공 ${len}자")
        } else {
            val isTitle = kind.startsWith("title")
            val copyBtn = if (isTitle) "제목 복사" else "내용 복사"
            setStatus("$label 실패 — '$copyBtn' 누른 뒤 에디터칸을 길게 눌러 붙여넣기 하세요 (❓사용법)")
            dbg("$label 실패(0자) → 복사/붙여넣기 안내")
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
        
        // 스플릿 뷰 해제: 폼이 다시 전체 공간을 차지하게 함
        (form.layoutParams as LinearLayout.LayoutParams).weight = 1f
        form.requestLayout()

        showPostingChrome(false)
        postedUrl.text = url
        // '공유하기 복사'를 직접 누르는 대신, 같은 결과(제목+모바일URL)를 공유 원문칸에 채운다.
        // 원문칸이 채워지면 watcher 가 후킹 메시지를 자동 변환한다.
        autofillShareSource(url)
        setStatus("게시 완료! 공유 원문·후킹 메시지가 자동 생성됐어요 — 확인 후 '후킹 복사'/SNS 공유")
        toast("블로그 게시 완료 · 후킹 메시지 생성됨")
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
        buildSnsCards(findViewById(R.id.snsCards))
        findViewById<Button>(R.id.btnGenHook).setOnClickListener {
            hookVariant++                       // 매번 다른 후킹 조합으로 회전
            regenHook(force = true)
            toast("후킹 문구를 새로 생성했어요")
        }
        findViewById<Button>(R.id.btnCopyHook).setOnClickListener { copyHookOutput() }

        // 공유 원문이 바뀌면 후킹 메시지 자동 변환(제목/URL 변경은 제목 watcher·onPublished 에서 갱신)
        snsInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { regenHook() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        enableInnerScroll(snsInput)
        enableInnerScroll(hookOutput)
    }

    /**
     * '공유하기 복사' 자동화 대체: 게시 직후 공유 '원문'(제목 + 모바일URL)을 원문칸에 채운다.
     * 원문이 바뀌면 snsInput watcher 가 후킹 메시지를 자동 변환한다.
     */
    private fun autofillShareSource(url: String?) {
        val title = titleInput.text?.toString()?.takeIf { it.isNotBlank() }
            ?: TitleGen.generate(contentInput.text.toString())
        val murl = url?.let { mobileShareUrl(it) } ?: ""
        val source = if (murl.isNotBlank()) "$title\n$murl" else title
        snsInput.setText(source)   // → watcher 가 regenHook 실행
        regenHook()                // 즉시 1회 보장
    }

    /**
     * 공유 원문/제목/URL 로 후킹 메시지를 만들어 hookOutput 박스에 채운다(자동 갱신).
     * 원문에 URL 이 있으면 그것을, 없으면 게시 URL(모바일)을 사용.
     */
    private fun regenHook(force: Boolean = false) {
        val source = snsInput.text?.toString()?.takeIf { it.isNotBlank() }
            ?: titleInput.text?.toString()?.takeIf { it.isNotBlank() }
            ?: contentInput.text.toString()
        if (source.isBlank()) {
            if (force) toast("공유 원문이나 제목을 먼저 입력하세요")
            return
        }
        val urlInSource = Regex("https?://\\S+").find(source)?.value
        val url = urlInSource ?: publishedUrl?.let { mobileShareUrl(it) } ?: ""
        hookOutput.setText(SnsShare.buildHook(source, url, hookVariant))
        regenSnsCards()
    }

    /** 후킹 메시지 박스의 현재 문구를 클립보드로 복사 */
    private fun copyHookOutput() {
        val t = hookOutput.text?.toString()?.takeIf { it.isNotBlank() }
            ?: run { regenHook(); hookOutput.text?.toString() }
        if (t.isNullOrBlank()) { toast("공유 원문을 먼저 입력하세요"); return }
        copyHook(t)
        toast("후킹 문구를 클립보드에 복사했습니다")
        setStatus("후킹 문구 복사됨")
    }

    /** 게시 URL 을 모바일 공유 형식(m.blog.naver.com/{id}/{logNo})으로 변환 */
    private fun mobileShareUrl(url: String): String {
        val logNo = Regex("logNo=(\\d+)").find(url)?.groupValues?.get(1)
            ?: Regex("naver\\.com/[^/]+/(\\d{5,})").find(url)?.groupValues?.get(1)
        return if (logNo != null) "https://m.blog.naver.com/$currentAccount/$logNo" else url
    }

    /** 공유에 쓸 텍스트: 후킹 메시지 박스 내용(없으면 즉석 생성) */
    private fun hookText(): String {
        hookOutput.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        regenHook()
        return hookOutput.text?.toString()?.trim().orEmpty()
    }

    private fun copyHook(text: String) {
        val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText("hook", text))
    }

    // ---------------------------------------------------------------
    //  앱 열기 + 복사 → 붙여넣기 (웹뷰 자동입력 우회)
    // ---------------------------------------------------------------

    /** 네이버 블로그 앱 대신 웹뷰 스플릿 뷰로 열기 */
    private fun openNaverBlogApp() {
        posting = true // 스플릿 뷰 활성화를 위해 posting 상태로 간주
        form.visibility = View.VISIBLE
        web.visibility = View.VISIBLE
        
        // 스플릿 뷰 설정
        (form.layoutParams as LinearLayout.LayoutParams).weight = 1f
        (web.layoutParams as LinearLayout.LayoutParams).weight = 1f
        form.requestLayout()
        web.requestLayout()
        
        showPostingChrome(true)
        progress.visibility = View.VISIBLE
        setStatus("${currentAccount} 블로그 홈으로 이동 중…")
        web.loadUrl(Accounts.homeUrl(currentAccount))
    }

    /** 플레이스토어(없으면 웹)로 해당 패키지 페이지 열기 */
    private fun openStore(pkg: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")))
        } catch (e: Exception) {
            try {
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=$pkg")
                    )
                )
            } catch (_: Exception) {}
        }
    }

    private fun openApp(pkg: String, label: String) {
        val intent = packageManager.getLaunchIntentForPackage(pkg)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } else {
            toast("$label 앱이 없습니다 — 스토어로 이동합니다")
            openStore(pkg)
        }
    }

    /** SNS 카드 입력칸의 현재 문구를 클립보드로 복사 */
    private fun copyPlatform(id: String) {
        val input = snsCardInputs[id] ?: return
        val t = input.text?.toString()?.takeIf { it.isNotBlank() }
        if (t == null) { toast("먼저 공유 원문을 입력/생성하세요"); return }
        copyHook(t)
        toast("${SnsShare.platform(id).label} 문구 복사됨 — 앱에서 길게 눌러 붙여넣기")
    }

    /** SNS별 카드 문구를 (원문/제목/URL 기준) 다시 생성해 각 입력칸에 채운다. */
    private fun regenSnsCards() {
        if (snsCardInputs.isEmpty()) return
        val source = snsInput.text?.toString()?.takeIf { it.isNotBlank() }
            ?: titleInput.text?.toString()?.takeIf { it.isNotBlank() }
            ?: contentInput.text.toString()
        if (source.isBlank()) return
        val urlInSource = Regex("https?://\\S+").find(source)?.value
        val url = urlInSource ?: publishedUrl?.let { mobileShareUrl(it) } ?: ""
        for (p in SnsShare.PLATFORMS) {
            snsCardInputs[p.id]?.setText(SnsShare.buildFor(p.id, source, url, hookVariant))
        }
    }

    /** 카드 상단 안내(플랫폼 형식 요약) */
    private fun platformHint(id: String): String = when (id) {
        "facebook" -> "길게 · 이모지 · 링크 미리보기"
        "linkedin" -> "전문적 · 이모지 없이 · 해시태그"
        "instagram" -> "캡션형 · 링크는 프로필 · 해시태그 많이"
        "threads" -> "캐주얼 · 짧게 · 해시태그 소량"
        "x" -> "아주 짧게(280자) · 해시태그 2개"
        else -> ""
    }

    /** SNS별 카드(라벨 + 편집 가능한 문구칸 + 복사/앱열기)를 코드로 생성 */
    private fun buildSnsCards(container: LinearLayout) {
        container.removeAllViews()
        snsCardInputs.clear()
        for (p in SnsShare.PLATFORMS) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(0xFF161616.toInt())
                setPadding(dp(10), dp(8), dp(10), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
            }

            val labelRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setBackgroundColor(when(p.id) {
                    "facebook" -> 0xFF1877F2.toInt()
                    "linkedin" -> 0xFF0A66C2.toInt()
                    "instagram" -> 0xFFE4405F.toInt()
                    "threads" -> 0xFF000000.toInt()
                    "x" -> 0xFF000000.toInt()
                    else -> 0xFF555555.toInt()
                })
                setPadding(dp(8), dp(4), dp(8), dp(4))
            }

            val iconText = when(p.id) {
                "facebook" -> "FB"
                "linkedin" -> "IN"
                "instagram" -> "IG"
                "threads" -> "TH"
                "x" -> "X "
                else -> ""
            }

            val label = TextView(this).apply {
                text = "$iconText ${p.label}  ·  ${platformHint(p.id)}"
                setTextColor(android.graphics.Color.WHITE)
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
            }
            labelRow.addView(label)
            card.addView(labelRow)

            val input = EditText(this).apply {
                setTextColor(0xFFFFE27A.toInt())
                setHintTextColor(0xFF5a5a5a.toInt())
                hint = "공유 원문을 넣으면 ${p.label} 형식으로 자동 생성됩니다"
                textSize = 13f
                setBackgroundColor(0xFF1C1C1C.toInt())
                setPadding(dp(10), dp(8), dp(10), dp(8))
                gravity = android.view.Gravity.TOP
                inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE
                setHorizontallyScrolling(false)
                minLines = 2
                maxLines = 3
                isVerticalScrollBarEnabled = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) }
            }
            enableInnerScroll(input)
            card.addView(input)
            snsCardInputs[p.id] = input

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) }
            }
            val btnCopy = Button(this).apply {
                text = "📋 복사"
                textSize = 12f
                backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF444444.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
                setOnClickListener { copyPlatform(p.id) }
            }
            val btnOpen = Button(this).apply {
                text = "📱 ${p.label} 열기"
                textSize = 12f
                backgroundTintList = android.content.res.ColorStateList.valueOf(when(p.id) {
                    "facebook" -> 0xFF1877F2.toInt()
                    "linkedin" -> 0xFF0A66C2.toInt()
                    "instagram" -> 0xFFE4405F.toInt()
                    else -> 0xFF03c75a.toInt()
                })
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply { marginStart = dp(6) }
                setOnClickListener { copyPlatform(p.id); openApp(p.pkg, p.label) }
            }
            row.addView(btnCopy)
            row.addView(btnOpen)
            card.addView(row)

            container.addView(card)
        }
        regenSnsCards()
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
                
                // 스플릿 뷰 해제: 폼이 다시 전체 공간을 차지하게 함
                (form.layoutParams as LinearLayout.LayoutParams).weight = 1f
                form.requestLayout()

                showPostingChrome(false)
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
