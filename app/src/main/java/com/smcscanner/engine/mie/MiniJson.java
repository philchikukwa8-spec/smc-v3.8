package com.smcscanner.engine.mie;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Small strict JSON reader/writer (no dependencies) for stored definitions and for validating AI output. */
public final class MiniJson {
    private MiniJson() {}

    public static final class ParseException extends Exception { public ParseException(String m) { super(m); } }

    public static String write(Object o) { StringBuilder sb = new StringBuilder(); w(sb, o); return sb.toString(); }

    @SuppressWarnings("unchecked")
    private static void w(StringBuilder sb, Object o) {
        if (o == null) sb.append("null");
        else if (o instanceof String) str(sb, (String) o);
        else if (o instanceof Boolean) sb.append(o);
        else if (o instanceof Number) { double d = ((Number) o).doubleValue(); if (d == Math.rint(d) && Math.abs(d) < 1e15) sb.append((long) d); else sb.append(String.format(Locale.US, "%.8g", d)); }
        else if (o instanceof double[]) { double[] a = (double[]) o; sb.append('['); for (int i = 0; i < a.length; i++) { if (i > 0) sb.append(','); w(sb, a[i]); } sb.append(']'); }
        else if (o instanceof List) { sb.append('['); boolean f = true; for (Object x : (List<Object>) o) { if (!f) sb.append(','); f = false; w(sb, x); } sb.append(']'); }
        else if (o instanceof Map) { sb.append('{'); boolean f = true; for (Map.Entry<String, Object> e : ((Map<String, Object>) o).entrySet()) { if (!f) sb.append(','); f = false; str(sb, e.getKey()); sb.append(':'); w(sb, e.getValue()); } sb.append('}'); }
        else str(sb, String.valueOf(o));
    }

    private static void str(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default: if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c);
            }
        }
        sb.append('"');
    }

    /** returns Map&lt;String,Object&gt;, List&lt;Object&gt;, String, Double, Boolean or null */
    public static Object parse(String s) throws ParseException {
        int[] p = {0};
        skip(s, p);
        Object v = val(s, p, 0);
        skip(s, p);
        if (p[0] != s.length()) throw new ParseException("Extra text after the JSON value");
        return v;
    }

    private static void skip(String s, int[] p) { while (p[0] < s.length() && Character.isWhitespace(s.charAt(p[0]))) p[0]++; }

    private static Object val(String s, int[] p, int depth) throws ParseException {
        if (depth > 20) throw new ParseException("Too deep");
        if (p[0] >= s.length()) throw new ParseException("Unexpected end");
        char c = s.charAt(p[0]);
        if (c == '{') {
            p[0]++; Map<String, Object> m = new LinkedHashMap<>(); skip(s, p);
            if (p[0] < s.length() && s.charAt(p[0]) == '}') { p[0]++; return m; }
            while (true) {
                skip(s, p);
                if (p[0] >= s.length() || s.charAt(p[0]) != '"') throw new ParseException("Expected a key");
                String k = string(s, p); skip(s, p);
                if (p[0] >= s.length() || s.charAt(p[0]) != ':') throw new ParseException("Expected ':'");
                p[0]++; skip(s, p);
                m.put(k, val(s, p, depth + 1)); skip(s, p);
                if (p[0] >= s.length()) throw new ParseException("Unexpected end");
                char d = s.charAt(p[0]++);
                if (d == '}') return m;
                if (d != ',') throw new ParseException("Expected ',' or '}'");
            }
        }
        if (c == '[') {
            p[0]++; List<Object> l = new ArrayList<>(); skip(s, p);
            if (p[0] < s.length() && s.charAt(p[0]) == ']') { p[0]++; return l; }
            while (true) {
                skip(s, p);
                l.add(val(s, p, depth + 1)); skip(s, p);
                if (p[0] >= s.length()) throw new ParseException("Unexpected end");
                char d = s.charAt(p[0]++);
                if (d == ']') return l;
                if (d != ',') throw new ParseException("Expected ',' or ']'");
            }
        }
        if (c == '"') return string(s, p);
        if (s.startsWith("true", p[0])) { p[0] += 4; return Boolean.TRUE; }
        if (s.startsWith("false", p[0])) { p[0] += 5; return Boolean.FALSE; }
        if (s.startsWith("null", p[0])) { p[0] += 4; return null; }
        int st = p[0];
        while (p[0] < s.length() && "+-0123456789.eE".indexOf(s.charAt(p[0])) >= 0) p[0]++;
        if (st == p[0]) throw new ParseException("Unexpected character '" + c + "'");
        try { return Double.valueOf(s.substring(st, p[0])); } catch (NumberFormatException e) { throw new ParseException("Bad number"); }
    }

    private static String string(String s, int[] p) throws ParseException {
        StringBuilder sb = new StringBuilder();
        p[0]++;
        while (p[0] < s.length()) {
            char c = s.charAt(p[0]++);
            if (c == '"') return sb.toString();
            if (c == '\\') {
                if (p[0] >= s.length()) break;
                char e = s.charAt(p[0]++);
                switch (e) {
                    case 'n': sb.append('\n'); break; case 't': sb.append('\t'); break; case 'r': sb.append('\r'); break;
                    case 'b': sb.append('\b'); break; case 'f': sb.append('\f'); break;
                    case 'u': if (p[0] + 4 > s.length()) throw new ParseException("Bad escape"); sb.append((char) Integer.parseInt(s.substring(p[0], p[0] + 4), 16)); p[0] += 4; break;
                    default: sb.append(e);
                }
            } else sb.append(c);
        }
        throw new ParseException("Unterminated string");
    }
}
