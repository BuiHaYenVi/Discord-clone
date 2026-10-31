import type { NetworkFrameLog, TransportKind, WsEnvelope } from '../types/api';

const DEFAULT_WS_BASE =
  (import.meta as unknown as { env?: Record<string, string | undefined> }).env?.VITE_WS_URL ||
  'ws://localhost:8081';

type WsEventHandler = (envelope: WsEnvelope<Record<string, unknown>>) => void;
type ConnectionStateHandler = (connected: boolean) => void;
type FrameLogger = (entry: Omit<NetworkFrameLog, 'id' | 'timestampMs'>) => void;

export class WsClient {
  private wsUrl: string;
  private socket: WebSocket | null = null;
  private token: string | null = null;
  private handlers = new Set<WsEventHandler>();
  private stateHandlers = new Set<ConnectionStateHandler>();
  private frameLogger: FrameLogger | null = null;
  private reconnectTimer: number | null = null;
  private manualClose = false;
  private pendingRequests = new Map<string, {
    resolve: (payload: Record<string, unknown>) => void;
    reject: (error: Error) => void;
    timeoutId: number;
  }>();

  constructor(wsUrl = DEFAULT_WS_BASE) {
    this.wsUrl = wsUrl;
  }

  setFrameLogger(logger: FrameLogger | null): void {
    this.frameLogger = logger;
  }

  onEvent(handler: WsEventHandler): () => void {
    this.handlers.add(handler);
    return () => {
      this.handlers.delete(handler);
    };
  }

  onConnectionState(handler: ConnectionStateHandler): () => void {
    this.stateHandlers.add(handler);
    return () => {
      this.stateHandlers.delete(handler);
    };
  }

  connect(token: string): void {
    this.token = token;
    this.manualClose = false;
    this.clearReconnect();

    if (this.socket && (this.socket.readyState === WebSocket.OPEN || this.socket.readyState === WebSocket.CONNECTING)) {
      this.socket.close();
      this.rejectPendingRequests('WebSocket connection was replaced.');
    }

    try {
      const ws = new WebSocket(this.wsUrl);
      this.socket = ws;

      ws.onopen = () => {
        if (this.socket !== ws) return;
        this.emitFrame(
          'OUT',
          'WS',
          `WS_HANDSHAKE GET ${this.wsUrl} HTTP/1.1 101 Switching Protocols`,
          'WebSocket Connected (:8081)',
        );
        void this.request('AUTH_TOKEN', { token }, 10000)
          .then(() => {
            if (this.socket === ws) this.notifyState(true);
          })
          .catch((error: unknown) => {
            if (this.socket !== ws) return;
            const message = error instanceof Error ? error.message : String(error ?? '');
            if (/INVALID_TOKEN|UNAUTHORIZED|AUTH_REQUIRED/i.test(message)) {
              this.token = null;
              this.manualClose = true;
            }
            ws.close();
          });
      };

      ws.onmessage = (event: MessageEvent) => {
        if (this.socket !== ws) return;
        const raw = typeof event.data === 'string' ? event.data : JSON.stringify(event.data);
        try {
          const parsed = JSON.parse(raw) as WsEnvelope<Record<string, unknown>>;
          const eventType = parsed.type || 'UNKNOWN';
          const payloadTransport =
            (parsed.payload?.transport as TransportKind | undefined) ?? 'WS';

          if (eventType === 'PROTOCOL_FRAME_LOG') {
            const p = parsed.payload ?? {};
            this.emitFrame(
              (p.direction as 'IN' | 'OUT') ?? 'IN',
              (p.transport as TransportKind) ?? 'TCP',
              `Bridge ${String(p.transport ?? 'TCP')} event`,
              `Bridge ${String(p.transport ?? 'TCP')} event`,
            );
          } else {
            this.emitFrame(
              'IN',
              payloadTransport,
              `WS ${eventType} event`,
              `RX ${eventType}`,
            );
          }

          if (eventType === 'ACK' || eventType === 'ERROR') {
            const requestId = String(parsed.rid ?? parsed.payload?.rid ?? '');
            const pending = this.pendingRequests.get(requestId);
            if (pending) {
              window.clearTimeout(pending.timeoutId);
              this.pendingRequests.delete(requestId);
              if (eventType === 'ACK') {
                pending.resolve((parsed.payload?.data as Record<string, unknown>) ?? {});
              } else {
                const message = String(parsed.payload?.message ?? 'The request was rejected by the server.');
                const code = String(parsed.payload?.code ?? 'SERVER_ERROR');
                pending.reject(new Error(`${code}: ${message}`));
              }
            }
          }

          this.handlers.forEach((h) => h(parsed));
        } catch {
          this.emitFrame('IN', 'WS', 'WS malformed event', 'RX malformed event');
        }
      };

      ws.onerror = () => {
        if (this.socket === ws) this.notifyState(false);
      };

      ws.onclose = () => {
        if (this.socket !== ws) return;
        this.socket = null;
        this.notifyState(false);
        this.rejectPendingRequests('WebSocket disconnected before the server confirmed the request.');
        if (!this.manualClose && this.token) {
          this.reconnectTimer = window.setTimeout(() => {
            if (this.token && !this.manualClose) {
              this.connect(this.token);
            }
          }, 4000);
        }
      };
    } catch {
      this.notifyState(false);
    }
  }

  disconnect(): void {
    this.manualClose = true;
    this.token = null;
    this.clearReconnect();
    if (this.socket) {
      try {
        this.socket.close();
      } catch {
        // Ignore close errors
      }
      this.socket = null;
    }
    this.notifyState(false);
    this.rejectPendingRequests('WebSocket disconnected before the server confirmed the request.');
  }

  private rejectPendingRequests(message: string): void {
    for (const [rid, pending] of this.pendingRequests) {
      window.clearTimeout(pending.timeoutId);
      pending.reject(new Error(message));
      this.pendingRequests.delete(rid);
    }
  }

  isOpen(): boolean {
    return this.socket !== null && this.socket.readyState === WebSocket.OPEN;
  }

  send<T = Record<string, unknown>>(
    type: string,
    payload: T,
    transportHint: TransportKind = 'WS',
  ): boolean {
    const rid = `req-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 6)}`;
    return this.sendWithRid(type, payload, rid, transportHint);
  }

  request<T = Record<string, unknown>>(
    type: string,
    payload: T,
    timeoutMs = 10000,
  ): Promise<Record<string, unknown>> {
    const rid = `req-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 6)}`;
    return new Promise((resolve, reject) => {
      if (!this.isOpen() || !this.socket) {
        reject(new Error('WebSocket is not connected.'));
        return;
      }
      const timeoutId = window.setTimeout(() => {
        this.pendingRequests.delete(rid);
        reject(new Error(`The server did not confirm ${type} within ${timeoutMs} ms.`));
      }, timeoutMs);
      this.pendingRequests.set(rid, { resolve, reject, timeoutId });
      if (!this.sendWithRid(type, payload, rid)) {
        window.clearTimeout(timeoutId);
        this.pendingRequests.delete(rid);
        reject(new Error('WebSocket is not connected.'));
      }
    });
  }

  private sendWithRid<T>(
    type: string,
    payload: T,
    rid: string,
    transportHint: TransportKind = 'WS',
  ): boolean {
    const envelope: WsEnvelope<T> = { type, rid, payload };
    const serialized = JSON.stringify(envelope);

    if (this.isOpen() && this.socket) {
      this.socket.send(serialized);
      this.emitFrame('OUT', transportHint, `WS ${type} request`, `TX ${type}`);
      return true;
    }
    return false;
  }

  private emitFrame(
    direction: 'IN' | 'OUT',
    transport: TransportKind,
    rawFrame: string,
    summary: string,
  ): void {
    if (this.frameLogger) {
      this.frameLogger({ direction, transport, rawFrame, summary });
    }
  }

  private notifyState(connected: boolean): void {
    this.stateHandlers.forEach((fn) => fn(connected));
  }

  private clearReconnect(): void {
    if (this.reconnectTimer !== null) {
      window.clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
  }
}

export const wsClient = new WsClient();
