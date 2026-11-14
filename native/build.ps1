# Builds audiobookshelf-lite.apk without Gradle: aapt2 + javac + d8 + zipalign + apksigner.
# Credentials/server come from ..\config.json (single user, single instance, hardcoded on purpose).
$ErrorActionPreference = 'Stop'
$here  = Split-Path -Parent $MyInvocation.MyCommand.Path
$root  = Split-Path -Parent $here
$sdk   = Join-Path (Split-Path -Parent $root) 'android-sdk'
$bt    = Join-Path $sdk 'bt-unpack\android-14'
$jar   = Join-Path $sdk 'plat-unpack\android-5.1.1\android.jar'
$out   = Join-Path $here 'build'
$cfg   = Get-Content (Join-Path $root 'config.json') -Raw | ConvertFrom-Json

foreach ($p in @($bt, $jar)) { if (-not (Test-Path $p)) { throw "Missing $p" } }
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Force "$out\classes", "$out\gen\lite\abs", "$out\dex" | Out-Null

function J([string]$s) { '"' + ($s -replace '\\', '\\' -replace '"', '\"') + '"' }
$lock = if ($null -eq $cfg.lockTask) { 'true' } else { ([string]([bool]$cfg.lockTask)).ToLower() }
$configJava = @"
package lite.abs;
final class Config {
    static final String BASE = $(J $cfg.absUrl);
    static final String USER = $(J $cfg.username);
    static final String PASS = $(J $cfg.password);
    static final boolean LOCK_TASK = $lock;
    private Config() {}
}
"@
[System.IO.File]::WriteAllText("$out\gen\lite\abs\Config.java", $configJava, (New-Object System.Text.UTF8Encoding($false)))

& "$bt\aapt2.exe" link -o "$out\base.apk" -I $jar --manifest "$here\AndroidManifest.xml" --min-sdk-version 21 --target-sdk-version 22 --version-code 1 --version-name 1.0
if ($LASTEXITCODE) { throw 'aapt2 link failed' }

$srcs = @(Get-ChildItem "$here\src" -Recurse -Filter *.java | ForEach-Object FullName) + "$out\gen\lite\abs\Config.java"
& javac --release 8 -encoding UTF-8 -Xlint:-options -cp $jar -d "$out\classes" @srcs
if ($LASTEXITCODE) { throw 'javac failed' }

$classes = Get-ChildItem "$out\classes" -Recurse -Filter *.class | ForEach-Object FullName
& "$bt\d8.bat" --min-api 21 --lib $jar --output "$out\dex" @classes
if ($LASTEXITCODE) { throw 'd8 failed' }

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$out\base.apk", 'Update')
[void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$out\dex\classes.dex", 'classes.dex')
$zip.Dispose()

& "$bt\zipalign.exe" -f 4 "$out\base.apk" "$out\aligned.apk"
if ($LASTEXITCODE) { throw 'zipalign failed' }

$ks = Join-Path $here 'debug.keystore'
if (-not (Test-Path $ks)) {
    $ErrorActionPreference = 'Continue' # keytool prints progress on stderr
    & keytool -genkeypair -keystore $ks -storepass android -keypass android -alias abslite -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=ABS Lite' 2>&1 | Out-Null
    $ErrorActionPreference = 'Stop'
    if (-not (Test-Path $ks)) { throw 'keytool failed to create keystore' }
}
$apk = Join-Path $root 'audiobookshelf-lite.apk'
& "$bt\apksigner.bat" sign --ks $ks --ks-pass pass:android --key-pass pass:android --min-sdk-version 21 --out $apk "$out\aligned.apk"
if ($LASTEXITCODE) { throw 'apksigner failed' }
"Built $apk ($([math]::Round((Get-Item $apk).Length / 1KB)) KB)"
