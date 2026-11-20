package com.smcscanner.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.smcscanner.engine.mie.ResearchStore;
import com.smcscanner.engine.mie.Row;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.List;

/** The research database (research.db). Separate from every other file the app keeps; versioned through SQLite's user_version. */
public final class SqliteResearchStore extends SQLiteOpenHelper implements ResearchStore {
    private static SqliteResearchStore instance;

    public static synchronized SqliteResearchStore get(Context c) {
        if (instance == null) instance = new SqliteResearchStore(c.getApplicationContext());
        return instance;
    }

    private SqliteResearchStore(Context c) { super(c, "research.db", null, SCHEMA_VERSION); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS setups (id TEXT PRIMARY KEY, ts INTEGER, symbol TEXT, state TEXT, signal TEXT, outcome TEXT, source TEXT, sample TEXT, data BLOB)");
        db.execSQL("CREATE INDEX IF NOT EXISTS setups_ts ON setups(ts)");
        db.execSQL("CREATE TABLE IF NOT EXISTS kv (area TEXT, k TEXT, json TEXT, PRIMARY KEY (area, k))");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // additive migrations only: never drop research data
        onCreate(db);
    }

    private static byte[] pack(Row r) {
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            ObjectOutputStream oo = new ObjectOutputStream(bo);
            oo.writeObject(r);
            oo.close();
            return bo.toByteArray();
        } catch (Exception e) { return new byte[0]; }
    }

    private static Row unpack(byte[] b) {
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(b))) { return (Row) in.readObject(); } catch (Exception e) { return null; }
    }

    @Override public synchronized void upsert(Row r) {
        ContentValues v = new ContentValues();
        v.put("id", r.id); v.put("ts", r.ts); v.put("symbol", r.symbol); v.put("state", r.state); v.put("signal", r.signal);
        v.put("outcome", r.outcome); v.put("source", r.source); v.put("sample", r.sample); v.put("data", pack(r));
        getWritableDatabase().insertWithOnConflict("setups", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    @Override public synchronized Row get(String id) {
        Cursor c = getReadableDatabase().rawQuery("SELECT data FROM setups WHERE id=?", new String[]{id});
        try { return c.moveToFirst() ? unpack(c.getBlob(0)) : null; } finally { c.close(); }
    }

    @Override public synchronized List<Row> all() {
        List<Row> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery("SELECT data FROM setups ORDER BY ts", null);
        try { while (c.moveToNext()) { Row r = unpack(c.getBlob(0)); if (r != null) out.add(r); } } finally { c.close(); }
        return out;
    }

    /** many rows in one transaction (a replay writes thousands) */
    public synchronized void upsertAll(List<Row> rows) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (Row r : rows) upsert(r);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    /** rows with this source ("live" or "replay") */
    public synchronized List<Row> bySource(String source) {
        List<Row> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery("SELECT data FROM setups WHERE source=? ORDER BY ts", new String[]{source});
        try { while (c.moveToNext()) { Row r = unpack(c.getBlob(0)); if (r != null) out.add(r); } } finally { c.close(); }
        return out;
    }

    public synchronized void deleteRows(List<String> ids) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (String id : ids) db.delete("setups", "id=?", new String[]{id});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    /** only live rows (not walk-forward replay rows), so the live record is never mixed with tests */
    public synchronized List<Row> live() {
        List<Row> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery("SELECT data FROM setups WHERE source='live' ORDER BY ts", null);
        try { while (c.moveToNext()) { Row r = unpack(c.getBlob(0)); if (r != null) out.add(r); } } finally { c.close(); }
        return out;
    }

    @Override public synchronized void put(String area, String key, String json) {
        ContentValues v = new ContentValues();
        v.put("area", area); v.put("k", key); v.put("json", json);
        getWritableDatabase().insertWithOnConflict("kv", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    @Override public synchronized String get(String area, String key) {
        Cursor c = getReadableDatabase().rawQuery("SELECT json FROM kv WHERE area=? AND k=?", new String[]{area, key});
        try { return c.moveToFirst() ? c.getString(0) : null; } finally { c.close(); }
    }

    @Override public synchronized List<String> keys(String area) {
        List<String> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery("SELECT k FROM kv WHERE area=? ORDER BY k", new String[]{area});
        try { while (c.moveToNext()) out.add(c.getString(0)); } finally { c.close(); }
        return out;
    }

    @Override public synchronized void delete(String area, String key) {
        getWritableDatabase().delete("kv", "area=? AND k=?", new String[]{area, key});
    }
}
