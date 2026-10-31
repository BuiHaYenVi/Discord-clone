import assert from 'node:assert/strict';
import { afterEach, test } from 'node:test';
import { ApiClient } from '../src/shared/api/apiClient.ts';
import { WsClient } from '../src/shared/ws/wsClient.ts';

const originalFetch = globalThis.fetch;
const originalWindow = globalThis.window;
const originalWebSocket = globalThis.WebSocket;

afterEach(() => {
  globalThis.fetch = originalFetch;
  if (originalWindow === undefined) delete globalThis.window;
  else globalThis.window = originalWindow;
  if (originalWebSocket === undefined) delete globalThis.WebSocket;
  else globalThis.WebSocket = originalWebSocket;
});

test('HTTP telemetry records routes and status without credentials or response tokens', async () => {
  const password = 'test-only-password-9341';
  const token = 'test-only-jwt-9341';
  const api = new ApiClient('http://uri.test');
  const frames = [];
  globalThis.window = { setTimeout, clearTimeout };
  globalThis.fetch = async (_url, init) => {
    assert.match(init.body, new RegExp(password));
    return new Response(JSON.stringify({
      token,
      user: { id: 1, username: 'tester', displayName: 'Tester', status: 'ONLINE' },
    }), { status: 200, headers: { 'Content-Type': 'application/json' } });
  };
  api.setFrameLogger((frame) => frames.push(frame));

  const result = await api.login('tester', password);

  assert.equal(result.token, token);
  assert.equal(frames.length, 2);
  assert.ok(frames.every((frame) => !frame.rawFrame.includes(password)));
  assert.ok(frames.every((frame) => !frame.rawFrame.includes(token)));
  assert.ok(frames.every((frame) => !frame.summary.includes(password)));
  assert.ok(frames.every((frame) => !frame.summary.includes(token)));
});

test('WebSocket sends authentication after a token-free handshake and reports ready after ACK', async () => {
  class FakeWebSocket {
    static CONNECTING = 0;
    static OPEN = 1;
    static CLOSED = 3;
    static latest;

    constructor(url) {
      this.url = url;
      this.readyState = FakeWebSocket.CONNECTING;
      this.sent = [];
      FakeWebSocket.latest = this;
    }

    send(frame) { this.sent.push(frame); }
    close() {
      this.readyState = FakeWebSocket.CLOSED;
      this.onclose?.();
    }
  }

  const token = 'test-only-jwt-4812';
  const frames = [];
  const client = new WsClient('ws://uri.test:8081');
  globalThis.window = { setTimeout, clearTimeout };
  globalThis.WebSocket = FakeWebSocket;
  client.setFrameLogger((frame) => frames.push(frame));
  let connected = false;
  const unsubscribe = client.onConnectionState((state) => { connected = state; });

  client.connect(token);
  const socket = FakeWebSocket.latest;
  assert.equal(socket.url, 'ws://uri.test:8081');

  socket.readyState = FakeWebSocket.OPEN;
  socket.onopen();
  assert.equal(connected, false);
  assert.equal(socket.sent.length, 1);
  const authRequest = JSON.parse(socket.sent[0]);
  assert.equal(authRequest.type, 'AUTH_TOKEN');
  assert.equal(authRequest.payload.token, token);

  socket.onmessage({ data: JSON.stringify({ type: 'ACK', rid: authRequest.rid, payload: { data: {} } }) });
  await new Promise((resolve) => setImmediate(resolve));

  assert.equal(connected, true);
  assert.ok(frames.every((frame) => !frame.rawFrame.includes(token)));
  assert.ok(frames.every((frame) => !frame.summary.includes(token)));
  unsubscribe();
  client.disconnect();
});
