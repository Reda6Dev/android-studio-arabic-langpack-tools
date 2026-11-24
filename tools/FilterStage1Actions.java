/**
 * Stage 1 XML Action Filter using javap bytecode disassembly across classes & superclasses.
 * أداة تصفية أوامر XML في إضافات Android وGoogle لعزل الأوامر القابلة للتعريب عن الأوامر المثبتة في الكود أو الداخلية.
 *
 * Inputs:
 *   -Dstudio.home=<path>   Android Studio installation directory (or ANDROID_STUDIO_HOME env var)
 *   -Dwork.dir=<path>      Working directory containing analysis/android_xml_actions_breakdown.csv (and optional arabic-language-pack-0.3.1.zip)
 * Outputs:
 *   <work.dir>/analysis/stage1_xml_actions_filter.csv, <work.dir>/batches/stage1_in/*.tsv
 */
import javax.xml.parsers.*;
import org.w3c.dom.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.regex.*;

public class FilterStage1Actions {
    private static final Path STUDIO_ROOT = Paths.get(System.getProperty("studio.home", System.getenv("ANDROID_STUDIO_HOME") != null ? System.getenv("ANDROID_STUDIO_HOME") : "C:\\Program Files\\Android\\Android Studio"));
    private static final Path PHASE2 = Paths.get(System.getProperty("work.dir", System.getenv("WORK_DIR") != null ? System.getenv("WORK_DIR") : "."));
    private static final Path BREAKDOWN_CSV = PHASE2.resolve("analysis\\android_xml_actions_breakdown.csv");
    private static final Path PACK_031_ZIP = PHASE2.resolve("arabic-language-pack-0.3.1.zip");
    private static final Path OUT_FILTER_CSV = PHASE2.resolve("analysis\\stage1_xml_actions_filter.csv");
    private static final Path STAGE1_IN_DIR = PHASE2.resolve("batches\\stage1_in");
    private static final String JAVAP_EXE = STUDIO_ROOT.resolve("jbr\\bin\\javap.exe").toString();

    record Row187(String pluginDir, String jarRel, String xmlPath, String tagType, String id, String className,
                  String textAttr, String effectiveBundle) {}

    public static void main(String[] args) throws Exception {
        for (String a : args) { if ("-h".equals(a) || "--help".equals(a)) { System.out.println("Usage: FilterStage1Actions  (-Dstudio.home=... -Dwork.dir=...)"); return; } }
        // 1. Load existing keys in 0.3.1 so we never re-add or touch them
        Set<String> existing031Keys = new HashSet<>(); // baseName|key
        if (Files.exists(PACK_031_ZIP))
        try (java.util.zip.ZipFile outer = new java.util.zip.ZipFile(PACK_031_ZIP.toFile())) {
            var je = outer.getEntry("arabic-language-pack-0.3.1/lib/arabic-language-pack-0.3.1.jar");
            Path tmp = Files.createTempFile("p031f", ".jar");
            try (InputStream is = outer.getInputStream(je)) { Files.copy(is, tmp, StandardCopyOption.REPLACE_EXISTING); }
            try (java.util.zip.ZipFile inner = new java.util.zip.ZipFile(tmp.toFile())) {
                var en = inner.entries();
                while (en.hasMoreElements()) {
                    var e = en.nextElement();
                    if (!e.getName().endsWith("_ar.properties")) continue;
                    String baseName = e.getName().replace("_ar.properties", "").replace('/', '.');
                    Properties p = new Properties();
                    try (Reader r = new InputStreamReader(inner.getInputStream(e), StandardCharsets.UTF_8)) { p.load(r); }
                    for (String k : p.stringPropertyNames()) existing031Keys.add(baseName + "|" + k);
                }
            } finally { Files.deleteIfExists(tmp); }
        }

        // 2. Read XML_TEXT_WITH_ACTIVE_BUNDLE_NO_KEY rows from android_xml_actions_breakdown.csv
        List<Row187> candidates = new ArrayList<>();
        List<String> lines = Files.readAllLines(BREAKDOWN_CSV, StandardCharsets.UTF_8);
        for (int i = 1; i < lines.size(); i++) {
            List<String> c = parseCsvLine(lines.get(i));
            if (c.size() < 16) continue;
            if (!"XML_TEXT_WITH_ACTIVE_BUNDLE_NO_KEY".equals(c.get(15))) continue;
            candidates.add(new Row187(c.get(0), c.get(1), c.get(2), c.get(3), c.get(4), c.get(5), c.get(6), c.get(11)));
        }
        System.out.println("Loaded XML_TEXT_WITH_ACTIVE_BUNDLE_NO_KEY candidates: " + candidates.size());

        // 3. Check XML internal="true" and ancestor groups in each XML file
        Map<String, String> xmlRawCache = new HashMap<>();
        Map<String, Set<String>> internalIdsByXml = new HashMap<>(); // jarRel!xmlPath -> set of tag|id marked internal=true or under internal/debug/RAG group
        Map<String, String> ancestorGroupsByKey = new HashMap<>(); // jarRel!xmlPath|tag|id -> groups

        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(false); dbf.setValidating(false);
        dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

        for (Row187 r : candidates) {
            String xmlKey = r.jarRel + "!" + r.xmlPath;
            if (!internalIdsByXml.containsKey(xmlKey)) {
                Set<String> intSet = new HashSet<>();
                try (JarFile jf = new JarFile(STUDIO_ROOT.resolve(r.jarRel).toFile())) {
                    var je = jf.getEntry(r.xmlPath);
                    if (je != null) {
                        byte[] bytes = jf.getInputStream(je).readAllBytes();
                        Document doc = dbf.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
                        walkXmlInternal(doc.getDocumentElement(), false, "", intSet, xmlKey, ancestorGroupsByKey);
                    }
                }
                internalIdsByXml.put(xmlKey, intSet);
            }
        }

        // 4. Inspect bytecode of every unique class via javap grouped by JAR (and fallback across Android/Platform JARs)
        Map<String, List<String>> classesByJar = new LinkedHashMap<>();
        Set<String> uniqueClasses = new LinkedHashSet<>();
        for (Row187 r : candidates) {
            if (r.className != null && !r.className.isEmpty()) {
                uniqueClasses.add(r.className);
                classesByJar.computeIfAbsent(r.jarRel, k -> new ArrayList<>()).add(r.className);
            }
        }

        // Build compact classpath containing JARs owning candidate classes + design/gemini/platform core
        Set<String> cpJars = new LinkedHashSet<>();
        for (Row187 r : candidates) cpJars.add(STUDIO_ROOT.resolve(r.jarRel).toAbsolutePath().toString());
        cpJars.add(STUDIO_ROOT.resolve("plugins\\android\\lib\\android.jar").toAbsolutePath().toString());
        cpJars.add(STUDIO_ROOT.resolve("plugins\\design-tools\\lib\\design-tools.jar").toAbsolutePath().toString());
        cpJars.add(STUDIO_ROOT.resolve("plugins\\gemini\\lib\\aiplugin.jar").toAbsolutePath().toString());
        cpJars.add(STUDIO_ROOT.resolve("lib\\app.jar").toAbsolutePath().toString());
        cpJars.add(STUDIO_ROOT.resolve("lib\\intellij.platform.ide.impl.jar").toAbsolutePath().toString());
        cpJars.add(STUDIO_ROOT.resolve("lib\\intellij.platform.core.jar").toAbsolutePath().toString());
        cpJars.add(STUDIO_ROOT.resolve("lib\\util-8.jar").toAbsolutePath().toString());
        String fullCp = String.join(";", cpJars);

        // Run javap -c -p on candidate classes AND recursively on non-platform superclasses
        Set<String> inspectedAll = new LinkedHashSet<>();
        List<String> queue = new ArrayList<>(uniqueClasses);
        Map<String, String> javapOutputByClass = new HashMap<>();
        while (!queue.isEmpty()) {
            List<String> toRun = new ArrayList<>();
            for (String q : queue) {
                if (inspectedAll.add(q)) toRun.add(q);
            }
            queue.clear();
            if (toRun.isEmpty()) break;
            for (int i = 0; i < toRun.size(); i += 20) {
                List<String> chunk = toRun.subList(i, Math.min(toRun.size(), i + 20));
                List<String> cmd = new ArrayList<>();
                cmd.add(JAVAP_EXE);
                cmd.add("-cp");
                cmd.add(fullCp);
                cmd.add("-c");
                cmd.add("-p");
                cmd.addAll(chunk);
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);
                Process proc = pb.start();
                String allOut = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                proc.waitFor();
                splitJavapByClass(allOut, javapOutputByClass);
            }
            // Queue non-platform superclasses for next hop
            for (String cls : toRun) {
                String jp = javapOutputByClass.get(cls);
                if (jp == null) continue;
                Matcher extM = Pattern.compile("extends\\s+([A-Za-z0-9_$.]+)").matcher(jp);
                if (extM.find()) {
                    String sup = extM.group(1);
                    if (!sup.startsWith("java.") && !sup.startsWith("com.intellij.openapi.actionSystem.") && !sup.equals("com.intellij.openapi.project.DumbAwareAction")) {
                        queue.add(sup);
                    }
                }
            }
        }
        System.out.println("Disassembled classes (incl. superclasses): " + javapOutputByClass.size());

        // 5. Evaluate each of the 187 rows strictly
        Files.createDirectories(STAGE1_IN_DIR);
        List<Row187> eligible = new ArrayList<>();
        Set<String> seenOutKeys = new HashSet<>();

        int skip031 = 0, skipNoId = 0, skipInternal = 0, skipWordLen = 0, skipCtor = 0, skipUpdate = 0, skipDup = 0;

        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(OUT_FILTER_CSV, StandardCharsets.UTF_8))) {
            pw.println("PluginDir,XmlPath,TagType,Id,EffectiveBundle,ProposedKey,TextAttr,WordCount,ClassName,BytecodeFlags,Decision,Reason");
            for (Row187 r : candidates) {
                String proposedKey = (r.id != null && !r.id.isEmpty()) ? (r.tagType + "." + r.id + ".text") : "";
                int wc = countWords(r.textAttr);
                String xmlKey = r.jarRel + "!" + r.xmlPath;
                boolean isXmlInternal = internalIdsByXml.getOrDefault(xmlKey, Collections.emptySet()).contains(r.tagType + "|" + r.id);
                String ancGroups = ancestorGroupsByKey.getOrDefault(xmlKey + "|" + r.tagType + "|" + r.id, "");

                String bFlags = "";
                boolean hasNonEmptySuperText = false;
                boolean callsSetTextInUpdate = false;

                if (r.className != null && !r.className.isEmpty()) {
                    String jp = javapOutputByClass.get(r.className);
                    if (jp == null) {
                        bFlags = "CLASS_NOT_FOUND_IN_JAVAP";
                    } else {
                        List<String> allFlags = new ArrayList<>();
                        String cur = r.className;
                        Set<String> seenSup = new HashSet<>();
                        while (cur != null && !cur.isEmpty() && seenSup.add(cur)) {
                            String curJp = javapOutputByClass.get(cur);
                            if (curJp == null) break;
                            BytecodeCheck bc = inspectClassBytecode(curJp, cur);
                            if (bc.nonEmptyTextInitOrCtor) hasNonEmptySuperText = true;
                            if (bc.callsSetTextInUpdate) callsSetTextInUpdate = true;
                            if (!"CLEAN".equals(bc.summary)) allFlags.add((cur.equals(r.className) ? "" : simpleName(cur) + ":") + bc.summary);
                            Matcher extM = Pattern.compile("extends\\s+([A-Za-z0-9_$.]+)").matcher(curJp);
                            cur = extM.find() ? extM.group(1) : null;
                            if (cur != null && (cur.startsWith("java.") || cur.startsWith("com.intellij.openapi.actionSystem.") || cur.equals("com.intellij.openapi.project.DumbAwareAction"))) {
                                cur = null;
                            }
                        }
                        bFlags = allFlags.isEmpty() ? "CLEAN" : String.join("+", allFlags);
                    }
                } else {
                    bFlags = "DEFAULT_ACTION_OR_GROUP";
                }

                String decision;
                String reason;
                if (proposedKey.isEmpty()) {
                    decision = "SKIP"; reason = "EMPTY_ID"; skipNoId++;
                } else if (existing031Keys.contains(r.effectiveBundle + "|" + proposedKey)) {
                    decision = "SKIP"; reason = "ALREADY_IN_031"; skip031++;
                } else if (isInternalDebugRag(r, isXmlInternal, ancGroups)) {
                    decision = "SKIP"; reason = "INTERNAL_DEBUG_RAG"; skipInternal++;
                } else if (wc > 15) {
                    decision = "SKIP"; reason = "WORDS_GT_15 (" + wc + ")"; skipWordLen++;
                } else if (hasNonEmptySuperText) {
                    decision = "SKIP"; reason = "CODE_CTOR_NONEMPTY_TEXT"; skipCtor++;
                } else if (callsSetTextInUpdate) {
                    decision = "SKIP"; reason = "CODE_UPDATE_SET_TEXT"; skipUpdate++;
                } else if (!seenOutKeys.add(r.effectiveBundle + "|" + proposedKey)) {
                    decision = "SKIP"; reason = "DUPLICATE_XML_KEY"; skipDup++;
                } else {
                    decision = "INCLUDE"; reason = "ELIGIBLE";
                    eligible.add(r);
                }

                pw.printf(Locale.ROOT, "%s,%s,%s,%s,%s,%s,%s,%d,%s,%s,%s,%s%n",
                        csv(r.pluginDir), csv(r.xmlPath), csv(r.tagType), csv(r.id),
                        csv(r.effectiveBundle), csv(proposedKey), csv(r.textAttr), wc,
                        csv(r.className), csv(bFlags), csv(decision), csv(reason));
            }
        }

        // 6. Write batches into stage1_in (chunk size <= 120)
        // Clear old stage1_in files if any
        try (var s = Files.list(STAGE1_IN_DIR)) {
            for (Path p : s.toList()) Files.deleteIfExists(p);
        }
        int batchIdx = 1;
        for (int i = 0; i < eligible.size(); i += 100) {
            List<Row187> sub = eligible.subList(i, Math.min(eligible.size(), i + 100));
            Path outTsv = STAGE1_IN_DIR.resolve(String.format(Locale.ROOT, "batch_S1_%03d.tsv", batchIdx++));
            try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(outTsv, StandardCharsets.UTF_8))) {
                pw.println("File\tKey\tEnglish\tArabic");
                for (Row187 r : sub) {
                    String propFile = r.effectiveBundle.substring(r.effectiveBundle.lastIndexOf('.') + 1) + ".properties";
                    String key = r.tagType + "." + r.id + ".text";
                    pw.println(propFile + "\t" + key + "\t" + r.textAttr.replace("\t", " ") + "\t");
                }
            }
            System.out.println("Wrote Stage 1 batch: " + outTsv.getFileName() + " (" + sub.size() + " rows)");
        }

        System.out.printf(Locale.ROOT,
                "\n=== STAGE 1 FILTER SUMMARY ===\nCandidates=%d | INCLUDE=%d | SKIP: already031=%d, noId=%d, internal/debug/rag=%d, >15w=%d, codeCtor=%d, codeUpdate=%d, dup=%d%n",
                candidates.size(), eligible.size(), skip031, skipNoId, skipInternal, skipWordLen, skipCtor, skipUpdate, skipDup);
    }

    private static boolean isInternalDebugRag(Row187 r, boolean isXmlInternal, String ancGroups) {
        if (isXmlInternal) return true;
        String id = r.id.toLowerCase(Locale.ROOT);
        String txt = r.textAttr.toLowerCase(Locale.ROOT);
        String grp = ancGroups.toLowerCase(Locale.ROOT);
        String xml = r.xmlPath.toLowerCase(Locale.ROOT);
        if (id.contains("internal") || grp.contains("internal")) return true;
        if (id.contains("rag") || txt.contains("rag") || grp.contains("rag")) return true;
        if (txt.contains("webp") && txt.contains("testaction")) return true;
        if (id.contains("debugAction") || id.endsWith("testaction") || id.contains(".internal.") || grp.contains("devactions")) return true;
        // Careful: "Debug" tools like "Attach Debugger" are user-facing, while Internal/Debug actions like Dump/Demo/Mock/ServerFlagDebug/PrintDebug are internal!
        if (grp.contains("android.internal") || grp.contains("tools.internal") || grp.contains("studiobot.internal") || grp.contains("debuggroup")) return true;
        if (xml.contains("internal") || txt.startsWith("dump ") || txt.contains("webp support test") || txt.equals("webpsupporttestaction")) return true;
        if (id.contains("dump") && (id.contains("internal") || txt.contains("dump"))) return true;
        if (txt.contains("demo") && id.contains("demo")) return true;
        return false;
    }

    private static void walkXmlInternal(Element el, boolean inheritedInternal, String groupChain,
                                        Set<String> intSet, String xmlKey, Map<String, String> ancMap) {
        String tag = el.getTagName();
        boolean curInt = inheritedInternal || "true".equalsIgnoreCase(el.getAttribute("internal"));
        String curChain = groupChain;
        if ("group".equals(tag) && !el.getAttribute("id").isEmpty()) {
            curChain = curChain.isEmpty() ? el.getAttribute("id") : (curChain + ">" + el.getAttribute("id"));
        }
        if ("action".equals(tag) || "group".equals(tag)) {
            String id = el.getAttribute("id");
            if (!id.isEmpty()) {
                // also inspect <add-to-group group-id="..."> children
                StringBuilder addGroups = new StringBuilder(curChain);
                NodeList nl = el.getChildNodes();
                for (int i = 0; i < nl.getLength(); i++) {
                    if (nl.item(i) instanceof Element ce && "add-to-group".equals(ce.getTagName())) {
                        String gid = ce.getAttribute("group-id");
                        if (!gid.isEmpty()) {
                            if (addGroups.length() > 0) addGroups.append(";");
                            addGroups.append(gid);
                        }
                    }
                }
                ancMap.put(xmlKey + "|" + tag + "|" + id, addGroups.toString());
                if (curInt) intSet.add(tag + "|" + id);
            }
        }
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element ch) {
                walkXmlInternal(ch, curInt, curChain, intSet, xmlKey, ancMap);
            }
        }
    }

    static class BytecodeCheck {
        boolean nonEmptyTextInitOrCtor;
        boolean callsSetTextInUpdate;
        String summary;
    }

    private static BytecodeCheck inspectClassBytecode(String javapText, String className) {
        BytecodeCheck res = new BytecodeCheck();
        String superFqcn = "";
        Matcher extM = Pattern.compile("extends\\s+([A-Za-z0-9_$.]+)").matcher(javapText);
        if (extM.find()) superFqcn = extM.group(1).replace('.', '/');

        String[] lines = javapText.split("\\R");
        boolean inCtor = false;
        boolean inUpdate = false;
        boolean hasLdcStringInCtor = false;
        boolean callsSuperWithText = false;
        boolean callsSetTextInCtor = false;

        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].trim();
            if ((l.endsWith(");") || l.contains(") throws ")) && l.contains("(") && !l.startsWith("?") && !l.contains(": ")) {
                String sigPart = l.substring(0, l.indexOf('('));
                inCtor = sigPart.endsWith("." + simpleName(className)) || sigPart.equals(simpleName(className)) || sigPart.equals(className);
                inUpdate = l.contains(" update(com.intellij.openapi.actionSystem.AnActionEvent)");
                continue;
            }
            if (inCtor) {
                if (l.contains("ldc ") && l.contains("// String ")) {
                    hasLdcStringInCtor = true;
                }
                if (l.contains("invokespecial") && l.contains("\"<init>\"")
                        && (l.contains("(Ljava/lang/String;") || l.contains("(Ljava/util/function/Supplier;"))) {
                    if ((!superFqcn.isEmpty() && l.contains(superFqcn + ".\"<init>\""))
                            || l.contains("AnAction.\"<init>\"")
                            || l.contains("DumbAwareAction.\"<init>\"")
                            || l.contains("ActionGroup.\"<init>\"")
                            || l.contains("DefaultActionGroup.\"<init>\"")
                            || l.contains("BaseAction.\"<init>\"")
                            || l.contains("ToggleAction.\"<init>\"")
                            || l.contains("ComboBoxAction.\"<init>\"")
                            || l.contains("." + simpleName(className) + ".\"<init>\"")
                            || (hasLdcStringInCtor && l.contains("Action.\"<init>\""))) {
                        callsSuperWithText = true;
                    }
                }
                if (l.contains("Presentation.setText:") || l.contains("Presentation.setFallbackPresentationText:")) {
                    callsSetTextInCtor = true;
                }
            }
            if (inUpdate) {
                if (l.contains("Presentation.setText:") || l.contains("Presentation.setFallbackPresentationText:")) {
                    res.callsSetTextInUpdate = true;
                }
            }
        }
        res.nonEmptyTextInitOrCtor = callsSuperWithText || callsSetTextInCtor;
        List<String> flags = new ArrayList<>();
        if (callsSuperWithText) flags.add("SUPER_CTOR_STRING");
        if (callsSetTextInCtor) flags.add("CTOR_SET_TEXT");
        if (res.callsSetTextInUpdate) flags.add("UPDATE_SET_TEXT");
        if (flags.isEmpty()) flags.add(hasLdcStringInCtor ? "EMPTY_SUPER_HAS_STRINGS" : "CLEAN");
        res.summary = String.join("|", flags);
        return res;
    }

    private static String simpleName(String fqcn) {
        int i = fqcn.lastIndexOf('.');
        return i >= 0 ? fqcn.substring(i + 1) : fqcn;
    }

    private static void splitJavapByClass(String out, Map<String, String> map) {
        String[] parts = out.split("(?=Compiled from )");
        for (String p : parts) {
            Matcher m = Pattern.compile("(?:class|interface|enum)\\s+([A-Za-z0-9_$.]+)").matcher(p);
            if (m.find()) {
                map.put(m.group(1), p);
            }
        }
    }

    private static int countWords(String s) {
        if (s == null) return 0;
        String t = s.replace("&", "").replace("_", "").trim();
        if (t.isEmpty()) return 0;
        return t.split("\\s+").length;
    }

    private static List<String> parseCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQ = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQ) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') { sb.append('"'); i++; }
                else if (c == '"') inQ = false;
                else sb.append(c);
            } else {
                if (c == '"') inQ = true;
                else if (c == ',') { out.add(sb.toString()); sb.setLength(0); }
                else sb.append(c);
            }
        }
        out.add(sb.toString());
        return out;
    }

    private static String csv(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
