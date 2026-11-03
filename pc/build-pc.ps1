# build-pc.ps1 -- builds NBAHub.exe (the PC app) with the .NET Framework
# compiler that ships with Windows. No Visual Studio, no NuGet, no Gradle.
#
#   powershell -ExecutionPolicy Bypass -File .\build-pc.ps1
#
# Output: NBAHub.exe next to hub.py (nba\hub\NBAHub.exe) and in .\pc\.
$ErrorActionPreference = 'Continue'

$root = Split-Path -Parent $PSScriptRoot          # repo root
$pc   = $PSScriptRoot
$hub  = Join-Path $root 'nba\hub'

$csc = 'C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe'
if (-not (Test-Path $csc)) { $csc = 'C:\Windows\Microsoft.NET\Framework\v4.0.30319\csc.exe' }
if (-not (Test-Path $csc)) { throw 'csc.exe not found (needs .NET Framework 4.x)' }

# ---- icon: derive app.ico from the phone launcher so both look like the app
$ico = Join-Path $pc 'app.ico'
if (-not (Test-Path $ico)) {
    $png = Join-Path $root 'android\res\mipmap-xxhdpi\ic_launcher.png'
    if (Test-Path $png) {
        Write-Host '[1/3] building app.ico from the launcher png' -ForegroundColor Cyan
        $py = @"
from PIL import Image
im = Image.open(r'$png').convert('RGBA')
sizes = [(256,256),(128,128),(64,64),(48,48),(32,32),(16,16)]
im.resize((256,256), Image.LANCZOS).save(r'$ico', format='ICO', sizes=sizes)
print('icon written')
"@
        $tmp = Join-Path $env:TEMP 'mkico.py'
        Set-Content -Path $tmp -Value $py -Encoding UTF8
        python $tmp
    }
}

Write-Host '[2/3] compiling NBAHub.exe' -ForegroundColor Cyan
$refs = @(
    '/r:System.dll',
    '/r:System.Core.dll',
    '/r:System.Drawing.dll',
    '/r:System.Windows.Forms.dll',
    '/r:System.Management.dll'
)
$args = @(
    '/nologo', '/target:winexe', '/platform:anycpu', '/optimize+',
    '/codepage:65001',
    "/win32icon:$ico",
    "/out:$pc\NBAHub.exe"
) + $refs + @((Join-Path $pc 'NBAPC.cs'))

& $csc @args
if ($LASTEXITCODE -ne 0 -or -not (Test-Path (Join-Path $pc 'NBAHub.exe'))) { throw 'csc failed' }

Write-Host '[3/3] copying next to hub.py' -ForegroundColor Cyan
Copy-Item (Join-Path $pc 'NBAHub.exe') (Join-Path $hub 'NBAHub.exe') -Force

Write-Host ''
Write-Host ('OK -> ' + (Join-Path $hub 'NBAHub.exe')) -ForegroundColor Green
Write-Host ('size  ' + [math]::Round((Get-Item (Join-Path $hub 'NBAHub.exe')).Length / 1KB) + ' KB')
