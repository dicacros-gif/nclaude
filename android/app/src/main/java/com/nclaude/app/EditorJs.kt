package com.nclaude.app

/**
 * 네이버 스마트에디터 ONE 자동 입력 스크립트.
 *
 * 진입점(코틀린이 호출):
 *   - window.__NB_run(payload)   : 에디터 준비될 때까지 자체 폴링 → 제목/본문/서식 입력 → AndroidPoster.onFilled(report)
 *   - window.__NB_imageAt(idx)   : idx 번째 문단 끝으로 커서 이동 후 '사진' 버튼 클릭(파일 선택창 유발)
 *   - window.__NB_images()       : 현재 위치에서 '사진' 버튼 클릭 → AndroidPoster.onImageButton(ok)
 *   - window.__NB_publish()      : '발행' 버튼 + 설정 패널 확정 클릭(best-effort) → AndroidPoster.onPublishClicked(ok)
 *
 * 콜백(코틀린 AndroidPoster): log(s), onFilled(json), onImageButton(b), onNeedLogin(), onPublishClicked(b)
 *
 * 에디터는 보통 iframe#mainFrame(같은 출처) 안에 있으므로 contentDocument 로 진입한다.
 * 셀렉터/타이밍은 네이버 업데이트 시 조정이 필요할 수 있다(미드저니 EXTRACT_JS 와 동일한 성격).
 * 주의: Kotlin raw string 이므로 JS 안에서 '$' 문자는 절대 쓰지 않는다(템플릿 충돌 방지).
 */
object EditorJs {
    const val SCRIPT = """
(function(){
  if (window.__NB_DEFINED) return;
  window.__NB_DEFINED = true;
  window.__NB_DONE = false;
  window.__NB_RUNNING = false;

  function log(m){ try{ AndroidPoster.log(''+m); }catch(e){} }

  function edoc(){
    try {
      var f = document.getElementById('mainFrame');
      if (f && f.contentDocument){
        var cd = f.contentDocument;
        if (cd.querySelector('.se-content, .se-documentTitle, [contenteditable="true"]')) return cd;
      }
    } catch(e){}
    try {
      var ifr = document.querySelectorAll('iframe');
      for (var i=0;i<ifr.length;i++){
        try { var d = ifr[i].contentDocument;
          if (d && d.querySelector('.se-documentTitle, .se-content')) return d; } catch(e){}
      }
    } catch(e){}
    return document;
  }
  function win(d){ return d.defaultView || window; }

  function looksLikeLogin(){
    try{
      var h = (location.host||'');
      if (h.indexOf('nid.naver.com')>=0) return true;
      if (document.querySelector('#id, #pw, input[name="id"], input[name="pw"]')) return true;
    }catch(e){}
    return false;
  }

  function closePopups(d){
    var sels = ['.se-popup-button-cancel','.__se_pop_layer .se-popup-button-cancel',
                '.se-popup-button-close','button.se_popup_close','.se-help-panel-close-button'];
    for (var i=0;i<sels.length;i++){
      var ns = d.querySelectorAll(sels[i]);
      for (var j=0;j<ns.length;j++){ try{ ns[j].click(); }catch(e){} }
    }
    var btns = d.querySelectorAll('button, a');
    for (var k=0;k<btns.length;k++){
      var t = (btns[k].textContent||'').replace(/\s/g,'');
      if (t==='취소'||t==='닫기'||t==='새로작성'||t==='새글작성'){ try{ btns[k].click(); }catch(e){} }
    }
  }

  function titleEl(d){
    return d.querySelector('.se-documentTitle .se-text-paragraph')
        || d.querySelector('.se-section-documentTitle .se-text-paragraph')
        || d.querySelector('.se-title-text .se-text-paragraph');
  }
  function bodyParas(d){
    return d.querySelectorAll('.se-content .se-text-paragraph, .se-component.se-text .se-text-paragraph');
  }
  function firstBodyEl(d){
    var el = d.querySelector('.se-component.se-text .se-text-paragraph')
          || d.querySelector('.se-content .se-text-paragraph');
    if (el) return el;
    var t = titleEl(d);
    var ces = d.querySelectorAll('[contenteditable="true"]');
    for (var i=0;i<ces.length;i++){ if (ces[i]!==t) return ces[i]; }
    return null;
  }

  function selectAll(d, el){
    var w = win(d); el.focus();
    var sel = w.getSelection(); var r = d.createRange();
    r.selectNodeContents(el); sel.removeAllRanges(); sel.addRange(r);
  }
  function caretEnd(d, el){
    var w = win(d); el.focus();
    var sel = w.getSelection(); var r = d.createRange();
    r.selectNodeContents(el); r.collapse(false); sel.removeAllRanges(); sel.addRange(r);
  }

  function esc(s){ return (''+s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;'); }
  function plainLen(el){ return (el && el.textContent ? el.textContent : '').replace(/\s/g,'').length; }

  // 문단(.se-text-paragraph)이 직접 editable 이 아닐 때를 대비해 contenteditable 호스트로 포커스
  function focusHost(d, el){
    var n = el;
    while(n && n.getAttribute){
      if (n.getAttribute('contenteditable')==='true'){ try{ n.focus(); }catch(e){} break; }
      n = n.parentNode;
    }
    try{ el.focus(); }catch(e){}
  }

  function typeTitle(d, el, text, clear){
    if(!el) return false;
    focusHost(d, el);
    if (clear===false){ caretEnd(d, el); }
    else { selectAll(d, el); try{ d.execCommand('delete',false,null); }catch(e){} }
    var ok=false;
    try{ ok = d.execCommand('insertText',false,text); }catch(e){ ok=false; }
    if (!ok || plainLen(el)===0){            // 폴백: 직접 주입 + input 이벤트
      try{
        if (clear===false){ el.textContent = (el.textContent||'') + text; }
        else { el.textContent = text; }
        var IE = win(d).InputEvent || win(d).Event;
        el.dispatchEvent(new IE('input',{bubbles:true}));
        ok = plainLen(el)>0;
      }catch(e){}
    }
    return ok;
  }

  // 줄 단위로 입력해 실제 문단을 생성(줄간격 보존)
  function typeBody(d, el, lines, clear){
    if(!el) return false;
    focusHost(d, el);
    if (clear===false){ caretEnd(d, el); if((el.textContent||'').length){ try{ d.execCommand('insertParagraph',false,null); }catch(e){} } }
    else { selectAll(d, el); try{ d.execCommand('delete',false,null); }catch(e){} }
    var any=false;
    for (var i=0;i<lines.length;i++){
      if (i>0){
        try{ if(!d.execCommand('insertParagraph',false,null)) d.execCommand('insertText',false,'\n'); }
        catch(e){ try{ d.execCommand('insertText',false,'\n'); }catch(e2){} }
      }
      var ln = lines[i] || '';
      if (ln.length){
        try{ if (d.execCommand('insertText',false,ln)) any=true; }catch(e){}
      }
    }
    if (!any || plainLen(el)===0){           // 폴백 1: execCommand insertHTML
      try{
        var html='';
        for (var j=0;j<lines.length;j++){ var L=lines[j]||''; html += '<p>'+(L?esc(L):'<br>')+'</p>'; }
        focusHost(d, el);
        if (clear===false){ caretEnd(d, el); } else { selectAll(d, el); }
        var okH=false;
        try{ okH = d.execCommand('insertHTML', false, html); }catch(e){ okH=false; }
        if (!okH || plainLen(el)===0){        // 폴백 2: innerHTML 직접 주입 + input 이벤트
          if (clear===false){ el.innerHTML = (el.innerHTML||'') + html; }
          else { el.innerHTML = html; }
        }
        var IE2 = win(d).InputEvent || win(d).Event;
        el.dispatchEvent(new IE2('input',{bubbles:true}));
        any = plainLen(el)>0;
      }catch(e){}
    }
    return any;
  }

  // 줄(문단) 통째 서식: 정규화 텍스트가 일치하는 문단을 찾아 적용
  function applyLineSegs(d, segs){
    var count = 0;
    var paras = bodyParas(d);
    for (var s=0;s<segs.length;s++){
      var seg = segs[s];
      var key = (seg.text||'').replace(/\s/g,'');
      if (!key) continue;
      for (var i=0;i<paras.length;i++){
        var pt = (paras[i].textContent||'').replace(/\s/g,'');
        if (pt && pt===key){
          selectAll(d, paras[i]);
          try{
            if (seg.bold) d.execCommand('bold',false,null);
            if (seg.color) d.execCommand('foreColor',false,seg.color);
            if (seg.hilite) d.execCommand('hiliteColor',false,seg.hilite);
            count++;
          }catch(e){}
          break;
        }
      }
    }
    try{ win(d).getSelection().removeAllRanges(); }catch(e){}
    return count;
  }

  // 단어 인라인 강조: 본문 텍스트노드에서 단어 첫 등장 위치에 서식
  function findText(d, root, term){
    try{
      var walker = d.createTreeWalker(root, NodeFilter.SHOW_TEXT, null, false);
      var node;
      while(node = walker.nextNode()){
        var idx = (node.nodeValue||'').indexOf(term);
        if (idx>=0){
          var r = d.createRange();
          r.setStart(node, idx); r.setEnd(node, idx+term.length);
          return r;
        }
      }
    }catch(e){}
    return null;
  }
  function applyWords(d, words){
    if (!words || !words.length) return 0;
    var count = 0;
    var root = d.querySelector('.se-content') || d.body || d;
    for (var wi=0; wi<words.length; wi++){
      var term = words[wi].text; if(!term) continue;
      var hit = findText(d, root, term);
      if (hit){
        try{
          var sel = win(d).getSelection(); sel.removeAllRanges(); sel.addRange(hit);
          if (words[wi].bold) d.execCommand('bold',false,null);
          if (words[wi].hilite) d.execCommand('hiliteColor',false,words[wi].hilite);
          if (words[wi].color) d.execCommand('foreColor',false,words[wi].color);
          count++;
        }catch(e){}
      }
    }
    try{ win(d).getSelection().removeAllRanges(); }catch(e){}
    return count;
  }

  function clickImageButton(){
    var d = edoc(); closePopups(d);
    var sels = [
      'button.se-toolbar-item-image',
      '.se-toolbar [data-name="image"]',
      '.se-image-toolbar-button',
      'button.se-toolbar-button-image',
      '[data-log="ime.image"]',
      '.se-toolbar-item-image button',
      'button[data-name="image"]',
      'button[data-name="photo"]',
      'button[aria-label*="사진"]',
      'button[aria-label*="이미지"]',
      'button[title*="사진"]',
      'button[title*="이미지"]',
      '.se-toolbar-item-image'
    ];
    for (var i=0;i<sels.length;i++){
      var b = d.querySelector(sels[i]);
      if (b){ try{ b.click(); log('사진버튼 클릭 '+sels[i]); return true; }catch(e){} }
    }
    // 텍스트/라벨로 한 번 더 탐색
    var all = d.querySelectorAll('button, a, [role="button"]');
    for (var k=0;k<all.length;k++){
      var t = (all[k].textContent||'')+' '+(all[k].getAttribute('aria-label')||'')+' '+(all[k].getAttribute('title')||'');
      t = t.replace(/\s/g,'');
      if (t.indexOf('사진')>=0 || t.indexOf('이미지')>=0){
        try{ all[k].click(); log('사진버튼(텍스트) 클릭'); return true; }catch(e){}
      }
    }
    // 최후: 숨은 파일 input 을 직접 클릭(웹뷰 onShowFileChooser 가 가장 확실히 뜸)
    var inputs = d.querySelectorAll('input[type=file]');
    var img = null;
    for (var fi=0; fi<inputs.length; fi++){
      var ac = (inputs[fi].getAttribute('accept')||'');
      if (ac.indexOf('image')>=0){ img = inputs[fi]; break; }
      if (!img) img = inputs[fi];
    }
    if (img){ try{ img.click(); log('파일 input 직접 클릭'); return true; }catch(e){} }
    log('사진버튼 못 찾음');
    return false;
  }

  function doFill(d, tEl, bEl, payload){
    var report = {found:true, titleOk:false, bodyOk:false, bodyLen:0, paraCount:0, lineSegs:0, words:0};
    try{
      if (tEl) report.titleOk = typeTitle(d, tEl, payload.title||'');
      if (bEl) report.bodyOk = typeBody(d, bEl, payload.lines||[]);
      var paras = bodyParas(d);
      report.paraCount = paras.length;
      var total = 0;
      for (var i=0;i<paras.length;i++) total += (paras[i].textContent||'').length;
      report.bodyLen = total;
      if (payload.segs && payload.segs.length) report.lineSegs = applyLineSegs(d, payload.segs);
      if (payload.words && payload.words.length) report.words = applyWords(d, payload.words);
    }catch(e){ report.error=''+e; log('입력 오류 '+e); }
    window.__NB_DONE = true; window.__NB_RUNNING = false;
    log('입력완료 제목='+report.titleOk+' 본문='+report.bodyOk+' 글자수='+report.bodyLen
        +' 문단='+report.paraCount+' 줄서식='+report.lineSegs+' 단어='+report.words);
    try{ AndroidPoster.onFilled(JSON.stringify(report)); }catch(e){}
  }

  window.__NB_run = function(payload){
    if (window.__NB_DONE || window.__NB_RUNNING) return;
    window.__NB_RUNNING = true;
    if (typeof payload === 'string'){ try{ payload = JSON.parse(payload); }catch(e){ payload = {}; } }
    var tries = 0;
    function attempt(){
      if (window.__NB_DONE) return;
      tries++;
      try{
        var d = edoc();
        closePopups(d);
        var tEl = titleEl(d), bEl = firstBodyEl(d);
        if (tEl || bEl){
          log('에디터 발견(시도 '+tries+')');
          doFill(d, tEl, bEl, payload);
          return;
        }
        if (looksLikeLogin()){
          log('로그인 페이지 감지');
          try{ AndroidPoster.onNeedLogin(); }catch(e){}
          window.__NB_RUNNING = false;   // 로그인 후 코틀린이 재주입
          return;
        }
      }catch(e){ log('탐색 오류 '+e); }
      if (tries < 14){ setTimeout(attempt, 800); }
      else {
        window.__NB_RUNNING = false;
        log('에디터 못 찾음(시도 '+tries+')');
        try{ AndroidPoster.onFilled(JSON.stringify({found:false})); }catch(e){}
      }
    }
    attempt();
  };

  // 입력 결과를 코틀린으로 정직하게 보고(읽기 검증된 글자수)
  function reportManual(kind, ok, len, found){
    try{ AndroidPoster.onManualResult(kind, !!ok, len|0, found!==false); }catch(e){}
  }

  // 수동 제목 입력(상단 '제목 입력' 버튼) — 자동 실패 시 단독 호출. clear=true(덮어쓰기)
  window.__NB_fillTitle = function(payload){
    if (typeof payload === 'string'){ try{ payload = JSON.parse(payload); }catch(e){ payload = {}; } }
    var n=0;
    (function go(){
      var d = edoc(); closePopups(d);
      var tEl = titleEl(d);
      if (tEl){
        typeTitle(d, tEl, payload.title||'', true);
        var len = (tEl.textContent||'').length;       // 실제 들어간 글자수 재확인
        log('수동 제목입력 글자='+len);
        reportManual('title', len>0, len, true);
        return;
      }
      if (++n < 12){ setTimeout(go, 500); }
      else { log('수동 제목: 제목칸 못찾음'); reportManual('title', false, 0, false); }
    })();
  };

  // 수동 본문 입력(상단 '내용 입력' 버튼) — 서식까지 적용, clear=true(덮어쓰기)
  window.__NB_fillBody = function(payload){
    if (typeof payload === 'string'){ try{ payload = JSON.parse(payload); }catch(e){ payload = {}; } }
    var n=0;
    (function go(){
      var d = edoc(); closePopups(d);
      var bEl = firstBodyEl(d);
      if (bEl){
        typeBody(d, bEl, payload.lines||[], true);
        try{ if (payload.segs && payload.segs.length) applyLineSegs(d, payload.segs); }catch(e){}
        try{ if (payload.words && payload.words.length) applyWords(d, payload.words); }catch(e){}
        var paras = bodyParas(d), total=0;
        for (var i=0;i<paras.length;i++) total += (paras[i].textContent||'').length;
        log('수동 본문입력 글자='+total+' 문단='+paras.length);
        reportManual('body', total>0, total, true);
        return;
      }
      if (++n < 12){ setTimeout(go, 500); }
      else { log('수동 본문: 본문칸 못찾음'); reportManual('body', false, 0, false); }
    })();
  };

  // 제목 붙여넣기(append) — 기존 내용 지우지 않고 커서 끝에 추가
  window.__NB_pasteTitle = function(payload){
    if (typeof payload === 'string'){ try{ payload = JSON.parse(payload); }catch(e){ payload = {}; } }
    var n=0;
    (function go(){
      var d = edoc(); closePopups(d);
      var tEl = titleEl(d);
      if (tEl){
        typeTitle(d, tEl, payload.title||'', false);
        var len = (tEl.textContent||'').length;
        log('제목 붙여넣기 글자='+len);
        reportManual('titlePaste', len>0, len, true);
        return;
      }
      if (++n < 12){ setTimeout(go, 500); }
      else { log('제목 붙여넣기: 제목칸 못찾음'); reportManual('titlePaste', false, 0, false); }
    })();
  };

  // 본문 붙여넣기(append) — 기존 내용 지우지 않고 커서 끝에 추가
  window.__NB_pasteBody = function(payload){
    if (typeof payload === 'string'){ try{ payload = JSON.parse(payload); }catch(e){ payload = {}; } }
    var n=0;
    (function go(){
      var d = edoc(); closePopups(d);
      var bEl = firstBodyEl(d);
      if (bEl){
        typeBody(d, bEl, payload.lines||[], false);
        try{ if (payload.segs && payload.segs.length) applyLineSegs(d, payload.segs); }catch(e){}
        try{ if (payload.words && payload.words.length) applyWords(d, payload.words); }catch(e){}
        var paras = bodyParas(d), total=0;
        for (var i=0;i<paras.length;i++) total += (paras[i].textContent||'').length;
        log('본문 붙여넣기 글자='+total+' 문단='+paras.length);
        reportManual('bodyPaste', total>0, total, true);
        return;
      }
      if (++n < 12){ setTimeout(go, 500); }
      else { log('본문 붙여넣기: 본문칸 못찾음'); reportManual('bodyPaste', false, 0, false); }
    })();
  };

  window.__NB_imageAt = function(idx){
    try{
      var d = edoc(); closePopups(d);
      var paras = bodyParas(d);
      if (paras.length){
        var t = idx; if (t<0) t=0; if (t>paras.length-1) t=paras.length-1;
        caretEnd(d, paras[t]);
        log('사진 위치 문단 '+t+'/'+paras.length);
      }
    }catch(e){ log('imageAt 오류 '+e); }
    return clickImageButton();
  };

  window.__NB_images = function(){
    var ok = clickImageButton();
    try{ AndroidPoster.onImageButton(ok); }catch(e){}
    return ok;
  };

  window.__NB_publish = function(){
    function findFirst(doc, names){
      if(!doc) return null;
      var btns = doc.querySelectorAll('button, a');
      for (var i=0;i<btns.length;i++){
        var t = (btns[i].textContent||'').replace(/\s/g,'');
        for (var n=0;n<names.length;n++){ if (t===names[n]) return btns[i]; }
      }
      return null;
    }
    function findLast(doc, names){
      if(!doc) return null;
      var btns = doc.querySelectorAll('button, a'); var found=null;
      for (var i=0;i<btns.length;i++){
        var t = (btns[i].textContent||'').replace(/\s/g,'');
        for (var n=0;n<names.length;n++){ if (t===names[n]) found=btns[i]; }
      }
      return found;
    }
    var opened = false;
    var b = findFirst(document, ['발행']) || findFirst(edoc(), ['발행']);
    if (b){ try{ b.click(); opened=true; }catch(e){} }
    log('발행 1차 '+opened);
    setTimeout(function(){
      var c = findLast(document, ['발행','확인']) || findLast(edoc(), ['발행','확인']);
      var done=false;
      if (c){ try{ c.click(); done=true; }catch(e){} }
      log('발행 2차 '+done);
      try{ AndroidPoster.onPublishClicked(done); }catch(e){}
    }, 1500);
    return opened;
  };
})();
"""
}
