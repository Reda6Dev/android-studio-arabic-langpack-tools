import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.MessageFormat;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;

/**
 * Clean standalone packager and verifier for Android Studio Language Pack.
 * أداة بناء وتحقق مستقلة لبناء ملف الإضافة (.zip) من مجلد *_ar.properties وملف plugin.xml.
 *
 * Inputs:
 *   - Translation directory (-Dmessages.dir=... or default <repo>/src/messages or <repo>/messages)
 *   - Plugin descriptor (-Dplugin.xml=... or default <repo>/plugin/META-INF/plugin.xml)
 *   - Optional Android Studio directory (-Dstudio.home=... or ANDROID_STUDIO_HOME env var) for JBR verification
 * Outputs:
 *   - Installable plugin ZIP (-Dout.zip=... or arg[0], default <repo>/build/<base.name>.zip)
 *   - Console verification report (bundle count, key count, BOM=0, duplicateKeys=0, JBR errors=0)
 *
 * Usage:
 *   javac -encoding UTF-8 -d out tools/BuildPackFromSources.java
 *   java -Dfile.encoding=UTF-8 -cp out BuildPackFromSources [outputZip]
 */
public class BuildPackFromSources {
    private static final Set<String> TOP_LEVEL_NON_MESSAGES_ROOTS = Set.of(
            "bundle", "com", "message", "splittingtabs"
    );

    public static void main(String[] args) throws Exception {
        for (String a : args) {
            if ("-h".equals(a) || "--help".equals(a)) {
                System.out.println("Usage: BuildPackFromSources [outputZip]");
                System.out.println("Properties: -Drepo.dir=. -Dmessages.dir=... -Dplugin.xml=... -Dout.zip=... -Dbase.name=arabic-language-pack-0.6 -Dstudio.home=...");
                return;
            }
        }

        Path repoDir = Paths.get(System.getProperty("repo.dir", ".")).toAbsolutePath().normalize();
        Path messagesDir = resolveMessagesDir(repoDir);
        Path pluginXml = Paths.get(System.getProperty("plugin.xml",
                repoDir.resolve("plugin").resolve("META-INF").resolve("plugin.xml").toString())).toAbsolutePath().normalize();

        String baseFolder = System.getProperty("base.name", "arabic-language-pack-0.6");
        String outProp = System.getProperty("out.zip", "");
        Path outZip = args.length > 0
                ? Paths.get(args[0]).toAbsolutePath().normalize()
                : (!outProp.isBlank()
                    ? Paths.get(outProp).toAbsolutePath().normalize()
                    : repoDir.resolve("build").resolve(baseFolder + ".zip"));

        if (!Files.isDirectory(messagesDir)) throw new IllegalStateException("Missing messages directory: " + messagesDir);
        if (!Files.isRegularFile(pluginXml)) throw new IllegalStateException("Missing plugin.xml: " + pluginXml);

        Files.createDirectories(outZip.getParent());

        List<Path> propFiles = new ArrayList<>();
        try (var s = Files.walk(messagesDir)) {
            s.filter(Files::isRegularFile).filter(p -> p.toString().endsWith("_ar.properties")).forEach(propFiles::add);
        }
        propFiles.sort(Comparator.comparing(p -> mapToJarEntryPath(messagesDir, p)));
        if (propFiles.isEmpty()) throw new IllegalStateException("No *_ar.properties files found under " + messagesDir);

        Path tmpJar = Files.createTempFile("arpack", ".jar");
        int bomFiles = 0, dupKeys = 0, totalKeys = 0;

        try {
            Map<String, Path> jarPathToSource = new LinkedHashMap<>();
            for (Path pf : propFiles) {
                String jarEntry = mapToJarEntryPath(messagesDir, pf);
                if (jarPathToSource.put(jarEntry, pf) != null) {
                    throw new IllegalStateException("Duplicate JAR entry path mapped: " + jarEntry);
                }
            }

            try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(tmpJar))) {
                jos.putNextEntry(new JarEntry("META-INF/"));
                jos.closeEntry();
                jos.putNextEntry(new JarEntry("META-INF/plugin.xml"));
                Files.copy(pluginXml, jos);
                jos.closeEntry();

                Set<String> dirs = new TreeSet<>();
                for (String rel : jarPathToSource.keySet()) {
                    int idx = 0;
                    while ((idx = rel.indexOf('/', idx)) >= 0) {
                        dirs.add(rel.substring(0, idx + 1));
                        idx++;
                    }
                }
                for (String d : dirs) {
                    jos.putNextEntry(new JarEntry(d));
                    jos.closeEntry();
                }

                for (Map.Entry<String, Path> e : jarPathToSource.entrySet()) {
                    String rel = e.getKey();
                    Path pf = e.getValue();
                    byte[] bytes = Files.readAllBytes(pf);
                    if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
                        bomFiles++;
                    }
                    Properties props = new Properties();
                    try (Reader r = new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
                        props.load(r);
                    }
                    dupKeys += countDuplicateLogicalKeys(new String(bytes, StandardCharsets.UTF_8));
                    totalKeys += props.size();

                    jos.putNextEntry(new JarEntry(rel));
                    jos.write(bytes);
                    jos.closeEntry();
                }
            }

            try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(outZip))) {
                zos.putNextEntry(new ZipEntry(baseFolder + "/"));
                zos.closeEntry();
                zos.putNextEntry(new ZipEntry(baseFolder + "/lib/"));
                zos.closeEntry();
                zos.putNextEntry(new ZipEntry(baseFolder + "/lib/" + baseFolder + ".jar"));
                Files.copy(tmpJar, zos);
                zos.closeEntry();
            }

            System.out.printf(Locale.ROOT, "Packaged ZIP: %s (%d bytes), bundles=%d, totalKeys=%d, bomFiles=%d, dupKeys=%d%n",
                    outZip, Files.size(outZip), jarPathToSource.size(), totalKeys, bomFiles, dupKeys);
            if (bomFiles != 0 || dupKeys != 0) {
                throw new IllegalStateException("Integrity error: bomFiles=" + bomFiles + ", dupKeys=" + dupKeys);
            }

            String studioHomeProp = System.getProperty("studio.home", System.getenv("ANDROID_STUDIO_HOME"));
            if (studioHomeProp != null && !studioHomeProp.isBlank() && Files.isDirectory(Paths.get(studioHomeProp).resolve("lib"))) {
                verifyWithPlatform(Paths.get(studioHomeProp).resolve("lib"), tmpJar, jarPathToSource);
            } else {
                System.out.println("Note: Set -Dstudio.home=... or ANDROID_STUDIO_HOME to also run IntelliJ platform JBR verification.");
            }
        } finally {
            Files.deleteIfExists(tmpJar);
        }
    }

    private static Path resolveMessagesDir(Path repoDir) {
        String custom = System.getProperty("messages.dir", "");
        if (!custom.isBlank()) return Paths.get(custom).toAbsolutePath().normalize();
        Path p1 = repoDir.resolve("src").resolve("messages");
        if (Files.isDirectory(p1)) return p1;
        Path p2 = repoDir.resolve("src");
        if (Files.isDirectory(p2)) return p2;
        return repoDir.resolve("messages");
    }

    /**
     * Maps both flat FooBundle_ar.properties inside messages/ -> "messages/FooBundle_ar.properties"
     * and nested non-messages subpaths (bundle/..., com/..., message/..., splittingtabs/...) -> exact JAR path.
     */
    private static String mapToJarEntryPath(Path baseDir, Path file) {
        String rel = baseDir.relativize(file).toString().replace('\\', '/');
        if (rel.startsWith("messages/")) return rel;
        int slash = rel.indexOf('/');
        if (slash > 0 && TOP_LEVEL_NON_MESSAGES_ROOTS.contains(rel.substring(0, slash))) {
            return rel;
        }
        return "messages/" + rel;
    }

    private static void verifyWithPlatform(Path studioLib, Path jarPath, Map<String, Path> jarPathToSource) throws Exception {
        List<URL> urls = new ArrayList<>();
        urls.add(jarPath.toUri().toURL());
        try (var s = Files.list(studioLib)) {
            for (Path p : s.toList()) {
                if (p.toString().endsWith(".jar")) urls.add(p.toUri().toURL());
            }
        }
        Locale arLocale = Locale.forLanguageTag("ar");
        int verified = 0, errors = 0;
        try (URLClassLoader cl = new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader())) {
            Class<?> abKt = Class.forName("com.intellij.AbstractBundleKt", true, cl);
            Method resolve = abKt.getDeclaredMethod("_doResolveBundle", ClassLoader.class, Locale.class, String.class);
            resolve.setAccessible(true);
            Class<?> twm = Class.forName("com.intellij.openapi.util.text.TextWithMnemonic", true, cl);
            Method parse = twm.getDeclaredMethod("parse", String.class);
            parse.setAccessible(true);

            for (Map.Entry<String, Path> e : jarPathToSource.entrySet()) {
                String rel = e.getKey();
                Path pf = e.getValue();
                String baseName = rel.replaceFirst("_ar\\.properties$", "").replace('/', '.');
                ResourceBundle rb = (ResourceBundle) resolve.invoke(null, cl, arLocale, baseName);
                if (rb == null || !arLocale.equals(rb.getLocale())) {
                    System.err.println("Failed _doResolveBundle: " + baseName);
                    errors++;
                    continue;
                }
                Properties own = new Properties();
                try (Reader r = Files.newBufferedReader(pf, StandardCharsets.UTF_8)) {
                    own.load(r);
                }
                for (String k : own.stringPropertyNames()) {
                    String val = rb.getString(k);
                    if (!own.getProperty(k).equals(val)) {
                        System.err.println("Roundtrip mismatch: " + baseName + "|" + k);
                        errors++;
                        continue;
                    }
                    parse.invoke(null, val);
                    if (val.matches("(?s).*\\{\\d+[,}].*")) {
                        new MessageFormat(val, arLocale);
                    }
                    verified++;
                }
            }
        }
        System.out.printf(Locale.ROOT, "Platform JBR Verification: verified=%d, errors=%d%n", verified, errors);
        if (errors > 0) throw new IllegalStateException("Platform JBR verification failed with " + errors + " errors.");
    }

    private static int countDuplicateLogicalKeys(String content) {
        Set<String> seen = new HashSet<>();
        int dups = 0;
        String[] lines = content.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].stripLeading();
            if (l.isEmpty() || l.startsWith("#") || l.startsWith("!")) continue;
            while (endsWithOddBackslashes(l) && i + 1 < lines.length) {
                i++;
                l = l.substring(0, l.length() - 1) + lines[i].stripLeading();
            }
            int eq = -1;
            for (int c = 0; c < l.length(); c++) {
                char ch = l.charAt(c);
                if (ch == '\\') { c++; continue; }
                if (ch == '=' || ch == ':') { eq = c; break; }
            }
            if (eq > 0 && !seen.add(l.substring(0, eq).trim())) dups++;
        }
        return dups;
    }

    private static boolean endsWithOddBackslashes(String s) {
        int c = 0;
        for (int i = s.length() - 1; i >= 0 && s.charAt(i) == '\\'; i--) c++;
        return (c % 2) == 1;
    }
}
