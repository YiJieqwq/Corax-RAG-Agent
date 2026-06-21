# Changelog
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
- Fix /ai config: show missing keys (search_rounds, pat_wake, sewarden)
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
