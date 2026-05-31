package com.nclaude.app

/**
 * 네이버 스마트에디터 ONE 자동 입력 스크립트.
 *
 * WebView 에 주입된 뒤 두 진입점을 제공한다.
 *   - window.__NB_run(payload)  : 제목/본문 입력 + 가독성 서식 적용 → AndroidPoster.onFilled(report)
 *   - window.__NB_images()      : 에디터 '사진' 버튼 클릭(파일 선택창 유발) → AndroidPoster.onImageButton(ok)
 *
 * 에디터는 보통 iframe#mainFrame(같은 출처) 안에 있으므로 contentDocument 로 진입한다.
 * 셀렉터/타이밍은 네이버 업데이트 시 조정이 필요할 수 있다(미드저니 EXTRACT_JS 와 동일한 성격).
 *
 * 주의: Kotlin raw string 이므로 JS 안에서 '$' 문자는 쓰지 않는다(템플릿 충돌 방지).
 */
object EditorJs {
    const val SCRIPT = """
(function(){
  if (window.__NB_DEFINED) return;
  window.__NB_DEFINED = true;

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
  function firstBodyEl(d){
    var el = d.querySelector('.se-component.se-text .se-text-paragraph')
          || d.querySelector('.se-content .se-text-paragraph');
    if (el) return el;
    var t = titleEl(d);
    var ces = d.querySelectorAll('[contenteditable="true"]');
    for (var i=0;i<ces.length;i++){ if (ces[i]!==t) return ces[i]; }
    return null;
  }

  function focusEnd(d, el){
    var w = win(d); el.focus();
    var sel = w.getSelection(); var r = d.createRange();
    r.selectNodeContents(el); sel.removeAllRanges(); sel.addRange(r);
  }

  function typeTitle(d, el, text){
    if(!el) return false;
    focusEnd(d, el);
    try{ d.execCommand('delete',false,null); }catch(e){}
    try{ return d.execCommand('insertText',false,text); }catch(e){ return false; }
  }

  // 줄 단위로 입력해 실제 문단을 생성(서식/줄간격 보존)
  function typeBody(d, el, lines){
    if(!el) return false;
    focusEnd(d, el);
    try{ d.execCommand('delete',false,null); }catch(e){}
    var ok = true;
    for (var i=0;i<lines.length;i++){
      if (i>0){
        try{ if(!d.execCommand('insertParagraph',false,null)) d.execCommand('insertText',false,'\n'); }
        catch(e){ try{ d.execCommand('insertText',false,'\n'); }catch(e2){} }
      }
      var ln = lines[i] || '';
      if (ln.length){
        try{ ok = d.execCommand('insertText',false,ln) && ok; }catch(e){ ok=false; }
      }
    }
    return ok;
  }

  function selectPara(d, p){
    var w = win(d); var sel = w.getSelection(); var r = d.createRange();
    r.selectNodeContents(p); sel.removeAllRanges(); sel.addRange(r);
  }

  function applySegs(d, segs){
    var count = 0;
    var paras = d.querySelectorAll('.se-content .se-text-paragraph, .se-component.se-text .se-text-paragraph');
    for (var s=0;s<segs.length;s++){
      var seg = segs[s];
      var key = (seg.text||'').replace(/\s/g,'');
      if (!key) continue;
      for (var i=0;i<paras.length;i++){
        var pt = (paras[i].textContent||'').replace(/\s/g,'');
        if (pt && pt===key){
          selectPara(d, paras[i]);
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

  window.__NB_run = function(payload){
    var report = { titleOk:false, bodyOk:false, formatCount:0, found:false };
    try{
      var d = edoc();
      closePopups(d);
      var tEl = titleEl(d), bEl = firstBodyEl(d);
      report.found = !!(tEl || bEl);
      if (tEl) report.titleOk = typeTitle(d, tEl, payload.title || '');
      if (bEl) report.bodyOk = typeBody(d, bEl, payload.lines || []);
      if (payload.segs && payload.segs.length) report.formatCount = applySegs(d, payload.segs);
    }catch(e){ report.error = (''+e); }
    try{ AndroidPoster.onFilled(JSON.stringify(report)); }catch(e){}
  };

  window.__NB_images = function(){
    var ok = false, d = edoc();
    closePopups(d);
    var sels = [
      'button.se-toolbar-item-image',
      '.se-toolbar [data-name="image"]',
      '.se-image-toolbar-button',
      'button.se-toolbar-button-image',
      '[data-log="ime.image"]',
      '.se-toolbar-item-image button',
      'button[data-name="image"]'
    ];
    for (var i=0;i<sels.length && !ok;i++){
      var b = d.querySelector(sels[i]);
      if (b){ try{ b.click(); ok = true; }catch(e){} }
    }
    try{ AndroidPoster.onImageButton(ok); }catch(e){}
    return ok;
  };
})();
"""
}
