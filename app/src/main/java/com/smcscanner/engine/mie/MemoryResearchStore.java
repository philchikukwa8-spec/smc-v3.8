package com.smcscanner.engine.mie;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** In-memory ResearchStore (tests and fallback). */
public final class MemoryResearchStore implements ResearchStore {
    private final Map<String, Row> rows = new LinkedHashMap<>();
    private final Map<String, Map<String, String>> kv = new TreeMap<>();

    @Override public void upsert(Row r) { rows.put(r.id, r); }
    @Override public Row get(String id) { return rows.get(id); }
    @Override public List<Row> all() { return new ArrayList<>(rows.values()); }
    @Override public void put(String area, String key, String json) { Map<String, String> m = kv.get(area); if (m == null) { m = new TreeMap<>(); kv.put(area, m); } m.put(key, json); }
    @Override public String get(String area, String key) { Map<String, String> m = kv.get(area); return m == null ? null : m.get(key); }
    @Override public List<String> keys(String area) { Map<String, String> m = kv.get(area); return m == null ? new ArrayList<String>() : new ArrayList<>(m.keySet()); }
    @Override public void delete(String area, String key) { Map<String, String> m = kv.get(area); if (m != null) m.remove(key); }
}
