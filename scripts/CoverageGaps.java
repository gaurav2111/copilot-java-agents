import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

/**
 * Turns one or more JaCoCo XML reports into a short, ranked list of under-covered classes
 * with their uncovered methods, so an agent never has to read the raw report.
 *
 * Run (JDK 11+, no build needed). Gradle reports live at <module>/build/reports/jacoco/test/jacocoTestReport.xml
 * (XML output must be enabled: reports.xml.required = true); Maven at <module>/target/site/jacoco/jacoco.xml.
 *   java scripts/CoverageGaps.java "**" 70 15                                          # every report under the repo (both layouts)
 *   java scripts/CoverageGaps.java order-service/build/reports/jacoco/test/jacocoTestReport.xml 80 10
 *   java scripts/CoverageGaps.java order-service/target/site/jacoco/jacoco.xml
 *
 * Ranking: (missed branches + missed lines) weighted by complexity, so big risky classes come first,
 * not DTOs with one uncovered getter. Classes matching EXCLUDE are skipped (edit the list for your repo).
 */
public class CoverageGaps {
    static final List<String> EXCLUDE = List.of(
            "Application", "Config", "Configuration", "Properties", "Dto", "DTO", "Request", "Response",
            "Entity", "Constants", "Exception", "Mapper$", "MapperImpl", "Builder", "package-info", "$$", "_");

    public static void main(String[] args) throws Exception {
        if (args.length == 0) { System.err.println("usage: CoverageGaps <jacoco.xml | glob> [thresholdPct] [topN]"); System.exit(2); }
        int threshold = args.length > 1 ? Integer.parseInt(args[1]) : 70;
        int topN = args.length > 2 ? Integer.parseInt(args[2]) : 15;

        List<Path> reports = resolve(args[0]);
        if (reports.isEmpty()) { System.err.println("no jacoco.xml found for " + args[0] + " (run mvn verify with jacoco first)"); System.exit(1); }

        List<Gap> gaps = new ArrayList<>();
        long totalLines = 0, missedLines = 0, totalBranches = 0, missedBranches = 0;
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
        f.setValidating(false);
        for (Path r : reports) {
            Document d = f.newDocumentBuilder().parse(r.toFile());
            String module = moduleOf(r);
            NodeList classes = d.getElementsByTagName("class");
            for (int i = 0; i < classes.getLength(); i++) {
                Element c = (Element) classes.item(i);
                String name = c.getAttribute("name").replace('/', '.');
                if (EXCLUDE.stream().anyMatch(name::contains)) continue;
                Counter line = counter(c, "LINE"), br = counter(c, "BRANCH"), cx = counter(c, "COMPLEXITY");
                totalLines += line.total(); missedLines += line.missed; totalBranches += br.total(); missedBranches += br.missed;
                if (line.total() == 0) continue;
                int pct = (int) Math.round(100.0 * line.covered / line.total());
                if (pct >= threshold) continue;
                List<String> methods = new ArrayList<>();
                NodeList ms = c.getElementsByTagName("method");
                for (int j = 0; j < ms.getLength(); j++) {
                    Element m = (Element) ms.item(j);
                    Counter ml = counter(m, "LINE");
                    String mn = m.getAttribute("name");
                    if (ml.total() > 0 && ml.covered == 0 && !mn.equals("<init>") && !mn.equals("<clinit>"))
                        methods.add(mn + "(" + ml.total() + "L)");
                }
                double score = (br.missed * 2.0 + line.missed) * Math.max(1, Math.log1p(cx.total()));
                gaps.add(new Gap(module, name, pct, line, br, cx.total(), methods, score));
            }
        }
        gaps.sort(Comparator.comparingDouble((Gap g) -> -g.score));
        System.out.printf("COVERAGE SUMMARY: lines %d/%d (%.1f%%), branches %d/%d (%.1f%%), reports=%d, classes below %d%%=%d%n",
                totalLines - missedLines, totalLines, pct(totalLines - missedLines, totalLines),
                totalBranches - missedBranches, totalBranches, pct(totalBranches - missedBranches, totalBranches),
                reports.size(), threshold, gaps.size());
        System.out.println("TOP " + Math.min(topN, gaps.size()) + " TARGETS (rank | module | class | line% | missed lines | missed branches | complexity | uncovered methods):");
        int k = 1;
        for (Gap g : gaps.stream().limit(topN).collect(Collectors.toList())) {
            String methods = g.methods.isEmpty() ? "-" : String.join(", ", g.methods.subList(0, Math.min(8, g.methods.size())))
                    + (g.methods.size() > 8 ? " +" + (g.methods.size() - 8) + " more" : "");
            System.out.printf("%2d | %s | %s | %d%% | %d | %d | %d | %s%n", k++, g.module, g.cls, g.pct, g.line.missed, g.branch.missed, g.complexity, methods);
        }
    }

    static List<Path> resolve(String arg) throws Exception {
        if (arg.contains("*")) {
            try (Stream<Path> s = Files.walk(Paths.get("."))) {
                return s.filter(p -> {
                    String n = p.getFileName().toString(), full = p.toString();
                    boolean maven = n.equals("jacoco.xml") && full.contains("/target/");
                    boolean gradle = n.endsWith(".xml") && full.contains("/build/reports/jacoco/");
                    return maven || gradle;
                }).sorted().collect(Collectors.toList());
            }
        }
        Path p = Paths.get(arg);
        return Files.exists(p) ? List.of(p) : List.of();
    }

    static double pct(long a, long b) { return b == 0 ? 100.0 : 100.0 * a / b; }

    /** module = path component just before "target" or "build"; "." when the report is at the root. */
    static String moduleOf(Path r) {
        Path abs = r.toAbsolutePath().normalize();
        for (int i = abs.getNameCount() - 1; i > 0; i--) {
            String n = abs.getName(i).toString();
            if (n.equals("target") || n.equals("build")) {
                Path rel = Paths.get(".").toAbsolutePath().normalize().relativize(abs.subpath(0, i).toAbsolutePath());
                String m = abs.getRoot().resolve(abs.subpath(0, i)).toString();
                try { m = Paths.get(".").toAbsolutePath().normalize().relativize(abs.getRoot().resolve(abs.subpath(0, i))).toString(); } catch (Exception ignored) {}
                return m.isEmpty() ? "." : m;
            }
        }
        return r.getParent() == null ? "." : r.getParent().toString();
    }

    static Counter counter(Element e, String type) {
        NodeList cs = e.getChildNodes();
        for (int i = 0; i < cs.getLength(); i++) {
            if (cs.item(i) instanceof Element && "counter".equals(cs.item(i).getNodeName())) {
                Element c = (Element) cs.item(i);
                if (type.equals(c.getAttribute("type")))
                    return new Counter(Integer.parseInt(c.getAttribute("missed")), Integer.parseInt(c.getAttribute("covered")));
            }
        }
        return new Counter(0, 0);
    }

    static final class Counter { final int missed, covered; Counter(int m, int c) { missed = m; covered = c; } int total() { return missed + covered; } }
    static final class Gap {
        final String module, cls; final int pct, complexity; final Counter line, branch; final List<String> methods; final double score;
        Gap(String module, String cls, int pct, Counter line, Counter branch, int complexity, List<String> methods, double score) {
            this.module = module; this.cls = cls; this.pct = pct; this.line = line; this.branch = branch; this.complexity = complexity; this.methods = methods; this.score = score; }
    }
}
