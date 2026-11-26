package com.nuvio.tv.core.server

/**
 * Static HTML, CSS, and Web App Manifest assets for the Zero-Install Nuvio+ Mobile Remote & Second Screen PWA.
 *
 * Served directly by [NuvioControlServer] with zero external CDN dependencies so it works completely offline on LAN.
 */
object NuvioWebRemoteHtml {

    const val ICON_SVG: String =
        "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 512 512'><rect width='512' height='512' rx='96' fill='#0B0D12'/><text x='50%' y='54%' font-family='Arial,sans-serif' font-size='220' font-weight='800' fill='#E50914' text-anchor='middle' dominant-baseline='middle'>N+</text></svg>"

    val MANIFEST_JSON: String = """
{
  "name": "Nuvio+ Remote",
  "short_name": "Nuvio+",
  "description": "Zero-install second screen remote, queue editor, phone stream picker, AI voice, and live AV telemetry.",
  "start_url": "/",
  "scope": "/",
  "display": "standalone",
  "orientation": "portrait",
  "background_color": "#0B0D12",
  "theme_color": "#E50914",
  "icons": [
    {
      "src": "/icon.svg",
      "sizes": "any",
      "type": "image/svg+xml",
      "purpose": "any maskable"
    }
  ]
}
""".trimIndent()

    val HTML: String = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover,maximum-scale=1,user-scalable=no">
<meta name="theme-color" content="#E50914">
<meta name="apple-mobile-web-app-capable" content="yes">
<meta name="apple-mobile-web-app-status-bar-style" content="black-translucent">
<meta name="apple-mobile-web-app-title" content="Nuvio+">
<link rel="manifest" href="/manifest.webmanifest">
<link rel="icon" type="image/svg+xml" href="/icon.svg">
<title>Nuvio+ Remote</title>
<style>
:root{--bg:#0B0D12;--panel:#12151D;--panel2:#191E2A;--line:#252C3B;--txt:#E8ECF4;--dim:#8A93A6;--accent:#E50914;--ok:#22C55E;--warn:#F59E0B;--cyan:#22D3EE;--violet:#A78BFA;--radius:14px}
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
html,body{margin:0;padding:0;height:100%}
body{background:var(--bg);color:var(--txt);font:14px/1.4 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;overscroll-behavior:none}
button,input,select{font:inherit;color:inherit}
button{background:var(--panel2);border:1px solid var(--line);border-radius:12px;padding:11px 12px;cursor:pointer;transition:transform .07s,background .12s}
button:active{transform:scale(.97);background:#232A3A}
button:disabled{opacity:.4;cursor:default}
button.primary{background:linear-gradient(135deg,var(--accent),#A8060E);border-color:transparent;color:#fff;font-weight:700}
button.cyan{background:rgba(34,211,238,.12);border-color:rgba(34,211,238,.35);color:var(--cyan);font-weight:600}
button.sm{padding:7px 10px;font-size:12px;border-radius:9px}
input,select{background:var(--panel2);border:1px solid var(--line);border-radius:12px;padding:11px 13px;width:100%;outline:none}
input:focus{border-color:var(--accent)}
.hidden{display:none!important}
#pairView{min-height:100dvh;display:flex;align-items:center;justify-content:center;padding:24px}
.pair-card{width:100%;max-width:360px;background:var(--panel);border:1px solid var(--line);border-radius:20px;padding:24px;text-align:center}
.pair-card h1{margin:0 0 6px;font-size:24px}
.pair-card h1 span{color:var(--accent)}
.pair-card p{margin:0 0 18px;color:var(--dim);font-size:13px}
#pin-input{text-align:center;font-size:26px;letter-spacing:10px;font-weight:700;margin-bottom:14px}
#pair-msg{min-height:18px;margin-top:10px;font-size:12px;color:var(--warn)}
#appView{display:flex;flex-direction:column;min-height:100dvh}
#topbar{display:flex;align-items:center;gap:8px;padding:calc(10px + env(safe-area-inset-top,0px)) 14px 10px;background:rgba(18,21,29,.94);backdrop-filter:blur(12px);border-bottom:1px solid var(--line);position:sticky;top:0;z-index:15}
#conn-dot{width:9px;height:9px;border-radius:50%;background:var(--dim);flex:0 0 auto}
#conn-dot.on{background:var(--ok);box-shadow:0 0 8px var(--ok)}
#conn-device{flex:1;font-size:13px;font-weight:600;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
#unpair-btn{padding:6px 10px;font-size:11px;color:var(--dim)}
main{flex:1;padding:12px 12px calc(86px + env(safe-area-inset-bottom,0px));max-width:600px;width:100%;margin:0 auto}
.panel{display:none;flex-direction:column;gap:11px}
.panel.active{display:flex}
.card{background:var(--panel);border:1px solid var(--line);border-radius:var(--radius);padding:13px}
.label{font-size:11px;text-transform:uppercase;letter-spacing:.9px;color:var(--dim);margin-bottom:8px;display:flex;justify-content:space-between;align-items:center}
.row{display:flex;gap:8px;align-items:center}
.row>*{flex:1;min-width:0}
.chips{display:flex;gap:7px;flex-wrap:wrap}
.chip{padding:7px 11px;font-size:12px;border-radius:999px;background:var(--panel2);border:1px solid var(--line)}
.chip.active{border-color:var(--accent);background:rgba(229,9,20,.16)}
.muted{color:var(--dim);font-size:12px}
#np-title{font-size:16px;font-weight:700;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
#np-sub{font-size:12px;color:var(--dim);margin-top:2px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
#np-state{font-size:11px;color:var(--cyan);margin-top:4px;font-weight:600}
#scrub{width:100%;margin:10px 0 4px;accent-color:var(--accent)}
.scrub-times{display:flex;justify-content:space-between;font-size:11px;color:var(--dim);font-variant-numeric:tabular-nums}
.track-row{display:grid;grid-template-columns:1fr 1fr;gap:8px;margin-top:8px}
.track-row select{padding:8px 10px;font-size:12px}
.seg{display:flex;background:var(--panel2);border-radius:10px;padding:3px;gap:3px;margin-bottom:10px}
.seg button{flex:1;border:none;background:none;padding:7px;font-size:12px;border-radius:8px;color:var(--dim)}
.seg button.on{background:#262E3F;color:#fff;font-weight:600}
#trackpad{height:155px;background:radial-gradient(circle at 50% 40%,#1F2636,#11151E);border:1px dashed var(--line);border-radius:var(--radius);display:flex;align-items:center;justify-content:center;color:var(--dim);font-size:12px;touch-action:none;user-select:none}
.dpad{display:grid;grid-template-columns:repeat(3,1fr);gap:8px;max-width:250px;margin:0 auto}
.dpad button{aspect-ratio:1;font-size:16px;font-weight:700}
.dpad .center{background:var(--accent);border-color:transparent;color:#fff;font-size:13px}
.list{display:flex;flex-direction:column;gap:8px}
.queue-row,.browse-item,.stream-card{background:var(--panel2);border:1px solid var(--line);border-radius:12px;padding:10px 11px}
.queue-row{display:flex;align-items:center;gap:8px;touch-action:none}
.queue-row.current{border-color:var(--accent);background:rgba(229,9,20,.1)}
.queue-row.dragging{opacity:.55;border-color:var(--cyan)}
.queue-row.drag-over{border-color:var(--ok);box-shadow:0 0 0 1px var(--ok)}
.queue-handle{padding:4px 6px;color:var(--dim);cursor:grab;font-size:16px;user-select:none}
.queue-num{width:20px;font-size:11px;color:var(--dim);text-align:center}
.queue-title{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-weight:600;font-size:13px}
.browse-item{text-align:left;width:100%;display:flex;flex-direction:column;gap:3px}
.browse-item .bt{font-weight:700;font-size:14px}
.browse-item .bm{font-size:11px;color:var(--dim)}
.stream-card{display:flex;flex-direction:column;gap:8px}
.stream-head{display:flex;justify-content:space-between;align-items:flex-start;gap:8px}
.stream-title{font-weight:600;font-size:13px;word-break:break-word}
.badge{font-size:10px;padding:3px 7px;border-radius:999px;background:rgba(167,139,250,.15);color:var(--violet);white-space:nowrap}
.stream-actions{display:grid;grid-template-columns:repeat(3,1fr);gap:6px}
#voice-btn{width:110px;height:110px;border-radius:50%;margin:4px auto 8px;display:flex;align-items:center;justify-content:center;font-weight:700;font-size:13px;background:linear-gradient(135deg,var(--accent),#9E050D);border:none;color:#fff}
#voice-btn.listening{animation:pulse 1.1s infinite}
@keyframes pulse{0%,100%{box-shadow:0 0 0 0 rgba(229,9,20,.65)}55%{box-shadow:0 0 0 16px rgba(229,9,20,0)}}
#voice-log{max-height:220px;overflow-y:auto;display:flex;flex-direction:column;gap:7px}
.voice-bubble{padding:9px 12px;border-radius:12px;font-size:13px;line-height:1.35}
.voice-user{background:#232B3D;align-self:flex-end;max-width:85%}
.voice-ai{background:var(--panel2);border:1px solid var(--line);align-self:flex-start;max-width:92%}
#nerd-cards{display:grid;grid-template-columns:repeat(2,1fr);gap:8px}
.kpi{background:var(--panel2);border:1px solid var(--line);border-radius:11px;padding:9px 10px}
.kpi-label{font-size:10px;text-transform:uppercase;letter-spacing:.7px;color:var(--dim)}
.kpi-value{font-size:13px;font-weight:700;margin-top:2px;font-variant-numeric:tabular-nums;word-break:break-word}
#nerd-spark{width:100%;height:96px;display:block;background:var(--panel2);border:1px solid var(--line);border-radius:12px}
nav#tabs{position:fixed;left:0;right:0;bottom:0;display:grid;grid-template-columns:repeat(5,1fr);background:rgba(18,21,29,.97);backdrop-filter:blur(12px);border-top:1px solid var(--line);padding:6px 4px calc(6px + env(safe-area-inset-bottom,0px));z-index:20}
nav#tabs button{background:none;border:none;padding:7px 2px;font-size:10px;color:var(--dim);display:flex;flex-direction:column;align-items:center;gap:2px}
nav#tabs button.active{color:var(--accent);font-weight:700}
nav#tabs button .ico{font-size:17px;line-height:1}
#toast{position:fixed;left:50%;bottom:calc(80px + env(safe-area-inset-bottom,0px));transform:translateX(-50%) translateY(16px);background:#1F2737;border:1px solid var(--line);padding:9px 15px;border-radius:999px;font-size:12px;opacity:0;pointer-events:none;transition:opacity .18s,transform .18s;z-index:30;max-width:90vw;text-align:center}
#toast.show{opacity:1;transform:translateX(-50%) translateY(0)}
#toast.error{border-color:var(--accent);color:#FCA5A5}
</style>
</head>
<body>

<section id="pairView">
  <div class="pair-card">
    <h1>Nuvio<span>+</span> Remote</h1>
    <p>Enter the 6-digit pairing PIN from Settings &rarr; Remote Control &amp; QR, or scan the QR code.</p>
    <input id="pin-input" type="tel" inputmode="numeric" maxlength="6" placeholder="000000" aria-label="Pairing PIN">
    <button id="pair-btn" class="primary" style="width:100%">Pair Device</button>
    <div id="pair-msg" role="status" aria-live="polite"></div>
  </div>
</section>

<section id="appView" class="hidden">
  <header id="topbar">
    <span id="conn-dot" aria-hidden="true"></span>
    <span id="conn-device">Connecting&hellip;</span>
    <button id="unpair-btn" type="button">Unpair</button>
  </header>

  <main>
    <!-- 1. REMOTE -->
    <div class="panel active" id="panel-remote" data-tab="remote">
      <div class="card">
        <div id="np-title">Nothing playing</div>
        <div id="np-sub">&mdash;</div>
        <div id="np-state">idle</div>
        <input id="scrub" type="range" min="0" max="1000" value="0" aria-label="Seek">
        <div class="scrub-times"><span id="scrub-pos">0:00</span><span id="scrub-dur">0:00</span></div>
        <div class="row" style="margin-top:8px">
          <button data-playback="rw" type="button" class="sm">-10s</button>
          <button data-playback="play_pause" type="button" class="primary sm">Play / Pause</button>
          <button data-playback="ff" type="button" class="sm">+30s</button>
          <button data-playback="skip_intro" type="button" class="cyan sm">Skip Intro</button>
        </div>
        <div class="row" style="margin-top:7px">
          <button data-playback="previous" type="button" class="sm">Prev</button>
          <button data-playback="stop" type="button" class="sm">Stop</button>
          <button data-playback="next" type="button" class="sm">Next</button>
          <button data-playback="speed" type="button" class="sm">Speed: <span id="speed-label">1x</span></button>
        </div>
        <div class="track-row">
          <select id="audio-select" aria-label="Audio track"><option value="-1">Audio: Default</option></select>
          <select id="subtitle-select" aria-label="Subtitle track"><option value="-1">Subtitles: Off</option></select>
        </div>
      </div>

      <div class="card">
        <div class="label">
          <span>Navigation</span>
          <div class="seg" style="margin:0;width:165px">
            <button id="mode-trackpad" class="on" type="button">Trackpad</button>
            <button id="mode-dpad" type="button">D-Pad</button>
          </div>
        </div>
        <div id="trackpad-wrap">
          <div id="trackpad">Swipe to move &middot; Tap to OK</div>
          <div class="row" style="margin-top:8px">
            <button data-dpad="BACK" type="button" class="sm">Back</button>
            <button data-dpad="SELECT" type="button" class="primary sm">OK</button>
            <button data-dpad="MENU" type="button" class="sm">Menu</button>
          </div>
        </div>
        <div id="dpad-wrap" class="hidden">
          <div class="dpad">
            <span></span><button data-dpad="UP" type="button">&#9650;</button><span></span>
            <button data-dpad="LEFT" type="button">&#9664;</button><button data-dpad="SELECT" type="button" class="center">OK</button><button data-dpad="RIGHT" type="button">&#9654;</button>
            <button data-dpad="BACK" type="button">Back</button><button data-dpad="DOWN" type="button">&#9660;</button><button data-dpad="MENU" type="button">Menu</button>
          </div>
        </div>
      </div>

      <div class="card">
        <div class="label">Phone Keyboard &rarr; TV</div>
        <div class="row">
          <input id="text-input" type="text" placeholder="Type on phone to search TV or command AI..." autocomplete="off">
        </div>
        <div class="row" style="margin-top:8px">
          <button id="text-search" type="button">Send to TV Search</button>
          <button id="text-ai" type="button" class="cyan">Send to AI Operator</button>
        </div>
      </div>

      <div class="card">
        <div class="label">Quick Screens</div>
        <div class="chips">
          <button class="chip" data-screen="home" type="button">Home</button>
          <button class="chip" data-screen="search" type="button">Search</button>
          <button class="chip" data-screen="library" type="button">Library</button>
          <button class="chip" data-screen="livetv" type="button">Live TV &amp; AI Guide</button>
          <button class="chip" data-screen="settings" type="button">Settings</button>
        </div>
      </div>

      <div class="card">
        <div class="label"><span>AI Broadcast &amp; Live Channels</span><button id="livetv-refresh" type="button" class="sm">Refresh</button></div>
        <div id="livetv-list" class="chips"></div>
      </div>
    </div>

    <!-- 2. QUEUE -->
    <div class="panel" id="panel-queue" data-tab="queue">
      <div class="card">
        <div class="label"><span>Playback Queue (Drag Handle to Reorder)</span><span id="queue-count" class="muted">0 items</span></div>
        <div id="queue-list" class="list"></div>
        <div class="row" style="margin-top:10px">
          <button id="queue-advance" type="button" class="primary">Play Next in Queue</button>
          <button id="queue-clear" type="button">Clear Queue</button>
        </div>
      </div>

      <div class="card">
        <div class="label">AI Thematic Channel Builder</div>
        <form id="thematic-form" class="row" autocomplete="off">
          <input id="thematic-prompt" type="text" placeholder="e.g. rainy cyberpunk neo-noir marathon">
          <button type="submit" class="primary" style="flex:0 0 86px">Launch</button>
        </form>
        <div id="curated-moods" class="chips" style="margin-top:10px">
          <button class="chip mood-chip" data-mood="cozy" type="button">Cozy</button>
          <button class="chip mood-chip" data-mood="binge" type="button">Thrilling Binge</button>
          <button class="chip mood-chip" data-mood="comedy" type="button">Comedy Relief</button>
          <button class="chip mood-chip" data-mood="mind_bender" type="button">Mind-Bender</button>
          <button class="chip mood-chip" data-mood="late_night" type="button">Late Night</button>
        </div>
      </div>
    </div>

    <!-- 3. SECOND-SCREEN BROWSE & PICK STREAMS -->
    <div class="panel" id="panel-browse" data-tab="browse">
      <form id="browse-form" class="card" autocomplete="off">
        <div class="label">Second-Screen Browse (TV Playback Uninterrupted)</div>
        <div class="row">
          <input id="browse-query" type="search" placeholder="Search shows & movies on your phone...">
          <button type="submit" class="primary" style="flex:0 0 86px">Search</button>
        </div>
      </form>
      <div class="card">
        <div class="label">Catalog Matches</div>
        <div id="browse-results" class="list"><div class="muted">Search above to browse catalogs without touching TV playback.</div></div>
      </div>
      <div class="card">
        <div class="label">Stream Picker</div>
        <div id="browse-streams" class="list"><div class="muted">Tap any title above to fetch streams quietly on your phone.</div></div>
      </div>
    </div>

    <!-- 4. AI VOICE -->
    <div class="panel" id="panel-voice" data-tab="voice">
      <div class="card" style="text-align:center">
        <div class="label" style="justify-content:center">Push-to-Talk into AiOperatorEngine</div>
        <button id="voice-btn" type="button">Hold / Tap<br>to Speak</button>
        <div class="row" style="margin-top:8px">
          <input id="voice-input" type="text" placeholder="Or type an AI Operator instruction...">
          <button id="voice-send" type="button" class="primary" style="flex:0 0 74px">Send</button>
        </div>
        <div class="chips" style="margin-top:10px;justify-content:center">
          <button class="chip voice-chip" data-prompt="Play the next episode in Continue Watching" type="button">Continue Watching</button>
          <button class="chip voice-chip" data-prompt="Queue a cozy British mystery night" type="button">British Mystery Queue</button>
          <button class="chip voice-chip" data-prompt="Tune AI channel 9001" type="button">Tune Ch 9001</button>
          <button class="chip voice-chip" data-prompt="Skip ahead 5 minutes" type="button">Skip +5m</button>
        </div>
      </div>
      <div class="card">
        <div class="label">Operator Transcript</div>
        <div id="voice-log"></div>
      </div>
    </div>

    <!-- 5. NERD STATS & HOT-SWAP RADAR -->
    <div class="panel" id="panel-nerd" data-tab="nerd">
      <div class="card">
        <div class="label"><span>Live AV Telemetry Cockpit (On-Demand)</span><span class="muted">1s pulse when open</span></div>
        <div id="nerd-cards"></div>
        <div class="label" style="margin-top:12px"><span>Throughput (Green) &amp; Buffer Ahead (Cyan)</span></div>
        <canvas id="nerd-spark" width="360" height="96"></canvas>
      </div>
      <div class="card">
        <div class="label"><span>Stream Hot-Swap Radar (Exact Timestamp)</span><button id="hotswap-refresh" type="button" class="sm">Scan Mirror</button></div>
        <div id="hotswap-list" class="list"><div class="muted">Open during playback or tap Scan Mirror to hot-swap streams without losing your place.</div></div>
      </div>
    </div>
  </main>

  <nav id="tabs">
    <button class="tab-btn active" data-tab="remote" type="button"><span class="ico">&#127918;</span>Remote</button>
    <button class="tab-btn" data-tab="queue" type="button"><span class="ico">&#128220;</span>Queue</button>
    <button class="tab-btn" data-tab="browse" type="button"><span class="ico">&#128269;</span>Browse</button>
    <button class="tab-btn" data-tab="voice" type="button"><span class="ico">&#127908;</span>AI Voice</button>
    <button class="tab-btn" data-tab="nerd" type="button"><span class="ico">&#128202;</span>Nerd</button>
  </nav>
</section>

<div id="toast"></div>
<script src="/app.js"></script>
</body>
</html>
""".trimIndent()
}
