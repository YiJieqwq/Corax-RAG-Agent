# Corax-RAG

**English** | [中文](README.zh-CN.md)

> Corax "Strata" v5.1.0 (C-VFS) — a lightweight Agentic RAG that remembers you, the way a raven remembers.

---

## Introduction

Corax-RAG (Corax Strata) is a lightweight Agentic RAG framework running inside QQ. Built as a QFun Plugin (BeanShell) and powered by the DeepSeek v4 Flash model, it gives group chats and private chats long-term memory, identity isolation, and AI assistant capability.

**v5.1.0:** Corax-Shell virtual filesystem + snapshot approval + write protection + circuit breaker.

---

## Quick Start

1. Download the Releases bundle → extract it into the QFun plugin directory
2. `/ai set api_key sk-xxx` to configure your API key
3. `/ai on` to enable the AI
4. The default persona is "Xiaomao" (wake words: `小猫` / `猫猫` / `小喵`); switch with `/ai reboot Chen Qianyu`

---

## Corax-Shell

The AI has a virtual Linux filesystem, and every capability is exposed through `shell(cmd)`:

| Path | Purpose |
|------|---------|
| `/etc/` | Configuration files (safe file writes require approval) |
| `/proc/` | System status, persona management |
| `/dev/` | Message interfaces, background output |
| `/persist/` | Persistent storage |
| `/var/` | Database + logs |

The AI can also use pipes (`|`), redirection (`>`), background jobs (`&`), web search, and memory management.

### Write Approval

Writing to `/etc/admins.txt`, `blocked.txt`, `members.txt`, `enabled_conversations.txt`, `listen_sessions.txt`, or `default_account.txt` requires administrator approval via `/ai operation permit`.

### Snapshots

Every write automatically saves the previous version into `.snapshots/`, so the AI can roll back at any time with `corax-snapshot-list` / `corax-snapshot-restore` / `corax-snapshot-rm`.

---

## Core Technology

| Layer | Name | Function |
|----|------|------|
| Strata | Hot/cold tiered memory | High-frequency flotation + cold-label complement |
| DREX | Formatted executor | Routes `tool_calls` → archive |
| CAST | Three-tier credibility | First-hand > reported > unknown |
| WARDEN-I | Three-layer identity isolation | Protocol layer → system layer → user layer |
| STREAM | Stable context | Prefix cache + tail injection + disk persistence |

---

## Command Reference

```
/ai <content>                 # Chat with the AI
/ai on/off/status             # Enable / disable
/ai operation permit/reject   # Approve operations
/ai memory/search/public      # Memory management
/ai listen on/off/summary     # Listen mode
/ai set <k> <v> / config      # Configuration
/ai reboot [name]             # Switch persona
/admin /block /member         # Permissions
```

---

## Configuration

| Key | Default | Description |
|----|------|------|
| `model` | `deepseek-v4-flash` | Model |
| `context_ttl` | `0` | Expiry in minutes (0 = never expires) |
| `shell_rounds` | `8` | Maximum shell rounds |
| `temperature` | `0.7` | Temperature |

---

## License

**MIT** © 2026 YiJieqwq · Thanks to Shixiaoshi0417 for continued contributions
