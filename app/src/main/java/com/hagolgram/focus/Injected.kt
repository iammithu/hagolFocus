package com.hagolgram.focus

/**
 * Injected into every instagram.com page (Messages + Profile tabs). Instagram is a single-page app, so this:
 *  - sends every tap on a reel (in a chat, on a profile) to OUR native single-reel player (HF.reel)
 *  - blocks /explore and the /reels/ feed
 *  - hides Instagram's own bottom bar, Reels/Explore links, "Instagram Lite / open app" banners and nag dialogs
 * It is safe to inject many times.
 */
const val INJECT_JS = """
(function () {
  if (window.__hf) return;
  var root = document.head || document.documentElement;
  if (!root) return;
  window.__hf = 1;

  var st = document.createElement('style');
  st.textContent = 'html.hf-home article{display:none!important}' +
    'a[href="/reels/"],a[href="/reels"],a[href^="/explore"]{display:none!important}' +
    'a[href*="play.google.com"],a[href*="instagram.com/download"]{display:none!important}';
  root.appendChild(st);

  var rxBlock = /^\/(explore(\/|$)|reels\/?$)/;
  var rxCode = /^\/(?:[^\/]+\/)?reels?\/([A-Za-z0-9_-]{5,})\/?$/;
  function reelCode(p) { var m = rxCode.exec(p); return (m && m[1] !== 'audio') ? m[1] : null; }

  var guarding = false;
  function back() { if (history.length > 1) history.back(); else location.replace('/direct/inbox/'); }
  function guard() {
    var p = location.pathname;
    document.documentElement.classList.toggle('hf-home', p === '/');
    if (guarding) return;
    var c = reelCode(p);
    if (c) { guarding = true; if (window.HF) HF.reel(c); back(); }
    else if (rxBlock.test(p)) { guarding = true; back(); }
    if (guarding) setTimeout(function () { guarding = false; }, 1200);
  }
  ['pushState', 'replaceState'].forEach(function (k) {
    var o = history[k];
    history[k] = function () { var r = o.apply(this, arguments); setTimeout(guard, 0); return r; };
  });
  window.addEventListener('popstate', guard);
  setInterval(guard, 500);
  guard();

  document.addEventListener('click', function (e) {
    var a = e.target && e.target.closest ? e.target.closest('a') : null;
    if (!a) return;
    var h = a.getAttribute('href') || '';
    var path = h.replace(/^https?:\/\/[^\/]*instagram\.com/, '').split('?')[0];
    var c = reelCode(path);
    if (c) { e.preventDefault(); e.stopPropagation(); if (window.HF) HF.reel(c); return; }
    if (rxBlock.test(path)) { e.preventDefault(); e.stopPropagation(); }
  }, true);

  function hideFixed(el) {
    var n = el;
    while (n && n !== document.body && n !== document.documentElement) {
      var pos = getComputedStyle(n).position;
      if (pos === 'fixed' || pos === 'sticky') {
        if (n.getBoundingClientRect().height < 220) { n.style.setProperty('display', 'none', 'important'); return true; }
      }
      n = n.parentElement;
    }
    return false;
  }

  function dismiss(n) {
    var d = (n.matches && n.matches('[role="dialog"]')) ? n : (n.querySelector ? n.querySelector('[role="dialog"]') : null);
    if (!d) return;
    if (!/Turn on Notifications|Add Instagram to your Home screen|Use the App|Instagram Lite/i.test(d.textContent || '')) return;
    var bs = d.querySelectorAll('button');
    for (var i = 0; i < bs.length; i++) {
      if (/not now|cancel|close/i.test(bs[i].textContent || '')) { bs[i].click(); break; }
    }
  }

  function process(n) {
    if (!n || n.nodeType !== 1) return;
    var as = n.tagName === 'A' ? [n] : (n.querySelectorAll ? n.querySelectorAll('a[href]') : []);
    for (var i = 0; i < as.length; i++) {
      var a = as[i];
      if (a.__hf) continue;
      var h = a.getAttribute('href') || '';
      if (h === '/reels/' || h === '/reels' || /^\/explore/.test(h) || /play\.google\.com|instagram\.com\/download/.test(h)) {
        a.__hf = 1; hideFixed(a);
      }
    }
    var t = n.textContent || '';
    if (t.length < 160 && /instagram lite|use the app|get the app|download the app|open app|open instagram|get instagram|open in app/i.test(t)) hideFixed(n);
    dismiss(n);
  }

  var pending = [], timer = null;
  function flush() {
    timer = null;
    var list = pending; pending = [];
    if (list.length > 150) list = [document.documentElement];
    for (var i = 0; i < list.length; i++) process(list[i]);
  }
  new MutationObserver(function (ms) {
    for (var i = 0; i < ms.length; i++) {
      var add = ms[i].addedNodes;
      for (var j = 0; j < add.length; j++) if (add[j].nodeType === 1) pending.push(add[j]);
    }
    if (!timer) timer = setTimeout(flush, 250);
  }).observe(document.documentElement, { childList: true, subtree: true });
  process(document.documentElement);

  // path of YOUR profile (the avatar link in Instagram's own nav bar)
  window.__hfProfile = function () {
    var imgs = document.querySelectorAll('a[href^="/"] img[alt]');
    for (var i = 0; i < imgs.length; i++) {
      if (!/profile picture/i.test(imgs[i].alt || '')) continue;
      if (imgs[i].closest('article')) continue;
      var a = imgs[i].closest('a'), h = a && a.getAttribute('href');
      if (h && !/^\/(explore|reels|reel|direct|stories|p)\b/.test(h)) return h;
    }
    return '';
  };
})();
"""
