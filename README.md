# Arabic Language Pack Toolset for Android Studio (`261.*`)

[العربية (Arabic README)](README.ar.md) | [Full Process Guide (`docs/PROCESS.md`)](docs/PROCESS.md)

> **Unofficial Community Project (Beta)** — Translations produced with this pipeline are **AI-assisted (`arabic-translator-v2`) and reviewed via sampling only**.

An open-source extraction, bytecode reachability auditing, validation, and JBR verification toolset for building the **Arabic Language Pack (Community)** plugin for **Android Studio 2026.1.1 (build `261.*`)**.

This repository contains **only the clean build/discovery/validation tools, prompts, glossary, and engineering documentation** — no Android Studio `.jar` files, no extracted English `.properties`, and no personal paths are included. The tools extract everything directly from your own local Android Studio installation.

---

## Key Verified Metrics (from `docs/final_report.md`)

- **Total IDE strings audited:** `47,873` keys across `365` `.properties` bundles
- **Final Full Edition (`v0.6`):** `183` bundles, **`25,877` translated keys**, `0` BOM files, `0` duplicate keys, `0` JetBrains Runtime (JBR) verification errors
- **Milestone Progression:** `0.1` (`3,024`) → `0.2` (`7,669`) → `0.3` (`18,477`) → `0.3.1` (`18,489`) → `0.3.2` (`18,592`) → `0.4` (`21,067`) → `0.5` (`22,333`) → `0.6` (`25,877`)
- **Stage 0 Reachability Exclusions:** `36` bundles (`764` keys across Stages 2–4) bypass IntelliJ's `DynamicBundle` loader (`ResourceBundle.getBundle` / `MessageBundleReference`)
- **Discovery Policy Exclusions:** `21,320` keys (`PROG_LANG`: `10,777`, `INSPECTIONS`: `4,414`, `TECHNICAL`: `2,943`, >40-word / low-level errors: `3,186`)

---

## Repository Structure

| Path | Purpose |
| :--- | :--- |
| `tools/Phase2Discovery.java` | Scans local Android Studio `lib/*.jar` and `plugins/**/*.jar`, classifies bundles, extracts `.properties`, and splits translation batches |
| `tools/AnalyzeBundleReachability.java` | Stage 0 bytecode auditor identifying `DynamicBundle` vs `ResourceBundle.getBundle` / `MessageBundleReference` bypasses (`bundle_reachability.csv`) |
| `tools/FilterStage1Actions.java` | Stage 1 `javap` bytecode & XML filter isolating localizable XML actions (`action.<id>.text`) from code-hardcoded or internal ones |
| `tools/validate_translations.ps1` | Automated QA validator checking placeholders, `MessageFormat`/`ChoiceFormat`, `&`/`_` mnemonics, HTML tags, RTL/terminology rules |
| `tools/BuildStagedPack.java` | Staged builder applying Stage 0 bypass exclusions, `SLUG_EXCLUDE_KEYS`, JAR byte checks, JBR `_doResolveBundle` verification, and stats CSVs |
| `tools/BuildPackFromSources.java` | Standalone packager turning any `messages/` folder of `*_ar.properties` + `plugin/META-INF/plugin.xml` into an installable `.zip` + JBR check |
| `plugin/META-INF/plugin.xml` | Clean plugin descriptor (`com.reda6dev.arabic.languagepack`, `since-build="261.0"`, `until-build="261.*"`, `<languageBundle locale="ar"/>`) |
| `docs/PROCESS.md` | Complete 9-section technical handbook covering architecture, step-by-step commands, terminology rules, exclusions, updates, and porting to other languages |
| `docs/decisions.md` | Chronological log of all technical & linguistic decisions (`Sections 1–18`) |
| `docs/term_glossary.csv` | Canonical Arabic/English terminology rules (`81` terms) |
| `docs/final_report.md` | Audited milestone progression and categorized inventory of everything remaining in English and why |
| `docs/license_inventory.csv` | On-disk license audit of all `183` bundles in `v0.6` (`95` Apache 2.0 confirmed vs `88` proprietary / undocumented) |
| `docs/prompts/` | System prompt & definition for `arabic-translator-v2` and reusable stage prompts (`workflow-prompts.md`) |

---

## Quick Start: Build a Plugin ZIP from Translated Files

If you have the Arabic `*_ar.properties` files in a `messages/` folder (for example from the companion `ArabicLangPack-Translation` package):

```powershell
javac -encoding UTF-8 -d out tools/BuildPackFromSources.java
java -Dfile.encoding=UTF-8 -Duser.language=en -Duser.country=US `
  -Dmessages.dir="PATH_TO\messages" `
  -Dplugin.xml="plugin\META-INF\plugin.xml" `
  -Dstudio.home="C:\Program Files\Android\Android Studio" `
  -cp out BuildPackFromSources build\arabic-language-pack-0.6-custom.zip
```

To run discovery, reachability analysis, and batch validation on a fresh working directory (`./work`):

```powershell
javac -encoding UTF-8 -d out tools/Phase2Discovery.java tools/AnalyzeBundleReachability.java
java -Dfile.encoding=UTF-8 -Dstudio.home="C:\Program Files\Android\Android Studio" -Dwork.dir=".\work" -cp out Phase2Discovery
java -Dfile.encoding=UTF-8 -Dstudio.home="C:\Program Files\Android\Android Studio" -Dwork.dir=".\work" -cp out AnalyzeBundleReachability
powershell -NoProfile -ExecutionPolicy Bypass -File tools\validate_translations.ps1 -InputPath .\work\your_batch.tsv -ExtractedDirs .\work\extracted -StrictEnglishCheck
```

---

## Installation, Upgrade & Emergency Removal

- **Compatibility:** Android Studio `2026.1.1` (build `261.*`) only.
- **Upgrade Warning:** If you previously installed any build using the legacy ID `com.intellij.ar.community`, uninstall it first and restart Android Studio before installing `com.reda6dev.arabic.languagepack`.
- **Install:** `Settings > Plugins > Gear icon (⚙) > Install Plugin from Disk...` → select the `.zip` → restart → `Settings > Appearance & Behavior > System Settings > Language and Region` → select **Arabic** → restart.
- **Emergency Removal:** Close Android Studio and delete the `arabic-language-pack-*` folder inside `%APPDATA%\Google\AndroidStudio2026.1.1\plugins\` (Windows), `~/Library/Application Support/Google/AndroidStudio2026.1.1/plugins/` (macOS), or `~/.local/share/Google/AndroidStudio2026.1.1/plugins/` (Linux).

---

## Known Limitations & Legal Disclaimer

- Code-hardcoded UI strings (`205` XML actions without text/key, `45` ctor/`update()` actions, `4` bytecode-driven actions), XML strings without `<resource-bundle>` (`5` actions + `11` Settings `displayName` entries), and `36` bypassing bundles remain in English.
- Mixed Arabic/English labels may show Swing/BiDi punctuation ordering quirks; programming keywords and standard developer terms (`Git`, `Commit`, `Gradle`, `SDK`, `Shelf`, etc.) are intentionally preserved in English.
- **Disclaimer:** **Android Studio** and **Gemini** are trademarks of **Google LLC**; **IntelliJ** and **IntelliJ IDEA** are trademarks of **JetBrains s.r.o.** This repository is an independent community project (`https://github.com/Reda6Dev`) and is not affiliated with or endorsed by Google LLC or JetBrains s.r.o. Tools in `tools/` are MIT-licensed ([LICENSE](LICENSE)); see [NOTICE](NOTICE) regarding original UI strings and unverified legal status of derived translations.
