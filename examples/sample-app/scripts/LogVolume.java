import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

/**
 * Joins a log-analytics export (what actually gets ingested) with the source statements from LogAudit.java,
 * and prices the top emitters, so reduction is driven by measured volume, not guesses.
 *
 *   java scripts/LogVolume.java <export.csv> <log-statements.csv> [--price-per-gb 0.50] [--avg-bytes 350] [--top 25]
 *
 * export.csv: one row per message pattern as your log tool reports it (Splunk "top message", Datadog pattern view,
 *   Cloud Logging "logs analytics" group-by). Header required; columns matched case-insensitively:
 *   pattern|message|template , count|events|events_per_day|hits , bytes|size|bytes_per_day (optional)
 *   Numbers may contain commas or units (1.2M, 340k).
 * log-statements.csv: from  java scripts/LogAudit.java . 40 --csv build/log-statements.csv
 *
 * Price defaults are deliberately rough and vendor-dependent; pass --price-per-gb from your contract. Reference points
 * (list prices, 2026, ingestion only): GCP Cloud Logging ~0.50/GiB after the free tier, Elastic Cloud ~0.09/GB,
 * Datadog ~0.10/GB ingest plus indexing per million events, Splunk Cloud priced per GB/day of licence.
 */
public class LogVolume {
    public static void main(String[] args) throws IOException {
        if (args.length < 2) { System.err.println("usage: LogVolume <export.csv> <log-statements.csv> [--price-per-gb N] [--avg-bytes N] [--top N]"); System.exit(2); }
        double price = 0.50; int avgBytes = 350; int top = 25;
        for (int i = 2; i < args.length; i++) {
            switch (args[i]) { case "--price-per-gb": price = Double.parseDouble(args[++i]); break; case "--avg-bytes": avgBytes = Integer.parseInt(args[++i]); break; case "--top": top = Integer.parseInt(args[++i]); break; }
        }
        List<Map<String, String>> export = readCsv(Paths.get(args[0]));
        List<Map<String, String>> stmts = readCsv(Paths.get(args[1]));
        List<Row> rows = new ArrayList<>();
        double totalBytes = 0;
        for (Map<String, String> e : export) {
            String pattern = first(e, "pattern", "message", "template", "msg");
            double count = num(first(e, "count", "events", "events_per_day", "hits", "total"));
            String bytesStr = first(e, "bytes", "size", "bytes_per_day", "volume");
            double bytes = bytesStr == null || bytesStr.isBlank() ? count * avgBytes : num(bytesStr);
            if (pattern == null) continue;
            Map<String, String> best = null; double bestScore = 0;
            for (Map<String, String> s : stmts) {
                double sc = similarity(pattern, s.getOrDefault("message", ""));
                if (sc > bestScore) { bestScore = sc; best = s; }
            }
            Row r = new Row(); r.pattern = pattern; r.count = count; r.bytes = bytes; r.match = bestScore >= 0.6 ? best : null; r.score = bestScore;
            rows.add(r); totalBytes += bytes;
        }
        rows.sort(Comparator.comparingDouble((Row r) -> -r.bytes));
        double gb = totalBytes / 1e9;
        System.out.printf("LOG VOLUME: %d patterns, %.2f GB/day, ~$%.2f/day at $%.2f/GB (%.0f%% matched to source)%n",
                rows.size(), gb, gb * price, price, rows.isEmpty() ? 0 : 100.0 * rows.stream().filter(r -> r.match != null).count() / rows.size());
        System.out.println("TOP EMITTERS (GB/day | $/day | %total | events/day | source | level | flags | action):");
        double cum = 0;
        for (Row r : rows.stream().limit(top).collect(Collectors.toList())) {
            double rgb = r.bytes / 1e9; cum += r.bytes;
            String src = r.match == null ? "(unmatched: " + trunc(r.pattern, 50) + ")" : shortPath(r.match.get("file")) + ":" + r.match.get("line");
            String level = r.match == null ? "?" : r.match.get("level"); String flags = r.match == null ? "" : r.match.get("flags");
            System.out.printf("%7.3f | %7.2f | %4.0f%% | %10s | %s | %s | %s | %s%n", rgb, rgb * price, 100 * r.bytes / totalBytes, fmt(r.count), src, level, flags, action(level, flags, r));
        }
        System.out.printf("top %d carry %.0f%% of volume. Removing or downgrading them to DEBUG saves up to ~$%.2f/day (~$%.0f/year).%n",
                Math.min(top, rows.size()), 100 * cum / Math.max(1, totalBytes), cum / 1e9 * price, cum / 1e9 * price * 365);
    }

    static String action(String level, String flags, Row r) {
        if (r.match == null) return "find source (framework logger or dynamic message): check config package levels";
        if (flags.contains("PII")) return "REMOVE or redact: PII/secret";
        if (flags.contains("LOOP")) return "downgrade to DEBUG or log once per batch with a count";
        if (flags.contains("ENTRYEXIT")) return "remove (use tracing) or DEBUG";
        if (flags.contains("PAYLOAD")) return "log ids and sizes, not the payload";
        if (flags.contains("DUP")) return "merge duplicates; keep one with context";
        if (level.equals("DEBUG") || level.equals("TRACE")) return "DEBUG is enabled in prod: fix config level";
        if (level.equals("INFO")) return "keep if it is a business event; else DEBUG";
        return "review";
    }
    static final class Row { String pattern; double count, bytes, score; Map<String, String> match; }

    static double similarity(String pattern, String template) {
        if (template == null || template.isEmpty()) return 0;
        List<String> a = tokens(pattern), b = tokens(template);
        if (b.isEmpty()) return 0;
        Set<String> sa = new HashSet<>(a); long common = b.stream().filter(sa::contains).count();
        return (double) common / Math.max(b.size(), 1);
    }
    static List<String> tokens(String s) {
        return Arrays.stream(s.toLowerCase().replace("{}", " ").replaceAll("[^a-z]+", " ").trim().split("\\s+")).filter(t -> t.length() > 2).collect(Collectors.toList());
    }
    static String first(Map<String, String> m, String... keys) { for (String k : keys) if (m.containsKey(k) && !m.get(k).isBlank()) return m.get(k); return null; }
    static double num(String s) {
        if (s == null) return 0; s = s.trim().replace(",", "").toLowerCase();
        double mul = 1; if (s.endsWith("k")) { mul = 1e3; s = s.substring(0, s.length() - 1); } else if (s.endsWith("m")) { mul = 1e6; s = s.substring(0, s.length() - 1); } else if (s.endsWith("g") || s.endsWith("gb")) { mul = 1e9; s = s.replaceAll("gb?$", ""); } else if (s.endsWith("mb")) { mul = 1e6; s = s.replace("mb", ""); } else if (s.endsWith("kb")) { mul = 1e3; s = s.replace("kb", ""); }
        try { return Double.parseDouble(s) * mul; } catch (NumberFormatException e) { return 0; }
    }
    static String fmt(double n) { return n >= 1e6 ? String.format("%.1fM", n / 1e6) : n >= 1e3 ? String.format("%.0fk", n / 1e3) : String.format("%.0f", n); }
    static String trunc(String s, int n) { return s.length() <= n ? s : s.substring(0, n) + "..."; }
    static String shortPath(String f) { if (f == null) return "?"; int i = f.indexOf("src/"); return i >= 0 ? f.substring(i).replaceFirst("src/(main|test)/java/", "") : f; }
    static List<Map<String, String>> readCsv(Path p) throws IOException {
        List<String> lines = Files.readAllLines(p).stream().filter(l -> !l.isBlank()).collect(Collectors.toList());
        if (lines.isEmpty()) return List.of();
        List<String> header = splitCsv(lines.get(0)).stream().map(h -> h.trim().toLowerCase().replace(" ", "_")).collect(Collectors.toList());
        List<Map<String, String>> rows = new ArrayList<>();
        for (String l : lines.subList(1, lines.size())) {
            List<String> cells = splitCsv(l); Map<String, String> row = new HashMap<>();
            for (int i = 0; i < header.size() && i < cells.size(); i++) row.put(header.get(i), cells.get(i));
            rows.add(row);
        }
        return rows;
    }
    static List<String> splitCsv(String line) {
        List<String> out = new ArrayList<>(); StringBuilder sb = new StringBuilder(); boolean q = false;
        for (char c : line.toCharArray()) { if (c == '"') q = !q; else if (c == ',' && !q) { out.add(sb.toString()); sb.setLength(0); } else sb.append(c); }
        out.add(sb.toString()); return out;
    }
}
