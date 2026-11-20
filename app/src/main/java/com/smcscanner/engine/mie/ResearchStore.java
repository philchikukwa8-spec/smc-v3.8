package com.smcscanner.engine.mie;

import java.util.List;

/** Persistence boundary for the research database. Android uses SQLite; tests use the in-memory version. */
public interface ResearchStore {
    int SCHEMA_VERSION = 1;

    /** insert or replace by id */
    void upsert(Row r);
    Row get(String id);
    List<Row> all();
    /** generic versioned key/value areas: "profile", "pattern", "version", "ai" */
    void put(String area, String key, String json);
    String get(String area, String key);
    List<String> keys(String area);
    void delete(String area, String key);
}
