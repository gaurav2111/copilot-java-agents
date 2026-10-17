import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

/**
 * Joins a scanner export with the Maven dependency tree and prints a compact triage table.
 * Input CSV (header required, extra columns ignored, order free):
 *   groupId,artifactId,version,cve,severity,fixVersion
 * Export this from Snyk / Sonatype / Black Duck / Dependabot / Sonar. Most tools can export CSV; map their column names once.
 *
 * Run (from the repository root):
 *   scripts/dep-tree.sh                                  # Gradle -> build/deptree.txt, Maven -> target/deptree.txt
 *   java scripts/CveTriage.java findings.csv build/deptree.txt
 * Both tree formats are understood (Gradle `dependencies` and Maven `dependency:tree`), auto-detected per line.
 *
 * Output per finding: how it is reached (DIRECT / TRANSITIVE via X / NOT FOUND), resolved version, suggested strategy.
 * "ALREADY RESOLVED" means Gradle/Maven already picks a version >= fix (e.g. via a BOM or constraint): confirm, then
 * tell the scanner it is stale or that it scanned the wrong configuration.
 */
public class CveTriage {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) { System.err.println("usage: CveTriage <findings.csv> <deptree.txt>"); System.exit(2); }
        List<Map<String, String>> findings = readCsv(Paths.get(args[0]));
        Map<String, List<Hit>> paths = parseTree(Paths.get(args[1]));

        System.out.println("CVE TRIAGE (" + findings.size() + " findings)");
        System.out.println("cve | severity | artifact | current -> fix | reach | resolved | via | strategy");
        Set<String> seen = new LinkedHashSet<>();
        for (Map<String, String> f : findings) {
            String ga = f.getOrDefault("groupid", "?") + ":" + f.getOrDefault("artifactid", "?");
            String key = f.getOrDefault("cve", "?") + "|" + ga;
            if (!seen.add(key)) continue;
            List<Hit> hits = paths.getOrDefault(ga, List.of());
            String reach, via, strategy, resolved;
            String fix = f.getOrDefault("fixversion", "?");
            if (hits.isEmpty()) {
                reach = "NOT FOUND"; via = "-"; resolved = "-"; strategy = "verify scanner scope (test/provided/plugin/buildscript?) or stale report";
            } else {
                boolean direct = hits.stream().anyMatch(h -> h.direct);
                reach = direct ? "DIRECT" : "TRANSITIVE";
                resolved = hits.stream().map(h -> h.resolved).filter(Objects::nonNull).distinct().collect(Collectors.joining("/"));
                if (resolved.isEmpty()) resolved = "?";
                via = hits.stream().map(h -> h.via).distinct().limit(3).collect(Collectors.joining(" ; "));
                boolean viaSpring = hits.stream().anyMatch(h -> h.via.contains("spring-boot") || h.via.contains("org.springframework"));
                List<String> rv = hits.stream().map(h -> h.resolved).filter(Objects::nonNull).distinct().collect(Collectors.toList());
                boolean allFixed = !fix.equals("?") && !rv.isEmpty() && rv.stream().allMatch(v -> compareVersions(v, fix) >= 0);
                boolean someFixed = !fix.equals("?") && rv.stream().anyMatch(v -> compareVersions(v, fix) >= 0);
                if (allFixed) {
                    strategy = "ALREADY RESOLVED to " + resolved + " everywhere - confirm with dep-path, close as stale/misconfigured scan";
                } else if (someFixed) {
                    String still = hits.stream().filter(h -> h.resolved != null && compareVersions(h.resolved, fix) < 0)
                            .map(h -> h.via.split(":")[0].isEmpty() ? h.via.split(":")[1] : h.via.split(": ")[0]).distinct().collect(Collectors.joining(","));
                    strategy = "PARTIALLY resolved; still vulnerable in " + still + " -> " + (direct ? "bump direct version there" : "add constraint / BOM bump there");
                } else if (direct) {
                    strategy = "bump direct version (version catalog / parent properties)";
                } else {
                    strategy = (viaSpring ? "prefer Spring Boot BOM/platform bump" : (gradle ? "add constraint (dependencies { constraints { } })" : "dependencyManagement override"))
                             + ", then exclusion if still pulled";
                }
            }
            System.out.printf("%s | %s | %s | %s -> %s | %s | %s | %s | %s%n",
                    f.getOrDefault("cve", "?"), f.getOrDefault("severity", "?"), ga,
                    f.getOrDefault("version", "?"), fix, reach, resolved, via, strategy);
        }
    }

    static List<Map<String, String>> readCsv(Path p) throws IOException {
        List<String> lines = Files.readAllLines(p).stream().filter(l -> !l.isBlank()).collect(Collectors.toList());
        String[] header = lines.get(0).split(",");
        List<Map<String, String>> rows = new ArrayList<>();
        for (String l : lines.subList(1, lines.size())) {
            String[] cells = l.split(",", -1);
            Map<String, String> row = new HashMap<>();
            for (int i = 0; i < header.length && i < cells.length; i++)
                row.put(header[i].trim().toLowerCase().replace(" ", "").replace("_", ""), cells[i].trim().replace("\"", ""));
            rows.add(row);
        }
        return rows;
    }

    static boolean gradle = false;

    static final class Hit {
        final boolean direct; final String via; final String resolved;
        Hit(boolean direct, String via, String resolved) { this.direct = direct; this.via = via; this.resolved = resolved; }
    }

    /** groupId:artifactId -> hits. Understands Maven dependency:tree text and Gradle `dependencies` output. */
    static Map<String, List<Hit>> parseTree(Path p) throws IOException {
        Map<String, List<Hit>> out = new HashMap<>();
        // Maven: "[INFO] +- g:a:jar:v:scope" (prefix groups of 3 chars). Gradle: "+--- g:a:v -> v2 (*)" (prefix groups of 5).
        Pattern maven = Pattern.compile("^([|+\\\\` -]*)([^:\\s]+):([^:\\s]+):[^:\\s]+:([^:\\s]+)(?::([^:\\s]+))?");
        Pattern gradleLine = Pattern.compile("^([| ]*)[+\\\\]--- (.+)$");
        Pattern gradleHeader = Pattern.compile("^(?:Root p|P)roject '?(:?[^']*)'?");
        String module = "?";
        Deque<String> stack = new ArrayDeque<>();
        for (String raw : Files.readAllLines(p)) {
            String line = raw.replaceFirst("^\\[INFO\\] ", "");
            Matcher gh = gradleHeader.matcher(line);
            if (gh.find() && !line.contains("---")) { module = gh.group(1).isEmpty() ? "root" : gh.group(1); stack.clear(); gradle = true; continue; }
            Matcher g = gradleLine.matcher(line);
            if (g.find()) {
                gradle = true;
                int depth = g.group(1).length() / 5 + 1;
                String body = g.group(2).replaceAll("\\s*\\([*cn]\\)\\s*$", "").trim();
                String ga, resolved;
                if (body.startsWith("project ")) { ga = "project" + body.substring(8).trim(); resolved = null; }
                else {
                    String[] arrow = body.split("\\s*->\\s*");
                    String[] parts = arrow[0].split(":");
                    if (parts.length < 2) continue;
                    ga = parts[0] + ":" + parts[1];
                    String declared = parts.length > 2 ? parts[2] : null;
                    resolved = arrow.length > 1 ? arrow[1].trim() : declared;
                }
                while (stack.size() >= depth) stack.pollLast();
                stack.addLast(ga + (resolved != null ? ":" + resolved : ""));
                boolean direct = depth == 1;
                String via = direct ? module : module + ": " + String.join(" -> ", stack);
                out.computeIfAbsent(ga, k -> new ArrayList<>()).add(new Hit(direct, via, resolved));
                continue;
            }
            Matcher m = maven.matcher(line);
            if (!m.find()) continue;
            String ga = m.group(2) + ":" + m.group(3);
            if (m.group(1).isBlank()) { module = ga; stack.clear(); continue; }   // reactor module header line
            int depth = m.group(1).replace(" ", "").length() / 2;
            while (stack.size() >= depth) stack.pollLast();
            stack.addLast(ga + ":" + m.group(4));
            boolean direct = depth <= 1;
            String via = direct ? module : module + ": " + String.join(" -> ", stack);
            out.computeIfAbsent(ga, k -> new ArrayList<>()).add(new Hit(direct, via, m.group(4)));
        }
        return out;
    }

    /** numeric-aware compare of dotted versions; non-numeric parts compared as strings; 1.30 < 2.0, 2.15.3 > 2.15 */
    static int compareVersions(String a, String b) {
        String[] x = a.replaceAll("[^0-9A-Za-z.]", ".").split("\\."), y = b.replaceAll("[^0-9A-Za-z.]", ".").split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            String p = i < x.length ? x[i] : "0", q = i < y.length ? y[i] : "0";
            int c;
            try { c = Integer.compare(Integer.parseInt(p), Integer.parseInt(q)); } catch (NumberFormatException e) { c = p.compareTo(q); }
            if (c != 0) return c;
        }
        return 0;
    }
}
