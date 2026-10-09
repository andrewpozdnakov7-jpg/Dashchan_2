// Native configuration is data; lifecycle and URL guards remain in this factory.
(function (nativeReaderConfig) {
  var previous = window.__slooopRedditBoardStyle;
  if (previous) {
    if (previous.observer) previous.observer.disconnect();
    if (previous.onPop) removeEventListener('popstate', previous.onPop);
    if (previous.onClick) document.removeEventListener('click', previous.onClick, true);
  }
  var i = 'slooop-reddit-board-style',
    s = document.getElementById(i);
  if (!s) {
    s = document.createElement('style');
    s.id = i;
    document.head.appendChild(s);
  }
  s.textContent = nativeReaderConfig.css;
  function boardPage() {
    var p = location.pathname;
    return p === '/' || /^\/(?:best|hot|new|top|rising)\/?$/.test(p) || /^\/r\/[^/]+(?:\/(?:hot|new|top|rising))?\/?$/.test(p);
  }
  function scoreSign(value) {
    if (!value) return 'neutral';
    var s = String(value).trim().toLowerCase(),
      m = s.replace(/,/g, '').match(/-?\d+(?:\.\d+)?/);
    if (!m) return 'neutral';
    var n = parseFloat(m[0]);
    if (/k$/.test(s)) n *= 1000;else if (/m$/.test(s)) n *= 1000000;
    return n > 0 ? 'positive' : n < 0 ? 'negative' : 'neutral';
  }
  function decorate() {
    var posts = document.querySelectorAll('shreddit-post');
    for (var i = 0; i < posts.length; i++) posts[i].setAttribute('data-slooop-score-sign', scoreSign(posts[i].getAttribute('score')));
  }
  function sync() {
    var enabled = boardPage();
    document.documentElement.classList.toggle('slooop-reddit-board', enabled);
    if (enabled) decorate();
  }
  var observer = new MutationObserver(sync),
    onPop = function () {
      setTimeout(sync, 0);
    },
    onClick = function () {
      setTimeout(sync, 0);
      setTimeout(sync, 350);
    };
  observer.observe(document.documentElement, {
    childList: true,
    subtree: true
  });
  addEventListener('popstate', onPop);
  document.addEventListener('click', onClick, true);
  window.__slooopRedditBoardStyle = {
    observer: observer,
    onPop: onPop,
    onClick: onClick
  };
  sync();
})
