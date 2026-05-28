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

static List cachedWakeWords = null;
static long wakeWordsFileMtime = 0;

static String cachedSkills = null;
static long skillsDirMtime = 0;

// ==================== SQLite ====================
SQLiteDatabase getDb() {
    if (sharedDb == null || !sharedDb.isOpen()) {
        String dbPath = pluginPath + "/config/data.db";
        File dbFile = new File(dbPath);
        File parent = dbFile.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
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
        try {
            sharedDb.execSQL(
                "ALTER TABLE memories ADD COLUMN tags TEXT NOT NULL DEFAULT ''"
            );
        } catch (Exception ignored) { }
        try {
            sharedDb.execSQL(
                "ALTER TABLE memories ADD COLUMN scope TEXT NOT NULL DEFAULT 'private'"
            );
        } catch (Exception ignored) { }
        try {
            sharedDb.execSQL(
                "ALTER TABLE memories ADD COLUMN subject_uin TEXT NOT NULL DEFAULT ''"
            );
        } catch (Exception ignored) { }
        sharedDb.execSQL(
            "CREATE TABLE IF NOT EXISTS tag_pool (" +
            "uin TEXT NOT NULL, " +
            "tag TEXT NOT NULL, " +
            "count INTEGER NOT NULL DEFAULT 0, " +
            "PRIMARY KEY (uin, tag)" +
            ")"
        );
        sharedDb.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_tag_pool_uin ON tag_pool(uin)"
        );
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
    File f = new File(pluginPath + "/config/prompt.txt");
    if (!f.exists()) return "";
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
            sb.append(line);
            sb.append("\n");
        }
        br.close();
    } catch (Exception e) {
        log("error.txt", "loadPersona: " + e.getMessage());
        return "";
    }
    cachedPersona = sb.toString().trim();
    personaFileMtime = mtime;
    return cachedPersona;
}

List loadWakeWords() {
    File f = new File(pluginPath + "/config/prompt.txt");
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
                    if (!w.isEmpty()) words.add(w);
                }
            }
        }
    } catch (Exception e) {
        log("error.txt", "loadWakeWords: " + e.getMessage());
    }
    cachedWakeWords = words;
    wakeWordsFileMtime = mtime;
    return words;
}

boolean startsWithWakeWord(String text) {
    if (text == null) return false;
    List words = loadWakeWords();
    if (words.isEmpty()) return false;
    for (int i = 0; i < words.size(); i++) {
        if (text.startsWith((String) words.get(i))) return true;
    }
    return false;
}

// ==================== Skills ====================
String loadSkills() {
    File dir = new File(pluginPath + "/config/skills");
    if (!dir.exists() || !dir.isDirectory()) {
        cachedSkills = "";
        skillsDirMtime = 0;
        return "";
    }
    long latestMtime = 0;
    File[] files = dir.listFiles(new FilenameFilter() {
        public boolean accept(File dir, String name) {
            return name.endsWith(".skill.txt");
        }
    });
    if (files == null || files.length == 0) {
        cachedSkills = "";
        skillsDirMtime = 0;
        return "";
    }
    for (int i = 0; i < files.length; i++) {
        long mt = files[i].lastModified();
        if (mt > latestMtime) latestMtime = mt;
    }
    if (cachedSkills != null && latestMtime == skillsDirMtime) {
        return cachedSkills;
    }
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < files.length; i++) {
        String name = files[i].getName();
        String skillName = name.substring(0, name.length() - ".skill.txt".length());
        try {
            BufferedReader br = new BufferedReader(new FileReader(files[i]));
            StringBuilder content = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                content.append(line).append("\n");
            }
            br.close();
            String desc = content.toString().trim();
            if (!desc.isEmpty()) {
                sb.append(skillName);
                sb.append(":\n");
                sb.append(desc);
                sb.append("\n\n");
            }
        } catch (Exception e) {
            log("error.txt", "loadSkills: " + e.getMessage());
        }
    }
    cachedSkills = sb.toString().trim();
    skillsDirMtime = latestMtime;
    return cachedSkills;
}

// ==================== 默认账户 ====================
String getDefaultAccount() {
    File f = new File(pluginPath + "/config/default_account.txt");
    if (!f.exists()) return "user";
    try {
        BufferedReader br = new BufferedReader(new FileReader(f));
        String s = br.readLine();
        br.close();
        if (s != null) {
            s = s.trim().toLowerCase();
            if (s.equals("blocked")) return "blocked";
        }
    } catch (Exception e) { }
    return "user";
}

void setDefaultAccountConfig(String type) {
    try {
        File parent = new File(pluginPath + "/config");
        if (!parent.exists()) parent.mkdirs();
        PrintWriter pw = new PrintWriter(
            new FileWriter(pluginPath + "/config/default_account.txt")
        );
        pw.println(type);
        pw.close();
    } catch (Exception e) {
        log("error.txt", "setDefaultAccountConfig: " + e.getMessage());
    }
}

boolean canUseAi(String uin) {
    if (getRole(uin).equals("BLOCKED")) return false;
    if (uin.equals(myUin)) return true;
    String role = getRole(uin);
    if (role.equals("ADMIN")) return true;
    if (getDefaultAccount().equals("user")) return true;
    Set whitelist = readStringSet(pluginPath + "/config/users.txt");
    return whitelist.contains(uin);
}

// ==================== Tag 池 ====================
Map getTagPool(String uin) {
    long now = System.currentTimeMillis();
    if (tagPoolCache != null &&
        uin.equals(tagPoolCacheUin) &&
        (now - tagPoolCacheTime) < TAG_POOL_CACHE_MS) {
        return tagPoolCache;
    }
    Map pool = new LinkedHashMap();
    Cursor c = null;
    try {
        c = getDb().rawQuery(
            "SELECT tag, count FROM tag_pool " +
            "WHERE uin = ? ORDER BY count DESC, tag ASC",
            new String[]{uin}
        );
        while (c.moveToNext()) {
            pool.put(c.getString(0), c.getInt(1));
        }
    } catch (Exception e) {
        log("error.txt", "getTagPool: " + e.getMessage());
    } finally {
        if (c != null) c.close();
    }
    tagPoolCache = pool;
    tagPoolCacheTime = now;
    tagPoolCacheUin = uin;
    return pool;
}

Map getPublicTagPool() {
    Map pool = new LinkedHashMap();
    Cursor c = null;
    try {
        c = getDb().rawQuery(
            "SELECT tag, count FROM tag_pool " +
            "WHERE uin = 'PUBLIC' ORDER BY count DESC, tag ASC",
            null
        );
        while (c.moveToNext()) {
            pool.put(c.getString(0), c.getInt(1));
        }
    } catch (Exception e) {
        log("error.txt", "getPublicTagPool: " + e.getMessage());
    } finally {
        if (c != null) c.close();
    }
    return pool;
}

void updateTagPool(String uin, String tagsStr, int delta) {
    if (tagsStr == null || tagsStr.trim().isEmpty()) return;
    String[] tags = tagsStr.split(",");
    SQLiteDatabase db = getDb();
    try {
        db.beginTransaction();
        for (int i = 0; i < tags.length; i++) {
            String t = tags[i].trim().toLowerCase();
            if (t.isEmpty()) continue;
            int curCount = getTagPoolCount(db, uin, t);
            int newCount = Math.max(0, curCount + delta);
            ContentValues cv = new ContentValues();
            cv.put("count", newCount);
            int updated = db.update(
                "tag_pool", cv,
                "uin = ? AND tag = ?",
                new String[]{uin, t}
            );
            if (updated == 0 && delta > 0) {
                cv.put("uin", uin);
                cv.put("tag", t);
                cv.put("count", 1);
                db.insert("tag_pool", null, cv);
            }
            if (updated > 0 && newCount <= 0) {
                db.delete(
                    "tag_pool",
                    "uin = ? AND tag = ?",
                    new String[]{uin, t}
                );
            }
        }
        db.setTransactionSuccessful();
    } catch (Exception e) {
        log("error.txt", "updateTagPool: " + e.getMessage());
    } finally {
        db.endTransaction();
    }
    if (!"PUBLIC".equals(uin)) {
        tagPoolCache = null;
        tagPoolCacheTime = 0;
        tagPoolCacheUin = "";
    }
}

int getTagPoolCount(SQLiteDatabase db, String uin, String tag) {
    Cursor c = null;
    try {
        c = db.rawQuery(
            "SELECT count FROM tag_pool WHERE uin = ? AND tag = ?",
            new String[]{uin, tag}
        );
        if (c.moveToFirst()) return c.getInt(0);
    } catch (Exception ignored) { }
    finally { if (c != null) c.close(); }
    return 0;
}

void rebuildTagPool(String uin) {
    SQLiteDatabase db = getDb();
    try {
        db.beginTransaction();
        db.delete("tag_pool", "uin = ?", new String[]{uin});
        Cursor c = db.rawQuery(
            "SELECT tags FROM memories " +
            "WHERE uin = ? AND scope = 'private' AND tags != ''",
            new String[]{uin}
        );
        while (c.moveToNext()) {
            String ts = c.getString(0);
            if (ts == null || ts.trim().isEmpty()) continue;
            String[] tags = ts.split(",");
            for (int i = 0; i < tags.length; i++) {
                String t = tags[i].trim().toLowerCase();
                if (t.isEmpty()) continue;
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
        c.close();
        db.setTransactionSuccessful();
    } catch (Exception e) {
        log("error.txt", "rebuildTagPool: " + e.getMessage());
    } finally {
        db.endTransaction();
    }
    tagPoolCache = null;
    tagPoolCacheTime = 0;
}

void rebuildPublicTagPool() {
    SQLiteDatabase db = getDb();
    try {
        db.beginTransaction();
        db.delete("tag_pool", "uin = 'PUBLIC'", null);
        Cursor c = db.rawQuery(
            "SELECT tags FROM memories " +
            "WHERE scope = 'public' AND tags != ''",
            null
        );
        while (c.moveToNext()) {
            String ts = c.getString(0);
            if (ts == null || ts.trim().isEmpty()) continue;
            String[] tags = ts.split(",");
            for (int i = 0; i < tags.length; i++) {
                String t = tags[i].trim().toLowerCase();
                if (t.isEmpty()) continue;
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
        c.close();
        db.setTransactionSuccessful();
    } catch (Exception e) {
        log("error.txt", "rebuildPublicTagPool: " + e.getMessage());
    } finally {
        db.endTransaction();
    }
}

// ==================== 记忆操作 ====================
boolean storeMemory(String uin, String content, String tags, String scope, String subjectUin) {
    try {
        long now = System.currentTimeMillis();
        ContentValues cv = new ContentValues();
        cv.put("uin", uin);
        cv.put("content", content);
        cv.put("tags", tags != null ? tags : "");
        cv.put("scope", scope != null ? scope : "private");
        cv.put("subject_uin", subjectUin != null && !subjectUin.isEmpty() ? subjectUin : "");
        cv.put("created_at", now);
        cv.put("accessed_at", now);
        long id = getDb().insert("memories", null, cv);
        if (id != -1) {
            if ("public".equals(scope)) {
                updateTagPool("PUBLIC", tags, 1);
            } else {
                updateTagPool(uin, tags, 1);
            }
            writeLog(uin,
                "[MEMORY/" + scope + "] tags:" + tags +
                " about:" + (subjectUin != null && !subjectUin.isEmpty() ? subjectUin : uin) +
                " " + content + " (id=" + id + ")"
            );
            return true;
        }
        return false;
    } catch (Exception e) {
        log("error.txt", "storeMemory: " + e.getMessage());
        return false;
    }
}

void touchMemory(long id) {
    try {
        ContentValues cv = new ContentValues();
        cv.put("accessed_at", System.currentTimeMillis());
        getDb().update(
            "memories", cv,
            "id = ?",
            new String[]{String.valueOf(id)}
        );
    } catch (Exception ignored) { }
}

List searchMemories(String uin, String keyword) {
    List results = new ArrayList();
    Cursor c = null;
    try {
        c = getDb().rawQuery(
            "SELECT id, content, tags, scope, subject_uin FROM memories " +
            "WHERE uin = ? AND scope = 'private' " +
            "AND (content LIKE ? OR tags LIKE ?) ORDER BY accessed_at DESC LIMIT 20",
            new String[]{uin, "%" + keyword + "%", "%" + keyword + "%"}
        );
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
    } catch (Exception e) {
        log("error.txt", "searchMemories: " + e.getMessage());
    } finally {
        if (c != null) c.close();
    }
    return results;
}

List searchMemoriesByTag(String uin, String tag) {
    List results = new ArrayList();
    Cursor c = null;
    try {
        c = getDb().rawQuery(
            "SELECT id, content, tags, scope, subject_uin FROM memories " +
            "WHERE uin = ? AND scope = 'private' " +
            "AND tags LIKE ? ORDER BY accessed_at DESC LIMIT 20",
            new String[]{uin, "%" + tag.toLowerCase() + "%"}
        );
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
    } catch (Exception e) {
        log("error.txt", "searchMemoriesByTag: " + e.getMessage());
    } finally {
        if (c != null) c.close();
    }
    return results;
}

List searchPublicByTag(String tag) {
    List results = new ArrayList();
    Cursor c = null;
    try {
        c = getDb().rawQuery(
            "SELECT id, content, tags, scope, subject_uin FROM memories " +
            "WHERE scope = 'public' AND tags LIKE ? ORDER BY accessed_at DESC LIMIT 20",
            new String[]{"%" + tag.toLowerCase() + "%"}
        );
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
    } catch (Exception e) {
        log("error.txt", "searchPublicByTag: " + e.getMessage());
    } finally {
        if (c != null) c.close();
    }
    return results;
}

List searchPublicMemories(String keyword) {
    List results = new ArrayList();
    Cursor c = null;
    try {
        c = getDb().rawQuery(
            "SELECT id, content, tags, scope, subject_uin FROM memories " +
            "WHERE scope = 'public' AND (content LIKE ? OR tags LIKE ?) " +
            "ORDER BY accessed_at DESC LIMIT 20",
            new String[]{"%" + keyword + "%", "%" + keyword + "%"}
        );
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
    } catch (Exception e) {
        log("error.txt", "searchPublicMemories: " + e.getMessage());
    } finally {
        if (c != null) c.close();
    }
    return results;
}

List searchAllMemoriesByKeyword(String keyword, int limit) {
    List results = new ArrayList();
    Cursor c = null;
    try {
        c = getDb().rawQuery(
            "SELECT id, uin, content, tags, scope, subject_uin FROM memories " +
            "WHERE content LIKE ? " +
            "ORDER BY scope DESC, accessed_at DESC LIMIT ?",
            new String[]{"%" + keyword + "%", String.valueOf(limit)}
        );
        while (c.moveToNext()) {
            Map m = new HashMap();
            m.put("id", c.getLong(0));
            m.put("uin", c.getString(1));
            m.put("content", c.getString(2));
            m.put("tags", c.getString(3) != null ? c.getString(3) : "");
            m.put("scope", c.getString(4));
            m.put("subjectUin", c.getString(5) != null ? c.getString(5) : "");
            results.add(m);
            touchMemory(c.getLong(0));
        }
    } catch (Exception e) {
        log("error.txt", "searchAllMemoriesByKeyword: " + e.getMessage());
    } finally {
        if (c != null) c.close();
    }
    return results;
}

List getRecentMemories(String uin, int limit) {
    List results = new ArrayList();
    Cursor c = null;
    try {
        c = getDb().rawQuery(
            "SELECT id, content, tags, scope, subject_uin FROM memories " +
            "WHERE uin = ? AND scope = 'private' " +
            "ORDER BY accessed_at DESC LIMIT ?",
            new String[]{uin, String.valueOf(limit)}
        );
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
    } catch (Exception e) {
        log("error.txt", "getRecentMemories: " + e.getMessage());
    } finally {
        if (c != null) c.close();
    }
    return results;
}

List getMyMemories(String uin, int limit) {
    List results = new ArrayList();
    Cursor c = null;
    try {
        c = getDb().rawQuery(
            "SELECT id, content, tags FROM memories " +
            "WHERE uin = ? AND scope = 'private' " +
            "ORDER BY accessed_at DESC LIMIT ?",
            new String[]{uin, String.valueOf(limit)}
        );
        while (c.moveToNext()) {
            Map m = new HashMap();
            m.put("id", c.getLong(0));
            m.put("content", c.getString(1));
            m.put("tags", c.getString(2) != null ? c.getString(2) : "");
            results.add(m);
        }
    } catch (Exception e) {
        log("error.txt", "getMyMemories: " + e.getMessage());
    } finally {
        if (c != null) c.close();
    }
    return results;
}

List getPublicMemories(int limit) {
    List results = new ArrayList();
    Cursor c = null;
    try {
        c = getDb().rawQuery(
            "SELECT id, content, tags FROM memories " +
            "WHERE scope = 'public' ORDER BY accessed_at DESC LIMIT ?",
            new String[]{String.valueOf(limit)}
        );
        while (c.moveToNext()) {
            Map m = new HashMap();
            m.put("id", c.getLong(0));
            m.put("content", c.getString(1));
            m.put("tags", c.getString(2) != null ? c.getString(2) : "");
            results.add(m);
        }
    } catch (Exception e) {
        log("error.txt", "getPublicMemories: " + e.getMessage());
    } finally {
        if (c != null) c.close();
    }
    return results;
}

List getAllMemoriesAdmin(int limit) {
    List results = new ArrayList();
    Cursor c = null;
    try {
        c = getDb().rawQuery(
            "SELECT id, uin, content, tags, scope, subject_uin FROM memories " +
            "ORDER BY scope DESC, accessed_at DESC LIMIT ?",
            new String[]{String.valueOf(limit)}
        );
        while (c.moveToNext()) {
            Map m = new HashMap();
            m.put("id", c.getLong(0));
            m.put("uin", c.getString(1));
            m.put("content", c.getString(2));
            m.put("tags", c.getString(3) != null ? c.getString(3) : "");
            m.put("scope", c.getString(4));
            m.put("subjectUin", c.getString(5) != null ? c.getString(5) : "");
            results.add(m);
        }
    } catch (Exception e) {
        log("error.txt", "getAllMemoriesAdmin: " + e.getMessage());
    } finally {
        if (c != null) c.close();
    }
    return results;
}

List searchAllMemoriesAdmin(String keyword, int limit) {
    List results = new ArrayList();
    Cursor c = null;
    try {
        c = getDb().rawQuery(
            "SELECT id, uin, content, tags, scope, subject_uin FROM memories " +
            "WHERE content LIKE ? OR tags LIKE ? " +
            "ORDER BY scope DESC, accessed_at DESC LIMIT ?",
            new String[]{"%" + keyword + "%", "%" + keyword + "%", String.valueOf(limit)}
        );
        while (c.moveToNext()) {
            Map m = new HashMap();
            m.put("id", c.getLong(0));
            m.put("uin", c.getString(1));
            m.put("content", c.getString(2));
            m.put("tags", c.getString(3) != null ? c.getString(3) : "");
            m.put("scope", c.getString(4));
            m.put("subjectUin", c.getString(5) != null ? c.getString(5) : "");
            results.add(m);
        }
    } catch (Exception e) {
        log("error.txt", "searchAllMemoriesAdmin: " + e.getMessage());
    } finally {
        if (c != null) c.close();
    }
    return results;
}

int getMemoryCount(String uin) {
    Cursor c = null;
    try {
        c = getDb().rawQuery(
            "SELECT COUNT(*) FROM memories " +
            "WHERE uin = ? AND scope = 'private'",
            new String[]{uin}
        );
        if (c.moveToFirst()) return c.getInt(0);
    } catch (Exception ignored) { }
    finally { if (c != null) c.close(); }
    return 0;
}

int deleteMemoriesByKeyword(String uin, String keyword) {
    try {
        Cursor c = getDb().rawQuery(
            "SELECT tags FROM memories " +
            "WHERE uin = ? AND scope = 'private' AND content LIKE ?",
            new String[]{uin, "%" + keyword + "%"}
        );
        while (c.moveToNext()) {
            String tags = c.getString(0);
            if (tags != null && !tags.trim().isEmpty()) {
                updateTagPool(uin, tags, -1);
            }
        }
        c.close();
        return getDb().delete(
            "memories",
            "uin = ? AND scope = 'private' AND content LIKE ?",
            new String[]{uin, "%" + keyword + "%"}
        );
    } catch (Exception e) {
        log("error.txt", "deleteMemoriesByKeyword: " + e.getMessage());
        return 0;
    }
}

boolean deleteMemoryById(long id, String requesterUin, String requesterRole) {
    try {
        Cursor c = getDb().rawQuery(
            "SELECT uin, tags, scope FROM memories WHERE id = ?",
            new String[]{String.valueOf(id)}
        );
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
        if (requesterRole.equals("ADMIN") || requesterRole.equals("ROOT")) {
            deleted = getDb().delete("memories", "id = ?", new String[]{String.valueOf(id)});
        } else {
            deleted = getDb().delete("memories", "id = ? AND uin = ?",
                new String[]{String.valueOf(id), requesterUin});
        }

        if (deleted > 0 && tags != null && !tags.trim().isEmpty()) {
            if ("public".equals(scope)) {
                updateTagPool("PUBLIC", tags, -1);
            } else {
                updateTagPool(memUin.isEmpty() ? requesterUin : memUin, tags, -1);
            }
        }
        return deleted > 0;
    } catch (Exception e) {
        return false;
    }
}

// ==================== 角色管理 ====================
String getRole(String uin) {
    if (uin.equals(myUin)) return "ROOT";
    Set admins = readStringSet(pluginPath + "/config/admins.txt");
    if (admins.contains(uin)) return "ADMIN";
    Set blocked = readStringSet(pluginPath + "/config/blocked.txt");
    if (blocked.contains(uin)) return "BLOCKED";
    return "USER";
}

Set readStringSet(String path) {
    Set set = new HashSet();
    File f = new File(path);
    if (!f.exists()) return set;
    try {
        BufferedReader br = new BufferedReader(new FileReader(f));
        String line;
        while ((line = br.readLine()) != null) {
            line = line.trim();
            if (!line.isEmpty()) set.add(line);
        }
        br.close();
    } catch (Exception e) {
        log("error.txt", "readStringSet: " + e.getMessage());
    }
    return set;
}

void writeStringSet(String path, Set set) {
    File f = new File(path);
    File parent = f.getParentFile();
    if (parent != null && !parent.exists()) parent.mkdirs();
    try {
        BufferedWriter bw = new BufferedWriter(new FileWriter(f));
        for (Object s : set) {
            bw.write(s + "\n");
        }
        bw.flush();
        bw.close();
    } catch (Exception e) {
        log("error.txt", "writeStringSet: " + e.getMessage());
    }
}

void addToList(String path, String uin) {
    Set set = readStringSet(path);
    if (!set.contains(uin)) {
        set.add(uin);
        writeStringSet(path, set);
    }
}

void removeFromList(String path, String uin) {
    Set set = readStringSet(path);
    if (set.remove(uin)) writeStringSet(path, set);
}

// ==================== 日志 ====================
String getCurrentTime() {
    return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
}

String getMemberName(int chatType, String peerUin, String uin) {
    if (chatType == 2) {
        try {
            Object mem = getMemberInfo(peerUin, uin);
            if (mem != null && mem.uinName != null) return mem.uinName;
        } catch (Exception e) { }
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
                            if (remark != null && remark.length() > 0) return remark;
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
        bw.newLine();
        bw.flush();
        bw.close();
    } catch (Exception e) {
        log("error.txt", "writeLog: " + e.getMessage());
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
    cfg.put("context_ttl", "30");
    cfg.put("max_turns", "10");
    cfg.put("ai_url", "https://api.deepseek.com");
    cfg.put("search_provider", "bocha");
    cfg.put("search_api_key", "");
    cfg.put("show_stats", "1");
    cfg.put("debug", "0");
    cfg.put("ai1_model", "");
    cfg.put("ai1_api_key", "");
    cfg.put("ai1_api_url", "");
    cfg.put("ai1_max_tokens", "512");

    File f = new File(pluginPath + "/config/ai_config.txt");
    if (f.exists()) {
        try {
            BufferedReader br = new BufferedReader(new FileReader(f));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf("=");
                if (eq > 0) {
                    String k = line.substring(0, eq).trim();
                    String v = line.substring(eq + 1).trim();
                    cfg.put(k, v);
                }
            }
            br.close();
        } catch (Exception e) {
            log("error.txt", "loadAiConfig: " + e.getMessage());
        }
    }
    aiConfigCache = cfg;
    aiConfigCacheTime = now;
    return cfg;
}

void saveAiConfig(Map cfg) {
    try {
        File parent = new File(pluginPath + "/config");
        if (!parent.exists()) parent.mkdirs();
        PrintWriter pw = new PrintWriter(new FileWriter(pluginPath + "/config/ai_config.txt"));
        pw.println("# 鉴存-LMA v13 config");
        pw.println();
        for (Object entry : cfg.entrySet()) {
            Map.Entry e = (Map.Entry) entry;
            pw.println(e.getKey() + "=" + e.getValue());
        }
        pw.close();
        aiConfigCache = null;
        aiConfigCacheTime = 0;
    } catch (Exception e) {
        log("error.txt", "saveAiConfig: " + e.getMessage());
    }
}

String getAiConfig(String key) {
    Map cfg = loadAiConfig();
    Object v = cfg.get(key);
    return v != null ? v.toString() : "";
}

String resolveAiCfg(Map cfg, String prefixedKey, String fallbackKey) {
    String v = (String) cfg.get(prefixedKey);
    if (v != null && !v.isEmpty()) return v;
    v = (String) cfg.get(fallbackKey);
    return v != null ? v : "";
}

// ==================== v13: Function Calling 工具定义 ====================

JSONArray buildAI1Tools() {
    JSONArray tools = new JSONArray();

    JSONObject t1 = new JSONObject();
    t1.put("type", "function");
    JSONObject f1 = new JSONObject();
    f1.put("name", "search_by_tag");
    f1.put("description", "按标签搜索当前用户的私有记忆");
    f1.put("parameters", new JSONObject(
        "{\"type\":\"object\"," +
        "\"properties\":{\"tag\":{\"type\":\"string\",\"description\":\"标签名\"}}," +
        "\"required\":[\"tag\"]}"
    ));
    t1.put("function", f1);
    tools.put(t1);

    JSONObject t2 = new JSONObject();
    t2.put("type", "function");
    JSONObject f2 = new JSONObject();
    f2.put("name", "search_by_keyword");
    f2.put("description", "模糊搜索当前用户的私有记忆");
    f2.put("parameters", new JSONObject(
        "{\"type\":\"object\"," +
        "\"properties\":{\"keyword\":{\"type\":\"string\",\"description\":\"搜索关键词\"}}," +
        "\"required\":[\"keyword\"]}"
    ));
    t2.put("function", f2);
    tools.put(t2);

    JSONObject t3 = new JSONObject();
    t3.put("type", "function");
    JSONObject f3 = new JSONObject();
    f3.put("name", "search_public");
    f3.put("description", "搜索公有记忆");
    f3.put("parameters", new JSONObject(
        "{\"type\":\"object\"," +
        "\"properties\":{\"keyword\":{\"type\":\"string\",\"description\":\"搜索关键词\"}}," +
        "\"required\":[\"keyword\"]}"
    ));
    t3.put("function", f3);
    tools.put(t3);

    JSONObject t4 = new JSONObject();
    t4.put("type", "function");
    JSONObject f4 = new JSONObject();
    f4.put("name", "search_public_by_tag");
    f4.put("description", "按标签搜索公有记忆");
    f4.put("parameters", new JSONObject(
        "{\"type\":\"object\"," +
        "\"properties\":{\"tag\":{\"type\":\"string\",\"description\":\"标签名\"}}," +
        "\"required\":[\"tag\"]}"
    ));
    t4.put("function", f4);
    tools.put(t4);

    JSONObject t5 = new JSONObject();
    t5.put("type", "function");
    JSONObject f5 = new JSONObject();
    f5.put("name", "search_all");
    f5.put("description", "搜索全部记忆(含他人私有)，用于冲突检测");
    f5.put("parameters", new JSONObject(
        "{\"type\":\"object\"," +
        "\"properties\":{\"keyword\":{\"type\":\"string\",\"description\":\"搜索关键词\"}}," +
        "\"required\":[\"keyword\"]}"
    ));
    t5.put("function", f5);
    tools.put(t5);

    JSONObject t6 = new JSONObject();
    t6.put("type", "function");
    JSONObject f6 = new JSONObject();
    f6.put("name", "get_recent");
    f6.put("description", "拉取当前用户最近10条私有记忆");
    f6.put("parameters", new JSONObject(
        "{\"type\":\"object\",\"properties\":{}}"
    ));
    t6.put("function", f6);
    tools.put(t6);

    return tools;
}

JSONArray buildAI2Tools() {
    JSONArray tools = new JSONArray();

    // create_memory
    JSONObject t1 = new JSONObject();
    t1.put("type", "function");
    JSONObject f1 = new JSONObject();
    f1.put("name", "create_memory");
    f1.put("description", "创建私有记忆");
    f1.put("parameters", new JSONObject(
        "{\"type\":\"object\"," +
        "\"properties\":{" +
        "\"content\":{\"type\":\"string\",\"description\":\"记忆内容\"}," +
        "\"tags\":{\"type\":\"string\",\"description\":\"标签，英文逗号分隔\"}," +
        "\"about\":{\"type\":\"string\",\"description\":\"记忆对象的UIN，缺省为说话者本人\"}}," +
        "\"required\":[\"content\",\"tags\"]}"
    ));
    t1.put("function", f1);
    tools.put(t1);

    // create_public_memory
    JSONObject t2 = new JSONObject();
    t2.put("type", "function");
    JSONObject f2 = new JSONObject();
    f2.put("name", "create_public_memory");
    f2.put("description", "创建公有记忆");
    f2.put("parameters", new JSONObject(
        "{\"type\":\"object\"," +
        "\"properties\":{" +
        "\"content\":{\"type\":\"string\",\"description\":\"记忆内容\"}," +
        "\"tags\":{\"type\":\"string\",\"description\":\"标签，英文逗号分隔\"}," +
        "\"about\":{\"type\":\"string\",\"description\":\"记忆对象的UIN\"}}," +
        "\"required\":[\"content\",\"tags\"]}"
    ));
    t2.put("function", f2);
    tools.put(t2);

    // overwrite_memory
    JSONObject t3 = new JSONObject();
    t3.put("type", "function");
    JSONObject f3 = new JSONObject();
    f3.put("name", "overwrite_memory");
    f3.put("description", "覆写指定id的私有记忆(先删旧后写新)");
    f3.put("parameters", new JSONObject(
        "{\"type\":\"object\"," +
        "\"properties\":{" +
        "\"id\":{\"type\":\"integer\",\"description\":\"要覆写的记忆id\"}," +
        "\"content\":{\"type\":\"string\",\"description\":\"新内容\"}," +
        "\"tags\":{\"type\":\"string\",\"description\":\"新标签\"}," +
        "\"about\":{\"type\":\"string\",\"description\":\"记忆对象UIN\"}}," +
        "\"required\":[\"id\",\"content\",\"tags\"]}"
    ));
    t3.put("function", f3);
    tools.put(t3);

    // overwrite_public_memory
    JSONObject t4 = new JSONObject();
    t4.put("type", "function");
    JSONObject f4 = new JSONObject();
    f4.put("name", "overwrite_public_memory");
    f4.put("description", "覆写指定id的公有记忆(先删旧后写新)");
    f4.put("parameters", new JSONObject(
        "{\"type\":\"object\"," +
        "\"properties\":{" +
        "\"id\":{\"type\":\"integer\",\"description\":\"要覆写的记忆id\"}," +
        "\"content\":{\"type\":\"string\",\"description\":\"新内容\"}," +
        "\"tags\":{\"type\":\"string\",\"description\":\"新标签\"}," +
        "\"about\":{\"type\":\"string\",\"description\":\"记忆对象UIN\"}}," +
        "\"required\":[\"id\",\"content\",\"tags\"]}"
    ));
    t4.put("function", f4);
    tools.put(t4);

    // delete_memory
    JSONObject t5 = new JSONObject();
    t5.put("type", "function");
    JSONObject f5 = new JSONObject();
    f5.put("name", "delete_memory");
    f5.put("description", "按id精确删除一条记忆");
    f5.put("parameters", new JSONObject(
        "{\"type\":\"object\"," +
        "\"properties\":{\"id\":{\"type\":\"integer\",\"description\":\"要删除的记忆id\"}}," +
        "\"required\":[\"id\"]}"
    ));
    t5.put("function", f5);
    tools.put(t5);

    // search_web
    JSONObject t6 = new JSONObject();
    t6.put("type", "function");
    JSONObject f6 = new JSONObject();
    f6.put("name", "search_web");
    f6.put("description", "联网搜索。整条回复只能调用这一个函数，不得附带content。");
    f6.put("parameters", new JSONObject(
        "{\"type\":\"object\"," +
        "\"properties\":{\"query\":{\"type\":\"string\",\"description\":\"简短搜索关键词\"}}," +
        "\"required\":[\"query\"]}"
    ));
    t6.put("function", f6);
    tools.put(t6);

    // fetch_page
    JSONObject t7 = new JSONObject();
    t7.put("type", "function");
    JSONObject f7 = new JSONObject();
    f7.put("name", "fetch_page");
    f7.put("description", "抓取网页全文。整条回复只能调用这一个函数，不得附带content。");
    f7.put("parameters", new JSONObject(
        "{\"type\":\"object\"," +
        "\"properties\":{\"url\":{\"type\":\"string\",\"description\":\"网页完整URL\"}}," +
        "\"required\":[\"url\"]}"
    ));
    t7.put("function", f7);
    tools.put(t7);

    // call_skill
    JSONObject t8 = new JSONObject();
    t8.put("type", "function");
    JSONObject f8 = new JSONObject();
    f8.put("name", "call_skill");
    f8.put("description", "调用系统技能，命令会原样执行");
    f8.put("parameters", new JSONObject(
        "{\"type\":\"object\"," +
        "\"properties\":{\"command\":{\"type\":\"string\",\"description\":\"技能命令原文\"}}," +
        "\"required\":[\"command\"]}"
    ));
    t8.put("function", f8);
    tools.put(t8);

    return tools;
}

// ==================== AI1 系统提示词（检索规划，v13 FC版）====================
String buildAI1Prompt(String tagPoolStr, String senderUin, String senderName, String userRole) {
    StringBuilder sb = new StringBuilder();
    sb.append("你是鉴存-LMA 的检索规划模块。");
    sb.append("你有以下工具可用，请根据用户消息调用合适的工具搜索记忆。\n\n");

    sb.append("规则：\n");
    sb.append("- 用户询问具体信息 → 搜索相关标签和关键词\n");
    sb.append("- 用户给出个人信息 → 搜索已有记录检查冲突\n");
    sb.append("- 问题涉及群体/公共话题 → 同时搜索私有和公有\n");
    sb.append("- 只是打招呼或闲聊 → 不调用任何工具\n");
    sb.append("- 不确定时宁可多搜不要漏搜\n");
    sb.append("- 有意义的数字串如果在 [公有] 标签中存在，只能用 search_public_by_tag\n");
    sb.append("- 标签名必须在标签池中存在才用 search_by_tag，否则用 search_by_keyword\n\n");

    sb.append("=== 当前用户标签池 ===\n");
    sb.append(tagPoolStr.isEmpty() ? "暂无标签" : tagPoolStr);
    sb.append("\n\n");

    sb.append("=== 当前用户 ===\n");
    sb.append("UIN: ").append(senderUin);
    sb.append("  名称: ").append(senderName);
    sb.append("  角色: ").append(userRole);
    sb.append("\n");
    return sb.toString();
}

// ==================== AI2 系统提示词（应答器，v13 FC版）====================
String buildAI2Prompt(String userRole, String senderUin, String senderName, int chatType, String peerUin) {
    StringBuilder sb = new StringBuilder();

    String persona = loadPersona();
    if (!persona.isEmpty()) {
        sb.append(persona);
        sb.append("\n\n");
    } else {
        sb.append("你是鉴存-LMA，一个有长期记忆、会主动求证的 AI 助手。\n\n");
    }

    String skills = loadSkills();
    if (!skills.isEmpty()) {
        sb.append("=== 可用技能 ===\n");
        sb.append("用 call_skill 函数调用技能。\n");
        sb.append(skills);
        sb.append("\n");
    }

    sb.append("=== 核心规则 ===\n");
    sb.append("你以当前人设回复，同时拥有联网搜索能力。\n\n");

    sb.append("每条用户消息有 [UIN:xxx, 名称:xxx, 角色:xxx] 标记身份。\n");
    sb.append("引用消息前有 ↳引用: 标记。\n");
    sb.append("系统已检索记忆注入下方（用户不可见）。\n");
    sb.append("记忆操作是后台静默的，不要在自然语言中说\"记下了/存好了\"。\n");
    sb.append("把检索结果当自己的知识自然回答，不提及后台概念。\n");
    sb.append("信息不够时调用 search_web。\n\n");

    sb.append("=== 记忆操作（两步验证）===\n");
    sb.append("第1轮：回复用户的同时调用 create_memory/create_public_memory/delete_memory 表达意向。\n");
    sb.append("系统会自动检索库里已有内容，告诉你匹配结果（含id）。\n");
    sb.append("第2轮(静默)：根据检索结果确认操作：\n");
    sb.append("  新建 → 再次调用 create_memory/create_public_memory\n");
    sb.append("  覆写 → 调用 overwrite_memory/overwrite_public_memory(id:匹配到的编号)\n");
    sb.append("  删除 → 调用 delete_memory(id:匹配到的编号)\n");
    sb.append("  取消 → 不调用任何函数\n");
    sb.append("第2轮只调用函数，不输出content。\n\n");

    sb.append("=== 记忆规则 ===\n");
    sb.append("必须打标签（简短中文，英文逗号分隔）。\n");
    sb.append("内容出现纯数字串必须作为标签。\n");
    sb.append("about 代表记忆对象，缺省=自述。自述可信度远高于转述。\n");
    sb.append("群聊→create_public_memory，私聊→create_memory。\n");
    sb.append("ROOT/ADMIN写入必须执行。USER写公有需冲突检查。\n");
    sb.append("优先级：自述>转述，ROOT>ADMIN>USER。\n");
    sb.append("主动记录有价值信息，已有则不重复。\n\n");

    sb.append("=== 联网搜索 ===\n");
    sb.append("调用 search_web(query) 联网搜索。整条回复只能有这一个函数调用，不得附带content。\n");
    sb.append("调用 fetch_page(url) 抓取网页。同样只能单独调用。\n");
    sb.append("最多5轮搜索，search_web 和 fetch_page 可交替使用。\n\n");

    sb.append("=== 回复格式 ===\n");
    sb.append("1. 纯文本，禁止Markdown\n");
    sb.append("2. 保持人设语气\n");
    sb.append("3. 回复超过30字必须分段：[SPLIT]内容[/SPLIT][SPLIT]内容[/SPLIT]。禁止嵌套 [SPLIT]，每段是一对独立标签。\n");
    sb.append("4. 技能调用用 call_skill\n");
    sb.append("5. 用户用什么语言就问什么语言\n\n");

    sb.append("=== 当前用户 ===\n");
    sb.append("UIN:").append(senderUin);
    sb.append(" 名称:").append(senderName);
    sb.append(" 角色:").append(userRole);
    sb.append("\nROOT:").append(myUin).append("\n\n");

    if (chatType == 2) {
        sb.append("会话:群聊 群号:").append(peerUin);
        try {
            java.util.List gl = getGroupList();
            if (gl != null) {
                for (int i = 0; i < gl.size(); i++) {
                    Object gi = gl.get(i);
                    try {
                        java.lang.reflect.Field fg = gi.getClass().getField("group");
                        if (String.valueOf(fg.get(gi)).equals(peerUin)) {
                            java.lang.reflect.Field fn = gi.getClass().getField("groupName");
                            String gn = (String) fn.get(gi);
                            if (gn != null && !gn.isEmpty()) sb.append(" 群名:").append(gn);
                            break;
                        }
                    } catch (Exception ignored2) { }
                }
            }
        } catch (Exception ignored) { }
        sb.append("\n");
    } else {
        sb.append("会话:私聊 对方:").append(getMemberName(1, peerUin, peerUin)).append("\n");
    }

    sb.append("当前时间:").append(getCurrentTime()).append("\n");
    return sb.toString();
}

// ==================== AI 上下文 ====================
List getAiContext(String peerUin, int chatType) {
    String key = peerUin + "_" + chatType;
    List ctx = (List) aiContexts.get(key);
    long ttl;
    try {
        ttl = Long.parseLong(getAiConfig("context_ttl")) * 60 * 1000L;
    } catch (Exception e) {
        ttl = 30 * 60 * 1000L;
    }
    long now = System.currentTimeMillis();
    if (ctx != null && !ctx.isEmpty()) {
        Map last = (Map) ctx.get(ctx.size() - 1);
        Long ts = (Long) last.get("_ts");
        if (ts != null && (now - ts) > ttl) {
            aiContexts.remove(key);
            ctx = null;
        }
    }
    if (ctx == null) {
        ctx = new ArrayList();
        aiContexts.put(key, ctx);
    }
    return ctx;
}

void clearAiContext(String peerUin, int chatType) {
    aiContexts.remove(peerUin + "_" + chatType);
}

void addToContext(List ctx, String role, String content) {
    Map m = new HashMap();
    m.put("role", role);
    m.put("content", content);
    m.put("_ts", System.currentTimeMillis());
    ctx.add(m);
    int maxTurns;
    try {
        maxTurns = Integer.parseInt(getAiConfig("max_turns"));
    } catch (Exception e) {
        maxTurns = 10;
    }
    while (ctx.size() > maxTurns * 2) {
        ctx.remove(0);
    }
}

JSONArray ctxToMessages(List ctx) {
    JSONArray arr = new JSONArray();
    for (int i = 0; i < ctx.size(); i++) {
        Map m = (Map) ctx.get(i);
        JSONObject j = new JSONObject();
        j.put("role", (String) m.get("role"));
        j.put("content", (String) m.get("content"));
        arr.put(j);
    }
    return arr;
}

// ==================== AI 调用（v13 FC版）====================
Map callAI(String configPrefix, String systemPrompt, JSONArray messages, int maxTokens, JSONArray tools) {
    System.setProperty("http.keepAlive", "true");
    Map cfg = loadAiConfig();

    String apiKey = resolveAiCfg(cfg, configPrefix + "api_key", "api_key");
    if (apiKey.isEmpty()) return null;

    String model = resolveAiCfg(cfg, configPrefix + "model", "model");
    if (model.isEmpty()) model = "deepseek-v4-flash";

    String aiUrl = resolveAiCfg(cfg, configPrefix + "api_url", "ai_url");
    if (aiUrl.isEmpty()) aiUrl = "https://api.deepseek.com";

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
        body.put("temperature", 0.7);
        body.put("max_tokens", maxTokens);

        JSONArray allMsgs = new JSONArray();
        JSONObject sys = new JSONObject();
        sys.put("role", "system");
        sys.put("content", systemPrompt);
        allMsgs.put(sys);
        for (int i = 0; i < messages.length(); i++) {
            allMsgs.put(messages.get(i));
        }
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
            log("error.txt", "AI HTTP " + code + ": " + resp.toString());
            return null;
        }

        JSONObject jResp = new JSONObject(resp.toString());
        JSONArray choices = jResp.getJSONArray("choices");
        Map result = new HashMap();
        if (choices.length() > 0) {
            JSONObject msgObj = choices.getJSONObject(0).getJSONObject("message");
            if (msgObj.has("content")) {
                result.put("content", msgObj.getString("content"));
            } else {
                result.put("content", "");
            }
            if (msgObj.has("tool_calls")) {
                result.put("tool_calls", msgObj.getJSONArray("tool_calls"));
            }
        } else {
            result.put("content", "");
        }
        if (jResp.has("usage")) {
            JSONObject usage = jResp.getJSONObject("usage");
            result.put("prompt_tokens", usage.optInt("prompt_tokens", 0));
            result.put("completion_tokens", usage.optInt("completion_tokens", 0));
        } else {
            result.put("prompt_tokens", 0);
            result.put("completion_tokens", 0);
        }
        return result;
    } catch (Exception e) {
        log("error.txt", "callAI[" + configPrefix + "]: " + e.getMessage());
        return null;
    } finally {
        // 不再断联
    }
}

// ==================== 联网搜索 ====================
String doWebSearch(String query) {
    Map cfg = loadAiConfig();
    String provider = (String) cfg.get("search_provider");
    if (provider == null || provider.isEmpty()) provider = "bocha";
    if ("bing".equals(provider)) return bingSearch(query);
    return bochaSearch(query);
}

String bingSearch(String query) {
    Map cfg = loadAiConfig();
    String apiKey = (String) cfg.get("search_api_key");
    if (apiKey == null || apiKey.isEmpty()) return "[搜索失败: 未配置 search_api_key]";
    HttpURLConnection conn = null;
    try {
        String encQuery = URLEncoder.encode(query, "UTF-8");
        URL url = new URL("https://api.bing.microsoft.com/v7.0/search?q=" + encQuery + "&count=8&mkt=zh-CN");
        conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Ocp-Apim-Subscription-Key", apiKey);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        conn.connect();
        if (conn.getResponseCode() != 200) return "[搜索失败: HTTP " + conn.getResponseCode() + "]";
        BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
        StringBuilder resp = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) resp.append(line);
        br.close();
        JSONObject jResp = new JSONObject(resp.toString());
        JSONArray results = null;
        if (jResp.has("webPages")) results = jResp.getJSONObject("webPages").getJSONArray("value");
        if (results == null || results.length() == 0) return "[搜索无结果: " + query + "]";
        StringBuilder out = new StringBuilder();
        int count = Math.min(results.length(), 8);
        for (int i = 0; i < count; i++) {
            JSONObject r = results.getJSONObject(i);
            out.append(i + 1).append(". ").append(r.optString("snippet", "(无摘要)")).append("\n");
        }
        return out.toString().trim();
    } catch (Exception e) {
        return "[搜索异常: " + e.getMessage() + "]";
    } finally {
        if (conn != null) conn.disconnect();
    }
}

String bochaSearch(String query) {
    Map cfg = loadAiConfig();
    String apiKey = (String) cfg.get("search_api_key");
    if (apiKey == null || apiKey.isEmpty()) return "[搜索失败: 未配置 search_api_key]";
    HttpURLConnection conn = null;
    try {
        URL url = new URL("https://api.bochaai.com/v1/web-search");
        conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        JSONObject reqBody = new JSONObject();
        reqBody.put("query", query);
        reqBody.put("count", 8);
        reqBody.put("summary", true);
        byte[] postData = reqBody.toString().getBytes("UTF-8");
        conn.setRequestProperty("Content-Length", String.valueOf(postData.length));
        OutputStream os = conn.getOutputStream();
        os.write(postData);
        os.flush();
        os.close();
        if (conn.getResponseCode() != 200) return "[搜索失败: HTTP " + conn.getResponseCode() + "]";
        BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
        StringBuilder resp = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) resp.append(line);
        br.close();
        JSONObject jResp = new JSONObject(resp.toString());
        if (jResp.has("data")) jResp = jResp.getJSONObject("data");
        JSONArray results = null;
        if (jResp.has("webPages")) results = jResp.getJSONObject("webPages").getJSONArray("value");
        if (results == null || results.length() == 0) return "[搜索无结果: " + query + "]";
        StringBuilder out = new StringBuilder();
        int count = Math.min(results.length(), 8);
        for (int i = 0; i < count; i++) {
            JSONObject r = results.getJSONObject(i);
            out.append(i + 1).append(". ");
            String summary = r.optString("summary", "");
            if (!summary.isEmpty()) {
                if (summary.length() > 300) summary = summary.substring(0, 300) + "...";
                out.append(summary);
            } else {
                out.append(r.optString("snippet", "(无摘要)"));
            }
            out.append("\n");
        }
        return out.toString().trim();
    } catch (Exception e) {
        return "[搜索异常: " + e.getMessage() + "]";
    } finally {
        if (conn != null) conn.disconnect();
    }
}

// ==================== 简单网页抓取 ====================
String fetchWebContentSimple(String urlStr, int maxLen) {
    HttpURLConnection conn = null;
    try {
        URL url = new URL(urlStr);
        conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android 14; Mobile; rv:130.0) Gecko");
        conn.connect();
        if (conn.getResponseCode() != 200) return "[抓取失败: HTTP " + conn.getResponseCode() + "]";
        BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        int total = 0;
        while ((line = br.readLine()) != null && total < maxLen) {
            String t = line.trim();
            if (t.length() == 0) continue;
            int lt = 0;
            for (int i = 0; i < t.length(); i++) {
                if (t.charAt(i) == '<') lt++;
            }
            if (lt > 3) continue;
            sb.append(t + "\n");
            total = total + t.length();
        }
        br.close();
        String result = sb.toString().trim();
        if (result.length() == 0) return "[页面无有效文本]";
        if (result.length() > maxLen) result = result.substring(0, maxLen);
        return result;
    } catch (Exception e) {
        return "[抓取异常: " + e.getMessage() + "]";
    } finally {
        if (conn != null) conn.disconnect();
    }
}

// ==================== v13: Tool Calls 辅助方法 ====================
String getToolArg(JSONObject tc, String key) {
    try {
        String args = tc.getJSONObject("function").getString("arguments");
        JSONObject jo = new JSONObject(args);
        return jo.optString(key, "");
    } catch (Exception e) {
        return "";
    }
}

int getToolArgInt(JSONObject tc, String key) {
    try {
        String args = tc.getJSONObject("function").getString("arguments");
        JSONObject jo = new JSONObject(args);
        return jo.optInt(key, -1);
    } catch (Exception e) {
        return -1;
    }
}

// ==================== Debug 输出 ====================
void sendDebug(String peerUin, int chatType, String text) {
    try {
        sendMsg(peerUin, "[DEBUG] " + text, chatType);
    } catch (Exception e) { }
}

// ==================== /ai memory ====================
void handleAiMemory(Object msg, String args) {
    String senderUin = String.valueOf(msg.userUin);
    String userRole = getRole(senderUin);
    String[] parts = args.split("\\s+");
    String sub = (parts.length > 0) ? parts[0] : "";

    if (sub.isEmpty()) {
        List my = getMyMemories(senderUin, 15);
        List pub = getPublicMemories(5);
        Map pool = getTagPool(senderUin);
        Map pubPool = getPublicTagPool();
        StringBuilder sb = new StringBuilder();
        sb.append("[我的记忆] 共 ");
        sb.append(getMemoryCount(senderUin));
        sb.append(" 条");

        if (!pool.isEmpty()) {
            sb.append("\n[私有标签] ");
            int c = 0;
            for (Object e : pool.entrySet()) {
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
                    sb.append(" ...");
                    break;
                }
            }
        }

        if (!pubPool.isEmpty()) {
            sb.append("\n[公有标签] ");
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
                    sb.append(" ...");
                    break;
                }
            }
        }

        if (my.isEmpty()) {
            sb.append("\n暂无私有记忆");
        } else {
            sb.append("\n(最近 ");
            sb.append(Math.min(my.size(), 15));
            sb.append(" 条):\n");
            for (int i = 0; i < my.size(); i++) {
                Map m = (Map) my.get(i);
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

        if (!pub.isEmpty()) {
            sb.append("\n[公有记忆] 共 ");
            sb.append(pub.size());
            sb.append(" 条:\n");
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

    if (sub.equals("search")) {
        if (parts.length < 2) {
            sendStyledHeader(msg, "ERROR",
                "用法: /ai memory search <kw|tag:x|tags:x,y|pub:xxx|pub tags:x,y>");
            return;
        }
        String kw = parts[1];
        List found;

        if (kw.startsWith("pub tags:")) {
            String raw = kw.substring(9);
            String[] tl = raw.split(",");
            List ta = new ArrayList();
            for (int i = 0; i < tl.length; i++) {
                String t = tl[i].trim();
                if (!t.isEmpty()) {
                    ta.add(t);
                }
            }
            found = new ArrayList();
            for (int i = 0; i < ta.size(); i++) {
                List r = searchPublicByTag((String) ta.get(i));
                for (int j = 0; j < r.size(); j++) {
                    found.add(r.get(j));
                }
            }
        } else if (kw.startsWith("pub:")) {
            found = searchPublicMemories(kw.substring(4));
        } else if (kw.startsWith("tags:")) {
            String raw = kw.substring(5);
            String[] tl = raw.split(",");
            List ta = new ArrayList();
            for (int i = 0; i < tl.length; i++) {
                String t = tl[i].trim();
                if (!t.isEmpty()) {
                    ta.add(t);
                }
            }
            found = new ArrayList();
            for (int i = 0; i < ta.size(); i++) {
                List r = searchMemoriesByTag(senderUin, (String) ta.get(i));
                for (int j = 0; j < r.size(); j++) {
                    found.add(r.get(j));
                }
            }
        } else if (kw.startsWith("tag:")) {
            found = searchMemoriesByTag(senderUin, kw.substring(4));
        } else {
            List pf = searchMemories(senderUin, kw);
            List puf = searchPublicMemories(kw);
            found = new ArrayList();
            for (int i = 0; i < pf.size(); i++) {
                Map m = (Map) pf.get(i);
                m.put("scope", "private");
                found.add(m);
            }
            for (int i = 0; i < puf.size(); i++) {
                Map m = (Map) puf.get(i);
                m.put("scope", "public");
                found.add(m);
            }
        }

        if (found.isEmpty()) {
            sendStyledHeader(msg, "INFO", "没有匹配 \"" + kw + "\" 的内容");
        } else {
            StringBuilder sb = new StringBuilder();
            sb.append("[搜索 \"");
            sb.append(kw);
            sb.append("\"] 共 ");
            sb.append(found.size());
            sb.append(" 条:\n");
            for (int i = 0; i < found.size(); i++) {
                Map m = (Map) found.get(i);
                String t = (String) m.get("tags");
                sb.append("#");
                sb.append(m.get("id"));
                sb.append(" [");
                sb.append(m.get("scope"));
                sb.append("] ");
                if (t != null && !t.isEmpty()) {
                    sb.append("[");
                    sb.append(t);
                    sb.append("] ");
                }
                sb.append(m.get("content"));
                sb.append("\n");
            }
            sendStyledHeader(msg, "INFO", sb.toString());
        }
        return;
    }

    if (sub.equals("tags")) {
        Map pool = getTagPool(senderUin);
        Map pubPool = getPublicTagPool();
        StringBuilder sb = new StringBuilder();
        sb.append("[私有标签] ");
        sb.append(pool.size());
        sb.append(" 个:\n");
        if (pool.isEmpty()) {
            sb.append("(无)\n");
        } else {
            for (Object e : pool.entrySet()) {
                Map.Entry en = (Map.Entry) e;
                sb.append("  ");
                sb.append(en.getKey());
                sb.append(" (");
                sb.append(en.getValue());
                sb.append("条)\n");
            }
        }

        sb.append("[公有标签] ");
        sb.append(pubPool.size());
        sb.append(" 个:\n");
        if (pubPool.isEmpty()) {
            sb.append("(无)\n");
        } else {
            for (Object e : pubPool.entrySet()) {
                Map.Entry en = (Map.Entry) e;
                sb.append("  ");
                sb.append(en.getKey());
                sb.append(" (");
                sb.append(en.getValue());
                sb.append("条)\n");
            }
        }
        sendStyledHeader(msg, "INFO", sb.toString());
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
        boolean ok = storeMemory(senderUin, ct.toString(), tags, "private", senderUin);
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

    if (sub.equals("rebuild")) {
        if (!userRole.equals("ADMIN") && !userRole.equals("ROOT")) {
            sendStyledHeader(msg, "ERROR", "权限不足");
            return;
        }
        rebuildTagPool(senderUin);
        rebuildPublicTagPool();
        sendStyledHeader(msg, "INFO", "标签池已重建");
        return;
    }

    if (sub.equals("reset")) {
        if (!userRole.equals("ROOT")) {
            sendStyledHeader(msg, "ERROR", "权限不足");
            return;
        }
        SQLiteDatabase db = getDb();
        try {
            db.beginTransaction();
            db.delete("memories", null, null);
            db.delete("tag_pool", null, null);
            db.setTransactionSuccessful();
        } catch (Exception e) {
            log("error.txt", "reset: " + e.getMessage());
        } finally {
            db.endTransaction();
        }
        tagPoolCache = null;
        tagPoolCacheTime = 0;
        tagPoolCacheUin = "";
        sendStyledHeader(msg, "SUCCESS", "已清空全部记忆和标签池");
        return;
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
            if (!userRole.equals("ADMIN") && !userRole.equals("ROOT")) {
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
            boolean ok = storeMemory("PUBLIC", ct.toString(), tags, "public", senderUin);
            if (ok) {
                sendStyledHeader(msg, "SUCCESS", "已添加公有记忆");
            } else {
                sendStyledHeader(msg, "ERROR", "添加失败");
            }
            return;
        }

        if (parts[1].equals("rm")) {
            if (!userRole.equals("ADMIN") && !userRole.equals("ROOT")) {
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
        if (!userRole.equals("ADMIN") && !userRole.equals("ROOT")) {
            sendStyledHeader(msg, "ERROR", "权限不足");
            return;
        }
        List all = getAllMemoriesAdmin(50);
        if (all.isEmpty()) {
            sendStyledHeader(msg, "INFO", "数据库为空");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[全部记忆] ");
        sb.append(all.size());
        sb.append(" 条:\n");
        for (int i = 0; i < all.size(); i++) {
            Map m = (Map) all.get(i);
            sb.append("#");
            sb.append(m.get("id"));
            sb.append(" [");
            sb.append(m.get("scope"));
            sb.append("] ");
            if (!"PUBLIC".equals(m.get("uin"))) {
                sb.append("(");
                sb.append(m.get("uin"));
                sb.append(") ");
            }
            String t = (String) m.get("tags");
            if (t != null && !t.isEmpty()) {
                sb.append("[");
                sb.append(t);
                sb.append("] ");
            }
            sb.append(m.get("content"));
            sb.append("\n");
        }
        sendStyledHeader(msg, "INFO", sb.toString());
        return;
    }

    if (sub.equals("admin")) {
        if (!userRole.equals("ADMIN") && !userRole.equals("ROOT")) {
            sendStyledHeader(msg, "ERROR", "权限不足");
            return;
        }
        if (parts.length < 2) {
            sendStyledHeader(msg, "ERROR", "用法: /ai memory admin search/set/rm");
            return;
        }
        String action = parts[1];

        if (action.equals("search")) {
            if (parts.length < 3) {
                sendStyledHeader(msg, "ERROR", "用法: /ai memory admin search <kw>");
                return;
            }
            List found = searchAllMemoriesAdmin(parts[2], 30);
            if (found.isEmpty()) {
                sendStyledHeader(msg, "INFO", "未找到");
                return;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("[搜索 \"");
            sb.append(parts[2]);
            sb.append("\"] ");
            sb.append(found.size());
            sb.append(" 条:\n");
            for (int i = 0; i < found.size(); i++) {
                Map m = (Map) found.get(i);
                sb.append("#");
                sb.append(m.get("id"));
                sb.append(" [");
                sb.append(m.get("scope"));
                sb.append("] ");
                if (!"PUBLIC".equals(m.get("uin"))) {
                    sb.append("(");
                    sb.append(m.get("uin"));
                    sb.append(") ");
                }
                String t = (String) m.get("tags");
                if (t != null && !t.isEmpty()) {
                    sb.append("[");
                    sb.append(t);
                    sb.append("] ");
                }
                sb.append(m.get("content"));
                sb.append("\n");
            }
            sendStyledHeader(msg, "INFO", sb.toString());
            return;
        }

        if (action.equals("set")) {
            if (parts.length < 4) {
                sendStyledHeader(msg, "ERROR", "用法: /ai memory admin set <UIN> [tags:x,y] <内容>");
                return;
            }
            String target = parts[2];
            String tags = "";
            int cs = 3;
            if (parts.length > 3 && parts[3].startsWith("tags:")) {
                tags = parts[3].substring(5);
                cs = 4;
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
            boolean ok = storeMemory(target, ct.toString(), tags, "private", target);
            if (ok) {
                sendStyledHeader(msg, "SUCCESS", "已为 " + target + " 添加");
            } else {
                sendStyledHeader(msg, "ERROR", "添加失败");
            }
            return;
        }

        if (action.equals("rm")) {
            if (parts.length < 3) {
                sendStyledHeader(msg, "ERROR", "用法: /ai memory admin rm <id>");
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
                sendStyledHeader(msg, "SUCCESS", "已删除 #" + id);
            } else {
                sendStyledHeader(msg, "ERROR", "删除失败");
            }
            return;
        }

        sendStyledHeader(msg, "ERROR", "用法: admin search <kw> | admin set <uin> <c> | admin rm <id>");
        return;
    }

    if (sub.equals("tidy")) {
        if (parts.length >= 2 && parts[1].equals("auto")) {
            SQLiteDatabase db = getDb();
            int total = 0;
            Cursor c = null;
            try {
                c = db.rawQuery(
                    "SELECT id, tags FROM memories WHERE uin = ? AND scope = 'private' AND " +
                    "(tags = '' OR tags IS NULL OR accessed_at < ?)",
                    new String[]{senderUin, String.valueOf(System.currentTimeMillis() - 30L * 24 * 3600 * 1000)}
                );
                while (c.moveToNext()) {
                    long id = c.getLong(0);
                    String ts = c.getString(1);
                    deleteMemoryById(id, senderUin, userRole);
                    total++;
                }
            } catch (Exception e) {
                log("error.txt", "tidy: " + e.getMessage());
            } finally {
                if (c != null) c.close();
            }
            sendStyledHeader(msg, "SUCCESS", "已清理 " + total + " 条（无标签 或 30天未访问）");
            return;
        }

        List orphan = new ArrayList();
        List stale = new ArrayList();
        Cursor c = null;
        try {
            long cutoff = System.currentTimeMillis() - 30L * 24 * 3600 * 1000;
            c = getDb().rawQuery(
                "SELECT id, content, tags, accessed_at FROM memories WHERE uin = ? AND scope = 'private' " +
                "AND (tags = '' OR tags IS NULL OR accessed_at < ?) ORDER BY accessed_at ASC LIMIT 30",
                new String[]{senderUin, String.valueOf(cutoff)}
            );
            while (c.moveToNext()) {
                Map m = new HashMap();
                m.put("id", c.getLong(0));
                m.put("content", c.getString(1));
                m.put("tags", c.getString(2));
                m.put("accessed", c.getLong(3));
                String ts = c.getString(2);
                if (ts == null || ts.trim().isEmpty()) {
                    orphan.add(m);
                } else {
                    stale.add(m);
                }
            }
        } catch (Exception e) {
            log("error.txt", "tidy preview: " + e.getMessage());
        } finally {
            if (c != null) c.close();
        }

        StringBuilder sb = new StringBuilder();
        if (!orphan.isEmpty()) {
            sb.append("[无标签记忆] ").append(orphan.size()).append(" 条:\n");
            for (int i = 0; i < orphan.size(); i++) {
                Map m = (Map) orphan.get(i);
                sb.append("  #").append(m.get("id")).append(" ").append(m.get("content")).append("\n");
            }
        }
        if (!stale.isEmpty()) {
            sb.append("[30天未访问] ").append(stale.size()).append(" 条:\n");
            for (int i = 0; i < stale.size(); i++) {
                Map m = (Map) stale.get(i);
                sb.append("  #").append(m.get("id")).append(" [").append(m.get("tags")).append("] ").append(m.get("content")).append("\n");
            }
        }
        if (orphan.isEmpty() && stale.isEmpty()) {
            sb.append("记忆库很干净，无需整理");
        } else {
            sb.append("\n/ai memory tidy auto — 自动清理以上记忆");
        }
        sendStyledHeader(msg, "INFO", sb.toString());
        return;
    }

    if (sub.equals("dedupe")) {
        if (!userRole.equals("ADMIN") && !userRole.equals("ROOT")) {
            sendStyledHeader(msg, "ERROR", "权限不足");
            return;
        }
        Map cfg = loadAiConfig();
        if (((String) cfg.get("api_key")).isEmpty()) {
            sendStyledHeader(msg, "ERROR", "AI 未启用");
            return;
        }

        List all = getMyMemories(senderUin, 200);
        if (all.size() < 2) {
            sendStyledHeader(msg, "INFO", "记忆不足 2 条，无需去重");
            return;
        }

        StringBuilder memList = new StringBuilder();
        for (int i = 0; i < all.size(); i++) {
            Map m = (Map) all.get(i);
            String t = (String) m.get("tags");
            memList.append("#").append(m.get("id"));
            if (t != null && !t.isEmpty()) memList.append(" [").append(t).append("]");
            memList.append(" ").append(m.get("content")).append("\n");
        }

        sendMsg(String.valueOf(msg.peerUin), "[AI] 正在分析记忆重复...", msg.type);

        JSONArray dedupeMsgs = new JSONArray();
        JSONObject dedupeUserMsg = new JSONObject();
        dedupeUserMsg.put("role", "user");
        dedupeUserMsg.put("content",
            "以下是用户 " + senderUin + " 的所有私有记忆。" +
            "找出重复或高度相似的条目，对重复项调用 delete_memory(id) 删除。" +
            "保留最早的一条。不重复则不调用。\n\n" + memList.toString()
        );
        dedupeMsgs.put(dedupeUserMsg);

        StringBuilder dedupePrompt = new StringBuilder();
        dedupePrompt.append("你是记忆整理模块。找出重复记忆并删除。\n");
        dedupePrompt.append("- 含义完全相同或高度相似 → 视为重复\n");
        dedupePrompt.append("- 保留最早创建的（id最小），删除后续重复项\n");
        dedupePrompt.append("- 用 delete_memory(id) 删除\n");

        Map dedupeResult = callAI("ai1_", dedupePrompt.toString(), dedupeMsgs, 1024, null);
        if (dedupeResult == null) {
            sendStyledHeader(msg, "ERROR", "AI 服务不可用");
            return;
        }

        int removed = 0;
        if (dedupeResult.containsKey("tool_calls")) {
            JSONArray tcs = (JSONArray) dedupeResult.get("tool_calls");
            for (int i = 0; i < tcs.length(); i++) {
                JSONObject tc = tcs.getJSONObject(i);
                String fname = tc.getJSONObject("function").getString("name");
                if (fname.equals("delete_memory")) {
                    int fid = getToolArgInt(tc, "id");
                    if (fid > 0 && deleteMemoryById(fid, senderUin, userRole)) {
                        removed++;
                    }
                }
            }
        }

        if (removed > 0) {
            sendStyledHeader(msg, "SUCCESS", "已删除 " + removed + " 条重复记忆");
        } else {
            sendStyledHeader(msg, "INFO", "未发现重复记忆");
        }
        return;
    }

    if (sub.equals("skills")) {
        StringBuilder sb = new StringBuilder();
        sb.append("[技能列表]\n");
        File dir = new File(pluginPath + "/config/skills");
        if (!dir.exists() || !dir.isDirectory()) {
            sb.append("skills 目录不存在");
            sendStyledHeader(msg, "INFO", sb.toString());
            return;
        }
        File[] files = dir.listFiles(new FilenameFilter() {
            public boolean accept(File d, String name) {
                return name.endsWith(".skill.txt");
            }
        });
        if (files == null || files.length == 0) {
            sb.append("无技能文件");
            sendStyledHeader(msg, "INFO", sb.toString());
            return;
        }
        sb.append(files.length);
        sb.append(" 个技能:\n");
        for (int i = 0; i < files.length; i++) {
            String name = files[i].getName();
            sb.append("  ");
            sb.append(name.substring(0, name.length() - ".skill.txt".length()));
            sb.append(" (");
            sb.append(files[i].lastModified());
            sb.append(")\n");
        }
        String sc = loadSkills();
        if (!sc.isEmpty()) {
            sb.append("\n内容:\n");
            sb.append(sc);
        }
        sendStyledHeader(msg, "INFO", sb.toString());
        return;
    }

    sendStyledHeader(msg, "ERROR",
        "未知: /ai memory " + sub +
        "\n可用: search/set/rm/tags/public/all/admin/rebuild/reset/skills/tidy/dedupe");
}

// ==================== /ai 子命令 ====================
void handleAiSet(Object msg, String args) {
    String role = getRole(String.valueOf(msg.userUin));
    if (!role.equals("ADMIN") && !role.equals("ROOT")) { sendStyledHeader(msg, "ERROR", "权限不足"); return; }
    String[] parts = args.split("\\s+", 2);
    if (parts.length < 2) {
        sendStyledHeader(msg, "ERROR",
            "用法: /ai set <key> <value>\n" +
            "AI2: api_key, model, ai_url\n" +
            "AI1: ai1_model, ai1_api_key, ai1_api_url, ai1_max_tokens\n" +
            "上下文: context_ttl, max_turns\n" +
            "搜索: search_provider, search_api_key\n" +
            "其他: show_stats (1 or 0), debug (0 or 1)"
        );
        return;
    }
    String key = parts[0].trim();
    String value = parts[1].trim();
    String[] vk = {
        "api_key", "model", "ai_url",
        "ai1_model", "ai1_api_key", "ai1_api_url", "ai1_max_tokens",
        "context_ttl", "max_turns",
        "search_provider", "search_api_key",
        "show_stats", "debug"
    };
    boolean valid = false;
    for (int i = 0; i < vk.length; i++) { if (vk[i].equals(key)) { valid = true; break; } }
    if (!valid) { sendStyledHeader(msg, "ERROR", "无效: " + key); return; }
    if (key.equals("context_ttl") || key.equals("max_turns") || key.equals("ai1_max_tokens") || key.equals("show_stats") || key.equals("debug")) {
        try { Integer.parseInt(value); } catch (Exception e) { sendStyledHeader(msg, "ERROR", key + " 必须是整数"); return; }
    }
    Map cfg = loadAiConfig();
    cfg.put(key, value);
    saveAiConfig(cfg);
    sendStyledHeader(msg, "INFO", "已更新: " + key);
}

void handleAiConfig(Object msg) {
    if (!requireAdminOrRoot(msg)) return;
    Map cfg = loadAiConfig();
    StringBuilder sb = new StringBuilder("[AI 配置]\n");
    String[] keys = {
        "model", "api_key", "ai_url",
        "ai1_model", "ai1_api_key", "ai1_api_url", "ai1_max_tokens",
        "context_ttl", "max_turns",
        "search_provider", "search_api_key",
        "show_stats", "debug"
    };
    for (int i = 0; i < keys.length; i++) {
        String k = keys[i];
        String v = (String) cfg.get(k);
        if (v == null) v = "";
        if (k.contains("api_key") && v.length() >= 8) v = maskApiKey(v);
        if (k.equals("ai1_model") && v.isEmpty()) v = "(继承 model)";
        if (k.equals("ai1_api_key") && v.isEmpty()) v = "(继承 api_key)";
        if (k.equals("ai1_api_url") && v.isEmpty()) v = "(继承 ai_url)";
        sb.append(k).append(" = ").append(v).append("\n");
    }
    sb.append("default_account = ").append(getDefaultAccount()).append("\n");
    String persona = loadPersona();
    sb.append("prompt.txt = ").append(persona.isEmpty() ? "(未配置)" : "已加载 (" + persona.length() + "字符)").append("\n");
    List ww = loadWakeWords();
    sb.append("唤醒词 = ");
    if (ww.isEmpty()) { sb.append("(未配置)"); }
    else {
        for (int i = 0; i < ww.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(ww.get(i));
        }
    }
    sb.append("\n技能 = ");
    String skills = loadSkills();
    if (skills.isEmpty()) { sb.append("(未配置)"); }
    else {
        File dir = new File(pluginPath + "/config/skills");
        File[] files = dir.listFiles(new FilenameFilter() {
            public boolean accept(File dir, String name) {
                return name.endsWith(".skill.txt");
            }
        });
        sb.append(files != null ? files.length : 0).append(" 个");
    }
    sb.append("\n");
    sendStyledHeader(msg, "INFO", sb.toString());
}

// ==================== /websearch ====================
void handleWebSearch(Object msg, String args) {
    String role = getRole(String.valueOf(msg.userUin));
    if (!role.equals("ADMIN") && !role.equals("ROOT")) { sendStyledHeader(msg, "ERROR", "权限不足"); return; }
    String[] parts = args.split("\\s+");
    String sub = (parts.length > 0) ? parts[0] : "";
    if (sub.isEmpty() || sub.equals("config")) {
        Map cfg = loadAiConfig();
        String sp = (String) cfg.get("search_provider");
        String sk = (String) cfg.get("search_api_key");
        StringBuilder sb = new StringBuilder();
        sb.append("[搜索配置]\nsearch_provider = ").append(sp != null ? sp : "bocha");
        sb.append("\nsearch_api_key = ").append(sk != null && !sk.isEmpty() ? maskApiKey(sk) : "(未设置)");
        sb.append("\n\n可用: bing, bocha");
        sendStyledHeader(msg, "INFO", sb.toString());
        return;
    }
    if (sub.equals("set")) {
        if (parts.length < 3) {
            sendStyledHeader(msg, "ERROR", "用法: /websearch set <key> <value>\n 可选: search_provider, search_api_key");
            return;
        }
        String key = parts[1].trim();
        String value = parts[2].trim();
        if (!key.equals("search_provider") && !key.equals("search_api_key")) { sendStyledHeader(msg, "ERROR", "无效: " + key); return; }
        if (key.equals("search_provider")) {
            value = value.toLowerCase();
            if (!value.equals("bing") && !value.equals("bocha")) {
                sendStyledHeader(msg, "ERROR", "不支持: " + value);
                return;
            }
        }
        Map cfg = loadAiConfig();
        cfg.put(key, value);
        saveAiConfig(cfg);
        sendStyledHeader(msg, "INFO", "已更新: " + key);
        return;
    }
    if (sub.equals("test")) {
        if (parts.length < 2) {
            sendStyledHeader(msg, "ERROR", "用法: /websearch test <关键词>");
            return;
        }
        String query = "";
        for (int i = 1; i < parts.length; i++) {
            if (i > 1) {
                query = query + " ";
            }
            query = query + parts[i];
        }
        sendMsg(String.valueOf(msg.peerUin), "[AI] 正在搜索: " + query, msg.type);
        String result = doWebSearch(query);
        sendStyledHeader(msg, "INFO", result);
        return;
    }
    sendStyledHeader(msg, "ERROR", "用法: /websearch [config|set|test]");
}

void handleSetDefaultAccount(Object msg, String type) {
    if (!getRole(String.valueOf(msg.userUin)).equals("ROOT")) { sendPermissionDenied(msg); return; }
    type = type.trim().toLowerCase();
    if (!type.equals("user") && !type.equals("blocked")) { sendStyledHeader(msg, "ERROR", "/setdefaultaccount user/blocked"); return; }
    setDefaultAccountConfig(type);
    sendStyledHeader(msg, "INFO", "默认账户: " + type + (type.equals("blocked") ? " (白名单)" : " (开放)"));
}

void handleAiForget(Object msg, String keyword) {
    String senderUin = String.valueOf(msg.userUin);
    if (keyword == null || keyword.trim().isEmpty()) { sendStyledHeader(msg, "ERROR", "用法: /ai forget <关键词>"); return; }
    int d = deleteMemoriesByKeyword(senderUin, keyword.trim());
    sendStyledHeader(msg, "INFO", d > 0 ? "已删除 " + d + " 条" : "没有匹配的记忆");
}

String maskApiKey(String key) {
    if (key == null || key.length() < 8) return "(未设置)";
    return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
}

// ==================== /ai 主入口（v13 DARA-FC）====================
void handleAi(Object msg, String prompt) {
    long startTime = System.currentTimeMillis();
    int totalPt = 0;
    int totalCt = 0;
    int totalCalls = 0;

    String senderUin = String.valueOf(msg.userUin);
    String peerUin = String.valueOf(msg.peerUin);
    int chatType = msg.type;
    String userRole = getRole(senderUin);
    String senderName = getMemberName(chatType, peerUin, senderUin);
    boolean debug = "1".equals(getAiConfig("debug"));

    String trimmed = prompt.trim();
    String forAI1 = trimmed;
    List ww = loadWakeWords();
    for (int i = 0; i < ww.size(); i++) {
        String w = (String) ww.get(i);
        if (forAI1.startsWith(w)) {
            forAI1 = forAI1.substring(w.length());
            if (forAI1.startsWith("，") || forAI1.startsWith(",")
                || forAI1.startsWith(" ") || forAI1.startsWith("　")) {
                forAI1 = forAI1.substring(1);
            }
            forAI1 = forAI1.trim();
            break;
        }
    }

    if (trimmed.equalsIgnoreCase("off")) {
        if (!userRole.equals("ADMIN") && !userRole.equals("ROOT")) { sendStyledHeader(msg, "ERROR", "权限不足"); return; }
        addToList(pluginPath + "/config/disabled_conversations.txt", peerUin + "_" + chatType);
        sendStyledHeader(msg, "INFO", "当前会话 AI 已禁用"); return;
    }
    if (trimmed.equalsIgnoreCase("on")) {
        if (!userRole.equals("ADMIN") && !userRole.equals("ROOT")) { sendStyledHeader(msg, "ERROR", "权限不足"); return; }
        removeFromList(pluginPath + "/config/disabled_conversations.txt", peerUin + "_" + chatType);
        sendStyledHeader(msg, "INFO", "当前会话 AI 已启用"); return;
    }
    if (trimmed.equalsIgnoreCase("status")) {
        Set dis = readStringSet(pluginPath + "/config/disabled_conversations.txt");
        sendStyledHeader(msg, "INFO", "当前会话: AI " + (dis.contains(peerUin + "_" + chatType) ? "已禁用" : "已启用"));
        return;
    }

    if (readStringSet(pluginPath + "/config/disabled_conversations.txt").contains(peerUin + "_" + chatType)) {
        sendStyledHeader(msg, "INFO", "当前会话已禁用 AI。管理员: /ai on");
        return;
    }

    if (!canUseAi(senderUin)) {
        sendStyledHeader(msg, "ERROR", "没有 AI 权限" + (getRole(senderUin).equals("BLOCKED") ? "（已拉黑）" : "（不在白名单）"));
        return;
    }

    if (trimmed.equalsIgnoreCase("clear")) {
        if (!userRole.equals("ADMIN") && !userRole.equals("ROOT")) { sendStyledHeader(msg, "ERROR", "权限不足"); return; }
        clearAiContext(peerUin, chatType);
        sendStyledHeader(msg, "INFO", "上下文已清除"); return;
    }
    if (trimmed.equalsIgnoreCase("config")) { handleAiConfig(msg); return; }
    if (trimmed.startsWith("set ")) { handleAiSet(msg, trimmed.substring(4).trim()); return; }
    if (trimmed.equals("memory") || trimmed.startsWith("memory ")) {
        handleAiMemory(msg, trimmed.startsWith("memory ") ? trimmed.substring(7).trim() : "");
        return;
    }
    if (trimmed.startsWith("forget ")) { handleAiForget(msg, trimmed.substring(7).trim()); return; }

    if (trimmed.equals("debug") || trimmed.startsWith("debug ")) {
        String[] dp = trimmed.split("\\s+");
        if (dp.length == 1) {
            sendStyledHeader(msg, "INFO", "debug = " + getAiConfig("debug") + " (0=关 1=开)");
        } else if (dp[1].equals("0") || dp[1].equals("1")) {
            Map cfg = loadAiConfig();
            cfg.put("debug", dp[1]);
            saveAiConfig(cfg);
            sendStyledHeader(msg, "INFO", "debug = " + dp[1]);
        } else {
            sendStyledHeader(msg, "ERROR", "用法: /ai debug 0 或 /ai debug 1");
        }
        return;
    }

    Map cfg = loadAiConfig();
    if (((String) cfg.get("api_key")).isEmpty()) {
        sendStyledHeader(msg, "ERROR", "AI 未启用。管理员: /ai set api_key <key>");
        return;
    }

    getDb();
    List ctx = getAiContext(peerUin, chatType);

    // 提取引用消息
    String quotedText = "";
    try {
        Object data = msg.data;
        if (data != null) {
            java.util.List elements = (java.util.List) data.getClass().getDeclaredField("elements").get(data);
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
                            if (su != null && !su.toString().isEmpty()) ruin = su.toString();
                        } catch (Exception ex2) {}
                        if (ruin.isEmpty()) {
                            try {
                                java.lang.reflect.Field sf = re.getClass().getDeclaredField("uin");
                                sf.setAccessible(true);
                                Object su = sf.get(re);
                                if (su != null && !su.toString().isEmpty()) ruin = su.toString();
                            } catch (Exception ex2) {}
                        }
                        try {
                            java.lang.reflect.Field sf = re.getClass().getDeclaredField("sourceMsgText");
                            sf.setAccessible(true);
                            Object src = sf.get(re);
                            if (src != null && !src.toString().isEmpty()) {
                                if (!ruin.isEmpty()) {
                                    quotedText = "[" + getMemberName(chatType, peerUin, ruin) +
                                        "(UIN:" + ruin + "," + getRole(ruin) + ")] " + src.toString();
                                } else {
                                    quotedText = src.toString();
                                }
                            }
                        } catch (Exception ex2) {}
                        break;
                    }
                }
            }
        }
    } catch (Exception ignored) {}

    String atInfo = "";
    try {
        if (msg.atList != null && msg.atList.size() > 0) {
            StringBuilder atSb = new StringBuilder();
            for (int ai = 0; ai < msg.atList.size(); ai++) {
                String atUin = String.valueOf(msg.atList.get(ai));
                if (atUin.equals(myUin)) continue;
                String atName = getMemberName(chatType, peerUin, atUin);
                String atRole = getRole(atUin);
                if (atSb.length() > 0) atSb.append(" ");
                atSb.append("@").append(atName).append("(UIN:").append(atUin).append(",").append(atRole).append(")");
            }
            if (atSb.length() > 0) {
                atInfo = " 目标: " + atSb.toString();
            }
        }
    } catch (Exception ignored) {}

    String idPrompt = "[UIN:" + senderUin + ", 名称:" + senderName + ", 角色:" + userRole + "] " +
        (quotedText.isEmpty() ? "" : "↳ 引用: " + quotedText + " | ") +
        atInfo +
        (atInfo.isEmpty() ? "" : " | ") +
        prompt;

    // ==================== 预检索 ====================
    StringBuilder preCtx = new StringBuilder();
    Set preSeen = new HashSet();
    Map pubPoolPre = getPublicTagPool();
    Map privPoolPre = getTagPool(senderUin);
    String scanText = forAI1.isEmpty() ? trimmed : forAI1;
    String[] scanWords = scanText.split("[\\s，。？！,.!?、：；\\[\\]「」《》（）—…]+");
    for (int wi = 0; wi < scanWords.length; wi++) {
        String w = scanWords[wi].trim();
        if (w.length() < 4 || !w.matches("\\d+")) continue;

        if (privPoolPre.containsKey(w)) {
            List r = searchMemoriesByTag(senderUin, w);
            for (int ri = 0; ri < r.size(); ri++) {
                Map m = (Map) r.get(ri);
                Long mid = (Long) m.get("id");
                if (!preSeen.contains(mid)) {
                    preSeen.add(mid);
                    preCtx.append("  [预检:私有标签] #").append(mid).append(": ").append(m.get("content")).append("\n");
                }
            }
        }
        if (pubPoolPre.containsKey(w)) {
            List r = searchPublicByTag(w);
            for (int ri = 0; ri < r.size(); ri++) {
                Map m = (Map) r.get(ri);
                Long mid = (Long) m.get("id");
                if (!preSeen.contains(mid)) {
                    preSeen.add(mid);
                    preCtx.append("  [预检:公有标签] #").append(mid).append(": ").append(m.get("content")).append("\n");
                }
            }
        }
        if (!privPoolPre.containsKey(w) && !pubPoolPre.containsKey(w)) {
            List privR = searchMemories(senderUin, w);
            List pubR = searchPublicMemories(w);
            for (int ri = 0; ri < privR.size(); ri++) {
                Map m = (Map) privR.get(ri);
                Long mid = (Long) m.get("id");
                if (!preSeen.contains(mid)) {
                    preSeen.add(mid);
                    preCtx.append("  [预检:私有] #").append(mid).append(": ").append(m.get("content")).append("\n");
                }
            }
            for (int ri = 0; ri < pubR.size(); ri++) {
                Map m = (Map) pubR.get(ri);
                Long mid = (Long) m.get("id");
                if (!preSeen.contains(mid)) {
                    preSeen.add(mid);
                    preCtx.append("  [预检:公有] #").append(mid).append(": ").append(m.get("content")).append("\n");
                }
            }
        }
    }

    if (debug && preCtx.length() > 0) {
        sendDebug(peerUin, chatType, "预检索结果:\n" + preCtx.toString());
    }

    // ==================== 第一阶段：AI1 检索规划 (FC) ====================
    if (debug) sendDebug(peerUin, chatType, "--- AI1 检索规划 ---");

    Map tagPool = getTagPool(senderUin);
    Map pubTagPool = getPublicTagPool();
    StringBuilder tagPoolStr = new StringBuilder();
    int tc = 0;
    for (Object e : tagPool.entrySet()) {
        Map.Entry en = (Map.Entry) e;
        if (tc > 0) tagPoolStr.append(", ");
        tagPoolStr.append(en.getKey()).append("(").append(en.getValue()).append(")");
        tc++;
        if (tc >= 30) { tagPoolStr.append(" ..."); break; }
    }
    if (!pubTagPool.isEmpty()) {
        if (tc > 0) tagPoolStr.append("; ");
        tagPoolStr.append("[公有] ");
        int pc = 0;
        for (Object e : pubTagPool.entrySet()) {
            Map.Entry en = (Map.Entry) e;
            if (pc > 0) tagPoolStr.append(", ");
            tagPoolStr.append(en.getKey()).append("(").append(en.getValue()).append(")");
            pc++;
            if (pc >= 20) { tagPoolStr.append(" ..."); break; }
        }
    }

    String ai1Prompt = buildAI1Prompt(tagPoolStr.toString(), senderUin, senderName, userRole);
    JSONArray ai1Tools = buildAI1Tools();

    JSONArray ai1Msgs = new JSONArray();
    JSONObject ai1UserMsg = new JSONObject();
    ai1UserMsg.put("role", "user");
    ai1UserMsg.put("content", forAI1.isEmpty() ? idPrompt :
        "[UIN:" + senderUin + ", 名称:" + senderName + ", 角色:" + userRole + "] " +
        (quotedText.isEmpty() ? "" : "↳ 引用: " + quotedText + " | ") + forAI1);
    ai1Msgs.put(ai1UserMsg);

    int ai1MaxTokens;
    try { ai1MaxTokens = Integer.parseInt(getAiConfig("ai1_max_tokens")); } catch (Exception e) { ai1MaxTokens = 512; }

    Map ai1Result = callAI("ai1_", ai1Prompt, ai1Msgs, ai1MaxTokens, ai1Tools);
    totalCalls++;

    if (ai1Result != null) {
        try { totalPt += Integer.parseInt(String.valueOf(ai1Result.get("prompt_tokens"))); } catch (Exception e) { }
        try { totalCt += Integer.parseInt(String.valueOf(ai1Result.get("completion_tokens"))); } catch (Exception e) { }
    }

    // ==================== 第二阶段：DREX 执行检索 ====================
    if (debug) sendDebug(peerUin, chatType, "--- 检索执行 ---");

    StringBuilder retrievalCtx = new StringBuilder();
    Set seenIds = new HashSet();
    if (preCtx.length() > 0) {
        retrievalCtx.append("[预检索自动匹配]\n").append(preCtx.toString());
        seenIds.addAll(preSeen);
    }

    if (ai1Result != null && ai1Result.containsKey("tool_calls")) {
        JSONArray tcs = (JSONArray) ai1Result.get("tool_calls");
        if (debug) sendDebug(peerUin, chatType, "AI1 tool_calls (" + tcs.length() + "个)");

        for (int ti = 0; ti < tcs.length(); ti++) {
            JSONObject tc = tcs.getJSONObject(ti);
            String fname = tc.getJSONObject("function").getString("name");
            List results = null;
            String label = "";

            if (fname.equals("search_by_tag")) {
                String v = getToolArg(tc, "tag");
                if (!v.isEmpty()) {
                    results = searchMemoriesByTag(senderUin, v);
                    label = "私有标签:" + v;
                }
                if ((results == null || results.isEmpty()) && !v.isEmpty()) {
                    results = searchMemories(senderUin, v);
                    label = "私有标签(降级):" + v;
                }
            } else if (fname.equals("search_by_keyword")) {
                String v = getToolArg(tc, "keyword");
                if (!v.isEmpty()) {
                    results = searchMemories(senderUin, v);
                    label = "私有关键词:" + v;
                }
            } else if (fname.equals("search_public_by_tag")) {
                String v = getToolArg(tc, "tag");
                if (!v.isEmpty()) {
                    results = searchPublicByTag(v);
                    label = "公有标签:" + v;
                }
            } else if (fname.equals("search_public")) {
                String v = getToolArg(tc, "keyword");
                if (!v.isEmpty()) {
                    results = searchPublicMemories(v);
                    label = "公有关键词:" + v;
                }
            } else if (fname.equals("search_all")) {
                String v = getToolArg(tc, "keyword");
                if (!v.isEmpty()) {
                    results = searchAllMemoriesByKeyword(v, 20);
                    label = "全库:" + v;
                }
            } else if (fname.equals("get_recent")) {
                results = getRecentMemories(senderUin, 10);
                label = "最近记忆";
            }

            if (results != null && !results.isEmpty()) {
                retrievalCtx.append("[").append(label).append("]\n");
                for (int ri = 0; ri < results.size(); ri++) {
                    Map m = (Map) results.get(ri);
                    Long mid = (Long) m.get("id");
                    if (seenIds.contains(mid)) continue;
                    seenIds.add(mid);
                    retrievalCtx.append("  [").append(m.get("scope")).append("] #").append(mid);
                    String suin = (String) m.get("subjectUin");
                    String muin = m.get("uin") != null ? m.get("uin").toString() : "";
                    if (suin != null && !suin.isEmpty() && !suin.equals(muin)) {
                        retrievalCtx.append(" (转述").append(suin).append(")");
                    }
                    retrievalCtx.append(": ").append(m.get("content")).append("\n");
                }
            }
        }
    }

    if (seenIds.isEmpty()) {
        List recent = getRecentMemories(senderUin, 5);
        if (!recent.isEmpty()) {
            retrievalCtx.append("[兜底:最近记忆]\n");
            for (int ri = 0; ri < recent.size(); ri++) {
                Map m = (Map) recent.get(ri);
                retrievalCtx.append("  [私有] #").append(m.get("id")).append(": ").append(m.get("content")).append("\n");
            }
        }
    }

    if (debug) {
        String rcStr = retrievalCtx.toString();
        sendDebug(peerUin, chatType, "检索上下文:\n" + (rcStr.isEmpty() ? "(无结果)" : rcStr));
    }

    // ==================== 第三阶段：AI2 应答 R1 (FC) ====================
    if (debug) sendDebug(peerUin, chatType, "--- AI2 应答 ---");

    addToContext(ctx, "user", idPrompt);

    String ai2Prompt = buildAI2Prompt(userRole, senderUin, senderName, chatType, peerUin);
    JSONArray ai2Tools = buildAI2Tools();

    JSONArray ai2Msgs = new JSONArray();
    for (int i = 0; i < ctx.size(); i++) {
        Map m = (Map) ctx.get(i);
        JSONObject j = new JSONObject();
        j.put("role", m.get("role"));
        j.put("content", m.get("content"));
        ai2Msgs.put(j);
    }

    JSONObject retrievalMsg = new JSONObject();
    retrievalMsg.put("role", "system");
    retrievalMsg.put("content",
        "（以下是你已知的记忆，用户看不到这段话）\n" +
        (retrievalCtx.length() > 0 ? retrievalCtx.toString() : "(无)") +
        "\n如果记忆不足以回答问题，调用 search_web。");
    ai2Msgs.put(retrievalMsg);

    Map ai2Result = callAI("", ai2Prompt, ai2Msgs, 8192, ai2Tools);
    totalCalls++;

    String ai2Content = "";
    JSONArray ai2ToolCalls = null;
    if (ai2Result != null) {
        ai2Content = (String) ai2Result.getOrDefault("content", "");
        if (ai2Result.containsKey("tool_calls")) {
            ai2ToolCalls = (JSONArray) ai2Result.get("tool_calls");
        }
        try { totalPt += Integer.parseInt(String.valueOf(ai2Result.get("prompt_tokens"))); } catch (Exception e) { }
        try { totalCt += Integer.parseInt(String.valueOf(ai2Result.get("completion_tokens"))); } catch (Exception e) { }
    } else {
        sendStyledHeader(msg, "ERROR", "AI 服务暂时不可用");
        return;
    }

    if (debug) {
        String dbg = "AI2 R1 content: " + ai2Content;
        if (ai2ToolCalls != null) {
            dbg = dbg + "\ntool_calls: " + ai2ToolCalls.length() + "个";
            for (int i = 0; i < ai2ToolCalls.length(); i++) {
                JSONObject tc = ai2ToolCalls.getJSONObject(i);
                dbg = dbg + "\n  " + tc.getJSONObject("function").getString("name") +
                    "(" + tc.getJSONObject("function").getString("arguments") + ")";
            }
        }
        sendDebug(peerUin, chatType, dbg);
    }

    // ==================== 第四阶段：DREX 分流处理 ====================
    boolean hasSentReply = false;
    boolean isFirstReply = true;

    // Step A: 先发送 content（如果有）
    if (!ai2Content.isEmpty()) {
        while (ai2Content.indexOf("[SPLIT][SPLIT]") != -1) {
            ai2Content = ai2Content.replace("[SPLIT][SPLIT]", "[SPLIT]");
        }
        while (ai2Content.indexOf("[/SPLIT][/SPLIT]") != -1) {
            ai2Content = ai2Content.replace("[/SPLIT][/SPLIT]", "[/SPLIT]");
        }
        while (ai2Content.indexOf("[SPLIT]") != -1) {
            int splitOpen = ai2Content.indexOf("[SPLIT]");
            int splitClose = ai2Content.indexOf("[/SPLIT]", splitOpen + "[SPLIT]".length());
            if (splitClose == -1) {
                String before = ai2Content.substring(0, splitOpen).trim();
                if (!before.isEmpty()) {
                    if (isFirstReply) {
                        sendReplyMsg(peerUin, msg.msgId, before, chatType);
                        isFirstReply = false;
                    } else {
                        sendMsg(peerUin, before, chatType);
                    }
                    hasSentReply = true;
                    try { Thread.sleep(80); } catch (Exception ignored) {}
                }
                ai2Content = ai2Content.substring(splitOpen + "[SPLIT]".length());
                break;
            }
            String beforeSplit = ai2Content.substring(0, splitOpen).trim();
            String rawCommand = ai2Content.substring(splitOpen + "[SPLIT]".length(), splitClose).trim();
            ai2Content = ai2Content.substring(splitClose + "[/SPLIT]".length());
            if (!beforeSplit.isEmpty()) {
                if (isFirstReply) {
                    sendReplyMsg(peerUin, msg.msgId, beforeSplit, chatType);
                    isFirstReply = false;
                } else {
                    sendMsg(peerUin, beforeSplit, chatType);
                }
                hasSentReply = true;
                try { Thread.sleep(80); } catch (Exception ignored) {}
            }
            if (!rawCommand.isEmpty()) {
                if (isFirstReply) {
                    sendReplyMsg(peerUin, msg.msgId, rawCommand, chatType);
                    isFirstReply = false;
                } else {
                    sendMsg(peerUin, rawCommand, chatType);
                }
                hasSentReply = true;
                try { Thread.sleep(80); } catch (Exception ignored) {}
            }
        }
        String remain = ai2Content.trim();
        if (!remain.isEmpty()) {
            if (isFirstReply) {
                sendReplyMsg(peerUin, msg.msgId, remain, chatType);
                isFirstReply = false;
            } else {
                sendMsg(peerUin, remain, chatType);
            }
            hasSentReply = true;
            try { Thread.sleep(80); } catch (Exception ignored) {}
        }
    }

    // Step B: 分流 tool_calls
    if (ai2ToolCalls != null && ai2ToolCalls.length() > 0) {
        List memCalls = new ArrayList();
        List searchCalls = new ArrayList();
        List skillCalls = new ArrayList();

        for (int i = 0; i < ai2ToolCalls.length(); i++) {
            JSONObject tc = ai2ToolCalls.getJSONObject(i);
            String fname = tc.getJSONObject("function").getString("name");
            if (fname.equals("search_web") || fname.equals("fetch_page")) {
                searchCalls.add(tc);
            } else if (fname.equals("call_skill")) {
                skillCalls.add(tc);
            } else {
                memCalls.add(tc);
            }
        }

        // 执行技能
        for (int i = 0; i < skillCalls.size(); i++) {
            JSONObject tc = (JSONObject) skillCalls.get(i);
            String cmd = getToolArg(tc, "command");
            if (!cmd.isEmpty()) {
                sendMsg(peerUin, cmd, chatType);
            }
        }

        // 记忆验证轮
        if (!memCalls.isEmpty()) {
            StringBuilder verifyCtx = new StringBuilder();
            verifyCtx.append("[记忆操作验证]\n");
            verifyCtx.append("你刚才表达了以下意向（第2轮静默确认）：\n\n");

            for (int i = 0; i < memCalls.size(); i++) {
                JSONObject tc = (JSONObject) memCalls.get(i);
                String fname = tc.getJSONObject("function").getString("name");

                if (fname.equals("create_memory") || fname.equals("create_public_memory")) {
                    String content = getToolArg(tc, "content");
                    String tags = getToolArg(tc, "tags");
                    String about = getToolArg(tc, "about");
                    verifyCtx.append("意向[").append(fname).append("] tags:").append(tags);
                    verifyCtx.append(" content:").append(content).append("\n");
                    boolean isPriv = fname.equals("create_memory");
                    List matches;
                    if (isPriv) {
                        matches = searchMemories(senderUin, content);
                    } else {
                        matches = searchPublicMemories(content);
                    }
                    if (!matches.isEmpty()) {
                        verifyCtx.append("  → 库里匹配:\n");
                        for (int j = 0; j < matches.size(); j++) {
                            Map m = (Map) matches.get(j);
                            verifyCtx.append("    id#").append(m.get("id"));
                            verifyCtx.append(" [").append(m.get("tags")).append("] ");
                            verifyCtx.append(m.get("content")).append("\n");
                        }
                    } else {
                        verifyCtx.append("  → 库里无匹配，可新建\n");
                    }
                } else if (fname.equals("delete_memory")) {
                    int did = getToolArgInt(tc, "id");
                    verifyCtx.append("意向[delete_memory] id:").append(did).append("\n");
                    if (did > 0) {
                        verifyCtx.append("  → 将按id精确删除\n");
                    }
                } else if (fname.equals("overwrite_memory") || fname.equals("overwrite_public_memory")) {
                    int oid = getToolArgInt(tc, "id");
                    String content = getToolArg(tc, "content");
                    verifyCtx.append("意向[").append(fname).append("] id:").append(oid);
                    verifyCtx.append(" content:").append(content).append("\n");
                }
                verifyCtx.append("\n");
            }

            verifyCtx.append("请确认：新建用 create_memory/create_public_memory，");
            verifyCtx.append("覆写用 overwrite_memory/overwrite_public_memory(id:匹配到的编号)，");
            verifyCtx.append("删除用 delete_memory(id:匹配到的编号)。取消则不调用任何函数。");
            verifyCtx.append("第2轮只调用函数，不输出content。");

            if (debug) sendDebug(peerUin, chatType, "记忆验证请求:\n" + verifyCtx.toString());

            JSONObject verifyMsg = new JSONObject();
            verifyMsg.put("role", "system");
            verifyMsg.put("content", verifyCtx.toString());
            ai2Msgs.put(verifyMsg);

            Map verifyResult = callAI("", ai2Prompt, ai2Msgs, 4096, ai2Tools);
            totalCalls++;

            if (verifyResult != null) {
                try { totalPt += Integer.parseInt(String.valueOf(verifyResult.get("prompt_tokens"))); } catch (Exception e) { }
                try { totalCt += Integer.parseInt(String.valueOf(verifyResult.get("completion_tokens"))); } catch (Exception e) { }

                if (verifyResult.containsKey("tool_calls")) {
                    JSONArray vtcs = (JSONArray) verifyResult.get("tool_calls");
                    if (debug) sendDebug(peerUin, chatType, "记忆验证输出: " + vtcs.length() + "个确认");

                    for (int i = 0; i < vtcs.length(); i++) {
                        JSONObject tc = vtcs.getJSONObject(i);
                        String fname = tc.getJSONObject("function").getString("name");
                        executeMemoryCall(tc, fname, senderUin, userRole);
                    }
                }
            }
        }

        // 搜索循环（最多5轮）
        int sr = 0;
        // 用searchRound追踪
        while (!searchCalls.isEmpty()) {
            JSONObject tc = (JSONObject) searchCalls.get(0);
            String fname = tc.getJSONObject("function").getString("name");
            String sq = "";
            boolean isFetch = fname.equals("fetch_page");
            if (isFetch) {
                sq = getToolArg(tc, "url");
            } else {
                sq = getToolArg(tc, "query");
            }
            if (sq.isEmpty()) break;

            sr++;
            if (debug) sendDebug(peerUin, chatType, "AI2 请求搜索(" + fname + "): " + sq);
            sendMsg(peerUin, isFetch ? "[AI] 正在抓取..." : "[AI] 正在检索: " + sq, chatType);

            String result = isFetch ? fetchWebContentSimple(sq, 3000) : doWebSearch(sq);
            if (result.length() > 2000) result = result.substring(0, 2000) + "...(已截断)";

            String note = "如果信息仍不够，可以再次调用 search_web 或 fetch_page。";
            JSONObject searchResultMsg = new JSONObject();
            searchResultMsg.put("role", "system");
            searchResultMsg.put("content", "[搜索结果: " + sq + "]\n" + result + "\n" + note);
            ai2Msgs.put(searchResultMsg);

            Map ai2r2 = callAI("", ai2Prompt, ai2Msgs, 8192, ai2Tools);
            totalCalls++;
            searchCalls.clear();

            if (ai2r2 != null) {
                try { totalPt += Integer.parseInt(String.valueOf(ai2r2.get("prompt_tokens"))); } catch (Exception e) { }
                try { totalCt += Integer.parseInt(String.valueOf(ai2r2.get("completion_tokens"))); } catch (Exception e) { }

                String r2Content = (String) ai2r2.getOrDefault("content", "");
                JSONArray r2TCs = null;
                if (ai2r2.containsKey("tool_calls")) {
                    r2TCs = (JSONArray) ai2r2.get("tool_calls");
                }

                // 先发 content（SPLIT 分段处理）
                if (!r2Content.isEmpty()) {
                    while (r2Content.indexOf("[SPLIT][SPLIT]") != -1) {
                        r2Content = r2Content.replace("[SPLIT][SPLIT]", "[SPLIT]");
                    }
                    while (r2Content.indexOf("[/SPLIT][/SPLIT]") != -1) {
                        r2Content = r2Content.replace("[/SPLIT][/SPLIT]", "[/SPLIT]");
                    }
                    while (r2Content.indexOf("[SPLIT]") != -1) {
                        int splitOpen = r2Content.indexOf("[SPLIT]");
                        int splitClose = r2Content.indexOf("[/SPLIT]", splitOpen + "[SPLIT]".length());
                        if (splitClose == -1) {
                            String before = r2Content.substring(0, splitOpen).trim();
                            if (!before.isEmpty()) {
                                if (isFirstReply) {
                                    sendReplyMsg(peerUin, msg.msgId, before, chatType);
                                    isFirstReply = false;
                                } else {
                                    sendMsg(peerUin, before, chatType);
                                }
                                hasSentReply = true;
                                try { Thread.sleep(80); } catch (Exception ignored) {}
                            }
                            r2Content = r2Content.substring(splitOpen + "[SPLIT]".length());
                            break;
                        }
                        String beforeSplit = r2Content.substring(0, splitOpen).trim();
                        String inner = r2Content.substring(splitOpen + "[SPLIT]".length(), splitClose).trim();
                        r2Content = r2Content.substring(splitClose + "[/SPLIT]".length());
                        if (!beforeSplit.isEmpty()) {
                            if (isFirstReply) {
                                sendReplyMsg(peerUin, msg.msgId, beforeSplit, chatType);
                                isFirstReply = false;
                            } else {
                                sendMsg(peerUin, beforeSplit, chatType);
                            }
                            hasSentReply = true;
                            try { Thread.sleep(80); } catch (Exception ignored) {}
                        }
                        if (!inner.isEmpty()) {
                            if (isFirstReply) {
                                sendReplyMsg(peerUin, msg.msgId, inner, chatType);
                                isFirstReply = false;
                            } else {
                                sendMsg(peerUin, inner, chatType);
                            }
                            hasSentReply = true;
                            try { Thread.sleep(80); } catch (Exception ignored) {}
                       }
                    }
                    String remain = r2Content.trim();
                    if (!remain.isEmpty()) {
                        if (isFirstReply) {
                            sendReplyMsg(peerUin, msg.msgId, remain, chatType);
                            isFirstReply = false;
                        } else {
                            sendMsg(peerUin, remain, chatType);
                        }
                        hasSentReply = true;
                        try { Thread.sleep(80); } catch (Exception ignored) {}
                    }
                }

                // 分流第二轮 tool_calls
                if (r2TCs != null && r2TCs.length() > 0) {
                    for (int i = 0; i < r2TCs.length(); i++) {
                        JSONObject rtc = r2TCs.getJSONObject(i);
                        String rfname = rtc.getJSONObject("function").getString("name");
                        if (rfname.equals("search_web") || rfname.equals("fetch_page")) {
                            searchCalls.add(rtc);
                        } else if (rfname.equals("call_skill")) {
                            String cmd = getToolArg(rtc, "command");
                            if (!cmd.isEmpty()) {
                                sendMsg(peerUin, cmd, chatType);
                            }
                        } else {
                            executeMemoryCall(rtc, rfname, senderUin, userRole);
                        }
                    }
                }
            } else {
                break;
            }

            // 上限5轮
            if (sr >= 5) break;
        }
    }

    // ==================== 最终兜底 ====================
    if (!hasSentReply) {
        if (isFirstReply) {
            sendReplyMsg(peerUin, msg.msgId, "[AI] 深度思考中...", chatType);
        } else {
            sendMsg(peerUin, "[AI] 深度思考中...", chatType);
        }
    }

    // ==================== 统计 ====================
    StringBuilder finalMsg = new StringBuilder();
    if ("1".equals(getAiConfig("show_stats"))) {
        long elapsed = (System.currentTimeMillis() - startTime) / 1000;
        if (elapsed < 1) elapsed = 1;
        finalMsg.append("----------\n");
        finalMsg.append("Time:").append(getCurrentTime());
        finalMsg.append("\nUser:").append(senderUin).append("(").append(userRole).append(")");
        if (totalPt > 0) finalMsg.append("\nTokenIn:").append(totalPt);
        if (totalCt > 0) finalMsg.append("\nTokenOut:").append(totalCt);
        finalMsg.append("\nThinkTime:").append(elapsed).append("s");
        finalMsg.append("\nAIcalls:").append(totalCalls);
    }
    if (debug) {
        finalMsg.append("\n[DEBUG] AI调用:").append(totalCalls).append("次");
    }
    if (finalMsg.length() > 0) {
        sendMsg(peerUin, finalMsg.toString(), chatType);
    }

    addToContext(ctx, "assistant", ai2Content);
    writeLog(senderUin, "/ai (OK v13)");
}

// ==================== v13: 记忆操作执行 ====================
void executeMemoryCall(JSONObject tc, String fname, String senderUin, String userRole) {
    try {
        if (fname.equals("create_memory")) {
            String content = getToolArg(tc, "content");
            String tags = getToolArg(tc, "tags");
            String about = getToolArg(tc, "about");
            if (content.isEmpty()) return;
            String su = about.isEmpty() ? senderUin : about;
            storeMemory(senderUin, content, tags, "private", su);
        } else if (fname.equals("create_public_memory")) {
            String content = getToolArg(tc, "content");
            String tags = getToolArg(tc, "tags");
            String about = getToolArg(tc, "about");
            if (content.isEmpty()) return;
            String su = about.isEmpty() ? senderUin : about;
            storeMemory("PUBLIC", content, tags, "public", su);
        } else if (fname.equals("overwrite_memory")) {
            int id = getToolArgInt(tc, "id");
            String content = getToolArg(tc, "content");
            String tags = getToolArg(tc, "tags");
            String about = getToolArg(tc, "about");
            if (id <= 0 || content.isEmpty()) return;
            deleteMemoryById(id, senderUin, userRole);
            String su = about.isEmpty() ? senderUin : about;
            storeMemory(senderUin, content, tags, "private", su);
        } else if (fname.equals("overwrite_public_memory")) {
            int id = getToolArgInt(tc, "id");
            String content = getToolArg(tc, "content");
            String tags = getToolArg(tc, "tags");
            String about = getToolArg(tc, "about");
            if (id <= 0 || content.isEmpty()) return;
            deleteMemoryById(id, senderUin, userRole);
            String su = about.isEmpty() ? senderUin : about;
            storeMemory("PUBLIC", content, tags, "public", su);
        } else if (fname.equals("delete_memory")) {
            int id = getToolArgInt(tc, "id");
            if (id > 0) {
                deleteMemoryById(id, senderUin, userRole);
            }
        }
    } catch (Exception e) {
        log("error.txt", "executeMemoryCall: " + e.getMessage());
    }
}

// ==================== 消息工具 ====================
void sendStyledHeader(Object msg, String status, String msgText) {
    String senderUin = String.valueOf(msg.userUin);
    String role = getRole(senderUin);
    StringBuilder sb = new StringBuilder();
    sb.append("[").append(status).append("] ").append(getCurrentTime());
    sb.append("\n[USER] ").append(senderUin).append(" (").append(role).append(")");
    sb.append("\n[MSG] ").append(msgText);
    sendMsg(String.valueOf(msg.peerUin), sb.toString(), msg.type);
}

void sendPermissionDenied(Object msg) {
    sendStyledHeader(msg, "ERROR", "权限不足");
}

boolean requireAdminOrRoot(Object msg) {
    String role = getRole(String.valueOf(msg.userUin));
    if (!role.equals("ADMIN") && !role.equals("ROOT")) {
        sendPermissionDenied(msg);
        return false;
    }
    return true;
}

String extractTargetUin(Object msg, String arg) {
    if (msg == null) return null;
    if (msg.atList != null && msg.atList.size() > 0) {
        for (int i = 0; i < msg.atList.size(); i++) {
            String at = String.valueOf(msg.atList.get(i));
            if (!at.equals(myUin)) return at;
        }
    }
    if (arg != null) {
        String trimmed = arg.trim();
        if (trimmed.matches("\\d{5,15}")) return trimmed;
    }
    return null;
}

boolean isNumeric(String s) {
    return s != null && s.matches("[0-9]+");
}

// ==================== 生命周期 ====================
public void onDestroy() {
    for (Object key : aiContexts.keySet()) {
        aiContexts.remove(key);
    }
    closeSharedDb();
    new Handler(Looper.getMainLooper()).removeCallbacks(this::closeSharedDb);
}

// ==================== 消息路由 ====================
public void onMsg(Object msg) {
    if (msg == null) return;
    String text = msg.msg;
    if (text == null) return;
    String senderUin = String.valueOf(msg.userUin);
    String peerUin = String.valueOf(msg.peerUin);
    int chatType = msg.type;
    String trimmed = text.trim();

    if (trimmed.startsWith("/ai")) {
        String aiArg = trimmed.substring(3).trim();
        if (aiArg.isEmpty()) {
            sendStyledHeader(msg, "ERROR",
                "/ai <内容> -- AI 对话\n" +
                "/ai memory -- 记忆+标签\n" +
                "/ai memory search <kw|tag:x|tags:x,y|pub:xxx>\n" +
                "/ai debug -- 查看/切换调试模式\n" +
                "/ai forget <kw> / clear / config / set\n" +
                "/ai off / on / status"
            );
            return;
        }
        handleAi(msg, aiArg);
        return;
    }

    if (trimmed.startsWith("/websearch")) {
        String wsArg = trimmed.length() > 10 ? trimmed.substring(11).trim() : "";
        handleWebSearch(msg, wsArg);
    }

    if (trimmed.startsWith("/setdefaultaccount")) {
        String arg = trimmed.length() > 19 ? trimmed.substring(20).trim() : "";
        if (arg.isEmpty()) {
            sendStyledHeader(msg, "ERROR", "用法: /setdefaultaccount user/blocked\n当前: " + getDefaultAccount());
            return;
        }
        handleSetDefaultAccount(msg, arg);
        return;
    }

    if (!trimmed.startsWith("/") && startsWithWakeWord(trimmed)) {
        handleAi(msg, trimmed);
        return;
    }

    if (!trimmed.startsWith("/") || trimmed.length() < 2) return;

    String[] tokens = trimmed.split("\\s+");
    String cmd = tokens[0];

    if (cmd.equals("/whoami")) {
        if (tokens.length > 1) { sendStyledHeader(msg, "ERROR", "/whoami 不需要参数"); return; }
        String role = getRole(senderUin);
        StringBuilder info = new StringBuilder();
        info.append("角色: ").append(role);
        info.append("\n记忆: ").append(getMemoryCount(senderUin)).append(" 条");
        info.append("\n标签: ").append(getTagPool(senderUin).size()).append(" 个");
        info.append("\nAI权限: ").append(canUseAi(senderUin) ? "可用" : "不可用");
        info.append("\n默认账户: ").append(getDefaultAccount());
        sendStyledHeader(msg, "INFO", info.toString());
        return;
    }

    if (cmd.equals("/help")) {
        if (tokens.length > 1) { sendStyledHeader(msg, "ERROR", "/help 不需要参数"); return; }
        String role = getRole(senderUin);
        String da = getDefaultAccount();
        List ww = loadWakeWords();
        StringBuilder h = new StringBuilder();
        h.append("鉴存-LMA v13.0 (DARA-FC)\n\n");
        if (!ww.isEmpty()) {
            h.append("唤醒词: ");
            for (int i = 0; i < ww.size(); i++) { if (i > 0) h.append("、"); h.append(ww.get(i)); }
            h.append("\n唤醒词 + 内容可直接呼叫 AI\n\n");
        }
        h.append("/ai <内容> -- AI 对话\n");
        h.append("/ai debug [0|1] -- 调试模式\n");
        h.append("/ai status -- AI 状态\n");
        h.append("/ai memory -- 记忆+标签\n");
        h.append("/ai memory search <kw|tag:x|tags:x,y|pub:xxx|pub tags:x,y>\n");
        h.append("/ai memory set [tags:x,y] <内容> / rm <id>\n");
        h.append("/ai memory tags / tidy / dedupe / skills\n");
        h.append("/ai forget <kw>\n");
        h.append("/whoami / /help\n");
        if (role.equals("ADMIN") || role.equals("ROOT")) {
            h.append("\n[管理]\n");
            h.append("/ai off / on / clear / config / set\n");
            h.append("/ai memory public / all / admin / rebuild\n");
            h.append("/websearch [config|set|test]\n");
            h.append("/block @某人 / /block list / /user @某人 / /log\n");
        }
        if (role.equals("ROOT")) {
            h.append("/ai memory reset\n/admin @某人 / /setdefaultaccount user/blocked\n");
        }
        h.append("\n默认账户: ").append(da).append(da.equals("blocked") ? " (白名单)" : " (开放)");
        h.append("\nv13 DARA-FC | Author: CNYiJieqwq异界, xn--4gqx06mbqk");
        sendStyledHeader(msg, "INFO", h.toString());
        return;
    }

    String role = getRole(senderUin);
    if (role.equals("BLOCKED")) {
        if (!cmd.equals("/whoami") && !cmd.equals("/help") && !cmd.equals("/ai")) {
            sendPermissionDenied(msg); return;
        }
    }

    if (cmd.equals("/log")) {
        if (!requireAdminOrRoot(msg)) return;
        String p = pluginPath + "/config/log.txt";
        if (!new File(p).exists()) { sendStyledHeader(msg, "INFO", "日志已创建"); }
        else { sendFile(peerUin, p, chatType); }
        return;
    }

    if (cmd.equals("/admin")) {
        if (!role.equals("ROOT")) { sendPermissionDenied(msg); return; }
        if (tokens.length >= 2 && tokens[1].equals("list")) {
            Set admins = readStringSet(pluginPath + "/config/admins.txt");
            if (admins.isEmpty()) { sendStyledHeader(msg, "INFO", "管理员列表为空"); return; }
            StringBuilder sb = new StringBuilder();
            sb.append("管理员列表 (").append(admins.size()).append("人):\n");
            for (Object a : admins) { sb.append("  ").append(a).append("\n"); }
            sendStyledHeader(msg, "INFO", sb.toString().trim());
            return;
        }
        if (tokens.length < 2) {
            sendStyledHeader(msg, "ERROR", "用法: /admin @某人 或 /admin <UID>");
            return;
        }
        String t = extractTargetUin(msg, tokens.length >= 2 ? tokens[1] : "");
        if (t == null && isNumeric(tokens[1])) t = tokens[1];
        if (t == null) { sendStyledHeader(msg, "ERROR", "请 @用户 或提供 UID"); return; }
        addToList(pluginPath + "/config/admins.txt", t);
        removeFromList(pluginPath + "/config/blocked.txt", t);
        sendStyledHeader(msg, "SUCCESS", "已授予管理员: " + t);
        return;
    }

    if (cmd.equals("/block")) {
        if (!requireAdminOrRoot(msg)) return;
        if (tokens.length >= 2 && tokens[1].equals("list")) {
            Set blocked = readStringSet(pluginPath + "/config/blocked.txt");
            StringBuilder sb = new StringBuilder();
            if (blocked.isEmpty()) { sb.append("黑名单为空"); }
            else {
                sb.append("黑名单 (").append(blocked.size()).append("人):\n");
                for (Object b : blocked) { sb.append("  ").append(b).append("\n"); }
            }
            if (getDefaultAccount().equals("blocked")) sb.append("\n当前默认账户: blocked，新用户自动加入黑名单");
            sendStyledHeader(msg, "INFO", sb.toString().trim());
            return;
        }
        if (tokens.length < 2) {
            sendStyledHeader(msg, "ERROR", "用法: /block @某人 或 /block <UID>");
            return;
        }
        String t = extractTargetUin(msg, tokens.length >= 2 ? tokens[1] : "");
        if (t == null && isNumeric(tokens[1])) t = tokens[1];
        if (t == null) { sendStyledHeader(msg, "ERROR", "请 @用户 或提供 UID"); return; }
        if (t.equals(myUin)) { sendStyledHeader(msg, "ERROR", "不能拉黑宿主"); return; }
        String tr = getRole(t);
        if (role.equals("ADMIN") && (tr.equals("ADMIN") || tr.equals("ROOT"))) { sendStyledHeader(msg, "ERROR", "不能拉黑 " + tr); return; }
        removeFromList(pluginPath + "/config/admins.txt", t);
        addToList(pluginPath + "/config/blocked.txt", t);
        removeFromList(pluginPath + "/config/users.txt", t);
        sendStyledHeader(msg, "SUCCESS", "已拉黑: " + t);
        return;
    }

    if (cmd.equals("/user")) {
        if (!requireAdminOrRoot(msg)) return;
        if (tokens.length >= 2 && tokens[1].equals("list")) {
            Set users = readStringSet(pluginPath + "/config/users.txt");
            StringBuilder sb = new StringBuilder();
            if (users.isEmpty()) { sb.append("用户白名单为空"); }
            else {
                sb.append("用户白名单 (").append(users.size()).append("人):\n");
                for (Object u : users) { sb.append("  ").append(u).append("\n"); }
            }
            if (getDefaultAccount().equals("user")) sb.append("\n当前默认账户: user，新用户无需加入白名单");
            sendStyledHeader(msg, "INFO", sb.toString().trim());
            return;
        }
        if (tokens.length < 2) {
            sendStyledHeader(msg, "ERROR", "用法: /user @某人 或 /user <UID>");
            return;
        }
        String t = extractTargetUin(msg, tokens.length >= 2 ? tokens[1] : "");
        if (t == null && isNumeric(tokens[1])) t = tokens[1];
        if (t == null) { sendStyledHeader(msg, "ERROR", "请 @用户 或提供 UID"); return; }
        if (t.equals(myUin)) { sendStyledHeader(msg, "ERROR", "不能修改宿主权限"); return; }
        String tr = getRole(t);
        if (role.equals("ADMIN") && (tr.equals("ADMIN") || tr.equals("ROOT"))) { sendStyledHeader(msg, "ERROR", "无法修改 " + tr + " 权限"); return; }
        removeFromList(pluginPath + "/config/admins.txt", t);
        removeFromList(pluginPath + "/config/blocked.txt", t);
        if (getDefaultAccount().equals("blocked")) {
            addToList(pluginPath + "/config/users.txt", t);
            sendStyledHeader(msg, "SUCCESS", "已设为USER并加入白名单: " + t);
        } else {
            sendStyledHeader(msg, "SUCCESS", "已设为 USER: " + t);
        }
        return;
    }

    // 彩蛋
    new Handler(Looper.getMainLooper()).removeCallbacks(this::closeSharedDb);
    new Handler(Looper.getMainLooper()).postDelayed(this::closeSharedDb, 30000);
}

/*
 * ======================== 鉴存-LMA v13.0 (DARA-FC) ========================
 *
 * v13.0: 全域 Function Calling
 * - AI1 用 tools 输出检索计划，DREX 解析 JSON tool_calls
 * - AI2 用 tools 输出记忆操作+搜索请求
 * - 记忆两步验证：R1意向 → 检索验证 → R2确认(id精确操作)
 * - create/overwrite/delete 语义清晰，不再用字符串标签
 * - SPLIT 保持字符串，用于分段发送
 * - 兼容所有 OpenAI 格式 API
 *
 * 作者: CNYiJieqwq异界, xn--4gqx06mbqk
 * 版本: 13.0
 * 最后更新: 2026-05-28 GMT+08:00
 */