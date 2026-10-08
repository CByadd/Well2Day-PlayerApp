<#
.SYNOPSIS
    Build a release-signed kiosk APK, verify it, and copy it to "APK Root".

.DESCRIPTION
    1. Makes sure the release key (well2day-release.jks + keystore.properties) is present,
       copying it from the backup folder if needed.
    2. Runs gradlew assembleF1Release (version.properties auto-increments AFTER the build).
    3. Verifies the APK is signed with the release key, then prints the versionCode / versionName / SHA-256
       to enter in Admin -> App updates.
    4. Copies the APK to ..\APK Root\ (where update_system_app.ps1 picks it up).

.EXAMPLE
    .\build_release.ps1
#>
$ErrorActionPreference = "Stop"
$ProjectDir  = Split-Path -Parent $MyInvocation.MyCommand.Path
$KeyBackup   = "E:\Srinivash\Well2Day-Signing-Key"
$ReleaseCert = "82d9641a296ac155978894ed70f0cbe4ab5b50466f725d530c30c9cef78dcc2e"
$ApkRoot     = Join-Path (Split-Path -Parent $ProjectDir) "APK Root"

function Step($m) { Write-Host "[+] $m" -ForegroundColor Yellow }
function Fail($m) { Write-Host "[X] $m" -ForegroundColor Red; exit 1 }

# ── 1. Release key ─────────────────────────────────────────────────────────────
foreach ($f in "well2day-release.jks", "keystore.properties") {
    if (-not (Test-Path (Join-Path $ProjectDir $f))) {
        if (-not (Test-Path (Join-Path $KeyBackup $f))) { Fail "$f missing here and in $KeyBackup. Without it the APK would be unsigned." }
        Step "Copying $f from $KeyBackup"
        Copy-Item (Join-Path $KeyBackup $f) $ProjectDir
    }
}

# ── 2. SDK tools ───────────────────────────────────────────────────────────────
$sdk = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, "$env:LOCALAPPDATA\Android\Sdk") |
    Where-Object { $_ -and (Test-Path "$_\build-tools") } | Select-Object -First 1
if (-not $sdk) { Fail "Android SDK not found. Set ANDROID_HOME." }
$bt = Get-ChildItem "$sdk\build-tools" -Directory | Sort-Object { [version]$_.Name } | Select-Object -Last 1
$aapt2 = Join-Path $bt.FullName "aapt2.exe"
$apksigner = Join-Path $bt.FullName "apksigner.bat"

# ── 3. Build ───────────────────────────────────────────────────────────────────
$outDir = Join-Path $ProjectDir "app\build\outputs\apk\f1\release"
if (Test-Path $outDir) { Remove-Item "$outDir\*.apk" }
Step "Building (gradlew assembleF1Release)..."
Push-Location $ProjectDir
try { & .\gradlew.bat assembleF1Release; $code = $LASTEXITCODE } finally { Pop-Location }
if ($code -ne 0) { Fail "Gradle build failed (exit $code)." }
$apk = Get-ChildItem "$outDir\*.apk" | Select-Object -First 1
if (-not $apk) { Fail "No APK found in $outDir" }

# ── 4. Verify signature + read version ─────────────────────────────────────────
Step "Verifying signature..."
$sig = & $apksigner verify --print-certs $apk.FullName 2>&1 | Out-String
if ($LASTEXITCODE -ne 0) { Fail "apksigner verify failed:`n$sig" }
if ($sig -notmatch "certificate SHA-256 digest: $ReleaseCert") { Fail "APK is NOT signed with the Well2Day release key:`n$sig" }

$badging = & $aapt2 dump badging $apk.FullName 2>$null | Select-Object -First 1
if ($badging -notmatch "name='([^']+)' versionCode='(\d+)' versionName='([^']*)'") { Fail "Could not read APK version." }
$pkg, $versionCode, $versionName = $Matches[1], $Matches[2], $Matches[3]
$sha256 = (Get-FileHash $apk.FullName -Algorithm SHA256).Hash.ToLower()

# ── 5. Copy to APK Root ────────────────────────────────────────────────────────
$dest = Join-Path $ApkRoot $apk.Name
if (Test-Path $dest) { Fail "$dest already exists - not overwriting. Rename or delete it first." }
Copy-Item $apk.FullName $dest

Write-Host "====================================================" -ForegroundColor Green
Write-Host " Release APK ready (signed with release key)" -ForegroundColor Green
Write-Host "   File        : $dest"
Write-Host "   Package     : $pkg"
Write-Host "   versionCode : $versionCode"
Write-Host "   versionName : $versionName"
Write-Host "   SHA-256     : $sha256"
Write-Host " Publish: Admin -> App updates, enter versionCode $versionCode / versionName $versionName" -ForegroundColor Green
Write-Host "====================================================" -ForegroundColor Green
