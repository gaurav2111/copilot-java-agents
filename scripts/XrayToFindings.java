import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

/**
 * Converts a JFrog Xray report (REST violations/vulnerabilities export, or `jf audit --format json`) into the
 * findings.csv that CveTriage.java reads, and prints a short summary. Nobody reads the JSON; this tool does.
 *
 *   java scripts/XrayToFindings.java xray-report.json [findings.csv] [--min-severity High] [--all-versions]
 *   java scripts/XrayToFindings.java xray-after.json --diff xray-before.json        # closed / still open / new by issue id
 *
 * Output columns: Group ID, Artifact ID, Version, CVE, Severity, Fix Version, Issue ID, Applicable, Summary
 * (CveTriage uses the first six; the rest travel along for the report.)
 *
 * Shape handled (keys are looked up case-insensitively, missing keys are tolerated):
 *   { "total_count": N, "data": [ { "id", "severity", "summary", "component", "source_comp_id", "cves": [{"cve"}],
 *       "component_versions": [ { "id", "vulnerable_versions": [...], "fixed_versions": [...] } ], "applicability"/"contextual_analysis" ... } ] }
 *   or a top-level array, or {"violations":[...]}, {"vulnerabilities":[...]}, or jf audit's
 *   [ { "vulnerabilities": [ { "issueId", "severity", "impactedPackageName", "impactedPackageVersion", "fixedVersions", "cves": [{"id"}], "applicability": {"status"} } ] } ]
 * Fix version chosen = the smallest fixed version above the current one, preferring the same major (least breaking).
 * Adjust the key names in keysFor() if your export differs; the parser below needs no change.
 */
@SuppressWarnings("unchecked")
public class XrayToFindings {
    static boolean EXPLAIN = false; static int explained = 0;
    static final Pattern GAV = Pattern.compile("(?:gav://)?([\\w.-]+):([\\w.-]+)(?::([\\w.+-]+))?");
    static final Pattern VER = Pattern.compile("\\d+(?:\\.\\d+)+(?:[-.][\\w.]+)?");

    public static void main(String[] args) throws IOException {
        if (args.length == 0) { System.err.println("usage: XrayToFindings <xray.json> [findings.csv] [--min-severity Sev] [--all-versions] [--diff before.json]"); System.exit(2); }
        Path in = Paths.get(args[0]); Path out = Paths.get("findings.csv"); String minSev = null; String diff = null; boolean allVersions = false;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--min-severity")) minSev = args[++i];
            else if (args[i].equals("--diff")) diff = args[++i];
            else if (args[i].equals("--all-versions")) allVersions = true;
            else if (args[i].equals("--explain")) EXPLAIN = true;
            else out = Paths.get(args[i]);
        }
        List<Finding> findings = load(in);
        if (diff != null) { diffReports(load(Paths.get(diff)), findings); return; }
        int before = findings.size();
        if (minSev != null) { int floor = rank(minSev); findings = findings.stream().filter(f -> rank(f.severity) >= floor).collect(Collectors.toList()); }
        // dedupe: same component+version+cve
        Map<String, Finding> uniq = new LinkedHashMap<>();
        for (Finding f : findings) uniq.putIfAbsent(f.group + ":" + f.artifact + ":" + f.version + ":" + f.cve, f);
        findings = new ArrayList<>(uniq.values());
        findings.sort(Comparator.comparingInt((Finding f) -> -rank(f.severity)).thenComparing(f -> f.group + f.artifact));
        try (var w = Files.newBufferedWriter(out)) {
            w.write("Group ID,Artifact ID,Version,CVE,Severity,Fix Version,Issue ID,Applicable,Summary\n");
            for (Finding f : findings)
                w.write(String.join(",", q(f.group), q(f.artifact), q(f.version), q(f.cve), q(f.severity), q(f.fix), q(f.issueId), q(f.applicable), q(trunc(f.summary, 80))) + "\n");
        }
        Map<String, Long> bySev = findings.stream().collect(Collectors.groupingBy(f -> f.severity, LinkedHashMap::new, Collectors.counting()));
        long components = findings.stream().map(f -> f.group + ":" + f.artifact).distinct().count();
        long bumps = findings.stream().map(f -> f.group + ":" + f.artifact + "->" + f.fix).distinct().count();
        long notApplicable = findings.stream().filter(f -> f.applicable.toLowerCase().startsWith("not")).count();
        long noFix = findings.stream().filter(f -> f.fix.isEmpty()).count();
        System.out.printf("XRAY -> FINDINGS: %d raw findings, %d after dedupe%s, by severity %s%n", before, findings.size(), minSev == null ? "" : " at or above " + minSev, bySev);
        System.out.printf("components: %d, distinct bumps: %d, no fixed version: %d, marked not applicable: %d%n", components, bumps, noFix, notApplicable);
        System.out.println("written: " + out + "   next: scripts/dep-tree.sh && java scripts/CveTriage.java " + out + " build/deptree.txt");
    }

    // ---------- mapping ----------
    static final class Finding { String group = "", artifact = "", version = "", cve = "", severity = "", fix = "", issueId = "", applicable = "", summary = ""; }

    static List<Finding> load(Path p) throws IOException {
        Object root = new Json(Files.readString(p)).parse();
        List<Object> items = new ArrayList<>();
        collect(root, items, 0);
        List<Finding> out = new ArrayList<>();
        for (Object o : items) out.addAll(toFindings((Map<String, Object>) o));
        return out;
    }

    /** find the arrays of finding objects wherever they are: data / violations / vulnerabilities / top-level array */
    static void collect(Object node, List<Object> items, int depth) {
        if (depth > 6) return;
        if (node instanceof List) { for (Object e : (List<Object>) node) { if (e instanceof Map && looksLikeFinding((Map<String, Object>) e)) items.add(e); else collect(e, items, depth + 1); } }
        else if (node instanceof Map) { for (Map.Entry<String, Object> en : ((Map<String, Object>) node).entrySet()) if (en.getValue() instanceof List || en.getValue() instanceof Map) collect(en.getValue(), items, depth + 1); }
    }
    static boolean looksLikeFinding(Map<String, Object> m) {
        return get(m, "severity") != null && (get(m, "component", "source_comp_id", "impactedPackageName", "component_versions", "components", "cves", "id", "issueId") != null);
    }

    static List<Finding> toFindings(Map<String, Object> m) {
        List<Finding> out = new ArrayList<>();
        String issueId = str(get(m, "issue_id", "issueId", "id", "xray_id"));
        String severity = normSev(str(get(m, "severity")));
        String summary = str(get(m, "summary", "description", "title"));
        String applicable = applicability(m);
        List<String> cves = new ArrayList<>();
        Object c = get(m, "cves", "cve");
        if (c instanceof List) for (Object e : (List<Object>) c) { if (e instanceof Map) { String id = str(get((Map<String, Object>) e, "cve", "id", "cve_id")); if (!id.isEmpty()) cves.add(id); } else if (e != null) cves.add(String.valueOf(e)); }
        else if (c instanceof Map) { String id = str(get((Map<String, Object>) c, "cve", "id")); if (!id.isEmpty()) cves.add(id); }
        else if (c != null) cves.add(String.valueOf(c));
        if (cves.isEmpty()) cves.add(issueId.isEmpty() ? "UNKNOWN" : issueId);

        // components: either component_versions[] / components[] (REST) or impactedPackageName (jf audit) or component / source_comp_id strings
        List<String[]> comps = new ArrayList<>();   // {group, artifact, version, fixedVersionsJoined}
        Object cv = get(m, "component_versions", "components", "impacted_components");
        if (cv instanceof Map) cv = List.of(cv);                                   // a single object instead of a list
        String fallbackGav = str(get(m, "source_comp_id", "component", "impacted_artifact"));
        String deepFixed = joinList(deepFind(m, "fixed_versions", "fixedVersions", "fixed_version", "fixedVersion", "fix_versions", "fix_version", "fixed_in"));
        if (EXPLAIN && explained < 2) {
            explained++;
            System.out.println("EXPLAIN finding #" + explained + ": top-level keys = " + m.keySet());
            if (cv instanceof List && !((List<Object>) cv).isEmpty() && ((List<Object>) cv).get(0) instanceof Map) System.out.println("  component_versions[0] keys = " + ((Map<String, Object>) ((List<Object>) cv).get(0)).keySet());
            System.out.println("  component/source_comp_id = '" + fallbackGav + "'   issue id = '" + issueId + "'   severity = '" + severity + "'   cves = " + cves);
            System.out.println("  fixed versions found anywhere in the finding = " + (deepFixed.isEmpty() ? "NONE (field name not recognised: look at the keys above and add it to deepFind in toFindings)" : deepFixed));
        }
        if (cv instanceof List && !((List<Object>) cv).isEmpty()) {
            for (Object e : (List<Object>) cv) {
                if (!(e instanceof Map)) continue;
                Map<String, Object> cm = (Map<String, Object>) e;
                String id = str(get(cm, "id", "component_id", "name"));
                String[] ga = gav(id.isEmpty() ? fallbackGav : id);
                String version = ga[2].isEmpty() ? gav(fallbackGav)[2] : ga[2];
                if (version.isEmpty()) version = str(get(cm, "version", "impacted_version"));
                comps.add(new String[]{ga[0], ga[1], version, joinList(get(cm, "fixed_versions", "fixedVersions", "fixed_version"))});
            }
        } else if (get(m, "impactedPackageName") != null) {
            String[] ga = gav(str(get(m, "impactedPackageName")));
            comps.add(new String[]{ga[0], ga[1], str(get(m, "impactedPackageVersion", "version")), joinList(get(m, "fixedVersions", "fixed_versions"))});
        } else if (!fallbackGav.isEmpty()) {
            String[] ga = gav(fallbackGav);
            comps.add(new String[]{ga[0], ga[1], ga[2], joinList(get(m, "fixed_versions", "fixedVersions"))});
        }
        if (comps.isEmpty()) return out;
        for (String[] comp : comps) {
            if (comp[1].isEmpty()) continue;
            String fix = pickFix(comp[3].isEmpty() ? deepFixed : comp[3], comp[2]);
            if (EXPLAIN && explained <= 2) System.out.println("  -> " + comp[0] + ":" + comp[1] + " current '" + comp[2] + "' fixed list '" + (comp[3].isEmpty() ? deepFixed : comp[3]) + "' chosen fix '" + fix + "'");
            for (String cve : cves) {
                Finding f = new Finding(); f.group = comp[0]; f.artifact = comp[1]; f.version = comp[2]; f.cve = cve; f.severity = severity; f.fix = fix; f.issueId = issueId; f.applicable = applicable; f.summary = summary;
                out.add(f);
            }
        }
        return out;
    }

    /** first value under any of these key names, anywhere inside the finding (maps and lists, depth 6) */
    static Object deepFind(Object node, String... keys) {
        return deepFind(node, keys, 0);
    }
    static Object deepFind(Object node, String[] keys, int depth) {
        if (depth > 6 || node == null) return null;
        if (node instanceof Map) {
            Map<String, Object> mm = (Map<String, Object>) node;
            Object direct = get(mm, keys);
            if (direct != null && !(direct instanceof List && ((List<?>) direct).isEmpty())) return direct;
            for (Object v : mm.values()) { Object r = deepFind(v, keys, depth + 1); if (r != null) return r; }
        } else if (node instanceof List) {
            for (Object e : (List<Object>) node) { Object r = deepFind(e, keys, depth + 1); if (r != null) return r; }
        }
        return null;
    }

    static String applicability(Map<String, Object> m) {
        Object a = get(m, "applicability", "contextual_analysis", "applicable");
        if (a == null) return "";
        if (a instanceof Map) { String s = str(get((Map<String, Object>) a, "status", "result", "applicability")); return s; }
        if (a instanceof Boolean) return (Boolean) a ? "applicable" : "not applicable";
        return String.valueOf(a);
    }

    /** smallest fixed version above current, same major preferred; fixed_versions strings may be "[1.2.3]", "≥ 1.2.3", "1.2.3 ≤ Version < 2.0" */
    static String pickFix(String fixedList, String current) {
        if (fixedList == null || fixedList.isEmpty()) return "";
        List<String> cands = new ArrayList<>();
        Matcher mm = VER.matcher(fixedList);
        while (mm.find()) cands.add(mm.group());
        if (cands.isEmpty()) return "";
        List<String> above = cands.stream().filter(v -> current.isEmpty() || compareVersions(v, current) > 0).sorted(XrayToFindings::compareVersions).collect(Collectors.toList());
        if (above.isEmpty()) return cands.stream().min(XrayToFindings::compareVersions).get();
        String major = current.isEmpty() ? "" : current.split("\\.")[0];
        Optional<String> sameMajor = above.stream().filter(v -> v.split("\\.")[0].equals(major)).findFirst();
        return sameMajor.orElse(above.get(0));
    }
    static int compareVersions(String a, String b) {
        String[] x = a.split("[.-]"), y = b.split("[.-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            String p = i < x.length ? x[i] : "0", q = i < y.length ? y[i] : "0"; int c;
            try { c = Integer.compare(Integer.parseInt(p), Integer.parseInt(q)); } catch (NumberFormatException e) { c = p.compareTo(q); }
            if (c != 0) return c;
        }
        return 0;
    }
    static String[] gav(String s) {
        if (s == null) return new String[]{"", "", ""};
        s = s.replaceFirst("^(gav|generic|npm|pypi)://", "");
        Matcher m = GAV.matcher(s);
        if (m.matches()) return new String[]{m.group(1), m.group(2), m.group(3) == null ? "" : m.group(3)};
        if (m.find()) return new String[]{m.group(1), m.group(2), m.group(3) == null ? "" : m.group(3)};
        return new String[]{"", s, ""};
    }
    static int rank(String sev) { switch (normSev(sev)) { case "Critical": return 4; case "High": return 3; case "Medium": return 2; case "Low": return 1; default: return 0; } }
    static String normSev(String s) { if (s == null) return "Unknown"; s = s.trim().toLowerCase(); if (s.startsWith("crit")) return "Critical"; if (s.startsWith("hi")) return "High"; if (s.startsWith("med") || s.startsWith("mod")) return "Medium"; if (s.startsWith("lo")) return "Low"; return s.isEmpty() ? "Unknown" : Character.toUpperCase(s.charAt(0)) + s.substring(1); }
    static String joinList(Object o) { if (o == null) return ""; if (o instanceof List) return ((List<Object>) o).stream().map(String::valueOf).collect(Collectors.joining(" | ")); return String.valueOf(o); }
    static Object get(Map<String, Object> m, String... keys) {
        for (String k : keys) for (Map.Entry<String, Object> e : m.entrySet()) if (e.getKey().equalsIgnoreCase(k) && e.getValue() != null) return e.getValue();
        return null;
    }
    static String str(Object o) { return o == null ? "" : String.valueOf(o).trim(); }
    static String q(String s) { return "\"" + (s == null ? "" : s.replace("\"", "'").replace("\n", " ")) + "\""; }
    static String trunc(String s, int n) { return s.length() <= n ? s : s.substring(0, n) + "..."; }

    static void diffReports(List<Finding> before, List<Finding> after) {
        Set<String> b = before.stream().map(f -> key(f)).collect(Collectors.toCollection(TreeSet::new));
        Set<String> a = after.stream().map(f -> key(f)).collect(Collectors.toCollection(TreeSet::new));
        Set<String> closed = new TreeSet<>(b); closed.removeAll(a);
        Set<String> opened = new TreeSet<>(a); opened.removeAll(b);
        Set<String> still = new TreeSet<>(a); still.retainAll(b);
        System.out.printf("XRAY DIFF: before=%d after=%d closed=%d still open=%d new=%d%n", b.size(), a.size(), closed.size(), still.size(), opened.size());
        closed.forEach(k -> System.out.println("  CLOSED  " + k));
        opened.forEach(k -> System.out.println("  NEW     " + k));
        if (!still.isEmpty()) { System.out.println("  STILL OPEN (first 20):"); still.stream().limit(20).forEach(k -> System.out.println("    " + k)); }
    }
    static String key(Finding f) { return f.severity + " " + f.group + ":" + f.artifact + " " + f.cve + (f.issueId.isEmpty() ? "" : " (" + f.issueId + ")"); }

    // ---------- minimal JSON parser (objects keep insertion order) ----------
    static final class Json {
        final String s; int i = 0;
        Json(String s) { this.s = s; }
        Object parse() { ws(); Object v = value(); return v; }
        Object value() {
            ws(); if (i >= s.length()) return null;
            char c = s.charAt(i);
            if (c == '{') return object(); if (c == '[') return array(); if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; } if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; } if (s.startsWith("null", i)) { i += 4; return null; }
            int st = i; while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (st == i) { i++; return null; }   // tolerate junk
            String n = s.substring(st, i); try { return n.contains(".") || n.contains("e") || n.contains("E") ? (Object) Double.parseDouble(n) : (Object) Long.parseLong(n); } catch (NumberFormatException e) { return n; }
        }
        Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>(); i++; ws();
            while (i < s.length() && s.charAt(i) != '}') {
                ws(); if (s.charAt(i) == ',') { i++; ws(); continue; }
                String k = s.charAt(i) == '"' ? string() : bareKey(); ws(); if (i < s.length() && s.charAt(i) == ':') i++; ws();
                Object v = (i < s.length() && (s.charAt(i) == ',' || s.charAt(i) == '}')) ? null : value();
                m.put(k, v); ws();
            }
            i++; return m;
        }
        List<Object> array() {
            List<Object> l = new ArrayList<>(); i++; ws();
            while (i < s.length() && s.charAt(i) != ']') { ws(); if (s.charAt(i) == ',') { i++; continue; } l.add(value()); ws(); }
            i++; return l;
        }
        String string() {
            StringBuilder sb = new StringBuilder(); i++;
            while (i < s.length() && s.charAt(i) != '"') {
                char c = s.charAt(i++);
                if (c == '\\' && i < s.length()) { char e = s.charAt(i++); switch (e) { case 'n': sb.append('\n'); break; case 't': sb.append('\t'); break; case 'r': sb.append('\r'); break; case 'u': sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break; default: sb.append(e); } }
                else sb.append(c);
            }
            i++; return sb.toString();
        }
        String bareKey() { int st = i; while (i < s.length() && s.charAt(i) != ':' && !Character.isWhitespace(s.charAt(i))) i++; return s.substring(st, i); }
        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
    }
}
