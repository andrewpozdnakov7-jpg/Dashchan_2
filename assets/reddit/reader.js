// Native configuration is data; lifecycle and URL guards remain in this factory.
(function (nativeReaderConfig) {
  var previous = window.__slooopRedditReaderStyle;
  if (previous) {
    if (previous.observer) previous.observer.disconnect();
    if (previous.onPop) removeEventListener('popstate', previous.onPop);
    if (previous.onClick) document.removeEventListener('click', previous.onClick, true);
  }
  var i = 'slooop-reddit-reader-style',
    s = document.getElementById(i);
  if (!s) {
    s = document.createElement('style');
    s.id = i;
    document.head.appendChild(s);
  }
  s.textContent = nativeReaderConfig.css;
  function sync() {
    document.documentElement.classList.toggle('slooop-reddit-reader', /\/comments\//.test(location.pathname));
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
  window.__slooopRedditReaderStyle = {
    observer: observer,
    onPop: onPop,
    onClick: onClick
  };
  sync();
})
