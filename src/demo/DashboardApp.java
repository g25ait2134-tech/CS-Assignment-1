package demo;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;

public final class DashboardApp {
    private final Path logFile;
    private final int maxInitialEvents;

    private DashboardApp(Path logFile, int maxInitialEvents) {
        this.logFile = logFile;
        this.maxInitialEvents = maxInitialEvents;
    }

    public static void main(String[] args) throws Exception {
        int port = intEnv("DASHBOARD_PORT", 8090);
        Path logFile = Path.of(env("LOG_FILE", "/logs/events.jsonl"));
        Path parent = logFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (!Files.exists(logFile)) {
            Files.createFile(logFile);
        }
        DashboardApp app = new DashboardApp(logFile, intEnv("DASHBOARD_INITIAL_EVENTS", 5000));
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/", app::index);
        server.createContext("/events", app::events);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        DemoLog.log("dashboard_started", "port", port, "logFile", logFile.toString(),
                "maxInitialEvents", app.maxInitialEvents);
    }

    private void index(HttpExchange exchange) throws IOException {
        byte[] bytes = HTML.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("content-type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private void events(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("content-type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("cache-control", "no-cache");
        exchange.sendResponseHeaders(200, 0);
        OutputStream out = exchange.getResponseBody();
        try {
            for (String line : tailLines(maxInitialEvents)) {
                send(out, line);
            }
            try (RandomAccessFile file = new RandomAccessFile(logFile.toFile(), "r")) {
                file.seek(file.length());
                while (true) {
                    String line = file.readLine();
                    if (line == null) {
                        sleep(350);
                        continue;
                    }
                    send(out, new String(line.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8));
                }
            }
        } catch (IOException ignored) {
            exchange.close();
        }
    }

    private List<String> tailLines(int maxLines) throws IOException {
        List<String> lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
        if (lines.size() <= maxLines) {
            return lines;
        }
        return lines.subList(lines.size() - maxLines, lines.size());
    }

    private void send(OutputStream out, String line) throws IOException {
        if (line == null || line.isBlank()) {
            return;
        }
        out.write(("data: " + line + "\n\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static int intEnv(String key, int defaultValue) {
        try {
            return Integer.parseInt(env(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static final String HTML = """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>RSA Demo Dashboard</title>
              <style>
                :root { --bg:#f6f7f9; --panel:#fff; --ink:#17202a; --muted:#667085; --line:#d9dee7; --green:#16845b; --red:#c23b3b; --blue:#2d6cdf; --amber:#aa6b00; }
                * { box-sizing: border-box; }
                body { margin:0; font-family:Inter,ui-sans-serif,system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif; background:var(--bg); color:var(--ink); }
                header { display:flex; align-items:center; justify-content:space-between; gap:12px; padding:16px 20px; border-bottom:1px solid var(--line); background:var(--panel); position:sticky; top:0; z-index:2; }
                h1 { margin:0; font-size:20px; font-weight:700; letter-spacing:0; }
                .status { display:flex; align-items:center; gap:8px; color:var(--muted); font-size:13px; }
                .dot { width:10px; height:10px; border-radius:50%; background:var(--amber); }
                .dot.live { background:var(--green); }
                main { padding:18px; display:grid; gap:16px; }
                .metrics { display:grid; grid-template-columns:repeat(6,minmax(120px,1fr)); gap:12px; }
                .metric,.panel { background:var(--panel); border:1px solid var(--line); border-radius:8px; }
                .metric { padding:12px; min-height:82px; }
                .metric span { display:block; color:var(--muted); font-size:12px; }
                .metric strong { display:block; margin-top:8px; font-size:24px; }
                .grid { display:grid; grid-template-columns:1.1fr .9fr; gap:16px; }
                .panel { padding:14px; min-width:0; }
                .panel-head { display:flex; align-items:center; justify-content:space-between; gap:10px; margin-bottom:10px; }
                .panel h2 { margin:0; font-size:14px; }
                button { border:1px solid var(--line); border-radius:6px; background:#fbfcfe; color:var(--ink); padding:6px 10px; cursor:pointer; }
                button.active { border-color:var(--blue); color:var(--blue); font-weight:700; }
                canvas { width:100%; height:230px; display:block; border:1px solid var(--line); border-radius:6px; background:#fbfcfe; }
                .log-wrap { max-height:440px; overflow:auto; border:1px solid var(--line); border-radius:6px; }
                table { width:100%; border-collapse:collapse; font-size:12px; }
                thead th { position:sticky; top:0; background:#f9fafc; z-index:1; }
                th,td { padding:8px; border-bottom:1px solid var(--line); text-align:left; vertical-align:top; }
                th { color:var(--muted); font-weight:600; }
                code { font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace; font-size:12px; word-break:break-word; }
                .hint { color:var(--muted); font-size:12px; }
                @media (max-width:950px) { .metrics,.grid { grid-template-columns:1fr; } header { align-items:flex-start; flex-direction:column; } }
              </style>
            </head>
            <body>
              <header>
                <h1>RSA Demo Dashboard</h1>
                <div class="status"><span id="dot" class="dot"></span><span id="conn">connecting</span></div>
              </header>
              <main>
                <section class="metrics">
                  <div class="metric"><span>Total Events</span><strong id="total">0</strong></div>
                  <div class="metric"><span>Stored In Browser</span><strong id="stored">0</strong></div>
                  <div class="metric"><span>Dropped Oldest</span><strong id="dropped">0</strong></div>
                  <div class="metric"><span>Avg Latency</span><strong id="avgLatency">0 ms</strong></div>
                  <div class="metric"><span>HTTP 2xx</span><strong id="okCount">0</strong></div>
                  <div class="metric"><span>HTTP 4xx/5xx</span><strong id="errCount">0</strong></div>
                </section>
                <section class="grid">
                  <div class="panel"><div class="panel-head"><h2>Latency By Event</h2></div><canvas id="latency" width="900" height="260"></canvas></div>
                  <div class="panel"><div class="panel-head"><h2>Status Codes</h2></div><canvas id="status" width="620" height="260"></canvas></div>
                </section>
                <section class="panel">
                  <div class="panel-head">
                    <div><h2>Scrollable JSON Log History</h2><div class="hint">Keeps up to 5,000 events. Scroll inside this pane to inspect earlier events after load bursts.</div></div>
                    <button id="follow" class="active" type="button">Follow Live</button>
                  </div>
                  <div id="logWrap" class="log-wrap">
                    <table>
                      <thead><tr><th>#</th><th>Time</th><th>Service</th><th>Event</th><th>Key Fields</th></tr></thead>
                      <tbody id="rows"></tbody>
                    </table>
                  </div>
                </section>
              </main>
              <script>
                const MAX_EVENTS = 5000;
                const MAX_LATENCY_POINTS = 500;
                let received = 0;
                let dropped = 0;
                let followLive = true;
                const events = [];
                const latency = [];
                const statuses = new Map();
                const rows = document.getElementById('rows');
                const logWrap = document.getElementById('logWrap');
                const follow = document.getElementById('follow');
                const dot = document.getElementById('dot');
                const conn = document.getElementById('conn');

                follow.onclick = () => {
                  followLive = !followLive;
                  follow.classList.toggle('active', followLive);
                  follow.textContent = followLive ? 'Follow Live' : 'Paused Scroll';
                };
                logWrap.addEventListener('scroll', () => {
                  const nearBottom = logWrap.scrollTop + logWrap.clientHeight >= logWrap.scrollHeight - 20;
                  if (!nearBottom && followLive) {
                    followLive = false;
                    follow.classList.remove('active');
                    follow.textContent = 'Paused Scroll';
                  }
                });

                const source = new EventSource('/events');
                source.onopen = () => { dot.classList.add('live'); conn.textContent = 'live'; };
                source.onerror = () => { dot.classList.remove('live'); conn.textContent = 'reconnecting'; };
                source.onmessage = (message) => {
                  try {
                    const event = JSON.parse(message.data);
                    received++;
                    events.push(event);
                    if (events.length > MAX_EVENTS) {
                      events.shift();
                      dropped++;
                    }
                    if (Number.isFinite(Number(event.latencyMs))) {
                      latency.push({ event: event.event || 'unknown', value: Number(event.latencyMs) });
                      if (latency.length > MAX_LATENCY_POINTS) latency.shift();
                    }
                    addStatus(event.status);
                    addStatus(event.status200, '200');
                    addStatus(event.status429, '429');
                    render();
                  } catch (e) {
                    console.warn('bad event', e, message.data);
                  }
                };

                function addStatus(value, forcedCode) {
                  if (value === undefined || value === null || value === '') return;
                  const code = forcedCode || String(value);
                  const increment = forcedCode ? Number(value) : 1;
                  if (Number.isFinite(increment)) statuses.set(code, (statuses.get(code) || 0) + increment);
                }

                function render() {
                  renderMetrics();
                  renderRows();
                  drawLatency();
                  drawStatus();
                  if (followLive) logWrap.scrollTop = logWrap.scrollHeight;
                }

                function renderMetrics() {
                  document.getElementById('total').textContent = String(received);
                  document.getElementById('stored').textContent = String(events.length);
                  document.getElementById('dropped').textContent = String(dropped);
                  const avg = latency.length ? latency.reduce((a, b) => a + b.value, 0) / latency.length : 0;
                  document.getElementById('avgLatency').textContent = `${avg.toFixed(1)} ms`;
                  let ok = 0, err = 0;
                  for (const [code, count] of statuses) {
                    const n = Number(code);
                    if (n >= 200 && n < 300) ok += count;
                    if (n >= 400) err += count;
                  }
                  document.getElementById('okCount').textContent = String(ok);
                  document.getElementById('errCount').textContent = String(err);
                }

                function renderRows() {
                  const fragment = document.createDocumentFragment();
                  events.forEach((event, index) => {
                    const tr = document.createElement('tr');
                    const fields = Object.entries(event)
                      .filter(([k]) => !['ts', 'service', 'event'].includes(k))
                      .map(([k, v]) => `${k}=${String(v).slice(0, 160)}`)
                      .join('  ');
                    tr.innerHTML = `<td>${dropped + index + 1}</td><td>${shortTime(event.ts)}</td><td>${event.service || event.mode || ''}</td><td>${event.event || ''}</td><td><code>${escapeHtml(fields)}</code></td>`;
                    fragment.appendChild(tr);
                  });
                  rows.replaceChildren(fragment);
                }

                function drawLatency() {
                  const canvas = document.getElementById('latency');
                  const ctx = canvas.getContext('2d');
                  clear(ctx, canvas);
                  const max = Math.max(5, ...latency.map((x) => x.value));
                  const w = canvas.width, h = canvas.height, pad = 28;
                  axis(ctx, w, h, pad);
                  if (!latency.length) return;
                  ctx.strokeStyle = '#2d6cdf';
                  ctx.lineWidth = 2;
                  ctx.beginPath();
                  latency.forEach((point, i) => {
                    const x = pad + (i / Math.max(1, latency.length - 1)) * (w - pad * 2);
                    const y = h - pad - (point.value / max) * (h - pad * 2);
                    if (i === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
                  });
                  ctx.stroke();
                  ctx.fillStyle = '#17202a';
                  ctx.font = '12px system-ui';
                  ctx.fillText(`${max.toFixed(0)} ms`, 8, 18);
                }

                function drawStatus() {
                  const canvas = document.getElementById('status');
                  const ctx = canvas.getContext('2d');
                  clear(ctx, canvas);
                  const items = Array.from(statuses.entries()).sort(([a], [b]) => Number(a) - Number(b));
                  const max = Math.max(1, ...items.map(([, count]) => count));
                  const w = canvas.width, h = canvas.height, pad = 28;
                  axis(ctx, w, h, pad);
                  const barW = Math.max(20, (w - pad * 2) / Math.max(1, items.length) - 14);
                  items.forEach(([code, count], i) => {
                    const x = pad + i * (barW + 14);
                    const barH = (count / max) * (h - pad * 2);
                    const n = Number(code);
                    ctx.fillStyle = n >= 400 ? '#c23b3b' : n >= 300 ? '#aa6b00' : '#16845b';
                    ctx.fillRect(x, h - pad - barH, barW, barH);
                    ctx.fillStyle = '#17202a';
                    ctx.font = '12px system-ui';
                    ctx.fillText(code, x, h - 8);
                    ctx.fillText(String(count), x, h - pad - barH - 6);
                  });
                }

                function clear(ctx, canvas) { ctx.clearRect(0, 0, canvas.width, canvas.height); }
                function axis(ctx, w, h, pad) {
                  ctx.strokeStyle = '#d9dee7';
                  ctx.beginPath();
                  ctx.moveTo(pad, pad);
                  ctx.lineTo(pad, h - pad);
                  ctx.lineTo(w - pad, h - pad);
                  ctx.stroke();
                }
                function shortTime(ts) {
                  if (!ts) return '';
                  const d = new Date(ts);
                  return Number.isNaN(d.getTime()) ? ts : d.toLocaleTimeString();
                }
                function escapeHtml(value) {
                  return value.replace(/[&<>"']/g, (c) => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
                }
              </script>
            </body>
            </html>
            """;
}
