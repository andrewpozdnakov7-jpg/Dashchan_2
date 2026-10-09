// Native configuration is data; lifecycle and URL guards remain in this factory.
(function (nativeReaderConfig) {
  if (window.__slooopRedditPromoGuard) return;
  var promoKnown = 'shreddit-app-selector,shreddit-app-selector-banner,shreddit-app-selector-modal,xpromo-app-selector,shreddit-async-loader[bundlename*="app-selector"],[data-testid*="app-selector"],[data-testid*="app-promo"],.XPromoPopupRpl.m-active,[paint-group="xpromo"],#xpromo-bottom-sheet';
  var headerKnown = 'reddit-header-large,reddit-header-small';
  var communityHeaderKnown = 'community-header,shreddit-subreddit-header,subreddit-header,[data-testid="subreddit-header"]';
  var communityBannerKnown = 'shreddit-community-banner,community-banner,[data-testid*="subreddit-banner" i],[data-testid*="community-banner" i],[class*="subreddit-banner" i],[class*="community-banner" i],[slot="banner"],[part*="banner" i]';
  var style = document.createElement('style');
  style.textContent = nativeReaderConfig.css;
  (document.head || document.documentElement).appendChild(style);
  function parent(n) {
    if (n.parentElement) return n.parentElement;
    var r = n.getRootNode && n.getRootNode();
    return r && r.host ? r.host : null;
  }
  function query(root, selector) {
    var out = [];
    if (root && root.nodeType === 1 && root.matches && root.matches(selector)) out.push(root);
    var found = root && root.querySelectorAll ? root.querySelectorAll(selector) : [];
    for (var i = 0; i < found.length; i++) out.push(found[i]);
    return out;
  }
  function compactEdited(root) {
    var spans = query(root, 'span');
    for (var i = 0; i < spans.length; i++) {
      var span = spans[i];
      if (span.hasAttribute('data-slooop-edited-compact') || !span.querySelector('faceplate-timeago')) continue;
      var text = span.textContent || '';
      if (text.indexOf('Отредакт') < 0) continue;
      for (var node = span.firstChild; node; node = node.nextSibling) if (node.nodeType === 3 && node.nodeValue.indexOf('Отредакт') >= 0) {
        node.nodeValue = ', ред. ';
        span.setAttribute('data-slooop-edited-compact', '');
        break;
      }
    }
  }
  function promoText(t) {
    t = (t || '').toLowerCase();
    return (t.indexOf('reddit') >= 0 || t.indexOf('прилож') >= 0) && /(open|download|get|install|откры|скач|загруз|установ)/.test(t);
  }
  function appAction(n) {
    var h = ((n.href || n.getAttribute && n.getAttribute('href') || '') + '').toLowerCase();
    if (h.indexOf('com.reddit.frontpage') >= 0 || h.indexOf('id1064216828') >= 0 || h.indexOf('reddit.app.link') >= 0 || h.indexOf('/mobile/download') >= 0) return true;
    var t = ((n.innerText || '') + ' ' + (n.getAttribute && n.getAttribute('aria-label') || '') + ' ' + (n.getAttribute && n.getAttribute('title') || '')).toLowerCase();
    return promoText(t);
  }
  function dialogFor(n) {
    var found = null;
    for (var i = 0; n && i < 24; i++, n = parent(n)) {
      var name = ((n.localName || '') + ' ' + (n.id || '') + ' ' + (typeof n.className === 'string' ? n.className : '') + ' ' + (n.getAttribute && n.getAttribute('data-testid') || '')).toLowerCase();
      var role = n.getAttribute && n.getAttribute('role'),
        s = getComputedStyle(n),
        r = n.getBoundingClientRect();
      if (role === 'dialog' || role === 'alertdialog' || n.localName === 'dialog') return n;
      if (!found && (/(app-selector|app-promo|xpromo)/.test(name) || /^(fixed|absolute|sticky)$/.test(s.position) && r.width >= innerWidth * .7 && r.height >= innerHeight * .18 && r.height <= innerHeight * 1.1 && r.bottom >= innerHeight * .8)) found = n;
    }
    return found;
  }
  function hide(n) {
    if (!n || n.classList && n.classList.contains('slooop-reddit-app-promo')) return false;
    if (n.classList) n.classList.add('slooop-reddit-app-promo');
    if (n.style) n.style.setProperty('display', 'none', 'important');
    return true;
  }
  function hideCommunityBanners(root) {
    var changed = false,
      headers = query(root, communityHeaderKnown);
    if (root && root.host && root.host.matches && root.host.matches(communityHeaderKnown)) headers.push(root.host);
    for (var h = 0; h < headers.length; h++) {
      var header = headers[h];
      header.style.setProperty('background-image', 'none', 'important');
      var scope = header.shadowRoot || header,
        banners = query(scope, communityBannerKnown);
      for (var b = 0; b < banners.length; b++) changed = hide(banners[b]) || changed;
      var media = query(scope, 'img,picture,faceplate-img');
      for (var m = 0; m < media.length; m++) {
        var rect = media[m].getBoundingClientRect();
        if (rect.width >= innerWidth * .7 && rect.height >= 32 && rect.height <= innerHeight * .35 && rect.width / Math.max(rect.height, 1) >= 2.2) changed = hide(media[m]) || changed;
      }
    }
    return changed;
  }
  function clearUnlock() {
    var b = document.body,
      e = document.documentElement;
    [e, b].forEach(function (n) {
      if (!n || !n.hasAttribute('data-slooop-promo-unlocked')) return;
      if (n.style.getPropertyValue('overflow-y') === 'auto') n.style.removeProperty('overflow-y');
      if (n.style.getPropertyValue('touch-action') === 'auto') n.style.removeProperty('touch-action');
      n.removeAttribute('data-slooop-promo-unlocked');
    });
  }
  function unlock() {
    var top = 0,
      b = document.body,
      e = document.documentElement;
    if (b) {
      top = parseFloat(b.style.top) || 0;
    }
    [e, b].forEach(function (n) {
      if (!n) return;
      var s = getComputedStyle(n);
      if (s.overflow === 'hidden' || s.overflow === 'clip' || s.overflowY === 'hidden' || s.overflowY === 'clip') {
        n.style.setProperty('overflow-y', 'auto', 'important');
        n.setAttribute('data-slooop-promo-unlocked', '');
      }
      n.style.setProperty('touch-action', 'auto', 'important');
      n.setAttribute('data-slooop-promo-unlocked', '');
      if (s.position === 'fixed') {
        n.style.removeProperty('position');
        n.style.removeProperty('top');
      }
    });
    if (top < 0) setTimeout(function () {
      scrollTo(0, -top);
    }, 0);
  }
  function roots(root, out) {
    out.push(root);
    if (root !== document) {
      try {
        observer.observe(root, options);
      } catch (ignored) {}
    }
    var all = root.querySelectorAll ? root.querySelectorAll('*') : [];
    for (var i = 0; i < all.length; i++) if (all[i].shadowRoot) roots(all[i].shadowRoot, out);
  }
  function scan(root) {
    var rs = [];
    roots(root || document, rs), hidden = false, legitimateDialog = false;
    for (var x = 0; x < rs.length; x++) {
      compactEdited(rs[x]);
      hidden = hideCommunityBanners(rs[x]) || hidden;
      var headers = query(rs[x], headerKnown);
      for (var h = 0; h < headers.length; h++) {
        headers[h].style.setProperty('display', 'none', 'important');
        headers[h].style.setProperty('visibility', 'hidden', 'important');
      }
      var direct = query(rs[x], promoKnown);
      for (var d = 0; d < direct.length; d++) hidden = hide(direct[d]) || hidden;
      var dialogs = query(rs[x], 'dialog,[role=dialog],[role=alertdialog],rpl-dialog,rpl-dialog-sheet');
      for (var j = 0; j < dialogs.length; j++) {
        var dialog = dialogs[j],
          text = dialog.innerText || dialog.textContent || '';
        if (text.length < 1600 && promoText(text)) hidden = hide(dialog) || hidden;else {
          var rect = dialog.getBoundingClientRect(),
            display = getComputedStyle(dialog).display;
          if (display !== 'none' && rect.width > 0 && rect.height > 0) legitimateDialog = true;
        }
      }
      var actions = query(rs[x], 'a,button,[role=button],faceplate-tracker,[tabindex]');
      for (var a = 0; a < actions.length; a++) if (appAction(actions[a])) hidden = hide(dialogFor(actions[a])) || hidden;
    }
    if (legitimateDialog) clearUnlock();else if (hidden) {
      unlock();
      setTimeout(unlock, 100);
      setTimeout(unlock, 400);
    }
    return hidden;
  }
  var options = {
      childList: true,
      subtree: true
    },
    observer = new MutationObserver(function (changes) {
      var targets = [];
      for (var i = 0; i < changes.length; i++) if (targets.indexOf(changes[i].target) < 0) targets.push(changes[i].target);
      for (var i = 0; i < targets.length; i++) scan(targets[i]);
    });
  observer.observe(document.documentElement, options);
  window.__slooopRedditPromoGuard = observer;
  scan(document);
})
