<#
.SYNOPSIS
    Inputs:  -InputPath <TSV/CSV file or dir>, -ExtractedDirs <extracted English .properties dirs>, [-StrictEnglishCheck]
    Outputs: Validation report (stdout) + exit code 0 (PASS) or 1 (errors)
    Validates Arabic UI translation batches (TSV/CSV) against original extracted English bundles.
    أداة التحقق التلقائي من صحة الترجمات العربية (المتغيرات، MessageFormat، علامات الاختصار، XML/HTML، قواعد التسمية).

.EXAMPLE
    .\validate_translations.ps1 -InputPath .\work\batch.tsv -ExtractedDirs .\work\extracted -StrictEnglishCheck
#>
param(
    [string]$InputPath = "",
    [string[]]$ExtractedDirs = @(
        (Join-Path $PSScriptRoot "..\work\extracted")
    ),
    [switch]$StrictEnglishCheck
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

if ([string]::IsNullOrWhiteSpace($InputPath)) {
    Write-Host "Usage: validate_translations.ps1 -InputPath <file_or_dir> [-ExtractedDirs <dir1,dir2>] [-StrictEnglishCheck]"
    exit 0
}
if (-not (Test-Path $InputPath)) {
    Write-Error "Input path not found: $InputPath"
    exit 1
}

function Unescape-Prop([string]$s) {
    $sb = New-Object System.Text.StringBuilder
    $i = 0
    while ($i -lt $s.Length) {
        $c = $s[$i]
        if ($c -eq '\' -and ($i + 1) -lt $s.Length) {
            $n = $s[$i + 1]
            switch ($n) {
                't' { [void]$sb.Append("`t"); $i += 2; continue }
                'n' { [void]$sb.Append("`n"); $i += 2; continue }
                'r' { [void]$sb.Append("`r"); $i += 2; continue }
                'f' { [void]$sb.Append("`f"); $i += 2; continue }
                'u' {
                    if (($i + 5) -lt $s.Length) {
                        $hex = $s.Substring($i + 2, 4)
                        if ($hex -match '^[0-9a-fA-F]{4}$') {
                            [void]$sb.Append([char][Convert]::ToInt32($hex, 16))
                            $i += 6
                            continue
                        }
                    }
                    [void]$sb.Append($n); $i += 2; continue
                }
                default { [void]$sb.Append($n); $i += 2; continue }
            }
        } else {
            [void]$sb.Append($c)
            $i++
        }
    }
    return $sb.ToString()
}

function Load-PropertiesFile([string]$FilePath) {
    $p = New-Object 'System.Collections.Generic.Dictionary[string,string]'
    $lines = [System.IO.File]::ReadAllLines($FilePath, [System.Text.Encoding]::UTF8)
    $i = 0
    while ($i -lt $lines.Count) {
        $line = $lines[$i]
        $trimmed = $line.TrimStart()
        if ($trimmed.Length -eq 0 -or $trimmed.StartsWith("#") -or $trimmed.StartsWith("!")) {
            $i++
            continue
        }
        $logical = $line
        while ($true) {
            $bsCount = 0
            for ($k = $logical.Length - 1; $k -ge 0 -and $logical[$k] -eq '\'; $k--) {
                $bsCount++
            }
            if (($bsCount % 2) -eq 1 -and ($i + 1) -lt $lines.Count) {
                $i++
                $logical = $logical.Substring(0, $logical.Length - 1) + $lines[$i].TrimStart()
            } else {
                break
            }
        }
        $sepIdx = -1
        for ($j = 0; $j -lt $logical.Length; $j++) {
            $ch = $logical[$j]
            if ($ch -eq '\') { $j++; continue }
            if ($ch -eq '=' -or $ch -eq ':') { $sepIdx = $j; break }
        }
        if ($sepIdx -gt 0) {
            $rawKey = $logical.Substring(0, $sepIdx).Trim()
            $rawVal = $logical.Substring($sepIdx + 1).TrimStart()
            $key = Unescape-Prop $rawKey
            $val = Unescape-Prop $rawVal
            $p[$key] = $val
        }
        $i++
    }
    return $p
}

function Get-BraceTokens([string]$Text) {
    $list = New-Object System.Collections.Generic.List[string]
    $balanced = $true
    $i = 0
    while ($i -lt $Text.Length) {
        if ($Text[$i] -eq '{') {
            $depth = 0
            $start = $i
            $closed = $false
            while ($i -lt $Text.Length) {
                if ($Text[$i] -eq '{') { $depth++ }
                elseif ($Text[$i] -eq '}') {
                    $depth--
                    if ($depth -eq 0) {
                        $token = $Text.Substring($start, $i - $start + 1)
                        if ($token -match '^\{[0-9]+(,.*)?\}$') {
                            $list.Add($token)
                        }
                        $closed = $true
                        break
                    }
                }
                $i++
            }
            if (-not $closed) { $balanced = $false; break }
        } elseif ($Text[$i] -eq '}') {
            $balanced = $false
        }
        $i++
    }
    return [PSCustomObject]@{
        Balanced = $balanced
        Tokens = $list
    }
}

function Get-BraceSignature([string]$Token) {
    # Exact token unless choice format; for choice format, extract index + choice type + branch conditions
    if ($Token -match '^\{([0-9]+)\s*,\s*choice\s*,(.*)\}$') {
        $idx = $Matches[1]
        $body = $Matches[2]
        # Match selectors at start or after |, even if nested placeholders exist? Split top-level branches
        $selectors = New-Object System.Collections.Generic.List[string]
        $depth = 0
        $partStart = 0
        for ($k = 0; $k -lt $body.Length; $k++) {
            $ch = $body[$k]
            if ($ch -eq '{') { $depth++ }
            elseif ($ch -eq '}') { $depth-- }
            elseif ($ch -eq '|' -and $depth -eq 0) {
                $branch = $body.Substring($partStart, $k - $partStart)
                if ($branch -match '^\s*([-0-9.,∞]+[#<≤])') {
                    $selectors.Add($Matches[1].Trim())
                } else {
                    $selectors.Add("BAD_BRANCH")
                }
                $partStart = $k + 1
            }
        }
        $lastBranch = $body.Substring($partStart)
        if ($lastBranch -match '^\s*([-0-9.,∞]+[#<≤])') {
            $selectors.Add($Matches[1].Trim())
        } else {
            $selectors.Add("BAD_BRANCH")
        }
        return "{$idx,choice:" + ($selectors -join '|') + "}"
    }
    return $Token
}

function Get-PrintfPlaceholders([string]$Text) {
    $matches = [regex]::Matches($Text, '(?<![0-9])%(?:[0-9]+\$)?[-#+0,(]*(?:[0-9]+(?:\.[0-9]+)?)?[sdfoxXeEgGcCbBhHaAtT%]')
    $list = New-Object System.Collections.Generic.List[string]
    foreach ($m in $matches) {
        if ($m.Value -ne '%%') {
            $list.Add($m.Value)
        }
    }
    return ($list | Sort-Object)
}

function Get-NewlineCount([string]$Text) {
    $literal = ([regex]::Matches($Text, '\\n')).Count
    $actual = ([regex]::Matches($Text, "`n")).Count
    return ($literal + $actual)
}

function Has-LoneSingleQuote([string]$Text) {
    # Returns true if there is an odd run of single quotes (MessageFormat hazard)
    $i = 0
    while ($i -lt $Text.Length) {
        if ($Text[$i] -eq "'") {
            $run = 0
            while ($i -lt $Text.Length -and $Text[$i] -eq "'") {
                $run++
                $i++
            }
            if (($run % 2) -eq 1) { return $true }
        } else {
            $i++
        }
    }
    return $false
}

function Get-QuotedPlaceholderCount([string]$Text) {
    return ([regex]::Matches($Text, "''\{[0-9]+[^}]*\}''")).Count
}

function Get-NonEntityAmpersandCount([string]$Text) {
    $cleaned = [regex]::Replace($Text, '&(?:amp|lt|gt|quot|nbsp|apos);', '')
    return ([regex]::Matches($cleaned, '&')).Count
}

function Get-HtmlTags([string]$Text) {
    $tags = New-Object System.Collections.Generic.List[string]
    $matches = [regex]::Matches($Text, '<([^<>]+)>')
    foreach ($m in $matches) {
        $inner = $m.Groups[1].Value.Trim()
        # Normalize tag name + preserve href if present
        if ($inner -match '^/?([A-Za-z][A-Za-z0-9-]*)') {
            $tagName = $Matches[0].ToLowerInvariant()
            if ($inner -match 'href\s*=\s*([''"])(.*?)\1') {
                $tags.Add("$tagName[href=$($Matches[2])]")
            } elseif ($inner -match 'actionId\s*=\s*([''"])(.*?)\1') {
                $tags.Add("$tagName[actionId=$($Matches[2])]")
            } else {
                $tags.Add($tagName)
            }
        }
    }
    return ($tags -join ',')
}

function Get-MnemonicInfo([string]$Text) {
    $i = 0
    while ($i -lt ($Text.Length - 1)) {
        $c = $Text[$i]
        if ($c -eq '_' -or $c -eq '&') {
            $next = $Text[$i + 1]
            if ($next -eq $c) {
                $i += 2
                continue
            }
            if ($c -eq '&' -and $Text.Substring($i) -match '^&(amp|lt|gt|quot|nbsp|apos);') {
                $i++
                continue
            }
            if ($next -match '[A-Za-z0-9]') {
                return "$c`:$([char]::ToUpperInvariant($next))"
            }
        }
        $i++
    }
    return "NONE"
}

function Strip-MnemonicMarker([string]$Text) {
    return [regex]::Replace($Text, '[_&]([A-Za-z0-9])', '$1')
}

function Find-ForbiddenEnglishParens([string]$Text, [string]$English = "") {
    $bad = New-Object System.Collections.Generic.List[string]
    if (-not [string]::IsNullOrEmpty($English) -and $Text.Trim() -eq $English.Trim()) {
        return $bad
    }
    # Strip Markdown links [label](target) and quoted query/syntax examples "..."
    $cleaned = [regex]::Replace($Text, '\[[^\]]+\]\([^)]+\)', '')
    $cleaned = [regex]::Replace($cleaned, '"[^"]*"', '')
    $matches = [regex]::Matches($cleaned, '\([^()]*\)')
    foreach ($m in $matches) {
        $inner = $m.Value
        if ($inner -match '[A-Za-z]') {
            if ($inner -match '^\([_&][A-Za-z0-9]\)$') { continue }
            if ($inner -match '^\(\{[0-9]+.*\}\)$' -or $inner -match '^\(%') { continue }
            $bad.Add($inner)
        }
    }
    return $bad
}

$AllowedEnglishWords = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
@(
    'Git','GitHub','GitLab','Commit','Commits','Push','Pull','Fetch','Merge','Rebase','Stash','Checkout',
    'Shelf','VCS','IDE','URL','URLs','URI','JVM','JRE','JDK','ADB','USB','SDK','APK','AAB','NDK','AVD',
    'Gradle','Android','Kotlin','Java','Groovy','IntelliJ','Studio','Google','JetBrains','Emulator',
    'Logcat','Debug','Debugger','Refactor','Refactoring','Class','Patch','Patches','Recompile','Annotate',
    'Step','Over','Into','Out','Ctrl','Alt','Shift','Tab','Esc','Enter','Cmd','Command','Option','Backspace',
    'Windows','Linux','macOS','Mac','OS','UI','UX','API','CLI','GUI','VM','JVM','JAR','AAR','DEX','ELF',
    'XML','JSON','YAML','HTML','CSS','SQL','SQLite','RegExp','Markdown','Grazie','EditorConfig','ClangFormat',
    'JUnit','TestNG','Maven','ProGuard','R8','Lint','CMake','Ninja','LLDB','GDB','JNI','AIDL','Jetpack',
    'Compose','Room','Firebase','Wear','TV','Auto','DDMS','ANR','OOM','CPU','GPU','RAM','ROM','DPI','HEX',
    'RGB','RGBA','UTF','ASCII','EOF','EOL','CRLF','LF','CR','HTTP','HTTPS','SSH','SSL','TLS','TCP','UDP',
    'IP','DNS','Proxy','SOCKS','PAC','OAuth','Token','SAML','GPG','PGP','SSH-Agent','HEAD','SSH','FTP',
    'PNG','JPG','JPEG','SVG','GIF','WebP','PDF','CSV','TSV','ZIP','GZIP','TAR','JAR','WAR','EAR',
    'App','Bundle','Console','Daemon','Sync','Build','Preview','Run','Rerun','Fix','Quick','Layout',
    'Diff','Blame','Cherry','Pick','cherry-pick','Reflog','Bisect','Worktree','Submodule','Subtree',
    'PR','MR','CI','CD','POSIX','GNU','StdOut','StdErr','Stdin','Profiler','Perfetto','Systrace',
    'BOM','Heap','Emmet','WSL','TODO','VFS','Finder','Terminal','Console','Registry','Blame',
    'Cherry-Pick','Toolbox','DevKit','XSD','Lambda','Vision','Bundler',
    'transient','trait','final','static','abstract','override','overridden','interface','enum','var','val','const','volatile','synchronized','readonly','public','protected','private','package','native','strictfp','sealed','inline','infix','operator','suspend','tailrec','lateinit','reified','open','internal','companion','init','CamelHumps',
    'Gemini','MCP','RAG','AI','Agent','Agents','LLM','LLMs','StudioBot','Bot','C++','CMake','NDK','LLDB','Clang','Xcode','ObjC','Swift','KMP','Compose','Material','Room','Dagger','Hilt','Coroutines','Flow','Ktor','Gradle','SDK','ADB','Emulator','Logcat','Device','Manager','Play','Policy','Layout','Inspector','Profiler','Trace','App','Actions','Tab','tab','Ok','Cancel','GitHub','Docker','Kubernetes','Firebase','Cloud','SSO','OAuth',
    'Apk','Sdk','Jre','Jdk','Ide','Vcs','Git','Adb','Usb','Url','Xml','Http','Https','Ui','Api',
    'a','b','c','d','e','f','g','h','i','j','k','l','m','n','o','p','q','r','s','t','u','v','w','x','y','z',
    'OK','FAQ','ID','UUID','SHA','MD5','CRC','UTF-8','ISO','UTC','GMT','KB','MB','GB','TB','ms','fps','Hz','dp','sp','px',
    'href','amp','lt','gt','quot','nbsp','apos','br','html','body','code','b','i','u','p','li','ul','ol','span','div','kbd','em','strong','pre'
) | ForEach-Object { [void]$AllowedEnglishWords.Add($_) }

$AllowedStandaloneEnglish = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
@(
    'Git','GitHub','GitLab','Commit','Commit…','Commit...','Push','Push…','Pull','Pull…',
    'Fetch','Merge','Merge…','Rebase','Rebase…','Stash','Stash…','Checkout','Checkout…',
    'Shelf','VCS','IDE','URL','JVM','ADB','USB','SDK','Android SDK','Gradle','Android','Kotlin','Java','Groovy',
    'Logcat','Debug','Debug…','Refactor','Recompile','Annotate','Step Over','Step Into','Step Out',
    'Emulator','Patch','Class','XML','JSON','YAML','HTML','CSS','SQL','HTTP','HTTPS','SSH','SSL','TLS',
    'UTF-8','CRLF','LF','CR','API','CLI','GUI','UI','UX','Lint','Markdown','EditorConfig','ClangFormat',
    'Jetpack Compose','Compose','ProGuard','R8','CMake','Ninja','LLDB','GDB','JUnit','TestNG','Maven',
    'Ctrl','Alt','Shift','Tab','Esc','Enter','App Bundle','APK','AVD','DDMS','NDK','JDK','JRE','AAR','JAR',
    'HEAD','WIP','SHA-1','SHA-256','MD5','POSIX','UNIX','Windows','macOS','Linux',
    'Console','Terminal','TODO','VFS','Finder','Registry','Blame','Cherry-Pick','Lambda','Emmet','BOM','WSL',
    'transient','trait','final','static','abstract','override','overridden','interface','enum','var','val','const','volatile','synchronized','readonly','public','protected','private','package private','static init','native','strictfp','sealed','inline','infix','operator','suspend','tailrec','lateinit','reified','open','internal','companion',
    'CamelHumps','Gemini','MCP','RAG','AI','Firebase','SQLite','Perfetto','Systrace','Gradle KTS','KTS','TOML','RegExp','XPath','XSD',
    'IntelliJ IDEA','Android Studio','PyCharm','WebStorm','PhpStorm','RubyMine','CLion','DataGrip','GoLand','Rider','AppCode','Fleet','Visual Studio','VSCode','Xcode','Eclipse','Pro','Ultimate','Community',
    'git  --abort','Git checkout','Git cherry-pick','Git merge','Git rebase','Git stash','TextConv','lib','JavaScript',
    '((Type)expr)','if (expr) {...}','if','if / else','!(expr)','(expr)','try / finally','while','Swing','PID','Gradle DSL','Groovy Gradle DSL','Wrapper','Gradle JVM',
    'module-info.java','package-info.java','JLink','boolean','void','Beans','Kubernetes','IntelliJ Profiler','Android Logcat',
    'Define','Typedef','-copyWithZone','-copyWithZone:','-description','-init/encodeWithCoder','-init/encodeWithCoder:','-initWith','+sharedInstance','@synthesize','#pragma region...endregion','explicit','Compose Multiplatform','Grazie',
    'Gemini Code Assist','Gemini -','[MissingFileChange] :','[MissingFileChange]','studiobot.testGeneration.dialog','<html><head><style>%%STYLE%%</style></head><body>'
) | ForEach-Object { [void]$AllowedStandaloneEnglish.Add($_) }

$BadTaaMarbutaRegex = '(?<![\u0600-\u06FF])(?:ال)?(أخيره|اخيره|إعاده|اعاده|إداره|اداره|نافذه|قائمه|صفحه|وحده|حزمه|مكتبه|داله|رساله|عمليه|مهمه|إضافه|اضافه|شاشه|حاله|قيمه|مشكله|لغه|نسخه|طريقه|خطوه|مجموعه|صوره|علامه|بيئه|أداه|اداه|ذاكره|لاحقه|بادئه)(?![\u0600-\u06FF])'

$bundleCache = @{}

function Load-Rows([string]$Path) {
    $allRows = New-Object System.Collections.Generic.List[object]
    $files = @()
    if (Test-Path $Path -PathType Container) {
        $files = Get-ChildItem -Path $Path -Filter "*.tsv" | Sort-Object Name | Select-Object -ExpandProperty FullName
    } else {
        $files = @($Path)
    }

    foreach ($f in $files) {
        if ($f -match '\.tsv$') {
            $lines = [System.IO.File]::ReadAllLines($f, [System.Text.Encoding]::UTF8)
            foreach ($line in $lines) {
                if ([string]::IsNullOrWhiteSpace($line)) { continue }
                $parts = $line -split "`t", 4
                if ($parts.Count -lt 4) { continue }
                if ($parts[0] -eq 'File' -and $parts[1] -eq 'Key') { continue }
                $allRows.Add([PSCustomObject]@{
                    SourceFile = (Split-Path $f -Leaf)
                    File = $parts[0]
                    Key = $parts[1]
                    English = Unescape-Prop $parts[2]
                    Arabic = Unescape-Prop $parts[3]
                    RawEnglish = $parts[2]
                    RawArabic = $parts[3]
                })
            }
        } else {
            $csvRows = Import-Csv -Path $f -Encoding UTF8
            foreach ($r in $csvRows) {
                $k = if ($r.PSObject.Properties['المفتاح']) { [string]$r.'المفتاح' } else { [string]$r.'Key' }
                $fl = if ($r.PSObject.Properties['الملف']) { [string]$r.'الملف' } else { [string]$r.'File' }
                $en = if ($r.PSObject.Properties['الإنجليزي']) { [string]$r.'الإنجليزي' } else { [string]$r.'English' }
                $ar = if ($r.PSObject.Properties['ترجمة مقترحة']) { [string]$r.'ترجمة مقترحة' } else { [string]$r.'Arabic' }
                $allRows.Add([PSCustomObject]@{
                    SourceFile = (Split-Path $f -Leaf)
                    File = $fl
                    Key = $k
                    English = $en
                    Arabic = $ar
                    RawEnglish = $en
                    RawArabic = $ar
                })
            }
        }
    }
    return $allRows
}

function Find-BundleMap([string]$FileName) {
    if ($bundleCache.ContainsKey($FileName)) { return $bundleCache[$FileName] }
    foreach ($dir in $ExtractedDirs) {
        if (-not (Test-Path $dir)) { continue }
        $candidate = Join-Path $dir $FileName
        if (Test-Path $candidate) {
            $bundleCache[$FileName] = Load-PropertiesFile $candidate
            return $bundleCache[$FileName]
        }
    }
    $bundleCache[$FileName] = $null
    return $null
}

$rows = Load-Rows $InputPath
$failures = New-Object System.Collections.Generic.List[string]
$warnings = New-Object System.Collections.Generic.List[string]
$checked = 0

foreach ($row in $rows) {
    $checked++
    $key = $row.Key
    $file = $row.File
    $en = $row.English
    $ar = $row.Arabic

    if ([string]::IsNullOrWhiteSpace($ar)) {
        $failures.Add("[$file | $key] Translation is empty.")
        continue
    }

    # Check leading/trailing space mismatch
    $enLead = ($en.Length -gt 0 -and [char]::IsWhiteSpace($en[0]))
    $arLead = ($ar.Length -gt 0 -and [char]::IsWhiteSpace($ar[0]))
    if ($arLead -and -not $enLead) {
        $failures.Add("[$file | $key] Unexpected leading whitespace in AR='$ar'")
    }
    $enTrail = ($en.Length -gt 0 -and [char]::IsWhiteSpace($en[$en.Length - 1]))
    $arTrail = ($ar.Length -gt 0 -and [char]::IsWhiteSpace($ar[$ar.Length - 1]))
    if ($arTrail -and -not $enTrail) {
        $failures.Add("[$file | $key] Unexpected trailing whitespace in AR='$ar'")
    }

    # Check original bundle key existence
    $origMap = Find-BundleMap $file
    if ($null -eq $origMap) {
        $failures.Add("[$file | $key] Original .properties file not found in ExtractedDirs")
        continue
    }
    if (-not $origMap.ContainsKey($key)) {
        $failures.Add("[$file | $key] Key does NOT exist in original $file")
        continue
    }

    # Brace & ChoiceFormat check
    $enBraceInfo = Get-BraceTokens $en
    $arBraceInfo = Get-BraceTokens $ar
    if ($enBraceInfo.Balanced -and -not $arBraceInfo.Balanced) {
        $failures.Add("[$file | $key] Unbalanced braces in AR='$ar'")
    } else {
        $enSigs = ($enBraceInfo.Tokens | ForEach-Object { Get-BraceSignature $_ } | Sort-Object) -join '|'
        $arSigs = ($arBraceInfo.Tokens | ForEach-Object { Get-BraceSignature $_ } | Sort-Object) -join '|'
        if ($enSigs -ne $arSigs) {
            $failures.Add("[$file | $key] Brace/ChoiceFormat mismatch: EN=[$enSigs] vs AR=[$arSigs] | EN='$en' AR='$ar'")
        }
    }

    # Printf placeholders
    $enPrintf = (Get-PrintfPlaceholders $en) -join '|'
    $arPrintf = (Get-PrintfPlaceholders $ar) -join '|'
    if ($enPrintf -ne $arPrintf) {
        $failures.Add("[$file | $key] Printf placeholders mismatch: EN=[$enPrintf] vs AR=[$arPrintf] | EN='$en' AR='$ar'")
    }

    # Newlines
    $enNl = Get-NewlineCount $en
    $arNl = Get-NewlineCount $ar
    if ($enNl -ne $arNl) {
        $failures.Add("[$file | $key] Newline (\n) count mismatch: EN=$enNl vs AR=$arNl")
    }

    # MessageFormat quoting safety when brace placeholders exist
    if ($enBraceInfo.Tokens.Count -gt 0) {
        if (-not (Has-LoneSingleQuote $en) -and (Has-LoneSingleQuote $ar)) {
            $failures.Add("[$file | $key] Unpaired single quote (') inside MessageFormat string AR='$ar'")
        }
        $enQp = Get-QuotedPlaceholderCount $en
        $arQp = Get-QuotedPlaceholderCount $ar
        if ($enQp -ne $arQp) {
            $failures.Add("[$file | $key] Quoted placeholder ''{N}'' count mismatch: EN=$enQp vs AR=$arQp | EN='$en' AR='$ar'")
        }
    }

    # Ampersands & Mnemonics
    $enAmp = Get-NonEntityAmpersandCount $en
    $arAmp = Get-NonEntityAmpersandCount $ar
    if ($enAmp -ne $arAmp) {
        $failures.Add("[$file | $key] Ampersand (&) count mismatch: EN=$enAmp vs AR=$arAmp | EN='$en' AR='$ar'")
    }

    $enMnem = Get-MnemonicInfo $en
    $arMnem = Get-MnemonicInfo $ar
    if ($enMnem -ne $arMnem) {
        $failures.Add("[$file | $key] Mnemonic mismatch: EN=[$enMnem] vs AR=[$arMnem] | EN='$en' AR='$ar'")
    }

    # Forbidden English parentheses
    $badParens = Find-ForbiddenEnglishParens $ar $en
    if ($badParens.Count -gt 0) {
        $failures.Add("[$file | $key] Forbidden English parentheses: [$($badParens -join ', ')] in AR='$ar'")
    }

    # HTML tags & href preservation
    $enHtml = Get-HtmlTags $en
    $arHtml = Get-HtmlTags $ar
    if ($enHtml -ne $arHtml) {
        $failures.Add("[$file | $key] HTML tags/attributes mismatch: EN=[$enHtml] vs AR=[$arHtml] | EN='$en' AR='$ar'")
    }

    # Linguistic checks: no space after waw before Arabic word
    if ($ar -match '(?:^|\s)و\s+[\u0600-\u06FF]') {
        $failures.Add("[$file | $key] Space after conjunction 'و' before Arabic word in AR='$ar'")
    }

    # Linguistic checks: Taa Marbuta typo (ه instead of ة)
    if ($ar -match $BadTaaMarbutaRegex) {
        $failures.Add("[$file | $key] Possible Taa Marbuta typo '$($Matches[1])' in AR='$ar'")
    }

    # Terminology rule checks: Shelf != الرف, Update != raw Update
    if ($en -match '\bShelf\b|\bShelve\b|\bShelved\b|\bUnshelve\b' -and $ar -match '(?:^|[^\u0600-\u06FF])(ال)?رف(وف)?(?![\u0600-\u06FF])') {
        $failures.Add("[$file | $key] Shelf must stay 'Shelf' in English, found '$($Matches[0])' in AR='$ar'")
    }
    if ($en -match '\bUpdate\b' -and $ar -match '\bUpdate\b') {
        $failures.Add("[$file | $key] 'Update' must be translated to 'تحديث', still in AR='$ar'")
    }

    # Untranslated exact match check
    $enPlain = (Strip-MnemonicMarker $en).Trim()
    $arPlain = (Strip-MnemonicMarker $ar).Trim()
    if ($enPlain -eq $arPlain -and $enPlain -match '[A-Za-z]') {
        $noPlaceholders = [regex]::Replace($enPlain, '\{[0-9]+[^}]*\}', '').Trim()
        $noPunct = $noPlaceholders.TrimEnd(':', '…', '.').Trim()
        if ($noPunct.Length -gt 0 -and -not $AllowedStandaloneEnglish.Contains($noPunct)) {
            $failures.Add("[$file | $key] Untranslated text matches English: AR='$ar'")
        }
    }

    # Optional strict leftover English word check inside mixed strings
    if ($StrictEnglishCheck) {
        $stripped = [regex]::Replace($ar, '<[^>]+>', ' ')
        $stripped = [regex]::Replace($stripped, '\{[0-9]+[^}]*\}', ' ')
        $stripped = [regex]::Replace($stripped, '[_&]([A-Za-z0-9])', '$1')
        $stripped = [regex]::Replace($stripped, '%[0-9$]*[sd]', ' ')
        $wordMatches = [regex]::Matches($stripped, '[A-Za-z][A-Za-z0-9_-]*')
        foreach ($wm in $wordMatches) {
            $w = $wm.Value
            if (-not $AllowedEnglishWords.Contains($w)) {
                $warnings.Add("[$file | $key] Non-whitelisted English word '$w' in AR='$ar' (EN='$en')")
            }
        }
    }
}

Write-Output "Checked rows: $checked"
if ($warnings.Count -gt 0) {
    Write-Output "WARNINGS ($($warnings.Count)):"
    $warnings | Select-Object -First 40 | ForEach-Object { Write-Output "  WARN: $_" }
}
if ($failures.Count -gt 0) {
    Write-Output "FAILED: $($failures.Count) violation(s) found:"
    foreach ($f in ($failures | Select-Object -First 80)) {
        Write-Output "  ERROR: $f"
    }
    if ($failures.Count -gt 80) {
        Write-Output "  ... ($($failures.Count - 80) more errors omitted)"
    }
    exit 1
}

Write-Output "PASS: All $checked translations passed all validation checks."
exit 0
