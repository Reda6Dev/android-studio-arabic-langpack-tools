# الدليل الهندسي الكامل للعملية (Process & Architecture Guide)

يشرح هذا الدليل المنهجية الكاملة التي أُنشئت بها حزمة اللغة العربية لـ **Android Studio 2026.1.1 (بناء `261.*`)**، بالترتيب الدقيق لكل مرحلة، كي يتمكن أي مطور من فهم التجربة، إكمال ما تبقى، تحديث الحزمة لإصدار أحدث من Android Studio، أو نقل الأداة إلى لغة أخرى.

---

## 1. ما هو المشروع؟ (Overview)
- **الهوية:** حزمة لغة عربية مجتمعية غير رسمية (**Beta**) لبيئة Android Studio (`com.reda6dev.arabic.languagepack`).
- **طريقة الترجمة:** ترجمة سياقية بمساعدة وكيل ذكاء اصطناعي متخصّص (`arabic-translator-v2`) ملتزم بقاموس مصطلحات صارم (`term_glossary.csv`)، مع **فحص آلي شمولي** لكل نص ومراجعة بشرية **بالعينات فقط** (`30` نصاً بعد الموجات الأولى، و`100` نص عند اكتمال كل مرحلة).

---

## 2. كيف تعمل حزم اللغة في المنصة؟ (Platform Mechanics)
1. **نقطة التمديد (`LanguageBundleEP`):** يبحث Android Studio (المبني على IntelliJ Platform) عن امتدادات:
   ```xml
   <extensions defaultExtensionNs="com.intellij">
     <languageBundle locale="ar"/>
   </extensions>
   ```
   ويقرؤها `LocalizationUtil.getAllLanguageBundleExtensions()` دون تقييد برمجي بمعرّف إضافة معيّن (`<id>`).
2. **تحميل الملفات:** عند اختيار `Arabic` (`ar`) في `Language and Region`، يقوم `DynamicBundle` و`AbstractBundleKt._doResolveBundle` بالبحث عبر محمل أصناف الإضافة (`PluginDescriptor.getClassLoader()`) عن ملف مطابق باسم `<baseName>_ar.properties` (مثلاً `messages/ActionsBundle_ar.properties` مقابل `messages/ActionsBundle.properties`).
3. **الترجمة الجزئية (Fallback):** آلية `PropertyResourceBundle` تعيّن ملف الإنجليزية الأصلي كـ `parent` تلقائياً؛ فأي مفتاح غير مترجم في `*_ar.properties` يعود مباشرة للنص الإنجليزي الأصلي دون أخطاء.
4. **التوافق (`since-build` / `until-build`):** تثبيت `since-build="261.0"` و`until-build="261.*"` يحصر التوافق ببناء `2026.1.1` لأن أسماء المفاتيح وصياغاتها تتغير بين الإصدارات الكبرى للمنصة.
5. **المعرّف الداخلي (`<id>`):** استُبدل المعرّف التجريبي السابق `com.intellij.ar.community` بالمعرّف `com.reda6dev.arabic.languagepack` تجنباً للالتباس مع حزم JetBrains الرسمية.

---

## 3. خطوات العملية كاملة مع أوامر التنفيذ (Step-by-Step Pipeline)

### الخطوة أ — استخراج الحزم وتصنيفها (`Phase2Discovery.java`)
تفحص الأداة مجلدي `lib/` و`plugins/` في تثبيت Android Studio المحلي، وتقرأ كل ملفات `*Bundle*.properties` و`messages/*.properties` وتستخرج الإنجليزية إلى `extracted/` وتنتج `discovery.csv` و`bundle_paths.tsv` ودفعات `batches/level*_in/`:
```powershell
javac -encoding UTF-8 -d out tools/Phase2Discovery.java
java -Dfile.encoding=UTF-8 -Dstudio.home="C:\Program Files\Android\Android Studio" -Dwork.dir=".\work" -cp out Phase2Discovery
```
**النطاق وتقسيم المستويات:**
- **المستوى 1 (`0.3` / `0.3.2`):** الأزرار والقوائم والتبويبات وأسماء الإعدادات وعناصر XML (≤ 15 كلمة).
- **المستوى 2 (`0.4`):** التلميحات والشروحات (`.description`, `.tooltip`, `.hint`, `.comment` ≤ 40 كلمة).
- **المستوى 3 (`0.5`):** حزم واجهة Gemini والوكيل المدمج القابلة للوصول عبر `DynamicBundle`.
- **المستوى 4 (`0.6`):** الحوارات والتأكيدات والإشعارات وحالات التشغيل (≤ 40 كلمة، بلا استثناءات/stack traces).

### الخطوة ب — المرحلة 0: فحص إمكانية الوصول عبر Bytecode (`AnalyzeBundleReachability.java`)
قبل ترجمة المراحل 2–4، تتحقق الأداة من كلاسات الجافا التي تقرأ كل `.properties`: هل ترث من `com.intellij.DynamicBundle` / `AbstractBundle` أم تتجاوزها باستدعاء `java.util.ResourceBundle.getBundle(...)` مباشرةً أو `MessageBundleReference`؟
```powershell
javac -encoding UTF-8 -d out tools/AnalyzeBundleReachability.java
java -Dfile.encoding=UTF-8 -Dstudio.home="C:\Program Files\Android\Android Studio" -Dwork.dir=".\work" -cp out AnalyzeBundleReachability
```
- **النتيجة الموثقة (`bundle_reachability.csv`):** من `191` حزمة مدققة: `144` `DYNAMIC_BUNDLE` و`4` `ABSTRACT_BUNDLE_ONLY` و`7` حُسبت عاملة، مقابل **`36` حزمة تتجاوز حزمة اللغة** (`15` عبر `ResourceBundle.getBundle` و`21` عبر `MessageBundleReference`) — وُفّر بذلك ترجمة `764` مفتاحاً لا يمكن أن تظهر مترجمة في الواجهة.

### الخطوة ج — المرحلة 1: تصفية أوامر XML لإضافات Android/Google (`FilterStage1Actions.java`)
تفكّك الأداة كلاسات الأوامر في `plugins/android`, `design-tools`, `gemini` عبر `javap -c -p` (مع كلاساتها الأب) لفحص XML attributes (`internal="true"`) واكتشاف الأوامر التي تمرر نصاً ثابتاً للـ `constructor` أو تستدعي `Presentation.setText` داخل `update()`:
```powershell
javac -encoding UTF-8 -d out tools/FilterStage1Actions.java
java -Dfile.encoding=UTF-8 -Dstudio.home="C:\Program Files\Android\Android Studio" -Dwork.dir=".\work" -cp out FilterStage1Actions
```

### الخطوة د — الترجمة بالوكلاء والقاموس (`arabic-translator-v2`)
تُمرر دفعات `batches/*_in/*.tsv` (بمعدل ~100–110 صفوف لكل دفعة، وبحد أقصى 6 وكلاء بالتوازي لتفادي نفاد الحصة وتسجيل التقدم في `progress.json` بعد كل موجة) إلى وكيل الترجمة الموصوف في [docs/prompts/arabic-translator-v2.md](prompts/arabic-translator-v2.md) وفق [docs/term_glossary.csv](term_glossary.csv)، ثم تُراجع عينة `30` صفاً بعد الموجة الأولى وعينة `100` صف عند إتمام المرحلة.

### الخطوة هـ — التحقق الآلي الشامل (`validate_translations.ps1`)
```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools\validate_translations.ps1 `
  -InputPath .\work\batches\level4_out `
  -ExtractedDirs .\work\extracted -StrictEnglishCheck
```
**لماذا يوجد كل فحص في هذا السكربت؟**
1. **المعاملات `{0}`, `{1}` و`{0,choice,...}` و`%s`:** منع تعطل `MessageFormat` أو `String.format` في الجافا وقت التشغيل.
2. **أزواج علامات التنصيص `'` و`''{0}''`:** لأن الفاصلة المفردة `'` تهرب التنسيق في `MessageFormat` فتُخفي النص أو تكسره.
3. **عدد أسطر `\n` ومطابقة المسافات البادئة واللاحقة:** لضمان اتساق التنسيق داخل الحوارات والمحرر.
4. **علامات الاختصار (`&X` و`_X` و`Get-MnemonicInfo`):** الحفاظ على نفس حرف الاختصار في لوحة المفاتيح بصيغة `(&X)`.
5. **الوسوم `<html>`, `<b>`, `<a href="...">`:** منع كسر الروابط والألوان داخل JBR Swing HTML.
6. **القواعد اللغوية والاصطلاحية:** منع الأقواس الإنجليزية `(Commit)`، ومنع المسافة بعد حرف العطف `و`، وتصحيح التاء المربوطة `ة` بدل `ه`، ومنع ترجمة `Shelf` إلى `رف`، وإلزام ترجمة `Update` إلى `تحديث`.

### الخطوة و — البناء والتحقق ببيئة البرنامج (`BuildStagedPack.java` و`BuildPackFromSources.java`)
- **البناء المرحلي المتدرج (عند إعادة بناء المراحل 1–4 من دفعات TSV):**
```powershell
javac -encoding UTF-8 -d out tools/BuildStagedPack.java
java -Dfile.encoding=UTF-8 -Duser.language=en -Duser.country=US `
  -Dstudio.home="C:\Program Files\Android\Android Studio" -Dwork.dir=".\work" -cp out BuildStagedPack 0.6
```
- **البناء السريع من ملفات `*_ar.properties` الجاهزة:**
```powershell
javac -encoding UTF-8 -d out tools/BuildPackFromSources.java
java -Dfile.encoding=UTF-8 -Duser.language=en -Duser.country=US `
  -Dmessages.dir=".\messages" -Dplugin.xml=".\plugin\META-INF\plugin.xml" `
  -Dstudio.home="C:\Program Files\Android\Android Studio" -cp out BuildPackFromSources
```
يقوم كلاهما بفحص **BOM=0** و**duplicateKeys=0**، ثم يحمّلان كل حزمة عبر `AbstractBundleKt._doResolveBundle(cl, ar, bundleName)` ويمرران كل قيمة على `TextWithMnemonic.parse` و`new MessageFormat(..., ar)`؛ كما يقيس `BuildStagedPack` في المرحلة 1 محاكاة `computeActionText` على 20 أمراً عشوائياً.

---

## 4. قواعد الترجمة والمصطلحات وأسبابها (`decisions.md` و`term_glossary.csv`)
- **عدم وضع المصطلح الإنجليزي بين قوسين (`اعتماد (Commit)` ❌):** يربك اتجاه القراءة في Swing RTL؛ البديل: دمج المصطلح الأجنبي مباشرةً داخل الجملة (`إجراء Commit`) أو ترجمته بالكامل (`إعدادات`, `تحديث`, `بناء`).
- **إبقاء أفعال Git القياسية بالإنجليزية (`Commit, Push, Pull, Fetch, Merge, Rebase, Stash, Checkout, Shelf, Cherry-Pick`):** لأن المطور العربي يبحث عنها بمسمياتها في Git، ولأن `Shelf` بالذات لو تُرجم إلى `الرف` لأصبح مبهماً في بيئة التطوير.
- **إبقاء الكلمات البرمجية المفتاحية بالإنجليزية (`transient`, `final`, `sealed`, `suspend`, `var`...):** لأنها مفاتيح لغة برمجة تظهر في إعدادات `Code Style` ولا يجوز تعريبها (`ليس transient`).
- **توحيد `Update` إلى `تحديث`، و`Settings` إلى `إعدادات`، و`Configuration` إلى `تكوين`، والأقواس الثلاثة (`{}` المعقوفة / `[]` المربعة / `()` الدائرية) و`CamelHumps`:** لضمان اتساق كل شاشات IDE بلا تشتيت.

---

## 5. الدروس المستفادة (Lessons Learned)
1. **حدود آلية Language Pack:** لا تستطيع أي حزمة لغة تعريب:
   - نصوص مكتوبة حرفياً داخل Java/Kotlin (`205` عنصر XML بلا نص/مفتاح، `45` أمراً بالـ constructor/`update()`، `4` أوامر bytecode مثل `GenerateSignedApk` و`DeviceManager2` و`RunAndroidSdkManager` و`RunLayoutInspector`).
   - نصوص XML بدون `<resource-bundle>` (`5` أوامر + `11` صفحة إعدادات عبر `displayName="..."`).
   - الحزم الـ `36` التي تستدعي `ResourceBundle.getBundle` مباشرةً متجاوزةً محمّل `DynamicBundle`.
2. **حيلة حقن مفاتيح `action.<id>.text`:** اكتُشف وتُحقق منه عملياً (`0.3.1` و`0.3.2`) أن `ActionManagerImpl` يبحث عن `action.<id>.text` في الـ `<resource-bundle>` الفعّال حتى لو لم يكن المفتاح موجوداً في الملف الإنجليزي الأصلي؛ مما أتاح تعريب **`103` أوامر** إضافية كانت مكتوبةً في XML الإنجليزي (`AndroidBundle`, `AndroidDesignerActionsBundle`, `SmlBundle`, `SmlIjBundle`).
3. **مزالق المنصة واتجاه النص (RTL / BiDi / PowerShell):**
   - ملفات `.properties` لا يُقرأ فيها BOM بشكل آمن، بينما **سكربتات `.ps1` في PowerShell 5.1 تتطلب UTF-8 WITH BOM** لتفادي تحريف النصوص العربية داخل السكربت كـ Windows-1252.
   - يجب الحفاظ على حروف `Mnemonic` الأصلية بصيغة `(&F)` وإلا تعطّلت اختصارات لوحة المفاتيح أو انعكس حرف الاختصار.
4. **لماذا يفشل أسلوب القاموس/الاستبدال البرمجي مقارنةً بالترجمة السياقية؟** في المرحلة 1 ظهرت تشوهات مثل `تغييرات عرض` بدل `عرض التغييرات` و`فحص لـ تحديثات` عند الاعتماد على قواعد آلية أو قواميس؛ كما يعجز الاستبدال الآلي عن ضبط المثنى والجمع في `ChoiceFormat` (`{0,choice,1#...|2#...}`) أو التمييز بين المعنى البرمجي (`Object`/`Class`) والمعنى الإجرائي.

---

## 6. ما استُبعد من النطاق وأسبابه بالأرقام (من `final_report.md`)
من إجمالي **`47,873` مفتاحاً** في `365` حزمة:
1. **مستبعدات الاكتشاف (`21,320` مفتاحاً):**
   - `PROG_LANG` (`10,777` مفتاحاً عبر `113` حزمة): أشجار النحو ورسائل المحللات البرمجية للغات.
   - `INSPECTIONS` (`4,414` مفتاحاً عبر `13` حزمة): فحوصات الكود التحليلية التقنية.
   - `TECHNICAL` (`2,943` مفتاحاً عبر `49` حزمة): مفاتيح داخلية وبروتوكولات لا تمس واجهة المستخدم الاعتيادية.
   - مستبعدات أخرى في `UI_GENERAL`/`SETTINGS`/`GEMINI_AI` (`3,186` مفتاحاً = `2,962 + 197 + 27`): الشروحات الأطول من 40 كلمة والأخطاء المنخفضة المستوى (`parse`/`IO`/`JVM`/`socket`).
2. **الحزم المتجاوزة لحزمة اللغة (`36` حزمة / `764` مفتاحاً في L2–L4):** (`L2=228`, `L3=152`, `L4=384`) لأنها تتجاوز `DynamicBundle`.
3. **عناصر XML والمفاتيح التقنية/الأخطاء المستثناة في المراحل 1–4:** `84` مرشحاً في المرحلة 1 (`12` في `0.3.1` + `27` `INTERNAL_DEBUG_RAG` + `35` constructor + `10` `update()`), `5` مفاتيح تقنية/معرفات دوال في المرحلة 3 (`helpId`, `functionName`, `methodName`, `html.wrapper`, `prefix.diff.error`), و`22` مفتاح أخطاء داخلية واستثناءات/K1-K2 في المرحلة 4 + رابطا `GPG`.
4. **غير مترجم بطبيعته في المنصة:** `205` عناصر XML بلا نص/مفتاح + `5` أوامر XML بلا bundle + `11` عنوان إعدادات `displayName`.

---

## 7. الإكمال والتحديث والنقل إلى لغة أخرى

### أ) كيف يكمل شخص ما تبقى؟
- يمكن توسيع النطاق ليشمل نصوصاً أطول من 40 كلمة (`excl`) أو فحوصات `INSPECTIONS` عبر تعديل شروط `classifyKeyLevel` و`classifyBundle` في `Phase2Discovery.java` ثم إعادة التوليد والترجمة والتحقق.
- أما الأجزاء التي تتجاوز `DynamicBundle` أو المكتوبة في الكود فتتطلب المساهمة في مستودع AOSP / IntelliJ Community نفسه لنقلها إلى `DynamicBundle` ومفاتيح `.properties`.

### ب) كيف يُحدَّث لإصدار جديد من Android Studio (مثلاً `262.*`)؟
1. شغّل `Phase2Discovery` و`AnalyzeBundleReachability` على مجلد عمل جديد موجهاً `-Dstudio.home` نحو التثبيت الجديد.
2. قارن ملفات `extracted/*.properties` الجديدة مع `*_ar.properties` الحالية: احتفظ بالمفاتيح التي لم يتغير نصها الإنجليزي، واستخرج المفاتيح الجديدة أو المتغيرة فقط في دفعات `TSV`.
3. ترجم دفعة الفرق فقط via `arabic-translator-v2` + `validate_translations.ps1`.
4. حدّث `since-build` و`until-build` و`<version>` في `plugin/META-INF/plugin.xml` وابنِ وتحقق عبر `BuildPackFromSources.java`.

### ج) كيف تُنقل الطريقة إلى لغة أخرى (مثلاً التركية أو الفرنسية أو الأردية)؟
1. في `plugin/META-INF/plugin.xml` غيّر `<languageBundle locale="tr"/>` (أو رمز اللغة المستهدفة).
2. عدّل لاحقة `_ar.properties` في `BuildPackFromSources` / `BuildStagedPack` إلى `_<locale>.properties`.
3. بدّل قواعد التحقق اللغوية في `validate_translations.ps1` (إزالة فحوصات `ة`/`و` العربية وإضافة قواعد اللغة الجديدة وقاموس مصطلحاتها).
4. أبقِ `Phase2Discovery`, `AnalyzeBundleReachability`, و`FilterStage1Actions` كما هي لأن استخراج الباندلات وفحص الـ bytecode مستقلان تماماً عن اللغة المستهدفة.

---

## 8. البناء والتثبيت والإزالة
راجع `README.md` / `README.ar.md` لأوامر `BuildPackFromSources` وخطوات التثبيت عبر `Install Plugin from Disk...` وإزالة الطوارئ اليدوية من مجلد `plugins`.

## 9. القيود المعروفة وإخلاء المسؤولية
- هذا العمل مجتمعي غير رسمي (Beta) بمساعدة الذكاء الاصطناعي ومراجعة العينات.
- **Android Studio** و**Gemini** علامتان تجاريتان لشركة **Google LLC**، و**IntelliJ** و**IntelliJ IDEA** علامتان تجاريتان لشركة **JetBrains s.r.o.**، ولا يتبع المشروع أياً منهما. السكربتات مرخصة تحت [MIT](LICENSE) والترجمات خاضعة لإشعار [NOTICE](NOTICE).
