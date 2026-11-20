package com.smcscanner.engine.mie;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Versioned strategy parameters with a stable CHAMPION and experimental challengers. Nothing becomes the champion without
 * an explicit approval call (the user's tap), and every change keeps a rollback point. Nothing here is ever changed by AI.
 */
public final class Versions {
    private Versions() {}

    public static final class Version {
        public String id = "", name = "", status = "challenger", changelog = "", created = "", approved = "";
        public final Map<String, String> params = new LinkedHashMap<>();
    }

    public static final String AREA = "version";
    public static final String ACTIVE_KEY = "_active";
    public static final String PREV_KEY = "_previous";

    public static Version defaults() {
        Version v = new Version();
        v.id = "mie-1.0"; v.name = "Baseline"; v.status = "champion"; v.created = "built-in"; v.changelog = "Initial blueprint rules.";
        v.params.put("allowCountertrend", "true");
        v.params.put("strongNeedsAligned", "true");
        return v;
    }

    @SuppressWarnings("unchecked")
    static Version read(String json) throws MiniJson.ParseException {
        Map<String, Object> o = (Map<String, Object>) MiniJson.parse(json);
        Version v = new Version();
        v.id = (String) o.get("id"); v.name = (String) o.get("name"); v.status = (String) o.get("status"); v.changelog = (String) o.get("changelog"); v.created = (String) o.get("created"); v.approved = o.get("approved") == null ? "" : (String) o.get("approved");
        Map<String, Object> p = (Map<String, Object>) o.get("params");
        if (p != null) for (Map.Entry<String, Object> e : p.entrySet()) v.params.put(e.getKey(), String.valueOf(e.getValue()));
        return v;
    }

    static String write(Version v) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("id", v.id); o.put("name", v.name); o.put("status", v.status); o.put("changelog", v.changelog); o.put("created", v.created); o.put("approved", v.approved);
        o.put("params", new LinkedHashMap<String, Object>(v.params));
        return MiniJson.write(o);
    }

    public static void ensure(ResearchStore s) {
        if (s.get(AREA, "mie-1.0") == null) { Version d = defaults(); s.put(AREA, d.id, write(d)); s.put(AREA, ACTIVE_KEY, d.id); }
        if (s.get(AREA, ACTIVE_KEY) == null) s.put(AREA, ACTIVE_KEY, "mie-1.0");
    }

    public static List<Version> list(ResearchStore s) {
        ensure(s);
        List<Version> out = new ArrayList<>();
        for (String k : s.keys(AREA)) { if (k.startsWith("_")) continue; try { out.add(read(s.get(AREA, k))); } catch (MiniJson.ParseException ignored) { } }
        return out;
    }

    public static Version active(ResearchStore s) {
        ensure(s);
        String id = s.get(AREA, ACTIVE_KEY);
        try { String j = s.get(AREA, id); if (j != null) return read(j); } catch (MiniJson.ParseException ignored) { }
        return defaults();
    }

    /** a proposal is stored as a challenger; it changes nothing until approve() */
    public static Version propose(ResearchStore s, String name, Map<String, String> params, String changelog, String created) {
        ensure(s);
        int n = list(s).size() + 1;
        Version v = new Version();
        v.id = "mie-1." + n; v.name = name; v.status = "challenger"; v.changelog = changelog; v.created = created;
        Version base = active(s);
        v.params.putAll(base.params); v.params.putAll(params);
        s.put(AREA, v.id, write(v));
        return v;
    }

    /** explicit user approval: the version becomes champion; the old champion is kept for rollback */
    public static boolean approve(ResearchStore s, String id, String when) {
        ensure(s);
        String j = s.get(AREA, id);
        if (j == null) return false;
        try {
            Version old = active(s);
            Version nv = read(j);
            old.status = "retired"; s.put(AREA, old.id, write(old));
            nv.status = "champion"; nv.approved = when; s.put(AREA, nv.id, write(nv));
            s.put(AREA, PREV_KEY, old.id); s.put(AREA, ACTIVE_KEY, nv.id);
            return true;
        } catch (MiniJson.ParseException e) { return false; }
    }

    public static boolean rollback(ResearchStore s) {
        String prev = s.get(AREA, PREV_KEY), cur = s.get(AREA, ACTIVE_KEY);
        if (prev == null || prev.equals(cur) || s.get(AREA, prev) == null) return false;
        try {
            Version c = read(s.get(AREA, cur)); c.status = "retired"; s.put(AREA, c.id, write(c));
            Version p = read(s.get(AREA, prev)); p.status = "champion"; s.put(AREA, p.id, write(p));
            s.put(AREA, ACTIVE_KEY, prev); s.put(AREA, PREV_KEY, cur);
            return true;
        } catch (MiniJson.ParseException e) { return false; }
    }

    /** push a version's parameters into the engine */
    public static void apply(Version v) {
        Mie.allowCountertrend = !"false".equals(v.params.get("allowCountertrend"));
        Mie.strongNeedsAligned = !"false".equals(v.params.get("strongNeedsAligned"));
    }
}
