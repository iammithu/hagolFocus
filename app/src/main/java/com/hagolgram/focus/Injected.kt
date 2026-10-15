package com.hagolgram.focus

/**
 * Injected into every instagram.com page. Instagram is a single-page app, so this:
 *  - hides the feed posts on Home (story tray stays), the built-in bottom bar, Reels/Explore links and "open app" banners
 *  - blocks navigation to /explore and the /reels/ feed (single reels from DMs still work)
 *  - locks vertical swiping on a single-reel page so it can't turn into an endless reel feed
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
                   'a[href="/reels/"],a[href="/reels"],a[href^="/explore"]{display:none!important}';
  root.appendChild(st);

  var rxBlock = /^\/(explore(\/|$)|reels\/?$)/;
  var rxReel = /^\/(reels?\/[^\/]+\/?|[^\/]+\/reel\/[^\/]+\/?)$/;
  var guarding = false;

  function guard() {
    var p = location.pathname, cl = document.documentElement.classList;
    cl.toggle('hf-home', p === '/');
    cl.toggle('hf-reel', rxReel.test(p));
    if (rxBlock.test(p) && !guarding) {
      guarding = true;
      if (history.length > 1) history.back(); else location.replace('/');
      setTimeout(function () { guarding = false; }, 900);
    }
  }
  ['pushState', 'replaceState'].forEach(function (k) {
    var o = history[k];
    history[k] = function () { var r = o.apply(this, arguments); setTimeout(guard, 0); return r; };
  });
  window.addEventListener('popstate', guard);
  setInterval(guard, 500);
  guard();

  // never follow links to the Reels feed / Explore
  document.addEventListener('click', function (e) {
    var a = e.target && e.target.closest ? e.target.closest('a') : null;
    if (!a) return;
    var h = a.getAttribute('href') || '';
    if (/^(https?:\/\/[^\/]*instagram\.com)?\/(explore|reels\/?$)/.test(h)) { e.preventDefault(); e.stopPropagation(); }
  }, true);

  // single reel page: no vertical swiping to the next reel (comments / inputs still scroll)
  document.addEventListener('touchmove', function (e) {
    if (!document.documentElement.classList.contains('hf-reel')) return;
    var t = e.target;
    if (t && t.closest && t.closest('[role="dialog"],textarea,input,[contenteditable="true"]')) return;
    e.preventDefault();
  }, { passive: false, capture: true });

  function hideFixed(el) {
    var n = el;
    while (n && n !== document.body && n !== document.documentElement) {
      var pos = getComputedStyle(n).position;
      if (pos === 'fixed' || pos === 'sticky') { n.style.setProperty('display', 'none', 'important'); return true; }
      n = n.parentElement;
    }
    return false;
  }

  function dismiss(n) {
    var d = (n.matches && n.matches('[role="dialog"]')) ? n : (n.querySelector ? n.querySelector('[role="dialog"]') : null);
    if (!d) return;
    if (!/Turn on Notifications|Add Instagram to your Home screen|Use the App/i.test(d.textContent || '')) return;
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
      if (h === '/reels/' || h === '/reels' || /^\/explore/.test(h)) { a.__hf = 1; hideFixed(a); }
    }
    var t = n.textContent || '';
    if (t.length < 40 && /^\s*(open app|get app|use the app|open instagram|get instagram)\s*$/i.test(t)) hideFixed(n);
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
