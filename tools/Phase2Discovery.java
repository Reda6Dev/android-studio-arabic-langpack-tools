/**
 * Stage 1/Discovery tool for Android Studio resource bundles.
 * Inputs:  Android Studio installation (-Dstudio.home / ANDROID_STUDIO_HOME); optional base *_ar.properties under <work.dir>/base-0.2/messages/
 * Outputs: <work.dir>/discovery.csv, bundle_paths.tsv, reverse_lookup_34.csv, extracted/*.properties, batches/level*_in/batch_L*_*.tsv
 * أداة استخراج وتصنيف مفاتيح حزم النصوص من تثبيت Android Studio المحلي إلى مستويات الترجمة.
 *
 * Configuration properties (optional):
 *   -Dstudio.home=<path>   Path to Android Studio installation (or ANDROID_STUDIO_HOME env var)
 *   -Dwork.dir=<path>      Working directory for extracted/batches/discovery outputs (default: ".")
 */
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.regex.*;
import java.util.zip.ZipEntry;

public class Phase2Discovery {
    static final Path STUDIO_HOME = Paths.get(System.getProperty("studio.home", System.getenv("ANDROID_STUDIO_HOME") != null ? System.getenv("ANDROID_STUDIO_HOME") : "C:\\Program Files\\Android\\Android Studio"));
    static final Path PHASE2 = Paths.get(System.getProperty("work.dir", System.getenv("WORK_DIR") != null ? System.getenv("WORK_DIR") : "."));
    static final Path BASE_02_MSGS = PHASE2.resolve("base-0.2").resolve("messages");
    static final Path EXTRACTED = PHASE2.resolve("extracted");
    static final Path BATCHES = PHASE2.resolve("batches");

    static final List<String> TARGET_34 = Arrays.asList(
        "Open",
        "Profile or Debug APK",
        "Close Project",
        "Local History",
        "Show History",
        "Show Project History",
        "Recent Changes",
        "Put Label",
        "Sync Project with Gradle Files",
        "Refresh Linked C++ Projects",
        "Packages",
        "Project Files",
        "Production",
        "Tests",
        "Project Source Files",
        "Project Non-Source Files",
        "Open Files",
        "All Changed Files",
        "Changes",
        "Scratches and Consoles",
        "Inspect for Play Policy Insights",
        "Analyze Stack Trace or Thread Dump",
        "Implement Methods",
        "Move Statement Down",
        "Rearrange Code",
        "Checkout Tag or Revision",
        "Run Anything",
        "SDK Manager",
        "Keymap",
        "Show Siblings",
        "Quick Type Definition",
        "Recently Changed Files",
        "Increase Font Size in All Editors",
        "Bidi Text Base Direction"
    );

    static final Set<String> SETTINGS_BUNDLES = new HashSet<>(Arrays.asList(
        "OptionsBundle.properties", "ApplicationBundle.properties", "EditorConfigBundle.properties",
        "GrazieBundle.properties", "SettingsSyncBundle.properties", "CodeStyleBundle.properties",
        "KeyMapBundle.properties", "CopyrightBundle.properties", "FileTypesBundle.properties",
        "ConfigurationStoreBundle.properties", "CredentialStoreBundle.properties", "CodeVisionBundle.properties",
        "MinimapBundle.properties", "LanguageAndRegionBundle.properties", "BuiltInServerBundle.properties",
        "ConfigurationScriptBundle.properties", "JavaCompilerBundle.properties", "VMOptionsBundle.properties",
        "KotlinProjectConfigurationBundle.properties", "KotlinBaseCompilerConfigurationUiBundle.properties",
        "KotlinRunConfigurationsBundle.properties", "KotlinPreferencesBundle.properties",
        "KotlinFormatterMinimalBundle.properties", "KotlinStyleBundle.properties",
        "ClangFormatBundle.properties", "JavaOptionBundle.properties"
    ));

    static final List<String> GEMINI_ORDER = Arrays.asList(
        "StudioGeminiBundle.properties", "AgentsCoreBundle.properties", "McpHostBundle.properties",
        "OnboardingCommonBundle.properties", "ChangeReviewBundle.properties", "V2Bundle.properties",
        "IntellijPluginCoreMessageBundle.properties", "PredictionServicesBundle.properties",
        "JourneysBundle.properties", "RagIndexer.properties", "RemoteProviderBundle.properties",
        "LocalProviderBundle.properties", "GiasEnterpriseBundle.properties",
        "StudioBotV2UiContainerBundle.properties", "InlineCompletionBundle.properties",
        "MlCompletionBundle.properties", "KotlinMlCompletionBundle.properties",
        "SmlBundle.properties", "SmlIjBundle.properties"
    );
    static final Set<String> GEMINI_BUNDLES = new HashSet<>(GEMINI_ORDER);

    // Core UI_GENERAL bundles prioritized for Level 1 & Level 2/4 (excludes pure compiler/language AST bundles,
    // third-party noise, and internal feedback survey/deprecated bundles unless specifically needed).
    static final Set<String> CORE_UI_PRIORITY = new LinkedHashSet<>(Arrays.asList(
        // 0.2 Level A core files (remaining short UI keys!)
        "CommonBundle.properties", "ActionsBundle.properties", "IdeBundle.properties",
        "AndroidBundle.properties", "ExecutionBundle.properties", "GitBundle.properties", "VcsBundle.properties",
        // Essential IDE / Project / Local History / Tool Windows / Scopes
        "LocalHistoryBundle.properties", "ProjectConceptBundle.properties", "ProjectBundle.properties",
        "CoreBundle.properties", "CoreDeprecatedMessagesBundle.properties", "LangBundle.properties",
        "CodeInsightBundle.properties", "AnalysisBundle.properties", "UsageViewBundle.properties",
        "UIBundle.properties", "IdeCoreBundle.properties", "UtilUiBundle.properties", "UtilBundle.properties",
        "PlatformEditorBundle.properties", "EditorBundle.properties", "FindBundle.properties",
        "RefactoringBundle.properties", "BookmarkBundle.properties", "StructureViewBundle.properties",
        "ProblemsViewBundle.properties", "NavBarFrontendBundle.properties", "BuildBundle.properties",
        "DaemonBundle.properties", "IndexingBundle.properties", "BootstrapBundle.properties",
        "ProjectModelBundle.properties", "WorkspaceModelIdeBundle.properties",
        "searchEverywhereFrontendBundle.properties", "JewelIntUIBundle.properties",
        // VCS / Git / Diff / Merge
        "DiffBundle.properties", "VcsLogBundle.properties", "DvcsBundle.properties",
        "GitFrontendBundle.properties", "GitTerminalBundle.properties", "VcsFrontendBundle.properties",
        "ModalCommitBundle.properties", "CollaborationToolsBundle.properties",
        "GithubBundle.properties", "GitLabBundle.properties", "HgBundle.properties",
        // Debugger / Profiler / Execution / Terminal / ExternalSystem / Gradle
        "XDebuggerBundle.properties", "XDebuggerUiBundle.properties", "XDebuggerImplBundle.properties",
        "FrontendDebuggerImplBundle.properties", "TestRunnerBundle.properties", "SmRunnerBundle.properties",
        "ExternalSystemBundle.properties", "DependencyUpdaterBundle.properties",
        "GradleBundle.properties", "DeclarativeBundle.properties", "GradleDocumentationBundle.properties",
        "TerminalBundle.properties", "DataGridBundle.properties", "CoverageBundle.properties",
        // Android Studio specific UI
        "LogcatBundle.properties", "DeviceManagerBundle.properties", "DeviceFileExplorerBundle.properties",
        "AndroidAdbUiBundle.properties", "LayoutInspectorBundle.properties", "AppInspectionBundle.properties",
        "AppInspectorBundle.properties", "DatabaseInspectorBundle.properties", "NetworkInspectorBundle.properties",
        "BackgroundTaskInspectorBundle.properties", "StreamingBundle.properties", "AndroidRunBundle.properties",
        "AndroidExecutionBundle.properties", "AndroidTestBundle.properties", "AgpUpgradeBundle.properties",
        "ApkAnalyzerBundle.properties", "RenderingBundle.properties", "AndroidDesignerActionsBundle.properties",
        "SplittingTabsBundle.properties", "ComposePreviewBundle.properties", "PreviewBundle.properties",
        "WearBundle.properties", "GlancePreviewBundle.properties", "ComposeBundle.properties",
        "ComposeIdeBundle.properties", "WearWhsBundle.properties", "WearDwfBundle.properties",
        "AndroidWearPairingBundle.properties", "FirebaseBundle.properties", "GoogleLoginBundle.properties",
        "BackupBundle.properties", "SymbolPickerBundle.properties", "TemplatesBundle.properties",
        "SamplesBrowserBundle.properties", "DaggerBundle.properties", "AndroidNdkBundle.properties",
        // Settings / Tools / Search / Spelling / Formats that appear in menus & Settings pages
        "SpellCheckerBundle.properties", "MarkdownBundle.properties", "MarkdownImagesBundle.properties",
        "JsonBundle.properties", "YAMLBundle.properties", "TomlBundle.properties",
        "ImagesBundle.properties", "PropertiesBundle.properties", "RegExpBundle.properties",
        "IntelliLangBundle.properties", "JUnitBundle.properties", "TestngBundle.properties",
        "ToolsBundle.properties", "CloudsBundle.properties", "CoverageBundle.properties",
        "IdeaDecompilerBundle.properties", "MicroservicesBundle.properties", "TaskBundle.properties",
        "JavaUiBundle.properties", "JavaBundle.properties",
        // Specific scope/project view UI bundle
        "OCBundle.properties", "GDBBundle.properties", "LLDBBundle.properties", "CidrDebuggerBundle.properties",
        "GradleJpsBundle.properties"
    ));

    static final Set<String> SLUG_EXCLUDE_KEYS = new HashSet<>(Arrays.asList(
        "GitBundle.properties|gpg.jb.manual.link",
        "GitBundle.properties|gpg.pinentry.jb.manual.link"
    ));

    static class BundleEntry {
        String jarRel;
        String internalPath;
        String fileName;
        String category;
        String reason;
        int totalKeys;
        int already02;
        int excluded;
        int lvl1;
        int lvl2;
        int lvl3;
        int lvl4;
        boolean priorityInUi;
        Properties props = new Properties();
        Map<String, String> orderedProps = new LinkedHashMap<>();
    }

    static class LookupHit {
        String sourceType; // PROPERTIES, XML, CLASS_CONST
        String location;
        String keyOrTag;
        String rawText;
    }

    public static void main(String[] args) throws Exception {
        for (String a : args) { if ("-h".equals(a) || "--help".equals(a)) { System.out.println("Usage: Phase2Discovery  (-Dstudio.home=... -Dwork.dir=...)"); return; } }
        Files.createDirectories(EXTRACTED);
        Files.createDirectories(BATCHES);

        // 1. Load already translated keys from phase2/base-0.2/messages/*_ar.properties
        Map<String, Set<String>> base02Keys = new HashMap<>();
        int total02 = 0;
        if (Files.exists(BASE_02_MSGS)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(BASE_02_MSGS, "*_ar.properties")) {
                for (Path p : ds) {
                    String origName = p.getFileName().toString().replace("_ar.properties", ".properties");
                    Set<String> keys = loadPropKeys(p);
                    base02Keys.put(origName, keys);
                    total02 += keys.size();
                }
            }
        }
        System.out.println("Loaded base-0.2 keys: " + total02 + " across " + base02Keys.size() + " files.");

        // 2. Scan all JARs under STUDIO_HOME/lib and STUDIO_HOME/plugins
        List<Path> jars = new ArrayList<>();
        for (String sub : Arrays.asList("lib", "plugins")) {
            Path root = STUDIO_HOME.resolve(sub);
            Files.walk(root).filter(p -> p.toString().toLowerCase().endsWith(".jar")).forEach(jars::add);
        }
        jars.sort(Comparator.comparing(Path::toString));
        System.out.println("Scanning " + jars.size() + " JAR files...");

        Map<String, List<BundleEntry>> byFileName = new LinkedHashMap<>();
        Map<String, List<LookupHit>> targetHits = new LinkedHashMap<>();
        for (String t : TARGET_34) targetHits.put(t, new ArrayList<>());

        // Prepare lower-case exact patterns for fast scan across XML and CLASS constant pools
        Map<String, String> lowerTargetMap = new LinkedHashMap<>();
        for (String t : TARGET_34) lowerTargetMap.put(t.toLowerCase(Locale.ROOT), t);

        for (Path jarPath : jars) {
            String jarRel = STUDIO_HOME.relativize(jarPath).toString().replace('\\', '/');
            try (JarFile jf = new JarFile(jarPath.toFile())) {
                Enumeration<JarEntry> entries = jf.entries();
                while (entries.hasMoreElements()) {
                    JarEntry je = entries.nextElement();
                    if (je.isDirectory()) continue;
                    String name = je.getName();
                    String lowerName = name.toLowerCase(Locale.ROOT);

                    if (lowerName.endsWith(".properties") && (name.contains("Bundle") || name.startsWith("messages/") || name.contains("/messages/"))) {
                        byte[] bytes = jf.getInputStream(je).readAllBytes();
                        BundleEntry be = new BundleEntry();
                        be.jarRel = jarRel;
                        be.internalPath = name;
                        be.fileName = Paths.get(name).getFileName().toString();
                        loadOrderedProperties(new ByteArrayInputStream(bytes), be.orderedProps, be.props);
                        be.totalKeys = be.orderedProps.size();
                        classifyBundle(be);
                        byFileName.computeIfAbsent(be.fileName, k -> new ArrayList<>()).add(be);

                        // Reverse lookup inside properties
                        for (Map.Entry<String, String> pe : be.orderedProps.entrySet()) {
                            String rawVal = pe.getValue();
                            String norm = normalizeUiText(rawVal);
                            String normLower = norm.toLowerCase(Locale.ROOT);
                            for (String t : TARGET_34) {
                                if (normLower.equals(t.toLowerCase(Locale.ROOT))) {
                                    LookupHit h = new LookupHit();
                                    h.sourceType = "PROPERTIES";
                                    h.location = jarRel + "!" + name;
                                    h.keyOrTag = pe.getKey();
                                    h.rawText = rawVal;
                                    targetHits.get(t).add(h);
                                }
                            }
                        }
                    } else if (lowerName.endsWith(".xml") && (name.startsWith("META-INF/") || name.contains("action") || name.contains("plugin"))) {
                        String xml = new String(jf.getInputStream(je).readAllBytes(), StandardCharsets.UTF_8);
                        for (String t : TARGET_34) {
                            if (xml.contains(t)) {
                                for (String line : xml.split("\r?\n")) {
                                    if (line.contains(t)) {
                                        LookupHit h = new LookupHit();
                                        h.sourceType = "XML";
                                        h.location = jarRel + "!" + name;
                                        h.keyOrTag = extractXmlContext(line);
                                        h.rawText = line.trim();
                                        targetHits.get(t).add(h);
                                    }
                                }
                            }
                        }
                    } else if (lowerName.endsWith(".class") && shouldScanClassFor34(jarRel, name)) {
                        byte[] bytes = jf.getInputStream(je).readAllBytes();
                        scanClassConstantPool(bytes, jarRel + "!" + name, targetHits);
                    }
                }
            } catch (Exception ex) {
                System.err.println("Warn reading " + jarRel + ": " + ex.getMessage());
            }
        }

        // 3. Deduplicate / pick canonical BundleEntry per fileName (prefer lib/ or plugin impl over kotlinc/kotlin-compiler.jar)
        Map<String, BundleEntry> canonical = new LinkedHashMap<>();
        List<BundleEntry> allBundles = new ArrayList<>();
        for (Map.Entry<String, List<BundleEntry>> e : byFileName.entrySet()) {
            List<BundleEntry> list = e.getValue();
            allBundles.addAll(list);
            list.sort(Comparator.comparingInt(Phase2Discovery::jarPriority));
            BundleEntry primary = list.get(0);
            canonical.put(e.getKey(), primary);
        }

        // Write bundle_paths.tsv and save canonical extracted .properties into phase2/extracted/
        List<String> pathRows = new ArrayList<>();
        pathRows.add("FileName\tInternalPath\tJarRelPath\tCategory\tPriorityInUI\tTotalKeys");
        for (BundleEntry be : canonical.values()) {
            if (!"TECHNICAL".equals(be.category) && !"INSPECTIONS".equals(be.category)) {
                // Save clean UTF-8 extracted file for validator & assembly
                writeCanonicalExtracted(be, jarPathFor(be));
            }
            pathRows.add(be.fileName + "\t" + be.internalPath + "\t" + be.jarRel + "\t" + be.category + "\t" + be.priorityInUi + "\t" + be.totalKeys);
        }
        Files.write(PHASE2.resolve("bundle_paths.tsv"), pathRows, StandardCharsets.UTF_8);

        // 4. Evaluate every key of every canonical bundle & prepare level batches
        List<String[]> lvl1Rows = new ArrayList<>();
        List<String[]> lvl2Rows = new ArrayList<>();
        List<String[]> lvl3CoreRows = new ArrayList<>();
        List<String[]> lvl3SmlRows = new ArrayList<>();
        List<String[]> lvl4Rows = new ArrayList<>();

        for (BundleEntry be : canonical.values()) {
            Set<String> already = base02Keys.getOrDefault(be.fileName, Collections.emptySet());
            for (Map.Entry<String, String> pe : be.orderedProps.entrySet()) {
                String k = pe.getKey();
                String v = pe.getValue();
                if (already.contains(k)) {
                    be.already02++;
                    continue;
                }
                if (SLUG_EXCLUDE_KEYS.contains(be.fileName + "|" + k) || isAlwaysExcluded(be, k, v)) {
                    be.excluded++;
                    continue;
                }

                int wc = countWords(v);
                if ("GEMINI_AI".equals(be.category)) {
                    if (wc <= 40) {
                        be.lvl3++;
                        if (be.fileName.startsWith("Sml")) {
                            lvl3SmlRows.add(new String[]{be.fileName, k, escTsv(v), "L3_SML"});
                        } else {
                            lvl3CoreRows.add(new String[]{be.fileName, k, escTsv(v), "L3_GEMINI"});
                        }
                    } else {
                        be.excluded++;
                    }
                    continue;
                }

                if ("UI_GENERAL".equals(be.category) || "SETTINGS".equals(be.category)) {
                    // For mixed Java/C++ bundles, only translate genuine UI/Menu/Dialog/Scope controls (avoid compiler/PSI noise)
                    if (("JavaBundle.properties".equals(be.fileName) || "OCBundle.properties".equals(be.fileName))
                        && !isExplicitUiControlKey(k)) {
                        be.excluded++;
                        continue;
                    }
                    String kind = classifyKeyLevel(be, k, v, wc);
                    if ("L1".equals(kind)) {
                        be.lvl1++;
                        lvl1Rows.add(new String[]{be.fileName, k, escTsv(v), level1PriorityRank(be)});
                    } else if ("L2".equals(kind)) {
                        be.lvl2++;
                        lvl2Rows.add(new String[]{be.fileName, k, escTsv(v), level1PriorityRank(be)});
                    } else if ("L4".equals(kind)) {
                        be.lvl4++;
                        lvl4Rows.add(new String[]{be.fileName, k, escTsv(v), level1PriorityRank(be)});
                    } else {
                        be.excluded++;
                    }
                } else if ("PROG_LANG".equals(be.category) && "KotlinBundle.properties".equals(be.fileName) && isExplicitKotlinUiControlKey(k)) {
                    int wcKotlin = countWords(v);
                    if (wcKotlin <= 15 && !k.toLowerCase(Locale.ROOT).endsWith(".description") && !k.toLowerCase(Locale.ROOT).endsWith(".tooltip")) {
                        be.lvl1++;
                        lvl1Rows.add(new String[]{be.fileName, k, escTsv(v), "03_KOTLIN_UI"});
                    } else {
                        be.excluded++;
                    }
                } else {
                    be.excluded++;
                }
            }
        }

        // Order batches cleanly: P1 first (core UI/Settings), then P2
        lvl1Rows.sort(Comparator.comparing((String[] r) -> r[3]).thenComparing(r -> r[0]).thenComparing(r -> r[1]));
        lvl2Rows.sort(Comparator.comparing((String[] r) -> r[3]).thenComparing(r -> r[0]).thenComparing(r -> r[1]));
        lvl4Rows.sort(Comparator.comparing((String[] r) -> r[3]).thenComparing(r -> r[0]).thenComparing(r -> r[1]));

        // Gemini priority order as listed in plan, then SmlBundle & SmlIjBundle
        Map<String, Integer> gemRank = new HashMap<>();
        for (int i = 0; i < GEMINI_ORDER.size(); i++) gemRank.put(GEMINI_ORDER.get(i), i);
        List<String[]> lvl3Rows = new ArrayList<>();
        lvl3CoreRows.sort(Comparator.comparingInt((String[] r) -> gemRank.getOrDefault(r[0], 999)).thenComparing(r -> r[1]));
        lvl3SmlRows.sort(Comparator.comparingInt((String[] r) -> gemRank.getOrDefault(r[0], 999)).thenComparing(r -> r[1]));
        lvl3Rows.addAll(lvl3CoreRows);
        lvl3Rows.addAll(lvl3SmlRows);

        writeBatches(BATCHES.resolve("level1_in"), "batch_L1_", lvl1Rows, 125);
        writeBatches(BATCHES.resolve("level2_in"), "batch_L2_", lvl2Rows, 110);
        writeBatches(BATCHES.resolve("level3_in"), "batch_L3_", lvl3Rows, 110);
        writeBatches(BATCHES.resolve("level4_in"), "batch_L4_", lvl4Rows, 110);

        // 5. Write discovery.csv (Section 1: All bundles classification + Section 2: Reverse lookup of 34 strings)
        List<String> discCsv = new ArrayList<>();
        discCsv.add("FileName,Category,PriorityUI,JarRelativePath,InternalPath,TotalKeys,Already02,Level1_03,Level2_04,Level3_05,Level4_06,Excluded,Notes");
        List<BundleEntry> sortedCanon = new ArrayList<>(canonical.values());
        sortedCanon.sort(Comparator.comparing((BundleEntry b) -> b.category)
            .thenComparing((BundleEntry b) -> !b.priorityInUi)
            .thenComparingInt((BundleEntry b) -> -(b.lvl1 + b.lvl2 + b.lvl3 + b.lvl4)));

        Map<String, int[]> catTotals = new LinkedHashMap<>();
        for (BundleEntry b : sortedCanon) {
            discCsv.add(csvRow(b.fileName, b.category, b.priorityInUi ? "CORE" : "STD", b.jarRel, b.internalPath,
                String.valueOf(b.totalKeys), String.valueOf(b.already02),
                String.valueOf(b.lvl1), String.valueOf(b.lvl2), String.valueOf(b.lvl3), String.valueOf(b.lvl4),
                String.valueOf(b.excluded), b.reason));
            int[] ct = catTotals.computeIfAbsent(b.category, k -> new int[7]);
            ct[0]++;
            ct[1] += b.totalKeys;
            ct[2] += b.already02;
            ct[3] += b.lvl1;
            ct[4] += b.lvl2;
            ct[5] += b.lvl3;
            ct[6] += b.lvl4;
        }
        Files.write(PHASE2.resolve("discovery.csv"), discCsv, StandardCharsets.UTF_8);

        // Write reverse_lookup_34.csv
        List<String> rlCsv = new ArrayList<>();
        rlCsv.add("TargetString,Status,InBase02,SourceType,Location,KeyOrTag,RawText,TranslatableViaLanguagePack");
        for (String t : TARGET_34) {
            List<LookupHit> hits = targetHits.get(t);
            if (hits.isEmpty()) {
                rlCsv.add(csvRow(t, "NOT_FOUND", "NO", "NONE", "", "", "", "CHECK"));
                continue;
            }
            // Prefer PROPERTIES hit first for status summary, log all distinct hits
            boolean hasProp = hits.stream().anyMatch(h -> "PROPERTIES".equals(h.sourceType));
            for (LookupHit h : hits) {
                String bundleName = h.location.contains("!") ? Paths.get(h.location.substring(h.location.indexOf('!') + 1)).getFileName().toString() : "";
                boolean in02 = "PROPERTIES".equals(h.sourceType) && base02Keys.getOrDefault(bundleName, Collections.emptySet()).contains(h.keyOrTag);
                String translatable = "PROPERTIES".equals(h.sourceType) ? "YES" : (hasProp ? "YES_ALSO_IN_PROPS" : "NO_HARDCODED");
                String status = "PROPERTIES".equals(h.sourceType) ? (in02 ? "IN_0.2" : "TRANSLATABLE_TARGET_LEVEL1") : h.sourceType;
                rlCsv.add(csvRow(t, status, in02 ? "YES" : "NO", h.sourceType, h.location, h.keyOrTag, h.rawText, translatable));
            }
        }
        Files.write(PHASE2.resolve("reverse_lookup_34.csv"), rlCsv, StandardCharsets.UTF_8);

        System.out.println("\n=== SUMMARY BY CATEGORY ===");
        for (Map.Entry<String, int[]> e : catTotals.entrySet()) {
            int[] c = e.getValue();
            System.out.printf(Locale.ROOT, "%-14s | files=%3d | total=%5d | base02=%4d | L1=%4d | L2=%4d | L3=%4d | L4=%4d%n",
                e.getKey(), c[0], c[1], c[2], c[3], c[4], c[5], c[6]);
        }
        long p1L1 = lvl1Rows.stream().filter(r -> !r[3].startsWith("09_")).count();
        long p2L1 = lvl1Rows.size() - p1L1;
        System.out.println("\nBatch counts created:");
        System.out.println("  Level 1 total=" + lvl1Rows.size() + " (CoreUI/Settings=" + p1L1 + ", Secondary UI=" + p2L1 + ")");
        System.out.println("  Level 2 total=" + lvl2Rows.size());
        System.out.println("  Level 3 total=" + lvl3Rows.size() + " (CoreGemini=" + lvl3CoreRows.size() + ", SML=" + lvl3SmlRows.size() + ")");
        System.out.println("  Level 4 total=" + lvl4Rows.size());
    }

    static String level1PriorityRank(BundleEntry be) {
        if ("SETTINGS".equals(be.category)) return "01_SETTINGS";
        if (be.fileName.equals("CommonBundle.properties") || be.fileName.equals("ActionsBundle.properties")
            || be.fileName.equals("IdeBundle.properties") || be.fileName.equals("AndroidBundle.properties")
            || be.fileName.equals("ExecutionBundle.properties") || be.fileName.equals("GitBundle.properties")
            || be.fileName.equals("VcsBundle.properties")) return "00_BASE02_REM";
        if (be.fileName.equals("LocalHistoryBundle.properties") || be.fileName.equals("ProjectConceptBundle.properties")
            || be.fileName.equals("ProjectBundle.properties") || be.fileName.equals("CoreBundle.properties")
            || be.fileName.equals("CoreDeprecatedMessagesBundle.properties") || be.fileName.equals("OCBundle.properties")
            || be.fileName.equals("AnalysisBundle.properties") || be.fileName.equals("UsageViewBundle.properties")
            || be.fileName.equals("LogcatBundle.properties") || be.fileName.equals("DeviceManagerBundle.properties")
            || be.fileName.equals("GradleBundle.properties") || be.fileName.equals("TerminalBundle.properties")
            || be.fileName.equals("XDebuggerBundle.properties") || be.fileName.equals("FindBundle.properties")
            || be.fileName.equals("EditorBundle.properties") || be.fileName.equals("DiffBundle.properties")
            || be.fileName.equals("UIBundle.properties") || be.fileName.equals("VcsLogBundle.properties")
            || be.fileName.equals("CodeInsightBundle.properties") || be.fileName.equals("LangBundle.properties")
            || be.fileName.equals("RefactoringBundle.properties") || be.fileName.equals("BookmarkBundle.properties")
            || be.fileName.equals("ProblemsViewBundle.properties") || be.fileName.equals("StructureViewBundle.properties")
            || be.fileName.equals("BuildBundle.properties") || be.fileName.equals("DvcsBundle.properties")
            || be.fileName.equals("GitFrontendBundle.properties") || be.fileName.equals("GitTerminalBundle.properties")
            || be.fileName.equals("JavaUiBundle.properties") || be.fileName.equals("JavaBundle.properties")) return "02_CORE_IDE";
        if (be.priorityInUi) return "03_CORE_EXTRA";
        return "09_SECONDARY";
    }

    static boolean isExplicitUiControlKey(String k) {
        String lk = k.toLowerCase(Locale.ROOT);
        return lk.startsWith("action.") || lk.startsWith("group.") || lk.startsWith("title.")
            || lk.startsWith("button.") || lk.startsWith("checkbox.") || lk.startsWith("radio.")
            || lk.startsWith("label.") || lk.startsWith("tab.") || lk.startsWith("toolwindow.")
            || lk.startsWith("configurable.") || lk.startsWith("settings.") || lk.startsWith("options.")
            || lk.startsWith("search.scope.") || lk.startsWith("scope.") || lk.startsWith("select.")
            || lk.startsWith("dialog.") || lk.startsWith("popup.") || lk.startsWith("column.")
            || lk.startsWith("menu.") || lk.startsWith("separator.") || lk.startsWith("status.")
            || lk.startsWith("android.")
            || lk.endsWith(".text") || lk.endsWith(".title") || lk.endsWith(".label")
            || lk.endsWith(".name") || lk.endsWith(".checkbox") || lk.endsWith(".tab")
            || lk.endsWith(".tooltip") || lk.endsWith(".tooltiptext") || lk.endsWith(".description");
    }

    static boolean isExplicitKotlinUiControlKey(String k) {
        String lk = k.toLowerCase(Locale.ROOT);
        if (lk.startsWith("fix.") || lk.startsWith("inspection.")) return false;
        return lk.startsWith("action.") || lk.startsWith("title.") || lk.startsWith("button.")
            || lk.startsWith("checkbox.") || lk.startsWith("radio.") || lk.startsWith("label.")
            || lk.startsWith("tab.") || lk.startsWith("toolwindow.") || lk.startsWith("configurable.")
            || lk.startsWith("settings.") || lk.startsWith("options.") || lk.startsWith("dialog.")
            || lk.startsWith("search.scope.") || lk.startsWith("wizard.");
    }

    static int jarPriority(BundleEntry b) {
        if (b.jarRel.startsWith("lib/intellij.platform.ide")) return 0;
        if (b.jarRel.startsWith("lib/")) return 1;
        if (b.jarRel.contains("kotlinc/lib/kotlin-compiler.jar")) return 99;
        if (b.jarRel.startsWith("plugins/android/")) return 2;
        if (b.jarRel.startsWith("plugins/java/")) return 3;
        return 10;
    }

    static void classifyBundle(BundleEntry b) {
        String f = b.fileName;
        String lowF = f.toLowerCase(Locale.ROOT);
        String lowPath = b.internalPath.toLowerCase(Locale.ROOT);

        // 1. Technical / localized / non-platform internals
        if (lowF.matches(".*_(ja|ko|zh_cn|en)\\.properties$")
            || !lowPath.startsWith("messages/") && !lowF.endsWith("bundle.properties")
            || lowPath.contains("org/languagetool/")
            || lowPath.contains("org/apache/maven/")
            || lowPath.contains("lucene")
            || f.equals("AgreementsBundle.properties")
            || f.endsWith("FeedbackMessagesBundle.properties")
            || f.endsWith("FeedbackBundle.properties")
            || f.equals("FeedbackBundle.properties")
            || f.equals("SmoothCaretFeedbackBundle.properties")
            || f.equals("KotlinDebuggerFeedbackSurveyBundle.properties")
            || f.equals("StatisticsBundle.properties")
            || f.equals("FeatureStatisticsBundle.properties")
            || f.equals("FeaturePromoBundle.properties")
            || f.equals("VcsProductivityFeatures.properties")
            || f.equals("ProcessHandshakeBundle.properties")
            || f.equals("RemoteDevUtilBundle.properties")
            || f.equals("IjentBundle.properties")
            || f.equals("EelImplBundle.properties")
            || f.equals("RuntimeBundle.properties")
            || f.equals("PrattBundle.properties")
            || f.equals("PluginFreezeBundle.properties")
            || f.equals("JavaLessonsBundle.properties")
            || f.equals("KotlinLessonsBundle.properties")
            || f.equals("JetBrainsAcademyWelcomeScreenBundle.properties")
            || f.equals("InternalActionsBundle.properties")
            || f.equals("PerformanceTestingBundle.properties")
            || f.equals("DevPsiViewerBundle.properties")
            || f.equals("IdeDeprecatedMessagesBundle.properties")
            || f.equals("ActionsDeprecatedMessagesBundle.properties")
            || f.equals("GitLabDeprecatedMessagesBundle.properties")
            || f.equals("GradleDeprecatedMessagesBundle.properties")
            || f.equals("TerminalDeprecatedMessagesBundle.properties")
            || f.equals("GroovyDeprecatedMessagesBundle.properties")
            || f.equals("KotlinJvmDecompilerDeprecatedMessagesBundle.properties")
            || f.equals("HgBundle.properties")
            || f.equals("UrlAssistBundle.properties")
            || f.equals("DataGridBundle.properties")
            || b.totalKeys == 0) {
            b.category = "TECHNICAL";
            b.reason = "Localized/3rdParty/Feedback/Deprecated/Internal";
            return;
        }

        // 2. Gemini / AI
        if (GEMINI_BUNDLES.contains(f) || b.jarRel.contains("plugins/gemini/") || lowF.contains("completion") && lowF.contains("ml")) {
            b.category = "GEMINI_AI";
            b.priorityInUi = !f.startsWith("Sml");
            b.reason = b.priorityInUi ? "Gemini/Agent/AI UI" : "Gemini SML Secondary";
            return;
        }

        // 3. Inspections (always excluded)
        if (lowF.contains("inspection") || f.equals("QuickFixBundle.properties") || f.equals("CommonQuickFixBundle.properties")
            || f.equals("JvmAnalysisBundle.properties") || f.equals("AndroidLintBundle.properties") || f.equals("LintBundle.properties")) {
            b.category = "INSPECTIONS";
            b.reason = "Code inspection / lint messages (excluded)";
            return;
        }

        // 4. Settings (Group B: all 26 non-duplicated files, incl. Java/Kotlin/ClangFormat settings)
        if (SETTINGS_BUNDLES.contains(f)) {
            b.category = "SETTINGS";
            b.priorityInUi = true;
            b.reason = "IDE/Language Settings";
            return;
        }

        // 5. Core UI General explicitly prioritized
        if (CORE_UI_PRIORITY.contains(f)) {
            b.category = "UI_GENERAL";
            b.priorityInUi = true;
            b.reason = "Core IDE / Android Studio / VCS UI";
            return;
        }

        // 6. Programming-language compiler / PSI / parser / highlighting / code-generation internals
        if (lowF.startsWith("javaerror") || lowF.startsWith("javacompilationerror") || lowF.startsWith("javapsi")
            || lowF.startsWith("javasyntax") || lowF.startsWith("javaanalysis") || lowF.startsWith("javarefactoring")
            || lowF.startsWith("javadebugger") || lowF.startsWith("javai18n") || lowF.startsWith("javafrontback")
            || lowF.startsWith("javastarters") || lowF.startsWith("javaterminal") || lowF.startsWith("javadev")
            || lowF.startsWith("javaindexing") || lowF.startsWith("javacoverage") || lowF.startsWith("refactorj")
            || lowF.startsWith("typemigration") || lowF.startsWith("manifest") || lowF.startsWith("designer")
            || lowF.startsWith("kotlin") || lowF.startsWith("k2")
            || lowF.startsWith("groovy")
            || lowF.startsWith("oc") || lowF.startsWith("cidr") || lowF.startsWith("clangd") || lowF.startsWith("cmake")
            || lowF.startsWith("ctesting") || lowF.startsWith("modulemap") || lowF.startsWith("dx")
            || lowF.startsWith("shbundle") || lowF.startsWith("xml") || lowF.startsWith("relaxng") || lowF.startsWith("mdn")
            || lowF.startsWith("htmltools") || lowF.startsWith("ssr") || lowF.startsWith("dfa") || lowF.startsWith("poly")
            || lowF.startsWith("completionbundle") || lowF.startsWith("typofixing")
            || lowF.startsWith("intension") || lowF.startsWith("intention")) {
            b.category = "PROG_LANG";
            b.reason = "Language AST / Compiler / Refactoring internal";
            return;
        }

        // 7. Remaining general UI / platform / plugin bundles
        b.category = "UI_GENERAL";
        b.priorityInUi = b.jarRel.startsWith("lib/") || b.jarRel.startsWith("plugins/android");
        b.reason = "General UI / Plugin bundle";
    }

    static boolean isAlwaysExcluded(BundleEntry b, String k, String v) {
        if (v == null || v.trim().isEmpty()) return true;
        String t = v.trim();
        String lowK = k.toLowerCase(Locale.ROOT);

        // Technical property keys
        if (lowK.startsWith("icon.") || lowK.endsWith(".icon") || lowK.contains(".mnemonic") || lowK.endsWith(".url")
            || lowK.endsWith(".link") && t.matches("^[A-Za-z0-9_./:-]+$") && !t.contains(" ")
            || lowK.startsWith("url.") || lowK.startsWith("help.id") || lowK.startsWith("inspection.")
            || lowK.endsWith(".id") && !t.contains(" ")) {
            return true;
        }

        // Symbols / placeholders / numbers only
        String stripped = t.replaceAll("\\{[0-9]+[^}]*\\}", "").replaceAll("<[^>]+>", "").replaceAll("[^A-Za-z]", "");
        if (stripped.isEmpty()) return true;

        // Pure URL / path / regex / CLI flags / snake_case / camelCase single-token identifier (EXCEPT known single UI words)
        if (t.matches("^https?://\\S+$") || t.matches("^([A-Za-z]:)?[\\\\/][\\w\\\\/.-]+$")) return true;
        if (t.matches("^[A-Za-z0-9]+(_[A-Za-z0-9]+){2,}$")) return true; // help anchor slugs like Foo_Bar_Baz
        if (t.startsWith("--") && !t.contains(" ")) return true;
        if (t.equals("TBD")) return true;
        if ("OptionsBundle.properties".equals(b.fileName) && k.startsWith("codestyle.property.description.")) {
            // In v0.2 these were codestyle property descriptions; for Level 2 we can allow <=40 words or exclude; actually let's include short ones in L2!
            return false;
        }

        // Exception / internal programming stack trace patterns
        String lowV = t.toLowerCase(Locale.ROOT);
        if (lowV.contains("exception") || lowV.contains("stacktrace") || lowV.contains("stack trace error")
            || lowV.contains("nullpointer") || lowV.contains("assertion") || lowK.endsWith(".exception")
            || lowK.contains("stacktrace.error") || lowK.startsWith("error.") && t.contains("Exception")) {
            return true;
        }
        return false;
    }

    static String classifyKeyLevel(BundleEntry b, String k, String v, int wc) {
        String lowK = k.toLowerCase(Locale.ROOT);
        String lowV = v.trim().toLowerCase(Locale.ROOT);

        // Tooltip / description / hint / comment -> Level 2 (<= 40 words)
        boolean isDescKey = lowK.endsWith(".description") || lowK.endsWith(".tooltip") || lowK.endsWith(".tooltiptext")
            || lowK.endsWith(".hint") || lowK.endsWith(".comment") || lowK.endsWith(".subtitle") || lowK.endsWith(".help")
            || lowK.contains(".description.") || lowK.contains(".tooltip.") || lowK.contains("tooltip")
            || lowK.startsWith("codestyle.property.description.")
            || lowK.startsWith("tooltip.") || lowK.startsWith("description.");
        if (isDescKey) {
            return wc <= 40 ? "L2" : "EXCL";
        }

        // Error / warning / exception strings -> excluded in Level 1/2; non-programming dialogs/warnings go to Level 4
        boolean isMessageKey = lowK.endsWith(".message") || lowK.endsWith(".text.confirm") || lowK.contains("confirm")
            || lowK.contains("notification") || lowK.contains("balloon") || lowK.contains("banner")
            || lowK.contains(".warning") || lowK.startsWith("warning.") || lowK.startsWith("progress.")
            || lowK.contains("dialog.message") || lowK.startsWith("message.") || lowK.endsWith(".status")
            || lowK.startsWith("status.") && lowV.contains("...") || lowK.startsWith("status.") && lowV.contains("…");
        boolean isErrorKey = lowK.endsWith(".error") || lowK.startsWith("error.") || lowK.contains(".error.")
            || lowV.contains("failed to") || lowV.contains("cannot ") || lowV.contains("unable to")
            || lowV.contains("invalid ") || lowV.contains("could not");

        if (isErrorKey) {
            // Programming/system error messages kept intentionally untranslated unless it's a benign user dialog prompt?
            // Plan: "مع استبقاء رسائل أخطاء البرمجة والاستثناءات بالإنجليزية عمداً" -> exclude technical error strings.
            if (isTechnicalError(lowK, lowV)) return "EXCL";
            return wc <= 40 ? "L4" : "EXCL";
        }

        if (isMessageKey || v.contains("?") || v.contains("<html>") || wc > 15) {
            // Questions / HTML notices / messages / status > 15 words -> Level 4 (up to 40 words),
            // UNLESS it's a Settings radio/checkbox <= 15 words even with <html> or ?.
            boolean isDirectUiControl = lowK.startsWith("action.") || lowK.startsWith("group.") || lowK.startsWith("button.")
                || lowK.startsWith("checkbox.") || lowK.startsWith("radio.") || lowK.startsWith("tab.")
                || lowK.startsWith("label.") || lowK.startsWith("menu.") || lowK.startsWith("column.")
                || lowK.startsWith("title.") || lowK.startsWith("configurable.") || lowK.startsWith("settings.")
                || lowK.startsWith("options.") || lowK.endsWith(".text") || lowK.endsWith(".title")
                || lowK.endsWith(".label") || lowK.endsWith(".name") || lowK.endsWith(".checkbox");
            if (isDirectUiControl && wc <= 15 && !isMessageKey) return "L1";
            return wc <= 40 ? "L4" : "EXCL";
        }

        // Everything else in UI_GENERAL / SETTINGS <= 15 words -> Level 1!
        return wc <= 15 ? "L1" : (wc <= 40 ? "L4" : "EXCL");
    }

    static boolean isTechnicalError(String lowK, String lowV) {
        return lowV.contains("exception") || lowV.contains("syntax") || lowV.contains("parse")
            || lowV.contains("token") || lowV.contains("expected") || lowV.contains("unexpected")
            || lowV.contains("io error") || lowV.contains("socket") || lowV.contains("exit code")
            || lowV.contains("protocol") || lowV.contains("bytecode") || lowV.contains("jvm")
            || lowK.startsWith("error.parse") || lowK.startsWith("error.internal");
    }

    static boolean shouldScanClassFor34(String jarRel, String entryName) {
        return jarRel.contains("android") || jarRel.contains("gradle") || jarRel.contains("platform.ide")
            || jarRel.contains("platform.lvcs") || jarRel.contains("vcs") || jarRel.contains("c.core");
    }

    static void scanClassConstantPool(byte[] data, String loc, Map<String, List<LookupHit>> targetHits) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            if (in.readInt() != 0xCAFEBABE) return;
            in.readUnsignedShort(); // minor
            in.readUnsignedShort(); // major
            int cpCount = in.readUnsignedShort();
            for (int i = 1; i < cpCount; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1: { // Utf8
                        String s = in.readUTF();
                        if (s.length() >= 4 && s.length() <= 120) {
                            String norm = normalizeUiText(s);
                            for (String t : TARGET_34) {
                                if (norm.equalsIgnoreCase(t)) {
                                    LookupHit h = new LookupHit();
                                    h.sourceType = "CLASS_CONST";
                                    h.location = loc;
                                    h.keyOrTag = "UTF8_CONST";
                                    h.rawText = s;
                                    targetHits.get(t).add(h);
                                }
                            }
                        }
                        break;
                    }
                    case 7: case 8: case 16: case 19: case 20:
                        in.skipBytes(2); break;
                    case 15:
                        in.skipBytes(3); break;
                    case 3: case 4: case 9: case 10: case 11: case 12: case 17: case 18:
                        in.skipBytes(4); break;
                    case 5: case 6:
                        in.skipBytes(8); i++; break;
                    default:
                        return;
                }
            }
        } catch (Exception ignored) {}
    }

    static String normalizeUiText(String s) {
        if (s == null) return "";
        String r = s.trim();
        r = r.replaceAll("[&_]([A-Za-z0-9])", "$1");
        r = r.replace("...", "").replace("\u2026", "").trim();
        return r;
    }

    static String extractXmlContext(String line) {
        Matcher m = Pattern.compile("id\\s*=\\s*\"([^\"]+)\"").matcher(line);
        return m.find() ? "id=" + m.group(1) : "xml-line";
    }

    static int countWords(String s) {
        String clean = s.replaceAll("<[^>]+>", " ").replaceAll("\\{[0-9]+[^}]*\\}", "X").trim();
        if (clean.isEmpty()) return 0;
        return clean.split("\\s+").length;
    }

    static Path jarPathFor(BundleEntry be) {
        return STUDIO_HOME.resolve(be.jarRel.replace('/', File.separatorChar));
    }

    static void writeCanonicalExtracted(BundleEntry be, Path jarPath) throws IOException {
        try (JarFile jf = new JarFile(jarPath.toFile())) {
            ZipEntry ze = jf.getEntry(be.internalPath);
            if (ze != null) {
                byte[] bytes = jf.getInputStream(ze).readAllBytes();
                Files.write(EXTRACTED.resolve(be.fileName), bytes);
            }
        }
    }

    static void writeBatches(Path dir, String prefix, List<String[]> rows, int batchSize) throws IOException {
        Files.createDirectories(dir);
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.tsv")) {
            for (Path p : ds) Files.deleteIfExists(p);
        }
        int num = 0;
        for (int i = 0; i < rows.size(); i += batchSize) {
            num++;
            int end = Math.min(i + batchSize, rows.size());
            List<String> lines = new ArrayList<>();
            lines.add("File\tKey\tEnglish\tArabic");
            for (int j = i; j < end; j++) {
                String[] r = rows.get(j);
                lines.add(r[0] + "\t" + r[1] + "\t" + r[2] + "\t");
            }
            String fname = String.format(Locale.ROOT, "%s%03d.tsv", prefix, num);
            Files.write(dir.resolve(fname), lines, StandardCharsets.UTF_8);
        }
    }

    static Set<String> loadPropKeys(Path p) throws IOException {
        Properties props = new Properties();
        try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) { props.load(r); }
        Set<String> set = new HashSet<>();
        for (String name : props.stringPropertyNames()) set.add(name);
        return set;
    }

    static void loadOrderedProperties(InputStream in, Map<String, String> ordered, Properties props) throws IOException {
        byte[] allBytes = in.readAllBytes();
        props.load(new InputStreamReader(new ByteArrayInputStream(allBytes), StandardCharsets.UTF_8));
        // Preserve key order by iterating logical lines and using Properties keys
        BufferedReader br = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(allBytes), StandardCharsets.UTF_8));
        String line;
        while ((line = br.readLine()) != null) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("!")) continue;
            while (endsWithOddBackslashes(line)) {
                String next = br.readLine();
                if (next == null) break;
                line = line.substring(0, line.length() - 1) + next.trim();
            }
            int sep = -1;
            for (int j = 0; j < line.length(); j++) {
                char c = line.charAt(j);
                if (c == '\\') { j++; continue; }
                if (c == '=' || c == ':') { sep = j; break; }
            }
            if (sep > 0) {
                String rawKey = line.substring(0, sep).trim();
                String key = unescapePropKey(rawKey);
                String val = props.getProperty(key);
                if (val != null) ordered.put(key, val);
            }
        }
        // Fallback for any keys parsed by Properties.load differently
        for (String name : props.stringPropertyNames()) {
            ordered.putIfAbsent(name, props.getProperty(name));
        }
    }

    static boolean endsWithOddBackslashes(String s) {
        int c = 0;
        for (int i = s.length() - 1; i >= 0 && s.charAt(i) == '\\'; i--) c++;
        return (c % 2) == 1;
    }

    static String unescapePropKey(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) { sb.append(s.charAt(++i)); }
            else sb.append(c);
        }
        return sb.toString();
    }

    static String escTsv(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "");
    }

    static String csvRow(String... cols) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cols.length; i++) {
            if (i > 0) sb.append(',');
            String v = cols[i] == null ? "" : cols[i];
            if (v.contains(",") || v.contains("\"") || v.contains("\n")) {
                sb.append('"').append(v.replace("\"", "\"\"")).append('"');
            } else {
                sb.append(v);
            }
        }
        return sb.toString();
    }
}
