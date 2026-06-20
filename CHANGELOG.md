# Changelog
## v4.3.1
### Features
- Enhance Tavily Extract (fetch_page): batch-extract up to 5 URLs, advanced depth, 6000 char limit (thanks @Shixiaoshi0417)
- fetch_page tool only exposed when search_provider=tavily
- Add search_memory / search_public_memory tools: AI can search memories by content keyword, not just by tag (thanks @Shixiaoshi0417)
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

## v4.2.1
### Features
- fix some bugs

## v4.2
### Features
- Enhance Tavily Extract (fetch_page): batch-extract up to 5 URLs in one call, upgrade extract_depth basic→advanced, raise fetched content limit 3000→6000 chars
- Add search_memory / search_public_memory tools: AI can now search memories by content keyword, not just by tag
- Add set_reminder / cancel_reminder tools: AI can set timed reminders for users, with `/ai reminder` command to view/cancel

### Bug Fixes
- Fix duplicate fetch_page tool definition when Tavily is configured
- Fix callAI HTTP connection leak: add finally block to disconnect
- Fix ctx ordering: save user+R1 before tool processing to preserve correct history sequence
- Fix duplicate quote extraction: move quote parsing before dumpctx, remove old duplicate block
- Fix canUseAi double getRole call, also allow OWNER role explicitly
- Fix getMemberName duplicate `uin.equals("0")` condition → second check is now `uin.equals("null")`
- Fix overwrite_memory/overwrite_public_memory: use subquery instead of unsupported `UPDATE...ORDER BY...LIMIT`, use parameterized queries, preserve original subject_uin
- Fix /ai config: display missing keys (search_rounds, pat_wake, sewarden)
- Fix /help version string: v3.0 → v4.2.1
- Fix onDestroy: save all contexts to disk before clearing, remove invalid removeCallbacks
- Fix listen mode: add saveCtxToDisk call after recording messages to persist listen-mode context
- Remove unused realUin parameter from getMemoryCount
- Enhance Tavily Extract (fetch_page): batch-extract up to 5 URLs in one call, upgrade extract_depth basic→advanced, raise fetched content limit 3000→6000 chars (thanks @Shixiaoshi0417)
- Add support for Tavily web search provider
- Allow configurable maximum search rounds
