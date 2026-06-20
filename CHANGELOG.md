# Changelog
## v4.2
### Features
- Add support for Tavily web search provider
- Allow configurable maximum search rounds

## v4.2.1
### Features
- fix some bugs

## Unreleased
### Features
- Enhance Tavily Extract (fetch_page): batch-extract up to 5 URLs in one call, upgrade extract_depth basic→advanced, raise fetched content limit 3000→6000 chars

### Bug Fixes
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
