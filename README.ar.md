# أداة حزمة اللغة العربية لـ Android Studio (`261.*`)

[English README](README.md) | [الدليل التقني الكامل (`docs/PROCESS.md`)](docs/PROCESS.md)

> **مشروع مجتمعي غير رسمي (نسخة تجريبية Beta)** — الترجمات الناتجة أُعدّت بمساعدة الذكاء الاصطناعي (`arabic-translator-v2`) ورِجعت **بالعينات فقط**.

هذه الأداة هي خط العمل الكامل لاستخراج حزم نصوص واجهة **Android Studio 2026.1.1 (بناء `261.*`)**، وفحص إمكانية وصولها إلى حزم اللغة عبر الـ bytecode، وتصفية الأوامر المكتوبة في XML مقابل الكود، والتحقق الآلي من جودة الترجمة وسلامة المتغيرات، ثم بناء الحزمة وفحص تشغيلها ببيئة جافا المدمجة (JBR).

المستودع يحتوي على **السكربتات والأدلة والقواميس فقط** — بلا أي ملفات `.jar` وبلا نصوص إنجليزية مستخرجة من البرنامج وبلا مسارات شخصية، حيث تستخرج الأداة كل شيء مباشرةً من نسخة Android Studio المثبتة على جهاز المستخدم.

---

## أبرز الأرقام الموثقة (من `docs/final_report.md`)

- **إجمالي المفاتيح المفحوصة:** `47,873` مفتاحاً عبر `365` حزمة `.properties`.
- **الإصدار الكامل (`v0.6`):** `183` حزمة فعّالة، **`25,877` مفتاحاً مترجماً**، `0` أخطاء BOM/تكرار/JBR.
- **مسار التطور التراكمي:** `0.1` (`3,024`) ← `0.2` (`7,669`) ← `0.3` (`18,477`) ← `0.3.1` (`18,489`) ← `0.3.2` (`18,592`) ← `0.4` (`21,067`) ← `0.5` (`22,333`) ← `0.6` (`25,877`).
- **المستبعد في المرحلة 0 (تجاوز الـ `DynamicBundle`):** `36` حزمة (`764` مفتاحاً عبر المراحل `2`–`4`).
- **المستبعد بالسياسة في الاكتشاف:** `21,320` مفتاحاً (`PROG_LANG`: `10,777`، `INSPECTIONS`: `4,414`، `TECHNICAL`: `2,943`، شروحات/أخطاء >40 كلمة: `3,186`).

---

## مكونات المستودع

| المسار | الوظيفة |
| :--- | :--- |
| `tools/Phase2Discovery.java` | استخراج الحزم من تثبيت Android Studio، وتصنيفها، وتوليد ملفات الدفعات المبدئية |
| `tools/AnalyzeBundleReachability.java` | أداة المرحلة 0 لفحص الـ bytecode وتحديد الحزم المتجاوزة لحزمة اللغة (`bundle_reachability.csv`) |
| `tools/FilterStage1Actions.java` | أداة المرحلة 1 لتفكيك أوامر XML عبر `javap` وعزل ما يُدار من الكود أو الداخلي |
| `tools/validate_translations.ps1` | أداة الفحص اللغوي والتقني الشامل لملفات الدفعات |
| `tools/BuildStagedPack.java` | أداة التجميع المرحلي لبناء حزمة متدرجة مع تطبيق استثناءات المرحلة 0 وفحص JBR |
| `tools/BuildPackFromSources.java` | أداة البناء المستقلة لتجميع مجلد `messages/` مع `plugin/META-INF/plugin.xml` إلى `.zip` |
| `plugin/META-INF/plugin.xml` | واصف الإضافة الرسمي بالمعرّف الجديد `com.reda6dev.arabic.languagepack` (`261.0` – `261.*`) |
| `docs/PROCESS.md` | الدليل المنهجي المفصل ذو الـ 9 محاور لفهم العملية وإعادتها أو تحديثها أو نقلها للغة أخرى |
| `docs/decisions.md` | سجل القرارات الهندسية واللغوية (الأقسام `1` إلى `18`) |
| `docs/term_glossary.csv` | قاموس المصطلحات المعتمد (`81` مصطلحاً وقواعده) |
| `docs/final_report.md` | التقرير النهائي وجرد ما بقي بالإنجليزية وأسبابه بالأرقام |
| `docs/license_inventory.csv` | جرد التراخيص الفعلي لجميع الحزم الـ `183` في `0.6` |
| `docs/prompts/` | تعريف وكيل الترجمة `arabic-translator-v2` والقوالب والأوامر المعتمدة |

---

## الاستخدام السريع

### 1. بناء ملف الإضافة (`.zip`) مباشرةً من مجلد `*_ar.properties`
```powershell
javac -encoding UTF-8 -d out tools/BuildPackFromSources.java
java -Dfile.encoding=UTF-8 -Duser.language=en -Duser.country=US `
  -Dmessages.dir="PATH_TO\messages" `
  -Dplugin.xml="plugin\META-INF\plugin.xml" `
  -Dstudio.home="C:\Program Files\Android\Android Studio" `
  -cp out BuildPackFromSources build\arabic-language-pack-0.6.zip
```

### 2. استخراج الحزم وفحصها على مجلد عمل جديد (`./work`)
```powershell
javac -encoding UTF-8 -d out tools/Phase2Discovery.java tools/AnalyzeBundleReachability.java
java -Dfile.encoding=UTF-8 -Dstudio.home="C:\Program Files\Android\Android Studio" -Dwork.dir=".\work" -cp out Phase2Discovery
java -Dfile.encoding=UTF-8 -Dstudio.home="C:\Program Files\Android\Android Studio" -Dwork.dir=".\work" -cp out AnalyzeBundleReachability
powershell -NoProfile -ExecutionPolicy Bypass -File tools\validate_translations.ps1 -InputPath .\work\batch.tsv -ExtractedDirs .\work\extracted -StrictEnglishCheck
```

---

## التثبيت والترقية والطوارئ
- **التوافق:** Android Studio `2026.1.1` (بناء `261.*`) فقط.
- **ملاحظة ترقية مهمة:** احذف أي نسخة مثبتة مسبقاً بالمعرّف القديم `com.intellij.ar.community` وأعد التشغيل قبل تثبيت المعرّف الجديد `com.reda6dev.arabic.languagepack`.
- **التثبيت:** `Settings > Plugins > Gear icon (⚙) > Install Plugin from Disk...` ← اختر `.zip` ← أعد التشغيل ← من `Language and Region` اختر **Arabic** ← أعد التشغيل.
- **الإزالة اليدوية للطوارئ:** أغلق البرنامج واحذف مجلد `arabic-language-pack-*` من مجلد `plugins` (`%APPDATA%\Google\AndroidStudio2026.1.1\plugins\` في Windows).

---

## القيود وإخلاء المسؤولية
- الأوامر المكتوبة في الكود (`205` بلا نص/مفتاح، `45` بالـ constructor/`update()`، `4` بالـ bytecode)، ونصوص XML بلا `resource-bundle` (`5` أوامر و`11` صفحة إعدادات)، والحزم الـ `36` المتجاوزة لـ `DynamicBundle` تبقى بالإنجليزية.
- **إخلاء مسؤولية:** **Android Studio** و**Gemini** علامتان تجاريتان لشركة **Google LLC**، و**IntelliJ** و**IntelliJ IDEA** علامتان تجاريتان لشركة **JetBrains s.r.o.**. هذا المشروع مجتمعي مستقل (`https://github.com/Reda6Dev`) ولا يتبع أياً منهما. السكربتات تحت `tools/` مرخصة بـ MIT ([LICENSE](LICENSE))، وانظر [NOTICE](NOTICE) بخصوص وضع الترخيص القانوني للنصوص المشتقة.
