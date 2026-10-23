import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

/**
 * Answers: "which release of the BOM this build declares manages a fixed version of this artifact, and how big is
 * that hop?" Reads BOM POMs from the artifact repository (your mirror), never guesses, never searches the web.
 *
 *   java scripts/BomFix.java <group:artifact> <fixedVersion> [--module <gradle project>] [--resolved <version>] [--bom g:a:v] [--repo URL] [--max 40] [--insecure]
 *   --module runs dependencyInsight for that project to learn the version the build actually resolves (Gradle);
 *   --resolved passes it by hand (Maven, or when you already ran dep-path.sh). With neither, only the BOM is examined.
 *   java scripts/BomFix.java org.apache.tomcat.embed:tomcat-embed-core 10.1.60
 *   java scripts/BomFix.java org.yaml:snakeyaml 2.0 --bom org.springframework.boot:spring-boot-dependencies:2.7.18
 *
 * BOM detection without --bom: mavenBom/platform/enforcedPlatform coordinates in the Gradle build files, Maven
 * <parent> or <dependencyManagement> imports, and the Spring Boot plugin version when the dependency-management
 * plugin is applied (that implies spring-boot-dependencies at the plugin version).
 * Repository: --repo, else REPO_URL env, else Maven Central. Basic auth via REPO_USER / REPO_TOKEN env.
 * TLS behind a re-signing proxy: pass -Djavax.net.ssl.trustStore=<store> (the one Gradle uses) to java; --insecure is for a quick test only.
 * Managed version lookup follows <parent> and imported BOMs (scope import) two levels deep, so Jackson under
 * spring-boot-dependencies (via jackson-bom) is found.
 *
 * Output: for each declared BOM, the first release per hop size (patch / minor / major) that manages >= fixedVersion,
 * the property name that carries the version (for an override), and the collateral: how many other managed versions
 * move with that bump, with the first few named.
 */
public class BomFix {
    static HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).proxy(java.net.ProxySelector.getDefault()).connectTimeout(java.time.Duration.ofSeconds(15)).build();
    /** --insecure: TESTING ONLY. Skips certificate checks for this read-only metadata fetch when a proxy re-signs TLS and
     *  the JDK truststore lacks its certificate. The proper fix is -Djavax.net.ssl.trustStore=<the store Gradle uses>. */
    static void insecure() throws Exception {
        javax.net.ssl.TrustManager[] trustAll = { new javax.net.ssl.X509TrustManager() {
            public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
            public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) { }
            public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) { } } };
        javax.net.ssl.SSLContext ctx = javax.net.ssl.SSLContext.getInstance("TLS"); ctx.init(null, trustAll, new java.security.SecureRandom());
        HTTP = HttpClient.newBuilder().sslContext(ctx).followRedirects(HttpClient.Redirect.NORMAL).proxy(java.net.ProxySelector.getDefault()).connectTimeout(java.time.Duration.ofSeconds(15)).build();
        System.out.println("WARNING: --insecure: TLS certificates are NOT verified for this run. Testing only.");
    }
    static String lastError = null;   // why the last fetch failed, for the report
    static String repo = Optional.ofNullable(System.getenv("REPO_URL")).orElse("https://repo1.maven.org/maven2");
    static final Map<String, Document> POM_CACHE = new HashMap<>();

    public static void main(String[] args) throws Exception {
        if (args.length < 2) { System.err.println("usage: BomFix <group:artifact> <fixedVersion> [--bom g:a:v] [--repo URL] [--max N]"); System.exit(2); }
        String[] ga = args[0].split(":"); String fix = args[1]; List<String> boms = new ArrayList<>(); int max = 40; String module = null; String resolved = null;
        for (int i = 2; i < args.length; i++) switch (args[i]) { case "--bom": boms.add(args[++i]); break; case "--repo": repo = args[++i]; System.setProperty("bomfix.repoArg", "1"); break; case "--max": max = Integer.parseInt(args[++i]); break; case "--insecure": insecure(); break; case "--module": module = args[++i]; break; case "--resolved": resolved = args[++i]; break; }
        String[] insight = module != null && resolved == null ? resolvedFromGradle(module, ga[1]) : new String[]{resolved, ""};
        resolved = insight[0]; String reasons = insight[1];
        if (resolved != null) {
            int cmp = compareVersions(resolved, fix);
            System.out.printf("RESOLVED in the build%s: %s %s%s%n", module != null ? " (" + module + ", runtimeClasspath)" : "", ga[1], resolved, reasons.isEmpty() ? "" : "  [" + reasons + "]");
            if (cmp >= 0) System.out.println("  VERDICT: already at or above the fix in the build. The scanner report is stale or from another build or module: rebuild, rescan, and compare with XrayToFindings --diff. No build change needed.");
        } else if (module == null) System.out.println("RESOLVED in the build: unknown (pass --module <project> or --resolved <version>; without it the verdict below is about the BOM only)");
        repo = repo.replaceAll("/+$", "");
        if (boms.isEmpty()) boms = detectBoms();
        if (boms.isEmpty()) { System.out.println("BOMFIX: no BOM or platform declared in this build; " + args[0] + " is not managed, bump it directly to " + fix + "."); return; }
        System.out.printf("BOMFIX %s -> at least %s   repository %s%s%n", args[0], fix, repo, System.getenv("REPO_URL") == null && System.getProperty("bomfix.repoArg") == null ? "  (default; set REPO_URL to your mirror)" : "");
        for (String bom : boms) {
            String[] b = bom.split(":"); if (b.length < 3) { System.out.println("  skip " + bom + " (no version)"); continue; }
            String current = b[2];
            lastError = null;
            if (pom(b[0], b[1], current) == null) {
                System.out.printf("  BOM %s:%s %s: CANNOT READ ITS POM -> %s%n", b[0], b[1], current, lastError == null ? "unknown error" : lastError);
                System.out.println("    nothing below is evaluated for this BOM. Fix: REPO_URL=https://<mirror>/<repo> (the repository that proxies Maven Central), REPO_USER/REPO_TOKEN if it needs a login, or run from a network that can reach it.");
                continue;
            }
            String managedNow = managedVersion(b[0], b[1], current, ga[0], ga[1], 0);
            System.out.printf("  BOM %s:%s %s manages %s: %s%n", b[0], b[1], current, ga[1], managedNow == null ? "not managed by this BOM (searched its dependencyManagement, parent and imported BOMs two levels deep)" : managedNow);
            if (managedNow == null) continue;
            if (compareVersions(managedNow, fix) >= 0) {
                if (resolved != null && compareVersions(resolved, fix) < 0)
                    System.out.printf("    VERDICT: the BOM already manages %s but the build resolves %s. Something overrides the BOM: a version property (%s), a dependencyManagement entry, a constraint, a force, a version written on the dependency, or a BOM imported after this one. Find it: scripts/dep-path.sh %s:%s <module> (selection reason and 'Version catalog / constraints' section). Bumping the BOM will change nothing.%n", managedNow, resolved, Optional.ofNullable(propertyFor(b[0], b[1], current, ga[0], ga[1])).orElse("?"), ga[0], ga[1]);
                else if (resolved == null)
                    System.out.println("    the BOM already manages a fixed version; whether the build uses it depends on overrides: rerun with --module <project> to compare with the resolved version");
                else System.out.println("    consistent: BOM and build both at or above the fix");
                continue;
            }
            List<String> versions = availableVersions(b[0], b[1]).stream().filter(v -> compareVersions(v, current) > 0 && !v.matches("(?i).*(SNAPSHOT|M\\d|RC\\d|alpha|beta).*")).sorted(BomFix::compareVersions).collect(Collectors.toList());
            if (versions.isEmpty()) { System.out.println("    no newer release found" + (lastError != null ? " (" + lastError + ")" : " in the repository metadata")); continue; }
            String[] cur = current.split("\\.");
            Map<String, String> firstPerHop = new LinkedHashMap<>(); int checked = 0; String propName = null;
            for (String v : versions) {
                String hop = hop(cur, v.split("\\."));
                if (firstPerHop.containsKey(hop)) continue;           // first release per hop size is enough
                if (checked++ >= max) break;
                String mv = managedVersion(b[0], b[1], v, ga[0], ga[1], 0);
                if (mv != null && compareVersions(mv, fix) >= 0) { firstPerHop.put(hop, v + " (manages " + mv + ")"); if (propName == null) propName = propertyFor(b[0], b[1], v, ga[0], ga[1]); }
                if (firstPerHop.size() == 3) break;
            }
            if (firstPerHop.isEmpty()) { System.out.println("    no release of this BOM (checked " + checked + ") manages >= " + fix + "; use a property override or a constraint, and flag it as debt"); }
            for (Map.Entry<String, String> e : firstPerHop.entrySet()) {
                String v = e.getValue().split(" ")[0];
                System.out.printf("    %-6s hop: %s%s%n", e.getKey(), e.getValue(), e.getKey().equals("patch") ? "   <- proposed" : "   (needs a decision)");
                Map<String, String> before = managedMap(b[0], b[1], current), after = managedMap(b[0], b[1], v);
                List<String> moved = after.entrySet().stream().filter(x -> before.containsKey(x.getKey()) && !before.get(x.getKey()).equals(x.getValue())).map(x -> x.getKey() + " " + before.get(x.getKey()) + "->" + x.getValue()).sorted().collect(Collectors.toList());
                long added = after.keySet().stream().filter(k -> !before.containsKey(k)).count();
                System.out.printf("           collateral: %d managed versions move, %d new; e.g. %s%n", moved.size(), added, moved.stream().limit(5).collect(Collectors.joining(", ")));
            }
            if (propName != null) System.out.printf("    override property if no acceptable hop: ext['%s'] = '%s'  (Gradle, dependency-management plugin) / <%s>%s</%s> (Maven)%n", propName, fix, propName, fix, propName);
        }
    }

    /** runs dependencyInsight for the project and returns {resolvedVersion or null, selection reasons} */
    static String[] resolvedFromGradle(String module, String artifact) {
        String path = module.startsWith(":") ? module : ":" + module.replace('/', ':');
        String wrapper = Files.exists(Paths.get("gradlew")) ? (System.getProperty("os.name").toLowerCase().contains("win") ? "gradlew.bat" : "./gradlew") : "gradle";
        List<String> cmd = new ArrayList<>(List.of(wrapper, "--offline", "-q", "--console=plain", path + ":dependencyInsight", "--dependency", artifact, "--configuration", "runtimeClasspath"));
        try {
            Process pr = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = new String(pr.getInputStream().readAllBytes(), StandardCharsets.UTF_8); pr.waitFor();
            Matcher m = Pattern.compile("(?m)^([\\w.-]+):" + Pattern.quote(artifact) + ":([\\w.+-]+)(?:\\s*\\(([^)]*)\\))?").matcher(out);
            if (m.find()) {
                Set<String> reasons = new LinkedHashSet<>(); if (m.group(3) != null) reasons.add(m.group(3));
                Matcher r = Pattern.compile("(?i)(by constraint|selected by rule|forced|conflict resolution|by ancestor|rejected)").matcher(out); while (r.find()) reasons.add(r.group(1).toLowerCase());
                return new String[]{m.group(2), String.join(", ", reasons)};
            }
            if (out.contains("No dependencies matching")) return new String[]{null, "not on " + path + " runtimeClasspath"};
            return new String[]{null, "could not parse dependencyInsight output (first line: " + out.lines().findFirst().orElse("") + ")"};
        } catch (Exception e) { return new String[]{null, "dependencyInsight failed: " + e.getMessage()}; }
    }

    // ---------- detection ----------
    static List<String> detectBoms() throws IOException {
        Set<String> out = new LinkedHashSet<>();
        List<Path> files;
        try (Stream<Path> s = Files.walk(Paths.get("."), 3)) {
            files = s.filter(p -> { String n = p.getFileName().toString(); return (n.equals("build.gradle") || n.equals("build.gradle.kts") || n.equals("pom.xml") || n.equals("libs.versions.toml")) && !p.toString().contains("/build/"); }).collect(Collectors.toList());
        }
        String all = ""; String bootVersion = null; boolean dmPlugin = false;
        for (Path f : files) {
            String t = Files.readString(f); all += "\n" + t;
            Matcher m = Pattern.compile("(?:mavenBom|platform|enforcedPlatform)\\s*\\(?\\s*['\"]([\\w.-]+:[\\w.-]+:[\\w.+-]+)['\"]").matcher(t);
            while (m.find()) out.add(m.group(1));
            if (t.contains("io.spring.dependency-management") || t.contains("spring.dependency.management")) dmPlugin = true;
            Matcher bv = Pattern.compile("org\\.springframework\\.boot['\"]?\\)?[^0-9\\n]{0,40}version[ (=]*['\"]?(\\d+\\.\\d+\\.\\d+)").matcher(t);
            if (bv.find()) bootVersion = bv.group(1);
            Matcher cat = Pattern.compile("(?m)^spring-boot\\s*=\\s*['\"](\\d+\\.\\d+\\.\\d+)").matcher(t);
            if (cat.find()) bootVersion = cat.group(1);
            if (f.getFileName().toString().equals("pom.xml")) {
                Matcher par = Pattern.compile("<parent>\\s*<groupId>([^<]+)</groupId>\\s*<artifactId>([^<]+)</artifactId>\\s*<version>([^<]+)</version>", Pattern.DOTALL).matcher(t);
                if (par.find()) out.add(par.group(1) + ":" + par.group(2) + ":" + par.group(3));
                Matcher imp = Pattern.compile("<groupId>([^<]+)</groupId>\\s*<artifactId>([^<]+)</artifactId>\\s*<version>([^<$]+)</version>\\s*<type>pom</type>\\s*<scope>import</scope>", Pattern.DOTALL).matcher(t);
                while (imp.find()) out.add(imp.group(1) + ":" + imp.group(2) + ":" + imp.group(3));
            }
        }
        if (dmPlugin && bootVersion != null && out.stream().noneMatch(b -> b.contains("spring-boot-dependencies"))) out.add("org.springframework.boot:spring-boot-dependencies:" + bootVersion);
        return new ArrayList<>(out);
    }

    // ---------- repository access ----------
    static String fetch(String path) {
        try {
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(repo + "/" + path)).GET();
            String u = System.getenv("REPO_USER"), t = System.getenv("REPO_TOKEN");
            if (t != null) rb.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(((u == null ? "token" : u) + ":" + t).getBytes(StandardCharsets.UTF_8)));
            HttpResponse<String> r = HTTP.send(rb.build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) { lastError = "HTTP " + r.statusCode() + " for " + repo + "/" + path; return null; }
            String body = r.body();
            if (body.stripLeading().toLowerCase().startsWith("<!doctype html") || body.stripLeading().toLowerCase().startsWith("<html")) { lastError = "HTML page instead of XML from " + repo + "/" + path + " (login page or wrong REPO_URL?)"; return null; }
            return body;
        } catch (Exception e) { lastError = e.getClass().getSimpleName() + ": " + e.getMessage() + " for " + repo + "/" + path; return null; }
    }
    static List<String> availableVersions(String g, String a) {
        String xml = fetch(g.replace('.', '/') + "/" + a + "/maven-metadata.xml");
        if (xml == null) return List.of();
        List<String> out = new ArrayList<>(); Matcher m = Pattern.compile("<version>([^<]+)</version>").matcher(xml);
        while (m.find()) out.add(m.group(1));
        return out;
    }
    static Document pom(String g, String a, String v) {
        String key = g + ":" + a + ":" + v;
        if (POM_CACHE.containsKey(key)) return POM_CACHE.get(key);
        String xml = fetch(g.replace('.', '/') + "/" + a + "/" + v + "/" + a + "-" + v + ".pom");
        Document d = null;
        if (xml != null) try { DocumentBuilderFactory f = DocumentBuilderFactory.newInstance(); f.setNamespaceAware(false); f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); d = f.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { d = null; }
        POM_CACHE.put(key, d); return d;
    }

    // ---------- managed version resolution ----------
    /** managed version of g:a in BOM bg:ba:bv, following parent and imported BOMs (depth <= 2) */
    static String managedVersion(String bg, String ba, String bv, String g, String a, int depth) {
        Document d = pom(bg, ba, bv); if (d == null || depth > 2) return null;
        Map<String, String> props = properties(d, bg, ba, bv);
        for (Element dep : deps(d)) {
            if (text(dep, "groupId").equals(g) && text(dep, "artifactId").equals(a)) { String v = resolve(text(dep, "version"), props); if (!v.isEmpty()) return v; }
        }
        for (Element dep : deps(d)) {
            if ("import".equals(text(dep, "scope")) && "pom".equals(text(dep, "type"))) {
                String v = managedVersion(resolve(text(dep, "groupId"), props), resolve(text(dep, "artifactId"), props), resolve(text(dep, "version"), props), g, a, depth + 1);
                if (v != null) return v;
            }
        }
        Element parent = child(d.getDocumentElement(), "parent");
        if (parent != null) return managedVersion(text(parent, "groupId"), text(parent, "artifactId"), text(parent, "version"), g, a, depth + 1);
        return null;
    }
    static String propertyFor(String bg, String ba, String bv, String g, String a) {
        Document d = pom(bg, ba, bv); if (d == null) return null;
        for (Element dep : deps(d)) if (text(dep, "groupId").equals(g) && text(dep, "artifactId").equals(a)) { Matcher m = Pattern.compile("\\$\\{([^}]+)\\}").matcher(text(dep, "version")); if (m.find()) return m.group(1); }
        return null;
    }
    /** all managed g:a -> version (direct entries only; enough for collateral counts) */
    static Map<String, String> managedMap(String bg, String ba, String bv) {
        Map<String, String> out = new TreeMap<>(); Document d = pom(bg, ba, bv); if (d == null) return out;
        Map<String, String> props = properties(d, bg, ba, bv);
        for (Element dep : deps(d)) if (!"import".equals(text(dep, "scope"))) out.put(text(dep, "groupId") + ":" + text(dep, "artifactId"), resolve(text(dep, "version"), props));
        return out;
    }
    static Map<String, String> properties(Document d, String bg, String ba, String bv) {
        Map<String, String> p = new HashMap<>();
        Element parent = child(d.getDocumentElement(), "parent");
        if (parent != null) { Document pd = pom(text(parent, "groupId"), text(parent, "artifactId"), text(parent, "version")); if (pd != null) p.putAll(properties(pd, "", "", "")); }
        Element props = child(d.getDocumentElement(), "properties");
        if (props != null) { NodeList ch = props.getChildNodes(); for (int i = 0; i < ch.getLength(); i++) if (ch.item(i) instanceof Element) p.put(ch.item(i).getNodeName(), ch.item(i).getTextContent().trim()); }
        p.put("project.version", bv);
        return p;
    }
    static List<Element> deps(Document d) {
        List<Element> out = new ArrayList<>(); Element dm = child(d.getDocumentElement(), "dependencyManagement"); if (dm == null) return out;
        Element ds = child(dm, "dependencies"); if (ds == null) return out;
        NodeList ch = ds.getChildNodes(); for (int i = 0; i < ch.getLength(); i++) if (ch.item(i) instanceof Element && ch.item(i).getNodeName().equals("dependency")) out.add((Element) ch.item(i));
        return out;
    }
    static Element child(Element e, String name) { NodeList ch = e.getChildNodes(); for (int i = 0; i < ch.getLength(); i++) if (ch.item(i) instanceof Element && ch.item(i).getNodeName().equals(name)) return (Element) ch.item(i); return null; }
    static String text(Element e, String name) { Element c = child(e, name); return c == null ? "" : c.getTextContent().trim(); }
    static String resolve(String v, Map<String, String> props) { for (int i = 0; i < 5 && v.contains("${"); i++) { Matcher m = Pattern.compile("\\$\\{([^}]+)\\}").matcher(v); StringBuffer sb = new StringBuffer(); while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(props.getOrDefault(m.group(1), m.group()))); m.appendTail(sb); v = sb.toString(); } return v; }

    static String hop(String[] cur, String[] cand) { if (!cur[0].equals(cand[0])) return "major"; if (cur.length > 1 && cand.length > 1 && !cur[1].equals(cand[1])) return "minor"; return "patch"; }
    static int compareVersions(String a, String b) {
        String[] x = a.split("[.-]"), y = b.split("[.-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            String p = i < x.length ? x[i] : "0", q = i < y.length ? y[i] : "0"; int c;
            try { c = Integer.compare(Integer.parseInt(p), Integer.parseInt(q)); } catch (NumberFormatException e) { c = p.compareTo(q); }
            if (c != 0) return c;
        }
        return 0;
    }
}
