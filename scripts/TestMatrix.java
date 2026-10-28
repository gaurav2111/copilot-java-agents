import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

/**
 * Case matrix for unit tests: public methods x applicable input/behaviour cases x what the existing tests already
 * mention. Keys and their meaning are in .github/TEST-CASES.md. Heuristic by design (regular expressions, no compiler),
 * so '+' means "a test that calls this method also mentions this case", which the agent confirms by reading only those
 * tests; '-' means no such test exists.
 *
 * Usage (JDK 11+, no build):
 *   java scripts/TestMatrix.java <module> [Class | Class#method | com.pkg.name] [--csv out.csv] [--all] [--private]
 *   java scripts/TestMatrix.java order-service                       # every class under src/main/java
 *   java scripts/TestMatrix.java order-service OrderService          # one class
 *   java scripts/TestMatrix.java order-service OrderService#cancel   # one method
 *   java scripts/TestMatrix.java order-service com.acme.order        # one package
 *   --all: also list methods whose cases are all covered; --private: include private methods
 * Output: summary line, then per class the test file found and per method "covered" and "missing" keys. Skips
 * getters/setters/equals/hashCode/toString, main, constructors, interfaces and test sources.
 */
public class TestMatrix {
    static final Pattern METHOD = Pattern.compile(
        "(?m)^[ \\t]*((?:public|protected|private|static|final|synchronized|abstract|default|native)\\s+)*(?:<[^>]+>\\s+)?([\\w$.<>\\[\\],?\\s]+?)\\s+(\\w+)\\s*\\(([^)]*)\\)\\s*(?:throws\\s+[\\w.,\\s]+)?\\s*\\{");
    static final Set<String> SKIP_NAMES = new HashSet<>(Arrays.asList("equals", "hashCode", "toString", "main", "clone", "finalize"));
    static boolean ALL = false, PRIVATE = false;

    public static void main(String[] args) throws IOException {
        if (args.length == 0) { System.err.println("usage: TestMatrix <module> [Class|Class#method|package] [--csv out] [--all] [--private]"); System.exit(2); }
        Path module = Paths.get(args[0]); String target = null; Path csv = null;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--csv")) csv = Paths.get(args[++i]);
            else if (args[i].equals("--all")) ALL = true;
            else if (args[i].equals("--private")) PRIVATE = true;
            else target = args[i];
        }
        Path main = module.resolve("src/main/java"); Path test = module.resolve("src/test/java");
        if (!Files.isDirectory(main)) { main = module; }
        String wantClass = null, wantMethod = null, wantPkg = null;
        if (target != null) {
            if (target.contains("#")) { wantClass = target.substring(0, target.indexOf('#')); wantMethod = target.substring(target.indexOf('#') + 1); }
            else if (target.contains(".") && Character.isLowerCase(target.charAt(0))) wantPkg = target.replace('.', '/');
            else wantClass = target;
        }
        final String fc = wantClass, fp = wantPkg;
        List<Path> files;
        try (Stream<Path> s = Files.walk(main)) {
            files = s.filter(p -> p.toString().endsWith(".java"))
                     .filter(p -> !p.toString().contains("/build/") && !p.toString().contains("/target/") && !p.toString().contains("/src/test/"))
                     .filter(p -> fc == null || p.getFileName().toString().equals(fc + ".java"))
                     .filter(p -> fp == null || p.toString().replace('\\', '/').contains("/" + fp + "/"))
                     .sorted().collect(Collectors.toList());
        }
        if (files.isEmpty()) { System.out.println("TEST MATRIX: no production class matched '" + target + "' under " + main); System.exit(1); }
        Map<String, Path> testFiles = new HashMap<>();
        if (Files.isDirectory(test)) try (Stream<Path> s = Files.walk(test)) {
            s.filter(p -> p.toString().endsWith(".java")).forEach(p -> testFiles.put(p.getFileName().toString().replace(".java", ""), p));
        }
        int classes = 0, methods = 0, applicable = 0, covered = 0; List<String> noTest = new ArrayList<>();
        StringBuilder out = new StringBuilder(); List<String> csvRows = new ArrayList<>();
        for (Path f : files) {
            String cls = f.getFileName().toString().replace(".java", "");
            String src = strip(Files.readString(f));
            if (src.matches("(?s).*\\b(interface|@interface)\\s+" + Pattern.quote(cls) + "\\b.*") && !src.contains("default ")) continue;
            List<String[]> ms = methods(src, cls, wantMethod);
            if (ms.isEmpty()) continue;
            classes++;
            Path tf = null; for (String suffix : new String[]{"Test", "Tests", "Spec", "IT"}) if (testFiles.containsKey(cls + suffix)) { tf = testFiles.get(cls + suffix); break; }
            String testSrc = tf == null ? "" : Files.readString(tf);
            List<String[]> tests = tf == null ? Collections.emptyList() : testMethods(testSrc);
            Map<String, Set<String>> notApplicable = new HashMap<>();   // "not applicable: <method> <key> [<key>...] - <reason>" lines in the test file
            Matcher nm = Pattern.compile("not applicable:\\s*(\\w+)\\s+([\\w ]+?)\\s+-\\s").matcher(testSrc);
            while (nm.find()) notApplicable.computeIfAbsent(nm.group(1), k -> new HashSet<>()).addAll(Arrays.asList(nm.group(2).trim().split("\\s+")));
            if (tf == null) noTest.add(cls);
            StringBuilder cb = new StringBuilder();
            for (String[] m : ms) {
                methods++;
                String name = m[0], params = m[1], body = m[2];
                LinkedHashSet<String> keys = new LinkedHashSet<>(Arrays.asList("happy"));
                if (body.contains("throw ") || m[3].contains("throws")) keys.add("exception");
                for (String p : splitParams(params)) keys.addAll(casesFor(p));
                List<String> hits = new ArrayList<>(), miss = new ArrayList<>();
                List<String> excNames = splitParams(params).stream().map(TestMatrix::simpleType).filter(x -> x.matches("\\w*(Exception|Error|Throwable)")).collect(Collectors.toList());
                List<String> relevant = tests.stream().filter(t -> t[1].contains(name + "(") || t[0].toLowerCase().contains(name.toLowerCase()) || excNames.stream().anyMatch(x -> t[1].contains(x))).map(t -> t[0] + " " + t[1]).collect(Collectors.toList());
                Set<String> na = notApplicable.getOrDefault(name, Collections.emptySet());
                String blob = String.join("\n", relevant);
                List<String> nas = new ArrayList<>();
                for (String k : keys) {
                    if (na.contains(k)) { nas.add(k); csvRows.add(cls + "," + name + ",\"" + params.replace("\"", "'") + "\"," + k + ",not-applicable"); continue; }
                    boolean hit = !relevant.isEmpty() && mentions(blob, k);
                    (hit ? hits : miss).add(k);
                    csvRows.add(cls + "," + name + ",\"" + params.replace("\"", "'") + "\"," + k + "," + (hit ? "covered" : "missing"));
                }
                applicable += keys.size() - nas.size(); covered += hits.size();
                if (miss.isEmpty() && !ALL) continue;
                cb.append(String.format("  %-34s covered: %-28s missing: %s%s%n", name + "(" + shortParams(params) + ")", hits.isEmpty() ? "-" : String.join(" ", hits), miss.isEmpty() ? "-" : String.join(" ", miss), nas.isEmpty() ? "" : "   n/a: " + String.join(" ", nas)));
            }
            out.append(cls).append("  test: ").append(tf == null ? "NONE" : tf.getFileName() + " (" + tests.size() + " tests)").append("\n").append(cb);
        }
        System.out.printf("TEST MATRIX %s%s: classes=%d methods=%d applicable cases=%d covered=%d (%d%%)%n", module, target == null ? "" : " " + target, classes, methods, applicable, covered, applicable == 0 ? 0 : covered * 100 / applicable);
        System.out.print(out);
        if (!noTest.isEmpty()) System.out.println("NO TEST CLASS: " + String.join(", ", noTest));
        System.out.println("keys: .github/TEST-CASES.md   '+' = a test calling the method mentions the case (confirm by reading that test); methods with nothing missing are hidden (--all shows them)");
        if (csv != null) { Files.createDirectories(csv.toAbsolutePath().getParent()); Files.write(csv, Stream.concat(Stream.of("class,method,params,case,status"), csvRows.stream()).collect(Collectors.toList())); System.out.println("CSV written: " + csv); }
    }

    /** remove comments and string literal contents so keywords inside them do not count */
    static String strip(String s) {
        s = s.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\n]*", "");
        return s.replaceAll("\"(?:\\\\.|[^\"\\\\])*\"", "\"\"");
    }

    static List<String[]> methods(String src, String cls, String only) {
        List<String[]> out = new ArrayList<>(); Matcher m = METHOD.matcher(src);
        while (m.find()) {
            String mods = src.substring(m.start(), m.start(3)); String ret = m.group(2).trim(), name = m.group(3), params = m.group(4).trim();
            if (ret.matches("(new|return|else|throw|case|do|for|while|if|switch|catch|try|synchronized)") || name.matches("(for|while|if|switch|catch|try|synchronized|record|class|enum)") || name.equals(cls) || SKIP_NAMES.contains(name)) continue;
            if (Character.isUpperCase(name.charAt(0))) continue;   // constructor, record header or nested type header
            String bd = body(src, m.end());
            if (params.isEmpty() && bd.trim().matches("return\\s+(this\\.)?[\\w.]+\\s*;")) continue;   // field accessor
            if (mods.contains("private") && !PRIVATE) continue;
            if (mods.contains("abstract")) continue;
            if (only != null && !name.equals(only)) continue;
            if (name.matches("(get|set|is|has)[A-Z]\\w*") && body(src, m.end()).split("\n").length <= 2 && !body(src, m.end()).contains("if")) continue;   // trivial accessor
            if (name.matches("(get|set|is|has)[A-Z]\\w*") && params.isEmpty() && body(src, m.end()).length() < 60) continue;
            out.add(new String[]{name, params, bd, m.group(0)});
        }
        return out;
    }

    static String body(String src, int from) {
        int depth = 1, i = from;
        while (i < src.length() && depth > 0) { char c = src.charAt(i++); if (c == '{') depth++; else if (c == '}') depth--; }
        return src.substring(from, Math.max(from, i - 1));
    }

    static List<String> splitParams(String params) {
        List<String> out = new ArrayList<>(); if (params.isEmpty()) return out;
        int depth = 0; StringBuilder cur = new StringBuilder();
        for (char c : params.toCharArray()) { if (c == '<') depth++; else if (c == '>') depth--; if (c == ',' && depth == 0) { out.add(cur.toString().trim()); cur.setLength(0); } else cur.append(c); }
        out.add(cur.toString().trim()); return out;
    }

    static String simpleType(String decl) {
        String d = decl.replaceAll("@\\w+(\\([^)]*\\))?\\s*", "").replaceAll("\\bfinal\\s+", "").trim();
        String type = d.contains(" ") ? d.substring(0, d.lastIndexOf(' ')).trim() : d; String raw = type.replaceAll("<.*>", "").replace("...", "").replace("[]", "");
        return raw.contains(".") ? raw.substring(raw.lastIndexOf('.') + 1) : raw;
    }
    static String shortParams(String params) { return splitParams(params).stream().map(p -> p.replaceAll("@\\w+(\\([^)]*\\))?\\s*", "").replaceAll("\\bfinal\\s+", "").replaceAll("<.*>", "").trim().split("\\s+")[0]).collect(Collectors.joining(", ")); }

    /** cases that apply to one parameter declaration such as "String id" or "List<Order.Line> lines" */
    static List<String> casesFor(String decl) {
        String d = decl.replaceAll("@\\w+(\\([^)]*\\))?\\s*", "").replaceAll("\\bfinal\\s+", "").trim();
        String type = d.contains(" ") ? d.substring(0, d.lastIndexOf(' ')).trim() : d; String raw = type.replaceAll("<.*>", "");
        boolean varargs = type.endsWith("...") || type.endsWith("[]"); raw = raw.replace("...", "").replace("[]", "");
        String simple = raw.contains(".") ? raw.substring(raw.lastIndexOf('.') + 1) : raw;
        if (varargs) return Arrays.asList("null", "empty", "single", "many", "duplicates", "nullelement", "order");
        switch (simple) {
            case "String": case "CharSequence": case "StringBuilder": return Arrays.asList("null", "empty", "blank", "single", "long", "trim", "case", "unicode", "special", "numeric", "newline");
            case "int": case "long": case "short": case "byte": return Arrays.asList("zero", "one", "negative", "max", "min", "boundary");
            case "Integer": case "Long": case "Short": case "Byte": case "BigInteger": return Arrays.asList("null", "zero", "one", "negative", "max", "min", "boundary");
            case "double": case "float": return Arrays.asList("zero", "negative", "max", "rounding", "nan");
            case "Double": case "Float": return Arrays.asList("null", "zero", "negative", "max", "rounding", "nan");
            case "BigDecimal": return Arrays.asList("null", "zero", "negative", "max", "rounding");
            case "boolean": return Arrays.asList("true", "false");
            case "Boolean": return Arrays.asList("null", "true", "false");
            case "char": return Arrays.asList("special", "unicode");
            case "Character": return Arrays.asList("null", "special", "unicode");
            case "List": case "Set": case "Collection": case "Iterable": case "Stream": case "Queue": case "Deque": case "SortedSet": return Arrays.asList("null", "empty", "single", "many", "duplicates", "nullelement", "order");
            case "Map": case "SortedMap": case "HashMap": case "TreeMap": return Arrays.asList("null", "empty", "single", "many", "nullvalue");
            case "Optional": return Arrays.asList("empty", "present");
            case "LocalDate": case "LocalDateTime": case "LocalTime": case "Instant": case "ZonedDateTime": case "OffsetDateTime": case "Date": case "Duration": case "Period": case "YearMonth": return Arrays.asList("null", "past", "future", "boundary", "zone");
            case "UUID": case "URI": case "URL": case "Path": case "File": case "Pattern": case "Locale": case "Currency": return Arrays.asList("null", "malformed", "valid");
            case "Object": return Arrays.asList("null", "valid", "invalid");
            case "Exception": case "RuntimeException": case "Throwable": case "Error": return Arrays.asList("message", "nullmessage");
            default:
                if (simple.isEmpty()) return Collections.emptyList();
                if (simple.matches("([A-Z][A-Za-z0-9_]*)?(Type|Status|Kind|Mode|Level|State|Category|Enum)")) return Arrays.asList("each", "null");
                if (simple.matches("\\w+(Exception|Error|Throwable)")) return Arrays.asList("message", "nullmessage");   // @ExceptionHandler and similar   // enum by naming convention
                if (simple.matches("(Money|Amount|Price|Quantity|Percentage|Rate)")) return Arrays.asList("null", "zero", "negative", "max", "rounding", "currency");
                return Arrays.asList("null", "valid", "invalid", "nullfield");
        }
    }

    /** test methods: [name, text] where text = annotations + body, lower-cased for matching but kept raw for non-ASCII */
    /** same length as src, with string and char literal contents blanked, so braces inside "{...}" JSON do not break brace matching */
    static String shadow(String src) {
        StringBuilder sb = new StringBuilder(src); boolean inStr = false, inChr = false;
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            if (inStr) { if (c == '\\') { sb.setCharAt(i, ' '); if (i + 1 < src.length()) sb.setCharAt(++i, ' '); } else if (c == '"') inStr = false; else sb.setCharAt(i, ' '); }
            else if (inChr) { if (c == '\\') { sb.setCharAt(i, ' '); if (i + 1 < src.length()) sb.setCharAt(++i, ' '); } else if (c == '\'') inChr = false; else sb.setCharAt(i, ' '); }
            else if (c == '"') inStr = true; else if (c == '\'') inChr = true;
        }
        return sb.toString();
    }
    static List<String[]> testMethods(String src) {
        List<String[]> out = new ArrayList<>(); String sh = shadow(src);
        // matched on the shadow (literal contents blanked) so parentheses and braces inside annotation strings do not break the match; text is taken from src at the same offsets
        Matcher m = Pattern.compile("(?s)((?:@\\w+(?:\\([^)]*\\))?\\s*)+)\\s*(?:public\\s+|protected\\s+|private\\s+)?(?:static\\s+)?(?:final\\s+)?[\\w<>\\[\\]]+\\s+(\\w+)\\s*\\([^)]*\\)\\s*(?:throws[^{]*)?\\{").matcher(sh);
        while (m.find()) {
            String ann = src.substring(m.start(1), m.end(1)); if (!ann.contains("@Test") && !ann.contains("@ParameterizedTest") && !ann.contains("@RepeatedTest") && !ann.contains("@TestFactory")) continue;
            int end = m.end() + body(sh, m.end()).length();
            out.add(new String[]{m.group(2), ann + src.substring(m.end(), Math.min(end, src.length()))});
        }
        return out;
    }

    static List<String> literals(String blob) {
        List<String> out = new ArrayList<>(); Matcher m = Pattern.compile("\"((?:\\\\.|[^\"\\\\])*)\"").matcher(blob);
        while (m.find()) out.add(m.group(1));
        return out;
    }
    static boolean mentions(String blob, String key) {
        String b = blob; String l = blob.toLowerCase(); List<String> lits = literals(blob);
        switch (key) {
            case "happy": return true;
            case "exception": return l.contains("assertthatthrownby") || l.contains("assertthrows") || l.contains("isinstanceof(") || l.contains("exception");
            case "null": return b.contains("null") || b.contains("NullSource") || b.contains("NullAndEmpty");
            case "empty": return lits.contains("") || b.contains("EmptySource") || b.contains("NullAndEmpty") || b.contains("List.of()") || b.contains("Set.of()") || b.contains("Map.of()") || b.contains("emptyList") || b.contains("emptySet") || b.contains("emptyMap") || b.contains("Optional.empty") || l.contains("isempty()");
            case "blank": case "whitespace": return lits.stream().anyMatch(x -> !x.isEmpty() && (x.trim().isEmpty() || x.contains("\\t"))) || l.contains("isblank") || l.contains("blank");
            case "single": return l.contains("single") || b.matches("(?s).*List\\.of\\([^,()]+\\).*") || lits.stream().anyMatch(x -> x.length() == 1);
            case "long": return b.contains("repeat(") || l.contains("toolong") || l.contains("maxlength") || lits.stream().anyMatch(x -> x.length() > 100);
            case "trim": return l.contains("trim") || lits.stream().anyMatch(x -> !x.trim().isEmpty() && (x.startsWith(" ") || x.endsWith(" ")));
            case "case": { Map<String, Set<String>> byLower = new HashMap<>(); for (String x : lits) if (x.matches(".*[A-Za-z].*")) byLower.computeIfAbsent(x.toLowerCase(), k -> new HashSet<>()).add(x);
                           return byLower.values().stream().anyMatch(v -> v.size() > 1) || b.contains("toUpperCase") || b.contains("toLowerCase") || l.contains("uppercase") || l.contains("lowercase") || l.contains("mixedcase") || l.contains("caseinsensitive") || l.contains("ignorecase"); }
            case "message": return l.contains("hasmessage") || l.contains("getmessage") || l.contains("error\"") || l.contains("message");
            case "nullmessage": return l.contains("nullmessage") || l.contains("withoutmessage") || b.matches("(?s).*new \\w*(Exception|Error)\\(\\).*");
            case "unicode": return l.contains("unicode") || b.contains("\\u") || lits.stream().anyMatch(x -> x.chars().anyMatch(c -> c > 127));
            case "special": return l.contains("special") || lits.stream().anyMatch(x -> x.matches(".*[!@#$%^&*|;<>'\\\\].*"));
            case "numeric": return l.contains("numeric") || lits.stream().anyMatch(x -> x.matches("\\d+"));
            case "newline": return lits.stream().anyMatch(x -> x.contains("\\n")) || l.contains("newline") || l.contains("multiline");
            case "zero": return l.contains("zero") || b.matches("(?s).*[(, ]0[),\\s].*") || b.contains("ZERO");
            case "one": return b.matches("(?s).*[(, ]1[),\\s].*") || b.contains("ONE");
            case "negative": return l.contains("negative") || b.matches("(?s).*[(, ]-\\d.*") || b.contains("negate()");
            case "max": return b.contains("MAX_VALUE") || l.contains("max") || l.contains("exceeds") || l.contains("toolarge") || l.contains("upperbound");
            case "min": return b.contains("MIN_VALUE") || l.contains("min");
            case "boundary": return l.contains("boundary") || l.contains("limit") || l.contains("exactly") || b.contains("+ 1") || b.contains("- 1") || l.contains("edge");
            case "rounding": return l.contains("round") || b.contains("setScale") || b.contains("HALF_") || l.contains("scale") || l.contains("precision");
            case "nan": return b.contains("NaN") || b.contains("Infinity") || b.contains("INFINITY");
            case "true": return b.contains("true"); case "false": return b.contains("false");
            case "many": return l.contains("many") || l.contains("multiple") || l.contains("several") || b.matches("(?s).*(List|Set)\\.of\\([^()]*,[^()]*,.*") || b.contains("asList(") && b.contains(",");
            case "duplicates": return l.contains("duplicate") || l.contains("twice") || l.contains("same ");
            case "nullelement": return b.contains("asList(null") || b.contains(", null") || l.contains("nullelement") || l.contains("containsnull");
            case "nullvalue": return b.contains(", null") || l.contains("nullvalue");
            case "order": return l.contains("order") && (l.contains("sorted") || l.contains("reverse") || l.contains("insertion") || l.contains("ordering")) || l.contains("containsexactly");
            case "present": return b.contains("Optional.of") || l.contains("present") || l.contains("ispresent");
            case "past": return b.contains("minus") || l.contains("past") || l.contains("yesterday") || l.contains("expired") || l.contains("before");
            case "future": return b.contains("plus") || l.contains("future") || l.contains("tomorrow") || l.contains("after");
            case "zone": return b.contains("ZoneId") || b.contains("ZoneOffset") || l.contains("timezone") || l.contains("zone");
            case "malformed": return l.contains("malformed") || l.contains("invalid") || l.contains("notauuid") || l.contains("bad");
            case "valid": return l.contains("valid") || l.contains("confirm") || l.contains("success") || l.contains("returns") || l.contains("happy");
            case "invalid": return l.contains("invalid") || l.contains("reject") || l.contains("illegal") || l.contains("violat");
            case "nullfield": return l.contains("nullfield") || l.contains("missing") || b.matches("(?s).*new \\w+\\([^)]*null.*");
            case "each": return l.contains("enumsource") || l.contains("values()") || l.contains("each");
            case "currency": return l.contains("currency") || l.contains("eur") || l.contains("usd");
            default: return false;
        }
    }
}
