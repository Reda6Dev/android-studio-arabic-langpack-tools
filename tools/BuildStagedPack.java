/**
 * Staged pack builder applying Stage 0 reachability exclusions, Stage 1 XML action keys,
 * Inputs:  <work.dir>/bundle_reachability.csv, bundle_paths.tsv, extracted/*.properties, batches/*_out/*.tsv, optional arabic-language-pack-0.3.1.zip
 * Outputs: <work.dir>/arabic-language-pack-<version>.zip and analysis/stage_<version>_stats.csv
 * and Stage 2-4 batches with full JBR verification.
 * أداة البناء المرحلي (مع استبعاد الحزم المتجاوزة من المرحلة 0 وفحص التشغيل في JBR).
 *
 * Configuration properties (optional):
 *   -Dstudio.home=<path>   Path to Android Studio installation (or ANDROID_STUDIO_HOME env var)
 *   -Dwork.dir=<path>      Working directory containing batches/, extracted/, analysis/ (default: ".")
 *   -Dplugin.id=<id>       Target plugin ID (default: com.reda6dev.arabic.languagepack)
 */
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.MessageFormat;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;

public class BuildStagedPack {
    private static final String STUDIO_HOME = System.getProperty("studio.home", System.getenv("ANDROID_STUDIO_HOME") != null ? System.getenv("ANDROID_STUDIO_HOME") : "C:\\Program Files\\Android\\Android Studio");
    private static final Path STUDIO_ROOT = Paths.get(STUDIO_HOME);
    private static final Path PHASE2 = Paths.get(System.getProperty("work.dir", System.getenv("WORK_DIR") != null ? System.getenv("WORK_DIR") : "."));
    private static final Path PACK_031_ZIP = PHASE2.resolve("arabic-language-pack-0.3.1.zip");
    private static final Path REACHABILITY_CSV = PHASE2.resolve("bundle_reachability.csv");
    private static final Path BUNDLE_PATHS_TSV = PHASE2.resolve("bundle_paths.tsv");
    private static final Path EXTRACTED = PHASE2.resolve("extracted");
    private static final Path BATCHES = PHASE2.resolve("batches");
    private static final Path ANALYSIS = PHASE2.resolve("analysis");

    static final Set<String> SLUG_EXCLUDE_KEYS = new HashSet<>(Arrays.asList(
            "GitBundle.properties|gpg.jb.manual.link",
            "GitBundle.properties|gpg.pinentry.jb.manual.link",
            "SmlBundle.properties|sml.studiobot.testGeneration.dialog.helpId",
            "SmlBundle.properties|sml.studiobot.codeBlock.action.functionName.text",
            "SmlBundle.properties|sml.studiobot.codeBlock.action.methodName.text",
            "SmlIjBundle.properties|aiplugin.onboarding.tier.introBlurb.html.wrapper",
            "SmlIjBundle.properties|aiplugin.feedback.dialog.description.prefix.diff.error",
            "ExecutionBundle.properties|dialog.message.internal.error"
    ));

    static final List<String> CODE_4_ACTIONS_KEYS = Arrays.asList(
            "messages/AndroidBundle_ar.properties|action.Android.GenerateSignedApk.text",
            "messages/AndroidBundle_ar.properties|action.Android.DeviceManager2.text",
            "messages/DeviceManagerBundle_ar.properties|action.Android.DeviceManager2.text",
            "messages/AndroidBundle_ar.properties|action.Android.RunAndroidSdkManager.text",
            "messages/AndroidBundle_ar.properties|action.WelcomeScreen.RunAndroidSdkManager.text",
            "messages/AndroidBundle_ar.properties|action.Android.RunLayoutInspector.text"
    );

    static class BundleMeta {
        String fileName, internalPath, jarRel;
    }

    static class LoadedPack {
        // arInternalPath (e.g., messages/AndroidBundle_ar.properties) -> ordered Map<key, val>
        Map<String, LinkedHashMap<String, String>> byArPath = new LinkedHashMap<>();
        int totalKeys = 0;
    }

    public static void main(String[] args) throws Exception {
        for (String a : args) { if ("-h".equals(a) || "--help".equals(a)) { System.out.println("Usage: BuildStagedPack <0.3.2|0.4|0.5|0.6>  (-Dstudio.home=... -Dwork.dir=... -Dplugin.id=...)"); return; } }
        if (args.length == 0) {
            System.err.println("Usage: BuildStagedPack <0.3.2|0.4|0.5|0.6>");
            System.exit(1);
        }
        String version = args[0];
        boolean incS1 = compareVer(version, "0.3.2") >= 0;
        boolean incL2 = compareVer(version, "0.4") >= 0;
        boolean incL3 = compareVer(version, "0.5") >= 0;
        boolean incL4 = compareVer(version, "0.6") >= 0;

        // 1. Load reachability exclusions (bundles bypassing language pack -> skip new keys in L2/L3/L4)
        Set<String> bypassBundleFiles = new HashSet<>();
        Map<String, String> bypassReasons = new HashMap<>();
        List<String> rLines = Files.readAllLines(REACHABILITY_CSV, StandardCharsets.UTF_8);
        for (int i = 1; i < rLines.size(); i++) {
            List<String> c = parseCsvLine(rLines.get(i));
            if (c.size() < 13) continue;
            if ("false".equalsIgnoreCase(c.get(12))) {
                bypassBundleFiles.add(c.get(0));
                bypassReasons.put(c.get(0), c.get(11));
            }
        }
        System.out.println("Loaded Stage 0 bypass bundles (skipped in Stages 2-4): " + bypassBundleFiles.size());

        // 2. Load bundle_paths.tsv + extracted English props
        Map<String, BundleMeta> metaByFile = new LinkedHashMap<>();
        List<String> bpLines = Files.readAllLines(BUNDLE_PATHS_TSV, StandardCharsets.UTF_8);
        for (int i = 1; i < bpLines.size(); i++) {
            String line = bpLines.get(i);
            if (line.trim().isEmpty()) continue;
            String[] p = line.split("\t", -1);
            if (p.length >= 4) {
                BundleMeta m = new BundleMeta();
                m.fileName = p[0];
                m.internalPath = p[1];
                m.jarRel = p[2];
                metaByFile.putIfAbsent(m.fileName, m);
            }
        }

        // 3. Load 0.3.1 base pack completely
        LoadedPack base031 = loadZipPack(PACK_031_ZIP, "arabic-language-pack-0.3.1/lib/arabic-language-pack-0.3.1.jar");
        System.out.println("Loaded 0.3.1 base: bundles=" + base031.byArPath.size() + ", keys=" + base031.totalKeys);

        // Track arPath -> origFileName for lookups
        Map<String, String> fileToArPath = new LinkedHashMap<>();
        for (String arPath : base031.byArPath.keySet()) {
            String origName = Paths.get(arPath).getFileName().toString().replace("_ar.properties", ".properties");
            fileToArPath.putIfAbsent(origName, arPath);
        }
        for (var e : metaByFile.entrySet()) {
            fileToArPath.putIfAbsent(e.getKey(), e.getValue().internalPath.replace(".properties", "_ar.properties"));
        }

        // Working copy starting from 0.3.1
        Map<String, LinkedHashMap<String, String>> finalByArPath = new LinkedHashMap<>();
        for (var e : base031.byArPath.entrySet()) {
            finalByArPath.put(e.getKey(), new LinkedHashMap<>(e.getValue()));
        }

        Map<String, Integer> addedPerArPath = new LinkedHashMap<>();
        Map<String, Integer> skippedBypassPerFile = new LinkedHashMap<>();
        List<String[]> currentStageRows = new ArrayList<>();

        // Overlay Stage 1 (0.3.2) -> ALWAYS allowed even for AndroidBundle (loaded by ActionManagerImpl via DynamicBundle!)
        if (incS1) {
            overlayBatches("stage1_out", "batch_S1_*.tsv", Collections.emptySet(), fileToArPath, finalByArPath,
                    version.equals("0.3.2") ? addedPerArPath : new HashMap<>(),
                    new HashMap<>(), version.equals("0.3.2") ? currentStageRows : null);
        }
        // Snapshot prev-stage count for the requested version
        Map<String, Integer> prevCountByArPath = new LinkedHashMap<>();
        if (version.equals("0.3.2")) {
            for (var e : base031.byArPath.entrySet()) prevCountByArPath.put(e.getKey(), e.getValue().size());
        } else {
            // Snapshot cumulative before current version's stage
            if (incL2 && version.equals("0.4")) snapshotCounts(finalByArPath, prevCountByArPath);
        }
        if (incL2) {
            overlayBatches("level2_out", "batch_L2_*.tsv", bypassBundleFiles, fileToArPath, finalByArPath,
                    version.equals("0.4") ? addedPerArPath : new HashMap<>(),
                    version.equals("0.4") ? skippedBypassPerFile : new HashMap<>(),
                    version.equals("0.4") ? currentStageRows : null);
        }
        if (incL3 && version.equals("0.5")) snapshotCounts(finalByArPath, prevCountByArPath);
        if (incL3) {
            overlayBatches("level3_out", "batch_L3_*.tsv", bypassBundleFiles, fileToArPath, finalByArPath,
                    version.equals("0.5") ? addedPerArPath : new HashMap<>(),
                    version.equals("0.5") ? skippedBypassPerFile : new HashMap<>(),
                    version.equals("0.5") ? currentStageRows : null);
        }
        if (incL4 && version.equals("0.6")) snapshotCounts(finalByArPath, prevCountByArPath);
        if (incL4) {
            overlayBatches("level4_out", "batch_L4_*.tsv", bypassBundleFiles, fileToArPath, finalByArPath,
                    version.equals("0.6") ? addedPerArPath : new HashMap<>(),
                    version.equals("0.6") ? skippedBypassPerFile : new HashMap<>(),
                    version.equals("0.6") ? currentStageRows : null);
        }

        // 4. Verify 100% preservation of ALL 0.3.1 keys & Code-4 exclusions in 0.3.2
        for (var e : base031.byArPath.entrySet()) {
            LinkedHashMap<String, String> builtMap = finalByArPath.get(e.getKey());
            if (builtMap == null) throw new AssertionError("Lost bundle from 0.3.1: " + e.getKey());
            for (var kv : e.getValue().entrySet()) {
                if (!kv.getValue().equals(builtMap.get(kv.getKey()))) {
                    throw new AssertionError("Lost/modified 0.3.1 key: " + e.getKey() + "|" + kv.getKey());
                }
            }
        }
        for (String forbidden : CODE_4_ACTIONS_KEYS) {
            String[] sp = forbidden.split("\\|", 2);
            var m = finalByArPath.get(sp[0]);
            if (m != null && m.containsKey(sp[1])) {
                throw new AssertionError("Forbidden code-driven key present: " + forbidden);
            }
        }

        // 5. Write JAR & ZIP (no BOM, sorted keys, UTF-8)
        Path jarRoot = PHASE2.resolve("jar-root-" + version);
        Path zipRoot = PHASE2.resolve("zip-root-" + version);
        Path finalZip = PHASE2.resolve("arabic-language-pack-" + version + ".zip");
        if (Files.exists(jarRoot)) deleteRecursively(jarRoot);
        if (Files.exists(zipRoot)) deleteRecursively(zipRoot);
        Files.createDirectories(jarRoot.resolve("META-INF"));

        int totalWrittenKeys = 0;
        List<String> sortedArPaths = new ArrayList<>(finalByArPath.keySet());
        Collections.sort(sortedArPaths);
        for (String arPath : sortedArPaths) {
            LinkedHashMap<String, String> trMap = finalByArPath.get(arPath);
            if (trMap.isEmpty()) continue;
            Path outProp = jarRoot.resolve(arPath.replace('/', File.separatorChar));
            Files.createDirectories(outProp.getParent());

            List<String> keys = new ArrayList<>(trMap.keySet());
            Collections.sort(keys);
            List<String> lines = new ArrayList<>(keys.size() + 1);
            lines.add("# Arabic (ar) translation for " + arPath + " (v" + version + ")");
            for (String k : keys) {
                lines.add(escapePropKey(k) + "=" + escapePropVal(trMap.get(k)));
                totalWrittenKeys++;
            }
            Files.writeString(outProp, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        }

        String pluginXml = "<idea-plugin>\n"
                + "  <id>" + System.getProperty("plugin.id", "com.reda6dev.arabic.languagepack") + "</id>\n"
                + "  <name>Arabic Language Pack (Community)</name>\n"
                + "  <version>" + version + "</version>\n"
                + "  <vendor url=\"https://github.com/Reda6Dev\">Reda6Dev</vendor>\n"
                + "  <description>Arabic localization for Android Studio UI, Settings, Tooltips, Gemini AI and Dialogs (v" + version + ").</description>\n"
                + "  <idea-version since-build=\"261.0\" until-build=\"261.*\"/>\n"
                + "  <depends>com.intellij.modules.platform</depends>\n"
                + "  <extensions defaultExtensionNs=\"com.intellij\">\n"
                + "    <languageBundle locale=\"ar\"/>\n"
                + "  </extensions>\n"
                + "</idea-plugin>\n";
        Files.writeString(jarRoot.resolve("META-INF").resolve("plugin.xml"), pluginXml, StandardCharsets.UTF_8);

        String dirName = "arabic-language-pack-" + version;
        Path pluginLibDir = zipRoot.resolve(dirName).resolve("lib");
        Files.createDirectories(pluginLibDir);
        Path jarOut = pluginLibDir.resolve(dirName + ".jar");
        String jarExe = STUDIO_HOME + "\\jbr\\bin\\jar.exe";

        runProc(Arrays.asList(jarExe, "-cMf", jarOut.toAbsolutePath().toString(), "-C", jarRoot.toAbsolutePath().toString(), "."));
        Files.deleteIfExists(finalZip);
        runProc(Arrays.asList(jarExe, "-cMf", finalZip.toAbsolutePath().toString(), "-C", zipRoot.toAbsolutePath().toString(), dirName));
        System.out.println("Packaged ZIP: " + finalZip + " (" + Files.size(finalZip) + " bytes), bundles=" + sortedArPaths.size() + ", totalKeys=" + totalWrittenKeys);

        // 6. Verify 0 BOM & 0 duplicate keys inside JAR
        verifyJarBytesAndDuplicates(jarOut);

        // 7. Verify ALL keys load via Studio's AbstractBundleKt._doResolveBundle + TextWithMnemonic + MessageFormat
        verifyRuntimeJar(jarOut, metaByFile, finalByArPath, version, currentStageRows);

        // 8. Write per-bundle stage stats CSV
        writeStageStatsCsv(version, finalByArPath, prevCountByArPath, addedPerArPath, skippedBypassPerFile, metaByFile, bypassReasons);
    }

    private static void snapshotCounts(Map<String, LinkedHashMap<String, String>> cur, Map<String, Integer> out) {
        for (var e : cur.entrySet()) out.put(e.getKey(), e.getValue().size());
    }

    private static void overlayBatches(
            String folder, String glob, Set<String> bypassBundleFiles,
            Map<String, String> fileToArPath,
            Map<String, LinkedHashMap<String, String>> finalByArPath,
            Map<String, Integer> addedPerArPath,
            Map<String, Integer> skippedBypassPerFile,
            List<String[]> collectedRows) throws IOException {
        Path dir = BATCHES.resolve(folder);
        if (!Files.exists(dir)) return;
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, glob)) {
            for (Path p : ds) files.add(p);
        }
        files.sort(Comparator.comparing(p -> p.getFileName().toString()));
        for (Path p : files) {
            List<String> rawLines = Files.readAllLines(p, StandardCharsets.UTF_8);
            for (String line : rawLines) {
                if (line.trim().isEmpty()) continue;
                String[] cols = line.split("\t", 4);
                if (cols.length < 4) continue;
                if ("File".equals(cols[0]) && "Key".equals(cols[1])) continue;
                String file = cols[0];
                String key = unescTsv(cols[1]);
                String en = unescTsv(cols[2]);
                String ar = unescTsv(cols[3]);
                if (ar.trim().isEmpty()) {
                    throw new IllegalStateException("Empty Arabic in " + p.getFileName() + " for " + file + "|" + key);
                }
                if (SLUG_EXCLUDE_KEYS.contains(file + "|" + key)) continue;
                if (bypassBundleFiles.contains(file)) {
                    skippedBypassPerFile.merge(file, 1, Integer::sum);
                    continue;
                }
                String arPath = fileToArPath.get(file);
                if (arPath == null) {
                    throw new IllegalStateException("No target JAR path for bundle: " + file);
                }
                LinkedHashMap<String, String> map = finalByArPath.computeIfAbsent(arPath, k -> new LinkedHashMap<>());
                if (!map.containsKey(key)) {
                    addedPerArPath.merge(arPath, 1, Integer::sum);
                }
                map.put(key, ar);
                if (collectedRows != null) {
                    collectedRows.add(new String[]{p.getFileName().toString(), file, arPath, key, en, ar});
                }
            }
        }
    }

    private static LoadedPack loadZipPack(Path zipPath, String innerJarEntry) throws Exception {
        LoadedPack lp = new LoadedPack();
        try (ZipFile zf = new ZipFile(zipPath.toFile())) {
            ZipEntry je = zf.getEntry(innerJarEntry);
            Path tmp = Files.createTempFile("basepack", ".jar");
            try (InputStream is = zf.getInputStream(je)) { Files.copy(is, tmp, StandardCopyOption.REPLACE_EXISTING); }
            try (ZipFile inner = new ZipFile(tmp.toFile())) {
                Enumeration<? extends ZipEntry> en = inner.entries();
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement();
                    if (e.isDirectory() || !e.getName().endsWith("_ar.properties")) continue;
                    Properties props = new Properties();
                    try (Reader r = new InputStreamReader(inner.getInputStream(e), StandardCharsets.UTF_8)) { props.load(r); }
                    LinkedHashMap<String, String> map = new LinkedHashMap<>();
                    List<String> keys = new ArrayList<>(props.stringPropertyNames());
                    Collections.sort(keys);
                    for (String k : keys) map.put(k, props.getProperty(k));
                    lp.byArPath.put(e.getName(), map);
                    lp.totalKeys += map.size();
                }
            } finally { Files.deleteIfExists(tmp); }
        }
        return lp;
    }

    private static void verifyJarBytesAndDuplicates(Path jarOut) throws IOException {
        int bomFiles = 0, dupKeys = 0, propFiles = 0;
        try (JarFile jf = new JarFile(jarOut.toFile())) {
            Enumeration<JarEntry> en = jf.entries();
            while (en.hasMoreElements()) {
                JarEntry je = en.nextElement();
                if (je.isDirectory()) continue;
                byte[] b = jf.getInputStream(je).readAllBytes();
                if (b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF) {
                    System.err.println("BOM DETECTED: " + je.getName());
                    bomFiles++;
                }
                if (je.getName().endsWith(".properties")) {
                    propFiles++;
                    Set<String> seen = new HashSet<>();
                    BufferedReader br = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(b), StandardCharsets.UTF_8));
                    String line;
                    while ((line = br.readLine()) != null) {
                        String t = line.trim();
                        if (t.isEmpty() || t.startsWith("#") || t.startsWith("!")) continue;
                        int eq = -1;
                        for (int j = 0; j < line.length(); j++) {
                            if (line.charAt(j) == '\\') { j++; continue; }
                            if (line.charAt(j) == '=' || line.charAt(j) == ':') { eq = j; break; }
                        }
                        if (eq > 0) {
                            String rawK = line.substring(0, eq);
                            if (!seen.add(rawK)) {
                                System.err.println("DUP KEY in " + je.getName() + ": " + rawK);
                                dupKeys++;
                            }
                        }
                    }
                }
            }
        }
        System.out.println("JAR Integrity Check: propFiles=" + propFiles + ", bomFiles=" + bomFiles + ", dupKeys=" + dupKeys);
        if (bomFiles > 0 || dupKeys > 0) System.exit(4);
    }

    private static void verifyRuntimeJar(
            Path jarOut, Map<String, BundleMeta> metaByFile,
            Map<String, LinkedHashMap<String, String>> finalByArPath,
            String version, List<String[]> stageRows) throws Exception {
        List<URL> urls = new ArrayList<>();
        urls.add(jarOut.toUri().toURL());
        Path libDir = STUDIO_ROOT.resolve("lib");
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(libDir, "*.jar")) {
            for (Path p : ds) urls.add(p.toUri().toURL());
        }
        Set<String> extraJars = new LinkedHashSet<>();
        for (BundleMeta m : metaByFile.values()) {
            if (!m.jarRel.startsWith("lib/")) extraJars.add(m.jarRel);
        }
        extraJars.add("plugins/android/lib/android.jar");
        extraJars.add("plugins/design-tools/lib/design-tools.jar");
        extraJars.add("plugins/gemini/lib/aiplugin.jar");
        for (String extra : extraJars) {
            Path ep = STUDIO_ROOT.resolve(extra.replace('/', File.separatorChar));
            if (Files.exists(ep)) urls.add(ep.toUri().toURL());
        }

        int totalVerified = 0, errors = 0;
        Locale arLocale = Locale.forLanguageTag("ar");
        try (URLClassLoader cl = new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader())) {
            Class<?> abKt = Class.forName("com.intellij.AbstractBundleKt", true, cl);
            Method resolve = abKt.getDeclaredMethod("_doResolveBundle", ClassLoader.class, Locale.class, String.class);
            resolve.setAccessible(true);

            Class<?> twmClass = Class.forName("com.intellij.openapi.util.text.TextWithMnemonic", true, cl);
            Method parseMnem = twmClass.getDeclaredMethod("parse", String.class);
            parseMnem.setAccessible(true);

            for (var e : finalByArPath.entrySet()) {
                String arPath = e.getKey();
                String bundleName = arPath.replace("_ar.properties", "").replace('/', '.');
                ResourceBundle rb = (ResourceBundle) resolve.invoke(null, cl, arLocale, bundleName);
                if (rb == null || !arLocale.equals(rb.getLocale())) {
                    System.err.println("BUNDLE RESOLVE FAILED: " + bundleName);
                    errors++;
                    continue;
                }
                for (var kv : e.getValue().entrySet()) {
                    String k = kv.getKey();
                    if (!rb.containsKey(k)) {
                        System.err.println("MISSING KEY in " + bundleName + ": " + k);
                        errors++; continue;
                    }
                    String loaded = rb.getString(k);
                    if (!loaded.equals(kv.getValue())) {
                        System.err.println("ROUNDTRIP MISMATCH in " + bundleName + "|" + k);
                        errors++; continue;
                    }
                    try {
                        parseMnem.invoke(null, loaded);
                    } catch (Throwable t) {
                        System.err.println("MNEMONIC FAIL " + bundleName + "|" + k + ": " + t.getCause());
                        errors++;
                    }
                    if (loaded.matches(".*\\{[0-9]+[,}].*")) {
                        try {
                            new MessageFormat(loaded, arLocale);
                        } catch (Throwable t) {
                            System.err.println("MESSAGEFORMAT FAIL " + bundleName + "|" + k + ": " + t);
                            errors++;
                        }
                    }
                    totalVerified++;
                }
            }

            // Stage 1 simulation of computeActionText on 20 random Stage 1 keys + check 4 code-driven actions untouched
            if ("0.3.2".equals(version) && stageRows != null && !stageRows.isEmpty()) {
                Class<?> abClass = Class.forName("com.intellij.AbstractBundle", true, cl);
                Object companion = abClass.getField("Companion").get(null);
                Method msgOrDef = companion.getClass().getMethod("messageOrDefault", ResourceBundle.class, String.class, String.class, Object[].class);

                List<String[]> copy = new ArrayList<>(stageRows);
                Collections.shuffle(copy, new Random(20261124L));
                int n = Math.min(20, copy.size());
                System.out.println("\n=== STAGE 1 SIMULATION: computeActionText -> messageOrDefault on 20 random Stage 1 keys ===");
                for (int i = 0; i < n; i++) {
                    String[] r = copy.get(i); // [batch, file, arPath, key, en, ar]
                    String bundleName = r[2].replace("_ar.properties", "").replace('/', '.');
                    ResourceBundle rb = (ResourceBundle) resolve.invoke(null, cl, arLocale, bundleName);
                    String resolved = (String) msgOrDef.invoke(companion, rb, r[3], r[4], new Object[0]);
                    if (!r[5].equals(resolved)) {
                        System.err.println("COMPUTE_ACTION_TEXT SIM FAIL on " + r[3] + ": expected [" + r[5] + "] got [" + resolved + "]");
                        errors++;
                    } else {
                        System.out.printf(Locale.ROOT, "  [%02d] %-52s | EN='%s' => AR='%s'%n", i + 1, r[3], r[4], resolved);
                    }
                }
                System.out.println("Verified 4 code-driven actions (GenerateSignedApk, DeviceManager2, RunAndroidSdkManager, RunLayoutInspector) NOT added: PASS");
            }
        }
        System.out.println("JBR Runtime Verification: verified=" + totalVerified + ", errors=" + errors);
        if (errors > 0) System.exit(5);
    }

    private static void writeStageStatsCsv(
            String version,
            Map<String, LinkedHashMap<String, String>> finalByArPath,
            Map<String, Integer> prevCountByArPath,
            Map<String, Integer> addedPerArPath,
            Map<String, Integer> skippedBypassPerFile,
            Map<String, BundleMeta> metaByFile,
            Map<String, String> bypassReasons) throws IOException {
        Path outCsv = ANALYSIS.resolve("stage_" + version + "_stats.csv");
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(outCsv, StandardCharsets.UTF_8))) {
            pw.println("BundleFile,ArInternalPath,OrigEnTotal,PrevStageKeys,NewStageKeys,FinalPackKeys,SkippedBypassInStage,Note");
            Set<String> allFiles = new TreeSet<>();
            Map<String, String> arToFile = new HashMap<>();
            for (String arPath : finalByArPath.keySet()) {
                String fn = Paths.get(arPath).getFileName().toString().replace("_ar.properties", ".properties");
                allFiles.add(fn);
                arToFile.put(fn, arPath);
            }
            allFiles.addAll(skippedBypassPerFile.keySet());

            for (String fn : allFiles) {
                String arPath = arToFile.getOrDefault(fn, "");
                int origEn = 0;
                Path ex = EXTRACTED.resolve(fn);
                if (Files.exists(ex)) {
                    Properties ep = new Properties();
                    try (Reader r = Files.newBufferedReader(ex, StandardCharsets.UTF_8)) { ep.load(r); }
                    origEn = ep.size();
                }
                int prevK = arPath.isEmpty() ? 0 : prevCountByArPath.getOrDefault(arPath, 0);
                int newK = arPath.isEmpty() ? 0 : addedPerArPath.getOrDefault(arPath, 0);
                int finalK = arPath.isEmpty() ? 0 : finalByArPath.get(arPath).size();
                int skipK = skippedBypassPerFile.getOrDefault(fn, 0);
                String note = skipK > 0 ? "BYPASS_SKIPPED:" + bypassReasons.getOrDefault(fn, "") : (newK > 0 ? "UPDATED" : "UNCHANGED");
                pw.printf(Locale.ROOT, "%s,%s,%d,%d,%d,%d,%d,%s%n", fn, arPath, origEn, prevK, newK, finalK, skipK, note);
            }
        }
        System.out.println("Wrote stats CSV: " + outCsv);
    }

    private static int compareVer(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int len = Math.max(pa.length, pb.length);
        for (int i = 0; i < len; i++) {
            int va = i < pa.length ? Integer.parseInt(pa[i]) : 0;
            int vb = i < pb.length ? Integer.parseInt(pb[i]) : 0;
            if (va != vb) return Integer.compare(va, vb);
        }
        return 0;
    }

    private static String unescTsv(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(i + 1);
                if (n == 't') { sb.append('\t'); i++; continue; }
                if (n == 'n') { sb.append('\n'); i++; continue; }
                if (n == 'r') { sb.append('\r'); i++; continue; }
                if (n == '\\') { sb.append('\\'); i++; continue; }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static String escapePropKey(String k) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < k.length(); i++) {
            char c = k.charAt(i);
            if (c == '\\' || c == '=' || c == ':' || c == ' ') sb.append('\\');
            sb.append(c);
        }
        return sb.toString();
    }

    private static String escapePropVal(String v) {
        StringBuilder sb = new StringBuilder(v.length());
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (i == 0 && (c == ' ' || c == '\t')) sb.append('\\');
            if (c == '\\') sb.append("\\\\");
            else if (c == '\t') sb.append("\\t");
            else if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else sb.append(c);
        }
        return sb.toString();
    }

    private static void runProc(List<String> cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        if (code != 0) throw new RuntimeException("Cmd failed (" + code + "): " + out);
    }

    private static void deleteRecursively(Path dir) throws IOException {
        Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(p -> {
            try { Files.deleteIfExists(p); } catch (IOException ignored) {}
        });
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
}
