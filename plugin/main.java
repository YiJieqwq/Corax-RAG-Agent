import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.*;
import java.util.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import android.os.Handler;
import android.os.Looper;
import java.net.*;
import java.util.Timer;
import java.util.TimerTask;
import org.json.*;

// ==================== 全局变量 ====================
static SQLiteDatabase sharedDb = null;

static Map aiContexts = new HashMap();
static Map aiConfigCache = null;
static long aiConfigCacheTime = 0;
static final long AI_CONFIG_CACHE_MS = 60 * 1000;

static Map tagPoolCache = null;
static long tagPoolCacheTime = 0;
static final long TAG_POOL_CACHE_MS = 10 * 1000;
static String tagPoolCacheUin = "";

static String cachedPersona = null;
static long personaFileMtime = 0;

static String cachedSystemPrompt = null;
static long systemPromptFileMtime = 0;

static List cachedWakeWords = null;
static long wakeWordsFileMtime = 0;

static Timer delayTimer = null;
static boolean aiProcessing = false;
static Queue msgQueue = new LinkedList();
static final int MSG_QUEUE_MAX = 20;
static Set listenSessions = null;
static boolean aiReady = false;
static String lastAssistantMsg = null;
static String quotedUin = "";
static String patOperatorUin = null;
static String patPeerUin = null;
// ==================== SQLite ====================
SQLiteDatabase getDb() {
    if (sharedDb == null || !sharedDb.isOpen()) {
        String dbPath = pluginPath + "/config/data.db";
        File dbFile = new File(dbPath);
        File parent = dbFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        sharedDb = SQLiteDatabase.openOrCreateDatabase(dbPath, null);
        sharedDb.execSQL(
            "CREATE TABLE IF NOT EXISTS memories (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
            "uin TEXT NOT NULL, " +
            "content TEXT NOT NULL, " +
            "tags TEXT NOT NULL DEFAULT '', " +
            "scope TEXT NOT NULL DEFAULT 'private', " +
            "created_at INTEGER, " +
            "accessed_at INTEGER" +
            ")"
        );
        sharedDb.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_memories_uin ON memories(uin)"
        );
        sharedDb.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_memories_tags ON memories(tags)"
        );
        try { sharedDb.execSQL("ALTER TABLE memories ADD COLUMN tags TEXT NOT NULL DEFAULT ''"); } catch (Exception ignored) { }
        try { sharedDb.execSQL("ALTER TABLE memories ADD COLUMN scope TEXT NOT NULL DEFAULT 'private'"); } catch (Exception ignored) { }
        try { sharedDb.execSQL("ALTER TABLE memories ADD COLUMN subject_uin TEXT NOT NULL DEFAULT ''"); } catch (Exception ignored) { }
        try { sharedDb.execSQL("ALTER TABLE memories ADD COLUMN weight INTEGER NOT NULL DEFAULT 1"); } catch (Exception ignored) { }
        try { sharedDb.execSQL("ALTER TABLE memories ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0"); } catch (Exception ignored) { }
        try { sharedDb.execSQL("ALTER TABLE memories ADD COLUMN credibility INTEGER NOT NULL DEFAULT 8"); } catch (Exception ignored) { }
        try { sharedDb.execSQL("ALTER TABLE memories ADD COLUMN source_text TEXT NOT NULL DEFAULT ''"); } catch (Exception ignored) { }
        sharedDb.execSQL(
            "CREATE TABLE IF NOT EXISTS tag_pool (" +
            "uin TEXT NOT NULL, " +
            "tag TEXT NOT NULL, " +
            "count INTEGER NOT NULL DEFAULT 0, " +
            "PRIMARY KEY (uin, tag)" +
            ")"
        );
        sharedDb.execSQL("CREATE INDEX IF NOT EXISTS idx_tag_pool_uin ON tag_pool(uin)");

    }
    return sharedDb;
}

void closeSharedDb() {
    if (sharedDb != null && sharedDb.isOpen()) {
        sharedDb.close();
        sharedDb = null;
    }
}

// ==================== Persona + 唤醒词 ====================
String loadPersona() {
    String activeName = getActivePersona();
    File dir = new File(pluginPath + "/config/prompt");
    if (!dir.exists()) {
        dir.mkdirs();
    }
    File f = new File(pluginPath + "/config/prompt/" + activeName + ".prompt.txt");
    if (!f.exists()) {
        File oldF = new File(pluginPath + "/config/prompt.txt");
        if (oldF.exists()) {
            dir.mkdirs();
            oldF.renameTo(new File(pluginPath + "/config/prompt/default.prompt.txt"));
            setActivePersona("default");
            f = new File(pluginPath + "/config/prompt/default.prompt.txt");
        }
        if (!f.exists()) {
            return "";
        }
    }
    long mtime = f.lastModified();
    if (cachedPersona != null && mtime == personaFileMtime) {
        return cachedPersona;
    }
    StringBuilder sb = new StringBuilder();
    try {
        BufferedReader br = new BufferedReader(new FileReader(f));
        String line;
        boolean firstLine = true;
        while ((line = br.readLine()) != null) {
            if (firstLine && line.startsWith("##唤醒词")) {
                firstLine = false;
                continue;
            }
            firstLine = false;
            sb.append(line).append("\n");
        }
        br.close();
    } catch (Exception e) { this.log("error.txt", "loadPersona: " + e.getMessage()); return ""; }
    cachedPersona = sb.toString().trim();
    personaFileMtime = mtime;
    return cachedPersona;
}

String loadSystemPrompt() {
    File f = new File(pluginPath + "/system.prompt.txt");
    if (!f.exists()) {
        return "";
    }
    long mtime = f.lastModified();
    if (cachedSystemPrompt != null && mtime == systemPromptFileMtime) {
        return cachedSystemPrompt;
    }
    try {
        BufferedReader br = new BufferedReader(new FileReader(f));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line).append("\n");
        br.close();
        cachedSystemPrompt = sb.toString().trim();
        systemPromptFileMtime = mtime;
        return cachedSystemPrompt;
    } catch (Exception e) { this.log("error.txt", "loadSystemPrompt: " + e.getMessage()); return ""; }
}

List loadWakeWords() {
    String activeName = getActivePersona();
    File f = new File(pluginPath + "/config/prompt/" + activeName + ".prompt.txt");
    if (!f.exists()) {
        cachedWakeWords = new ArrayList();
        wakeWordsFileMtime = 0;
        return cachedWakeWords;
    }
    long mtime = f.lastModified();
    if (cachedWakeWords != null && mtime == wakeWordsFileMtime) {
        return cachedWakeWords;
    }
    List words = new ArrayList();
    try {
        BufferedReader br = new BufferedReader(new FileReader(f));
        String firstLine = br.readLine();
        br.close();
        if (firstLine != null && firstLine.startsWith("##唤醒词")) {
            String raw = firstLine.substring("##唤醒词".length()).trim();
            if (!raw.isEmpty()) {
                String[] parts = raw.split(",");
                for (int i = 0; i < parts.length; i++) {
                    String w = parts[i].trim();
                    if (!w.isEmpty()) {
                        words.add(w);
                    }
                }
            }
        }
    } catch (Exception e) { this.log("error.txt", "loadWakeWords: " + e.getMessage()); }
    cachedWakeWords = words;
    wakeWordsFileMtime = mtime;
    return words;
}

boolean startsWithWakeWord(String text) {
    if (text == null) {
        return false;
    }
    List words = loadWakeWords();
    if (words.isEmpty()) {
        return false;
    }
    for (int i = 0; i < words.size(); i++) {
        if (text.startsWith((String) words.get(i))) {
            return true;
        }
    }
    return false;
}

// ==================== 默认账户 ====================
String getDefaultAccount() {
    File f = new File(pluginPath + "/config/default_account.txt");
    if (!f.exists()) {
        return "member";
    }
    try {
        BufferedReader br = new BufferedReader(new FileReader(f));
        String s = br.readLine();
        br.close();
        if (s != null) { s = s.trim().toLowerCase(); if (s.equals("blocked")) { return "blocked"; } }
    } catch (Exception e) { }
    return "member";
}

void setDefaultAccountConfig(String type) {
    try {
        File parent = new File(pluginPath + "/config");
        if (!parent.exists()) {
            parent.mkdirs();
        }
        PrintWriter pw = new PrintWriter(new FileWriter(pluginPath + "/config/default_account.txt"));
        pw.println(type);
        pw.close();
    } catch (Exception e) { this.log("error.txt", "setDefaultAccountConfig: " + e.getMessage()); }
}

boolean canUseAi(String uin) {
    String role = getRole(uin);
    if (role.equals("BLOCKED")) {
        return false;
    }
    if (uin.equals(myUin)) {
        return true;
    }
    if (role.equals("ADMIN") || role.equals("OWNER")) {
        return true;
    }
    if (getDefaultAccount().equals("member")) {
        return true;
    }
    Set whitelist = readStringSet(pluginPath + "/config/members.txt");
    return whitelist.contains(uin);
}

// ==================== Tag 池 ====================
Map getTagPool(String uin) {
    long now = System.currentTimeMillis();
    if (tagPoolCache != null && uin.equals(tagPoolCacheUin) && (now - tagPoolCacheTime) < TAG_POOL_CACHE_MS) {
        return tagPoolCache;
    }
    Map pool = new LinkedHashMap();
    Cursor c = null;
    try {
        c = getDb().rawQuery("SELECT tag, count FROM tag_pool WHERE uin = ? ORDER BY count DESC, tag ASC", new String[]{uin});
        while (c.moveToNext()) pool.put(c.getString(0), c.getInt(1));
    } catch (Exception e) { this.log("error.txt", "getTagPool: " + e.getMessage()); }
    finally { if (c != null) c.close(); }
    tagPoolCache = pool;
    tagPoolCacheTime = now;
    tagPoolCacheUin = uin;
    return pool;
}

Map getPublicTagPool() {
    Map pool = new LinkedHashMap();
    Cursor c = null;
    try {
        c = getDb().rawQuery("SELECT tag, count FROM tag_pool WHERE uin = 'PUBLIC' ORDER BY count DESC, tag ASC", null);
        while (c.moveToNext()) pool.put(c.getString(0), c.getInt(1));
    } catch (Exception e) { this.log("error.txt", "getPublicTagPool: " + e.getMessage()); }
    finally { if (c != null) c.close(); }
    return pool;
}

void updateTagPool(String uin, String tagsStr, int delta) {
    if (tagsStr == null || tagsStr.trim().isEmpty()) {
        return;
    }
    String[] tags = tagsStr.split(",");
    SQLiteDatabase db = getDb();
    try {
        db.beginTransaction();
        for (int i = 0; i < tags.length; i++) {
            String t = tags[i].trim().toLowerCase();
            if (t.isEmpty()) {
                continue;
            }
            int curCount = getTagPoolCount(db, uin, t);
            int newCount = Math.max(0, curCount + delta);
            ContentValues cv = new ContentValues();
            cv.put("count", newCount);
            int updated = db.update("tag_pool", cv, "uin = ? AND tag = ?", new String[]{uin, t});
            if (updated == 0 && delta > 0) {
                cv.put("uin", uin);
                cv.put("tag", t);
                cv.put("count", 1);
                db.insert("tag_pool", null, cv);
            }
            if (updated > 0 && newCount <= 0) {
                db.delete("tag_pool", "uin = ? AND tag = ?", new String[]{uin, t});
            }
        }
        db.setTransactionSuccessful();
    } catch (Exception e) { this.log("error.txt", "updateTagPool: " + e.getMessage()); }
    finally { db.endTransaction(); }
    if (!"PUBLIC".equals(uin)) {
        tagPoolCache = null;
        tagPoolCacheTime = 0;
        tagPoolCacheUin = "";
    }
}

int getTagPoolCount(SQLiteDatabase db, String uin, String tag) {
    Cursor c = null;
    try {
        c = db.rawQuery("SELECT count FROM tag_pool WHERE uin = ? AND tag = ?", new String[]{uin, tag});
        if (c.moveToFirst()) {
            return c.getInt(0);
        }
    } catch (Exception ignored) { }
    finally { if (c != null) c.close(); }
    return 0;
}

void rebuildTagPool(String uin) {
    SQLiteDatabase db = getDb();
    Cursor c = null;
    try {
        db.beginTransaction();
        db.delete("tag_pool", "uin = ?", new String[]{uin});
        c = db.rawQuery(
            "SELECT tags FROM memories " +
            "WHERE uin = ? AND scope = 'private' AND tags != ''",
            new String[]{uin}
        );
        while (c.moveToNext()) {
            String ts = c.getString(0);
            if (ts == null || ts.trim().isEmpty()) {
                continue;
            }
            String[] tags = ts.split(",");
            for (int i = 0; i < tags.length; i++) {
                String t = tags[i].trim().toLowerCase();
                if (t.isEmpty()) {
                    continue;
                }
                ContentValues cv = new ContentValues();
                int cur = getTagPoolCount(db, uin, t);
                if (cur == 0) {
                    cv.put("uin", uin);
                    cv.put("tag", t);
                    cv.put("count", 1);
                    db.insert("tag_pool", null, cv);
                } else {
                    cv.put("count", cur + 1);
                    db.update(
                        "tag_pool", cv,
                        "uin = ? AND tag = ?",
                        new String[]{uin, t}
                    );
                }
            }
        }
        db.setTransactionSuccessful();
    } catch (Exception e) {
        this.log("error.txt", "rebuildTagPool: " + e.getMessage());
    } finally {
        if (c != null) {
            c.close();
        }
        db.endTransaction();
    }
    tagPoolCache = null;
    tagPoolCacheTime = 0;
}

void rebuildPublicTagPool() {
    SQLiteDatabase db = getDb();
    Cursor c = null;
    try {
        db.beginTransaction();
        db.delete("tag_pool", "uin = 'PUBLIC'", null);
        c = db.rawQuery(
            "SELECT tags FROM memories " +
            "WHERE scope = 'public' AND tags != ''",
            null
        );
        while (c.moveToNext()) {
            String ts = c.getString(0);
            if (ts == null || ts.trim().isEmpty()) {
                continue;
            }
            String[] tags = ts.split(",");
            for (int i = 0; i < tags.length; i++) {
                String t = tags[i].trim().toLowerCase();
                if (t.isEmpty()) {
                    continue;
                }
                ContentValues cv = new ContentValues();
                int cur = getTagPoolCount(db, "PUBLIC", t);
                if (cur == 0) {
                    cv.put("uin", "PUBLIC");
                    cv.put("tag", t);
                    cv.put("count", 1);
                    db.insert("tag_pool", null, cv);
                } else {
                    cv.put("count", cur + 1);
                    db.update(
                        "tag_pool", cv,
                        "uin = 'PUBLIC' AND tag = ?",
                        new String[]{t}
                    );
                }
            }
        }
        db.setTransactionSuccessful();
    } catch (Exception e) {
        this.log("error.txt", "rebuildPublicTagPool: " + e.getMessage());
    } finally {
        if (c != null) {
            c.close();
        }
        db.endTransaction();
    }
}

// ==================== 可信度计算 ====================
int calcCredibility(String uin, String scope, String subjectUin) {
    if ("private".equals(scope)) {
        return 8;
    }
    String role = getRole(uin);
    if (subjectUin != null && !subjectUin.trim().isEmpty() && uin.equals(subjectUin)) {
        if (role.equals("OWNER")) {
            return 10;
        }
        if (role.equals("ADMIN")) {
            return 9;
        }
        return 8;
    }
    if (subjectUin != null && !subjectUin.trim().isEmpty()) {
        if (role.equals("OWNER")) {
            return 7;
        }
        if (role.equals("ADMIN")) {
            return 6;
        }
        return 5;
    }
    if (role.equals("OWNER")) {
        return 8;
    }
    if (role.equals("ADMIN")) {
        return 7;
    }
    return 6;
}

// ==================== 记忆操作 ====================
String normalizeSubjectUin(String recordUin, String subjectUin) {
    if (subjectUin != null && !subjectUin.trim().isEmpty()) {
        return subjectUin.trim();
    }
    return recordUin != null ? recordUin : "";
}

String calcAssertionType(String recordUin, String subjectUin) {
    String su = normalizeSubjectUin(recordUin, subjectUin);
    if (recordUin != null && recordUin.equals(su)) {
        return "self";
    }
    if (su != null && !su.isEmpty()) {
        return "reported";
    }
    return "unknown";
}

String assertionTypeLabel(String assertionType) {
    if ("self".equals(assertionType)) {
        return "自述";
    }
    if ("reported".equals(assertionType)) {
        return "转述";
    }
    return "未知";
}

boolean storeMemory(String uin, String content, String tags, String scope, String subjectUin) {
    String su = normalizeSubjectUin(uin, subjectUin);
    return storeMemoryWithSource(uin, content, tags, scope, su, "", 0, "", "", uin, 0);
}

boolean storeMemoryWithSource(String uin, String content, String tags, String scope, String subjectUin,
                              String sourcePeerUin, int sourceChatType, String sourceMsgId, String sourceText,
                              String sourceSenderUin, long sourceTimeMs) {
    try {
        long now = System.currentTimeMillis();
        String su = normalizeSubjectUin(uin, subjectUin);
        int cred = calcCredibility(uin, scope, su);
        ContentValues cv = new ContentValues();
        cv.put("uin", uin);
        cv.put("content", content);
        cv.put("tags", tags != null ? tags : "");
        cv.put("scope", scope != null ? scope : "private");
        cv.put("subject_uin", su);
        cv.put("created_at", now);
        cv.put("accessed_at", now);
        cv.put("weight", 1);
        cv.put("pinned", 0);
        cv.put("credibility", cred);
        cv.put("source_text", sourceText != null ? sourceText : "");
        long id = getDb().insert("memories", null, cv);
        if (id != -1) {
            if ("public".equals(scope)) {
                updateTagPool("PUBLIC", tags, 1);
            }
            else {
                updateTagPool(uin, tags, 1);
            }
            writeLog(uin, "[MEMORY/" + scope + "] cred:" + cred + " tags:" + tags +
                " " + content + " (id=" + id + ")");
            return true;
        }
        return false;
    } catch (Exception e) { this.log("error.txt", "storeMemory: " + e.getMessage()); return false; }
}

void touchMemory(long id) {
    try {
        ContentValues cv = new ContentValues();
        cv.put("accessed_at", System.currentTimeMillis());
        getDb().update("memories", cv, "id = ?", new String[]{String.valueOf(id)});
    } catch (Exception ignored) { }
}

void boostWeight(long id, int delta) {
    try {
        getDb().execSQL("UPDATE memories SET weight = weight + " + delta + " WHERE id = " + id);
    } catch (Exception ignored) { }
}

List searchMemories(String uin, String keyword) {
    List results = new ArrayList();
    Cursor c = null;
    try {
        c = getDb().rawQuery(
            "SELECT id, content, tags, scope, subject_uin FROM memories " +
            "WHERE uin = ? AND scope = 'private' AND (content LIKE ? OR tags LIKE ?) " +
            "ORDER BY accessed_at DESC LIMIT 20",
            new String[]{uin, "%" + keyword + "%", "%" + keyword + "%"});
        while (c.moveToNext()) {
            Map m = new HashMap();
            m.put("id", c.getLong(0));
            m.put("content", c.getString(1));
            m.put("tags", c.getString(2) != null ? c.getString(2) : "");
            m.put("scope", c.getString(3));
            m.put("subjectUin", c.getString(4) != null ? c.getString(4) : "");
            results.add(m);
            touchMemory(c.getLong(0));
        }
    } catch (Exception e) { this.log("error.txt", "searchMem: " + e.getMessage()); }
    finally { if (c != null) c.close(); }
    return results;
}

List getStrataPrivate(String uin) {
    List all = new ArrayList();
    Cursor c = null;
    try {
        c = getDb().rawQuery(
            "SELECT id, content, tags, weight, pinned FROM memories " +
            "WHERE uin = ? AND scope = 'private' ORDER BY pinned DESC, weight DESC, accessed_at DESC",
            new String[]{uin});
        while (c.moveToNext()) {
            Map m = new HashMap();
            m.put("id", c.getLong(0));
            m.put("content", c.getString(1));
            m.put("tags", c.getString(2) != null ? c.getString(2) : "");
            m.put("weight", c.getInt(3));
            m.put("pinned", c.getInt(4));
            all.add(m);
        }
    } catch (Exception e) { this.log("error.txt", "getStrataPrivate: " + e.getMessage()); }
    finally { if (c != null) c.close(); }
    return all;
}

List getStrataPublic() {
    List all = new ArrayList();
    Cursor c = null;
    try {
        c = getDb().rawQuery(
            "SELECT id, content, tags, weight, pinned, credibility, subject_uin, uin FROM memories " +
            "WHERE scope = 'public' ORDER BY pinned DESC, (weight * credibility) DESC, accessed_at DESC",
        null);
        while (c.moveToNext()) {
            Map m = new HashMap();
            m.put("id", c.getLong(0));
            m.put("content", c.getString(1));
            m.put("tags", c.getString(2) != null ? c.getString(2) : "");
            m.put("weight", c.getInt(3));
            m.put("pinned", c.getInt(4));
            m.put("credibility", c.getInt(5));
            m.put("subjectUin", c.getString(6) != null ? c.getString(6) : "");
            m.put("record_uin", c.getString(7) != null ? c.getString(7) : "");
            all.add(m);
        }
    } catch (Exception e) { this.log("error.txt", "getStrataPublic: " + e.getMessage()); }
    finally { if (c != null) c.close(); }
    return all;
}
List getMyMemories(String uin, int limit) {
    List results = new ArrayList();
    Cursor c = null;
    try {
        c = getDb().rawQuery(
            "SELECT id, content, tags FROM memories WHERE uin = ? AND scope = 'private' " +
            "ORDER BY accessed_at DESC LIMIT ?", new String[]{uin, String.valueOf(limit)});
        while (c.moveToNext()) {
            Map m = new HashMap();
            m.put("id", c.getLong(0));
            m.put("content", c.getString(1));
            m.put("tags", c.getString(2) != null ? c.getString(2) : "");
            results.add(m);
        }
    } catch (Exception e) { this.log("error.txt", "getMyMemories: " + e.getMessage()); }
    finally { if (c != null) c.close(); }
    return results;
}

List getPublicMemories(int limit) {
    List results = new ArrayList();
    Cursor c = null;
    try {
        c = getDb().rawQuery(
            "SELECT id, content, tags FROM memories WHERE scope = 'public' " +
            "ORDER BY accessed_at DESC LIMIT ?", new String[]{String.valueOf(limit)});
        while (c.moveToNext()) {
            Map m = new HashMap();
            m.put("id", c.getLong(0));
            m.put("content", c.getString(1));
            m.put("tags", c.getString(2) != null ? c.getString(2) : "");
            results.add(m);
        }
    } catch (Exception e) { this.log("error.txt", "getPublicMemories: " + e.getMessage()); }
    finally { if (c != null) c.close(); }
    return results;
}

String fmtTime(long ts) {
    if (ts <= 0) {
        return "未知";
    }
    try { return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(ts)); }
    catch (Exception e) { return String.valueOf(ts); }
}

long getMsgTimeMs(Object msg) {
    try {
        Object tv;
        try {
            tv = msg.getClass().getField("time").get(msg);
        } catch (Exception e) {
            java.lang.reflect.Field tf = msg.getClass().getDeclaredField("time");
            tf.setAccessible(true);
            tv = tf.get(msg);
        }
        long t = Long.parseLong(String.valueOf(tv));
        if (t > 0 && t < 100000000000L) {
            return t * 1000L;
        }
        return t;
    } catch (Exception e) { return System.currentTimeMillis(); }
}

Map getMemoryDetail(long id) {
    Cursor c = null;
    try {
        c = getDb().rawQuery(
            "SELECT id, uin, content, tags, scope, subject_uin, weight, pinned, credibility, created_at, accessed_at, " +
            "source_text FROM memories WHERE id = ?",
            new String[]{String.valueOf(id)});
        if (c.moveToFirst()) {
            Map m = new HashMap();
            m.put("id", c.getLong(0));
            m.put("uin", c.getString(1) != null ? c.getString(1) : "");
            m.put("content", c.getString(2) != null ? c.getString(2) : "");
            m.put("tags", c.getString(3) != null ? c.getString(3) : "");
            m.put("scope", c.getString(4) != null ? c.getString(4) : "private");
            m.put("subjectUin", c.getString(5) != null ? c.getString(5) : "");
            m.put("weight", c.getInt(6));
            m.put("pinned", c.getInt(7));
            m.put("credibility", c.getInt(8));
            m.put("createdAt", c.getLong(9));
            m.put("accessedAt", c.getLong(10));
            m.put("sourceText", c.getString(11) != null ? c.getString(11) : "");
            return m;
        }
    } catch (Exception e) { this.log("error.txt", "getMemoryDetail: " + e.getMessage()); }
    finally { if (c != null) c.close(); }
    return null;
}

boolean canViewMemoryDetail(Map m, String requesterUin, String requesterRole) {
    if (m == null) {
        return false;
    }
    if ("public".equals((String) m.get("scope"))) {
        return true;
    }
    if (requesterRole.equals("ADMIN") || requesterRole.equals("OWNER")) {
        return true;
    }
    return requesterUin.equals((String) m.get("uin"));
}

boolean deleteMemoryById(long id, String requesterUin, String requesterRole) {
    try {
        Cursor c = getDb().rawQuery("SELECT uin, tags, scope FROM memories WHERE id = ?", new String[]{String.valueOf(id)});
        String tags = "";
        String memUin = "";
        String scope = "private";
        if (c.moveToFirst()) {
            memUin = c.getString(0);
            tags = c.getString(1);
            scope = c.getString(2);
        }
        c.close();
        int deleted;
        if (requesterRole.equals("ADMIN") || requesterRole.equals("OWNER")) {
            deleted = getDb().delete("memories", "id = ?", new String[]{String.valueOf(id)});
        } else {
            deleted = getDb().delete("memories", "id = ? AND uin = ?", new String[]{String.valueOf(id), requesterUin});
        }
        if (deleted > 0 && tags != null && !tags.trim().isEmpty()) {
            if ("public".equals(scope)) {
                updateTagPool("PUBLIC", tags, -1);
            }
            else {
                updateTagPool(memUin.isEmpty() ? requesterUin : memUin, tags, -1);
            }
        }
        return deleted > 0;
    } catch (Exception e) { return false; }
}

int deleteMemoriesByKeyword(String uin, String keyword) {
    SQLiteDatabase db = getDb();
    Cursor c = null;
    try {
        db.beginTransaction();
        c = db.rawQuery(
            "SELECT tags FROM memories WHERE uin = ? AND scope = 'private' AND content LIKE ?",
            new String[]{uin, "%" + keyword + "%"});
        while (c.moveToNext()) {
            String tags = c.getString(0);
            if (tags != null && !tags.trim().isEmpty()) {
                updateTagPool(uin, tags, -1);
            }
        }
        int deleted = db.delete("memories", "uin = ? AND scope = 'private' AND content LIKE ?", new String[]{uin, "%" + keyword + "%"});
        db.setTransactionSuccessful();
        return deleted;
    } catch (Exception e) { this.log("error.txt", "deleteMemByKw: " + e.getMessage()); return 0; }
    finally { if (c != null) c.close(); db.endTransaction(); }
}

List searchMemoriesByTag(String uin, String tag) {
    List results = new ArrayList();
    Cursor c = null;
    try {
        c = getDb().rawQuery(
            "SELECT id, content, tags, scope, subject_uin FROM memories " +
            "WHERE uin = ? AND scope = 'private' AND tags LIKE ? ORDER BY accessed_at DESC LIMIT 20",
            new String[]{uin, "%" + tag.toLowerCase() + "%"});
        while (c.moveToNext()) {
            Map m = new HashMap();
            m.put("id", c.getLong(0));
            m.put("content", c.getString(1));
            m.put("tags", c.getString(2) != null ? c.getString(2) : "");
            m.put("scope", c.getString(3));
            m.put("subjectUin", c.getString(4) != null ? c.getString(4) : "");
            results.add(m);
            touchMemory(c.getLong(0));
            boostWeight(c.getLong(0), 3);
        }
    } catch (Exception e) { this.log("error.txt", "searchMemByTag: " + e.getMessage()); }
    finally { if (c != null) c.close(); }
    return results;
}

List searchPublicMemoriesByTag(String tag) {
    List results = new ArrayList();
    Cursor c = null;
    try {
        c = getDb().rawQuery(
            "SELECT id, content, tags, scope, subject_uin FROM memories " +
            "WHERE scope = 'public' AND tags LIKE ? ORDER BY accessed_at DESC LIMIT 20",
            new String[]{"%" + tag.toLowerCase() + "%"});
        while (c.moveToNext()) {
            Map m = new HashMap();
            m.put("id", c.getLong(0));
            m.put("content", c.getString(1));
            m.put("tags", c.getString(2) != null ? c.getString(2) : "");
            m.put("scope", "public");
            m.put("subjectUin", c.getString(4) != null ? c.getString(4) : "");
            results.add(m);
            touchMemory(c.getLong(0));
        }
    } catch (Exception e) { this.log("error.txt", "searchPubByTag: " + e.getMessage()); }
    finally { if (c != null) c.close(); }
    return results;
}

List searchPublicMemories(String keyword) {
    List results = new ArrayList();
    Cursor c = null;
    try {
        c = getDb().rawQuery(
            "SELECT id, content, tags, scope, subject_uin FROM memories " +
            "WHERE scope = 'public' AND (content LIKE ? OR tags LIKE ?) ORDER BY accessed_at DESC LIMIT 20",
            new String[]{"%" + keyword + "%", "%" + keyword + "%"});
        while (c.moveToNext()) {
            Map m = new HashMap();
            m.put("id", c.getLong(0));
            m.put("content", c.getString(1));
            m.put("tags", c.getString(2) != null ? c.getString(2) : "");
            m.put("scope", "public");
            m.put("subjectUin", c.getString(4) != null ? c.getString(4) : "");
            results.add(m);
            touchMemory(c.getLong(0));
        }
    } catch (Exception e) { this.log("error.txt", "searchPubMem: " + e.getMessage()); }
    finally { if (c != null) c.close(); }
    return results;
}

// ==================== Strata 格式化核心 ====================
String buildStrataContext(String senderUin) {
    StringBuilder ctx = new StringBuilder();
    List privAll = getStrataPrivate(senderUin);
    int total = privAll.size();
    int topN;
    if (total < 30) {
        topN = total;
    }
    else if (total <= 150) {
        topN = 30;
    }
    else {
        topN = 50;
    }

    Set hotTags = new HashSet();
    Set seenPinned = new HashSet();

    // 置顶
    boolean hasPinned = false;
    for (int i = 0; i < privAll.size(); i++) {
        Map m = (Map) privAll.get(i);
        int pinned = (Integer) m.get("pinned");
        if (pinned == 1 && !seenPinned.contains(m.get("id"))) {
            if (!hasPinned) {
                ctx.append("<pinned/>\n");
                hasPinned = true;
            }
            seenPinned.add(m.get("id"));
            ctx.append("#MP").append(m.get("id")).append(" ").append(m.get("content")).append("\n");
            String tags = (String) m.get("tags");
            if (tags != null && !tags.trim().isEmpty()) {
                String[] ta = tags.split(",");
                for (int j = 0; j < ta.length; j++) hotTags.add(ta[j].trim().toLowerCase());
            }
        }
    }

    // 热层
    int count = 0;
    if (ctx.length() > 0 && topN > 0) {
        ctx.append("\n");
    }
    if (topN > 0) {
        ctx.append("<archive/>\n");
        for (int i = 0; i < privAll.size() && count < topN; i++) {
            Map m = (Map) privAll.get(i);
            int pinned = (Integer) m.get("pinned");
            if (pinned == 1 && seenPinned.contains(m.get("id"))) {
                continue;
            }
            count++;
            ctx.append("#M").append(m.get("id")).append(" ").append(m.get("content")).append("\n");
            String tags = (String) m.get("tags");
            if (tags != null && !tags.trim().isEmpty()) {
                String[] ta = tags.split(",");
                for (int j = 0; j < ta.length; j++) hotTags.add(ta[j].trim().toLowerCase());
            }
            touchMemory((Long) m.get("id"));
        }
    }

    // 冷标签补集
    Map pool = getTagPool(senderUin);
    StringBuilder coldTags = new StringBuilder();
    int ct = 0;
    for (Object e : pool.entrySet()) {
        Map.Entry en = (Map.Entry) e;
        String tag = (String) en.getKey();
        if (!hotTags.contains(tag.toLowerCase())) {
            if (ct > 0) {
                coldTags.append(", ");
            }
            coldTags.append(tag);
            ct++;
            if (coldTags.length() > 200) {
                coldTags.append(" ...共" + pool.size() + "个标签");
                break;
            }
        }
    }
    if (coldTags.length() > 0) {
        ctx.append("\n<coldtags>").append(coldTags.toString()).append("</coldtags>\n");
        ctx.append("问题涉及冷标签且档案无答案时，调 search_by_tag 回查。\n");
    }
    return ctx.toString();
}

String buildPublicStrata() {
    List pubAll = getStrataPublic();
    if (pubAll.isEmpty()) {
        return "";
    }
    int total = pubAll.size();
    int topN;
    if (total < 30) {
        topN = total;
    }
    else if (total <= 150) {
        topN = 30;
    }
    else {
        topN = 50;
    }

    Map pool = getPublicTagPool();
    Set hotTags = new HashSet();
    Set seenPinned = new HashSet();
    StringBuilder ctx = new StringBuilder();

    // 置顶
    boolean hasPinned = false;
    for (int i = 0; i < pubAll.size(); i++) {
        Map pm = (Map) pubAll.get(i);
        int pinned = (Integer) pm.get("pinned");
        if (pinned == 1 && !seenPinned.contains(pm.get("id"))) {
            if (!hasPinned) {
                ctx.append("<public_pinned/>\n");
                hasPinned = true;
            }
            seenPinned.add(pm.get("id"));
            int cred = (Integer) pm.get("credibility");
            String ru = (String) pm.get("record_uin");
            long accessed = getAccessedAt((Long) pm.get("id"));
            ctx.append("#PP").append(pm.get("id"))
               .append("[信:").append(cred).append(",由:").append(getRole(ru)).append("|UIN:").append(ru)
               .append(",活:").append(relativeTime(accessed)).append("] ")
               .append(pm.get("content")).append("\n");
            String tags = (String) pm.get("tags");
            if (tags != null && !tags.trim().isEmpty()) {
                for (String t : tags.split(",")) hotTags.add(t.trim().toLowerCase());
            }
        }
    }

    // 热层
    int count = 0;
    if (ctx.length() > 0 && topN > 0) {
        ctx.append("\n");
    }
    if (topN > 0) {
        ctx.append("<public_archive/>\n");
        for (int i = 0; i < pubAll.size() && count < topN; i++) {
            Map pm = (Map) pubAll.get(i);
            int pinned = (Integer) pm.get("pinned");
            if (pinned == 1 && seenPinned.contains(pm.get("id"))) {
                continue;
            }
            count++;
            int cred = (Integer) pm.get("credibility");
            String ru = (String) pm.get("record_uin");
            long accessed = getAccessedAt((Long) pm.get("id"));
            ctx.append("#P").append(pm.get("id"))
               .append("[信:").append(cred).append(",由:").append(getRole(ru)).append("|UIN:").append(ru)
               .append(",活:").append(relativeTime(accessed)).append("] ")
               .append(pm.get("content")).append("\n");
            String tags = (String) pm.get("tags");
            if (tags != null && !tags.trim().isEmpty()) {
                for (String t : tags.split(",")) hotTags.add(t.trim().toLowerCase());
            }
        }
    }

    if (!pool.isEmpty()) {
        StringBuilder cold = new StringBuilder();
        int ct = 0;
        for (Object e : pool.entrySet()) {
            Map.Entry en = (Map.Entry) e;
            String tag = (String) en.getKey();
            if (!hotTags.contains(tag.toLowerCase())) {
                if (ct > 0) {
                    cold.append(", ");
                }
                cold.append(tag);
                if (++ct >= 15) {
                    break;
                }
            }
        }
        if (cold.length() > 0) {
            ctx.append("<public_coldtags>").append(cold.toString()).append("</public_coldtags> 查公有信息用 search_public_by_tag。\n");
        }
    }
    return ctx.toString().trim();
}

// ==================== FC 工具（单模型） ====================
JSONArray buildAI2Tools() {
    JSONArray tools = new JSONArray();
    JSONObject t = new JSONObject();
    t.put("type", "function");
    JSONObject f = new JSONObject();
    f.put("name", "shell");
    f.put("description", "Corax OS Shell。执行 Linux 风格命令。支持管道(|) 重定向(>) 后台(&)。run corax-help for available commands. 输出结果将自动返回给你。");
    f.put("parameters", new JSONObject(
        "{\"type\":\"object\",\"properties\":{\"cmd\":{\"type\":\"string\",\"description\":\"要执行的shell命令\"}},\"required\":[\"cmd\"]}"));
    t.put("function", f);
    tools.put(t);
    return tools;
}

// ==================== AI2 Prompt ====================
String buildAI2Prompt(String peerUin, int chatType) {
    StringBuilder sb = new StringBuilder();
    String persona = loadPersona();
    if (!persona.isEmpty()) { sb.append(persona).append("\n\n"); }
    else { sb.append("你是墨鸦,一个有长期记忆的 AI 助手。\n\n"); }

    String systemPrompt = loadSystemPrompt();
    if (!systemPrompt.isEmpty()) {
        sb.append(systemPrompt).append("\n\n");
    }
    
    sb.append("<skills>\n");
    sb.append("记忆：#M/#MP私有 #P/#PP公有。标签必打。用户透露信息时主动corax-mem-create。\n");
    sb.append("搜索：corax-mem-tag(标签) corax-mem-search(关键词)。\n");
    sb.append("联网：corax-search/corax-fetch，≤");
    int shellRounds = 8;
    try { shellRounds = Integer.parseInt(getAiConfig("shell_rounds")); } catch (Exception e) { }
    sb.append(shellRounds + "轮必须回复。\n");
    sb.append("定时：sleep N && cmd > /dev/out &\n");
    sb.append("</skills>\n");

    return sb.toString();
}

// ==================== AI 上下文 ====================
List getAiContext(String peerUin, int chatType) {
    String key = peerUin + "_" + chatType;
    List ctx = (List) aiContexts.get(key);
    long ttl = 30 * 60 * 1000L;
    try { ttl = Long.parseLong(getAiConfig("context_ttl")) * 60 * 1000L; } catch (Exception e) { }
    long now = System.currentTimeMillis();
    if (ttl > 0 && ctx != null && !ctx.isEmpty()) {
        Map last = (Map) ctx.get(ctx.size() - 1);
        Long ts = (Long) last.get("_ts");
        if (ts != null && (now - ts) > ttl) {
            aiContexts.remove(key);
            ctx = null;
        }
    }
    // v3.0: 内存无缓存时从磁盘恢复
    if (ctx == null || ctx.isEmpty()) {
        File cf = new File(pluginPath + "/config/ctx/" + key + ".json");
        if (cf.exists()) {
            try {
                BufferedReader br = new BufferedReader(new FileReader(cf));
                StringBuilder raw = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) raw.append(line);
                br.close();
                JSONArray arr = new JSONArray(raw.toString());
                ctx = new ArrayList();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject j = arr.getJSONObject(i);
                    Map m = new HashMap();
                    m.put("role", j.getString("role"));
                    m.put("content", j.getString("content"));
                    if (j.has("name")) {
                        m.put("name", j.getString("name"));
                    }
                    if (j.has("tool_calls")) {
                        m.put("tool_calls", j.getJSONArray("tool_calls"));
                    }
                    if (j.has("tool_call_id")) {
                        m.put("tool_call_id", j.getString("tool_call_id"));
                    }
                    m.put("_ts", j.getLong("_ts"));
                    ctx.add(m);
                }
                if (!ctx.isEmpty()) {
                    Map last = (Map) ctx.get(ctx.size() - 1);
                    Long ts = (Long) last.get("_ts");
                    if (ts != null && (now - ts) > ttl) {
                        ctx = new ArrayList();
                    }
                }
            } catch (Exception e) { ctx = new ArrayList(); }
        }
    }

    if (ctx == null) { ctx = new ArrayList(); }
    aiContexts.put(key, ctx);
    return ctx;
}

void clearAiContext(String peerUin, int chatType) {
    String key = peerUin + "_" + chatType;
    aiContexts.remove(key);
    File cf = new File(pluginPath + "/config/ctx/" + key + ".json");
    if (cf.exists()) {
        cf.delete();
    }
}

void saveCtxToDisk(String peerUin, int chatType) {
    String key = peerUin + "_" + chatType;
    List ctx = (List) aiContexts.get(key);
    if (ctx == null || ctx.isEmpty()) {
        return;
    }
    try {
        File dir = new File(pluginPath + "/config/ctx");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        JSONArray arr = new JSONArray();
        for (int i = 0; i < ctx.size(); i++) {
            Map m = (Map) ctx.get(i);
            JSONObject j = new JSONObject();
            j.put("role", m.get("role"));
            j.put("content", m.get("content"));
            if (m.get("name") != null) {
                j.put("name", m.get("name"));
            }
            if (m.get("tool_calls") != null) {
                j.put("tool_calls", m.get("tool_calls"));
            }
            if (m.get("tool_call_id") != null) {
                j.put("tool_call_id", m.get("tool_call_id"));
            }
            j.put("_ts", m.get("_ts"));
            arr.put(j);
        }
        PrintWriter pw = new PrintWriter(new FileWriter(new File(dir, key + ".json")));
        pw.print(arr.toString());
        pw.close();
    } catch (Exception e) { this.log("error.txt", "saveCtx: " + e.getMessage()); }
}

void trimCtx(List ctx) {
    int ctxLimit = 60;
    try { ctxLimit = Integer.parseInt(getAiConfig("context_limit")); } catch (Exception e) { }
    // 按对话轮数截断（一轮 = 用户消息 → AI回复 → 工具调用）
    int rounds = 0;
    int keepFrom = 0;
    for (int i = ctx.size() - 1; i >= 0; i--) {
        Map m = (Map) ctx.get(i);
        if ("user".equals(m.get("role"))) {
            rounds++;
            if (rounds > ctxLimit) {
                keepFrom = i + 1;
                break;
            }
        }
    }
    while (keepFrom > 0) {
        ctx.remove(0);
        keepFrom--;
    }
    // 清除开头的孤立 tool 消息（前置 assistant+tool_calls 已被截断）
    while (!ctx.isEmpty()) {
        Map first = (Map) ctx.get(0);
        if ("tool".equals(first.get("role"))) {
            ctx.remove(0);
        } else {
            break;
        }
    }
    // 清除尾部的孤立 assistant+tool_calls（后置 tool 已被截断）
    while (!ctx.isEmpty()) {
        Map last = (Map) ctx.get(ctx.size() - 1);
        if ("assistant".equals(last.get("role")) && last.get("tool_calls") != null) {
            ctx.remove(ctx.size() - 1);
        } else {
            break;
        }
    }
}

void injectApprovalResult(String peerUin, int chatType, String message) {
    List ictx = getAiContext(peerUin, chatType);
    if (ictx == null) {
        ictx = new ArrayList();
    }
    Map m = new HashMap();
    m.put("role", "system");
    m.put("content", "<operation-result>" + message + "</operation-result>");
    m.put("_ts", System.currentTimeMillis());
    ictx.add(m);
    saveCtxToDisk(peerUin, chatType);
}
void addToContext(List ctx, String role, String content, String name) {
    Map m = new HashMap();
    m.put("role", role); m.put("content", content);
    if (name != null) {
        m.put("name", name);
    }
    m.put("_ts", System.currentTimeMillis()); ctx.add(m);
    trimCtx(ctx);
}

void addToContextTC(List ctx, String role, String content, String name, JSONArray toolCalls, String toolCallId) {
    Map m = new HashMap();
    m.put("role", role); m.put("content", content);
    if (name != null) {
        m.put("name", name);
    }
    if (toolCalls != null) {
        m.put("tool_calls", toolCalls);
    }
    if (toolCallId != null) {
        m.put("tool_call_id", toolCallId);
    }
    m.put("_ts", System.currentTimeMillis()); ctx.add(m);
    trimCtx(ctx);
}

// ==================== AI 调用 ====================
Map callAI(String configPrefix, String systemPrompt, JSONArray messages, int maxTokens, JSONArray tools) {
    System.setProperty("http.keepAlive", "true");
    Map cfg = loadAiConfig();
    String apiKey = resolveAiCfg(cfg, configPrefix + "api_key", "api_key");
    if (apiKey.isEmpty()) {
        return null;
    }
    String model = resolveAiCfg(cfg, configPrefix + "model", "model");
    if (model.isEmpty()) {
        model = "deepseek-v4-flash";
    }
    String aiUrl = resolveAiCfg(cfg, configPrefix + "api_url", "ai_url");
    if (aiUrl.isEmpty()) {
        aiUrl = "https://api.deepseek.com";
    }
    HttpURLConnection conn = null;
    try {
        URL url = new URL(aiUrl + "/v1/chat/completions");
        conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setRequestProperty("Connection", "keep-alive");
        conn.setDoOutput(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(120000);
        JSONObject body = new JSONObject();
        body.put("model", model);
        double temp = 0.7;
        String ts = (String) cfg.get("temperature");
        if (ts != null && !ts.isEmpty()) { try { temp = Double.parseDouble(ts); } catch (Exception e) { } }
        body.put("temperature", temp);
        body.put("max_tokens", maxTokens);
        JSONArray allMsgs = new JSONArray();
        JSONObject sys = new JSONObject();
        sys.put("role", "system");
        sys.put("content", systemPrompt);
        allMsgs.put(sys);
        for (int i = 0; i < messages.length(); i++) allMsgs.put(messages.get(i));
        body.put("messages", allMsgs);
        if (tools != null && tools.length() > 0) {
            body.put("tools", tools);
            body.put("tool_choice", "auto");
        }
        OutputStream os = conn.getOutputStream();
        os.write(body.toString().getBytes("UTF-8"));
        os.flush();
        os.close();
        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder resp = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) resp.append(line);
        br.close();
        if (code != 200) {
            this.log("error.txt", "AI HTTP " + code + ": " + resp.toString());
            return null;
        }
        JSONObject jResp = new JSONObject(resp.toString());
        JSONArray choices = jResp.getJSONArray("choices");
        Map result = new HashMap();
        if (choices.length() > 0) {
            JSONObject msgObj = choices.getJSONObject(0).getJSONObject("message");
            result.put("content", msgObj.has("content") ? msgObj.getString("content") : "");
            if (msgObj.has("tool_calls")) {
                result.put("tool_calls", msgObj.getJSONArray("tool_calls"));
            }
        } else { result.put("content", ""); }
        if (jResp.has("usage")) {
            JSONObject usage = jResp.getJSONObject("usage");
            result.put("prompt_tokens", usage.optInt("prompt_tokens", 0));
            result.put("completion_tokens", usage.optInt("completion_tokens", 0));
        } else { result.put("prompt_tokens", 0); result.put("completion_tokens", 0); }
        return result;
    } catch (Exception e) { this.log("error.txt", "callAI: " + e.getMessage()); return null; }
    finally { if (conn != null) { try { conn.disconnect(); } catch (Exception ignored) { } } }
}

// ==================== handleAi v4.0 Strata ====================
void handleAi(Object msg, String prompt) {
    long startTime = System.currentTimeMillis();
    int totalPt = 0; int totalCt = 0; int totalCalls = 0;
    String senderUin = String.valueOf(msg.userUin);
    // 系统消息保护：UIN 无效时跳过处理
    if (senderUin == null || senderUin.isEmpty() || senderUin.equals("0") || senderUin.equals("null")) {
        aiProcessing = false; return;
    }
    String peerUin = String.valueOf(msg.peerUin);
    if ("null".equals(peerUin) && patPeerUin != null) {
        peerUin = patPeerUin;
    }
    int chatType = msg.type;
    String userRole = getRole(senderUin);
    String senderName = getMemberName(chatType, peerUin, senderUin);
    boolean debug = "1".equals(getAiConfig("debug"));
    String trimmed = prompt.trim();
    prompt = sewardenClean(prompt);
    trimmed = prompt.trim();
    String quotedText = "";
    String quotedMsgId = "";
    String forUser = trimmed;
    List ww = loadWakeWords();
    for (int i = 0; i < ww.size(); i++) {
        String w = (String) ww.get(i);
        if (forUser.startsWith(w)) {
            forUser = forUser.substring(w.length());
            if (forUser.startsWith("，") || forUser.startsWith(",") || forUser.startsWith(" ") || forUser.startsWith("　")) {
                forUser = forUser.substring(1);
            }
            forUser = forUser.trim();
            break;
        }
    }
    if (trimmed.equalsIgnoreCase("on")) {
    if (!userRole.equals("ADMIN") && !userRole.equals("OWNER")) {
        sendStyledHeader(msg, "ERROR", "权限不足"); return; }
        addToList(pluginPath + "/config/enabled_conversations.txt", peerUin + "_" + chatType);
        sendStyledHeader(msg, "INFO", "当前会话 AI 已启用"); return;
    }
    if (trimmed.equalsIgnoreCase("off")) {
        if (!userRole.equals("ADMIN") && !userRole.equals("OWNER")) {
            sendStyledHeader(msg, "ERROR", "权限不足");
            return;
        }
        removeFromList(pluginPath + "/config/enabled_conversations.txt", peerUin + "_" + chatType);
        sendStyledHeader(msg, "INFO", "当前会话 AI 已禁用"); return;
    }
    if (trimmed.equalsIgnoreCase("status")) {
        Set en = readStringSet(pluginPath + "/config/enabled_conversations.txt");
        sendStyledHeader(msg, "INFO", "当前会话: AI " + (en.contains(peerUin + "_" + chatType) ? "已启用" : "未启用")); return;
    }
    if (!readStringSet(pluginPath + "/config/enabled_conversations.txt").contains(peerUin + "_" + chatType)) {
        sendStyledHeader(msg, "INFO", "AI 未启用，发送 /ai on 启用");
        return;
    }
    if (!canUseAi(senderUin)) {
        sendStyledHeader(msg, "ERROR", "没有 AI 权限"); return;
    }
    if (trimmed.equalsIgnoreCase("clear")) {
        if (!userRole.equals("ADMIN") && !userRole.equals("OWNER")) {
            sendStyledHeader(msg, "ERROR", "权限不足");
            return;
        }
        clearAiContext(peerUin, chatType);
        sendStyledHeader(msg, "INFO", "上下文已清除"); return;
    }
    if (trimmed.equalsIgnoreCase("config")) {
        handleAiConfig(msg);
        return;
    }
    if (trimmed.startsWith("set ")) {
        handleAiSet(msg, trimmed.substring(4).trim());
        return;
    }
    if (trimmed.equals("memory") || trimmed.startsWith("memory ")) {
        handleAiMemory(msg, trimmed.startsWith("memory ") ? trimmed.substring(7).trim() : ""); return;
    }
    if (trimmed.startsWith("forget ")) {
        handleAiForget(msg, trimmed.substring(7).trim());
        return;
    }
    // 提前解析引用信息，供 dumpctx 和后续流程使用
    try {
        Object msgData = msg.data;
        if (msgData != null) {
            try {
                java.util.List elements = (java.util.List) msgData.getClass()
                    .getDeclaredField("elements")
                    .get(msgData);
                if (elements != null) {
                    for (int ei = 0; ei < elements.size(); ei++) {
                        Object el = elements.get(ei);
                        java.lang.reflect.Field rf = el.getClass().getDeclaredField("replyElement"); rf.setAccessible(true);
                        Object re = rf.get(el);
                        if (re != null) {
                            String ruin = "";
                            try { java.lang.reflect.Field sf = re.getClass().getDeclaredField("senderUin"); sf.setAccessible(true); Object su = sf.get(re); if (su != null && !su.toString().isEmpty()) ruin = su.toString(); } catch (Exception ex2) { }
                            quotedUin = ruin;
                            try {
                                java.lang.reflect.Field sf2 = re.getClass().getDeclaredField("sourceMsgId");
                                sf2.setAccessible(true);
                                Object smi = sf2.get(re);
                                if (smi != null && !smi.toString().isEmpty()) {
                                    quotedMsgId = smi.toString();
                                }
                            } catch (Exception ex3) { }
                            try {
                                java.lang.reflect.Field sf = re.getClass().getDeclaredField("sourceMsgText");
                                sf.setAccessible(true);
                                Object src = sf.get(re);
                                if (src != null && !src.toString().isEmpty()) { quotedText = sewardenClean(src.toString()); }
                            } catch (Exception ex2) { }
                            break;
                        }
                    }
                }
           } catch (Exception ignored) { }
        }
    } catch (Exception ignored) { }

    getDb(); List ctx = getAiContext(peerUin, chatType);

    if (trimmed.equals("listen") || trimmed.equals("listen on") || trimmed.equals("listen off") || trimmed.equals("listen status")) {
        if (!userRole.equals("ADMIN") && !userRole.equals("OWNER")) {
            sendPermissionDenied(msg);
            return;
        }
        String key = peerUin + "_" + chatType;
        if (trimmed.equals("listen") || trimmed.equals("listen on")) {
            clearListenLog(peerUin, chatType);
            addToList(pluginPath + "/config/listen_sessions.txt", key);
            if (listenSessions != null) {
                listenSessions.add(key);
            }
            List lctx = getAiContext(peerUin, chatType);
            Map lm = new HashMap();
            lm.put("role", "system");
            lm.put("content", "<listen t=\"" + getCurrentTime() + "\">开启</listen>");
            lm.put("_ts", System.currentTimeMillis());
            lctx.add(lm);
            sendStyledHeader(msg, "INFO", "监听已开启"); return;
        } else if (trimmed.equals("listen off")) {
            removeFromList(pluginPath + "/config/listen_sessions.txt", key);
            if (listenSessions != null) {
                listenSessions.remove(key);
            }
            List lctx = getAiContext(peerUin, chatType);
            Map lm = new HashMap();
            lm.put("role", "system");
            lm.put("content", "<listen t=\"" + getCurrentTime() + "\">关闭</listen>");
            lm.put("_ts", System.currentTimeMillis());
            lctx.add(lm);
            sendStyledHeader(msg, "INFO", "监听已关闭，临时群聊记录已删除"); return;
        } else if (trimmed.equals("listen summary") || trimmed.equals("listen summarize")) {
            handleListenSummary(msg); return;
        } else {
            Set ls = readStringSet(pluginPath + "/config/listen_sessions.txt");
            sendStyledHeader(msg, "INFO", "监听: " + (ls.contains(key) ? "已开启" : "已关闭")); return;
        }
    }


    if (trimmed.equals("reboot") || trimmed.startsWith("reboot ")) {
        handleReboot(msg, trimmed);
        String newPersona = loadPersona();
        if (!newPersona.isEmpty()) {
            addToContext(ctx, "system", "现在你需要扮演以下角色，忘记之前的身份设定：\n\n" + newPersona, null);
        }
        saveCtxToDisk(peerUin, chatType);
        return;
    }
    if (trimmed.equals("debug") || trimmed.startsWith("debug ")) {
        handleDebug(msg, trimmed);
        return;
    }
    Map cfg = loadAiConfig();
    if (((String) cfg.get("api_key")).isEmpty()) {
        sendStyledHeader(msg, "ERROR", "AI 未启用"); aiProcessing = false; return;
    }

    if (trimmed.equals("dumpctx")) {
        if (!userRole.equals("OWNER") && !userRole.equals("ADMIN")) {
            sendPermissionDenied(msg);
            return;
        }
        JSONArray dumpMsgs = new JSONArray();
        String fullPrompt = buildAI2Prompt(peerUin, chatType);
        String personaText = loadPersona();
        if (!personaText.isEmpty()) {
            fullPrompt = fullPrompt.replace(personaText, "[人设:" + getActivePersona() + ".prompt.txt (" + personaText.length() + "字符)]");
        }
        JSONObject ds = new JSONObject(); ds.put("role", "system"); ds.put("content", fullPrompt); dumpMsgs.put(ds);

        List dumpCtx = getAiContext(peerUin, chatType);
        for (int i = 0; i < dumpCtx.size(); i++) {
            Map dm = (Map) dumpCtx.get(i);
            String ctxContent = (String) dm.get("content");
           JSONObject dj = new JSONObject();
           dj.put("role", dm.get("role"));
           dj.put("content", ctxContent);
           if (dm.get("name") != null) {
               dj.put("name", dm.get("name"));
           }
           if (dm.get("tool_calls") != null) {
               dj.put("tool_calls", dm.get("tool_calls"));
           }
           if (dm.get("tool_call_id") != null) {
               dj.put("tool_call_id", dm.get("tool_call_id"));
           }
dumpMsgs.put(dj);
        }

        String pubS = buildPublicStrata(); if (!pubS.isEmpty()) { JSONObject pj = new JSONObject(); pj.put("role", "system"); pj.put("content", pubS); dumpMsgs.put(pj); }
        String privS = buildStrataContext(senderUin); if (!privS.isEmpty()) { JSONObject pvj = new JSONObject(); pvj.put("role", "system"); pvj.put("content", privS); dumpMsgs.put(pvj); }
        if (!quotedText.isEmpty()) {
            JSONObject qmu = new JSONObject(); qmu.put("role", "user");
            qmu.put("name", quotedUin);
            qmu.put("content", "<t>" + getCurrentTime() + "</t><u>" + quotedText + "</u>");
            dumpMsgs.put(qmu);
        }

        StringBuilder scx = new StringBuilder(); scx.append(chatType == 2 ? "群聊 群号:" + peerUin : "私聊").append(" 时间:").append(getCurrentTime());
        JSONObject scj = new JSONObject(); scj.put("role", "system"); scj.put("content", scx.toString()); dumpMsgs.put(scj);

        JSONObject uj = new JSONObject();
        uj.put("role", "user");
        uj.put("name", senderUin);
        String ujContent = "<t>" + getCurrentTime() + "</t>";
        if (!quotedMsgId.isEmpty()) {
            ujContent += "<refmsgid>" + quotedMsgId + "</refmsgid>";
        }
        ujContent += "<u>" + prompt + "</u>";
        uj.put("content", ujContent);
        dumpMsgs.put(uj);

        JSONObject dumpBody = new JSONObject();
        dumpBody.put("messages", dumpMsgs);
        dumpBody.put("tools", buildAI2Tools());
        String fp = pluginPath + "/config/ctxdump.json";
         try { PrintWriter pw = new PrintWriter(new FileWriter(fp)); pw.print(dumpBody.toString(2)); pw.close(); sendFile(peerUin, fp, chatType); }
         catch (Exception e) { sendStyledHeader(msg, "ERROR", "导出失败"); }
         aiProcessing = false; return;
    }

    String atInfo = "";
    try {
        if (msg.atList != null && msg.atList.size() > 0) {
            StringBuilder atSb = new StringBuilder();
            for (int ai = 0; ai < msg.atList.size(); ai++) {
                String atUin = String.valueOf(msg.atList.get(ai));
                if (atUin.equals(myUin)) {
                    continue;
                }
                atSb.append("@").append(getMemberName(chatType, peerUin, atUin)).append("(UIN:").append(atUin).append(") ");
            }
            if (atSb.length() > 0) {
                atInfo = "目标: " + atSb.toString();
            }
        }
    } catch (Exception ignored) { }

    // v3.0: 清洗 senderName（去结构字符）、ctx 不混入身份
    senderName = senderName.replaceAll("[{｛].*?[}｝]", "")
                       .replaceAll("[<＜].*?[>＞]", "")
                       .replaceAll("【.*?】", "")
                       .replaceAll("\\[.*?\\]", "")
                       .replaceAll("[,，:：;；]", "")
                       .trim();
    senderName = senderName.replace("__", "下划线")
                       .replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5_\\-]", "");
    if (senderName.isEmpty()) {
        senderName = senderUin;
    }

    aiProcessing = true;

    JSONArray ai2Tools = buildAI2Tools();
    JSONArray ai2Msgs = new JSONArray();

    // ctx: 只含历史（已是标准格式）
    for (int i = 0; i < ctx.size(); i++) {
        Map m = (Map) ctx.get(i);
        // 兼容旧 ctx：孤儿 tool 消息前补一个虚拟 assistant
        if ("tool".equals(m.get("role")) && m.get("tool_call_id") == null && m.get("content") != null) {
            JSONObject dc = new JSONObject();
            dc.put("role", "assistant");
            dc.put("content", "");
            dc.put("tool_calls", new JSONArray());
            ai2Msgs.put(dc);
        }
        JSONObject j = new JSONObject();
        j.put("role", m.get("role"));
        j.put("content", m.get("content"));
        if (m.get("name") != null) {
            j.put("name", m.get("name"));
        }
        if (m.get("tool_calls") != null) {
            j.put("tool_calls", m.get("tool_calls"));
        }
        if (m.get("tool_call_id") != null) {
            j.put("tool_call_id", m.get("tool_call_id"));
        }
        ai2Msgs.put(j);
    }
    // 确保 tool call/result 配对：移除孤儿消息防止 DeepSeek 400
    {
        JSONArray cleanMsgs = new JSONArray();
        boolean pendingToolCall = false;
        for (int ci = 0; ci < ai2Msgs.length(); ci++) {
            JSONObject cj = ai2Msgs.getJSONObject(ci);
            String cr = cj.optString("role", "");
            boolean hasTC = cj.has("tool_calls");
            boolean hasTCId = cj.has("tool_call_id");
            if ("tool".equals(cr) && hasTCId) {
                if (pendingToolCall) {
                    cleanMsgs.put(cj);
                    pendingToolCall = false;
                }
                // else: orphan tool, drop it
            } else if ("assistant".equals(cr) && hasTC) {
                cleanMsgs.put(cj);
                pendingToolCall = true;
            } else {
                cleanMsgs.put(cj);
                pendingToolCall = false;
            }
        }
        ai2Msgs = cleanMsgs;
    }

    // === 集中构建系统上下文（合并为一条消息） ===
    StringBuilder sysCtx = new StringBuilder();
    String persona = loadPersona();
    if (!persona.isEmpty()) { sysCtx.append(persona).append("\n\n"); }
    else { sysCtx.append("你是墨鸦,一个有长期记忆的 AI 助手。\n\n"); }
    String sysPrompt = loadSystemPrompt();
    if (!sysPrompt.isEmpty()) {
        sysCtx.append(sysPrompt).append("\n\n");
    }

    // 唤醒标记
    String lkey = peerUin + "_" + chatType;
    if (listenSessions != null && listenSessions.contains(lkey)) {
        sysCtx.append("<wake t=\"").append(getCurrentTime()).append("\" />\n");
    }
    // 公有记忆
    String pubStrata = buildPublicStrata();
    if (!pubStrata.isEmpty()) {
        sysCtx.append(pubStrata).append("\n");
    }
    // 私有记忆
    String privStrata = buildStrataContext(senderUin);
    if (!privStrata.isEmpty()) {
        sysCtx.append(privStrata).append("\n");
    }
    // 当前场景
    sysCtx.append(chatType == 2 ? "群聊 群号:" + peerUin : "私聊").append(" 时间:").append(getCurrentTime()).append("\n");
    if (!atInfo.isEmpty()) {
        sysCtx.append(atInfo).append("\n");
    }

    // 被引用者
    if (!quotedText.isEmpty()) {
        String quotedRole = getRole(quotedUin);
        String quotedName = getMemberName(chatType, peerUin, quotedUin);
        quotedName = quotedName.replaceAll("[<{＜【\\[（(].*?[>}＞】\\]）)]", "")
                               .replaceAll("[,，:：;；]", "").trim();
        if (quotedName.isEmpty()) {
            quotedName = quotedUin;
        }
        sysCtx.append("<t>").append(getCurrentTime()).append("</t><s><user uin=\"").append(quotedUin)
              .append("\" access=\"").append(quotedRole).append("\" display=\"").append(quotedName).append("\" /></s>\n");
        sysCtx.append("<t>").append(getCurrentTime()).append("</t><quote><quoter_uid>").append(quotedUin)
              .append("</quoter_uid><quote_content>").append(quotedText).append("</quote_content></quote>\n");
    }

    // 当前发言者身份
    sysCtx.append("<t>").append(getCurrentTime()).append("</t><s><user uin=\"").append(senderUin)
          .append("\" access=\"").append(userRole).append("\" display=\"").append(senderName).append("\" /></s>\n");

    // 技能清单
    sysCtx.append("<skills>\n");
    sysCtx.append("记忆：#M/#MP私有 #P/#PP公有。标签必打。用户透露信息时主动corax-mem-create。\n");
    sysCtx.append("搜索：corax-mem-tag(标签) corax-mem-search(关键词)。\n");
    sysCtx.append("联网：corax-search/corax-fetch，≤");
    int shellRounds = 8;
    try { shellRounds = Integer.parseInt(getAiConfig("shell_rounds")); } catch (Exception e) { }
    sysCtx.append(shellRounds).append("轮必须回复。\n");
    sysCtx.append("定时：sleep N && cmd > /dev/out &\n");
    sysCtx.append("</skills>");

    String ai2Prompt = sysCtx.toString();

    // 当前用户消息（纯净正文，name 只存 UIN）
    JSONObject usr = new JSONObject();
    usr.put("role", "user");
    usr.put("name", senderUin);
    String usrContent = "<t>" + getCurrentTime() + "</t>";
    if (!quotedMsgId.isEmpty()) {
        usrContent += "<refmsgid>" + quotedMsgId + "</refmsgid>";
    }
    usrContent += "<u>" + prompt + "</u>";
    usr.put("content", usrContent);
    ai2Msgs.put(usr);

    Map ai2Result = callAI("", ai2Prompt, ai2Msgs, 8192, ai2Tools);

    totalCalls++;
    String ai2Content = ""; JSONArray ai2TCs = null;
    if (ai2Result != null) {
        ai2Content = (String) ai2Result.getOrDefault("content", "");
        if (ai2Result.containsKey("tool_calls")) {
            ai2TCs = (JSONArray) ai2Result.get("tool_calls");
        }
        try { totalPt += Integer.parseInt(String.valueOf(ai2Result.get("prompt_tokens"))); } catch (Exception e) { }
        try { totalCt += Integer.parseInt(String.valueOf(ai2Result.get("completion_tokens"))); } catch (Exception e) { }
    } else { sendStyledHeader(msg, "ERROR", "AI 服务暂时不可用"); aiProcessing = false; return; }

    // ctx 顺序：先记录 user + R1，再处理工具循环（R2 助理回复在其后，保证历史顺序正确）
    addToContext(ctx, "system",
        "<t>" + getCurrentTime() + "</t><s><user uin=\"" + senderUin + "\" access=\"" + userRole + "\" display=\"" + senderName + "\" /></s>",
        null);
    addToContext(ctx, "user",
        "<t>" + getCurrentTime() + "</t><u>" + prompt + "</u>",
        senderUin);
    // 持久化本次 AI 调用：assistant 消息带 tool_calls（标准 OpenAI 格式）
    addToContextTC(ctx, "assistant", ai2Content, null, ai2TCs, null);

    if (debug) {
        String dbg = "R1 content: " + ai2Content;
        if (ai2TCs != null) {
            dbg = dbg + "\ntool_calls: " + ai2TCs.length() + "个";
            for (int i = 0; i < ai2TCs.length(); i++) {
                JSONObject tc = ai2TCs.getJSONObject(i);
                dbg = dbg + "\n  " + tc.getJSONObject("function").getString("name") + "(" + tc.getJSONObject("function").getString("arguments") + ")";
            }
        }
        sendDebug(peerUin, chatType, dbg);
    }

    boolean hasSentReply = false; boolean isFirstReply = true;

    if (!ai2Content.isEmpty()) {
        String[] segs = ai2Content.split("\\[SPLIT\\]");
        for (int si = 0; si < segs.length; si++) {
            String seg = segs[si].trim();
            if ("1".equals(getAiConfig("ai_prefix"))) {
                seg = "[AI] " + seg;
            }
            if (!seg.isEmpty()) {
                if (isFirstReply) {
                    if (msg.msgId != 0) {
                        sendReplyMsg(peerUin, msg.msgId, seg, chatType);
                    }
                    else {
                        sendMsg(peerUin, seg, chatType);
                    }
                    isFirstReply = false;
                } else sendMsg(peerUin, seg, chatType);
                hasSentReply = true;
                try { Thread.sleep(150); } catch (Exception ignored) { }
            }
        }
    }

    if (ai2TCs != null && ai2TCs.length() > 0) {
        // 注入 assistant+tool_calls，满足 tool 消息必须有前置 assistant 的要求
        JSONObject asstTC = new JSONObject();
        asstTC.put("role", "assistant");
        asstTC.put("content", ai2Content != null ? ai2Content : "");
        asstTC.put("tool_calls", ai2TCs);
        ai2Msgs.put(asstTC);

        List shellCalls = new ArrayList();
        for (int i = 0; i < ai2TCs.length(); i++) {
            JSONObject tc = ai2TCs.getJSONObject(i);
            String fn = tc.getJSONObject("function").getString("name");
            if (fn.equals("shell")) {
                String cmd = getToolArg(tc, "cmd");
                if (cmd.isEmpty()) {
                    continue;
                }
                Map qr = stripQuietFlag(cmd);
                cmd = (String) qr.get("cmd");

                String output = shellExecLine(cmd, senderUin, peerUin, chatType);
                if (output.isEmpty()) {
                    output = "[命令已执行，无输出]";
                }
                {
                    String tcid = tc.optString("id", "call_" + System.currentTimeMillis());
                    JSONObject sr = new JSONObject();
                    if (output.isEmpty()) {
                        sr.put("role", "tool");
                        sr.put("tool_call_id", tcid);
                        sr.put("content", "[命令已执行，无输出]");
                        ai2Msgs.put(sr);
                    } else {
                        sr.put("role", "tool");
                        sr.put("tool_call_id", tcid);
                        sr.put("content", "<shell_output>\n" + output + "\n</shell_output>\n基于以上 shell 输出继续处理。如需发消息给用户，必须用 > /dev/out 重定向。");
                        ai2Msgs.put(sr);
                        if (output.startsWith("[延时 ")) {
                            addToContext(ctx, "assistant", "好的，延时任务已创建", null);
                            hasSentReply = true;
                        } else {
                            addToContextTC(ctx, "tool", output, null, null, tcid);
                            shellCalls.add(output);
                        }
                    }
                }
            }
            else if (fn.equals("toggle_listen")) {
                boolean enable = getToolArg(tc, "enable").equals("true");
                String key = peerUin + "_" + chatType;
                if (enable) {
                    clearListenLog(peerUin, chatType);
                    addToList(pluginPath + "/config/listen_sessions.txt", key);
                    if (listenSessions != null) {
                        listenSessions.add(key);
                    }
                } else {
                    removeFromList(pluginPath + "/config/listen_sessions.txt", key);
                    if (listenSessions != null) {
                        listenSessions.remove(key);
                    }
                }
                Map ctxListen = new HashMap();
                ctxListen.put("role", "system");
                ctxListen.put("content", "<listen t=\"" + getCurrentTime() + "\">" + (enable ? "开启" : "关闭") + "</listen>");
                ctxListen.put("_ts", System.currentTimeMillis());
                ctx.add(ctxListen);
            }
        }

        int maxSr = 8;
        try { maxSr = Integer.parseInt(getAiConfig("shell_rounds")); } catch (Exception e) { }
        int sr = 0;

        while (!shellCalls.isEmpty()) {
            sr++;
            if (sr >= maxSr) {
                JSONObject forceMsg = new JSONObject();
                forceMsg.put("role", "system");
                forceMsg.put("content", "已达 shell 调用上限 (" + maxSr + "轮)。基于以上所有结果，现在必须直接回复用户。");
                ai2Msgs.put(forceMsg);
                shellCalls.clear();
                Map sr2 = callAI("", ai2Prompt, ai2Msgs, 8192, null); totalCalls++;
                if (sr2 != null) {
                    String r2c = (String) sr2.getOrDefault("content", "");
                    if (!r2c.isEmpty()) {
                        String[] segs = r2c.split("\\[SPLIT\\]");
                        for (int si = 0; si < segs.length; si++) {
                            String seg = segs[si].trim();
                            if ("1".equals(getAiConfig("ai_prefix"))) {
                                seg = "[AI] " + seg;
                            }
                            if (!seg.isEmpty()) {
                                if (isFirstReply) {
                                    if (msg.msgId != 0) {
                                        sendReplyMsg(peerUin, msg.msgId, seg, chatType);
                                    }
                                    isFirstReply = false;
                                }
                                else {
                                    sendMsg(peerUin, seg, chatType);
                                }
                                hasSentReply = true;
                                try { Thread.sleep(150); } catch (Exception ignored) { }
                            }
                        }
                        addToContext(ctx, "assistant", r2c, null);
                    }
                }
                break;
            }
            shellCalls.clear();
            Map sr2 = callAI("", ai2Prompt, ai2Msgs, 8192, ai2Tools); totalCalls++;
            if (sr2 != null) {
                try { totalPt += Integer.parseInt(String.valueOf(sr2.get("prompt_tokens"))); } catch (Exception e) { }
                try { totalCt += Integer.parseInt(String.valueOf(sr2.get("completion_tokens"))); } catch (Exception e) { }
                String r2c = (String) sr2.getOrDefault("content", "");
                if (!r2c.isEmpty()) {
                    String[] segs = r2c.split("\\[SPLIT\\]");
                    for (int si = 0; si < segs.length; si++) {
                        String seg = segs[si].trim();
                        if ("1".equals(getAiConfig("ai_prefix"))) {
                            seg = "[AI] " + seg;
                        }
                        if (!seg.isEmpty()) {
                            if (isFirstReply) {
                                if (msg.msgId != 0) {
                                    sendReplyMsg(peerUin, msg.msgId, seg, chatType);
                                }
                                isFirstReply = false;
                            }
                            else {
                                sendMsg(peerUin, seg, chatType);
                            }
                            hasSentReply = true;
                            try { Thread.sleep(150); } catch (Exception ignored) { }
                        }
                    }
                } else if (!hasSentReply) {
                    // AI 返回空内容，静默跳过（shell 模式下正常）
                    hasSentReply = true;
                }
                JSONArray sr2tc = null;
                if (sr2.containsKey("tool_calls")) {
                    sr2tc = (JSONArray) sr2.get("tool_calls");
                    JSONObject asstTC2 = new JSONObject();
                    asstTC2.put("role", "assistant");
                    asstTC2.put("content", r2c != null ? r2c : "");
                    asstTC2.put("tool_calls", sr2tc);
                    ai2Msgs.put(asstTC2);
                }
                if (!r2c.isEmpty() || sr2tc != null) {
                    addToContextTC(ctx, "assistant", r2c, null, sr2tc, null);
                }
                if (sr2tc != null) for (int i = 0; i < sr2tc.length(); i++) {
                    JSONObject rtc = sr2tc.getJSONObject(i);
                    String rfn = rtc.getJSONObject("function").getString("name");
                    if (rfn.equals("shell")) {
                        String scmd = getToolArg(rtc, "cmd");
                        if (!scmd.isEmpty()) {
                            Map qr2 = stripQuietFlag(scmd);
                            scmd = (String) qr2.get("cmd");
                            String out = shellExecLine(scmd, senderUin, peerUin, chatType);
                            if (out.isEmpty()) {
                                out = "[命令已执行，无输出]";
                            }
                            {
                                String rtcid = rtc.optString("id", "rcall_" + System.currentTimeMillis());
                                JSONObject srm = new JSONObject();
                                srm.put("role", "tool");
                                srm.put("tool_call_id", rtcid);
                                srm.put("content", "<shell_output>\n" + out + "\n</shell_output>\n继续基于以上输出处理。如果需要发送消息给用户，必须使用 > /dev/out 重定向。");
                                ai2Msgs.put(srm);
                                if (!out.startsWith("[延时 ")) {
                                    addToContextTC(ctx, "tool", out, null, null, rtcid);
                                    shellCalls.add(out);
                                }
                            }
                        }
                    }
                    else {
                        executeMemoryCall(rtc, rfn, senderUin, userRole, peerUin, chatType, String.valueOf(msg.msgId), prompt, getMsgTimeMs(msg));
                    }
                }
            } else break;
        }
    }

    if (!hasSentReply) {
        ; // 静默，shell 模式下正常
    }
    StringBuilder finalMsg = new StringBuilder();
    if ("1".equals(getAiConfig("show_stats"))) {
        long elapsed = (System.currentTimeMillis() - startTime) / 1000;
        if (elapsed < 1) {
            elapsed = 1;
        }
        finalMsg.append("--\nTime:").append(getCurrentTime()).append("\nUser:").append(senderUin).append("(").append(userRole).append(")");
        if (totalPt > 0) {
            finalMsg.append("\nTokenIn:").append(totalPt);
        }
        if (totalCt > 0) {
            finalMsg.append("\nTokenOut:").append(totalCt);
        }
        finalMsg.append("\nThinkTime:").append(elapsed).append("s\nAIcalls:").append(totalCalls);
    }
    if (finalMsg.length() > 0) {
        sendMsg(peerUin, finalMsg.toString(), chatType);
    }

    // v3.0: 精简 ctx 存储（带注解格式）
    String sceneTag = chatType == 2 ? "[群:" + peerUin + "]" : "[私聊]";
    // v3.0: ctx 落盘
    saveCtxToDisk(peerUin, chatType);
    writeLog(senderUin, "/ai: " + trimmed);
    if (hasSentReply && !ai2Content.isEmpty()) {
        lastAssistantMsg = ai2Content.trim();
    }
    aiProcessing = false;
    processQueue();
}

void processQueue() {
    while (!msgQueue.isEmpty() && !aiProcessing) {
        Object next = msgQueue.poll();
        if (next != null) {
            try { onMsg(next); } catch (Exception e) { this.log("error.txt", "processQueue: " + e.getMessage()); }
        }
    }
}

// ==================== AI 配置 ====================
Map loadAiConfig() {
    long now = System.currentTimeMillis();
    if (aiConfigCache != null && (now - aiConfigCacheTime) < AI_CONFIG_CACHE_MS) {
        return aiConfigCache;
    }
    Map cfg = new LinkedHashMap();
    cfg.put("api_key", "");
    cfg.put("model", "deepseek-v4-flash");
    cfg.put("context_ttl", "0");
    cfg.put("context_limit", "60");
    cfg.put("ai_url", "https://api.deepseek.com");
    cfg.put("search_provider", "tavily");
    cfg.put("search_api_key", "");
    cfg.put("show_stats", "0");
    cfg.put("debug", "0");
    cfg.put("temperature", "0.7");
    cfg.put("pat_wake", "1");
    cfg.put("ai_prefix", "1");
    cfg.put("shell_rounds", "8");
    // 兼容旧配置 search_rounds
    if (cfg.containsKey("search_rounds") && !cfg.containsKey("shell_rounds")) {
        cfg.put("shell_rounds", cfg.get("search_rounds"));
        cfg.remove("search_rounds");
    }
    cfg.put("sewarden", "1");

    File f = new File(pluginPath + "/config/ai_config.txt");
    if (f.exists()) {
        try {
            BufferedReader br = new BufferedReader(new FileReader(f));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int eq = line.indexOf("=");
                if (eq > 0) {
                    cfg.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
                }
            }
            br.close();
        } catch (Exception e) { this.log("error.txt", "loadAiConfig: " + e.getMessage()); }
    }
    aiConfigCache = cfg;
    aiConfigCacheTime = now;
    return cfg;
}

void saveAiConfig(Map cfg) {
    try {
        File parent = new File(pluginPath + "/config");
        if (!parent.exists()) {
            parent.mkdirs();
        }
        PrintWriter pw = new PrintWriter(new FileWriter(pluginPath + "/config/ai_config.txt"));
        pw.println("# 墨鸦 Strata config");
        pw.println();
        for (Object entry : cfg.entrySet()) {
            Map.Entry e = (Map.Entry) entry;
            pw.println(e.getKey() + "=" + e.getValue());
        }
        pw.close();
        aiConfigCache = null; aiConfigCacheTime = 0;
    } catch (Exception e) { this.log("error.txt", "saveAiConfig: " + e.getMessage()); }
}

String getAiConfig(String key) { Map cfg = loadAiConfig(); Object v = cfg.get(key); return v != null ? v.toString() : ""; }

String resolveAiCfg(Map cfg, String prefixedKey, String fallbackKey) {
    String v = (String) cfg.get(prefixedKey);
    if (v != null && !v.isEmpty()) {
        return v;
    }
    v = (String) cfg.get(fallbackKey);
    return v != null ? v : "";
}

// ==================== CAST ====================
String getRole(String uin) {
    if (uin.equals(myUin)) {
        return "OWNER";
    }
    Set admins = readStringSet(pluginPath + "/config/admins.txt");
    if (admins.contains(uin)) {
        return "ADMIN";
    }
    Set blocked = readStringSet(pluginPath + "/config/blocked.txt");
    if (blocked.contains(uin)) {
        return "BLOCKED";
    }
    return "MEMBER";
}

Set readStringSet(String path) {
    Set set = new HashSet();
    File f = new File(path);
    if (!f.exists()) {
        return set;
    }
    try {
        BufferedReader br = new BufferedReader(new FileReader(f));
        String line;
        while ((line = br.readLine()) != null) {
            line = line.trim();
            if (!line.isEmpty()) {
                set.add(line);
            }
        }
        br.close();
    } catch (Exception e) { this.log("error.txt", "readStringSet: " + e.getMessage()); }
    return set;
}

void writeStringSet(String path, Set set) {
    File f = new File(path);
    File parent = f.getParentFile();
    if (parent != null && !parent.exists()) {
        parent.mkdirs();
    }
    try {
        BufferedWriter bw = new BufferedWriter(new FileWriter(f));
        for (Object s : set) {
            bw.write(s + "\n");
        }
        bw.flush(); bw.close();
    } catch (Exception e) { this.log("error.txt", "writeStringSet: " + e.getMessage()); }
}

void addToList(String path, String uin) { Set set = readStringSet(path); if (!set.contains(uin)) { set.add(uin); writeStringSet(path, set); } }
void removeFromList(String path, String uin) { Set set = readStringSet(path); if (set.remove(uin)) writeStringSet(path, set); }

// SEWarden: 清洗用户消息中的系统标签，防止标签逃逸
String sewardenClean(String text) {
    if (!"1".equals(getAiConfig("sewarden"))) {
        return text;
    }
    return text.replace("</u>", "〈/u〉")
               .replace("<u>", "〈u〉")
               .replace("</s>", "〈/s〉")
               .replace("<s>", "〈s〉")
               .replace("<user", "〈user")
               .replace("</quote", "〈/quote")
               .replace("<quote", "〈quote")
               .replace("<listen", "〈listen")
               .replace("<wake", "〈wake")
               .replace("<skill", "〈skill")
               .replace("<memop", "〈memop")
               .replace("<tagresult", "〈tagresult")
               .replace("<searchresult", "〈searchresult")
               .replace("<search", "〈search")
               .replace("<pinned", "〈pinned")
               .replace("<archive", "〈archive")
               .replace("<coldtags", "〈coldtags")
               .replace("<public_pinned", "〈public_pinned")
               .replace("<public_archive", "〈public_archive")
               .replace("<public_coldtags", "〈public_coldtags")
               .replace("<t>", "〈t〉")
               .replace("</t>", "〈/t〉")
               .replace("<warn", "〈warn")
               .replace("<skills", "〈skills")
               .replace("<refmsgid", "〈refmsgid")
               .replace("<cmd_result", "〈cmd_result")
               .replace("<shell_output", "〈shell_output")
               .replace("<reminder", "〈reminder")
               .replace("<debug", "〈debug");
}

// ==================== 日志 ====================
String getCurrentTime() { return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()); }

String relativeTime(long timestamp) {
    long diff = System.currentTimeMillis() - timestamp;
    if (diff < 60000) {
        return "刚刚";
    }
    if (diff < 3600000) {
        return (diff / 60000) + "分钟前";
    }
    if (diff < 86400000) {
        return (diff / 3600000) + "小时前";
    }
    return (diff / 86400000) + "天前";
}

long getAccessedAt(long id) {
    Cursor c = null;
    try {
        c = getDb().rawQuery("SELECT accessed_at FROM memories WHERE id=?", new String[]{String.valueOf(id)});
        if (c.moveToFirst()) {
            return c.getLong(0);
        }
    } catch (Exception e) { }
    finally { if (c != null) c.close(); }
    return System.currentTimeMillis();
}

String getMemberName(int chatType, String peerUin, String uin) {
    // 系统消息保护：UIN 无效时直接返回
    if (uin == null || uin.isEmpty() || uin.equals("0") || uin.equals("null")) {
        return "System";
    }
    if (chatType == 2) {
        try {
            Object mem = getMemberInfo(peerUin, uin);
            if (mem != null && mem.uinName != null) {
                return mem.uinName;
            }
        } catch (NullPointerException e) { }
        catch (Exception e) { }
    } else if (chatType == 1) {
        try {
            java.util.List list = getAllFriend();
            if (list != null) {
                for (int i = 0; i < list.size(); i++) {
                    Object f = list.get(i);
                    try {
                        java.lang.reflect.Field fu = f.getClass().getField("uin");
                        if (String.valueOf(fu.get(f)).equals(uin)) {
                            java.lang.reflect.Field fr = f.getClass().getField("remark");
                            String remark = (String) fr.get(f);
                            if (remark != null && remark.length() > 0) {
                                return remark;
                            }
                            java.lang.reflect.Field fn = f.getClass().getField("name");
                            return (String) fn.get(f);
                        }
                    } catch (Exception e2) { }
                }
            }
        } catch (Exception e) { }
    }
    return uin;
}

void writeLog(String senderUin, String command) {
    String role = getRole(senderUin);
    String logPath = pluginPath + "/config/log.txt";
    try {
        File logFile = new File(logPath);
        if (logFile.exists() && logFile.length() > 10 * 1024 * 1024) {
            logFile.renameTo(new File(logPath + "." + System.currentTimeMillis()));
        }
        if (!logFile.exists()) {
            logFile.getParentFile().mkdirs();
            logFile.createNewFile();
        }
        BufferedWriter bw = new BufferedWriter(new FileWriter(logFile, true));
        bw.write("[" + getCurrentTime() + "] [" + role + "] " + senderUin + " " + command);
        bw.newLine(); bw.flush(); bw.close();
    } catch (Exception e) { this.log("error.txt", "writeLog: " + e.getMessage()); }
}

// ==================== 切槽 ====================
String getActivePersona() {
    File f = new File(pluginPath + "/config/active_prompt.txt");
    if (!f.exists()) {
        return "default";
    }
    try {
        BufferedReader br = new BufferedReader(new FileReader(f));
        String s = br.readLine();
        br.close();
        if (s != null && !s.trim().isEmpty()) {
            return s.trim();
        }
    } catch (Exception e) { }
    return "default";
}

void setActivePersona(String name) {
    try {
        PrintWriter pw = new PrintWriter(new FileWriter(pluginPath + "/config/active_prompt.txt"));
        pw.println(name); pw.close();
        cachedPersona = null; personaFileMtime = 0;
        cachedWakeWords = null; wakeWordsFileMtime = 0;
    } catch (Exception e) { }
}

List listPersonas() {
    List names = new ArrayList();
    File dir = new File(pluginPath + "/config/prompt");
    if (!dir.exists() || !dir.isDirectory()) {
        return names;
    }
    File[] files = dir.listFiles(new FilenameFilter() { public boolean accept(File d, String name) { return name.endsWith(".prompt.txt"); } });
    if (files != null) { for (int i = 0; i < files.length; i++) { String n = files[i].getName(); names.add(n.substring(0, n.length() - ".prompt.txt".length())); } }
    return names;
}

void handleReboot(Object msg, String trimmed) {
    if (!requireAdminOrOwner(msg)) {
        return;
    }
    String[] rp = trimmed.split("\\s+", 2);
    if (rp.length == 1) {
        List personas = listPersonas();
        String cur = getActivePersona();
        StringBuilder sb = new StringBuilder();
        sb.append("[当前人设] ").append(cur).append("\n[可选人设] ");
        if (personas.isEmpty()) { sb.append("(无)"); }
        else { sb.append(personas.size()).append(" 个:\n"); for (int i = 0; i < personas.size(); i++) { String p = (String) personas.get(i); sb.append("  ").append(p); if (p.equals(cur)) sb.append(" ←"); sb.append("\n"); } }
        sendStyledHeader(msg, "INFO", sb.toString());
    } else {
        String target = rp[1].trim();
        File tf = new File(pluginPath + "/config/prompt/" + target + ".prompt.txt");
        if (!tf.exists()) {
            sendStyledHeader(msg, "ERROR", "人设 \"" + target + "\" 不存在");
            return;
        }
        setActivePersona(target);
        sendStyledHeader(msg, "SUCCESS", "已切换至: " + target + "\n上下文已保留，新人设已注入。");
    }
    aiProcessing = false;
}

void handleDebug(Object msg, String trimmed) {
    if (!requireAdminOrOwner(msg)) {
        return;
    }
    String[] dp = trimmed.split("\\s+");
    if (dp.length == 1) { sendStyledHeader(msg, "INFO", "debug = " + getAiConfig("debug")); }
    else if (dp[1].equals("0") || dp[1].equals("1")) {
        Map cfg = loadAiConfig();
        cfg.put("debug", dp[1]);
        saveAiConfig(cfg);
        sendStyledHeader(msg, "INFO", "debug = " + dp[1]);
    }
    else { sendStyledHeader(msg, "ERROR", "用法: /ai debug 0/1"); }
}

void handleOperationApproval(Object msg, boolean permit) {
    String auin = String.valueOf(msg.userUin);
    String arole = getRole(auin);
    if (!arole.equals("ADMIN") && !arole.equals("OWNER")) {
        sendPermissionDenied(msg);
        return;
    }
    String apu = String.valueOf(msg.peerUin);
    int act = msg.type;
    String akey = apu + "_" + act;
    Map aop = (Map) pendingApprovals.get(akey);
    if (aop == null) {
        sendStyledHeader(msg, "INFO", "没有待审批的操作");
        return;
    }
    // 取消超时定时器
    Timer atm = (Timer) aop.get("timer");
    if (atm != null) {
        try { atm.cancel(); } catch (Exception e) { }
        aop.put("timer", null);
    }
    // 设结果并唤醒阻塞的 corax-snapshot-rm
    aop.put("result", permit ? "permit" : "reject");
    Object lock = aop.get("lock");
    if (lock != null) {
        synchronized (lock) {
            lock.notifyAll();
        }
    }
    // 兜底：如果 lock.wait 不在等（异常情况），直接注入 ctx
    String adesc = (String) aop.get("desc");
    if (permit) {
        injectApprovalResult(apu, act, "删除快照 " + adesc + " 已被批准并执行");
    } else {
        injectApprovalResult(apu, act, "删除快照 " + adesc + " 已被拒绝");
    }
    sendStyledHeader(msg, "INFO", permit ? "已批准" : "已删除");
}

// ==================== 联网搜索 ====================
String doWebSearch(String query) {
    Map cfg = loadAiConfig();
    String provider = (String) cfg.get("search_provider");
    if ("bocha".equals(provider)) {
        return bochaSearch(query);
    }
    if ("bing".equals(provider)) {
        return bingSearch(query);
    }
    return tavilySearch(query);
}

String bingSearch(String query) {
    Map cfg = loadAiConfig();
    String apiKey = (String) cfg.get("search_api_key");
    if (apiKey == null || apiKey.isEmpty()) {
        return "[搜索失败: 未配置 search_api_key]";
    }
    HttpURLConnection conn = null;
    try {
        URL url = new URL("https://api.bing.microsoft.com/v7.0/search?q=" + URLEncoder.encode(query, "UTF-8") + "&count=8&mkt=zh-CN");
        conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Ocp-Apim-Subscription-Key", apiKey);
        conn.setConnectTimeout(10000); conn.setReadTimeout(15000);
        conn.connect();
        if (conn.getResponseCode() != 200) {
            return "[搜索失败: HTTP " + conn.getResponseCode() + "]";
        }
        BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
        StringBuilder resp = new StringBuilder(); String line;
        while ((line = br.readLine()) != null) resp.append(line);
        br.close();
        JSONObject jResp = new JSONObject(resp.toString());
        JSONArray results = jResp.has("webPages") ? jResp.getJSONObject("webPages").getJSONArray("value") : null;
        if (results == null || results.length() == 0) {
            return "[搜索无结果]";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < Math.min(results.length(), 8); i++) out.append(i + 1).append(". ").append(results.getJSONObject(i).optString("snippet", "")).append("\n");
        return out.toString().trim();
    } catch (Exception e) { return "[搜索异常: " + e.getMessage() + "]"; }
    finally { if (conn != null) conn.disconnect(); }
}

String bochaSearch(String query) {
    Map cfg = loadAiConfig();
    String apiKey = (String) cfg.get("search_api_key");
    if (apiKey == null || apiKey.isEmpty()) {
        return "[搜索失败: 未配置 search_api_key]";
    }
    HttpURLConnection conn = null;
    try {
        URL url = new URL("https://api.bochaai.com/v1/web-search");
        conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000); conn.setReadTimeout(15000);
        JSONObject reqBody = new JSONObject();
        reqBody.put("query", query); reqBody.put("count", 8); reqBody.put("summary", true);
        byte[] postData = reqBody.toString().getBytes("UTF-8");
        OutputStream os = conn.getOutputStream(); os.write(postData); os.flush(); os.close();
        if (conn.getResponseCode() != 200) {
            return "[搜索失败: HTTP " + conn.getResponseCode() + "]";
        }
        BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
        StringBuilder resp = new StringBuilder(); String line;
        while ((line = br.readLine()) != null) resp.append(line);
        br.close();
        JSONObject jResp = new JSONObject(resp.toString());
        if (jResp.has("data")) {
            jResp = jResp.getJSONObject("data");
        }
        JSONArray results = jResp.has("webPages") ? jResp.getJSONObject("webPages").getJSONArray("value") : null;
        if (results == null || results.length() == 0) {
            return "[搜索无结果]";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < Math.min(results.length(), 8); i++) {
            JSONObject r = results.getJSONObject(i);
            String summary = r.optString("summary", "");
            if (!summary.isEmpty()) {
                if (summary.length() > 300) {
                    summary = summary.substring(0, 300) + "...";
                }
                out.append(i + 1).append(". ").append(summary);
            }
            else {
                out.append(i + 1).append(". ").append(r.optString("snippet", ""));
            }
            out.append("\n");
        }
        return out.toString().trim();
    } catch (Exception e) { return "[搜索异常: " + e.getMessage() + "]"; }
    finally { if (conn != null) conn.disconnect(); }
}

String tavilySearch(String query) {
    Map cfg = loadAiConfig();
    String apiKey = (String) cfg.get("search_api_key");
    if (apiKey == null || apiKey.isEmpty()) {
        return "[搜索失败: 未配置 search_api_key]";
    }
    HttpURLConnection conn = null;
    try {
        URL url = new URL("https://api.tavily.com/search");
        conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000); conn.setReadTimeout(15000);
        JSONObject reqBody = new JSONObject();
        reqBody.put("api_key", apiKey);
        reqBody.put("query", query);
        reqBody.put("search_depth", "advanced");
        reqBody.put("max_results", 5);
        byte[] postData = reqBody.toString().getBytes("UTF-8");
        OutputStream os = conn.getOutputStream(); os.write(postData); os.flush(); os.close();
        if (conn.getResponseCode() != 200) {
            return "[搜索失败: HTTP " + conn.getResponseCode() + "]";
        }
        BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
        StringBuilder resp = new StringBuilder(); String line;
        while ((line = br.readLine()) != null) resp.append(line);
        br.close();
        JSONObject jResp = new JSONObject(resp.toString());
        JSONArray results = jResp.optJSONArray("results");
        if (results == null || results.length() == 0) {
            return "[搜索无结果]";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < results.length(); i++) {
            JSONObject r = results.getJSONObject(i);
            String title = r.optString("title", "");
            String snippet = r.optString("content", "");
            if (snippet.length() > 300) {
                snippet = snippet.substring(0, 300) + "...";
            }
            out.append(i + 1).append(". ");
            if (!title.isEmpty()) {
                out.append(title).append("\n   ");
            }
            out.append(snippet).append("\n");
        }
        return out.toString().trim();
    } catch (Exception e) { return "[搜索异常: " + e.getMessage() + "]"; }
    finally { if (conn != null) conn.disconnect(); }
}

// Tavily Extract：使用 Tavily 的网页内容提取 API，获取干净的文本内容
// 支持一次传入多个 URL（批量抓取），extract_depth=advanced 提取更完整正文
String tavilyExtract(String[] urls, int maxLen) {
    Map cfg = loadAiConfig();
    String apiKey = (String) cfg.get("search_api_key");
    if (apiKey == null || apiKey.isEmpty()) {
        return "[抓取失败: 未配置 search_api_key]";
    }
    HttpURLConnection conn = null;
    try {
        URL url = new URL("https://api.tavily.com/extract");
        conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000); conn.setReadTimeout(20000);
        JSONObject reqBody = new JSONObject();
        reqBody.put("api_key", apiKey);
        reqBody.put("urls", new JSONArray(urls));
        reqBody.put("extract_depth", "advanced");
        reqBody.put("include_images", false);
        byte[] postData = reqBody.toString().getBytes("UTF-8");
        OutputStream os = conn.getOutputStream(); os.write(postData); os.flush(); os.close();
        if (conn.getResponseCode() != 200) {
            return "[抓取失败: HTTP " + conn.getResponseCode() + "]";
        }
        BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
        StringBuilder resp = new StringBuilder(); String line;
        while ((line = br.readLine()) != null) resp.append(line);
        br.close();
        JSONObject jResp = new JSONObject(resp.toString());
        JSONArray results = jResp.optJSONArray("results");
        if (results == null || results.length() == 0) {
            String detail = jResp.optString("detail", "");
            return "[抓取失败: " + (detail.isEmpty() ? "无结果" : detail) + "]";
        }
        boolean multi = results.length() > 1;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < results.length(); i++) {
            JSONObject r = results.getJSONObject(i);
            String raw = r.optString("raw_content", "");
            if (raw.isEmpty()) {
                continue;
            }
            if (multi) {
                out.append("【").append(r.optString("url", "")).append("】\n");
            }
            out.append(raw).append("\n");
        }
        if (out.length() == 0) {
            return "[抓取失败: 内容为空]";
        }
        String result = out.toString().trim();
        if (result.length() > maxLen) {
            result = result.substring(0, maxLen);
        }
        return result;
    } catch (Exception e) { return "[抓取异常: " + e.getMessage() + "]"; }
    finally { if (conn != null) conn.disconnect(); }
}

// fetch_page 路由：tavily 时走 Extract API（支持批量），失败降级原始抓取
String doFetchPage(String urlStr) {
    String[] urls = urlStr.trim().split("[\\s,]+");
    if (urls.length > 5) {
        urls = Arrays.copyOfRange(urls, 0, 5); // 单次最多抓取 5 个 URL;
    }
    String provider = (String) loadAiConfig().get("search_provider");
    if ("tavily".equals(provider)) {
        String result = tavilyExtract(urls, 6000);
        if (!result.startsWith("[")) {
            return result;
        }
        // Tavily Extract 失败，降级到原始 HTTP 抓取
    }
    boolean multi = urls.length > 1;
    int budget = multi ? 6000 / urls.length : 6000;
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < urls.length; i++) {
        if (urls[i].isEmpty()) {
            continue;
        }
        String c = fetchWebContentSimple(urls[i], budget);
        if (multi) {
            out.append("【").append(urls[i]).append("】\n");
        }
        out.append(c).append("\n");
    }
    return out.toString().trim();
}

String fetchWebContentSimple(String urlStr, int maxLen) {
    HttpURLConnection conn = null;
    try {
        URL url = new URL(urlStr);
        conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(8000); conn.setReadTimeout(8000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        conn.connect();
        if (conn.getResponseCode() != 200) {
            return "[抓取失败]";
        }
        BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        int total = 0;
        while ((line = br.readLine()) != null && total < maxLen) {
            String t = line.trim();
            if (t.length() == 0) {
                continue;
            }
            sb.append(t).append("\n");
            total += t.length();
        }
        br.close();
        String result = sb.toString().trim();
        if (result.length() > maxLen) {
            result = result.substring(0, maxLen);
        }
        return result;
    } catch (Exception e) { return "[抓取异常]"; }
    finally { if (conn != null) conn.disconnect(); }
}

// ==================== Corax-Shell VFS ====================
// 虚拟文件系统核心 — 路径式能力暴露

// VFS 读入口
String vfsRead(String path, String senderUin, String peerUin, int chatType) {
    path = vfsNorm(path);
    // /proc/sys/ — 系统属性
    if (path.startsWith("/proc/sys/")) {
        return vfsReadProcSys(path);
    }
    // /proc/self/ — 当前会话
    if (path.startsWith("/proc/self/")) {
        return vfsReadProcSelf(path, senderUin, chatType, peerUin);
    }
    // /proc/ps,free,uptime — 系统状态
    if (path.startsWith("/proc/ps")) {
        return vfsReadProcPS();
    }
    if (path.equals("/proc/free")) {
        return vfsReadProcFree();
    }
    if (path.equals("/proc/uptime")) {
        return vfsReadProcUptime();
    }
    if (path.startsWith("/proc/") && path.contains("/status")) {
        return vfsReadProcStatus(path);
    }
    if (path.startsWith("/proc/") && path.contains("/cmd")) {
        Map job = (Map) delayJobs.get(Integer.parseInt(path.replace("/proc/", "").replace("/cmd", "").trim()));
        return job != null ? String.valueOf(job.get("cmd")) : "[pid 不存在]";
    }
    if (path.startsWith("/proc/") && path.contains("/stdout")) {
        return vfsReadProcStdout(path);
    }
    // /proc/prompt/
    if (path.startsWith("/proc/prompt/")) {
        return vfsReadProcPrompt(path);
    }
    // /etc/
    if (path.startsWith("/etc/")) {
        return vfsReadEtc(path);
    }
    // /ctx/
    if (path.startsWith("/ctx/")) {
        return vfsReadCtx(path);
    }
    // /var/
    if (path.equals("/var/data.db")) {
        return "[SQLite: /var/data.db — 使用 sqlite3 查询]";
    }
    if (path.startsWith("/var/log/")) {
        return vfsReadVarLog(path);
    }
    // /dev/
    if (path.startsWith("/dev/")) {
        return vfsReadDev(path, peerUin, chatType);
    }
    // /persist/
    if (path.startsWith("/persist/")) {
        return vfsReadPersist(path);
    }
    // /src/
    if (path.startsWith("/src/")) {
        return "[拒绝: 源码不可访问]";
    }
    // /tmp/
    if (path.startsWith("/tmp/")) {
        return vfsReadTmp(path);
    }
    // directories
    if (path.equals("/bin/")) {
        return "touch rm mkdir chmod find sort uniq cut sed corax-edit corax-mem-create corax-mem-rm corax-mem-tag corax-mem-search corax-search corax-fetch corax-listen corax-reboot corax-snapshot-list corax-snapshot-restore corax-snapshot-rm stat corax-help";
    }
    if (path.equals("/")) {
        return "bin/  proc/  etc/  dev/  ctx/  var/  src/  tmp/  persist/  usr/";
    }
    if (path.equals("/proc/")) {
        return "sys/  self/  prompt/  ps  free  uptime";
    }
    if (path.equals("/etc/")) {
        return "admins.txt  blocked.txt  members.txt  enabled_conversations.txt  listen_sessions.txt  default_account.txt  prompt/  skills/";
    }
    return "[路径不存在: " + path + "]";
}

// VFS 写入口 — 返回 null 成功, 否则返回错误信息
String vfsWrite(String path, String content, boolean append, String senderUin, String peerUin, int chatType) {
    path = vfsNorm(path);
    if (path.startsWith("/proc/sys/")) {
        return vfsWriteProcSys(path, content);
    }
    if (path.startsWith("/proc/prompt/active")) {
        return vfsWritePromptActive(content);
    }
    if (path.startsWith("/etc/")) {
        return vfsWriteEtc(path, content, append);
    }
    if (path.equals("/dev/out")) {
        vfsWriteDevOut(content, peerUin, chatType);
        return null;
    }
    if (path.equals("/dev/exit")) {
        vfsWriteDevExit(content);
        return null;
    }
    if (path.startsWith("/persist/")) {
        return vfsWritePersist(path, content, append);
    }
    if (path.startsWith("/tmp/")) {
        return vfsWriteTmp(path, content, append);
    }
    if (path.startsWith("/proc/") && path.contains("/kill")) {
        return vfsWriteProcKill(path);
    }
    if (path.startsWith("/var/data.db")) {
        return vfsWriteVarDb(content);
    }
    return "[只读或不存在: " + path + "]";
}

// 路径规范化
String vfsNorm(String p) {
    if (p == null) {
        return "/";
    }
    p = p.trim();
    while (p.contains("//")) {
        p = p.replace("//", "/");
    }
    while (p.contains("/./")) {
        p = p.replace("/./", "/");
    }
    // 解析 /../ 路径穿越
    while (p.contains("/../")) {
        int idx = p.indexOf("/../");
        if (idx == 0) {
            p = p.substring(3);
        } else {
            int prev = p.lastIndexOf("/", idx - 1);
            if (prev < 0) {
                prev = 0;
            }
            p = p.substring(0, prev) + p.substring(idx + 3);
        }
    }
    while (p.endsWith("/..")) {
        p = p.substring(0, p.length() - 3);
        int lastSlash = p.lastIndexOf("/");
        if (lastSlash >= 0) {
            p = p.substring(0, lastSlash);
        } else {
            p = "/";
        }
    }
    if (!p.startsWith("/")) {
        p = "/" + p;
    }
    if (p.isEmpty()) {
        p = "/";
    }
    return p;
}

// ======= /proc/sys/ =======
String vfsReadProcSys(String path) {
    Map cfg = loadAiConfig();
    String key = path.replace("/proc/sys/", "");
    if (key.equals("api_key") || key.equals("search_api_key")) {
        String v = (String) cfg.get(key);
        if (v == null || v.isEmpty()) {
            return "(未设置)";
        }
        if (v.length() > 8) {
            v = v.substring(0, 4) + "****" + v.substring(v.length() - 4);
        }
        return v;
    }
    String v = getAiConfig(key);
    return v.isEmpty() ? "(未设置)" : v;
}
String vfsWriteProcSys(String path, String content) {
    String key = path.replace("/proc/sys/", "");
    if (key.equals("api_key") || key.equals("search_api_key")) {
        return "[拒绝: api_key/search_api_key 不可覆写]";
    }
    String[] vk = {"model","ai_url","context_ttl","context_limit","search_provider","show_stats","debug","ai_prefix","shell_rounds","temperature","pat_wake","sewarden"};
    boolean valid = false; for (int i = 0; i < vk.length; i++) if (vk[i].equals(key)) { valid = true; break; }
    if (!valid) {
        return "[无效配置键: " + key + "]";
    }
    aiConfigCache = null;
    String fullErr = snapCheckFull(path);
    if (fullErr != null) {
        return fullErr;
    }
    takeSnapshot(path);
    Map cfg = loadAiConfig(); cfg.put(key, content.trim()); saveAiConfig(cfg);
    return null;
}

// ======= /proc/self/ =======
String vfsReadProcSelf(String path, String senderUin, int chatType, String peerUin) {
    if (path.equals("/proc/self/role")) {
        return getRole(senderUin);
    }
    if (path.equals("/proc/self/memory_count")) {
        return String.valueOf(getMemoryCount(senderUin));
    }
    if (path.equals("/proc/self/chat")) {
        return peerUin + "_" + chatType;
    }
    if (path.equals("/proc/self/listening")) {
        if (listenSessions == null) {
            listenSessions = readStringSet(pluginPath + "/config/listen_sessions.txt");
        }
        return listenSessions.contains(peerUin + "_" + chatType) ? "yes" : "no";
    }
    return "[未知: " + path + "]";
}

// ======= /proc/prompt/ =======
String vfsReadProcPrompt(String path) {
    if (path.equals("/proc/prompt/active")) {
        return getActivePersona();
    }
    if (path.equals("/proc/prompt/slots")) {
        List personas = listPersonas();
        StringBuilder sb = new StringBuilder();
        String cur = getActivePersona();
        for (int i = 0; i < personas.size(); i++) {
            String p = (String) personas.get(i);
            sb.append(p);
            if (p.equals(cur)) {
                sb.append(" [active]");
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }
    return "[未知: " + path + "]";
}
String vfsWritePromptActive(String content) {
    String target = content.trim();
    List personas = listPersonas();
    boolean found = false;
    for (int i = 0; i < personas.size(); i++) {
        if (personas.get(i).equals(target)) {
            found = true;
            break;
        }
    }
    if (!found) {
        return "[人设不存在: " + target + "]";
    }
    if (target.equals(getActivePersona())) {
        return null;
    }
    setActivePersona(target);
    return null;
}

// ======= /etc/ =======
String vfsMapEtcPath(String path) {
    String file = path.substring(5); // strip "/etc/"
    if (file.startsWith("prompt/")) {
        return pluginPath + "/config/" + file;
    }
    if (file.startsWith("skills/")) {
        return pluginPath + "/config/" + file;
    }
    return pluginPath + "/config/" + file;
}
String vfsReadEtc(String path) {
    String real = vfsMapEtcPath(path);
    if (new File(real).isDirectory()) {
        String[] files = new File(real).list();
        if (files == null) {
            return "(空)";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < files.length; i++) sb.append(files[i]).append("\n");
        return sb.toString().trim();
    }
    return readFileString(real);
}
String vfsWriteEtc(String path, String content, boolean append) {
    String real = vfsMapEtcPath(path);
    // /etc/ 只允许修改已有文件，不允许新建
    if (!new File(real).exists()) {
        return "[只读: 系统路径不允许新建文件, 只能修改已有配置]";
    }
    String fullErr = snapCheckFull(path);
    if (fullErr != null) {
        return fullErr;
    }
    takeSnapshot(path);
    return writeFileString(real, content, append);
}

// ======= /ctx/ =======
String vfsReadCtx(String path) {
    if (path.equals("/ctx/")) {
        File dir = new File(pluginPath + "/config/ctx");
        String[] files = dir.list();
        if (files == null || files.length == 0) {
            return "(空)";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < files.length; i++) sb.append(files[i]).append("\n");
        return sb.toString().trim();
    }
    String real = pluginPath + "/config/ctx/" + path.replace("/ctx/", "");
    return readFileString(real);
}

// ======= /var/log/ =======
String vfsReadVarLog(String path) {
    if (path.equals("/var/log/messages")) {
        return readFileString(pluginPath + "/config/log.txt");
    }
    if (path.equals("/var/log/errors")) {
        return readFileString(pluginPath + "/config/error.txt");
    }
    return "[未知日志: " + path + "]";
}

// ======= /dev/ =======
// 消息总线 — 按 peerUin_chatType 隔离，每个会话只能读到自己的消息
static Map msgBus = java.util.Collections.synchronizedMap(new HashMap());
static int onMainThread = 0;
static List daemonOutQueue = java.util.Collections.synchronizedList(new ArrayList());
static List delayedTasks = java.util.Collections.synchronizedList(new ArrayList());
static Map pendingApprovals = new HashMap();
static int nextApprovalId = 1;
String vfsReadDev(String path, String peerUin, int chatType) {
    if (path.equals("/dev/msg-stream")) {
        String key = peerUin + "_" + chatType;
        List list = (List) msgBus.get(key);
        if (list == null || list.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        synchronized (list) {
            for (int i = 0; i < list.size(); i++) sb.append(list.get(i)).append("\n");
            list.clear();
        }
        return sb.toString().trim();
    }
    return "[只写设备或不存在]";
}
void vfsWriteDevOut(String content, String peerUin, int chatType) {
    // 检测是否在非主线程（daemon），如果是则放入待发队列
    if (onMainThread == 0) {
        daemonOutQueue.add(peerUin + "|" + chatType + "|" + content);
        return;
    }
    String msg = "[Output] " + content;
    lastAssistantMsg = msg;
    sendMsg(peerUin, msg, chatType);
}
void vfsWriteDevExit(String content) {
    // daemon 退出信号，由 shell exec 的 & 分支处理
}

// ======= /persist/ /tmp/ (共享存储) =======
static Map vfsTmp = new HashMap(); // 内存虚拟 /tmp
String vfsReadPersist(String path) {
    return readFileString(pluginPath + "/shared-space/" + path.replace("/persist/", ""));
}
String vfsWritePersist(String path, String content, boolean append) {
    String fullErr = snapCheckFull(path);
    if (fullErr != null) {
        return fullErr;
    }
    takeSnapshot(path);
    return writeFileString(pluginPath + "/shared-space/" + path.replace("/persist/", ""), content, append);
}
String vfsReadTmp(String path) {
    Object v = vfsTmp.get(path);
    return v != null ? v.toString() : "(文件不存在)";
}
String vfsWriteTmp(String path, String content, boolean append) {
    if (vfsTmp.size() > 50) {
        return "[tmp 文件数超限 50]";
    }
    String existing = (String) vfsTmp.get(path);
    if (existing != null && existing.length() + content.length() > 100000) {
        return "[tmp 单文件超限 100KB]";
    }
    if (append && existing != null) {
        content = existing + content;
    }
    vfsTmp.put(path, content);
    return null;
}

// ======= /src/ /proc/ps/free/uptime =======
String vfsReadSrc() { return readFileString(pluginPath + "/main.java"); }
static long wsStartTime = System.currentTimeMillis();
String vfsReadProcPS() {
    StringBuilder sb = new StringBuilder();
    sb.append("PID  STAT  CMD\n");
    for (Object e : daemons.entrySet()) {
        Map.Entry en = (Map.Entry) e;
        Thread t = (Thread) en.getValue();
        String stat = t.isAlive() ? "S" : "Z";
        sb.append(en.getKey()).append("    ").append(stat).append("    corax-daemon\n");
    }
    if (daemons.isEmpty()) {
        sb.append("(无活跃任务)\n");
    }
    return sb.toString();
}
String vfsReadProcFree() {
    return "daemons: " + daemons.size() + "  delay: " + delayJobs.size();
}
String vfsReadProcUptime() {
    long uptime = (System.currentTimeMillis() - wsStartTime) / 1000;
    return uptime + "s (" + (uptime / 3600) + "h " + ((uptime % 3600) / 60) + "m)";
}
String vfsReadProcStatus(String path) {
    try {
        String pidStr = path.replace("/proc/", "").replace("/status", "").trim();
        int pid = Integer.parseInt(pidStr);
        // 先查 daemon
        Thread t = (Thread) daemons.get(pid);
        if (t != null) {
            return t.isAlive() ? "running" : "terminated";
        }
        // 再查延时任务
        Map job = (Map) delayJobs.get(pid);
        if (job != null) {
            String st = String.valueOf(job.get("status"));
            long begin = Long.parseLong(String.valueOf(job.get("begin")));
            long end = Long.parseLong(String.valueOf(job.get("end")));
            long now = System.currentTimeMillis();
            if ("done".equals(st)) {
                return "done";
            }
            if (now >= end) {
                return "overtime (scheduled: " + (end - begin) / 1000 + "s ago)";
            }
            long remain = (end - now) / 1000;
            return "pending (remain: " + remain + "s, cmd: " + job.get("cmd") + ")";
        }
        return "[pid 不存在]";
    } catch (Exception e) { return "[解析失败]"; }
}
String vfsReadProcStdout(String path) {
    // daemon stdout 从队列读
    try {
        String pidStr = path.replace("/proc/", "").replace("/stdout", "").trim();
        int pid = Integer.parseInt(pidStr);
        List q = (List) daemonOutputs.get(pid);
        if (q == null || q.isEmpty()) {
            return "(空)";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < q.size(); i++) sb.append(q.get(i)).append("\n");
        q.clear();
        return sb.toString().trim();
    } catch (Exception e) { return "[解析失败]"; }
}
String vfsWriteProcKill(String path) {
    try {
        String pidStr = path.replace("/proc/", "").replace("/kill", "").trim();
        int pid = Integer.parseInt(pidStr);
        Thread t = (Thread) daemons.get(pid);
        if (t != null && t.isAlive()) {
            t.interrupt();
        }
        daemons.remove(pid);
        daemonOutputs.remove(pid);
        return null;
    } catch (Exception e) { return "[解析失败]"; }
}

// ======= /var/ =======
String vfsWriteVarDb(String sql) {
    // 仅拦截 DROP TABLE / ALTER TABLE，其余操作由快照保护
    String upper = sql.trim().toUpperCase();
    if (upper.contains("DROP") || upper.contains("ALTER")) {
        return "[拒绝: 不允许 DROP/ALTER]";
    }
    // SELECT 查询：只读，无需快照
    if (upper.startsWith("SELECT") || upper.startsWith("EXPLAIN")
        || upper.startsWith("WITH") || upper.startsWith("DESCRIBE")) {
        try {
            Cursor c = getDb().rawQuery(sql, null);
            StringBuilder sb = new StringBuilder();
            int colCount = c.getColumnCount();
            for (int i = 0; i < colCount; i++) {
                if (i > 0) {
                    sb.append(" | ");
                }
                sb.append(c.getColumnName(i));
            }
            sb.append("\n");
            for (int i = 0; i < colCount; i++) {
                if (i > 0) {
                    sb.append("-+-");
                }
                sb.append("---");
            }
            sb.append("\n");
            int rowCount = 0;
            while (c.moveToNext() && rowCount < 50) {
                for (int i = 0; i < colCount; i++) {
                    if (i > 0) {
                        sb.append(" | ");
                    }
                    sb.append(c.getString(i) != null ? c.getString(i) : "NULL");
                }
                sb.append("\n");
                rowCount++;
            }
            if (rowCount >= 50) {
                sb.append("... (truncated, max 50 rows)\n");
            }
            c.close();
            return sb.toString().isEmpty() ? "(查询结果为空)" : sb.toString().trim();
        }
        catch (Exception e) { return "[SQL错误: " + e.getMessage() + "]"; }
    }
    // 写操作：先快照，再执行
    String fullErr = snapCheckFull("/var/data.db");
    if (fullErr != null) {
        return fullErr;
    }
    takeSnapshot("/var/data.db");
    try {
        getDb().execSQL(sql);
        return null;
    }
    catch (Exception e) { return "[SQL错误: " + e.getMessage() + "]"; }
}

// ======= 辅助 =======
String readFileString(String path) {
    try {
        File f = new File(path);
        if (!f.exists()) {
            return "(文件不存在)";
        }
        if (f.isDirectory()) {
            String[] files = f.list();
            if (files == null || files.length == 0) {
                return "(空)";
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < files.length; i++) sb.append(files[i]).append("\n");
            return sb.toString().trim();
        }
        BufferedReader br = new BufferedReader(new FileReader(f));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line).append("\n");
        br.close();
        return sb.toString().trim();
    } catch (Exception e) { return "[读取失败: " + e.getMessage() + "]"; }
}
String writeFileString(String path, String content, boolean append) {
    try {
        File f = new File(path);
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        if (append && f.exists()) {
            FileWriter fw = new FileWriter(f, true);
            fw.write(content);
            fw.close();
        } else {
            PrintWriter pw = new PrintWriter(new FileWriter(f));
            pw.print(content);
            pw.close();
        }
        return null;
    } catch (Exception e) { return "[写入失败: " + e.getMessage() + "]"; }
}

void snapCopyFile(File src, File dst) {
    FileInputStream fis = null;
    FileOutputStream fos = null;
    try {
        fis = new FileInputStream(src);
        fos = new FileOutputStream(dst);
        byte[] buf = new byte[8192];
        int n;
        while ((n = fis.read(buf)) > 0) {
            fos.write(buf, 0, n);
        }
    }
    catch (Exception e) {
        this.log("error.txt", "snapCopyFile: " + e.getMessage());
    }
    finally {
        if (fis != null) {
            try {
                fis.close();
            }
            catch (Exception ignored) {
            }
        }
        if (fos != null) {
            try {
                fos.close();
            }
            catch (Exception ignored) {
            }
        }
    }
}

// ==================== 快照系统 ====================
String snapBaseDir() {
    return pluginPath + "/config/.snapshots";
}
String snapPathKey(String vpath) {
    // /var/data.db → var_data_db, /etc/admins.txt → etc_admins_txt
    return vpath.replace("/", "_").replaceAll("^_+", "");
}
String snapDir(String vpath) {
    return snapBaseDir() + "/" + snapPathKey(vpath);
}
int snapNextIndex(String vpath) {
    File dir = new File(snapDir(vpath));
    if (!dir.exists()) {
        return 1;
    }
    String[] files = dir.list();
    if (files == null || files.length == 0) {
        return 1;
    }
    int max = 0;
    for (int i = 0; i < files.length; i++) {
        int idx = 0;
        try { idx = Integer.parseInt(files[i].split("_")[0]); } catch (Exception e) { }
        if (idx > max) {
            max = idx;
        }
    }
    return max + 1;
}
String snapCurrentContent(String vpath) {
    // 读取当前内容作为快照数据
    if (vpath.startsWith("/var/data.db")) {
        File f = new File(pluginPath + "/config/data.db");
        if (!f.exists()) {
            return "";
        }
        return "[snapshot-binary]";
    }
    if (vpath.startsWith("/etc/")) {
        String real = vfsMapEtcPath(vpath);
        if (!new File(real).exists()) {
            return "";
        }
        return readFileString(real);
    }
    if (vpath.startsWith("/persist/")) {
        String real = pluginPath + "/shared-space/" + vpath.replace("/persist/", "");
        if (!new File(real).exists()) {
            return "";
        }
        return readFileString(real);
    }
    if (vpath.startsWith("/proc/sys/")) {
        String key = vpath.replace("/proc/sys/", "");
        Map cfg = loadAiConfig();
        String v = (String) cfg.get(key);
        return v != null ? v : "";
    }
    return "";
}
String snapCheckFull(String vpath) {
    File dir = new File(snapDir(vpath));
    if (dir.exists()) {
        String[] files = dir.list();
        if (files != null && files.length >= 10) {
            return "[快照已满: " + vpath + " 已达 10 个上限，请先 corax-snapshot-rm 删除旧快照]";
        }
    }
    return null;
}
void takeSnapshot(String vpath) {
    try {
        String current = snapCurrentContent(vpath);
        if (current.isEmpty() && !new File(pluginPath + "/config/data.db").exists()
            && !vpath.startsWith("/etc/") && !vpath.startsWith("/persist/")
            && !vpath.startsWith("/proc/sys/")) {
            return;
        }
        File dir = new File(snapDir(vpath));
        if (!dir.exists()) {
            dir.mkdirs();
        }
        // 二进制快照：直接复制数据库文件
        if ("[snapshot-binary]".equals(current)) {
            File src = new File(pluginPath + "/config/data.db");
            if (!src.exists()) {
                return;
            }
            try {
                getDb().execSQL("PRAGMA wal_checkpoint(TRUNCATE)");
            }
            catch (Exception e) {
            }
            int idx = snapNextIndex(vpath);
            String ts = getCurrentTime().replace(":", "-").replace(" ", "_");
            long len = src.length();
            String sizeStr = len >= 1024 ? (len / 1024 + "KB") : (len + "B");
            String fname = idx + "_" + ts + "_" + sizeStr;
            snapCopyFile(src, new File(dir, fname));
            return;
        }
        // 文本快照
        int idx = snapNextIndex(vpath);
        String ts = getCurrentTime().replace(":", "-").replace(" ", "_");
        int len = current.length();
        String sizeStr = len >= 1024 ? (len / 1024 + "KB") : (len + "B");
        String fname = idx + "_" + ts + "_" + sizeStr;
        writeFileString(new File(dir, fname).getAbsolutePath(), current, false);
    } catch (Exception e) { this.log("error.txt", "takeSnapshot: " + e.getMessage()); }
}
String listSnapshots(String vpath) {
    File dir = new File(snapDir(vpath));
    if (!dir.exists() || !dir.isDirectory()) {
        return "(无快照)";
    }
    String[] files = dir.list();
    if (files == null || files.length == 0) {
        return "(无快照)";
    }
    // 按数字索引排序（非字典序）
    for (int a = 0; a < files.length; a++) {
        for (int b = a + 1; b < files.length; b++) {
            int ia = 0;
            int ib = 0;
            try { ia = Integer.parseInt(files[a].split("_")[0]); } catch (Exception e) { }
            try { ib = Integer.parseInt(files[b].split("_")[0]); } catch (Exception e) { }
            if (ia > ib) {
                String tmp = files[a];
                files[a] = files[b];
                files[b] = tmp;
            }
        }
    }
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < files.length; i++) {
        // 1_2026-06-28_17-25-01_45KB → 1  2026-06-28 17:25:01  45KB
        String[] parts = files[i].split("_");
        String idx = parts[0];
        String date = parts.length > 1 ? parts[1] : "?";
        String time = parts.length > 2 ? parts[2] : "?";
        String size = parts.length > 3 ? parts[3] : "?";
        sb.append(idx).append("  ").append(date).append(" ").append(time.replace("-", ":")).append("  ").append(size).append("\n");
    }
    return sb.toString().trim();
}
String restoreSnapshot(String vpath, int snapIdx) {
    File dir = new File(snapDir(vpath));
    if (!dir.exists()) {
        return "[快照不存在]";
    }
    String[] files = dir.list();
    if (files == null) {
        return "[快照不存在]";
    }
    String target = null;
    for (int i = 0; i < files.length; i++) {
        if (files[i].startsWith(snapIdx + "_")) {
            target = files[i];
            break;
        }
    }
    if (target == null) {
        return "[快照 #" + snapIdx + " 不存在]";
    }
    // 恢复前先保存当前状态，以防恢复错误可撤销
    // 如果满 10 个，先删最旧的腾空间
    {
        File checkDir = new File(snapDir(vpath));
        if (checkDir.exists()) {
            String[] checkFiles = checkDir.list();
            if (checkFiles != null && checkFiles.length >= 10) {
                // 找最旧的（最小 index）并删除
                int minIdx = Integer.MAX_VALUE;
                String toDel = null;
                for (int ci = 0; ci < checkFiles.length; ci++) {
                    int cidx = 0;
                    try { cidx = Integer.parseInt(checkFiles[ci].split("_")[0]); } catch (Exception e) { }
                    if (cidx < minIdx) {
                        minIdx = cidx;
                        toDel = checkFiles[ci];
                    }
                }
                if (toDel != null) {
                    new File(checkDir, toDel).delete();
                }
            }
        }
    }
    takeSnapshot(vpath);
    // 二进制快照：直接复制文件
    if (vpath.startsWith("/var/data.db")) {
        closeSharedDb();
        snapCopyFile(new File(dir, target), new File(pluginPath + "/config/data.db"));
        getDb();
        return null;
    }
    String content = readFileString(new File(dir, target).getAbsolutePath());
    if (vpath.startsWith("/etc/")) {
        String real = vfsMapEtcPath(vpath);
        return writeFileString(real, content, false);
    }
    if (vpath.startsWith("/persist/")) {
        return writeFileString(pluginPath + "/shared-space/" + vpath.replace("/persist/", ""), content, false);
    }
    if (vpath.startsWith("/proc/sys/")) {
        String key = vpath.replace("/proc/sys/", "");
        Map cfg = loadAiConfig();
        cfg.put(key, content.trim());
        saveAiConfig(cfg);
        return null;
    }
    return "[不支持的恢复路径: " + vpath + "]";
}

// ==================== Corax-Shell 执行器 ====================
static Map daemons = java.util.Collections.synchronizedMap(new HashMap());
static Map daemonOutputs = java.util.Collections.synchronizedMap(new HashMap());
static int nextDaemonPid = 1;
// 延时任务注册表 {pid: {cmd, begin, end, status}}
static Map delayJobs = java.util.Collections.synchronizedMap(new LinkedHashMap());

// 单行命令解析与执行
String shellExecLine(String line, String senderUin, String peerUin, int chatType) {
    if (line == null || line.trim().isEmpty()) {
        return "";
    }
    line = line.trim();

    // 子Shell: ( ... )
    if (line.startsWith("(") && line.endsWith(")")) {
        line = line.substring(1, line.length() - 1).trim();
    }

    // ---- Tokenizer ----
    List tokens = new ArrayList();
    int pos = 0;
    while (pos < line.length()) {
        char c = line.charAt(pos);
        // 空白跳过
        if (c == ' ' || c == '\t') {
            pos++;
            continue;
        }
        // 注释
        if (c == '#') {
            break;
        }
        // 后台
        if (c == '&' && pos == line.length() - 1) {
            tokens.add("&");
            break;
        }
        if (c == '&' && pos + 1 < line.length() && line.charAt(pos + 1) == '&') {
            tokens.add("&&");
            pos += 2;
            continue;
        }
        // OR
        if (c == '|' && pos + 1 < line.length() && line.charAt(pos + 1) == '|') {
            tokens.add("||");
            pos += 2;
            continue;
        }
        // 管道
        if (c == '|') {
            tokens.add("|");
            pos++;
            continue;
        }
        // 分号
        if (c == ';') {
            tokens.add(";");
            pos++;
            continue;
        }
        // 重定向
        if (c == '>' && pos + 1 < line.length() && line.charAt(pos + 1) == '>') {
            tokens.add(">>");
            pos += 2;
            continue;
        }
        if (c == '>') {
            tokens.add(">");
            pos++;
            continue;
        }
        if (c == '<') {
            tokens.add("<");
            pos++;
            continue;
        }
        // 引号字符串
        if (c == '"' || c == '\'') {
            char quote = c;
            int startQ = ++pos;
            while (pos < line.length() && line.charAt(pos) != quote) pos++;
            tokens.add(line.substring(startQ, pos));
            if (pos < line.length()) {
                pos++; // skip closing quote;
            }
            continue;
        }
        // 普通单词
        int startW = pos;
        while (pos < line.length() && " |;&><\t".indexOf(line.charAt(pos)) < 0) pos++;
        tokens.add(line.substring(startW, pos));
    }
    if (tokens.isEmpty()) {
        return "";
    }

    // 后台标记
    boolean bg = tokens.size() > 0 && tokens.get(tokens.size() - 1).equals("&");
    if (bg) {
        tokens.remove(tokens.size() - 1);
    }

    // 延时后台命令检测：sleep N && cmd &
    boolean hasDelay = false;
    if (bg) {
        for (int ti = 0; ti < tokens.size(); ti++) {
            String t = (String) tokens.get(ti);
            if (t.equals("sleep") && ti + 1 < tokens.size()) {
                try { hasDelay = Long.parseLong(((String) tokens.get(ti + 1)).replaceAll("[^0-9]", "")) > 0; } catch (Exception e) {}
                break;
            }
        }
    }

    // 后台执行
    if (bg) {
        final List bgTokens = new ArrayList(tokens);
        final String bgSu = senderUin;
        final String bgPu = peerUin;
        final int bgCt = chatType;

        if (!hasDelay) {
            // 无延时，普通后台
            final List finalTokens = new ArrayList(bgTokens);
            if (daemons.size() >= 10) {
                return "[拒绝: daemon 数量已达上限 10，请先 kill 旧任务]";
            }
            final int p = nextDaemonPid++;
            Thread t = new Thread(new Runnable() {
                public void run() {
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        public void run() {
                            onMainThread++;
                            try {
                                int[] ix = new int[]{0};
                                parseSequence(finalTokens, ix, "", bgSu, bgPu, bgCt);
                            } catch (Exception e) {}
                            finally {
                                onMainThread--;
                                daemons.remove(p);
                                daemonOutputs.remove(p);
                            }
                        }
                    });
                }
            });
            t.setDaemon(true);
            t.start();
            daemons.put(p, t);
            return "[pid:" + p + "]";
        }

        // 提取 sleep 延时和实际执行 tokens
        long delayMs = 0;
        List execTokens = new ArrayList();
        for (int ti = 0; ti < bgTokens.size(); ti++) {
            String st = (String) bgTokens.get(ti);
            if (st.equals("sleep") && ti + 1 < bgTokens.size()) {
                try { delayMs = Long.parseLong(((String) bgTokens.get(ti + 1)).replaceAll("[^0-9]", "")) * 1000L; }
                catch (Exception ex) { }
                ti++;
                continue;
            }
            execTokens.add(st);
        }
        // 去掉 sleep 后面紧跟的 && / ;
        for (int ei = 0; ei < execTokens.size(); ei++) {
            if ((execTokens.get(ei).equals("&&") || execTokens.get(ei).equals(";")) && ei + 1 < execTokens.size()) {
                execTokens.remove(ei); ei--;
            }
        }

        if (delayMs > 0 && !execTokens.isEmpty()) {
            StringBuilder preview = new StringBuilder();
            for (int ei = 0; ei < Math.min(execTokens.size(), 6); ei++) {
                if (ei > 0) {
                    preview.append(" ");
                }
                preview.append(execTokens.get(ei));
            }
            final List st = new ArrayList(execTokens);
            // 双保险：Timer精确 + 轮询兜底
            final Map task = new HashMap();
            task.put("at", System.currentTimeMillis() + delayMs);
            task.put("tokens", new ArrayList(execTokens));
            task.put("su", bgSu); task.put("pu", bgPu); task.put("ct", bgCt);
            delayedTasks.add(task);
            
            if (delayTimer == null) {
                delayTimer = new Timer(true);
            }
            delayTimer.schedule(new TimerTask() {
                public void run() {
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        public void run() {
                            onMainThread++;
                            try {
                                int[] ix = new int[]{0};
                                parseSequence(st, ix, "", bgSu, bgPu, bgCt);
                            } catch (Exception e) {}
                            finally {
                                onMainThread--;
                            }
                        }
                    });
                }
            }, delayMs);
        }

        // 按 sleep N 拆分为链式段 [{delayMs, execTokens}, ...]
        // sleep 5 && cmd1 && sleep 10 && cmd2 -> [{5, cmd1}, {10, cmd2}]
        List segments = new ArrayList();
        List curTokens = new ArrayList();
        long curDelay = 0;
        for (int ti = 0; ti < bgTokens.size(); ti++) {
            String t = (String) bgTokens.get(ti);
            if (t.equals("sleep") && ti + 1 < bgTokens.size()) {
                if (!curTokens.isEmpty() || curDelay > 0) {
                    Map seg = new HashMap();
                    seg.put("delay", curDelay);
                    seg.put("tokens", new ArrayList(curTokens));
                    segments.add(seg);
                    curTokens.clear();
                }
                try { curDelay = Long.parseLong(((String) bgTokens.get(ti + 1)).replaceAll("[^0-9]", "")) * 1000L; }
                catch (Exception ex) { curDelay = 0; }
                ti++;
                continue;
            }
            curTokens.add(t);
        }
        if (!curTokens.isEmpty() || curDelay > 0) {
            Map seg = new HashMap();
            seg.put("delay", curDelay);
            seg.put("tokens", new ArrayList(curTokens));
            segments.add(seg);
        }

        // 有延时段，构建链式调度
        scheduleChain(segments, 0, bgSu, bgPu, bgCt);
        // 构建预览
        StringBuilder preview = new StringBuilder();
        for (int si = 0; si < segments.size(); si++) {
            Map seg = (Map) segments.get(si);
            if (si > 0) {
                preview.append("; ");
            }
            long d = Long.parseLong(String.valueOf(seg.get("delay")));
            List toks = (List) seg.get("tokens");
            preview.append("sleep ").append(d / 1000).append(" ");
            for (int ti = 0; ti < Math.min(toks.size(), 3); ti++) {
                preview.append(toks.get(ti)).append(" ");
            }
            if (toks.size() > 3) {
                preview.append("...");
            }
        }
        return "[延时链: " + preview.toString().trim() + "]";
    }

    // ---- 递归下降解析器 ----
    int[] idx = new int[]{0};
    String result = parseSequence(tokens, idx, "", senderUin, peerUin, chatType);
    return result != null ? result : "";
}

// 链式调度延时任务段 [{delay, tokens}, ...]
void scheduleChain(final List segments, final int index, final String bgSu, final String bgPu, final int bgCt) {
    if (index >= segments.size()) {
        return;
    }
    final Map seg = (Map) segments.get(index);
    final long delayMs = Long.parseLong(String.valueOf(seg.get("delay")));
    final List segTokens = (List) seg.get("tokens");
    if (segTokens.isEmpty()) {
        scheduleChain(segments, index + 1, bgSu, bgPu, bgCt);
        return;
    }
    // 清理首部 && / ;
    while (!segTokens.isEmpty() && (segTokens.get(0).equals("&&") || segTokens.get(0).equals(";"))) {
        segTokens.remove(0);
    }
    if (segTokens.isEmpty()) {
        scheduleChain(segments, index + 1, bgSu, bgPu, bgCt);
        return;
    }
    // 注册到进程表
    final int jobPid = nextDaemonPid++;
    StringBuilder cmdPreview = new StringBuilder();
    for (int ti = 0; ti < Math.min(segTokens.size(), 4); ti++) {
        if (ti > 0) {
            cmdPreview.append(" ");
        }
        cmdPreview.append(segTokens.get(ti));
    }
    Map job = new HashMap();
    job.put("cmd", cmdPreview.toString());
    job.put("begin", System.currentTimeMillis());
    job.put("end", System.currentTimeMillis() + delayMs);
    job.put("status", "pending");
    delayJobs.put(jobPid, job);
    // 双保险
    final Map task = new HashMap();
    task.put("at", System.currentTimeMillis() + delayMs);
    task.put("tokens", new ArrayList(segTokens));
    task.put("su", bgSu); task.put("pu", bgPu); task.put("ct", bgCt);
    delayedTasks.add(task);
    if (delayTimer == null) {
        delayTimer = new Timer(true);
    }
    delayTimer.schedule(new TimerTask() {
        public void run() {
            task.put("fired", Boolean.TRUE);
            job.put("status", "done");
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                public void run() {
                    onMainThread++;
                    try {
                        int[] ix = new int[]{0};
                        parseSequence(new ArrayList(segTokens), ix, "", bgSu, bgPu, bgCt);
                    } catch (Exception e) {}
                    finally { onMainThread--; }
                }
            });
            // 调度下一段
            scheduleChain(segments, index + 1, bgSu, bgPu, bgCt);
        }
    }, delayMs);
}

// 解析序列: pipeline ((; | && | ||) pipeline)*
String parseSequence(List tokens, int[] idx, String stdin, String senderUin, String peerUin, int chatType) {
    String result = parsePipeline(tokens, idx, stdin, senderUin, peerUin, chatType);
    while (idx[0] < tokens.size()) {
        String op = (String) tokens.get(idx[0]);
        if (op.equals(";")) {
            idx[0]++;
            result = parsePipeline(tokens, idx, "", senderUin, peerUin, chatType);
        }
        else if (op.equals("&&")) {
            idx[0]++;
            if (result != null && !result.isEmpty()) {
                result = parsePipeline(tokens, idx, "", senderUin, peerUin, chatType);
            } else {
                // 短路：消费右端 pipeline 的 tokens 但丢弃结果
                parsePipeline(tokens, idx, "", senderUin, peerUin, chatType);
            }
        }
        else if (op.equals("||")) {
            idx[0]++;
            if (result == null || result.isEmpty()) {
                result = parsePipeline(tokens, idx, "", senderUin, peerUin, chatType);
            } else {
                parsePipeline(tokens, idx, "", senderUin, peerUin, chatType);
            }
        }
        else {
            break;
        }
    }
    return result;
}

// 解析管道: command (| command)*
String parsePipeline(List tokens, int[] idx, String stdin, String senderUin, String peerUin, int chatType) {
    String pipeIn = stdin;
    while (idx[0] < tokens.size()) {
        // 收集命令词和重定向
        List cmdArgs = new ArrayList();
        String outRedir = null; boolean outAppend = false; String inRedir = null;
        while (idx[0] < tokens.size()) {
            String t = (String) tokens.get(idx[0]);
            if (t.equals("|") || t.equals(";") || t.equals("&&") || t.equals("||")) {
                break;
            }
            if (t.equals(">")) {
                idx[0]++;
                if (idx[0] < tokens.size()) {
                    outRedir = (String) tokens.get(idx[0]++);
                }
                continue;
            }
            if (t.equals(">>")) {
                idx[0]++;
                outAppend = true;
                if (idx[0] < tokens.size()) {
                    outRedir = (String) tokens.get(idx[0]++);
                }
                continue;
            }
            if (t.equals("<")) {
                idx[0]++;
                if (idx[0] < tokens.size()) {
                    inRedir = (String) tokens.get(idx[0]++);
                }
                continue;
            }
            if (t.equals(">")) {
                idx[0]++;
                if (idx[0] < tokens.size()) {
                    outRedir = (String) tokens.get(idx[0]++);
                }
                continue;
            }
            if (t.equals(">>")) {
                idx[0]++;
                outAppend = true;
                if (idx[0] < tokens.size()) {
                    outRedir = (String) tokens.get(idx[0]++);
                }
                continue;
            }
            if (t.equals("<")) {
                idx[0]++;
                if (idx[0] < tokens.size()) {
                    inRedir = (String) tokens.get(idx[0]++);
                }
                continue;
            }
            cmdArgs.add(t);
            idx[0]++;
        }

        if (cmdArgs.isEmpty()) {
            break;
        }

        // 输入重定向
        if (inRedir != null) {
            pipeIn = vfsRead(inRedir, senderUin, peerUin, chatType);
        }

        // 执行命令
        String cmd = (String) cmdArgs.get(0);
        String[] args = new String[cmdArgs.size() - 1];
        for (int ai = 1; ai < cmdArgs.size(); ai++) args[ai - 1] = (String) cmdArgs.get(ai);

        String result = shellBuiltin(cmd, args, pipeIn != null ? pipeIn : "", senderUin, peerUin, chatType);
        pipeIn = (result != null) ? result : "";

        // 输出重定向
        if (outRedir != null && !pipeIn.isEmpty()) {
            String werr = vfsWrite(outRedir, pipeIn, outAppend, senderUin, peerUin, chatType);
            if (werr != null) {
                pipeIn = werr;
            } else if (outRedir.equals("/dev/out")) {
                pipeIn = "[已发送到 /dev/out: " + (pipeIn.length() > 100 ? pipeIn.substring(0, 100) + "..." : pipeIn) + "]";
            } else {
                pipeIn = "";
            }
        }

        // 检查管道
        if (idx[0] < tokens.size() && tokens.get(idx[0]).equals("|")) {
            idx[0]++;
            continue;
        }
        break;
    }
    return pipeIn;
}

// 内置命令 (保持原有实现，不变)
String shellBuiltin(String cmd, String[] args, String stdin, String senderUin, String peerUin, int chatType) {
    try {
        if (cmd.equals("echo")) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < args.length; i++) {
                if (i > 0) {
                    sb.append(" ");
                }
                sb.append(args[i]);
            }
            return sb.toString();
        }
        if (cmd.equals("cat")) {
            if (args.length == 0) {
                return stdin;
            }
            String path = args[0];
            if (path.startsWith("-")) {
                path = args.length > 1 ? args[1] : args[0];
            }
            if (path.equals("-")) {
                return stdin;
            }
            // 二进制文件保护
            if (path.startsWith("/persist/") || path.startsWith("/var/")) {
                String real = path;
                if (path.startsWith("/persist/")) {
                    real = pluginPath + "/shared-space/" + path.replace("/persist/", "");
                }
                else if (path.startsWith("/var/")) {
                    real = pluginPath + "/config/" + path.replace("/var/", "");
                }
                File f = new File(real);
                if (f.isFile() && f.length() > 100 * 1024) {
                    return "文件过大 (" + (f.length() / 1024) + "KB), 禁止读取。使用 corax-sendfile 发送。";
                }
                byte[] head = new byte[Math.min((int) f.length(), 512)];
                if (f.isFile() && f.length() > 0) {
                    try {
                        FileInputStream fis = new FileInputStream(f);
                        fis.read(head);
                        fis.close();
                        for (int bi = 0; bi < head.length; bi++) {
                            if (head[bi] == 0) {
                                return "[二进制文件，不可 cat。使用 stat 查看信息]";
                            }
                        }
                    } catch (Exception e) { return "[读取失败]"; }
                }
            }
            return vfsRead(path, senderUin, peerUin, chatType);
        }
        if (cmd.equals("ls")) {
            String path = "/";
            for (int i = 0; i < args.length; i++) {
                if (!args[i].startsWith("-")) {
                    path = args[i];
                    break;
                }
            }
            return vfsRead(path, senderUin, peerUin, chatType);
        }
        if (cmd.equals("grep")) {
            boolean invert = false;
            String pattern = null;
            for (int i = 0; i < args.length; i++) {
                if (args[i].equals("-v")) {
                    invert = true;
                }
                else if (pattern == null) {
                    pattern = args[i];
                }
            }
            if (pattern == null) {
                return "grep: 需要模式";
            }
            String[] lines = stdin.split("\n");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < lines.length; i++) {
                boolean match = lines[i].contains(pattern);
                if (invert) {
                    match = !match;
                }
                if (match) {
                    sb.append(lines[i]).append("\n");
                }
            }
            return sb.toString().trim();
        }
        if (cmd.equals("wc")) {
            boolean linesOnly = args.length > 0 && args[0].equals("-l");
            String[] lines = stdin.split("\n");
            int count = stdin.isEmpty() ? 0 : lines.length;
            return linesOnly ? String.valueOf(count) : count + " " + stdin.length();
        }
        if (cmd.equals("head")) {
            int n = 10;
            if (args.length > 0 && args[0].equals("-n") && args.length > 1) { try { n = Integer.parseInt(args[1]); } catch (Exception e) {} }
            String[] lines = stdin.split("\n");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(n, lines.length); i++) sb.append(lines[i]).append("\n");
            return sb.toString().trim();
        }
        if (cmd.equals("tail")) {
            int n = 10;
            if (args.length > 0 && args[0].equals("-n") && args.length > 1) { try { n = Integer.parseInt(args[1]); } catch (Exception e) {} }
            String[] lines = stdin.split("\n");
            int start = Math.max(0, lines.length - n);
            StringBuilder sb = new StringBuilder();
            for (int i = start; i < lines.length; i++) sb.append(lines[i]).append("\n");
            return sb.toString().trim();
        }
        if (cmd.equals("date")) {
            return getCurrentTime();
        }
        if (cmd.equals("mount")) {
            return "/proc on /proc type corax-proc (rw)\n"
                + "/etc on /etc type corax-config (rw)\n"
                + "/dev on /dev type corax-device (rw)\n"
                + "/ctx on /ctx type corax-ctx (rw)\n"
                + "/var on /var type corax-data (rw)\n"
                + "/tmp on /tmp type corax-tmp (rw)\n"
                + "/persist on /persist type corax-persist (rw)\n"
                + "/src on /src type corax-src (ro)\n"
                + "/usr on /usr type corax-doc (ro)";
        }
        if (cmd.equals("sleep")) {
            if (args.length < 1) {
                return "sleep: 需要秒数参数";
            }
            try { Thread.sleep(Long.parseLong(args[0].replaceAll("[^0-9]", "")) * 1000L); }
            catch (Exception e) { return "sleep: 无效参数: " + args[0]; }
            return "";
        }

        // Corax 命令
        if (cmd.equals("sed")) {
            if (args.length < 2) {
                return "用法: sed 's/查找/替换/g' <文件路径>";
            }
            String pattern = args[0];
            String filePath = args[1];
            boolean global = pattern.endsWith("g");
            if (global) {
                pattern = pattern.substring(0, pattern.length() - 1);
            }
            if (!pattern.startsWith("s/") || !pattern.endsWith("/")) {
                return "sed: 需 s/old/new/ 或 s/old/new/g";
            }
            String inner = pattern.substring(2, pattern.length() - 1);
            int sep = inner.indexOf("/");
            if (sep < 0) {
                return "sed: 缺少分隔符";
            }
            String oldStr = inner.substring(0, sep);
            String newStr = inner.substring(sep + 1);
            String content = vfsRead(filePath, senderUin, peerUin, chatType);
            if (content.startsWith("(") || content.startsWith("[")) {
                return "sed: " + content;
            }
            if (global) {
                content = content.replace(oldStr, newStr);
            }
            else { int f = content.indexOf(oldStr); if (f >= 0) content = content.substring(0, f) + newStr + content.substring(f + oldStr.length()); }
            String err = vfsWrite(filePath, content, false, senderUin, peerUin, chatType);
            return err != null ? err : "替换完成";
        }
        if (cmd.equals("corax-edit")) {
            if (args.length < 3) {
                return "用法: corax-edit <文件路径> <旧文本> --- <新文本>";
            }
            String filePath = args[0];
            StringBuilder oldB = new StringBuilder(); StringBuilder newB = new StringBuilder();
            boolean sepReached = false;
            for (int i = 1; i < args.length; i++) {
                if (!sepReached && args[i].equals("---")) {
                    sepReached = true;
                    continue;
                }
                if (!sepReached) {
                    if (oldB.length() > 0) {
                        oldB.append(" ");
                    }
                    oldB.append(args[i]);
                }
                else { if (newB.length() > 0) newB.append(" "); newB.append(args[i]); }
            }
            if (!sepReached) {
                return "用法: corax-edit <路径> <旧文本> --- <新文本>";
            }
            String content = vfsRead(filePath, senderUin, peerUin, chatType);
            if (content.startsWith("(") || content.startsWith("[")) {
                return "编辑: " + content;
            }
            String oldS = oldB.toString(); String newS = newB.toString();
            if (!content.contains(oldS)) {
                return "未找到匹配文本";
            }
            content = content.replace(oldS, newS);
            String err = vfsWrite(filePath, content, false, senderUin, peerUin, chatType);
            return err != null ? err : "已替换 1 处";
        }
        if (cmd.equals("corax-search")) {
            return doWebSearch(args.length > 0 ? args[0] : "");
        }
        if (cmd.equals("corax-fetch")) {
            return doFetchPage(args.length > 0 ? args[0] : "");
        }
        if (cmd.equals("corax-mem-create")) {
            boolean pub = false; String tags = "", content = "", about = "";
            int ai = 0;
            if (ai < args.length && args[ai].equals("--public")) {
                pub = true;
                ai++;
            }
            if (ai < args.length && args[ai].startsWith("--about=")) {
                about = args[ai].substring(8);
                ai++;
            }
            if (ai < args.length) {
                tags = args[ai++];
            }
            StringBuilder sb = new StringBuilder();
            for (int i = ai; i < args.length; i++) {
                if (i > ai) {
                    sb.append(" ");
                }
                sb.append(args[i]);
            }
            content = sb.toString();
            if (tags.isEmpty() || content.isEmpty()) {
                return "用法: corax-mem-create [--public] [--about=<uin>] <tags> <content>";
            }
            String subject = about.isEmpty() ? (pub ? "" : senderUin) : about;
            boolean ok = storeMemory(senderUin, content, tags, pub ? "public" : "private", subject);
            return ok ? "已创建" : "创建失败";
        }
        if (cmd.equals("corax-mem-rm")) {
            if (args.length < 1) {
                return "用法: corax-mem-rm <id>";
            }
            long id = Long.parseLong(args[0]);
            boolean ok = deleteMemoryById(id, senderUin, getRole(senderUin));
            return ok ? "已删除 #" + id : "删除失败";
        }
        if (cmd.equals("corax-mem-tag")) {
            boolean pub = args.length > 0 && args[0].equals("--public");
            String tag = pub ? (args.length > 1 ? args[1] : "") : (args.length > 0 ? args[0] : "");
            if (tag.isEmpty()) {
                return "用法: corax-mem-tag [--public] <tag>";
            }
            List results = pub ? searchPublicMemoriesByTag(tag) : searchMemoriesByTag(senderUin, tag);
            return formatMemList(results, false);
        }
        if (cmd.equals("corax-mem-search")) {
            boolean pub = args.length > 0 && args[0].equals("--public");
            String kw = pub ? (args.length > 1 ? args[1] : "") : (args.length > 0 ? args[0] : "");
            if (kw.isEmpty()) {
                return "用法: corax-mem-search [--public] <keyword>";
            }
            List results = pub ? searchPublicMemories(kw) : searchMemories(senderUin, kw);
            return formatMemList(results, false);
        }
        if (cmd.equals("corax-listen")) {
            if (args.length < 1) {
                return "用法: corax-listen <on|off|status>";
            }
            if (args[0].equals("on")) {
                clearListenLog(peerUin, chatType);
                addToList(pluginPath + "/config/listen_sessions.txt", peerUin + "_" + chatType);
                if (listenSessions != null) {
                    listenSessions.add(peerUin + "_" + chatType);
                }
                return "监听已开启";
            }
            if (args[0].equals("off")) {
                removeFromList(pluginPath + "/config/listen_sessions.txt", peerUin + "_" + chatType);
                if (listenSessions != null) {
                    listenSessions.remove(peerUin + "_" + chatType);
                }
                return "监听已关闭";
            }
            if (args[0].equals("status")) {
                if (listenSessions == null) {
                    listenSessions = readStringSet(pluginPath + "/config/listen_sessions.txt");
                }
                return listenSessions.contains(peerUin + "_" + chatType) ? "已开启" : "已关闭";
            }
            return "用法: corax-listen <on|off|status>";
        }
        if (cmd.equals("corax-sendfile")) {
            if (args.length < 1) {
                return "用法: corax-sendfile <路径>";
            }
            String filePath = args[0];
            File f = new File(filePath);
            if (!f.exists()) {
                if (filePath.startsWith("/persist/")) {
                    f = new File(pluginPath + "/shared-space/" + filePath.replace("/persist/", ""));
                } else if (filePath.startsWith("/var/")) {
                    f = new File(pluginPath + "/config/" + filePath.replace("/var/", ""));
                }
            }
            if (!f.exists()) {
                return "文件不存在: " + filePath;
            }
            if (onMainThread == 0) {
                final String absPath = f.getAbsolutePath();
                final String fpu = peerUin;
                final int fct = chatType;
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    public void run() {
                        sendFile(fpu, absPath, fct);
                    }
                });
                return "[已投递到主线程，稍后发送]";
            }
            sendFile(peerUin, f.getAbsolutePath(), chatType);
            return "已发送: " + f.getName();
        }
        if (cmd.equals("corax-reboot")) {
            if (args.length < 1) {
                return "用法: corax-reboot <人设名称>";
            }
            String target = args[0];
            File tf = new File(pluginPath + "/config/prompt/" + target + ".prompt.txt");
            if (!tf.exists()) {
                return "人设 \"" + target + "\" 不存在";
            }
            setActivePersona(target);
            return "已切换至: " + target + "（上下文已保留，新人设将在下条消息生效）";
        }
        if (cmd.equals("corax-snapshot-list")) {
            if (args.length < 1) {
                return "用法: corax-snapshot-list <路径>";
            }
            return listSnapshots(args[0]);
        }
        if (cmd.equals("corax-snapshot-restore")) {
            if (args.length < 2) {
                return "用法: corax-snapshot-restore <路径> <编号>";
            }
            int idx = 0;
            try { idx = Integer.parseInt(args[1]); } catch (Exception e) { return "编号必须为数字"; }
            String err = restoreSnapshot(args[0], idx);
            return err != null ? err : "已恢复到快照 #" + idx;
        }
        if (cmd.equals("corax-snapshot-rm")) {
            if (args.length < 2) {
                return "用法: corax-snapshot-rm <路径> <编号>";
            }
            int rmIdx = 0;
            try { rmIdx = Integer.parseInt(args[1]); } catch (Exception e) { return "编号必须为数字"; }
            String rmPath = args[0];
            File rmSnapDir = new File(snapDir(rmPath));
            if (!rmSnapDir.exists()) {
                return "[快照不存在]";
            }
            String[] rmFiles = rmSnapDir.list();
            if (rmFiles == null) {
                return "[快照不存在]";
            }
            String rmTarget = null;
            for (int fi = 0; fi < rmFiles.length; fi++) {
                if (rmFiles[fi].startsWith(rmIdx + "_")) {
                    rmTarget = rmFiles[fi];
                    break;
                }
            }
            if (rmTarget == null) {
                return "[快照 #" + rmIdx + " 不存在]";
            }
            String[] rmSp = rmTarget.split("_");
            String rmDate = rmSp.length > 1 ? rmSp[1] : "?";
            String rmTime = rmSp.length > 2 ? rmSp[2].replace("-", ":") : "?";
            String rmSize = rmSp.length > 3 ? rmSp[3] : "?";
            String rmDesc = rmPath + " #" + rmIdx + " (" + rmDate + " " + rmTime + " " + rmSize + ")";
            String appKey = peerUin + "_" + chatType;
            final int appId = nextApprovalId++;
            // 取消旧审批的定时器
            Map oldOp = (Map) pendingApprovals.get(appKey);
            if (oldOp != null) {
                Timer oldTm = (Timer) oldOp.get("timer");
                if (oldTm != null) {
                    try { oldTm.cancel(); } catch (Exception ex) { }
                }
            }
            final Map rmOp = new HashMap();
            rmOp.put("type", "snapshot-delete");
            rmOp.put("vpath", rmPath);
            rmOp.put("idx", String.valueOf(rmIdx));
            rmOp.put("desc", rmDesc);
            rmOp.put("appId", appId);
            rmOp.put("result", null);
            final Object lock = new Object();
            rmOp.put("lock", lock);
            // 30s 超时定时器（Timer 线程，不走 Handler.post）
            Timer rmTm = new Timer(true);
            rmTm.schedule(new TimerTask() {
                public void run() {
                    synchronized (lock) {
                        Map op2 = (Map) pendingApprovals.get(appKey);
                        if (op2 != null && Integer.parseInt(String.valueOf(op2.get("appId"))) == appId) {
                            op2.put("result", "timeout");
                            op2.put("timer", null);
                        }
                        lock.notifyAll();
                    }
                }
            }, 30000);
            rmOp.put("timer", rmTm);
            pendingApprovals.put(appKey, rmOp);
            sendMsg(peerUin, "[Corax-Shell] 请求删除快照 " + rmDesc + "，是否批准？发送 /ai operation permit 或 /ai operation reject", chatType);
            // 阻塞等待审批结果（审批通过 onMsg 在其他线程唤醒）
            synchronized (lock) {
                try {
                    lock.wait(30000);
                } catch (InterruptedException e) { }
            }
            // 取出结果并执行
            Map finalOp = (Map) pendingApprovals.remove(appKey);
            String finalResult = "timeout";
            if (finalOp != null) {
                String fr = (String) finalOp.get("result");
                if (fr != null) {
                    finalResult = fr;
                }
                Timer ft = (Timer) finalOp.get("timer");
                if (ft != null) {
                    try { ft.cancel(); } catch (Exception ex) { }
                }
            }
            if ("permit".equals(finalResult)) {
                File delDir = new File(snapDir(rmPath));
                String[] delFiles = delDir.list();
                String delTarget = null;
                if (delFiles != null) {
                    for (int di = 0; di < delFiles.length; di++) {
                        if (delFiles[di].startsWith(rmIdx + "_")) {
                            delTarget = delFiles[di];
                            break;
                        }
                    }
                }
                if (delTarget != null) {
                    new File(delDir, delTarget).delete();
                    injectApprovalResult(peerUin, chatType, "删除快照 " + rmDesc + " 已被批准并执行");
                    return "快照 " + rmDesc + " 已被管理员批准并删除";
                } else {
                    injectApprovalResult(peerUin, chatType, "删除快照 " + rmDesc + " 批准但快照已不存在");
                    return "快照批准但快照已不存在: " + rmDesc;
                }
            } else if ("reject".equals(finalResult)) {
                injectApprovalResult(peerUin, chatType, "删除快照 " + rmDesc + " 已被拒绝");
                return "快照 " + rmDesc + " 已被管理员拒绝";
            } else {
                injectApprovalResult(peerUin, chatType, "删除快照 " + rmDesc + " 请求超时，已自动拒绝");
                return "快照 " + rmDesc + " 审批超时，已自动拒绝";
            }
        }
        if (cmd.equals("stat")) {
            if (args.length < 1) {
                return "用法: stat <路径>";
            }
            String arg1 = args[0];
            String path = arg1;
            if (arg1.startsWith("-") && args.length > 1) {
                path = args[1];
            }
            if (path.startsWith("-")) {
                return "用法: stat <路径>";
            }
            String content = vfsRead(path, senderUin, peerUin, chatType);
            if (content.startsWith("[路径不存在")) { return content; }
            boolean isDir = false;
            if (content.startsWith("bin") || content.startsWith("proc") || content.startsWith("etc")) {
                isDir = true;
            }
            if (content.startsWith("dev") || content.startsWith("ctx") || content.startsWith("var")) {
                isDir = true;
            }
            if (content.startsWith("src") || content.startsWith("tmp") || content.startsWith("persist")) {
                isDir = true;
            }
            if (content.startsWith("usr")) {
                isDir = true;
            }
            String perms = "/proc/sys/ api_key RO; 其余 RW";
            if (path.startsWith("/src/")) {
                perms = "-- (拒绝访问)";
            }
            else if (path.startsWith("/proc/")) {
                perms = "部分 RO, 部分 RW";
            }
            else if (path.startsWith("/dev/")) {
                perms = "msg-stream RO, out WO";
            }
            else if (path.startsWith("/ctx/")) {
                perms = "RO (上下文只读)";
            }
            else if (path.startsWith("/var/log/")) {
                perms = "RO (日志只读)";
            }
            else if (path.startsWith("/var/data.db")) {
                perms = "RW (SQLite)";
            }
            else if (path.startsWith("/etc/prompt/") && path.contains(getActivePersona())) {
                perms = "RO (当前人设不可改)";
            }
            else if (path.startsWith("/etc/prompt/")) {
                perms = "RW (非激活人设可改)";
            }
            else if (path.startsWith("/etc/skills/")) {
                perms = "RW";
            }
            else if (path.startsWith("/persist/")) {
                perms = "RW (持久化)";
            }
            else if (path.startsWith("/tmp/")) {
                perms = "RW (临时, 限额 1MB/50文件)";
            }
            else if (path.startsWith("/etc/")) {
                perms = "RW";
            }
            int size = content.length();
            return "文件: " + path + "\n类型: " + (isDir ? "目录" : "文件") + "\n大小: " + size + " 字符\n权限: " + perms;
        }
        if (cmd.equals("touch")) {
            if (args.length < 1) {
                return "用法: touch <文件路径>";
            }
            String path = args[0];
            String ferr = vfsWrite(path, "", true, senderUin, peerUin, chatType);
            return ferr != null ? ferr : "";
        }
        if (cmd.equals("rm")) {
            if (args.length < 1) {
                return "用法: rm <文件路径>";
            }
            String path = args[0];
            String err = vfsWrite(path, "", false, senderUin, peerUin, chatType);
            return err != null ? err : "已删除";
        }
        if (cmd.equals("mkdir")) {
            if (args.length < 1) {
                return "用法: mkdir <目录路径>";
            }
            String path = args[0];
            if (!path.endsWith("/")) {
                path += "/";
            }
            String err = vfsWrite(path, "(目录)", false, senderUin, peerUin, chatType);
            return err != null ? err : "";
        }
        if (cmd.equals("chmod")) {
            return "";
        }
        if (cmd.equals("find")) {
            if (args.length < 2) {
                return "用法: find <目录> -name <模式>";
            }
            String dir = args[0];
            String pattern = args.length > 2 && args[1].equals("-name") ? args[2] : "";
            if (pattern.isEmpty()) { return "用法: find <目录> -name <模式>"; }
            String listing = vfsRead(dir, senderUin, peerUin, chatType);
            String[] entries = listing.split("\n");
            StringBuilder sb = new StringBuilder();
            for (int ei = 0; ei < entries.length; ei++) {
                String e = entries[ei].trim();
                if (e.isEmpty()) {
                    continue;
                }
                if (pattern.equals("*") || e.contains(pattern.replace("*", ""))) {
                    sb.append(dir).append(dir.endsWith("/") ? "" : "/").append(e).append("\n");
                }
            }
            return sb.toString().trim();
        }
        if (cmd.equals("sort")) {
            String[] lines = stdin.split("\n");
            java.util.Arrays.sort(lines);
            StringBuilder sb = new StringBuilder();
            for (int si = 0; si < lines.length; si++) {
                if (!lines[si].trim().isEmpty()) {
                    sb.append(lines[si]).append("\n");
                }
            }
            return sb.toString().trim();
        }
        if (cmd.equals("uniq")) {
            String[] lines = stdin.split("\n");
            StringBuilder sb = new StringBuilder();
            String last = "";
            for (int ui = 0; ui < lines.length; ui++) {
                if (!lines[ui].equals(last) && !lines[ui].trim().isEmpty()) {
                    sb.append(lines[ui]).append("\n");
                    last = lines[ui];
                }
            }
            return sb.toString().trim();
        }
        if (cmd.equals("cut")) {
            String delim = "\t"; int field = 1;
            for (int ci = 0; ci < args.length; ci++) {
                if (args[ci].equals("-d") && ci + 1 < args.length) {
                    delim = args[ci + 1];
                    ci++;
                }
                else if (args[ci].equals("-f") && ci + 1 < args.length) { try { field = Integer.parseInt(args[ci + 1]); } catch (Exception e) {} ci++; }
            }
            String[] lines = stdin.split("\n");
            StringBuilder sb = new StringBuilder();
            for (int li = 0; li < lines.length; li++) {
                if (lines[li].trim().isEmpty()) {
                    continue;
                }
                String[] parts = delim.equals("\t") ? lines[li].split("\t") : lines[li].split(delim);
                if (field > 0 && field <= parts.length) {
                    sb.append(parts[field - 1]).append("\n");
                }
            }
            return sb.toString().trim();
        }
        if (cmd.equals("corax-help")) {
            return "Corax-Shell v5.0.0\n\n"
                + "内置命令: ls cat echo grep wc head tail date sleep\n"
                + "Corax命令: sed corax-edit corax-search corax-fetch corax-mem-create corax-mem-rm corax-mem-tag corax-mem-search corax-listen corax-reboot corax-snapshot-list corax-snapshot-restore corax-snapshot-rm\n"
                + "管道/重定向: | > >> &\n"
                + "文件系统: /proc/ /etc/ /dev/ /ctx/ /var/ /tmp/ /persist/ /src/\n"
                + "查阅 /persist/DevDocs.md 了解项目架构";
        }
        return cmd + ": 命令不存在。查看可用命令: corax-help";
    } catch (Exception e) {
        return cmd + ": " + e.getMessage();
    }
}

String formatMemList(List results, boolean isPublic) {
    if (results == null || results.isEmpty()) {
        return "(无)";
    }
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < results.size(); i++) {
        Map m = (Map) results.get(i);
        sb.append("#").append(m.get("id")).append(" ").append(m.get("content")).append("\n");
    }
    return sb.toString().trim();
}


// 消息总线注入 — onMsg 调用
void vfsPushMsgBus(String msgJson, String peerUin, int chatType) {
    String key = peerUin + "_" + chatType;
    List list = (List) msgBus.get(key);
    if (list == null) {
        list = java.util.Collections.synchronizedList(new ArrayList());
        msgBus.put(key, list);
    }
    list.add(msgJson);
    if (list.size() > 100) {
        list.remove(0);
    }
}




// ==================== Tool 辅助 ====================
String getToolArg(JSONObject tc, String key) {
    try { return new JSONObject(tc.getJSONObject("function").getString("arguments")).optString(key, ""); } catch (Exception e) { return ""; }
}

int getToolArgInt(JSONObject tc, String key) {
    try { return new JSONObject(tc.getJSONObject("function").getString("arguments")).optInt(key, -1); } catch (Exception e) { return -1; }
}

Map stripQuietFlag(String cmd) {
    String[] parts = cmd.split("\\s+");
    boolean quiet = false;
    StringBuilder clean = new StringBuilder();
    for (int i = 0; i < parts.length; i++) {
        if (parts[i].equals("--quiet")) {
            quiet = true;
        }
        else { if (clean.length() > 0) clean.append(" "); clean.append(parts[i]); }
    }
    Map result = new HashMap();
    result.put("cmd", clean.toString().trim());
    result.put("quiet", quiet);
    return result;
}

void sendDebug(String peerUin, int chatType, String text) { try { sendMsg(peerUin, "[DEBUG] " + text, chatType); } catch (Exception e) { } }

String listenLogPath(String peerUin, int chatType) {
    File dir = new File(pluginPath + "/config/listen_logs");
    if (!dir.exists()) {
        dir.mkdirs();
    }
    String key = (peerUin + "_" + chatType).replaceAll("[^0-9A-Za-z_-]", "_");
    return dir.getAbsolutePath() + "/" + key + ".jsonl";
}

void clearListenLog(String peerUin, int chatType) {
    try {
        File f = new File(listenLogPath(peerUin, chatType));
        if (f.exists()) {
            f.delete();
        }
    } catch (Exception e) { this.log("error.txt", "clearListenLog: " + e.getMessage()); }
}

void appendListenLog(String peerUin, int chatType, String senderUin, String senderName, String text, String quotedUin, String quotedText, String quotedMsgId, String msgId) {
    if (chatType != 2) {
        return;
    }
    try {
        JSONObject o = new JSONObject();
        o.put("time", getCurrentTime());
        o.put("ts", System.currentTimeMillis());
        o.put("peer", peerUin);
        o.put("sender", senderUin);
        o.put("name", senderName != null ? senderName : "");
        o.put("text", text != null ? text : "");
        o.put("msgId", msgId != null ? msgId : "");
        if (quotedUin != null && !quotedUin.isEmpty()) {
            o.put("quotedUin", quotedUin);
        }
        if (quotedText != null && !quotedText.isEmpty()) {
            o.put("quotedText", quotedText);
        }
        if (quotedMsgId != null && !quotedMsgId.isEmpty()) {
            o.put("quotedMsgId", quotedMsgId);
        }
        BufferedWriter bw = new BufferedWriter(new FileWriter(listenLogPath(peerUin, chatType), true));
        bw.write(o.toString());
        bw.newLine();
        bw.close();
    } catch (Exception e) { this.log("error.txt", "appendListenLog: " + e.getMessage()); }
}

String readListenLogForPrompt(String peerUin, int chatType, int maxChars) {
    File f = new File(listenLogPath(peerUin, chatType));
    if (!f.exists()) {
        return "";
    }
    List formattedLines = new ArrayList();
    int count = 0;
    try {
        BufferedReader br = new BufferedReader(new FileReader(f));
        String line;
        while ((line = br.readLine()) != null) {
            if (line.trim().isEmpty()) {
                continue;
            }
            JSONObject o = new JSONObject(line);
            StringBuilder entry = new StringBuilder();
            entry.append("[").append(o.optString("time", "")).append("] ");
            String name = o.optString("name", "");
            String sender = o.optString("sender", "");
            if (!name.isEmpty()) {
                entry.append(name).append("(").append(sender).append(")");
            }
            else {
                entry.append(sender);
            }
            if (!o.optString("quotedText", "").isEmpty()) {
                entry.append(" 引用 ").append(o.optString("quotedUin", "")).append(": ").append(o.optString("quotedText", "")).append(" | ");
            } else {
                entry.append(": ");
            }
            entry.append(o.optString("text", ""));
            formattedLines.add(entry.toString());
            count++;
        }
        br.close();
    } catch (Exception e) { this.log("error.txt", "readListenLog: " + e.getMessage()); }
    if (count == 0) {
        return "";
    }
    // 从尾部向前累加行，不超过 maxChars
    StringBuilder sb = new StringBuilder();
    boolean truncated = false;
    int totalLen = 0;
    for (int i = formattedLines.size() - 1; i >= 0; i--) {
        String entry = (String) formattedLines.get(i);
        int lineLen = entry.length() + 1;
        if (totalLen + lineLen > maxChars) {
            truncated = true;
            break;
        }
        sb.insert(0, entry + "\n");
        totalLen += lineLen;
    }
    if (truncated) {
        sb.insert(0, "[前部记录因长度限制已省略]\n");
    }
    return "共记录 " + count + " 条群聊消息。\n" + sb.toString();
}

void handleListenSummary(Object msg) {
    aiProcessing = true;
    try {
    String senderUin = String.valueOf(msg.userUin);
    String role = getRole(senderUin);
    if (!role.equals("ADMIN") && !role.equals("OWNER")) {
        sendPermissionDenied(msg);
        return;
    }
    String peerUin = String.valueOf(msg.peerUin);
    int chatType = msg.type;
    if (chatType != 2) {
        sendStyledHeader(msg, "ERROR", "监听总结仅支持群聊");
        return;
    }
    if (listenSessions == null) {
        listenSessions = readStringSet(pluginPath + "/config/listen_sessions.txt");
    }
    if (!listenSessions.contains(peerUin + "_" + chatType)) {
        sendStyledHeader(msg, "ERROR", "当前群未开启监听，无法总结");
        return;
    }
    Map cfg = loadAiConfig();
    if (((String) cfg.get("api_key")).isEmpty()) {
        sendStyledHeader(msg, "ERROR", "AI 未配置 api_key");
        return;
    }
    String logText = readListenLogForPrompt(peerUin, chatType, 30000);
    if (logText.isEmpty()) {
        sendStyledHeader(msg, "INFO", "监听期间暂无可总结的群聊记录");
        clearListenLog(peerUin, chatType);
        return;
    }
    JSONArray msgs = new JSONArray();
    JSONObject u = new JSONObject();
    u.put("role", "user");
    u.put("content", logText);
    msgs.put(u);
    String sp = "你是群聊记录总结器。请基于用户提供的监听期群聊记录，输出简洁中文总结。必须包含：主要话题、重要结论、待办/约定、出现的链接或资源、需要后续确认的点。不要编造记录中没有的信息。";
    Map r = callAI("", sp, msgs, 4096, null);
    if (r == null) {
        sendStyledHeader(msg, "ERROR", "AI 服务暂时不可用，监听记录未删除");
        return;
    }
    String content = (String) r.getOrDefault("content", "");
    if (content == null || content.trim().isEmpty()) {
        sendStyledHeader(msg, "ERROR", "总结为空，监听记录未删除");
        return;
    }
    if (content.trim().length() < 20) {
        sendStyledHeader(msg, "ERROR", "总结内容过短，监听记录未删除");
        return;
    }
    if ("1".equals(getAiConfig("ai_prefix"))) {
        content = "[AI] " + content.trim();
    }
    sendMsg(peerUin, content, chatType);
    clearListenLog(peerUin, chatType);
    } finally {
        aiProcessing = false;
    }
}

void executeMemoryCall(JSONObject tc, String fname, String senderUin, String userRole, String peerUin, int chatType, String sourceMsgId, String sourceText, long sourceTimeMs) {
    try {
        if (fname.equals("create_memory")) {
            String content = getToolArg(tc, "content"); String tags = getToolArg(tc, "tags"); String about = getToolArg(tc, "about");
            if (content.isEmpty()) {
                return;
            }
            String su = about.isEmpty() ? senderUin : about;
            storeMemoryWithSource(senderUin, content, tags, "private", su, peerUin, chatType, sourceMsgId, sourceText, senderUin, sourceTimeMs);
        } else if (fname.equals("create_public_memory")) {
            String content = getToolArg(tc, "content"); String tags = getToolArg(tc, "tags"); String about = getToolArg(tc, "about");
            if (content.isEmpty()) {
                return;
            }
            String su = about.isEmpty() ? senderUin : about;
            storeMemoryWithSource(senderUin, content, tags, "public", su, peerUin, chatType, sourceMsgId, sourceText, senderUin, sourceTimeMs);
        } else if (fname.equals("overwrite_memory")) {
            int id = getToolArgInt(tc, "id"); String content = getToolArg(tc, "content"); String tags = getToolArg(tc, "tags");
            if (id <= 0 || content.isEmpty()) {
                return;
            }
            int oldW = 1; String origSubject = senderUin;
            Cursor c = null;
            try {
                c = getDb().rawQuery("SELECT weight, subject_uin FROM memories WHERE id=?", new String[]{String.valueOf(id)});
                if (c.moveToFirst()) {
                    oldW = c.getInt(0);
                    String s = c.getString(1);
                    if (s != null && !s.isEmpty()) {
                        origSubject = s;
                    }
                }
            } catch (Exception e) { }
            finally { if (c != null) c.close(); }
            deleteMemoryById(id, senderUin, userRole);
            storeMemoryWithSource(senderUin, content, tags, "private", origSubject, peerUin, chatType, sourceMsgId, sourceText, senderUin, sourceTimeMs);
            Cursor last = null;
            try {
                last = getDb().rawQuery("SELECT id FROM memories WHERE uin=? AND scope='private' ORDER BY id DESC LIMIT 1", new String[]{senderUin});
                if (last.moveToFirst()) {
                    long lastId = last.getLong(0);
                    getDb().execSQL("UPDATE memories SET weight=? WHERE id=?", new Object[]{oldW + 1, lastId});
                }
            } catch (Exception e) { }
            finally { if (last != null) last.close(); }
        } else if (fname.equals("overwrite_public_memory")) {
            int id = getToolArgInt(tc, "id"); String content = getToolArg(tc, "content"); String tags = getToolArg(tc, "tags");
            if (id <= 0 || content.isEmpty()) {
                return;
            }
            int oldW = 1; String origSubject = senderUin;
            Cursor c = null;
            try {
                c = getDb().rawQuery("SELECT weight, subject_uin FROM memories WHERE id=?", new String[]{String.valueOf(id)});
                if (c.moveToFirst()) {
                    oldW = c.getInt(0);
                    String s = c.getString(1);
                    if (s != null && !s.isEmpty()) {
                        origSubject = s;
                    }
                }
            } catch (Exception e) { }
            finally { if (c != null) c.close(); }
            deleteMemoryById(id, senderUin, userRole);
            storeMemoryWithSource(senderUin, content, tags, "public", origSubject, peerUin, chatType, sourceMsgId, sourceText, senderUin, sourceTimeMs);
            Cursor last = null;
            try {
                last = getDb().rawQuery("SELECT id FROM memories WHERE scope='public' ORDER BY id DESC LIMIT 1", null);
                if (last.moveToFirst()) {
                    int lastId = last.getInt(0);
                    getDb().execSQL("UPDATE memories SET weight=" + (oldW + 1) + " WHERE id=" + lastId);
                }
            } catch (Exception e) { }
            finally { if (last != null) last.close(); }
        } else if (fname.equals("delete_memory")) { int id = getToolArgInt(tc, "id"); if (id > 0) deleteMemoryById(id, senderUin, userRole); }
    } catch (Exception e) { this.log("error.txt", "execMem: " + e.getMessage()); }
}
// ==================== 命令处理 ====================
void handleAiMemory(Object msg, String args) {
    String senderUin = String.valueOf(msg.userUin);
    String userRole = getRole(senderUin);
    String[] parts = args.split("\\s+");
    String sub = (parts.length > 0) ? parts[0] : "";
    if (sub.isEmpty()) {
        List my = getMyMemories(senderUin, 15);
        List pub = getPublicMemories(15);
        Map pool = getTagPool(senderUin);
        StringBuilder sb2 = new StringBuilder();
        sb2.append("[记忆] 公有" + pub.size() + "条, 私有" + my.size() + "条");
        if (!pool.isEmpty()) {
            sb2.append("\n[标签] ");
            int c = 0;
            for (Object e : pool.entrySet()) {
                Map.Entry en = (Map.Entry) e;
                if (c > 0) {
                    sb2.append(", ");
                }
                sb2.append(en.getKey()).append("(").append(en.getValue()).append(")");
                if (++c >= 15) {
                    break;
                }
            }
        }
        if (!pub.isEmpty()) {
            sb2.append("\n-- 公有 --\n");
            for (int i = 0; i < pub.size(); i++) {
                Map m = (Map) pub.get(i);
                sb2.append("#").append(m.get("id")).append(" ").append(m.get("content")).append("\n");
            }
        }
        if (!my.isEmpty()) {
            sb2.append("-- 私有 --\n");
            for (int i = 0; i < my.size(); i++) {
                Map m = (Map) my.get(i);
                sb2.append("#").append(m.get("id")).append(" ").append(m.get("content")).append("\n");
            }
        }
        if (pub.isEmpty() && my.isEmpty()) {
            sb2.append("\n暂无记忆");
        }
        sendStyledHeader(msg, "INFO", sb2.toString());
        return;
    }
    if (sub.equals("info")) {
        if (parts.length < 2) {
            sendStyledHeader(msg, "ERROR", "用法: /ai memory info <id>");
            return;
        }
        long id;
        try { id = Long.parseLong(parts[1].replace("#", "")); }
        catch (Exception e) { sendStyledHeader(msg, "ERROR", "id 必须是数字"); return; }
        Map m = getMemoryDetail(id);
        if (m == null) {
            sendStyledHeader(msg, "INFO", "没有找到 #" + id);
            return;
        }
        if (!canViewMemoryDetail(m, senderUin, userRole)) {
            sendPermissionDenied(msg);
            return;
        }
        String recordUin = (String) m.get("uin");
        String recordRole = getRole(recordUin);
        String subject = (String) m.get("subjectUin");
        if (subject == null || subject.isEmpty()) {
            subject = recordUin;
        }
        String subjectRole = getRole(subject);
        String assertionType = calcAssertionType(recordUin, subject);

        StringBuilder sb = new StringBuilder();
        sb.append("[记忆详情] #").append(m.get("id")).append("\n");
        sb.append("范围: ").append(m.get("scope")).append("\n");
        sb.append("记录者: ").append(recordUin).append("(").append(recordRole).append(")\n");
        sb.append("about: ").append(subject).append("(").append(subjectRole).append(")\n");
        sb.append("陈述类型: ").append(assertionTypeLabel(assertionType)).append("\n");
        sb.append("记得: ").append(m.get("content")).append("\n");
        sb.append("标签: ").append(m.get("tags")).append("\n");
        sb.append("可信度: ").append(m.get("credibility")).append(" 权重: ").append(m.get("weight"));
        if (Integer.parseInt(String.valueOf(m.get("pinned"))) == 1) {
            sb.append(" 已置顶");
        }
        sb.append("\n创建: ").append(fmtTime(Long.parseLong(String.valueOf(m.get("createdAt")))));
        sb.append("\n最近命中: ").append(fmtTime(Long.parseLong(String.valueOf(m.get("accessedAt")))));
        String st = (String) m.get("sourceText");
        if (st != null && !st.isEmpty()) {
            if (st.length() > 600) {
                st = st.substring(0, 600) + "...";
            }
            sb.append("\n来源原文: ").append(st);
        }
        sendStyledHeader(msg, "INFO", sb.toString());
        return;
    }
    if (sub.equals("pin")) {
        if (parts.length < 2) {
            sendStyledHeader(msg, "ERROR", "用法: /ai memory pin <id>");
            return;
        }
        try {
            long id = Long.parseLong(parts[1]);
            Cursor c = getDb().rawQuery("SELECT pinned FROM memories WHERE id=?", new String[]{String.valueOf(id)});
            int cur = 0;
            if (c.moveToFirst()) {
                cur = c.getInt(0);
            }
            c.close();
            int nv = cur == 1 ? 0 : 1;
            ContentValues cv = new ContentValues();
            cv.put("pinned", nv);
            getDb().update("memories", cv, "id=?", new String[]{String.valueOf(id)});
            sendStyledHeader(msg, "SUCCESS", "#" + id + (nv == 1 ? " 已置顶" : " 已取消置顶"));
        } catch (Exception e) { sendStyledHeader(msg, "ERROR", "id 必须是数字"); }
        return;
    }
    if (sub.equals("search")) {
        if (parts.length < 2) {
            sendStyledHeader(msg, "ERROR", "用法: /ai memory search <kw|tag:x>");
            return;
        }
        String kw = parts[1];
        List found = kw.startsWith("tag:") ? searchMemoriesByTag(senderUin, kw.substring(4)) : searchMemories(senderUin, kw);
        if (found.isEmpty()) {
            sendStyledHeader(msg, "INFO", "没有匹配 \"" + kw + "\"");
        }
        else {
            StringBuilder sb = new StringBuilder();
            sb.append("[搜索 \"").append(kw).append("\"] ").append(found.size()).append(" 条:\n");
            for (int i = 0; i < found.size(); i++) {
                Map m = (Map) found.get(i);
                sb.append("#").append(m.get("id")).append(" ").append(m.get("content")).append("\n");
            }
            sendStyledHeader(msg, "INFO", sb.toString());
        }
        return;
    }
    if (sub.equals("set")) {
        if (parts.length < 2) {
            sendStyledHeader(msg, "ERROR", "用法: /ai memory set [tags:x,y] <内容>");
            return;
        }
        String tags = "";
        int cs = 1;
        if (parts[1].startsWith("tags:")) {
            tags = parts[1].substring(5);
            cs = 2;
        }
        if (cs >= parts.length) {
            sendStyledHeader(msg, "ERROR", "缺少内容");
            return;
        }
        StringBuilder ct = new StringBuilder();
        for (int i = cs; i < parts.length; i++) {
            if (i > cs) {
                ct.append(" ");
            }
            ct.append(parts[i]);
        }
        boolean ok = storeMemoryWithSource(senderUin, ct.toString(), tags, "private", senderUin, String.valueOf(msg.peerUin), msg.type, String.valueOf(msg.msgId), String.valueOf(msg.msg), senderUin, getMsgTimeMs(msg));
        if (ok) {
            sendStyledHeader(msg, "SUCCESS", "已添加: " + ct.toString());
        } else {
            sendStyledHeader(msg, "ERROR", "添加失败");
        }
        return;
    }
    if (sub.equals("rm")) {
        if (parts.length < 2) {
            sendStyledHeader(msg, "ERROR", "用法: /ai memory rm <id>");
            return;
        }
        long id;
        try {
            id = Long.parseLong(parts[1]);
        } catch (Exception e) {
            sendStyledHeader(msg, "ERROR", "id 必须是数字");
            return;
        }
        boolean ok = deleteMemoryById(id, senderUin, userRole);
        if (ok) {
            sendStyledHeader(msg, "SUCCESS", "已删除 #" + id);
        } else {
            sendStyledHeader(msg, "ERROR", "删除失败");
        }
        return;
    }
    if (sub.equals("reset")) {
        if (!userRole.equals("OWNER")) {
            sendStyledHeader(msg, "ERROR", "权限不足");
            return;
        }
        try { getDb().delete("memories", null, null); getDb().delete("tag_pool", null, null); getDb().delete("sqlite_sequence", "name='memories'", null); tagPoolCache = null; } catch (Exception e) { }
        sendStyledHeader(msg, "SUCCESS", "已清空全部记忆"); return;
    }
    if (sub.equals("public")) {
        if (parts.length < 2) {
            List pub = getPublicMemories(30);
            Map pubPool = getPublicTagPool();
            StringBuilder sb = new StringBuilder();
            sb.append("[公有记忆] ");
            sb.append(pub.size());
            sb.append(" 条");
            if (!pubPool.isEmpty()) {
                sb.append("\n[标签] ");
                int c = 0;
                for (Object e : pubPool.entrySet()) {
                    Map.Entry en = (Map.Entry) e;
                    if (c > 0) {
                        sb.append(", ");
                    }
                    sb.append(en.getKey());
                    sb.append("(");
                    sb.append(en.getValue());
                    sb.append(")");
                    c++;
                    if (c >= 15) {
                        break;
                    }
                }
            }
            if (pub.isEmpty()) {
                sb.append("\n暂无");
            } else {
                sb.append("\n");
                for (int i = 0; i < pub.size(); i++) {
                    Map m = (Map) pub.get(i);
                    String t = (String) m.get("tags");
                    sb.append("#");
                    sb.append(m.get("id"));
                    if (t != null && !t.isEmpty()) {
                        sb.append(" [");
                        sb.append(t);
                        sb.append("]");
                    }
                    sb.append(" ");
                    sb.append(m.get("content"));
                    sb.append("\n");
                }
            }
            sendStyledHeader(msg, "INFO", sb.toString());
            return;
        }

        if (parts[1].equals("set")) {
            if (!userRole.equals("ADMIN") && !userRole.equals("OWNER")) {
                sendStyledHeader(msg, "ERROR", "权限不足");
                return;
            }
            String tags = "";
            int cs = 2;
            if (parts.length > 2 && parts[2].startsWith("tags:")) {
                tags = parts[2].substring(5);
                cs = 3;
            }
            if (cs >= parts.length) {
                sendStyledHeader(msg, "ERROR", "用法: /ai memory public set [tags:x,y] <内容>");
                return;
            }
            StringBuilder ct = new StringBuilder();
            for (int i = cs; i < parts.length; i++) {
                if (i > cs) {
                    ct.append(" ");
                }
                ct.append(parts[i]);
            }
            boolean ok = storeMemoryWithSource(senderUin, ct.toString(), tags, "public", senderUin, String.valueOf(msg.peerUin), msg.type, String.valueOf(msg.msgId), String.valueOf(msg.msg), senderUin, getMsgTimeMs(msg));
            if (ok) {
                sendStyledHeader(msg, "SUCCESS", "已添加公有记忆");
            } else {
                sendStyledHeader(msg, "ERROR", "添加失败");
            }
            return;
        }

        if (parts[1].equals("rm")) {
            if (!userRole.equals("ADMIN") && !userRole.equals("OWNER")) {
                sendStyledHeader(msg, "ERROR", "权限不足");
                return;
            }
            if (parts.length < 3) {
                sendStyledHeader(msg, "ERROR", "用法: /ai memory public rm <id>");
                return;
            }
            long id;
            try {
                id = Long.parseLong(parts[2]);
            } catch (Exception e) {
                sendStyledHeader(msg, "ERROR", "id 必须是数字");
                return;
            }
            boolean ok = deleteMemoryById(id, senderUin, userRole);
            if (ok) {
                sendStyledHeader(msg, "SUCCESS", "已删除");
            } else {
                sendStyledHeader(msg, "ERROR", "删除失败");
            }
            return;
        }

        sendStyledHeader(msg, "ERROR", "用法: /ai memory public [set|rm]");
        return;
    }
    if (sub.equals("all")) {
        if (!userRole.equals("ADMIN") && !userRole.equals("OWNER")) {
            sendStyledHeader(msg, "ERROR", "权限不足: /ai memory all 仅管理员可用");
            return;
        }
        StringBuilder sb2 = new StringBuilder();
        sb2.append("[全部记忆]\n");
        List allPub = getPublicMemories(100);
        sb2.append("-- 公有(");
        sb2.append(allPub.size());
        sb2.append(") --\n");
        for (int i = 0; i < allPub.size(); i++) {
            Map m = (Map) allPub.get(i);
            sb2.append("#").append(m.get("id")).append(" [").append(m.get("tags")).append("] ").append(m.get("content")).append("\n");
        }

        Cursor c = getDb().rawQuery("SELECT id, content, tags, uin FROM memories WHERE scope='private' ORDER BY uin, accessed_at DESC LIMIT 100", null);
        sb2.append("-- 私有(全用户) --\n");
        boolean hasPriv = false;
        while (c.moveToNext()) {
            hasPriv = true;
            sb2.append("#").append(c.getLong(0)).append(" [UIN:").append(c.getString(3)).append("] [").append(c.getString(2)).append("] ").append(c.getString(1)).append("\n");
        }
        c.close();
        if (!hasPriv) {
            sb2.append("(无)\n");
        }
        sendStyledHeader(msg, "INFO", sb2.toString());
        return;
    }
    sendStyledHeader(msg, "ERROR", "未知: /ai memory " + sub);
}

void handleAiSet(Object msg, String args) {
    String role = getRole(String.valueOf(msg.userUin));
    if (!role.equals("ADMIN") && !role.equals("OWNER")) {
        sendStyledHeader(msg, "ERROR", "权限不足");
        return;
    }
    String[] parts = args.split("\\s+", 2);
    if (parts.length < 2) {
        sendStyledHeader(msg, "ERROR", "用法: /ai set <key> <value>");
        return;
    }
    String key = parts[0].trim(); String value = parts[1].trim();
    String[] vk = { "api_key","model","ai_url","context_ttl","context_limit","search_provider","search_api_key","show_stats","debug","ai_prefix","shell_rounds","temperature","pat_wake","sewarden" };
    boolean valid = false; for (int i = 0; i < vk.length; i++) if (vk[i].equals(key)) { valid = true; break; }
    if (!valid) {
        sendStyledHeader(msg, "ERROR", "无效: " + key);
        return;
    }
    if (key.equals("context_ttl") || key.equals("context_limit") || key.equals("show_stats") || key.equals("debug") || key.equals("pat_wake")) {
        try { Integer.parseInt(value); } catch (Exception e) { sendStyledHeader(msg, "ERROR", "必须是整数"); return; }
    }
    if (key.equals("temperature")) { try { double d = Double.parseDouble(value); if (d < 0 || d > 2) { sendStyledHeader(msg, "ERROR", "temperature 0~2"); return; } } catch (Exception e) { sendStyledHeader(msg, "ERROR", "必须是小数"); return; } }
    Map cfg = loadAiConfig(); cfg.put(key, value); saveAiConfig(cfg);
    sendStyledHeader(msg, "INFO", "已更新: " + key);
}

void handleAiConfig(Object msg) {
    if (!requireAdminOrOwner(msg)) { return; }
    Map cfg = loadAiConfig();
    StringBuilder sb = new StringBuilder("[AI 配置]\n");
    String[] keys = { "model","api_key","ai_url","context_ttl","context_limit","search_provider","search_api_key","shell_rounds","show_stats","debug","ai_prefix","temperature","pat_wake","sewarden" };
    for (int i = 0; i < keys.length; i++) {
        String k = keys[i];
        String v = (String) cfg.get(k);
        if (v == null) {
            v = "";
        }
        if (k.contains("api_key") && v.length() >= 8) {
            v = maskApiKey(v);
        }
        sb.append(k).append(" = ").append(v).append("\n");
    }
    sb.append("default_account = ").append(getDefaultAccount()).append("\n");
    String persona = loadPersona(); sb.append("人设 = ").append(getActivePersona()).append(persona.isEmpty() ? " (未)" : " (" + persona.length() + "字符)").append("\n");
    List ww = loadWakeWords(); sb.append("唤醒词 = ").append(ww.isEmpty() ? "(无)" : ""); for (int i = 0; i < ww.size(); i++) { if (i > 0) sb.append(","); sb.append(ww.get(i)); }
    sendStyledHeader(msg, "INFO", sb.toString());
}

void handleAiForget(Object msg, String keyword) {
    String senderUin = String.valueOf(msg.userUin);
    if (keyword == null || keyword.trim().isEmpty()) {
        sendStyledHeader(msg, "ERROR", "用法: /ai forget <关键词>");
        return;
    }
    int d = deleteMemoriesByKeyword(senderUin, keyword.trim());
    sendStyledHeader(msg, "INFO", d > 0 ? "已删除 " + d + " 条" : "没有匹配的记忆");
}

String maskApiKey(String key) {
    if (key == null || key.length() < 8) {
        return "(未设置)";
    }
    return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
}

int getMemoryCount(String uin) {
    Cursor c = null;
    try {
        c = getDb().rawQuery("SELECT COUNT(*) FROM memories WHERE uin=? AND scope='private'", new String[]{uin});
        if (c.moveToFirst()) {
            return c.getInt(0);
        }
    } catch (Exception e) { }
    finally { if (c != null) c.close(); }
    return 0;
}
int getPublicMemoryCount() {
    Cursor c = null;
    try {
        c = getDb().rawQuery("SELECT COUNT(*) FROM memories WHERE scope='public'", null);
        if (c.moveToFirst()) {
            return c.getInt(0);
        }
    } catch (Exception e) { }
    finally { if (c != null) c.close(); }
    return 0;
}

// ==================== 消息工具 ====================
void sendStyledHeader(Object msg, String status, String msgText) {
    StringBuilder sb = new StringBuilder();
    sb.append("[").append(status).append("] ").append(getCurrentTime()).append("\n");
    sb.append("[AI] ").append(msgText);
    sendMsg(String.valueOf(msg.peerUin), sb.toString(), msg.type);
}

void sendPermissionDenied(Object msg) { sendStyledHeader(msg, "ERROR", "权限不足"); }

boolean requireAdminOrOwner(Object msg) {
    String role = getRole(String.valueOf(msg.userUin));
    if (!role.equals("ADMIN") && !role.equals("OWNER")) {
        sendPermissionDenied(msg);
        return false;
    }
    return true;
}

String extractTargetUin(Object msg, String arg) {
    if (msg == null) {
        return null;
    }
    if (msg.atList != null && msg.atList.size() > 0) {
        for (int i = 0; i < msg.atList.size(); i++) {
            String at = String.valueOf(msg.atList.get(i));
            if (!at.equals(myUin)) {
                return at;
            }
        }
    }
    if (arg != null && arg.trim().matches("\\d{5,15}")) {
        return arg.trim();
    }
    return null;
}

boolean isNumeric(String s) { return s != null && s.matches("[0-9]+"); }

// ==================== 生命周期 ====================
public void onDestroy() {
    if (delayTimer != null) {
        delayTimer.cancel();
        delayTimer.purge();
        delayTimer = null;
    }
    for (Object key : aiContexts.keySet()) {
        try {
            String[] parts = ((String) key).split("_");
            if (parts.length == 2) {
                saveCtxToDisk(parts[0], Integer.parseInt(parts[1]));
            }
        } catch (Exception ignored) { }
    }
    aiContexts.clear();
    closeSharedDb();
}

// ==================== 拍一拍 ====================
public void onPaiYiPai(String peerUin, int chatType, String operatorUin) {
    if (!"1".equals(getAiConfig("pat_wake"))) {
        return;
    }
    if (operatorUin == null) {
        return;
    }
    if (aiProcessing) {
        return;
    }

    patOperatorUin = operatorUin;
    patPeerUin = peerUin;

    handleAi(new Object() {
        public String userUin = patOperatorUin;
        public String peerUin = patPeerUin;
        public int type = 2;
        public long msgId = 0;
        public String msg = "";
        public Object data = null;
        public List atList = null;
    }, "[UIN:" + operatorUin + "][" + getCurrentTime() + "] 拍了拍你");
}

// ==================== 路由 ====================
public void onMsg(Object msg) {
    onMainThread++;
    try {
    if (msg == null) {
        return;
    }

    // 检查并执行到期延时任务
    // 轮询兜底：检查到期延时任务（Timer已触发的跳过）
    long nowMs = System.currentTimeMillis();
    synchronized (delayedTasks) {
        for (int di = 0; di < delayedTasks.size(); di++) {
            Map dtask = (Map) delayedTasks.get(di);
            if (Boolean.TRUE.equals(dtask.get("fired"))) {
                delayedTasks.remove(di);
                di--;
                continue;
            }
            long at = Long.parseLong(String.valueOf(dtask.get("at")));
            if (nowMs >= at) {
                int[] ix = new int[]{0};
                List dtoks = (List) dtask.get("tokens");
                String dsu = (String) dtask.get("su");
                String dpu = (String) dtask.get("pu");
                int dct = Integer.parseInt(String.valueOf(dtask.get("ct")));
                try { parseSequence(dtoks, ix, "", dsu, dpu, dct); } catch (Exception e) {}
                delayedTasks.remove(di);
                di--;
            }
        }
    }

    // 兜底：检查 pendingApprovals 是否超时（防止 Timer 不触发导致永久卡住）
    synchronized (pendingApprovals) {
        java.util.Iterator pit = pendingApprovals.entrySet().iterator();
        while (pit.hasNext()) {
            Map.Entry pe = (Map.Entry) pit.next();
            Map pop = (Map) pe.getValue();
            long pts = Long.parseLong(String.valueOf(pop.get("ts")));
            if (nowMs - pts > 35000) {
                // 超时：设结果并唤醒
                pop.put("result", "timeout");
                Object plock = pop.get("lock");
                if (plock != null) {
                    synchronized (plock) {
                        plock.notifyAll();
                    }
                }
                Timer ptm = (Timer) pop.get("timer");
                if (ptm != null) {
                    try { ptm.cancel(); } catch (Exception ex) { }
                }
                pit.remove();
            }
        }
    }

    // 排空 daemon 输出队列（主线程安全发送，去重防刷屏）
    Set sentCache = new HashSet();
    int sent = 0;
    while (!daemonOutQueue.isEmpty() && sent < 3) {
        String item = (String) daemonOutQueue.remove(0);
        if (!sentCache.add(item)) {
            continue; // 去重
        }
        String[] parts = item.split("\\|", 3);
        if (parts.length == 3) {
            String outMsg = "[Output] " + parts[2];
            lastAssistantMsg = outMsg;
            sendMsg(parts[0], outMsg, Integer.parseInt(parts[1]));
            // 持久化到 ctx，让 AI 回头看时可见
            List dctx = getAiContext(parts[0], Integer.parseInt(parts[1]));
            if (dctx != null) {
                Map dm = new HashMap();
                dm.put("role", "system");
                dm.put("content", "<t>" + getCurrentTime() + "</t><output>" + parts[2] + "</output>");
                dm.put("_ts", System.currentTimeMillis());
                dctx.add(dm);
            }
            sent++;
        }
    }
    if (!daemonOutQueue.isEmpty()) {
        daemonOutQueue.clear(); // 清空剩余;
    }
    
    // 消息队列：正在处理消息时缓存新消息，不丢弃
    if (aiProcessing) {
        if (msgQueue.size() >= MSG_QUEUE_MAX) {
            msgQueue.poll();
        }
        msgQueue.offer(msg);
        return;
    }
    // v3.0: 冷启动预热，静默初始化内部状态
    if (!aiReady) {
        getDb();
        loadAiConfig();
        aiReady = true;
    }

    String text = msg.msg;
    if (text == null) {
        return;
    }
    String senderUin = String.valueOf(msg.userUin);
    // 系统消息保护：UIN 无效时跳过处理
    if (senderUin == null || senderUin.isEmpty() || senderUin.equals("0") || senderUin.equals("null")) {
        aiProcessing = false; return;
    }
    String peerUin = String.valueOf(msg.peerUin);
    int chatType = msg.type;
    String msgJson = "{\"from\":\"" + senderUin + "\",\"text\":\""
        + text.replace("\"", "\\\"").replace("\n", " ").substring(0, Math.min(text.length(), 200))
        + "\",\"to\":\"" + peerUin + "\",\"type\":" + chatType + ",\"time\":\"" + getCurrentTime() + "\"}";
    vfsPushMsgBus(msgJson, peerUin, chatType);
    String trimmed = text.trim();

    // SEWarden: 清洗用户输入中的系统标签
    trimmed = sewardenClean(trimmed);

    // 操作审批：允许在 AI 处理中响应，越过 aiProcessing 拦截
    if (trimmed.equals("/ai operation permit")) {
        handleOperationApproval(msg, true);
        return;
    }
    if (trimmed.equals("/ai operation reject")) {
        handleOperationApproval(msg, false);
        return;
    }

    if (trimmed.startsWith("/ai")) {
        if (aiProcessing) {
            return;
        }
        String aiArg = trimmed.length() > 3 ? trimmed.substring(3).trim() : "";
        if (aiArg.isEmpty()) {
            sendStyledHeader(msg, "ERROR", "/ai <内容> / memory / debug / reboot / set / config / forget / off / on / status");
            return;
        }
        handleAi(msg, aiArg); return;
    }
    if (!aiProcessing && startsWithWakeWord(trimmed)) {
        handleAi(msg, trimmed); return;
    }
    if (trimmed.startsWith("/setdefaultaccount")) {
        if (!getRole(senderUin).equals("OWNER")) {
            sendPermissionDenied(msg);
            return;
        }
        String arg = trimmed.length() > 19 ? trimmed.substring(19).trim() : "";
        if (arg.startsWith("/")) {
            arg = arg.substring(1).trim();
        }
        if (arg.isEmpty() || (!arg.equals("member") && !arg.equals("blocked"))) {
            sendStyledHeader(msg, "ERROR", "/setdefaultaccount member/blocked");
            return;
        }
        setDefaultAccountConfig(arg);
        sendStyledHeader(msg, "INFO", "已设置: " + arg); return;
    }

    // v4.0: 监听模式 — 只记录不调用 AI
    if (listenSessions == null) {
        listenSessions = readStringSet(pluginPath + "/config/listen_sessions.txt");
    }
    if (!aiProcessing && !trimmed.startsWith("/")
        && listenSessions.contains(peerUin + "_" + chatType)) {

        // 回显检测：跳过 AI 自己发出去的消息回显
        if (senderUin.equals(myUin) && lastAssistantMsg != null && !lastAssistantMsg.isEmpty()
            && (trimmed.equals(lastAssistantMsg) || trimmed.endsWith(lastAssistantMsg))) {
            return;
        }
        
        // 判断是否被唤醒：@AI、唤醒词
        boolean isWakeUp = false;
        if (msg.atList != null && msg.atList.contains(myUin)) {
            isWakeUp = true;
        }
        if (!isWakeUp && startsWithWakeWord(trimmed)) {
            isWakeUp = true;
        }

        // 未被唤醒 → 只记录到 ctx，不调用 AI
        String senderName = getMemberName(chatType, peerUin, senderUin);
        senderName = senderName.replaceAll("[{｛].*?[}｝]", "")
                               .replaceAll("[<＜].*?[>＞]", "")
                               .replaceAll("【.*?】", "")
                               .replaceAll("\\[.*?\\]", "")
                               .replaceAll("[,，:：;；]", "")
                               .trim();
        String userRole = getRole(senderUin);

        // 获取引用信息（如果有）
        String quotedText = "";
        String quotedUin = "";
        String quotedMsgId = "";
        try {
            Object msgData = msg.data;
            if (msgData != null) {
                java.util.List elements = (java.util.List) msgData.getClass().getDeclaredField("elements").get(msgData);
                if (elements != null) {
                    for (int ei = 0; ei < elements.size(); ei++) {
                        Object el = elements.get(ei);
                        java.lang.reflect.Field rf = el.getClass().getDeclaredField("replyElement");
                        rf.setAccessible(true);
                        Object re = rf.get(el);
                        if (re != null) {
                            String ruin = "";
                            try {
                                java.lang.reflect.Field sf = re.getClass().getDeclaredField("senderUin");
                                sf.setAccessible(true);
                                Object su = sf.get(re);
                                if (su != null && !su.toString().isEmpty()) {
                                    ruin = su.toString();
                                }
                            } catch (Exception ex2) { }
                            quotedUin = ruin;
                            try {
                                java.lang.reflect.Field sf = re.getClass().getDeclaredField("sourceMsgText");
                                sf.setAccessible(true);
                                Object src = sf.get(re);
                                if (src != null && !src.toString().isEmpty()) { quotedText = sewardenClean(src.toString()); }
                            } catch (Exception ex2) { }
                            try {
                                java.lang.reflect.Field mf = re.getClass().getDeclaredField("sourceMsgId");
                                mf.setAccessible(true);
                                Object mid = mf.get(re);
                                if (mid != null && !mid.toString().isEmpty()) {
                                    quotedMsgId = mid.toString();
                                }
                            } catch (Exception ex3) { }
                            break;
                        }
                    }
                }
            }
        } catch (Exception ignored) { }

        appendListenLog(peerUin, chatType, senderUin, senderName, trimmed, quotedUin, quotedText, quotedMsgId, String.valueOf(msg.msgId));

        // 判断是否被唤醒：@AI、唤醒词
        boolean isWakeUp = false;
        if (msg.atList != null && msg.atList.contains(myUin)) {
            isWakeUp = true;
        }
        if (!isWakeUp && startsWithWakeWord(trimmed)) {
            isWakeUp = true;
        }

        if (isWakeUp) {
            // 被唤醒 → 调用 handleAi（handleAi 内部会注入 <wake />）
            handleAi(msg, trimmed);
            return;
        }

        List lctx = getAiContext(peerUin, chatType);

        // 如果有引用，注入被引用者身份 + 被引用原文
        if (!quotedText.isEmpty() && !quotedUin.isEmpty()) {
            String quotedRole = getRole(quotedUin);
            String quotedName = getMemberName(chatType, peerUin, quotedUin);
            quotedName = quotedName.replaceAll("[<{＜【\\[（(].*?[>}＞】\\]）)]", "")
                                   .replaceAll("[,，:：;；]", "").trim();
            if (quotedName.isEmpty()) {
                quotedName = quotedUin;
            }
            
            Map m1 = new HashMap();
            m1.put("role", "system");
            m1.put("content", "<t>" + getCurrentTime() + "</t><s><user uin=\"" + quotedUin + "\" access=\"" + quotedRole + "\" display=\"" + quotedName + "\" /></s>");
            m1.put("_ts", System.currentTimeMillis());
            lctx.add(m1);

            Map m2 = new HashMap();
            m2.put("role", "system");
            m2.put("content", "<t>" + getCurrentTime() + "</t><quote><quoter_uid>" + quotedUin + "</quoter_uid><quoter_time>" + getCurrentTime() + "</quoter_time><quote_content>" + quotedText + "</quote_content></quote>");
            m2.put("_ts", System.currentTimeMillis());
            lctx.add(m2);
        }

        // 注入当前发言者身份
        Map m3 = new HashMap();
        m3.put("role", "system");
        m3.put("content", "<t>" + getCurrentTime() + "</t><s><user uin=\"" + senderUin + "\" access=\"" + userRole + "\" display=\"" + senderName + "\" /></s>");
        m3.put("_ts", System.currentTimeMillis());
        lctx.add(m3);

        // 记录用户消息
        Map m4 = new HashMap();
        m4.put("role", "user");
        m4.put("name", senderUin);
        m4.put("content", "<t>" + getCurrentTime() + "</t><u>" + trimmed + "</u>");
        m4.put("_ts", System.currentTimeMillis());
        lctx.add(m4);


        saveCtxToDisk(peerUin, chatType);
        return;
    }
    // 唤醒词路由
    if (!aiProcessing && !trimmed.startsWith("/") && startsWithWakeWord(trimmed)) {
        if (!readStringSet(pluginPath + "/config/enabled_conversations.txt").contains(peerUin + "_" + chatType)) {
            return;
        }
        handleAi(msg, trimmed);
        return;
    }
    if (!trimmed.startsWith("/") || trimmed.length() < 2) {
        return;
    }
    String[] tokens = trimmed.split("\\s+");
    String cmd = tokens[0];
    if (!readStringSet(pluginPath + "/config/enabled_conversations.txt").contains(peerUin + "_" + chatType)) {
        return;
    }
    if (cmd.equals("/whoami")) {
        String role = getRole(senderUin);
        sendStyledHeader(msg, "INFO", "角色: " + role + "\n记忆: " + getMemoryCount(senderUin) + " 条\n默认账户: " + getDefaultAccount());
        return;
    }
    if (cmd.equals("/help")) {
        String role = getRole(senderUin);
        StringBuilder h = new StringBuilder();
        h.append("墨鸦 v4.4.0 Strata\n\n/ai <内容>\n/ai memory / debug / reboot / status\n");
        if (role.equals("ADMIN") || role.equals("OWNER")) {
            h.append("/ai set / config / off / on / clear\n");
        }
        if (role.equals("OWNER")) {
            h.append("/setdefaultaccount\n");
        }
        h.append("\n墨鸦-Strata | 轻量级 Agentic RAG");
        sendStyledHeader(msg, "INFO", h.toString()); return;
    }
    String role = getRole(senderUin);
    if (role.equals("BLOCKED")) { if (!cmd.equals("/whoami") && !cmd.equals("/help") && !cmd.equals("/ai")) { sendPermissionDenied(msg); return; } }
    if (cmd.equals("/log")) { if (!requireAdminOrOwner(msg)) { return; } String p = pluginPath + "/config/log.txt"; if (!new File(p).exists()) sendStyledHeader(msg, "INFO", "日志已创建"); else sendFile(peerUin, p, chatType); return; }
    if (cmd.equals("/admin")) {
        if (!role.equals("OWNER")) {
            sendPermissionDenied(msg);
            return;
        }
        if (tokens.length >= 2 && tokens[1].equals("list")) {
            Set admins = readStringSet(pluginPath + "/config/admins.txt");
            if (admins.isEmpty()) {
                sendStyledHeader(msg, "INFO", "管理员列表为空");
                return;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("管理员列表 (").append(admins.size()).append("人):\n");
            for (Object a : admins) {
                sb.append("  ").append(a).append("\n");
            }
            sendStyledHeader(msg, "INFO", sb.toString().trim());
            return;
        }
        if (tokens.length < 2) {
            sendStyledHeader(msg, "ERROR", "用法: /admin @某人 或 /admin <UID>");
            return;
        }
        String t = extractTargetUin(msg, tokens.length >= 2 ? tokens[1] : "");
        if (t == null && isNumeric(tokens[1])) {
            t = tokens[1];
        }
        if (t == null) {
            sendStyledHeader(msg, "ERROR", "请 @用户 或提供 UID");
            return;
        }
        addToList(pluginPath + "/config/admins.txt", t);
        removeFromList(pluginPath + "/config/blocked.txt", t);
        sendStyledHeader(msg, "SUCCESS", "已授予管理员: " + t);
        return;
    }
    if (cmd.equals("/block")) {
        if (!requireAdminOrOwner(msg)) { return; }
        if (tokens.length >= 2 && tokens[1].equals("list")) {
            Set blocked = readStringSet(pluginPath + "/config/blocked.txt");
            StringBuilder sb = new StringBuilder();
            if (blocked.isEmpty()) { sb.append("黑名单为空"); }
            else {
                sb.append("黑名单 (").append(blocked.size()).append("人):\n");
                for (Object b : blocked) {
                    sb.append("  ").append(b).append("\n");
                }
            }
            if (getDefaultAccount().equals("blocked")) {
                sb.append("\n当前默认账户: blocked，新用户自动加入黑名单");
            }
            sendStyledHeader(msg, "INFO", sb.toString().trim());
            return;
        }
        if (tokens.length < 2) {
            sendStyledHeader(msg, "ERROR", "用法: /block @某人 或 /block <UID>");
            return;
        }
        String t = extractTargetUin(msg, tokens.length >= 2 ? tokens[1] : "");
        if (t == null && isNumeric(tokens[1])) {
            t = tokens[1];
        }
        if (t == null) {
            sendStyledHeader(msg, "ERROR", "请 @用户 或提供 UID");
            return;
        }
        if (t.equals(myUin)) {
            sendStyledHeader(msg, "ERROR", "不能拉黑宿主");
            return;
        }
        String tr = getRole(t);
        if (role.equals("ADMIN") && (tr.equals("ADMIN") || tr.equals("OWNER"))) {
            sendStyledHeader(msg, "ERROR", "不能拉黑 " + tr);
            return;
        }
        removeFromList(pluginPath + "/config/admins.txt", t);
        addToList(pluginPath + "/config/blocked.txt", t);
        removeFromList(pluginPath + "/config/members.txt", t);
        sendStyledHeader(msg, "SUCCESS", "已拉黑: " + t);
        return;
    }
    if (cmd.equals("/member")) {
        if (!requireAdminOrOwner(msg)) { return; }
        if (tokens.length >= 2 && tokens[1].equals("list")) {
            Set members = readStringSet(pluginPath + "/config/members.txt");
            StringBuilder sb = new StringBuilder();
            if (members.isEmpty()) { sb.append("成员白名单为空"); }
            else {
                sb.append("成员白名单 (").append(members.size()).append("人):\n");
                for (Object u : members) {
                    sb.append("  ").append(u).append("\n");
                }
            }
            if (getDefaultAccount().equals("member")) {
                sb.append("\n当前默认账户: member，新成员无需加入白名单");
            }
            sendStyledHeader(msg, "INFO", sb.toString().trim());
            return;
        }
        if (tokens.length < 2) {
            sendStyledHeader(msg, "ERROR", "用法: /member @某人 或 /member <UID>");
            return;
        }
        String t = extractTargetUin(msg, tokens.length >= 2 ? tokens[1] : "");
        if (t == null && isNumeric(tokens[1])) {
            t = tokens[1];
        }
        if (t == null) {
            sendStyledHeader(msg, "ERROR", "请 @用户 或提供 UID");
            return;
        }
        if (t.equals(myUin)) {
            sendStyledHeader(msg, "ERROR", "不能修改宿主权限");
            return;
        }
        String tr = getRole(t);
        if (role.equals("ADMIN") && (tr.equals("ADMIN") || tr.equals("OWNER"))) {
            sendStyledHeader(msg, "ERROR", "无法修改 " + tr + " 权限");
            return;
        }
        removeFromList(pluginPath + "/config/admins.txt", t);
        removeFromList(pluginPath + "/config/blocked.txt", t);
        if (getDefaultAccount().equals("blocked")) {
            addToList(pluginPath + "/config/members.txt", t);
            sendStyledHeader(msg, "SUCCESS", "已设为MEMBER并加入白名单: " + t);
        } else {
            sendStyledHeader(msg, "SUCCESS", "已设为 MEMBER: " + t);
        }
        return;
    }
    } finally {
        onMainThread--;
    }
}

/*
 *  墨鸦 Strata v5.0.0
 *  轻量级 Agentic RAG — 群聊 AI 记忆助手
 *
 *  Author:  YiJieqwq异界
 *
 *  MIT License
 *  Copyright (c) 2026 YiJieqwq异界
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */
