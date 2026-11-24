# سجل القرارات التحريرية والتقنية — حزمة اللغة العربية v0.2

توثق هذه الصفحة القرارات المتخذة تلقائياً وفق قواعد المهمة دون إيقاف العمل:

## 1. فحص ملف `accepted_translations.csv` السابق (الأخطاء الإملائية والمشاكل اللغوية)
- **نتيجة فحص (`الأخيره` / `إعاده` / `اداره`)**: تم البحث نصياً عبر كامل `accepted_translations.csv` (3,024 صفاً) عن الكلمات (`الأخيره`، `إعاده`، `اداره`) وكانت النتيجة **0 تطابق** داخل الملف، ما يؤكد أن ظهورها بهذا الشكل سابقاً كان ناتجاً عن عرض الخط أو الترميز في الطرفية وليس في نص الملف نفسه.
- ومع ذلك، تمّت إضافة قاعدة فحص آلية داخل `validate_translations.ps1` لمنع كتابة التاء المربوطة هاءً (`ه` بدل `ة`) في أي قائمة شائعة من الأسماء والصفات في كل الدفعات القديمة والجديدة.
- **الأخطاء الفعلية المكتشفة في `accepted_translations.csv` (3,024 صفاً)**: بسبب توليدها سابقاً بالسكربت التركيبي (`TranslatePhase1.java`)، ظهر عدد من المشاكل اللغوية الحقيقية التي تقرر مراجعتها وإصلاحها يدوياً بواسطة النموذج اللغوي وتوثيقها في `fixed_errors.csv`:
  1. مسافة زائدة بعد واو العطف: مثل `و تمرير` و`و إعادة` (تصبح `وتمرير` و`وإعادة`).
  2. عكس ترتيب الإضافة وتركيب `لـ` غير الطبيعي: مثل `Commit لـ فحوصات رسالة:` (تُصحح إلى `فحوصات رسالة Commit:`) و`حفظ ''{0}'' تكوين` (تُصحح إلى `حفظ تكوين ''{0}''`).
  3. إسقاط `ال` التعريف في الإضافات المعرّفة: مثل `إعدادات مكتبة` أو `عرض ملفات مشكلة` (تُصحح إلى `إعدادات المكتبة` و`الملفات التي بها مشاكل`).
  4. ترجمة `Shelf` إلى `رف` أو `الرف` (تُصحح لتبقى `Shelf` بالإنجليزية وفق القاعدة الجديدة).
  5. إبقاء `Update` بالإنجليزية في مفاتيح VCS وGit (تُصحح إلى `تحديث` في جميع السياقات وفق القاعدة الجديدة).

## 2. تطبيق قاعدة `Update` و`Shelf` الجديدة على القاموس وكل الدفعات
- **`Update` = `تحديث` دائماً**: تم اعتماد ترجمة `Update` إلى `تحديث` في جميع السياقات، بما يشمل قوائم VCS وGit (مثل: `Update Project` -> `تحديث المشروع`، `Checkout and Update` -> `Checkout وتحديث`، `_Update` -> `تحديث (_U)`). كما تم تحديث الصفوف المقابلة في `phase1-glossary-250.csv`.
- **`Shelf`**: تبقى كلمة `Shelf` بالإنجليزية في جميع النصوص والأزرار (مثل: `Shelve Changes` -> `وضع التغييرات في Shelf`، `Unshelve` -> `استعادة من Shelf`، `Shelf` -> `Shelf`).

## 3. حل التعارض في نطاق المستوى ب (27 ملفاً في المجموعة `ب` مقابل استبعاد لغات البرمجة)
- يحتوي تصنيف المجموعة `ب` في `android-studio-bundles-stats.csv` على 27 ملفاً بإجمالي 3,235 مفتاحاً (منها 1,816 مفتاحاً ضمن حد `<= 15` كلمة وغير مستبعد اللاحقة).
- يتضمن التصنيف 16 ملف إعدادات عامة لمنصة Android Studio (إجمالي 1,420 مفتاحاً ضمن النطاق، أولها `OptionsBundle.properties` و`ApplicationBundle.properties`)، بالإضافة إلى 11 ملف إعدادات تابعة لإضافات لغات البرمجة (`Java`، `Kotlin`، `C++ ClangFormat`) بإجمالي 396 مفتاحاً ضمن النطاق.
- **القرار**: استخراج ملفات المجموعة `ب` كاملةً إلى `extracted_settings/`، والبدء بإعطاء الأولوية القصوى لملفات إعدادات الواجهة العامة الـ 16 (بدءاً من `OptionsBundle` و`ApplicationBundle`)، واستبعاد ملفات اللغات الـ 11 (`JavaCompilerBundle`، `VMOptionsBundle`، `ClangFormatBundle`، `JavaOptionBundle`، ومجموعة `Kotlin*`) التزاماً بقاعدة استبعاد لغات البرمجة (`Kotlin` و`Java` و`Groovy` و`C++`) وتجنباً لتكرار `JavaOptionBundle.properties` بين إضافتي Java وKotlin.
- **تنقية `OptionsBundle.properties`**: استبعاد مفاتيح `codestyle.property.description.*` (153 مفتاحاً مصنفاً كشروحات خصائص Code Style) والقيم النصية `TBD` غير المكتملة من JetBrains لتبقى واجهات الإعدادات الفعلية فقط.
- **ملاحظة تقنية حول تكرار اسم `JavaOptionBundle.properties`**: يوجد ملفان بهذا الاسم في `android-studio-bundles-stats.csv`؛ أحدهما في `plugins/Kotlin/kotlinc/lib/kotlin-compiler.jar` والآخر في `plugins/java/lib/java-impl.jar`، وكلاهما يحمل نفس المسار الداخلي `messages/JavaOptionBundle.properties`. تقرر فحص محتواهما عند الاستخراج والتأكد من عدم التعارض قبل التجميع.

## 4. قرار التعامل مع صيغ `ChoiceFormat` مثل `{0,choice,0#...|1#...}` وعلامات التنصيص `''`
- في المفاتيح التي تحتوي على `{0,choice,...}`: تُترجم النصوص المعروضة داخل الفروع فقط، مع الإبقاء التام على شروط الأرقام وعلاماتها (`0#`، `1#`، `1<`، `2#` إلخ) دون تغيير، والتحقق منها برمجياً في سكربت التحقق وفي `java.text.MessageFormat`.
- في المفاتيح التي تحتوي على placeholders مع `MessageFormat`: تبقى علامات التنصيص المزدوجة `''{0}''` حول المتغيرات مزدوجة دائماً لتجنب كسر `MessageFormat`، ولا يُشترط مطابقة العدد الكلي لـ `''` إذا كان المصدر الإنجليزي يحتوي على اختصار مثل `Can''t` أو `Don''t` لا يقابله تنصيص في العربية؛ بل يُشترط: (أ) ألا يوجد أي `'` مفرد غير مزدوج عندما يحتوي النص على `{0}`, و(ب) تطابق `''{N}''` عند وجودها.

## 5. مسار التحميل الداخلي للحزم داخل `arabic-language-pack-0.2.jar`
- تم التحقق من عمود `internal_path` لكل ملفات المستوى أ (7 ملفات) والمستوى ب (27 ملفاً) في `android-studio-bundles-stats.csv`: جميعها تقع تحت المسار الداخلي `messages/<BundleName>.properties`، ولذلك ستُحزم جميع ملفات `*_ar.properties` تحت `messages/` داخل `arabic-language-pack-0.2.jar`، وسيتم التحقق من تحميل كل حزمة عبر `AbstractBundleKt._doResolveBundle`.

## 6. فحص العينة التجريبية بعد أول 3 دفعات (`audit_01`، `audit_02`، `batch_A01`)
- تمت مراجعة وإصلاح أول دفعتين من القديم (`audit_01` و`audit_02`: 290 مفتاحاً) وترجمة أول دفعة جديدة (`batch_A01`: 140 مفتاحاً) بإجمالي **430 مفتاحاً**.
- نجحت الدفعات الثلاث في فحص `validate_translations.ps1 -StrictEnglishCheck` بـ **0 أخطاء**.
- **نسبة التصحيح في عينة المراجعة**: تم تعديل **102** ترجمة من أصل **290** في الدفعتين الأوليين فقط لإصلاح تركيبات آلية سابقة، أبرزها:
  - تكرار الكلمات: `عكس التغييرات تغييرات` -> `عكس التغييرات`.
  - خلل الإضافة و`لـ`: `فحص لـ تحديثات` -> `التحقق من التحديثات`، `تحرير التفضيلات تطبيق` -> `تحرير تفضيلات التطبيق`، `إظهار السجل مجموعة` -> `إظهار سجل المجموعة`، `عرض وضع` -> `وضع العرض`، `فحص جارٍ تحميل Class` -> `فحص تحميل Class`.
  - مصطلحات محدثة: `الرف` -> `Shelf`، `Checkout و Update` -> `Checkout وتحديث`.
  - إسقاط `ال` التعريف في الأسماء المضافة والمسافة بعد `و`.
- أكدت العينة العشوائية (30 عنصراً) أن الالتزام المباشر بقواعد الإضافة والتعريف وصيغ `{0,choice,...}` والـ mnemonics داخل أقواس `(&X)` / `(_X)` ينتج نصوصاً طبيعية ومتناسقة تماماً؛ ولذا اعتُمدت نفس التعليمات لبقية الدفعات.

## 7. استبعاد معرفات روابط التوثيق الداخلية (`Help Anchor Slugs`) وفحص الـ 100 عينة النهائية
- **استبعاد معرفات التوثيق في `GitBundle`**: عند المسح الشامل على كل النصوص المترجمة (7,672 مفتاحاً)، تبيّن أن المفتاحين `gpg.jb.manual.link` (`Set_up_GPG_commit_signing`) و`gpg.pinentry.jb.manual.link` (`Set_up_GPG_pinentry_for_commit_signing`) في `GitBundle.properties` هما معرفا روابط (Anchor Slugs) لصفحات المساعدة وليسا نصاً ظاهراً للمستخدم؛ لذا تم استبعادهما في التجميع النهائي (`AssembleAndVerify02.java`) وبقيا بالإنجليزية كما في الأصل لحماية الروابط من الكسر.
- **نتيجة فحص العينة العشوائية (100 مفتاح: 50 مستوى أ + 50 مستوى ب)**:
  - اجتازت العينة المراجعة اللغوية بنسبة عالية جداً، وتم ضبط 3 تفاصيل أسلوبية دقيقة بعد مراجعتها: مطابقة صفة الجمع غير العاقل (`فصل الحروف الكبيرة` بدل `الكبير`) في مفاتيح `CamelHumps`، توحيد `Directory -> مجلد` بدل `دليل` في `VcsBundle` (`إضافة تعيين مجلد VCS`)، وتصحيح ترجمة `settings.copyright.formatting.box` (`Box`) من `صندوق` إلى `إطار` لكونها تخص حدود إطار تعليق حقوق الملكية.

## 8. قرارات المرحلة 2 وإصلاحات v0.2 الإلزامية (v0.3 – v0.6)
- **إبقاء كلمات لغات البرمجة المحجوزة (`Keywords`) بالإنجليزية دائماً**: في شاشات `Code Style` و`Editor` وأي شاشة تتعلق بلغة البرمجة، تبقى الكلمات المحجوزة بالإنجليزية ولا تُترجم، ومنها: `transient`, `trait`, `final`, `static`, `abstract`, `override`, `overridden`, `interface`, `enum`, `var`, `val`, `const`, `volatile`, `synchronized`, `readonly`, `public`, `protected`, `private`, `package private`, `static init`, `native`, `strictfp`, `sealed`, `inline`, `infix`, `operator`, `suspend`, `tailrec`, `lateinit`, `reified`, `companion`. وعند وجود معكوس (`.inverted`) يُصاغ بـ `ليس <keyword>` (مثل `ليس transient` و`ليس trait`). تم تطبيق هذا الإصلاح على 72 مفتاحاً عبر `CodeStyleBundle_ar.properties` و`OptionsBundle_ar.properties` و`ApplicationBundle_ar.properties` و`ActionsBundle_ar.properties`.
- **إبقاء `CamelHumps` بالإنجليزية**: استبدال `نمط فصل الحروف الكبيرة` في `ActionsBundle_ar.properties` (4 مفاتيح) بـ `وضع "CamelHumps"` كما هو معتمد في `ApplicationBundle_ar.properties`.
- **توحيد ترجمة الأقواس والعوامل**:
  - `Braces and Operators` -> `الأقواس المعقوفة والعوامل` دائماً (مع إصلاح الخطأ المطبعي السابق `المعقوصة` وتوحيد `عوامل التشغيل` / `العوامل` إلى `العوامل`).
  - `Braces` -> `الأقواس المعقوفة`، `Brackets` -> `الأقواس المربعة`، `Parentheses` -> `الأقواس الدائرية` (توحيداً بدل `الهلالية`).
- **توسيع التغطية في المرحلة 2 على 4 مستويات مستقلة**:
  1. المستوى 1 (`0.3`): كل نصوص الواجهة والإعدادات المرئية المباشرة (`<= 15` كلمة) من جميع ملفات `UI_GENERAL` و`SETTINGS`، بما فيها المفاتيح القصيرة المتبقية من ملفات v0.2 التي استبعدها التصفية الأولية لـ v0.1 (`scope.open.files`، `scratches.and.consoles`، `select.in.packages`...) وملفات الإعدادات الـ 11 المستبعدة سابقاً (`JavaCompilerBundle`، `VMOptionsBundle`، `Kotlin*`، `ClangFormatBundle`...) وملفات الواجهة الأساسية (`LocalHistoryBundle`، `CoreBundle`، `LangBundle`، `CodeInsightBundle`، `OCBundle` لنطاقات `Project Source Files` وغيرها).
  2. المستوى 2 (`0.4`): التلميحات والشروحات (`.tooltip`, `.description`, `.hint`, `.comment`) حتى `<= 40` كلمة.
  3. المستوى 3 (`0.5`): واجهة Gemini والوكيل والذكاء الاصطناعي المدمج (`StudioGeminiBundle`, `AgentsCoreBundle`, `McpHostBundle` إلخ).
  4. المستوى 4 (`0.6`): رسائل النوافذ المنبثقة والإشعارات وأسئلة التأكيد وحالات النظام حتى `<= 40` كلمة، مع الإبقاء المتعمّد على رسائل أخطاء البرمجة والاستثناءات بالإنجليزية.
- **احترام المسار الداخلي الفعلي داخل الـ JAR**: يتم حفظ كل ملف `*_ar.properties` في المسار الداخلي المطابق لأصله داخل حزم Android Studio (`messages/...` أو `com/android/tools/idea/...` إلخ) كما رُصد في `discovery.csv`.

## 9. مراجعة العينة التجريبية للمستوى 1 (أول 3 دفعات — 375 مفتاحاً)
- تم فحص `batch_L1_001.tsv` و`batch_L1_002.tsv` و`batch_L1_003.tsv` (375 مفتاحاً) ومراجعة 30+ مفتاحاً منها يدوياً بالإضافة إلى تمريرها عبر `validate_translations.ps1`.
- **النتيجة**: تناسق تام مع قواعد المرحلة الثانية (`CamelHumps` بالإنجليزية، `Class` بالإنجليزية، عدم وجود أقواس إنجليزية، صياغة عربية سليمة لنصوص معالج Android وتكوينات التشغيل والاختصارات `(&E)` و`(&P)` و`(&C)`).
- **ملاحظة ضبط أسلوب**: الحفاظ على `\\&` كما هي عند ظهورها في الإنجليزية (مثل `Phone \\& Tablet` -> `الهاتف \\& الجهاز اللوحي`)، والالتزام الدائم بعدم وجود أي تنصيص مفرد غير مزدوج مع `{0}`.

## 10. مراجعة العينتين التجريبيتين للمستوى 2 (001–003) والمستوى 3 Gemini (001–003)
- **المستوى 2 (`batch_L2_001`–`003`)**: فحص 30 وصفاً وتلميحاً؛ النصوص طبيعية مع حفظ الوسوم والأقواس (`Blame`, `Class`, `Debug`, `WSL VM`)، ولا حاجة لتغيير القواعد.
- **المستوى 3 (`batch_L3_001`–`003`)**: فحص 30 نصاً لحزم `StudioGeminiBundle` و`AgentsCoreBundle` و`McpHostBundle` و`OnboardingCommonBundle`؛ حفظ تام لمصطلحات `Gemini`, `MCP`, `Lint`، مع استثناء روابط Markdown من نوع `[النص](https://...)` أو `(select_file)` في سكربت التحقق لأنها روابط تقنية مطلوبة وليست أقواساً عربية.
- **حالة هروب الأقواس `'{'` و`'}'` في `MessageFormat`**: عند احتواء النص الإنجليزي الأصلي على `'{'` / `'}'` أو `' '<br/>'` (كما في `CloudBundle.editor.pane.text.empty.text` و`IdeBundle.updates.incompatible.plugins.found`)، يُتحقق من تطابقها الفعلي عبر `java.text.MessageFormat` في JBR.

## 11. مراجعة العينة التجريبية للمستوى 4 (001–003)
- **المستوى 4 (`batch_L4_001`–`003`)**: فحص 30 رسالة حوار وإشعار وتأكيد وحالة (`ActionsBundle`, `AndroidBundle`, `ExecutionBundle`).
- **النتيجة**: صياغة طبيعية وتوافق مع وسوم `<i>` و`{1, choice, 1#...|2#{1, number}}`، وتوحيد ترجمة `build variant` إلى `متغير البناء` في بقية دفعات المستوى 4.

## 12. مراجعة المستوى 1 وبناء وتسليم `arabic-language-pack-0.3.zip` (v0.3)
- **التحقق الشامل لـ 87 دفعة (10,808 مفاتيح)**: نجحت جميع الدفعات الـ 87 في `validate_translations.ps1` بـ **0 أخطاء** بعد:
  - إصلاح 3 صياغات دقيقة: `تم تحزيمه`/`تم تحزيمها` في `JavaCompilerBundle|status.text.file.has.been.packaged` (بدل `حزمه`), إزالة القوسين الإنجليزيين من `LogcatBundle|logcat.filter.completion.hint1` و`hint2`, وتوحيد `CoreBundle|plugin.category.Keymap` إلى `اختصارات لوحة المفاتيح`.
  - إضافة قوالب الكود المطابقة للأصل ومصطلحات Git/Product في `$AllowedStandaloneEnglish`، واستثناء أمثلة الصياغة المحصورة بعلامات تنصيص `"..."` (مثل `"tag:Foo & (message:Bar | level:ERROR)"`) في `validate_translations.ps1`.
- **تجميع وفحص `arabic-language-pack-0.3.zip`**:
  - إجمالي الحزم المضمّنة: **161** حزمة، بإجمالي **18,477** مفتاحاً (`7,669` من `0.2` المصلحة + `10,808` من المستوى 1).
  - اجتازت الفحص المباشر على `arabic-language-pack-0.3.jar`: `0` ملفات BOM، `0` مفاتيح مكررة، `19/19` تحققاً حياً لإصلاحات `0.2` الإلزامية، و`18,477/18,477` فحص تحميل عبر `AbstractBundleKt._doResolveBundle` و`TextWithMnemonic.parse` و`MessageFormat` بـ `0` أخطاء.
- **إعادة اختبار قائمة الـ 34 نصاً**: تحوّل **30 من 34** نصاً إلى العربية داخل `0.3.jar`، بينما بقيت **4 نصوص فقط** بالإنجليزية لكونها مكتوبة مباشرةً في XML (`Profile or Debug APK`, `Inspect for Play Policy Insights`) أو في `.class` bytecode (`Sync Project with Gradle Files`, `Refresh Linked C++ Projects`).
- **تجميد المستويات 2 و3 و4 مؤقتاً**: حفظ حالة الدفعات المكتوبة جزئياً قبل انقطاع الحصة في `progress.json` دون إطلاق أي دفعة جديدة منها قبل تسليم `0.3`.

## 13. المرحلة 0 — فحص قابلية وصول الحزم (`bundle_reachability.csv`) واستثناء الحزم المتجاوِزة في المراحل 2–4
- تم فحص **191** حزمة (الـ 162 حزمة في `0.3.1` + الحزم المؤهلة في `discovery.csv`) عبر تفكيك بايت كود الكلاسات القارئة لكل حزمة:
  - **144 حزمة** تعمل عبر `DynamicBundle` (بما فيها حزم `AbstractKotlinBundle` التي تغلّف `DynamicBundle`، وحزم `SmlBundle` و`SmlIjBundle` و`McpHostBundle` و`AgentsCoreBundle` و`OnboardingCommonBundle` و`ChangeReviewBundle` و`V2Bundle`).
  - **4 حزم** تستخدم `AbstractBundle` مباشرة (`LocalProviderBundle`, `RemoteProviderBundle`, `StreamingBundle`, `WorkspaceModelJpsBundle`).
  - **7 حزم** لم يُعثر على كلاس مباشر لها وتُعامل كأنها تعمل حسب القاعدة (`UNKNOWN_NO_DIRECT_CLASS_MATCH`).
  - **36 حزمة تتجاوز حزمة اللغة** عبر `java.util.ResourceBundle.getBundle` مباشرة أو `MessageBundleReference`:
    - في المرحلة 3 (`GEMINI_AI`): `StudioGeminiBundle` (100)، `IntellijPluginCoreMessageBundle` (27)، `JourneysBundle` (25) — بإجمالي 152 مفتاحاً.
    - في المراحل 2 و4 (`UI_GENERAL`): `AndroidBundle`, `AgpUpgradeBundle`, `LogcatBundle`, `PreviewBundle`, `ComposePreviewBundle`, `LayoutInspectorBundle`, `AndroidWearPairingBundle`, `AndroidAdbUiBundle`, `FirebaseBundle`, `WearWhsBundle`, `ComposeBundle`, `DatabaseInspectorBundle`, `BackupBundle`, `AndroidTestBundle`, `DaggerBundle`, `SamplesBrowserBundle`, `DeviceManagerBundle`, `SymbolPickerBundle`, `WearDwfBundle`, `BackgroundTaskInspectorBundle`, `UtilUiBundle`, `SplittingTabsBundle`, `DeviceFileExplorerBundle`, `WearBundle`, `NetworkInspectorBundle`, `LayoutlibBundle`, `GlancePreviewBundle`, `AndroidRunBundle`, `GoogleLoginBundle`, `ApkAnalyzerBundle`, `AppInspectorBundle`, `RenderingBundle`, `AndroidExecutionBundle` — بإجمالي 237 مفتاحاً في L2 و358 مفتاحاً في L4.
- **القرار**: لا يُترجم أي مفتاح جديد داخل هذه الحزم الـ 36 في المراحل 2 و3 و4 (توفير إجمالي **747 مفتاحاً**). أما مفاتيح `<action>` و`<group>` في XML ضمن **المرحلة 1** (`action.<id>.text` / `group.<id>.text`) ف**تبقى مشمولة** لأن قراءتها تتم عبر `ActionManagerImpl` في المنصة باستخدام `DynamicBundle`، لا عبر كلاس `AndroidBundle` نفسه.

## 14. المرحلة 1 (`0.3.2`) — تصفية وترجمة عناصر XML الـ 187 لإضافات Android وGoogle
- فُحصت الـ `187` عينة من فئة `XML_TEXT_WITH_ACTIVE_BUNDLE_NO_KEY` عبر `FilterStage1Actions.java` مع تفكيك `javap -c -p` لكلاساتها وكلاساتها الأب (`superclasses`):
  - **مشمول للترجمة**: **`103`** مفاتيح (`79` في `AndroidBundle`، `19` في `AndroidDesignerActionsBundle`، `2` في `SmlBundle`، `3` في `SmlIjBundle`).
  - **مستبعد**: **`84`** عنصراً (`12` مضافة مسبقاً في `0.3.1`، `27` داخلية/`Internal`/`Debug`/`RAG` مثل `WebpSupportTestAction` و`DeadlockUIThread` و`RunNewProjectAgentAction`، و`35` تمرر نصاً للكونستركتور مثل `SetZoomAction` و`AndroidAddRtlSupportAction` و`InspectPlayPolicyCode` و`ImportApkAction` و`ToggleLayoutInspectorAction`، و`10` تستدعي `Presentation.setText` داخل `update()` مثل `DeviceAndSnapshotComboBox` و`sml.KeepAllChanges` و`GoogleLoginV2.LoginService`).
- اجتازت الدفعتان (`batch_S1_001.tsv` و`batch_S1_002.tsv`) فحص `validate_translations.ps1` بـ `0` أخطاء (مع مجلد مرجعي لنصوص XML `analysis/stage1_ref_props`).
- بُنيت الحزمة `arabic-language-pack-0.3.2.zip` (`163` حزمة، `18,592` مفتاحاً)، واجتازت فحص `AbstractBundleKt._doResolveBundle` و`TextWithMnemonic` و`MessageFormat` ومحاكاة `computeActionText` على `20/20` عينة عشوائية بـ `0` أخطاء مع بقاء الأوامر الـ 4 المكتوبة بالكود دون تغيير.

## 15. المرحلة 2 (`0.4`) — إكمال التلميحات والشروحات واستبعاد المتجاوزة
- أُصلحت الدفعة `batch_L2_022.tsv` (3 مخالفات في `DupLocatorBundle` و`ExternalSystemBundle`) عبر `arabic-translator-v2`، وتمت مراجعة عينة `30` للدفعات المصلحة (`L2_014`, `L2_019`, `L2_022`) وعينة `100` من كامل المستوى 2.
- اجتازت كل الـ `25` دفعة (`2,703` صفاً) فحص `validate_translations.ps1` بـ `0` أخطاء.
- استُبعدت عند التجميع `228` ترجمة تنتمي لـ `18` حزمة متجاوِزة من المرحلة 0 (أكبرها `AndroidBundle` `69` و`AgpUpgradeBundle` `57` و`PreviewBundle` `22`)، وبُنيت `arabic-language-pack-0.4.zip` (`165` حزمة، `21,067` مفتاحاً، `0` أخطاء في JBR).

## 16. المرحلة 3 (`0.5`) — واجهة Gemini والوكيل المدمج واستبعاد الحزم المتجاوزة
- اكتملت كل الـ `13` دفعة في `level3_out` بترجمة `batch_L3_006.tsv` (`110`) و`batch_L3_009.tsv` (`110`) عبر `arabic-translator-v2`.
- عُولجت التنبيهات الـ 6 في `L3_008`/`L3_012`/`L3_013` ومُعرفا التوليد البرمجي في `L3_006`: أُضيفت `5` مفاتيح تقنية ومعرفات دوال (`sml.studiobot.testGeneration.dialog.helpId`, `functionName.text`, `methodName.text`, `aiplugin.onboarding.tier.introBlurb.html.wrapper`, `aiplugin.feedback.dialog.description.prefix.diff.error`) إلى `SLUG_EXCLUDE_KEYS` في `BuildStagedPack.java` كي تبقى إنجليزية، وأُضيفت نصوص الواجهة/الوسوم الصالحة فقط (`Gemini Code Assist`, `Gemini -`, `[MissingFileChange] :`, `studiobot.testGeneration.dialog`, وقالب `%%STYLE%%`) إلى `$AllowedStandaloneEnglish`.
- اجتازت كل الـ `1,271` صفاً القابلة للوصول فحص `validate_translations.ps1` بـ `0` أخطاء، وتمت مراجعة عينة `100` (`analysis/sample_L3_100.tsv`).
- استُبعدت `152` ترجمة للحزم الـ 3 المتجاوِزة (`StudioGeminiBundle` `100`، `IntellijPluginCoreMessageBundle` `27`، `JourneysBundle` `25`) إضافةً إلى `5` مفاتيح تقنية، وبُنيت `arabic-language-pack-0.5.zip` (`179` حزمة فعّالة في الـ JAR، `22,333` مفتاحاً بإضافة `1,266` مفتاحاً جديداً فوق `0.4`، و`0` أخطاء في JBR).

## 17. المرحلة 4 (`0.6`) — تصفية الحزم المتجاوزة والأخطاء التقنية ومراجعة الموجة الأولى (`L4_004`–`L4_006`)
- فُحصت الدفعات `level4_in/batch_L4_004..036.tsv` (`3,620` صفاً) قبل الترجمة:
  - استُبعدت `234` صفاً تنتمي للحزم المتجاوِزة من المرحلة 0 (إضافةً إلى `150` صفاً لـ `AndroidBundle` في `L4_001`–`L4_002` تستبعدها أداة البناء تلقائياً).
  - استُبعد `21` صفاً لأخطاء داخلية/استثناءات محلّلات/متغيرات المصحّح وثوابت `K1`/`K2` (`analysis/stage4_tech_excluded.tsv`)، إضافةً إلى عزم استبعاد `ExecutionBundle|dialog.message.internal.error` من `L4_002` داخل `BuildStagedPack.java`.
  - حُفظت المدخلات المصفّاة (`3,365` صفاً في `33` دفعة) داخل `batches/level4_filtered_in/`.
- تُرجمت الموجة الأولى (`L4_004`, `L4_005`, `L4_006` — `325` صفاً) عبر `arabic-translator-v2` واجتازت الفحص والمطابقة 1-إلى-1 بـ `0` أخطاء، وتمت مراجعة عينة الـ `30` (`analysis/sample_L4_30.tsv`) واعتمادها دون تعديلات.

## 18. المرحلة 4 (`0.6`) — إكمال الحوارات والإشعارات وحالات التشغيل
- اكتملت كل الدفعات من `L4_007` إلى `L4_036` على موجات متوازية (بحد أقصى 6 وكلاء `arabic-translator-v2`)، ليصبح إجمالي الدفعات الـ `36` في `level4_out` هو `3,695` صفاً بـ `0` أخطاء في `validate_translations.ps1 -StrictEnglishCheck` و`0` اختلافات عن المدخلات.
- تمت مراجعة عينة `100` عشوائية (`analysis/sample_L4_100.tsv`)، وأُضيف المفتاح `ExecutionBundle.properties|dialog.message.internal.error` (من `L4_002`) إلى `SLUG_EXCLUDE_KEYS` في `BuildStagedPack.java` ليُستبعد مع الـ `21` مفتاحاً تقنياً المصفاة مسبقاً (`22` مفتاحاً إجمالاً).
- استُبعدت `384` صفاً تنتمي لـ `25` حزمة متجاوِزة من المرحلة 0 (`150` لـ `AndroidBundle` عند التجميع + `234` صُفّيت مسبقاً قبل الترجمة).
- بُنيت الحزمة `arabic-language-pack-0.6.zip` (`601,725` بايت، `183` حزمة فعّالة في الـ JAR، `25,877` مفتاحاً بإضافة `3,544` مفتاحاً جديداً فوق `0.5`)، وتم التحقق من بقاء كل مفاتيح `0.5` (`missingFrom06=0`) واجتياز فحص JBR بـ `0` أخطاء (`verified=25877`).










