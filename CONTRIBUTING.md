# Contributing / المساهمة

Contributions to improve tool reliability, documentation, terminology consistency, or translation quality are welcome!

## Guidelines
1. **Follow the Terminology Glossary:** Keep `docs/term_glossary.csv` and `docs/decisions.md` rules (`Commit`, `Push`, `Gradle`, `SDK`, `Shelf`, programming keywords stay English; `Update` -> `تحديث`; no English parentheses in Arabic text; no space after `و`).
2. **Never Commit Extracted IDE Files:** Do NOT commit Android Studio `.jar` files, extracted English `.properties` files, or `.tsv` batches containing original English IDE strings.
3. **Validate Before Submitting:**
   - Run `tools/validate_translations.ps1 -InputPath <your-batch> -ExtractedDirs <extracted-dir> -StrictEnglishCheck` (`0` errors).
   - Rebuild and JBR-verify using `tools/BuildPackFromSources.java`.
4. **Report Translation Bugs:** Open a GitHub Issue with the English text, current Arabic translation, suggested fix, and UI location.
