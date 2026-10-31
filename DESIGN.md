# Design System — Uri
## Zoho Cliq Visual System & Floating Multi-Card Workspace

---

## 1. Visual Direction & Domain Exemplars

- **Product Name**: **Uri** — Real-time Workspace Messaging, Voice, & Network Telemetry Platform.
- **Primary Visual Exemplar**: **Zoho Cliq** (adapted from `FE-ZOHO` component system) — crisp **`Quicksand`** typography, high-contrast royal blue (`#2563EB`) interactive accents, clean slate surfaces (`#F8FAFC` / `#FFFFFF` in light mode, `#090D16` / `#0F172A` in dark mode), compact density, and grouped chat bubbles (`20px` outer radius with `5px` stacked corners).
- **Structural Layout**: **Floating Multi-Card Workspace** — preserving Uri's 4-zone floating workspace geometry (`60px` Navigation Dock, `330px`/`350px` Channel & DM Sidebar, flex-1 Main Chat Stage, and `280px` slide-over Member Drawer) beneath a floating `48px` global search pill.
- **Core Aesthetic Philosophy**: **"Crisp Enterprise Clarity Meets Tactile Floating Cards"** — replacing legacy emerald/sage and Discord clone tropes with Zoho Cliq's refined slate hierarchy, subtle hairline borders (`1px solid #E2E8F0` light / `#1E293B` dark), and friendly rounded geometry.

---

## 2. Color Palette & Atmospheric Polarity (Dual / Adaptive Palette)

The workspace supports seamless **Light Mode (Slate Daylight)** and **Dark Mode (Deep Slate Night)** via `[data-theme="light"]` and `[data-theme="dark"]` (bridged to Tailwind `dark:*` variants).

### Primary Brand & Interactive Tokens (Cliq Blue)
| Token Role | Light Mode | Dark Mode | Usage |
| :--- | :--- | :--- | :--- |
| **Primary Accent (`--accent-primary`)** | `#2563EB` | `#3B82F6` | Primary CTAs, active indicators, unread badges, links |
| **Primary Hover (`--accent-hover`)** | `#1D4ED8` | `#2563EB` | Button hover states, interactive highlights |
| **Primary Active (`--accent-active`)** | `#1E40AF` | `#1D4ED8` | Pressed states, outgoing dark mode message bubble |
| **Primary Soft (`--accent-soft`)** | `#EFF6FF` | `rgba(37, 99, 235, 0.16)` | Active sidebar item fill, pill background, subtle callouts |
| **Primary Tint / Border (`--accent-tint`)** | `#BFDBFE` | `rgba(59, 130, 246, 0.32)` | Outgoing bubble border, active card/pill outlines |

### Light Mode Surface & Text Hierarchy (`[data-theme="light"]`)
| Surface / Role | Hex / Value | Usage |
| :--- | :--- | :--- |
| **App Canvas (`--bg-0`)** | `#F8FAFC` | Outer viewport background behind floating cards |
| **Card Surface (`--bg-1`)** | `#FFFFFF` | Primary floating cards (Dock, Sidebar, Chat Stage, Modals) |
| **Subtle Surface (`--bg-2`)** | `#F8FAFC` | Secondary headers, composer container, code/card wells |
| **Muted Surface (`--bg-3`)** | `#F1F5F9` | Incoming message bubbles, pill buttons, subtle badges |
| **Elevated Surface (`--bg-elevated`)** | `#FFFFFF` | Popovers, floating action toolbars, dropdown menus |
| **Hover Surface (`--bg-hover`)** | `#F1F5F9` | Channel row hover, action button hover |
| **Primary Text (`--text-primary`)** | `#0F172A` | Headings, active channel names, message body text |
| **Secondary Text (`--text-secondary`)** | `#334155` | Inactive channels, sender names, secondary labels |
| **Muted Text (`--text-muted`)** | `#64748B` | Timestamps, section headers, metadata, placeholders |
| **Subtle Border (`--border-subtle`)** | `#E2E8F0` | Card borders, section dividers, attachment outlines |
| **Strong Border (`--border-strong`)** | `#CBD5E1` | Input hover borders, emphasized separators |

### Dark Mode Surface & Text Hierarchy (`[data-theme="dark"]`)
| Surface / Role | Hex / Value | Usage |
| :--- | :--- | :--- |
| **App Canvas (`--bg-0`)** | `#090D16` | Deep slate-navy viewport canvas |
| **Card Surface (`--bg-1`)** | `#0F172A` | Primary floating cards (`slate-900`) |
| **Subtle Surface (`--bg-2`)** | `#1E293B` | Secondary panels, composer well (`slate-800`) |
| **Muted Surface (`--bg-3`)** | `#1E293B` | Incoming message bubbles, badge wells |
| **Elevated Surface (`--bg-elevated`)** | `#1E293B` | Popovers, floating message action bars, modals |
| **Hover Surface (`--bg-hover`)** | `#334155` | Interactive hover states (`slate-700`) |
| **Primary Text (`--text-primary`)** | `#F8FAFC` | Primary headings and message content |
| **Secondary Text (`--text-secondary`)** | `#CBD5E1` | Secondary labels, channel list items |
| **Muted Text (`--text-muted`)** | `#94A3B8` | Timestamps, muted hints, section labels |
| **Subtle Border (`--border-subtle`)** | `#1E293B` | Card outlines and dividers |
| **Strong Border (`--border-strong`)** | `#334155` | Active/focused container borders |

### Semantic & Telemetry Tokens
- **Online / Connected (`--status-online`)**: `#22C55E` (Green 500)
- **Idle / Away (`--status-idle`)**: `#F59E0B` (Amber 500)
- **Do Not Disturb / Busy (`--status-dnd`)**: `#EF4444` (Red 500)
- **Offline (`--status-offline`)**: `#94A3B8` (Slate 400)
- **Transport Badges**:
  - **WebSocket (`--transport-ws`)**: `#2563EB`
  - **TCP Socket (`--transport-tcp`)**: `#0284C7`
  - **UDP / WebRTC (`--transport-udp`)**: `#0D9488`

---

## 3. Typography & Scannable Hierarchy

### Font Pairings
- **Primary UI & Chat (`--font-sans`, `--font-display`)**: `'Quicksand', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif` (weights `400`, `500`, `600`, `700`).
- **Monospace & Telemetry (`--font-mono`)**: `'JetBrains Mono', 'Cascadia Code', monospace` (weights `500`, `600`) with `font-variant-numeric: tabular-nums`.

### Typographic Scale & Cliq Proportions
- **Stage / Channel Title**: `15px–16px`, `font-weight: 700`, `letter-spacing: -0.01em`, color `--text-primary`.
- **Sidebar Section Header**: `11px` (`0.6875rem`), `font-weight: 700`, ` uppercase`, `letter-spacing: 0.08em`, color `--text-muted`.
- **Sidebar Channel / DM Item**: `13px–14px`, `font-weight: 500` (inactive) / `600–700` (active or unread).
- **Message Body**: `13.5px–14px` (`0.84375rem–0.875rem`), `font-weight: 500`, `line-height: 1.5`, comfortable reading width (`65ch–75ch`).
- **Message Sender Name**: `12px–13px`, `font-weight: 600`, color `--text-secondary`.
- **Timestamps & Badges**: `10px–11px`, `font-weight: 600`, `font-variant-numeric: tabular-nums`, color `--text-muted`.

---

## 4. Layout, Spatial Architecture & Functional Zones

Uri preserves its **Floating Multi-Card Workspace** geometry (`max-width: 1560px`, `gap: 16px`, `padding: 12px 20px 20px`), with `border-radius: 24px` (`--radius-card`) on all primary floating containers:

1. **Top Floating Search Pill (`height: 48px`)**:
   - Centered floating pill (`max-width: 540px`) triggering the `Ctrl+K` Command Palette, flanked by quick network telemetry and call triggers.
2. **Zone 1 — Left Navigation Dock (`width: 60px`)**:
   - Vertical pill dock containing the **Uri** brand emblem, Direct Messages (`DM`) switch, Server/Guild selector icons with Cliq blue active pills, Add/Join Server actions, Network Monitor toggle, Theme toggle, and Current User status avatar.
3. **Zone 2 — Channel & DM Navigation Card (`width: 330px`, `350px` on `xl`)**:
   - **Guild Mode**: Server title header, Invite badge, inline search filter, collapsible `TEXT CHANNELS` and `VOICE CHANNELS` sections, inline voice room rosters, and bottom quick status bar.
   - **DM Mode**: Quick filter tabs (`All`, `Online`, `Pending`, `Blocked`, `Add Friend`) + Direct Message conversation list with Cliq avatars, presence dots, and unread counters.
4. **Zone 3 — Main Stage Card (`flex: 1`, `min-width: 0`)**:
   - **Stage Header (`height: 60px`)**: Channel/DM identity, presence/topic subtitle, transport badge, Pinned Messages trigger, Member Drawer toggle, and Voice/Video call actions.
   - **Pinned Message Bar**: Cliq-styled collapsible banner right below the header when pinned messages exist.
   - **Message Stream**: Chronological feed with sticky `MessageDateDivider` pills, grouped `MessageItem` bubbles, `FileAttachmentCard` previews, `VoiceWaveformPlayer` audio cards, and floating hover action toolbars.
   - **Composer Area**: Cliq-styled rounded input container (`rounded-2xl`) with attachment dropzone/preview strip, reply/edit banner, `@mention` autocomplete popover, emoji picker, voice recorder, and primary blue send CTA.
5. **Zone 4 — Contextual Member Drawer (`width: 280px`)**:
   - Slide-over floating card displaying `ONLINE` and `OFFLINE` member groups with Cliq avatars, role badges, and moderation controls.

---

## 5. Core Cliq Component Specifications

### Message Bubbles (`MessageItem` & `message-primitives`)
- **Grouping & Corners**:
  - Default bubble radius: `20px` (`rounded-[20px]`).
  - Consecutive incoming messages from the same sender within 5 minutes tighten the bottom-left/top-left corner to `5px` (`rounded-bl-[5px]` / `rounded-tl-[5px]`) and collapse vertical spacing to `2px` (`mt-0.5` vs `mt-3`).
  - Consecutive outgoing messages tighten the right-side corners (`rounded-br-[5px]` / `rounded-tr-[5px]`).
- **Surface Colors**:
  - **Incoming (Other)**: Light `#F1F5F9` (`text-slate-900`) / Dark `#1E293B` (`text-slate-100`).
  - **Outgoing (Self)**: Light `#DBEAFE` (`text-slate-900`, border `#BFDBFE`) / Dark `#1D4ED8` (`text-white`).
- **Hover Actions**: Floating pill (`bg-white dark:bg-slate-800 border-slate-200 dark:border-slate-700 shadow-md rounded-xl`) offering Quick Reactions, Reply, Pin/Unpin, Edit (own), and Delete (own).

### Avatars & Presence (`Avatar`)
- Supports sizes `xs` (`24px`), `sm` (`32px`), `md` (`36px`), `lg` (`40px`), `xl` (`48px`), `2xl` (`64px`).
- Explicit status mapping for Uri's `PresenceStatus`:
  - `ONLINE` → `#22C55E` (`bg-green-500`)
  - `IDLE` → `#F59E0B` (`bg-amber-500`)
  - `DND` → `#EF4444` (`bg-red-500`)
  - `OFFLINE` → `#94A3B8` (`bg-slate-400`)

### File & Voice Cards (`FileAttachmentCard`, `VoiceWaveformPlayer`)
- **Images**: Rounded `16px` preview (`max-h-[240px]`) with subtle hover zoom and one-click lightbox/download overlay.
- **Documents / Archives / Code**: Compact horizontal card (`p-2.5 rounded-xl border`) with semantic `FileTypeIcon` (PDF, Word, Excel, Archive, Audio, Video, Code, Generic), filename truncation, formatted byte size, and direct download button.
- **Voice Messages**: Cliq-inspired pill player with circular primary blue Play/Pause button, interactive progress waveform bars, and `tabular-nums` duration badge.
