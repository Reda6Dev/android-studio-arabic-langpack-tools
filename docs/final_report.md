# التقرير النهائي لحزمة اللغة العربية لـ Android Studio (`261.*`)

اكتمل بناء حزمة اللغة العربية لـ Android Studio حتى الإصدار **`arabic-language-pack-0.6.zip`** فوق `0.3.1`، مع التحقق التلقائي من صحة النصوص والمتغيرات والصيغ الشرطية وفحص التشغيل الفعلي عبر بيئة جافا المدمجة في البرنامج (JBR `DynamicBundle` و`TextWithMnemonic` و`MessageFormat`)، دون أي تعديل على تثبيت Android Studio أو مجلد إعدادات المستخدم أو أي مستودع Git محلي غير ذي صلة.

---

## 1. تطور الحزمة عبر الإصدارات

| الإصدار | الملف | الحجم (بايت) | عدد الحزم الفعّالة في الـ JAR | عدد المفاتيح | ما أُضيف / تُحقق منه |
| :--- | :--- | ---: | ---: | ---: | :--- |
| التجريبي | `arabic-language-pack-poc.zip` | 1,529 | 1 | 15 | إثبات صلاحية آلية `com.intellij.languageBundle` |
| `0.1` | `arabic-language-pack-0.1.zip` | 46,835 | 7 | 3,024 | الأوامر والعمليات الأساسية والقاموس الثابت |
| `0.2` | `arabic-language-pack-0.2.zip` | 143,062 | 23 | 7,669 | الواجهة الأساسية وصفحات الإعدادات |
| `0.3` | `arabic-language-pack-0.3.zip` | 393,414 | 161 | 18,477 | المستوى 1 (الأزرار والقوائم والتبويبات) + 72 إصلاحاً على `0.2` |
| `0.3.1` | `arabic-language-pack-0.3.1.zip` | 393,972 | 162 | 18,489 | تجربة حقن مفاتيح `action.<id>.text` لعناصر XML |
| `0.3.2` | `arabic-language-pack-0.3.2.zip` | 398,308 | 163 | 18,592 | **المرحلة 1:** `+103` أوامر وقوائم أندرويد من `XML`، `0` أخطاء JBR |
| `0.4` | `arabic-language-pack-0.4.zip` | 462,791 | 165 | 21,067 | **المرحلة 2:** `+2,475` من التلميحات والشروحات، `0` أخطاء JBR |
| `0.5` | `arabic-language-pack-0.5.zip` | 497,980 | 179 | 22,333 | **المرحلة 3:** `+1,266` من واجهة Gemini والوكيل المدمج، `0` أخطاء JBR |
| **`0.6`** | **`arabic-language-pack-0.6.zip`** | **601,725** | **183** | **25,877** | **المرحلة 4:** `+3,544` من الحوارات والإشعارات وحالات التشغيل، `0` أخطاء JBR |

---

## 2. لماذا تبقى بعض الأجزاء بالإنجليزية؟ (جرد شامل بالأرقام والأسباب)

من إجمالي `47,873` مفتاحاً فُحصت في `365` حزمة ضمن `discovery.csv`، تبقى النصوص غير المترجمة بالإنجليزية لأحد **4 أسباب تقنية أو تنظيمية محددة**:

### السبب 1: نصوص مكتوبة مباشرة في الكود البرمجي (Java / Kotlin)
لا تقرأ هذه النصوص من ملفات `.properties` ولا يستطيع أي Language Pack خارجي الوصول إليها بدون تعديل شيفرة Android Studio نفسها:
- **في إضافات Android وGoogle (`436` عنصر `<action>` و`<group>` في `android_xml_actions_breakdown.csv`):**
  - `205` عناصر بلا نص `text` ثابت في XML ولا مفتاح مقابل في الحزمة (`NO_XML_TEXT_NO_KEY_IN_ACTIVE_BUNDLE`).
  - `45` أمراً من فحص `FilterStage1Actions` (`stage1_xml_actions_filter.csv`): منها `35` تمرر النص مباشرة إلى constructor الأب (`CODE_CTOR_NONEMPTY_TEXT` مثل `ZoomInAction`, `AndroidAddRtlSupportAction`, `InspectPlayPolicyCode`, `ImportApkAction`, `ToggleLayoutInspectorAction`) و`10` تستدعي `Presentation.setText` داخل `update()` (`CODE_UPDATE_SET_TEXT` مثل `DeviceAndSnapshotComboBox`, `sml.KeepAllChanges`, `GoogleLoginV2.LoginService`).
  - الأوامر الـ 4 الخاصة بالـ bytecode التي أكدت `0.3.1` أن الكود يتغلب على XML فيها (`Android.GenerateSignedApk`, `Android.DeviceManager2`, `Android.RunAndroidSdkManager`, `Android.RunLayoutInspector`) أُبقيت دون تعديل.
- **نصوص داخلية في واجهة Gemini واللوحات التفاعلية الحديثة (Compose/Swing):** أي نص مكتوب حرفياً في الكود دون مفتاح في حزم `SmlBundle` أو `SmlIjBundle` أو `V2Bundle` يبقى بالإنجليزية.

### السبب 2: نصوص مكتوبة في ملفات XML بدون `<resource-bundle>`
تحتوي بعض عناصر الـ XML على `text="..."` أو `displayName="..."` مباشرة دون ربطها بحزمة نصوص فعّالة، فلا يمررها محرك IntelliJ إلى حزم اللغة:
- **5 أوامر في إضافات Google/Testing (`android_xml_actions_breakdown.csv`):**
  1. `DeveloperServices.Firebase` (`Firebase`)
  2. `DeveloperServices.TargetSDKVersionUpgradeAssistant` (`Android SDK Upgrade Assistant`)
  3. `GoogleCloudTesting.TestRecorder` (`Record Espresso Test`)
  4. `GoogleCloudTesting.RoboScriptRecorder` (`Record Robo Script`)
  5. `DeveloperServices.UrlAssistant` (`App Links Assistant`)
- **11 صفحة/مزوّد إعدادات بعناوين ثابتة (`HARDCODED_DISPLAY_NAME` في `settings_pages_coverage.csv`):**
  - `Memory Settings` (`memory.settings`), `Experimental` (`experimental`), `Android (Experimental)` (`experimentalPlugin`), `Google Accounts` (`GoogleLoginApplicationSettingsConfigurableProvider`), `Android SDK` (`AndroidSdkUpdater` - مدخلان), `Studio Labs` (`studioLabs`), `Gradle Declarative Focus` (`DeclarativeFocus`), `Communications` (`MarketingConfigurableProvider`), `Espresso Test Recorder` (`test.recorder`), `GitLab` (`vcs-gitlab`).
- كما تبيّن أن `9` عناصر إضافية بلا نص XML ولا حزمة (`NO_XML_TEXT_NO_BUNDLE`).

### السبب 3: `36` حزمة تتجاوز آلية حزمة اللغة (المرحلة 0 — `bundle_reachability.csv`)
في تدقيق المرحلة 0 لـ `191` حزمة: تعمل `144` عبر `DynamicBundle` و`4` عبر `AbstractBundle` و`7` حُسبت عاملة؛ بينما **تتجاوز `36` حزمة** حزمة اللغة نهائياً عبر استدعاء `java.util.ResourceBundle.getBundle` مباشرة (`15` حزمة) أو `MessageBundleReference` (`21` حزمة).

بناءً على هذا التحقق، استُبعدت ترجمات جديدة لهذه الحزم في المراحل 2 و3 و4 (مع الإبقاء فقط على مفاتيح `action.<id>.text` في المرحلة 1 لأن `ActionManagerImpl` يقرأها عبر `DynamicBundle`):

- **المستبعد في المرحلة 2 (`0.4`):** `228` مفتاحاً.
- **المستبعد في المرحلة 3 (`0.5`):** `152` مفتاحاً (`StudioGeminiBundle`: `100`، `IntellijPluginCoreMessageBundle`: `27`، `JourneysBundle`: `25`).
- **المستبعد في المرحلة 4 (`0.6`):** `384` مفتاحاً (`150` من `AndroidBundle` استُبعدت عند البناء من `L4_001`–`L4_002` + `234` صُفّيت مسبقاً من `L4_004`–`L4_036`).
- **الإجمالي المستبعد بسبب التجاوز في المراحل 2–4:** **`764` مفتاحاً** عبر `36` حزمة، وأبرزها حسب عدد المفاتيح:

| الحزمة | آلية التجاوز | المرحلة | المفاتيح المتأثرة (L2 / L3 / L4) |
| :--- | :--- | :--- | ---: |
| `AndroidBundle.properties` | `BYPASSES_DIRECT_RESOURCE_BUNDLE` | `UI_GENERAL` | `69 / 0 / 150` (`219`) |
| `StudioGeminiBundle.properties` | `BYPASSES_DIRECT_RESOURCE_BUNDLE` | `GEMINI_AI` | `0 / 100 / 0` (`100`) |
| `AgpUpgradeBundle.properties` | `BYPASSES_DIRECT_RESOURCE_BUNDLE` | `UI_GENERAL` | `57 / 0 / 14` (`71`) |
| `AndroidWearPairingBundle.properties` | `BYPASSES_DIRECT_RESOURCE_BUNDLE` | `UI_GENERAL` | `14 / 0 / 28` (`42`) |
| `PreviewBundle.properties` | `BYPASSES_MESSAGE_BUNDLE_REFERENCE` | `UI_GENERAL` | `22 / 0 / 18` (`40`) |
| `LogcatBundle.properties` | `BYPASSES_MESSAGE_BUNDLE_REFERENCE` | `UI_GENERAL` | `9 / 0 / 24` (`33`) |
| `LayoutInspectorBundle.properties` | `BYPASSES_MESSAGE_BUNDLE_REFERENCE` | `UI_GENERAL` | `4 / 0 / 26` (`30`) |
| `FirebaseBundle.properties` | `BYPASSES_DIRECT_RESOURCE_BUNDLE` | `UI_GENERAL` | `2 / 0 / 27` (`29`) |
| `IntellijPluginCoreMessageBundle.properties` | `BYPASSES_DIRECT_RESOURCE_BUNDLE` | `GEMINI_AI` | `0 / 27 / 0` (`27`) |
| `JourneysBundle.properties` | `BYPASSES_DIRECT_RESOURCE_BUNDLE` | `GEMINI_AI` | `0 / 25 / 0` (`25`) |
| بقية الـ `26` حزمة (`BackupBundle`, `AndroidAdbUiBundle`, `DatabaseInspectorBundle`, إلخ) | كلاهما | `UI_GENERAL` | `51 / 0 / 97` (`148`) |

*(ملاحظة: القائمة الكاملة للـ `36` حزمة موثقة في `bundle_reachability.csv`).*

### السبب 4: عناصر مستبعدة بالسياسة حفاظاً على الدقة البرمجية ووضوح التعليمات
1. **الفئات البرمجية والفحص الآلي في الاكتشاف الشامل (`discovery.csv`) — إجمالي `21,320` مفتاحاً مستبعداً:**
   - **لغات البرمجة وأشجار النحو (`PROG_LANG`):** `10,777` مفتاحاً عبر `113` حزمة (من أصل `10,948`).
   - **فحوصات الشيفرة (`INSPECTIONS`):** `4,414` مفتاحاً عبر `13` حزمة (أسماء وفحوصات الكود البرمجي).
   - **ملفات تقنية بحتة (`TECHNICAL`):** `2,943` مفتاحاً عبر `49` حزمة.
   - **مستبعدات أخرى في `UI_GENERAL` و`SETTINGS` و`GEMINI_AI`:** `3,186` مفتاحاً (`2,962 + 197 + 27`)، وتشمل النصوص الطويلة جداً (`> 40` كلمة) وأخطاء البرمجة/الإعراب/IO/JVM والاستثناءات الخام.
2. **الأخطاء الداخلية والاستثناءات ومفاتيح المطورين في مراحل التنفيذ:**
   - **المرحلة 1 (`0.3.2`):** `27` أمراً داخلياً/اختبارياً/تصحيحياً (`INTERNAL_DEBUG_RAG` مثل `WebpSupportTestAction`, `DeadlockUIThread`, `RunNewProjectAgentAction`).
   - **المرحلة 3 (`0.5`):** `5` مفاتيح تقنية ومعرفات دوال (`sml.studiobot.testGeneration.dialog.helpId`, `functionName.text`, `methodName.text`, `aiplugin.onboarding.tier.introBlurb.html.wrapper`, `aiplugin.feedback.dialog.description.prefix.diff.error`) في `SLUG_EXCLUDE_KEYS`.
   - **المرحلة 4 (`0.6`):** `22` مفتاحاً لأخطاء داخلية واستثناءات محللات الأوامر وثوابت المصحّح وأوضاع `K1`/`K2` (`21` في `stage4_tech_excluded.tsv` + `ExecutionBundle.properties|dialog.message.internal.error`).
   - **الروابط التقنية:** `GitBundle.properties|gpg.jb.manual.link` و`gpg.pinentry.jb.manual.link`.
3. **المصطلحات الإنجليزية المبقّاة عمداً داخل الجمل العربية:** مثل `Git, Commit, Push, Pull, Rebase, Stash, Shelf, Gradle, Kotlin, Java, SDK, JDK, JRE, APK, ADB, Logcat, Debugger, Diff, Gemini, Agent, MCP` وكذلك كلمات `Keywords` البرمجية لأن بقاءها كما هي أوضح للمطور.

---

## 3. خلاصة التحقق والسلامة النهائية
- **التحقق البرمجي لحزمة `0.6`:** اجتازت `25,877` ترجمة عبر `183` ملف `.properties` داخل الـ JAR فحوصات `JAR Integrity` (`bomFiles=0`, `dupKeys=0`) و`JBR Runtime Verification` (`verified=25877`, `errors=0`) وبقاء جميع مفاتيح `0.5` (`missingFrom06=0`).
- **سلامة البيئة:** لم يُعدَّل أي ملف في تثبيت Android Studio ولا مجلد إعداداته ولا أي مستودع Git محلي (`git status --short` فارغ)، وظلت جميع الإصدارات السابقة (`poc`, `0.1`, `0.2`, `0.3`, `0.3.1`, `0.3.2`, `0.4`, `0.5`, `0.6`) محفوظة كاملة.
