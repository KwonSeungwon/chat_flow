(function () {
  'use strict';

  var script = document.currentScript;
  var serverUrl = (script && script.getAttribute('data-server')) || '';

  if (!serverUrl) {
    console.warn('[ChatFlow] data-server 속성이 필요합니다.');
    return;
  }

  // 현재 페이지에서 포스트 ID 추출 (Tistory URL 패턴: /숫자)
  var pathMatch = window.location.pathname.match(/\/(\d+)$/);
  var postId = pathMatch ? pathMatch[1] : null;

  if (!postId) {
    console.warn('[ChatFlow] 블로그 포스트 페이지가 아닙니다.');
    return;
  }

  var roomId = 'blog-' + postId;
  var postTitle = document.title || 'Blog Post ' + postId;
  var isOpen = false;
  var iframe = null;

  // 채팅방 자동 생성
  function ensureRoom() {
    return fetch(serverUrl + '/api/chat/rooms/get-or-create', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        externalId: roomId,
        name: postTitle.substring(0, 100),
        description: '블로그 포스트 토론: ' + window.location.href
      })
    }).catch(function (err) {
      console.warn('[ChatFlow] 채팅방 생성 실패:', err);
    });
  }

  // 플로팅 버튼 생성
  var btn = document.createElement('div');
  btn.id = 'chatflow-widget-btn';
  btn.innerHTML = '💬';
  btn.title = '실시간 채팅';
  Object.assign(btn.style, {
    position: 'fixed',
    bottom: '20px',
    right: '20px',
    width: '56px',
    height: '56px',
    borderRadius: '50%',
    background: '#6366f1',
    color: 'white',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    fontSize: '24px',
    cursor: 'pointer',
    boxShadow: '0 4px 12px rgba(99,102,241,0.4)',
    zIndex: '99999',
    transition: 'transform 0.2s',
    userSelect: 'none'
  });

  btn.onmouseenter = function () { btn.style.transform = 'scale(1.1)'; };
  btn.onmouseleave = function () { btn.style.transform = 'scale(1)'; };

  // 채팅 패널(iframe) 생성
  function createPanel() {
    var panel = document.createElement('div');
    panel.id = 'chatflow-widget-panel';
    Object.assign(panel.style, {
      position: 'fixed',
      bottom: '86px',
      right: '20px',
      width: '360px',
      height: '480px',
      borderRadius: '16px',
      overflow: 'hidden',
      boxShadow: '0 8px 30px rgba(0,0,0,0.15)',
      zIndex: '99998',
      display: 'none',
      border: '1px solid #e5e7eb'
    });

    iframe = document.createElement('iframe');
    var widgetUrl = serverUrl + '/widget?roomId=' + encodeURIComponent(roomId) +
      '&server=' + encodeURIComponent(serverUrl) +
      '&title=' + encodeURIComponent(postTitle.substring(0, 50));
    iframe.src = widgetUrl;
    iframe.style.width = '100%';
    iframe.style.height = '100%';
    iframe.style.border = 'none';
    iframe.setAttribute('allow', 'clipboard-read; clipboard-write');

    panel.appendChild(iframe);
    document.body.appendChild(panel);
    return panel;
  }

  var panel = null;

  btn.onclick = function () {
    if (!panel) {
      ensureRoom();
      panel = createPanel();
    }

    isOpen = !isOpen;
    panel.style.display = isOpen ? 'block' : 'none';
    btn.innerHTML = isOpen ? '✕' : '💬';
    btn.style.background = isOpen ? '#ef4444' : '#6366f1';
    btn.style.boxShadow = isOpen
      ? '0 4px 12px rgba(239,68,68,0.4)'
      : '0 4px 12px rgba(99,102,241,0.4)';
  };

  document.body.appendChild(btn);
})();
