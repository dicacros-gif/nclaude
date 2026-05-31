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

    private var currentAccount = Accounts.IDS[0]
    private val selectedPhotos = mutableListOf<Uri>()
    private var titleEdited = false

    // 포스팅 상태
    private var posting = false
    private var filled = false
    private var publishedUrl: String? = null

    // SNS 일괄 공유 큐
    private val batchQueue = ArrayDeque<String>()
    private var batchActive = false
    private var awaitingReturn = false

    private var filePathCallback: ValueCallback<Array<Uri>>? = null

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
                if (url != null && Accounts.isLoggedIn()) {
                    Accounts.saveCurrentFor(this@MainActivity, currentAccount)
                }
                if (posting && url != null) {
                    if (isPublishedUrl(url)) {
                        onPublished(url)
                        return
                    }
                    if (!filled && isEditorUrl(url)) {
                        filled = true
                        setStatus("에디터 로딩 대기 중…")
                        web.postDelayed({ injectAndFill() }, 2500)
                    }
                }
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: WebChromeClient.FileChooserParams?
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                val uris = selectedPhotos.toTypedArray()
                filePathCallback?.onReceiveValue(if (uris.isEmpty()) null else uris)
                filePathCallback = null
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
        form.visibility = View.GONE
        web.visibility = View.VISIBLE
        progress.visibility = View.VISIBLE
        setStatus("${currentAccount} 글쓰기 페이지 여는 중…")
        web.loadUrl(Accounts.writeUrl(currentAccount))
    }

    private fun injectAndFill() {
        if (!posting) return
        setStatus("제목·본문 입력 중…")
        web.evaluateJavascript(EditorJs.SCRIPT) {
            val payload: JSONObject =
                Formatter.payload(titleInput.text.toString(), contentInput.text.toString())
            web.postDelayed({
                web.evaluateJavascript("window.__NB_run($payload)", null)
            }, 600)
        }
    }

    /** JS → Kotlin 결과 수신 */
    inner class AndroidPoster {
        @JavascriptInterface
        fun onFilled(json: String) {
            runOnUiThread { handleFilled(json) }
        }

        @JavascriptInterface
        fun onImageButton(ok: Boolean) {
            runOnUiThread {
                setStatus(
                    if (ok) "사진 업로드 창 호출됨 — 업로드 후 '발행'을 눌러주세요"
                    else "사진 버튼을 찾지 못함 — 에디터에서 직접 사진을 추가해주세요"
                )
            }
        }
    }

    private fun handleFilled(json: String) {
        progress.visibility = View.GONE
        val report = try { JSONObject(json) } catch (e: Exception) { JSONObject() }
        val titleOk = report.optBoolean("titleOk")
        val bodyOk = report.optBoolean("bodyOk")
        val fmt = report.optInt("formatCount")
        val found = report.optBoolean("found")
        if (!found) {
            setStatus("에디터를 찾지 못했습니다. 로그인이 필요하거나 페이지 구조가 바뀌었을 수 있어요")
            return
        }
        setStatus("입력 완료 (제목 ${yn(titleOk)} · 본문 ${yn(bodyOk)} · 서식 ${fmt}곳)")
        if (selectedPhotos.isNotEmpty()) {
            web.postDelayed({
                setStatus("사진 ${selectedPhotos.size}장 업로드 시도 중…")
                web.evaluateJavascript("window.__NB_images()", null)
            }, 900)
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

    private fun hookText(): String =
        SnsShare.buildHook(contentInput.text.toString(), publishedUrl ?: "")

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
