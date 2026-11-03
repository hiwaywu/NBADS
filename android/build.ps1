# build.ps1 -- builds NBAHub.apk with the SDK tools directly (no Gradle).
#   powershell -ExecutionPolicy Bypass -File build.ps1
$ErrorActionPreference = 'Continue'   # native tools write to stderr; check $LASTEXITCODE instead

$root = $PSScriptRoot
$sdk  = 'C:\Android\Sdk'
$bt   = Join-Path $sdk 'build-tools\36.0.0'   # 34.0.0 d8 crashes on inner classes
if (-not (Test-Path $bt)) { $bt = Join-Path $sdk 'build-tools\34.0.0' }
$jar  = Join-Path $sdk 'platforms\android-35\android.jar'
$out  = Join-Path $root 'build'

New-Item -ItemType Directory -Force -Path $out | Out-Null
Get-ChildItem $out -Recurse -File -ErrorAction SilentlyContinue | Remove-Item -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path "$out\gen", "$out\classes", "$out\dex" | Out-Null

Write-Host '[1/6] compiling resources' -ForegroundColor Cyan
& "$bt\aapt2.exe" compile --dir "$root\res" -o "$out\res.zip"
if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile failed' }

Write-Host '[2/6] linking resources + manifest' -ForegroundColor Cyan
& "$bt\aapt2.exe" link -o "$out\base.apk" -I $jar `
    --manifest "$root\AndroidManifest.xml" -R "$out\res.zip" `
    --java "$out\gen" --auto-add-overlay `
    --min-sdk-version 24 --target-sdk-version 34 `
    --version-code 3 --version-name 1.3
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link failed' }

Write-Host '[3/6] compiling java' -ForegroundColor Cyan
$srcs = @(Get-ChildItem "$root\src" -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$srcs += @(Get-ChildItem "$out\gen" -Recurse -Filter *.java | ForEach-Object { $_.FullName })
& javac -source 8 -target 8 -nowarn -bootclasspath $jar -classpath $jar -d "$out\classes" $srcs
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }

Write-Host '[4/6] dexing' -ForegroundColor Cyan
Push-Location "$out\classes"
& jar cf "$out\classes.jar" -C "$out\classes" .
Pop-Location
& "$bt\d8.bat" --lib $jar --min-api 24 --output "$out\dex" "$out\classes.jar"
if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }

Write-Host '[5/6] packaging' -ForegroundColor Cyan
Copy-Item "$out\base.apk" "$out\unsigned.apk" -Force
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$out\unsigned.apk", 'Update')
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
    $zip, "$out\dex\classes.dex", 'classes.dex',
    [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
$zip.Dispose()

Write-Host '[6/6] aligning + signing' -ForegroundColor Cyan
$ks = Join-Path $root 'debug.keystore'
if (-not (Test-Path $ks)) {
    & keytool -genkeypair -v -keystore $ks -storepass android -keypass android `
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 `
        -dname 'CN=Android Debug,O=Android,C=US' *> $null
}
& "$bt\zipalign.exe" -f 4 "$out\unsigned.apk" "$out\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }

& "$bt\apksigner.bat" sign --ks $ks --ks-pass pass:android --key-pass pass:android `
    --out (Join-Path $root 'NBAHub.apk') "$out\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw 'apksigner failed' }

& "$bt\apksigner.bat" verify --print-certs (Join-Path $root 'NBAHub.apk') | Out-Null
Write-Host ''
Write-Host ('OK -> ' + (Join-Path $root 'NBAHub.apk')) -ForegroundColor Green
Write-Host ('size  ' + [math]::Round((Get-Item (Join-Path $root 'NBAHub.apk')).Length/1KB) + ' KB')
