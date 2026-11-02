(function () {
  'use strict';

  var SOCKET_PATH = '/tui/ws';
  var MIN_RETRY_MS = 500;
  var MAX_RETRY_MS = 8000;

  var statusElement = document.getElementById('status');
  var encoder = new TextEncoder();
  var retryMs = MIN_RETRY_MS;
  var socket = null;

  var term = new Terminal({
    cursorBlink: true,
    macOptionIsMeta: true,
    fontFamily: 'monospace',
    theme: { background: '#000000' }
  });
  var fit = new FitAddon.FitAddon();
  term.loadAddon(fit);
  term.open(document.getElementById('terminal'));

  function setStatus(text) {
    statusElement.textContent = text;
  }

  function isOpen() {
    return socket !== null && socket.readyState === WebSocket.OPEN;
  }

  function socketUrl() {
    var scheme = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    return scheme + '//' + window.location.host + SOCKET_PATH;
  }

  function sendResize() {
    if (isOpen()) {
      socket.send('resize ' + term.cols + 'x' + term.rows);
    }
  }

  function scheduleReconnect() {
    var delay = retryMs;
    retryMs = Math.min(retryMs * 2, MAX_RETRY_MS);
    setStatus('disconnected, retrying');
    window.setTimeout(connect, delay);
  }

  function connect() {
    setStatus('connecting');
    socket = new WebSocket(socketUrl());
    socket.binaryType = 'arraybuffer';
    socket.onopen = function () {
      retryMs = MIN_RETRY_MS;
      setStatus('');
      term.reset();
      fit.fit();
      sendResize();
    };
    socket.onmessage = function (event) {
      term.write(new Uint8Array(event.data));
    };
    socket.onclose = function () {
      socket = null;
      scheduleReconnect();
    };
  }

  term.onData(function (data) {
    if (isOpen()) {
      socket.send(encoder.encode(data));
    }
  });

  term.onResize(sendResize);

  window.addEventListener('resize', function () {
    fit.fit();
  });

  connect();
})();
