# Changelog
## v4.4.0 — Workspace
### Features
- **Corax-Shell**: 虚拟文件系统 + 单一 shell(cmd) 工具替代全部 FC 工具
- 支持管道(|)、重定向(>)、后台(&)，提供 ls/cat/echo/grep/wc/head/tail/date/sleep 等内置命令
- /proc/sys/ 系统属性读写，/etc/ 名单/配置管理，/dev/msg-stream 消息总线，/dev/out 消息输出
- /persist/ 持久化存储，/tmp/ 临时工作区，daemon 后台任务
- corax-mem-* 记忆管理，corax-search/corax-fetch 联网，corax-listen 监听控制
- /proc/prompt/ 人设槽位（A/B 分区，激活槽只读）

### Bug Fixes
- 修复 BeanShell 把 log() 误判为变量声明导致静默崩溃
- 消息队列：AI 处理中收到的消息不再丢弃，FIFO 缓存最多 20 条

### Removed
- 移除定时提醒系统（由 shell daemon 替代）

## v4.3.2
### Features
- Add set_reminder / cancel_reminder / list_reminders tools: AI can set timed reminders, cancel them, and query pending reminders with remaining time (thanks @Shixiaoshi0417)
- Handler.postDelayed precise scheduling for reminders, independent of message events
- Add `/ai reminder` command: view own pending reminders with remaining time
- Add `/ai reminder all` command: admins can view all users' pending reminders
- Add `/ai reminder rm <id>` command: cancel a specific reminder
- Add search_memory / search_public_memory tools: AI can search memories by content keyword, not just by tag (thanks @Shixiaoshi0417)

## v4.3.1
### Features
- Enhance Tavily Extract (fetch_page): batch-extract up to 5 URLs, advanced depth, 6000 char limit (thanks @Shixiaoshi0417)
- fetch_page tool only exposed when search_provider=tavily
- Search final round physically prevents tool calls, ensuring answer output
- Protocol: add <refmsgid> tag explanation, close </s>, SEWarden clean quoted text
- Tag rename: <qid>→<refmsgid>, <mop>→<memop>, <hit>→<tagresult>

### Bug Fixes
- Fix ctx ordering: user+R1 now saved before tool loops
- Fix callAI HTTP connection leak (missing finally disconnect)
- Fix dumpctx: quote extraction moved before export
- Fix overwrite_memory SQL: subquery replaces unsupported ORDER BY LIMIT
- Fix overwrite_memory: preserve original subject_uin on overwrite
- Fix /ai config: show missing keys (shell_rounds, pat_wake, sewarden)
- Fix /help version, getMemberName duplicate check, canUseAi double getRole
- Fix onDestroy: save all contexts before clearing
- Fix listen mode: saveCtxToDisk after recording
- Fix aiProcessing guard on /ai route

## v4.2.1
### Features
- fix some bugs

## v4.2
### Features
- Add support for Tavily web search provider
- Allow configurable maximum search rounds
