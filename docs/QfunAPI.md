
# QFun Plugin API 参考

适用于 QFun 框架的 BeanShell 插件开发。所有 API 由 QFun 宿主提供，在脚本中可直接调用。

---

## 消息对象（`msg`）

`onMsg(Object msg)` 回调传入的消息对象，属性通过反射访问。

| 属性 | 类型 | 说明 |
|------|------|------|
| `msg.msg` | `String` | 消息内容 |
| `msg.userUin` | `long` | 发送者 UIN |
| `msg.peerUin` | `long` | 群号（群聊）或对方 UIN（私聊） |
| `msg.type` | `int` | 聊天类型：`1` 私聊，`2` 群聊 |
| `msg.msgId` | `long` | 消息 ID，用于回复 |
| `msg.atList` | `List<Long>` | @ 的用户 UIN 列表 |
| `msg.data` | `Object` | 消息数据包，内含引用消息信息 |

### 从 `msg.data` 获取引用消息

```java
Object data = msg.data;
java.util.List elements = (java.util.List) data.getClass()
    .getDeclaredField("elements").get(data);
for (Object el : elements) {
    java.lang.reflect.Field rf = el.getClass()
        .getDeclaredField("replyElement");
    rf.setAccessible(true);
    Object re = rf.get(el);
    if (re != null) {
        String senderUin = (String) re.getClass()
            .getDeclaredField("senderUin").get(re);
        String sourceText = (String) re.getClass()
            .getDeclaredField("sourceMsgText").get(re);
        String sourceMsgId = (String) re.getClass()
            .getDeclaredField("sourceMsgId").get(re);
        break;
    }
}
```

---

## 消息发送

| 方法 | 说明 |
|------|------|
| `sendMsg(String peerUin, String content, int chatType)` | 发送消息 |
| `sendReplyMsg(String peerUin, long msgId, String content, int chatType)` | 回复指定消息 |
| `sendFile(String peerUin, String filePath, int chatType)` | 发送文件 |

`peerUin` 为群号或对方 UIN，`chatType` 为 `1`（私聊）或 `2`（群聊）。

---

## 群组与好友

| 方法 | 返回 | 说明 |
|------|------|------|
| `getGroupList()` | `List` | 获取群列表 |
| `getMemberInfo(String groupUin, String userUin)` | `Object` | 获取群成员信息 |
| `getAllFriend()` | `List` | 获取好友列表 |

### 成员信息对象

```java
Object mem = getMemberInfo(groupUin, userUin);
String nickName = mem.uinName;   // 群昵称
```

### 群列表对象

```java
java.util.List groups = getGroupList();
for (Object g : groups) {
    String groupUin = String.valueOf(g.getClass().getField("group").get(g));
    String groupName = (String) g.getClass().getField("groupName").get(g);
}
```

### 好友列表对象

```java
java.util.List friends = getAllFriend();
for (Object f : friends) {
    String uin = String.valueOf(f.getClass().getField("uin").get(f));
    String remark = (String) f.getClass().getField("remark").get(f);
    String name = (String) f.getClass().getField("name").get(f);
}
```

---

## 文件与日志

| 方法 | 说明 |
|------|------|
| `log(String filename, String content)` | 写入日志文件（文件位于插件目录下） |

日志文件会自动创建在插件文件夹中，文件名建议加 `.txt` 后缀。

---

## 全局变量

QFun 自动注入以下变量：

| 变量 | 类型 | 说明 |
|------|------|------|
| `myUin` | `long` | 当前 QQ 号的 UIN |
| `pluginId` | `String` | 插件 ID |
| `classLoader` | `ClassLoader` | 类加载器 |
| `pluginPath` | `String` | 插件目录绝对路径（末尾不带 `/`） |

---

## 生命周期

| 方法 | 触发时机 |
|------|---------|
| `onMsg(Object msg)` | 收到消息时 |
| `onPaiYiPai(String peerUin, int chatType, String operatorUin)` | 被拍一拍时 |
| `onDestroy()` | 插件卸载时 |

---

## 部署说明

### 文件位置

```
.../QFun/{QQ号}/plugin/Corax-RAG/
└── main.java        # 主脚本文件
```

技能文件位于：

```
.../QFun/{QQ号}/plugin/Corax-RAG/config/skills/
├── 技能名.skill.txt
```

### 配置目录

`config/` 目录在脚本首次写入时自动创建，包含：

```
config/
├── data.db              # SQLite 数据库（记忆存储）
├── ai_config.txt        # AI 配置
├── admins.txt           # 管理员列表
├── members.txt          # 成员白名单
├── blocked.txt          # 黑名单
├── listen_sessions.txt  # 监听模式会话列表
├── prompt/              # 人设文件
├── ctx/                 # 上下文持久化
├── skills/              # 技能文件
├── log.txt              # 操作日志
└── error.txt            # 错误日志
```

### 负载限制

- 单文件脚本建议不超过 10000 行
- SQLite 数据库建议不超过 500MB
- 单个消息处理不建议超过 30 秒
- JSON 序列化输出时避免过大的 `toString(2)` indent（调试用 `dumpctx` 除外）
