# Changelog
## v5.0.0 — Corvus
### Features
- **链式延时任务**：sleep N 拆成 Timer 分段，精准延时不再阻塞主线程
- **延时任务注册表**：/proc/ps 可见，/proc/<pid>/status 和 /proc/<pid>/cmd 可查
- **corax-sendfile**：从 shell 发送文件到聊天，主线程安全投递
- **新内置命令**：ps / stat / touch / rm / mkdir / chmod / find / sort / uniq / cut
- **记忆来源记录**：AI 创建记忆时同步记录触发的原消息（source_text）(thanks @Shixiaoshi0417)
- mkdir 在 /persist/ 下创建真实目录
- Shell 命令不存在时提示"查看可用命令: corax-help"

### Security (thanks @Shixiaoshi0417)
- 路径穿越 /../ 防护：vfsNorm 逐段解析
- SQL 白名单：vfsWriteVarDb 改为 SELECT-only
- Daemon 上限 10 检查
- 技能路径注入防护 + loadSkillContent 死代码移除
- Cursor close 移至 finally + 事务保护

### Bug Fixes
- **工具持久化修复**：tool 结果完整保留到 ctx，加载时兼容旧格式孤儿 tool
- **延时任务输出落 ctx**：AI 回头看对话可见延时任务结果
- **鉴权重补**：handleDebug / handleReboot 加上 requireAdminOrOwner
- **ls 安全修复**：只显示文件元数据，不读内容（防止 mp4 乱码污染 AI）
- **cat 二进制保护**：大文件/二进制拒绝读取
- **超长行拆分**：BeanShell 250 字符限制兼容
- **全量 if-return/break/continue 展开**：三行花括号，不再静默崩溃
- **一行花括号 if-return 拆行**
- reboot 时 ctx 落盘
- 消息队列：AI 处理中不丢消息

### Changed
- skill 文件迁移到 /persist/（corax-skill 命令移除）
- 系统提示词精简
- /ai off 下所有非 /ai on 指令完全静默
- OpenAI 标准消息格式：assistant + tool_calls → tool → 完整 ctx 持久化

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
