/**
 * Stage 0 Bytecode Reachability Auditor for IntelliJ/Android Studio resource bundles.
 * أداة فحص إمكانية وصول حزم النصوص إلى LanguagePack (تفكيك الـ bytecode لتحديد DynamicBundle مقابل ResourceBundle.getBundle المباشر).
 *
 * Inputs:
 *   -Dstudio.home=<path>   Android Studio installation directory (or ANDROID_STUDIO_HOME env var)
 *   -Dwork.dir=<path>      Working directory containing discovery.csv (and optional arabic-language-pack-0.3.1.zip)
 * Outputs:
 *   <work.dir>/bundle_reachability.csv
 */
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;

public class AnalyzeBundleReachability {
    private static final Path STUDIO_ROOT = Paths.get(System.getProperty("studio.home", System.getenv("ANDROID_STUDIO_HOME") != null ? System.getenv("ANDROID_STUDIO_HOME") : "C:\\Program Files\\Android\\Android Studio"));
    private static final Path PHASE2 = Paths.get(System.getProperty("work.dir", System.getenv("WORK_DIR") != null ? System.getenv("WORK_DIR") : "."));
    private static final Path PACK_031_ZIP = PHASE2.resolve("arabic-language-pack-0.3.1.zip");
    private static final Path DISCOVERY_CSV = PHASE2.resolve("discovery.csv");
    private static final Path OUT_CSV = PHASE2.resolve("bundle_reachability.csv");

    static class TargetBundle {
        String fileName;
        String internalPath; // e.g., messages/AndroidBundle.properties
        String baseName;     // e.g., messages.AndroidBundle
        String baseSlash;    // e.g., messages/AndroidBundle
        String simpleName;   // e.g., AndroidBundle
        String category;
        String jarRel;
        boolean in031;
        int l2, l3, l4;
    }

    static class ClassMatch {
        String jarRel;
        String className;
        String superName;
        boolean callsDynamicBundle;
        boolean callsAbstractBundle;
        boolean callsMessageBundleRef;
        boolean callsJavaResourceBundleGetBundle;
        Set<String> matchedStrings = new LinkedHashSet<>();
    }

    public static void main(String[] args) throws Exception {
        for (String a : args) { if ("-h".equals(a) || "--help".equals(a)) { System.out.println("Usage: AnalyzeBundleReachability  (-Dstudio.home=... -Dwork.dir=...)"); return; } }
        Map<String, TargetBundle> targets = new LinkedHashMap<>();

        // 1. Load discovery.csv bundles (eligible or in 0.3.1)
        List<String> dLines = Files.readAllLines(DISCOVERY_CSV, StandardCharsets.UTF_8);
        for (int i = 1; i < dLines.size(); i++) {
            String line = dLines.get(i).trim();
            if (line.isEmpty()) continue;
            List<String> c = parseCsvLine(line);
            if (c.size() < 12) continue;
            String fileName = c.get(0);
            String cat = c.get(1);
            String jarRel = c.get(3);
            String internalPath = c.get(4);
            int l2 = Integer.parseInt(c.get(8));
            int l3 = Integer.parseInt(c.get(9));
            int l4 = Integer.parseInt(c.get(10));
            int already02 = Integer.parseInt(c.get(6));
            int l1 = Integer.parseInt(c.get(7));

            if (already02 + l1 + l2 + l3 + l4 == 0 && !cat.equals("GEMINI_AI") && !cat.equals("UI_GENERAL") && !cat.equals("SETTINGS")) {
                continue;
            }
            String baseSlash = internalPath.endsWith(".properties")
                    ? internalPath.substring(0, internalPath.length() - ".properties".length())
                    : internalPath;
            String baseDot = baseSlash.replace('/', '.');

            TargetBundle tb = new TargetBundle();
            tb.fileName = fileName;
            tb.internalPath = internalPath;
            tb.baseName = baseDot;
            tb.baseSlash = baseSlash;
            tb.simpleName = fileName.endsWith(".properties") ? fileName.substring(0, fileName.length() - ".properties".length()) : fileName;
            tb.category = cat;
            tb.jarRel = jarRel;
            tb.l2 = l2;
            tb.l3 = l3;
            tb.l4 = l4;
            targets.put(baseDot, tb);
        }

        // 2. Also ensure all 162 bundles in 0.3.1 are included (when 0.3.1 ZIP exists)
        if (Files.exists(PACK_031_ZIP))
        try (ZipFile outer = new ZipFile(PACK_031_ZIP.toFile())) {
            ZipEntry je = outer.getEntry("arabic-language-pack-0.3.1/lib/arabic-language-pack-0.3.1.jar");
            Path tmp = Files.createTempFile("p031", ".jar");
            try (InputStream is = outer.getInputStream(je)) {
                Files.copy(is, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            try (ZipFile inner = new ZipFile(tmp.toFile())) {
                Enumeration<? extends ZipEntry> en = inner.entries();
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement();
                    String n = e.getName();
                    if (!n.endsWith("_ar.properties")) continue;
                    String origPath = n.replace("_ar.properties", ".properties");
                    String baseSlash = origPath.substring(0, origPath.length() - ".properties".length());
                    String baseDot = baseSlash.replace('/', '.');
                    TargetBundle tb = targets.get(baseDot);
                    if (tb == null) {
                        tb = new TargetBundle();
                        tb.fileName = Paths.get(origPath).getFileName().toString();
                        tb.internalPath = origPath;
                        tb.baseName = baseDot;
                        tb.baseSlash = baseSlash;
                        tb.simpleName = tb.fileName.substring(0, tb.fileName.length() - ".properties".length());
                        tb.category = "IN_031_EXTRA";
                        tb.jarRel = "";
                        targets.put(baseDot, tb);
                    }
                    tb.in031 = true;
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        }

        System.out.println("Stage 0 target bundles to audit: " + targets.size());

        // Prepare lookup sets
        Set<String> allBaseDots = targets.keySet();
        Set<String> allBaseSlashes = new HashSet<>();
        Map<String, String> slashToDot = new HashMap<>();
        for (TargetBundle tb : targets.values()) {
            allBaseSlashes.add(tb.baseSlash);
            slashToDot.put(tb.baseSlash, tb.baseName);
        }

        Map<String, List<ClassMatch>> matchesByBundle = new HashMap<>();

        // 3. Scan all Studio JARs for class references to target bundles
        List<Path> jars = new ArrayList<>();
        for (String sub : Arrays.asList("lib", "plugins")) {
            try (var walk = Files.walk(STUDIO_ROOT.resolve(sub))) {
                walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted()
                    .forEach(jars::add);
            }
        }

        for (Path jp : jars) {
            String relJar = STUDIO_ROOT.relativize(jp).toString().replace('\\', '/');
            try (JarFile jf = new JarFile(jp.toFile())) {
                Enumeration<JarEntry> en = jf.entries();
                while (en.hasMoreElements()) {
                    JarEntry je = en.nextElement();
                    String name = je.getName();
                    if (!name.endsWith(".class") || je.isDirectory()) continue;

                    // Quick scan constant pool
                    byte[] bytes;
                    try (InputStream is = jf.getInputStream(je)) {
                        bytes = is.readAllBytes();
                    }
                    ClassPoolInfo cpi = parseClassPool(bytes);
                    if (cpi.utf8Strings.isEmpty()) continue;

                    Set<String> hitBundles = new LinkedHashSet<>();
                    for (String s : cpi.stringLiterals) {
                        if (allBaseDots.contains(s)) hitBundles.add(s);
                        else if (allBaseSlashes.contains(s)) hitBundles.add(slashToDot.get(s));
                    }
                    // Also check if class name itself ends with *Bundle / *Messages / *Strings and references DynamicBundle/MessageBundleReference/ResourceBundle in UTF8 pool
                    if (hitBundles.isEmpty()) {
                        for (String u : cpi.utf8Strings) {
                            if (allBaseDots.contains(u)) hitBundles.add(u);
                            else if (allBaseSlashes.contains(u)) hitBundles.add(slashToDot.get(u));
                        }
                    }
                    if (hitBundles.isEmpty()) continue;

                    // Filter: only care if the class actually reads a bundle (not just XML/plugin descriptors or test classes)
                    boolean looksLikeBundleReader = cpi.superName.contains("DynamicBundle")
                            || cpi.superName.contains("AbstractBundle")
                            || cpi.callsMessageBundleRef
                            || cpi.callsJavaResourceBundleGetBundle
                            || cpi.callsDynamicBundle
                            || cpi.callsAbstractBundle
                            || name.endsWith("Bundle.class")
                            || name.endsWith("Bundle$Companion.class")
                            || name.contains("Messages.class");
                    if (!looksLikeBundleReader) continue;

                    for (String bDot : hitBundles) {
                        ClassMatch cm = new ClassMatch();
                        cm.jarRel = relJar;
                        cm.className = name.substring(0, name.length() - ".class".length()).replace('/', '.');
                        cm.superName = cpi.superName.replace('/', '.');
                        cm.callsDynamicBundle = cpi.callsDynamicBundle
                                || cm.superName.equals("com.intellij.DynamicBundle")
                                || cm.superName.equals("org.jetbrains.kotlin.util.AbstractKotlinBundle");
                        cm.callsAbstractBundle = cpi.callsAbstractBundle || cm.superName.equals("com.intellij.AbstractBundle");
                        cm.callsMessageBundleRef = cpi.callsMessageBundleRef;
                        cm.callsJavaResourceBundleGetBundle = cpi.callsJavaResourceBundleGetBundle;
                        matchesByBundle.computeIfAbsent(bDot, k -> new ArrayList<>()).add(cm);
                    }
                }
            } catch (Exception ignored) {}
        }

        // 4. Classify each bundle & write bundle_reachability.csv
        int cntWorkingDynamic = 0, cntWorkingAbstract = 0, cntBypasses = 0, cntUnknown = 0;
        List<String> bypassLines = new ArrayList<>();

        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(OUT_CSV, StandardCharsets.UTF_8))) {
            pw.println("BundleFile,InternalPath,BaseName,DiscoveryCategory,In031,L2Keys,L3Keys,L4Keys,JarRel,ReaderClasses,SuperClasses,Mechanism,ReachableWithLanguagePack,Stage234Action");
            for (TargetBundle tb : targets.values()) {
                List<ClassMatch> cms = matchesByBundle.getOrDefault(tb.baseName, Collections.emptyList());

                boolean anyDynamic = cms.stream().anyMatch(c -> c.callsDynamicBundle);
                boolean anyAbstract = cms.stream().anyMatch(c -> c.callsAbstractBundle && !c.callsDynamicBundle);
                boolean anyMsgRef = cms.stream().anyMatch(c -> c.callsMessageBundleRef);
                boolean anyDirectGetBundle = cms.stream().anyMatch(c -> c.callsJavaResourceBundleGetBundle);

                String mechanism;
                boolean reachable;
                String action;

                if (anyDynamic) {
                    mechanism = "DYNAMIC_BUNDLE";
                    reachable = true;
                    action = "INCLUDE";
                    cntWorkingDynamic++;
                } else if (anyMsgRef || anyDirectGetBundle) {
                    // Check if ALL reader classes use MessageBundleReference / ResourceBundle.getBundle directly (no DynamicBundle)
                    mechanism = anyMsgRef ? "BYPASSES_MESSAGE_BUNDLE_REFERENCE" : "BYPASSES_DIRECT_RESOURCE_BUNDLE";
                    reachable = false;
                    action = "SKIP_NEW_KEYS_IN_STAGES_2_3_4";
                    cntBypasses++;
                } else if (anyAbstract) {
                    // Extends/calls AbstractBundle without DynamicBundle - treat cautiously or note? AbstractBundle calls IntelliJResourceControl; wait check
                    mechanism = "ABSTRACT_BUNDLE_ONLY";
                    reachable = true;
                    action = "INCLUDE_ABSTRACT";
                    cntWorkingAbstract++;
                } else {
                    mechanism = "UNKNOWN_NO_DIRECT_CLASS_MATCH";
                    reachable = true; // Rule: الملفات غير المحددة تُعامل كأنها تعمل
                    action = "INCLUDE_ASSUME_WORKING";
                    cntUnknown++;
                }

                String readers = cms.stream().map(c -> c.className).distinct().limit(5).reduce((a, b) -> a + " | " + b).orElse("");
                String supers = cms.stream().map(c -> c.superName).distinct().limit(5).reduce((a, b) -> a + " | " + b).orElse("");
                String foundJar = !tb.jarRel.isEmpty() ? tb.jarRel : cms.stream().map(c -> c.jarRel).findFirst().orElse("");

                pw.printf(Locale.ROOT, "%s,%s,%s,%s,%s,%d,%d,%d,%s,%s,%s,%s,%s,%s%n",
                        csv(tb.fileName), csv(tb.internalPath), csv(tb.baseName), csv(tb.category),
                        tb.in031, tb.l2, tb.l3, tb.l4,
                        csv(foundJar), csv(readers), csv(supers),
                        csv(mechanism), reachable, csv(action));

                if (!reachable) {
                    bypassLines.add(String.format(Locale.ROOT,
                            "  BYPASS | %-34s | cat=%-10s | L2=%3d L3=%3d L4=%3d | mech=%s | cls=%s",
                            tb.fileName, tb.category, tb.l2, tb.l3, tb.l4, mechanism, readers));
                }
            }
        }

        System.out.println("\n=== STAGE 0: BUNDLE REACHABILITY SUMMARY ===");
        System.out.println("Total audited bundles    : " + targets.size());
        System.out.println("  DYNAMIC_BUNDLE         : " + cntWorkingDynamic);
        System.out.println("  ABSTRACT_BUNDLE_ONLY   : " + cntWorkingAbstract);
        System.out.println("  BYPASSES_LANGUAGE_PACK : " + cntBypasses);
        System.out.println("  UNKNOWN (treat working): " + cntUnknown);
        System.out.println("\n=== BUNDLES THAT BYPASS LANGUAGE PACK ===");
        bypassLines.forEach(System.out::println);
        System.out.println("Wrote: " + OUT_CSV);
    }

    static class ClassPoolInfo {
        String superName = "";
        List<String> utf8Strings = new ArrayList<>();
        List<String> stringLiterals = new ArrayList<>();
        boolean callsDynamicBundle;
        boolean callsAbstractBundle;
        boolean callsMessageBundleRef;
        boolean callsJavaResourceBundleGetBundle;
    }

    private static ClassPoolInfo parseClassPool(byte[] b) {
        ClassPoolInfo res = new ClassPoolInfo();
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(b));
            if (in.readInt() != 0xCAFEBABE) return res;
            in.readUnsignedShort(); in.readUnsignedShort();
            int cpCount = in.readUnsignedShort();
            String[] utf8 = new String[cpCount];
            int[] strIdx = new int[cpCount];
            int[] clsIdx = new int[cpCount];
            int[] natName = new int[cpCount];
            int[] natDesc = new int[cpCount];
            int[] refCls = new int[cpCount];
            int[] refNat = new int[cpCount];
            byte[] tags = new byte[cpCount];

            for (int i = 1; i < cpCount; i++) {
                int tag = in.readUnsignedByte();
                tags[i] = (byte) tag;
                switch (tag) {
                    case 1 -> utf8[i] = in.readUTF();
                    case 3, 4 -> in.readInt();
                    case 5, 6 -> { in.readLong(); i++; }
                    case 7 -> clsIdx[i] = in.readUnsignedShort();
                    case 8 -> strIdx[i] = in.readUnsignedShort();
                    case 9, 10, 11 -> { refCls[i] = in.readUnsignedShort(); refNat[i] = in.readUnsignedShort(); }
                    case 12 -> { natName[i] = in.readUnsignedShort(); natDesc[i] = in.readUnsignedShort(); }
                    case 15 -> { in.readUnsignedByte(); in.readUnsignedShort(); }
                    case 16 -> in.readUnsignedShort();
                    case 17, 18 -> { in.readUnsignedShort(); in.readUnsignedShort(); }
                    case 19, 20 -> in.readUnsignedShort();
                    default -> { return res; }
                }
            }
            in.readUnsignedShort(); // access flags
            in.readUnsignedShort(); // this_class
            int superIdx = in.readUnsignedShort();
            if (superIdx > 0 && superIdx < cpCount && clsIdx[superIdx] > 0 && utf8[clsIdx[superIdx]] != null) {
                res.superName = utf8[clsIdx[superIdx]];
            }
            for (int i = 1; i < cpCount; i++) {
                if (tags[i] == 1 && utf8[i] != null) {
                    res.utf8Strings.add(utf8[i]);
                } else if (tags[i] == 8 && strIdx[i] < cpCount && utf8[strIdx[i]] != null) {
                    res.stringLiterals.add(utf8[strIdx[i]]);
                } else if (tags[i] == 10 || tags[i] == 11) {
                    int ci = refCls[i];
                    int ni = refNat[i];
                    if (ci > 0 && ci < cpCount && ni > 0 && ni < cpCount) {
                        String cn = utf8[clsIdx[ci]];
                        String mn = utf8[natName[ni]];
                        if ("com/intellij/DynamicBundle".equals(cn)) res.callsDynamicBundle = true;
                        else if ("com/intellij/AbstractBundle".equals(cn)) res.callsAbstractBundle = true;
                        else if ("com/android/tools/idea/localization/MessageBundleReference".equals(cn)) res.callsMessageBundleRef = true;
                        else if ("java/util/ResourceBundle".equals(cn) && "getBundle".equals(mn)) res.callsJavaResourceBundleGetBundle = true;
                    }
                }
            }
        } catch (Exception ignored) {}
        return res;
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
