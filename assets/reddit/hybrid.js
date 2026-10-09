// Native configuration is data; lifecycle and URL guards remain in this factory.
(function (nativeReaderConfig) {
  var VERSION = 11,
    existing = window.__slooopRedditHybridReader;
  if (existing && existing.version === VERSION) {
    existing.scan();
    return;
  }
  if (existing) {
    try {
      existing.observer.disconnect();
    } catch (e) {}
    try {
      if (existing.moreObserver) existing.moreObserver.disconnect();
    } catch (e) {}
    try {
      if (existing.cancelAutoLoad) existing.cancelAutoLoad();
    } catch (e) {}
    try {
      document.removeEventListener('click', existing.navigationIntent, true);
    } catch (e) {}
    try {
      window.removeEventListener('popstate', existing.historyNavigation);
    } catch (e) {}
    try {
      window.removeEventListener('scroll', existing.readerScroll);
    } catch (e) {}
    try {
      document.removeEventListener('scroll', existing.readerScroll, true);
    } catch (e) {}
    try {
      if (existing.originalPushState) history.pushState = existing.originalPushState;
      if (existing.originalReplaceState) history.replaceState = existing.originalReplaceState;
    } catch (e) {}
    document.documentElement.classList.remove('slooop-hybrid-reader-active');
    var staleHost = document.getElementById('slooop-hybrid-reader-host');
    if (staleHost) staleHost.remove();
    var staleStyle = document.getElementById('slooop-hybrid-reader-style');
    if (staleStyle) staleStyle.remove();
    delete window.__slooopRedditHybridReader;
  }
  var HOST = 'slooop-hybrid-reader-host',
    ACTIVE = 'slooop-hybrid-reader-active',
    RESUME = 'slooop-reddit-reader-resume';
  var resumeReader = false;
  try {
    var rawResume = sessionStorage.getItem(RESUME),
      savedResume = rawResume ? JSON.parse(rawResume) : null;
    if (savedResume && savedResume.until > Date.now() && savedResume.host === location.host) resumeReader = true;else if (rawResume) sessionStorage.removeItem(RESUME);
  } catch (e) {
    try {
      sessionStorage.removeItem(RESUME);
    } catch (ignored) {}
  }
  var state = {
    active: false,
    initialized: false,
    userOriginal: !resumeReader,
    timer: 0,
    key: '',
    path: '',
    readerY: 0,
    originalY: 0,
    action: null,
    pendingSince: 0,
    pendingReason: '',
    moreLoading: false,
    moreKey: '',
    moreTargets: [],
    collapsed: {},
    autoTimer: 0,
    resumeNavigation: false
  };
  function log(name, detail) {
    console.log(nativeReaderConfig.logPrefix + 'event=hybrid_' + name + (detail ? ' ' + detail : ''));
  }
  function text(value) {
    return (value || '').replace(/\s+/g, ' ').trim();
  }
  function scoreSign(value) {
    var n = parseFloat(text(value).replace(',', '.'));
    return n > 0 ? 'positive' : n < 0 ? 'negative' : 'neutral';
  }
  function scoreClass(value) {
    var sign = scoreSign(value);
    return sign === 'positive' ? 'score-positive' : sign === 'negative' ? 'score-negative' : '';
  }
  function safeUrl(value) {
    try {
      var u = new URL(value, location.href);
      return u.protocol === 'https:' ? u.href : null;
    } catch (e) {
      return null;
    }
  }
  function digest(value) {
    var hash = 2166136261;
    for (var i = 0; i < value.length; i++) {
      hash ^= value.charCodeAt(i);
      hash = Math.imul(hash, 16777619);
    }
    return (hash >>> 0).toString(36);
  }
  function element(name, className, value) {
    var e = document.createElement(name);
    if (className) e.className = className;
    if (value) e.textContent = value;
    return e;
  }
  var allowed = {
    P: 1,
    BR: 1,
    A: 1,
    STRONG: 1,
    B: 1,
    EM: 1,
    I: 1,
    U: 1,
    S: 1,
    CODE: 1,
    PRE: 1,
    BLOCKQUOTE: 1,
    UL: 1,
    OL: 1,
    LI: 1,
    H1: 1,
    H2: 1,
    H3: 1,
    H4: 1,
    H5: 1,
    H6: 1,
    SUP: 1,
    SUB: 1,
    SPAN: 1,
    IMG: 1
  };
  function copyClean(source, target) {
    for (var child = source.firstChild; child; child = child.nextSibling) {
      if (child.nodeType === 3) {
        target.appendChild(document.createTextNode(child.nodeValue));
        continue;
      }
      if (child.nodeType !== 1) continue;
      var tag = child.tagName;
      if (tag === 'SCRIPT' || tag === 'STYLE' || tag === 'IFRAME' || tag === 'FORM' || tag === 'INPUT' || tag === 'BUTTON' || tag === 'VIDEO' || tag === 'AUDIO') continue;
      var out = allowed[tag] ? document.createElement(tag.toLowerCase()) : document.createDocumentFragment();
      if (tag === 'A') {
        var href = safeUrl(child.getAttribute('href'));
        if (href) {
          out.setAttribute('href', href);
          out.setAttribute('rel', 'noopener noreferrer');
        }
      } else if (tag === 'IMG') {
        var src = safeUrl(child.getAttribute('src'));
        if (!src) continue;
        out.setAttribute('src', src);
        out.setAttribute('loading', 'lazy');
        out.setAttribute('alt', child.getAttribute('alt') || '');
      }
      copyClean(child, out);
      target.appendChild(out);
    }
  }
  function cleanContent(source) {
    var out = element('div', 'content');
    if (source) copyClean(source, out);
    return out;
  }
  function own(comment, selector) {
    var all = comment.querySelectorAll(selector);
    for (var i = 0; i < all.length; i++) if (all[i].closest('shreddit-comment') === comment) return all[i];
    return null;
  }
  var host = document.getElementById(HOST);
  if (!host) {
    host = document.createElement('div');
    host.id = HOST;
    host.hidden = true;
    (document.body || document.documentElement).appendChild(host);
  }
  var shadow = host.shadowRoot || host.attachShadow({
    mode: 'open'
  });
  shadow.innerHTML = '<style>' + nativeReaderConfig.css + '</style><button class="button reader-return" type="button"></button>' + '<main class="reader"><header class="toolbar"><div class="toolbar-title"></div>' + '<button class="button original" type="button"></button></header><div class="notice"></div>' + '<article class="post"></article><div class="comments-title"></div>' + '<section class="comments"></section></main>' + '<div class="action-overlay" hidden><section class="action-sheet"><div class="action-title"></div>' + '<button type="button" data-command="copy-text"></button><button type="button" data-command="copy-link"></button>' + '<button type="button" data-command="collapse"></button><button type="button" data-command="original-actions"></button>' + '<button type="button" data-command="cancel"></button></section></div>';
  var documentStyle = document.getElementById('slooop-hybrid-reader-style');
  if (!documentStyle) {
    documentStyle = document.createElement('style');
    documentStyle.id = 'slooop-hybrid-reader-style';
    documentStyle.textContent = 'html.' + ACTIVE + ' body>*:not(#' + HOST + '){display:none!important}';
    document.head.appendChild(documentStyle);
  }
  var ru = nativeReaderConfig.russian;
  var labels = ru ? {
    title: 'Режим чтения',
    original: 'Оригинал',
    reader: 'Режим чтения',
    notice: 'Стабильная разметка Slooop. Ответы, голосование и другие действия доступны на оригинальной странице Reddit.',
    comments: 'Комментарии',
    empty: 'Комментарии пока не загружены.',
    loading: 'Загрузка обсуждения…',
    actions: 'Действия',
    more: 'Ещё ответы',
    copyText: 'Копировать текст',
    copyLink: 'Копировать ссылку',
    copied: 'Скопировано',
    collapse: 'Свернуть ветку',
    expand: 'Развернуть ветку',
    originalActions: 'Действия на оригинальной странице',
    cancel: 'Отмена'
  } : {
    title: 'Reading mode',
    original: 'Original',
    reader: 'Reading mode',
    notice: 'Stable Slooop layout. Replies, voting, and other actions remain available on the original Reddit page.',
    comments: 'Comments',
    empty: 'Comments have not loaded yet.',
    loading: 'Loading discussion…',
    actions: 'Actions',
    more: 'More replies',
    copyText: 'Copy text',
    copyLink: 'Copy link',
    copied: 'Copied',
    collapse: 'Collapse thread',
    expand: 'Expand thread',
    originalActions: 'Actions on original page',
    cancel: 'Cancel'
  };
  shadow.querySelector('.toolbar-title').textContent = labels.title;
  shadow.querySelector('.original').textContent = labels.original;
  shadow.querySelector('.reader-return').textContent = labels.reader;
  shadow.querySelector('.notice').textContent = labels.notice;
  shadow.querySelector('.comments-title').textContent = labels.comments;
  shadow.querySelector('[data-command=copy-text]').textContent = labels.copyText;
  shadow.querySelector('[data-command=copy-link]').textContent = labels.copyLink;
  shadow.querySelector('[data-command=original-actions]').textContent = labels.originalActions;
  shadow.querySelector('[data-command=cancel]').textContent = labels.cancel;
  var actionOverlay = shadow.querySelector('.action-overlay');
  function closeActions() {
    actionOverlay.hidden = true;
    state.action = null;
  }
  function showReader() {
    state.originalY = scrollY;
    state.active = true;
    state.userOriginal = false;
    host.hidden = false;
    closeActions();
    host.removeAttribute('data-mode');
    document.documentElement.classList.add(ACTIVE);
    requestAnimationFrame(function () {
      scrollTo(0, state.readerY || 0);
    });
    log('mode', 'value=reader');
  }
  function showOriginal(target) {
    state.readerY = scrollY;
    state.active = false;
    state.userOriginal = true;
    closeActions();
    document.documentElement.classList.remove(ACTIVE);
    host.setAttribute('data-mode', 'original');
    requestAnimationFrame(function () {
      if (target && target.isConnected) target.scrollIntoView({
        block: 'center'
      });else scrollTo(0, state.originalY || 0);
    });
    log('mode', 'value=original');
  }
  function copyValue(value, button) {
    function done(ok) {
      if (ok) {
        var old = button.textContent;
        button.textContent = labels.copied;
        setTimeout(function () {
          button.textContent = old;
        }, 900);
      }
      log('copy', 'success=' + !!ok);
    }
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(value).then(function () {
        done(true);
      }, function () {
        fallback();
      });
    } else fallback();
    function fallback() {
      var area = document.createElement('textarea');
      area.value = value;
      area.setAttribute('readonly', '');
      area.style.position = 'fixed';
      area.style.opacity = '0';
      shadow.appendChild(area);
      area.select();
      var ok = false;
      try {
        ok = document.execCommand('copy');
      } catch (e) {}
      area.remove();
      done(ok);
    }
  }
  function refreshCollapsed(list) {
    var stack = [];
    for (var node = list.firstElementChild; node; node = node.nextElementSibling) {
      var depth = Math.max(0, parseInt(node.dataset.depth || '0', 10) || 0);
      stack.length = Math.min(stack.length, depth);
      node.hidden = stack.indexOf(true) >= 0;
      if (node.classList.contains('comment')) {
        var collapsed = !!state.collapsed[node.dataset.commentKey];
        node.dataset.collapsed = collapsed ? 'true' : 'false';
        stack[depth] = collapsed;
        stack.length = depth + 1;
      }
    }
  }
  function setCollapsed(card, collapsed) {
    var key = card.dataset.commentKey;
    if (key) state.collapsed[key] = collapsed;
    refreshCollapsed(card.parentElement);
  }
  function captureAnchor(list) {
    if (!state.active || state.userOriginal) return null;
    var toolbar = shadow.querySelector('.toolbar'),
      top = toolbar ? toolbar.getBoundingClientRect().bottom : 0;
    var cards = list.querySelectorAll('.comment[data-comment-key]');
    for (var i = 0; i < cards.length; i++) {
      var rect = cards[i].getBoundingClientRect();
      if (rect.bottom > top + 1 && rect.top < innerHeight) return {
        key: cards[i].dataset.commentKey,
        top: rect.top
      };
    }
    return null;
  }
  function restoreAnchor(anchor, list) {
    if (!anchor || !state.active || state.userOriginal) return;
    requestAnimationFrame(function () {
      var cards = list.querySelectorAll('.comment[data-comment-key]');
      for (var i = 0; i < cards.length; i++) if (cards[i].dataset.commentKey === anchor.key) {
        var delta = cards[i].getBoundingClientRect().top - anchor.top;
        if (Math.abs(delta) > .5) scrollBy(0, delta);
        log('anchor_restore', 'delta=' + Math.round(delta));
        break;
      }
    });
  }
  function openActions(comment, card, bodyText, permalink) {
    state.action = {
      comment: comment,
      card: card,
      body: bodyText,
      link: permalink
    };
    shadow.querySelector('.action-title').textContent = 'u/' + text(comment.getAttribute('author'));
    var collapse = shadow.querySelector('[data-command=collapse]'),
      next = card.nextElementSibling;
    collapse.hidden = !next || parseInt(next.dataset.depth || '0', 10) <= parseInt(card.dataset.depth || '0', 10);
    collapse.textContent = card.dataset.collapsed === 'true' ? labels.expand : labels.collapse;
    actionOverlay.hidden = false;
    log('actions_open', 'comment=' + text(comment.getAttribute('thingid')));
  }
  shadow.querySelector('.original').onclick = function () {
    showOriginal(null);
  };
  shadow.querySelector('.reader-return').onclick = function () {
    state.userOriginal = false;
    state.key = '';
    if (state.initialized) showReader();else showPending('user_request');
    scan(0);
  };
  actionOverlay.onclick = function (event) {
    if (event.target === actionOverlay) closeActions();
  };
  shadow.querySelector('[data-command=cancel]').onclick = closeActions;
  shadow.querySelector('[data-command=copy-text]').onclick = function () {
    if (state.action) copyValue(state.action.body, this);
  };
  shadow.querySelector('[data-command=copy-link]').onclick = function () {
    if (state.action) copyValue(state.action.link, this);
  };
  shadow.querySelector('[data-command=collapse]').onclick = function () {
    if (!state.action) return;
    var collapsed = state.action.card.dataset.collapsed !== 'true';
    setCollapsed(state.action.card, collapsed);
    closeActions();
    log('branch_toggle', 'collapsed=' + collapsed);
  };
  shadow.querySelector('[data-command=original-actions]').onclick = function () {
    if (state.action) showOriginal(state.action.comment);
  };
  function findMoreButton(target) {
    if (!target) return null;
    var candidates = [];
    if (target.matches && target.matches('button,[role=button]:not(a[href])')) candidates.push(target);
    var nested = target.querySelectorAll ? target.querySelectorAll('button,[role=button]:not(a[href])') : [];
    for (var i = 0; i < nested.length; i++) candidates.push(nested[i]);
    for (var i = 0; i < candidates.length; i++) {
      var candidate = candidates[i],
        label = (candidate.getAttribute('aria-label') || '').toLowerCase();
      if (candidate.getAttribute('aria-hidden') !== 'true' && label !== 'loading' && !candidate.disabled) return candidate;
    }
    return null;
  }
  function commentDepth(comment) {
    return Math.max(0, parseInt(comment.getAttribute('depth') || '0', 10) || 0);
  }
  function collectMoreTargets(tree, comments) {
    var nodes = Array.prototype.slice.call(tree.querySelectorAll('faceplate-partial[slot=children]'));
    var fullLinks = document.querySelectorAll('a[slot=more-comments-permalink][href]');
    for (var l = 0; l < fullLinks.length; l++) nodes.push(fullLinks[l]);
    var result = [];
    for (var i = 0; i < nodes.length; i++) {
      var target = nodes[i],
        navigation = target.matches && target.matches('a[href]');
      var clickable = navigation ? target : findMoreButton(target);
      if (!clickable) continue;
      var owner = target.closest ? target.closest('shreddit-comment') : null,
        ownerIndex = owner ? comments.indexOf(owner) : -1;
      var after = comments.length - 1,
        depth = 0,
        ownerKey = 'root';
      if (ownerIndex >= 0) {
        var ownerDepth = commentDepth(owner);
        after = ownerIndex;
        depth = Math.min(9, ownerDepth + 1);
        for (var j = ownerIndex + 1; j < comments.length; j++) {
          if (commentDepth(comments[j]) <= ownerDepth) break;
          after = j;
        }
        ownerKey = text(owner.getAttribute('thingid')) || safeUrl(owner.getAttribute('permalink')) || 'comment-' + ownerIndex;
      }
      result.push({
        target: target,
        after: after,
        depth: depth,
        key: ownerKey + '|' + i,
        navigation: navigation
      });
    }
    return result;
  }
  function loadMore(target, button) {
    if (!target || !target.isConnected || button.disabled || state.moreLoading) return;
    var navigation = target.matches && target.matches('a[href]'),
      clickable = navigation ? target : findMoreButton(target);
    if (!clickable || typeof clickable.click !== 'function') {
      log('more_failed', 'reason=no_action');
      return;
    }
    state.moreLoading = true;
    state.moreKey = button.dataset.moreKey || '';
    var all = shadow.querySelectorAll('.more');
    for (var i = 0; i < all.length; i++) all[i].disabled = true;
    button.textContent = labels.loading;
    state.active = true;
    state.userOriginal = false;
    host.hidden = false;
    host.removeAttribute('data-mode');
    if (navigation) {
      var href = safeUrl(clickable.href);
      if (!href) {
        state.moreLoading = false;
        button.disabled = false;
        button.textContent = labels.more;
        log('more_failed', 'reason=unsafe_navigation');
        return;
      }
      try {
        var nextUrl = new URL(href),
          currentUrl = new URL(location.href);
        nextUrl.hash = '';
        currentUrl.hash = '';
        if (nextUrl.href === currentUrl.href) {
          state.moreLoading = false;
          button.disabled = false;
          button.textContent = labels.more;
          log('more_failed', 'reason=same_navigation');
          return;
        }
      } catch (e) {}
      state.resumeNavigation = true;
      try {
        sessionStorage.setItem(RESUME, JSON.stringify({
          until: Date.now() + 45000,
          host: location.host
        }));
      } catch (e) {}
      log('more_navigation', 'host=' + location.host);
      location.assign(href);
      return;
    }
    document.documentElement.classList.add(ACTIVE);
    try {
      clickable.click();
    } catch (e) {
      state.moreLoading = false;
      state.moreKey = '';
      button.disabled = false;
      button.textContent = labels.more;
      log('more_failed', 'reason=click_exception');
      return;
    }
    log('more_requested', 'tag=' + target.tagName);
    setTimeout(function () {
      state.moreLoading = false;
      state.moreKey = '';
      state.key = '';
      scan(0);
      scheduleAutoLoad('settled');
    }, 3000);
  }
  var moreObserver = null;
  var autoFrame = 0;
  function cancelAutoLoad() {
    clearTimeout(state.autoTimer);
    state.autoTimer = 0;
    if (autoFrame) {
      cancelAnimationFrame(autoFrame);
      autoFrame = 0;
    }
  }
  function maybeAutoLoadMore(reason) {
    if (autoFrame) return;
    autoFrame = requestAnimationFrame(function () {
      autoFrame = 0;
      if (!state.active || state.userOriginal || state.moreLoading) return;
      var buttons = shadow.querySelectorAll('.more[data-more-key]');
      var threshold = Math.max(360, Math.min(900, innerHeight * .8));
      for (var i = 0; i < buttons.length; i++) {
        var button = buttons[i];
        if (button.dataset.navigation === 'true') continue;
        var rect = button.getBoundingClientRect();
        if (!button.hidden && rect.top <= innerHeight + threshold && rect.bottom >= -threshold && button.__slooopTarget) {
          log('more_auto', 'source=' + reason + ' distance=' + Math.round(rect.top - innerHeight));
          loadMore(button.__slooopTarget, button);
          break;
        }
      }
    });
  }
  function scheduleAutoLoad(reason) {
    clearTimeout(state.autoTimer);
    state.autoTimer = setTimeout(function () {
      state.autoTimer = 0;
      maybeAutoLoadMore(reason);
    }, 250);
  }
  function readerScroll() {
    scheduleAutoLoad('scroll_end');
  }
  if (typeof IntersectionObserver === 'function') {
    moreObserver = new IntersectionObserver(function (entries) {
      for (var i = 0; i < entries.length; i++) if (entries[i].isIntersecting && state.active && !state.userOriginal && !state.moreLoading) {
        scheduleAutoLoad('intersection');
        break;
      }
    }, {
      root: null,
      rootMargin: '900px 0px',
      threshold: 0
    });
  }
  function appendMoreMarkers(list, after) {
    for (var i = 0; i < state.moreTargets.length; i++) {
      var item = state.moreTargets[i];
      if (item.after !== after) continue;
      var button = element('button', 'button more', state.moreLoading && state.moreKey === item.key ? labels.loading : labels.more);
      button.type = 'button';
      button.dataset.moreKey = item.key;
      button.dataset.depth = item.depth;
      button.disabled = state.moreLoading;
      button.dataset.navigation = item.navigation ? 'true' : 'false';
      button.__slooopTarget = item.target;
      (function (target, current) {
        current.onclick = function () {
          loadMore(target, current);
        };
      })(item.target, button);
      list.appendChild(button);
      if (moreObserver && !item.navigation) moreObserver.observe(button);
    }
  }
  window.addEventListener('scroll', readerScroll, {
    passive: true
  });
  document.addEventListener('scroll', readerScroll, true);
  function showPending(reason) {
    if (state.userOriginal) return;
    var postBox = shadow.querySelector('.post'),
      titleBox = shadow.querySelector('.comments-title'),
      list = shadow.querySelector('.comments');
    postBox.hidden = true;
    titleBox.hidden = true;
    list.replaceChildren(element('div', 'empty', labels.loading));
    host.hidden = false;
    state.initialized = true;
    if (!state.active) showReader();
    if (state.pendingReason !== reason) {
      state.pendingReason = reason;
      log('pending', 'reason=' + reason);
    }
  }
  function render() {
    try {
      var path = location.pathname,
        isThread = /\/comments\//.test(path);
      if (state.path !== path) {
        var keepReader = state.resumeNavigation;
        state.resumeNavigation = false;
        state.path = path;
        state.key = '';
        state.pendingSince = Date.now();
        state.userOriginal = !keepReader;
        state.active = false;
        state.initialized = false;
        state.pendingReason = '';
        state.moreLoading = false;
        state.moreKey = '';
        state.moreTargets = [];
        state.collapsed = {};
        document.documentElement.classList.remove(ACTIVE);
        if (keepReader) {
          host.hidden = false;
          host.removeAttribute('data-mode');
        } else host.setAttribute('data-mode', 'original');
      }
      var post = isThread ? document.querySelector('shreddit-post') : null,
        tree = document.querySelector('#comment-tree');
      if (!tree && document.querySelector('shreddit-comment')) tree = document;
      if (!isThread) {
        if (state.initialized || state.active) {
          document.documentElement.classList.remove(ACTIVE);
          host.hidden = true;
          state.initialized = false;
          state.active = false;
          state.key = '';
          log('fallback', 'reason=not_thread');
        }
        return;
      }
      if (!post || !tree) {
        var missing = !post ? 'missing_post' : 'missing_tree';
        showPending(missing);
        if (document.readyState === 'complete' && Date.now() - state.pendingSince > 12000) log('waiting', 'reason=' + missing);
        scan(800);
        return;
      }
      var titleSource = post.querySelector('[slot=title]');
      var title = text(titleSource ? titleSource.textContent : post.getAttribute('post-title'));
      if (!title) {
        showPending('missing_title');
        scan(300);
        return;
      }
      var comments = Array.prototype.slice.call(tree.querySelectorAll('shreddit-comment'));
      var expected = parseInt(post.getAttribute('comment-count') || '0', 10) || 0;
      if (expected > 0 && comments.length === 0) {
        showPending('missing_comments');
        scan(300);
        return;
      }
      var contentKey = title;
      for (var n = 0; n < comments.length; n++) {
        var body = own(comments[n], '[slot=comment]');
        contentKey += '|' + comments[n].getAttribute('thingid') + '|' + comments[n].getAttribute('score') + '|' + (body ? text(body.textContent) : '');
      }
      var moreTargets = collectMoreTargets(tree, comments);
      state.moreTargets = moreTargets;
      var moreKey = '';
      for (var m = 0; m < moreTargets.length; m++) moreKey += '|' + moreTargets[m].key;
      var key = location.pathname + '|' + post.getAttribute('id') + '|' + comments.length + '|' + digest(contentKey) + '|' + digest(moreKey) + '|' + !!window.__slooopRedditTranslationEnabled;
      if (key === state.key) return;
      state.key = key;
      var postBox = shadow.querySelector('.post');
      postBox.hidden = false;
      postBox.replaceChildren();
      var commentsTitle = shadow.querySelector('.comments-title');
      commentsTitle.hidden = false;
      commentsTitle.textContent = labels.comments + ' · ' + comments.length + (expected ? ' / ' + expected : '');
      var postScore = text(post.getAttribute('score')),
        postSign = scoreSign(postScore);
      post.setAttribute('data-slooop-score-sign', postSign);
      var postMeta = element('div', 'post-meta');
      postMeta.appendChild(element('span', '', text(post.getAttribute('subreddit-prefixed-name'))));
      postMeta.appendChild(element('span', '', '· u/' + text(post.getAttribute('author'))));
      var postTime = text((post.querySelector('[slot=credit-bar] time') || {}).textContent);
      if (postTime) postMeta.appendChild(element('span', '', '· ' + postTime));
      if (postScore) postMeta.appendChild(element('span', scoreClass(postScore), '· ' + postScore));
      postBox.appendChild(postMeta);
      postBox.appendChild(element('h1', 'post-title', title));
      var bodySource = post.querySelector('[slot=text-body]');
      if (bodySource) postBox.appendChild(cleanContent(bodySource));
      var contentHref = safeUrl(post.getAttribute('content-href'));
      if (contentHref) {
        var link = element('a', 'post-link', contentHref);
        link.href = contentHref;
        link.rel = 'noopener noreferrer';
        postBox.appendChild(link);
      }
      var list = shadow.querySelector('.comments'),
        anchor = captureAnchor(list);
      if (state.action) closeActions();
      list.replaceChildren();
      appendMoreMarkers(list, -1);
      var rendered = 0;
      for (var i = 0; i < comments.length && i < 500; i++) {
        var comment = comments[i],
          source = own(comment, '[slot=comment]');
        var author = text(comment.getAttribute('author')),
          bodyText = source ? text(source.textContent) : '';
        if (!author && !bodyText) {
          appendMoreMarkers(list, i);
          continue;
        }
        var card = element('article', 'comment');
        var depth = Math.max(0, Math.min(8, parseInt(comment.getAttribute('depth') || '0', 10) || 0));
        var permalink = safeUrl(comment.getAttribute('permalink'));
        card.dataset.commentKey = text(comment.getAttribute('thingid')) || permalink || digest(author + '|' + bodyText);
        card.dataset.depth = depth;
        card.style.marginInlineStart = depth * 8 + 'px';
        var meta = element('div', 'comment-meta');
        meta.appendChild(element('span', 'author', author ? 'u/' + author : '[deleted]'));
        var metaSource = own(comment, '[slot=commentMeta]');
        var time = metaSource ? metaSource.querySelector('time') : null;
        if (time) meta.appendChild(element('span', 'time', '· ' + text(time.textContent)));
        var score = text(comment.getAttribute('score')),
          sign = scoreSign(score);
        comment.setAttribute('data-slooop-score-sign', sign);
        if (score) meta.appendChild(element('span', 'score ' + scoreClass(score), '· ' + score));
        card.appendChild(meta);
        if (source) {
          var clean = cleanContent(source);
          clean.className = 'content comment-body';
          card.appendChild(clean);
        }
        var actions = element('div', 'comment-actions'),
          button = element('button', '', labels.actions);
        button.type = 'button';
        (function (target, targetCard, targetBody, targetLink) {
          button.onclick = function () {
            openActions(target, targetCard, targetBody, targetLink);
          };
        })(comment, card, bodyText, permalink || location.href);
        actions.appendChild(button);
        card.appendChild(actions);
        list.appendChild(card);
        rendered++;
        appendMoreMarkers(list, i);
      }
      if (!rendered && !moreTargets.length) list.appendChild(element('div', 'empty', labels.empty));
      refreshCollapsed(list);
      restoreAnchor(anchor, list);
      host.hidden = false;
      state.pendingSince = 0;
      state.pendingReason = '';
      if (!state.userOriginal) {
        try {
          sessionStorage.removeItem(RESUME);
        } catch (e) {}
        resumeReader = false;
      }
      if (!state.initialized) {
        state.initialized = true;
        if (!state.userOriginal) showReader();else {
          document.documentElement.classList.remove(ACTIVE);
          host.setAttribute('data-mode', 'original');
        }
      } else if (state.active) {
        document.documentElement.classList.add(ACTIVE);
        host.removeAttribute('data-mode');
      }
      scheduleAutoLoad('render');
      log('render', 'comments=' + rendered + ' expected=' + expected + ' more=' + moreTargets.length);
    } catch (e) {
      showPending('render_exception');
      scan(1000);
      log('waiting', 'reason=exception name=' + (e && e.name ? e.name : 'unknown'));
    }
  }
  function scan(delay) {
    clearTimeout(state.timer);
    state.timer = setTimeout(render, delay == null ? 120 : delay);
  }
  var observer = new MutationObserver(function (changes) {
    for (var i = 0; i < changes.length; i++) {
      var target = changes[i].target;
      if (target !== host && !host.contains(target)) {
        scan();
        return;
      }
    }
  });
  observer.observe(document.documentElement, {
    childList: true,
    subtree: true,
    characterData: true,
    attributes: true,
    attributeFilter: ['score', 'comment-count', 'aria-hidden']
  });
  function navigationIntent(event) {
    var path = event.composedPath ? event.composedPath() : [],
      anchor = null;
    for (var i = 0; i < path.length; i++) if (path[i] && path[i].matches && path[i].matches('a[href]')) {
      anchor = path[i];
      break;
    }
    if (!anchor && event.target && event.target.closest) anchor = event.target.closest('a[href]');
    if (!anchor) return;
    try {
      var url = new URL(anchor.href, location.href),
        hostName = url.hostname.toLowerCase();
      if ((hostName === 'reddit.com' || hostName.endsWith('.reddit.com')) && /\/comments\//.test(url.pathname)) {
        state.key = '';
        state.pendingSince = Date.now();
        state.initialized = false;
        showOriginal(null);
      }
    } catch (e) {}
  }
  function historyNavigation() {
    if (location.pathname !== state.path) {
      state.path = '';
      state.pendingSince = Date.now();
    }
    state.key = '';
    setTimeout(render, 0);
  }
  var originalPushState = history.pushState,
    originalReplaceState = history.replaceState;
  history.pushState = function () {
    var result = originalPushState.apply(this, arguments);
    historyNavigation();
    return result;
  };
  history.replaceState = function () {
    var result = originalReplaceState.apply(this, arguments);
    historyNavigation();
    return result;
  };
  document.addEventListener('click', navigationIntent, true);
  window.addEventListener('popstate', historyNavigation);
  window.__slooopRedditHybridReader = {
    version: VERSION,
    scan: scan,
    showReader: showReader,
    showOriginal: showOriginal,
    observer: observer,
    moreObserver: moreObserver,
    navigationIntent: navigationIntent,
    historyNavigation: historyNavigation,
    readerScroll: readerScroll,
    cancelAutoLoad: cancelAutoLoad,
    originalPushState: originalPushState,
    originalReplaceState: originalReplaceState
  };
  log('installed', 'version=' + VERSION);
  if (/\/comments\//.test(location.pathname)) {
    state.path = location.pathname;
    state.pendingSince = Date.now();
    host.hidden = false;
    if (resumeReader) {
      state.active = false;
      state.userOriginal = false;
      showPending('resume_navigation');
    } else host.setAttribute('data-mode', 'original');
  }
  render();
})
