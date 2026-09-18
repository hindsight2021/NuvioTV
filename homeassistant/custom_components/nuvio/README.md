# Nuvio TV - Home Assistant Integration

A complete, 100% local-control integration and voice-assistant utility for **Nuvio TV**, designed for Home Assistant automations and voice control (**Assist**).

---

## 🔒 100% Local Network Architecture

> [!IMPORTANT]
> **No server runs on your PC!**
> The embedded control server (`NuvioControlServer` on port `8910`) is built directly into the **Nuvio TV app running on your TV / Nvidia Shield / Fire TV device**. Home Assistant talks directly to your TV over your local Wi-Fi / Ethernet LAN. No cloud services and no external PC processes are involved.

---

## 🎙️ Voice Control (Assist)

Nuvio TV exposes native intent handlers and custom sentence templates for Home Assistant Assist pipelines:

### 1. Standard Playback Sentences
- *"Pause Nuvio TV"* / *"Pause the TV"*
- *"Play Nuvio TV"* / *"Resume Nuvio TV"*
- *"Set volume to 40% on Nuvio TV"*
- *"Next track on Nuvio TV"*
- *"Stop Nuvio TV"*

### 2. Nuvio AI & Thematic Channels
- *"Play thematic channel Cyberpunk 80s on Nuvio TV"*
- *"Start channel Classic Sitcoms on Nuvio"*
- *"Put on Space Exploration channel on Nuvio"*
- *"Ask Nuvio to find movies directed by Christopher Nolan"*
- *"Tell Nuvio to search for Interstellar"*

### 3. Navigation
- *"Open search on Nuvio TV"*
- *"Go to live TV on Nuvio"*
- *"Open settings on Nuvio TV"*
- *"Press select on Nuvio"*
- *"Press back on Nuvio"*

To enable custom voice sentences, copy `custom_sentences/en/nuvio.yaml` to your Home Assistant `config/custom_sentences/en/nuvio.yaml`.

---

## ⚡ Automation Utility & Triggers

Nuvio TV provides first-class Home Assistant automation triggers and actions directly in the visual automation editor:

### Device Triggers
- **Playback Started**: Media begins playing (great for dimming lights).
- **Playback Paused**: Media paused (raise lights to warm ambient).
- **Playback Stopped**: Movie finished or returned to home screen.
- **Buffering**: Stream is buffering.

### Device Actions
- **Play / Pause / Stop**
- **Play Thematic Channel** (specify topic / mood)
- **Send AI Command** (send natural language prompt to TV)
- **Open Screen** (`home`, `search`, `live_tv`, `settings`, `library`)
- **Send Navigation Key** (`dpad_up`, `dpad_down`, `dpad_left`, `dpad_right`, `dpad_center`, `back`, `home`)

### Custom Services
- `nuvio.ai_command`: Send natural-language requests to Nuvio's AI.
- `nuvio.play_thematic_channel`: Spin up virtual channels on-demand.
- `nuvio.open_screen`: Jump directly to any screen.
- `nuvio.send_dpad`: Emulate remote control keys.

---

## 📦 Blueprints Included

1. **Cinematic Lighting Mode** (`blueprints/automation/nuvio/nuvio_cinema_mode.yaml`):
   - Automatically dims living room/theater lights when playback starts, and raises them when paused or stopped.
2. **Pause on Interruption** (`blueprints/automation/nuvio/nuvio_doorbell_pause.yaml`):
   - Automatically pauses playback when a doorbell or motion sensor triggers, and resumes after a configurable timeout.

---

## 🚀 Installation & Setup

1. Copy the `custom_components/nuvio` folder into your Home Assistant `<config_dir>/custom_components/` directory.
2. Restart Home Assistant.
3. In Home Assistant, navigate to **Settings -> Devices & Services -> Add Integration** and select **Nuvio TV**.
4. On your TV screen, open **Settings -> Integrations -> Remote Control & API**.
5. Enter your TV's local IP address and the 4-digit pairing PIN shown on your TV screen.
