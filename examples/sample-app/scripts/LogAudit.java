import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

/**
 * Static inventory of log statements with noise and risk flags, ranked by estimated volume weight.
 * No dependencies; JDK 11+. Heuristic by design: it finds candidates for a human or an agent to judge.
 *
 *   java scripts/LogAudit.java <srcRoot|module> [topN] [--csv out.csv] [--tests]
 *   java scripts/LogAudit.java order-service 25
 *   java scripts/LogAudit.java . 40 --csv build/log-statements.csv      # whole repo, CSV for LogVolume.java
 *
 * Flags per statement:
 *   LOOP      inside a for/while/forEach/stream block            x10 weight (fires per element)
 *   HOT       class or method on a hot path (filter, interceptor, repository, listener, scheduled, cache, health)  x5
 *   CONCAT    message built with + (no parameter placeholders)   quality; recipe-fixable
 *   PAYLOAD   logs a request/response/entity/body/list/map/toString()   x3 weight (large lines)
 *   PII       message or args mention password/token/secret/email/card/ssn/authorization/cookie...  never ship
 *   NOCAUSE   error/warn inside a catch block without the exception as last argument   quality; recipe-fixable
 *   ENTRYEXIT "entering"/"exiting"/"called"/"start of" style tracing at INFO or above   candidate for DEBUG or removal
 *   DUP       the same message literal appears 3+ times in the project
 *   SYSOUT    System.out/err or printStackTrace instead of a logger   recipe-fixable
 * Weight: level base (ERROR/WARN 1, INFO 1, DEBUG 0.15, TRACE 0.05) x LOOP x HOT x PAYLOAD. DEBUG/TRACE assume prod level INFO.
 */
public class LogAudit {
    static final Pattern CALL = Pattern.compile("\\b(?:log|logger|LOG|LOGGER|slf4jLogger|this\\.log)\\s*\\.\\s*(trace|debug|info|warn|error)\\s*\\(");
    static final Pattern SYSOUT = Pattern.compile("System\\.(out|err)\\.print(ln)?\\s*\\(|\\.printStackTrace\\s*\\(");
    static final Pattern LOOP_OPEN = Pattern.compile("\\b(for|while)\\s*\\(|\\.forEach\\s*\\(|\\.stream\\s*\\(\\)|\\bdo\\s*\\{");
    static final Pattern CATCH = Pattern.compile("\\bcatch\\s*\\(\\s*(?:final\\s+)?[\\w.<>|\\s]+\\s+(\\w+)\\s*\\)");
    static final Pattern METHOD_HOT = Pattern.compile("@(Scheduled|KafkaListener|JmsListener|RabbitListener|SqsListener|EventListener|Around|Before|After)\\b");
    static final Pattern CLASS_HOT = Pattern.compile("\\b(class|record)\\s+\\w*(Filter|Interceptor|Repository|Dao|Cache|Listener|Consumer|Handler|HealthIndicator|Aspect|Advice|Converter|Serializer|Deserializer)\\b");
    static final Pattern PII = Pattern.compile("(?i)\\b(password|passwd|pwd|secret|token|apikey|api_key|authorization|bearer|cookie|session[_-]?id|ssn|social[_-]?security|credit[_-]?card|cardnumber|card_number|cvv|iban|account[_-]?number|email|e-mail|phone|mobile|dob|date[_-]?of[_-]?birth|salary|address)\\b");
    static final Pattern PAYLOAD = Pattern.compile("(\\btoString\\(\\)|\\b(request|response|body|entity|dto|payload|headers|result|results|items|records|rows|json|xml)\\b)");
    static final Pattern ENTRYEXIT = Pattern.compile("(?i)^\\s*\"?\\s*(entering|exiting|enter |exit |start of|end of|called|invoked|begin |finished |->|<-|\\[start\\]|\\[end\\])");

    static final class Stmt {
        String file, cls, method; int line; String level; String msg; Set<String> flags = new TreeSet<>(); double weight;
    }

    public static void main(String[] args) throws IOException {
        if (args.length == 0) { System.err.println("usage: LogAudit <srcRoot|module> [topN] [--csv file] [--tests]"); System.exit(2); }
        Path root = Paths.get(args[0]); int topN = 25; Path csv = null; boolean tests = false;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--csv")) csv = Paths.get(args[++i]);
            else if (args[i].equals("--tests")) tests = true;
            else topN = Integer.parseInt(args[i]);
        }
        final boolean includeTests = tests;
        List<Path> files;
        try (Stream<Path> s = Files.walk(root)) {
            files = s.filter(p -> { String t = p.toString();
                    return t.endsWith(".java") && !t.contains("/build/") && !t.contains("/target/") && !t.contains("/scripts/")
                        && (t.contains("/src/main/java/") || (includeTests && t.contains("/src/test/java/"))); }).sorted().collect(Collectors.toList());
        }
        List<Stmt> all = new ArrayList<>();
        for (Path f : files) all.addAll(scan(f));
        // duplicates
        Map<String, Long> msgCount = all.stream().filter(s -> s.msg != null && s.msg.length() > 8).collect(Collectors.groupingBy(s -> s.msg, Collectors.counting()));
        for (Stmt s : all) if (s.msg != null && msgCount.getOrDefault(s.msg, 0L) >= 3) s.flags.add("DUP");
        for (Stmt s : all) {
            double base = s.level.equals("TRACE") ? 0.05 : s.level.equals("DEBUG") ? 0.15 : 1.0;
            s.weight = base * (s.flags.contains("LOOP") ? 10 : 1) * (s.flags.contains("HOT") ? 5 : 1) * (s.flags.contains("PAYLOAD") ? 3 : 1) * (s.level.equals("SYSOUT") ? 2 : 1);
        }
        // summary
        Map<String, Long> byLevel = all.stream().collect(Collectors.groupingBy(s -> s.level, TreeMap::new, Collectors.counting()));
        Map<String, Long> byFlag = new TreeMap<>();
        for (Stmt s : all) for (String fl : s.flags) byFlag.merge(fl, 1L, Long::sum);
        double totalWeight = all.stream().mapToDouble(s -> s.weight).sum();
        System.out.printf("LOG AUDIT %s: files=%d statements=%d  by level %s%n", root, files.size(), all.size(), byLevel);
        System.out.printf("flags: %s%n", byFlag.isEmpty() ? "none" : byFlag);
        List<Stmt> ranked = all.stream().sorted(Comparator.comparingDouble((Stmt s) -> -s.weight)).collect(Collectors.toList());
        double top = ranked.stream().limit(topN).mapToDouble(s -> s.weight).sum();
        System.out.printf("estimated volume weight: total=%.1f, top %d statements carry %.0f%% of it%n", totalWeight, Math.min(topN, ranked.size()), totalWeight == 0 ? 0 : 100 * top / totalWeight);
        System.out.println("TOP STATEMENTS (weight | level | flags | file:line | method | message):");
        for (Stmt s : ranked.stream().limit(topN).collect(Collectors.toList()))
            System.out.printf("%6.1f | %-6s | %-28s | %s:%d | %s | %s%n", s.weight, s.level, String.join(",", s.flags), shortPath(s.file), s.line, s.method, trunc(s.msg, 70));
        List<Stmt> pii = all.stream().filter(s -> s.flags.contains("PII")).collect(Collectors.toList());
        if (!pii.isEmpty()) { System.out.println("PII / SECRET CANDIDATES (must be reviewed before any commit point):");
            for (Stmt s : pii) System.out.printf("  %s:%d  %s%n", shortPath(s.file), s.line, trunc(s.msg, 90)); }
        configFindings(root);
        if (csv != null) {
            try (var w = Files.newBufferedWriter(csv)) {
                w.write("file,line,class,method,level,flags,weight,message\n");
                for (Stmt s : all) w.write(String.join(",", q(s.file), String.valueOf(s.line), q(s.cls), q(s.method), s.level, q(String.join("|", s.flags)), String.format("%.2f", s.weight), q(s.msg)) + "\n");
            }
            System.out.println("CSV written: " + csv + " (" + all.size() + " rows) for LogVolume.java");
        }
    }

    static List<Stmt> scan(Path f) throws IOException {
        List<String> lines = Files.readAllLines(f);
        List<Stmt> out = new ArrayList<>();
        String cls = f.getFileName().toString().replace(".java", "");
        boolean classHot = false;
        Deque<int[]> loops = new ArrayDeque<>();      // {depthAtOpen}
        Deque<Object[]> catches = new ArrayDeque<>(); // {depthAtOpen, varName}
        int depth = 0; String method = "?"; boolean methodHot = false; int methodDepth = -1; boolean pendingHot = false;
        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i);
            String code = stripStringsAndComments(raw);
            if (CLASS_HOT.matcher(code).find()) classHot = true;
            if (METHOD_HOT.matcher(code).find()) pendingHot = true;
            Matcher md = Pattern.compile("^\\s*(?:public|protected|private|static|final|synchronized|\\s)*[\\w<>\\[\\],.?\\s]+\\s+(\\w+)\\s*\\([^;]*\\)\\s*(?:throws [\\w.,\\s]+)?\\s*\\{?\\s*$").matcher(code);
            if (md.find() && !code.contains("=") && !code.trim().startsWith("return") && !code.trim().startsWith("new ")
                    && !md.group(1).matches("for|while|if|else|switch|catch|synchronized|try|do")) { method = md.group(1); methodHot = pendingHot; pendingHot = false; methodDepth = depth; }
            Matcher lm = LOOP_OPEN.matcher(code);
            boolean loopHere = lm.find(); int loopAt = loopHere ? lm.start() : -1;   // a call later on the same line is inside that loop
            Matcher cm = CATCH.matcher(code);
            String catchVar = cm.find() ? cm.group(1) : null;
            // statements on this line
            Matcher m = CALL.matcher(raw);
            while (m.find()) {
                Stmt s = new Stmt(); s.file = f.toString(); s.cls = cls; s.method = method; s.line = i + 1; s.level = m.group(1).toUpperCase();
                String call = callText(lines, i, m.end());
                s.msg = firstLiteral(call);
                String argsPart = call;
                if (!loops.isEmpty() || (loopHere && m.start() > loopAt)) s.flags.add("LOOP");
                if (classHot || methodHot) s.flags.add("HOT");
                if (s.msg != null && isConcat(call)) s.flags.add("CONCAT");
                if (PII.matcher(argsPart).find()) s.flags.add("PII");
                if (PAYLOAD.matcher(argsPart.replaceFirst("\"[^\"]*\"", "")).find() || (s.msg != null && PAYLOAD.matcher(s.msg).find() && argsPart.contains(","))) s.flags.add("PAYLOAD");
                if (!catches.isEmpty() && (s.level.equals("ERROR") || s.level.equals("WARN"))) {
                    String var = (String) catches.peek()[1];
                    String lastArg = argsPart.replaceAll("\\s+", "").replaceAll("\\)+;?$", "");
                    int lc = lastArg.lastIndexOf(','); String last = lc >= 0 ? lastArg.substring(lc + 1) : "";
                    if (var != null && !last.equals(var) && !last.startsWith(var + ".") && !argsPart.contains(var + ")")) s.flags.add("NOCAUSE");
                }
                if (s.msg != null && ENTRYEXIT.matcher(s.msg).find() && !s.level.equals("DEBUG") && !s.level.equals("TRACE")) s.flags.add("ENTRYEXIT");
                out.add(s);
            }
            Matcher so = SYSOUT.matcher(code);
            while (so.find()) {
                Stmt s = new Stmt(); s.file = f.toString(); s.cls = cls; s.method = method; s.line = i + 1; s.level = "SYSOUT";
                s.msg = firstLiteral(callText(lines, i, so.end())); s.flags.add("SYSOUT");
                if (!loops.isEmpty() || (loopHere && so.start() > loopAt)) s.flags.add("LOOP"); if (classHot || methodHot) s.flags.add("HOT");
                out.add(s);
            }
            // brace tracking after processing the line
            for (char c : code.toCharArray()) {
                if (c == '{') { depth++; if (loopHere) { loops.push(new int[]{depth}); loopHere = false; } if (catchVar != null) { catches.push(new Object[]{depth, catchVar}); catchVar = null; } }
                else if (c == '}') { if (!loops.isEmpty() && loops.peek()[0] == depth) loops.pop(); if (!catches.isEmpty() && (int) catches.peek()[0] == depth) catches.pop(); if (depth == methodDepth + 1) { method = "?"; methodHot = false; } depth--; }
            }
            if (loopHere && (code.trim().endsWith(";"))) { /* single-statement loop without braces: ignore */ }
        }
        return out;
    }

    /** text of the call from the opening paren to its matching close, across up to 6 lines */
    static String callText(List<String> lines, int i, int from) {
        StringBuilder sb = new StringBuilder(); int par = 1; boolean inStr = false;
        for (int l = i; l < Math.min(lines.size(), i + 6); l++) {
            String s = lines.get(l); int start = l == i ? from : 0;
            for (int k = start; k < s.length(); k++) {
                char c = s.charAt(k);
                if (c == '"' && (k == 0 || s.charAt(k - 1) != '\\')) inStr = !inStr;
                if (!inStr) { if (c == '(') par++; else if (c == ')') { par--; if (par == 0) return sb.toString(); } }
                sb.append(c);
            }
            sb.append(' ');
        }
        return sb.toString();
    }
    static String firstLiteral(String call) { Matcher m = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(call); return m.find() ? m.group(1) : null; }
    static boolean isConcat(String call) {
        // a + outside string literals in the first argument
        boolean inStr = false; int par = 0;
        for (int k = 0; k < call.length(); k++) {
            char c = call.charAt(k);
            if (c == '"' && (k == 0 || call.charAt(k - 1) != '\\')) inStr = !inStr;
            if (inStr) continue;
            if (c == '(') par++; else if (c == ')') par--;
            else if (c == ',' && par == 0) return false;
            else if (c == '+' && par == 0) return true;
        }
        return false;
    }
    static String stripStringsAndComments(String s) { return s.replaceAll("\"(?:[^\"\\\\]|\\\\.)*\"", "\"\"").replaceAll("//.*$", ""); }
    static String shortPath(String f) { int i = f.indexOf("src/"); return i >= 0 ? f.substring(0, i).replaceAll(".*/(\\w[\\w-]*)/$", "$1/") + f.substring(i).replaceFirst("src/(main|test)/java/", "") : f; }
    static String trunc(String s, int n) { if (s == null) return "(no literal)"; s = s.replace("\n", " "); return s.length() <= n ? s : s.substring(0, n) + "..."; }
    static String q(String s) { return "\"" + (s == null ? "" : s.replace("\"", "'")) + "\""; }

    static void configFindings(Path root) throws IOException {
        List<Path> cfgs;
        try (Stream<Path> s = Files.walk(root)) {
            cfgs = s.filter(p -> { String n = p.getFileName().toString(); return (n.startsWith("logback") && n.endsWith(".xml")) || (n.startsWith("log4j2") && (n.endsWith(".xml") || n.endsWith(".properties"))) || n.matches("application(-\\w+)?\\.(yml|yaml|properties)"); })
                    .filter(p -> !p.toString().contains("/build/") && !p.toString().contains("/target/") && !p.toString().contains("/src/test/")).sorted().collect(Collectors.toList());
        }
        System.out.println("CONFIG FINDINGS:");
        boolean any = false;
        for (Path c : cfgs) {
            String t = Files.readString(c);
            String n = c.getFileName().toString();
            List<String> notes = new ArrayList<>();
            Matcher m = Pattern.compile("(?im)^\\s*(?:logging\\.level\\.|<logger name=\")?([\\w.]+)[\"\\s:=]+(?:level=\")?\\s*(trace|debug)\\b").matcher(t);
            while (m.find()) notes.add("package '" + m.group(1) + "' at " + m.group(2).toUpperCase() + (m.group(1).matches("(?i).*(hibernate|springframework|apache|netty|kafka|tomcat|sql).*") ? " (framework: very high volume in prod)" : ""));
            if (Pattern.compile("(?im)^\\s*(root|logging\\.level\\.root|<root level=\")\\s*[:=\"]?\\s*(trace|debug)\\b").matcher(t).find()) notes.add("ROOT level is DEBUG/TRACE");
            if (n.startsWith("logback") && t.contains("<pattern>") && !t.contains("Json") && !t.contains("Logstash") && !t.contains("ECS")) notes.add("text pattern layout (consider structured JSON for the log sink)");
            if (t.contains("logging.structured") || t.contains("structured:")) notes.add("structured logging configured");
            if (n.startsWith("logback") && !t.contains("AsyncAppender")) notes.add("no AsyncAppender (synchronous console/file writes)");
            if (n.startsWith("logback") && !t.contains("DuplicateMessageFilter")) notes.add("no DuplicateMessageFilter");
            if (!notes.isEmpty()) { any = true; System.out.println("  " + shortPath(c.toString()) + ": " + String.join("; ", notes)); }
        }
        if (!any) System.out.println("  none (no DEBUG/TRACE package levels, no root DEBUG found in " + cfgs.size() + " config files)");
    }
}
