# Corax-Shell 脚本指南

## 快速开始

所有操作通过 `shell(cmd)` 工具执行。支持管道、重定向、后台执行。

```bash
# 搜索（原始结果只返回给 AI，不会发给用户）
corax-search "天气"

# 创建记忆
corax-mem-create person,note 张三的生日是5月20日

# 查看配置
cat /proc/sys/temperature

# 检查快照
corax-snapshot-list /etc/admins.txt

# 命令列表示例
cat /persist/mytask &
```

## 可用命令

### 内置命令
`ls` `cat` `echo` `grep` `wc` `head` `tail` `date` `sleep` `touch` `rm` `mkdir` `chmod` `find` `sort` `uniq` `cut` `sed` `tr` `awk` `tee` `stat` `ps`

### Corax 命令
| 命令 | 说明 |
|------|------|
| `corax-search <query>` | 联网搜索 |
| `corax-fetch <url>` | 抓取网页全文 |
| `corax-mem-create [--public] [--about=<uin>] <tags> <content>` | 创建记忆 |
| `corax-mem-rm <id>` | 删除记忆 |
| `corax-mem-tag [--public] <tag>` | 按标签搜索记忆 |
| `corax-mem-search [--public] <keyword>` | 按内容搜索记忆 |
| `corax-listen <on|off|status>` | 监听模式 |
| `corax-sendfile <path>` | 发送文件到聊天 |
| `corax-snapshot-list <path>` | 查看快照列表 |
| `corax-snapshot-restore <path> <id>` | 回滚快照 |
| `corax-snapshot-rm <path> <id>` | 删除快照（需审批） |
| `corax-reboot <persona>` | 切换人设 |
| `corax-edit <file> <old> <new>` | 文件内容替换 |
| `corax-help` | 查看帮助 |

## 安全审批

以下操作需要管理员发送 `/ai operation permit` 批准（30s 超时自动拒绝）：

| 操作 | 触发条件 |
|------|----------|
| 写入安全文件 | `/etc/admins.txt` `blocked.txt` `members.txt` `enabled_conversations.txt` `listen_sessions.txt` `default_account.txt` |
| 删除快照 | `corax-snapshot-rm` |

审批期间 AI 工具调用会等待结果，批准后自动继续。

## 文件系统

| 路径 | 说明 | 权限 |
|------|------|:--:|
| `/proc/sys/` | 系统属性 | api_key ro, 其余 rw |
| `/proc/self/` | 当前会话状态 | ro |
| `/proc/prompt/active` | 当前人设 | ro, 写=切换 |
| `/etc/prompt/*.prompt.txt` | 人设文件 | 激活 ro, 其他 rw |
| `/etc/admins.txt` 等 | 安全名单 | rw（需审批） |
| `/dev/out` | 发送消息 | w |
| `/dev/msg-stream` | 消息总线 | ro (FIFO) |
| `/ctx/` | 上下文历史 | ro |
| `/var/data.db` | 记忆数据库 | RW |
| `/persist/` | 持久化存储 | RW |
| `/tmp/` | 临时工作区 | RW |

## 后台守护 (daemon)

```bash
# 写入持久化脚本
echo 'sleep 3600 && corax-search "热搜" > /persist/news.txt' > /persist/hourly
cat /persist/hourly &

# 查看进程
cat /proc/ps

# 终止（PID 从 ps 获取）
echo 1 > /proc/<pid>/kill
```

## 限制

- /tmp/ 最多 50 个文件，单文件 100KB
- daemon 上限 10 个
- 快照上限 10 个/文件
- api_key 只读不可改
- 当前激活的人设文件不可覆写
