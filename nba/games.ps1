# games.ps1 -- list the NBA games the stream sites are carrying, then open one.
#
#   GAMES.bat                       list games, pick a number
#   powershell -File games.ps1 -Raw  just print the list
#
# The listing pages are plain HTML, so no browser is needed here. Picking a
# number opens that game's stream page in your normal browser (where you can
# hit Play, and where your ad blocker is already doing its job).
param(
    [string[]]$Site = @('https://methstreams.st/nba', 'https://crackstreams.st/nba'),
    [switch]$Raw,
    [switch]$NoDetail    # skip the per-game tip-off lookup: fast list, no times
)

$ErrorActionPreference = 'SilentlyContinue'
$UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36'

function Say($m, $c = 'Gray') { Write-Host $m -ForegroundColor $c }

function Get-Games([string]$page) {
    $html = (& curl.exe -sL -m 20 -A $UA $page 2>$null) -join "`n"
    if (-not $html) { return @() }
    $out = @()

    # the listing pages embed a schema.org ItemList -- cleanest source by far
    foreach ($m in [regex]::Matches($html, '(?s)<script type="application/ld\+json">(.*?)</script>')) {
        $json = $m.Groups[1].Value
        if ($json -notmatch 'ItemList') { continue }
        try { $obj = $json | ConvertFrom-Json } catch { continue }
        foreach ($it in $obj.itemListElement) {
            if (-not $it.url) { continue }
            $out += [pscustomobject]@{ Game = $it.name; When = (Get-DateLabel $it.url); Time = ''; Url = $it.url }
        }
    }

    # fallback: plain anchor scan
    if ($out.Count -eq 0) {
        foreach ($m in [regex]::Matches($html, 'href="(?<u>[^"]*/event/[^"]+)"')) {
            $url = $m.Groups['u'].Value
            if (-not $url.StartsWith('http')) {
                $root = [uri]$page
                $url = "$($root.Scheme)://$($root.Host)$url"
            }
            $slug = ($url -split '/event/')[-1] -replace '^m-', '' -replace '-\d{4}$', ''
            $name = (Get-Culture).TextInfo.ToTitleCase(($slug -replace '-vs-', ' vs ' -replace '-', ' '))
            $out += [pscustomobject]@{ Game = $name; When = (Get-DateLabel $url); Time = ''; Url = $url }
        }
    }
    return $out
}

function Get-DateLabel([string]$url) {
    # The slug's MMDD is a UTC date, not a local one. An 8pm ET game is therefore
    # filed as "-1111" while it is still Nov 10 here -- which is exactly how the
    # list ends up pointing at what looks like tomorrow's slate. Build the date as
    # UTC midnight and read the local date back out.
    $md = [regex]::Match($url, '-(\d{2})(\d{2})(?:[/?#]|$)')
    if ($md.Success) {
        $mon = [int]$md.Groups[1].Value; $day = [int]$md.Groups[2].Value
        if ($mon -ge 1 -and $mon -le 12 -and $day -ge 1 -and $day -le 31) {
            try {
                $utc = [datetime]::new((Get-Date).Year, $mon, $day, 0, 0, 0, [System.DateTimeKind]::Utc)
                $loc = $utc.ToLocalTime()
                if ($loc.Date -eq (Get-Date).Date) { return 'Today' }
                if ($loc.Date -eq (Get-Date).Date.AddDays(1)) { return 'Tomorrow' }
                return $loc.ToString('MMM d')
            } catch { }
        }
    }
    return ''
}

$all = @()
foreach ($s in $Site) { $all += Get-Games $s }
$seen = @{}; $uniq = @()
foreach ($g in $all) {
    if (-not $g.Game) { continue }
    $key = "$($g.Game)|$($g.When)"
    if ($seen.ContainsKey($key)) { continue }
    $seen[$key] = 1
    $uniq += $g
}
$all = $uniq | Sort-Object When, Game

# ---- tip-off + status per row (one request per game, in parallel) ----
# The listing is only name+url, so the real time comes from the event page's
# <time data-utc>. That single field also tells us what the slug date cannot:
# whether the game is still coming, on now, or already over.
. (Join-Path $PSScriptRoot 'resolve.ps1')
if (-not $NoDetail -and $all.Count -gt 0) {
    Say '  checking tip-off times...' 'DarkGray'
    $tmp = Join-Path $env:TEMP ('nbads_ev_' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Force -Path $tmp | Out-Null

    # One curl, many URLs, one connection pool: -Z runs them in parallel and the
    # -o/url pairs stay matched in order. Spawning a process per game also worked
    # but Start-Process re-splits the argument list on spaces, and a user-agent
    # string with spaces in it then arrives as a dozen junk arguments.
    $cargs = @('-Z', '--parallel-max', '8', '-sS', '-k', '-m', '15', '-A', $UA)
    for ($k = 0; $k -lt $all.Count; $k++) {
        $cargs += @('-o', (Join-Path $tmp "e_$k.html"), $all[$k].Url)
    }
    $null = & curl.exe @cargs 2>$null

    for ($k = 0; $k -lt $all.Count; $k++) {
        $f = Join-Path $tmp ("e_$k.html")
        $starts = $null; $chip = ''
        if (Test-Path $f) {
            $h = Get-Content -LiteralPath $f -Raw -ErrorAction SilentlyContinue
            if ($h) {
                $u = [regex]::Match($h, 'data-utc="(?<u>[^"]+)"').Groups['u'].Value
                if ($u) { try { $starts = [DateTimeOffset]::Parse($u).ToLocalTime() } catch { } }
                $chip = [regex]::Match($h, 'class="ev-chip">(?<c>[^<]+)<').Groups['c'].Value
                if ($chip) { $chip = $chip.Trim() }
            }
        }
        $all[$k] | Add-Member -NotePropertyName StartsAt -NotePropertyValue $starts -Force
        $all[$k] | Add-Member -NotePropertyName Chip -NotePropertyValue $chip -Force
        if ($starts) {
            $d = $starts.Date
            if ($d -eq (Get-Date).Date) { $all[$k].When = 'Today' }
            elseif ($d -eq (Get-Date).Date.AddDays(1)) { $all[$k].When = 'Tomorrow' }
            else { $all[$k].When = $starts.ToString('MMM d') }
            $all[$k].Time = $starts.ToString('HH:mm')
        }
    }
    Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue

    # drop what is already over; a stale "Final" row is how you end up clicking
    # a page whose player was torn down an hour ago
    $all = @($all | Where-Object { $_.Chip -ne 'Final' })
    $all = @($all | Sort-Object @{ Expression = { if ($_.StartsAt) { $_.StartsAt } else { [datetimeoffset]::MaxValue } } }, Game)
}

if ($all.Count -eq 0) {
    Say ''
    Say '  Nothing left on the slate right now -- every listed game is already final.' 'Yellow'
    Say '  NBA TV is always up: double-click NBA.bat' 'Gray'
    Say ''
    if (-not $Raw) { Read-Host 'Press Enter to close' | Out-Null }
    exit 0
}

Say ''
Say ('  NBA GAMES ON THE STREAM SITES  ({0})' -f (Get-Date -Format 'MMM d, HH:mm')) 'Cyan'
Say '  ------------------------------------------------------------' 'DarkGray'
$i = 0
foreach ($g in $all) {
    $i++
    $state = ''
    $colour = 'DarkCyan'
    if ($g.Chip -eq 'Live') { $state = 'LIVE'; $colour = 'Green' }
    elseif ($g.StartsAt) {
        if ($g.StartsAt -gt (Get-Date)) { $state = 'upcoming'; $colour = 'DarkGray' }
        else { $state = 'on now'; $colour = 'DarkCyan' }
    }
    $nm = $g.Game
    if ($nm.Length -gt 43) { $nm = $nm.Substring(0, 41) + '..' }
    Write-Host ('  [{0,2}] ' -f $i) -NoNewline -ForegroundColor DarkGray
    Write-Host ('{0,-44}' -f $nm) -NoNewline
    Write-Host ('{0,-9} {1,-6} ' -f $g.When, $g.Time) -NoNewline -ForegroundColor $colour
    Write-Host $state -ForegroundColor $(if ($state -eq 'LIVE') { 'Green' } else { 'DarkGray' })
}
Say ''
Say '  NBA TV (the always-on channel) plays with:   NBA.bat' 'DarkGray'
Say ''

if ($Raw) { exit 0 }

$sel = Read-Host '  Open game number (Enter to quit)'
if ($sel -match '^\d+$' -and [int]$sel -ge 1 -and [int]$sel -le $all.Count) {
    $pick = $all[[int]$sel - 1]
    Say ("  Opening " + $pick.Game) 'Green'

    # Walk the rotating chain instead of scraping for an .m3u8 that is never there:
    #   event page -> player host (foxiy.st today) -> embed host (trendy48.site)
    # resolve.ps1 also reads the gate (data-open), so a game that has not been let
    # through yet says so instead of showing you an empty black player.
    . (Join-Path $PSScriptRoot 'resolve.ps1')
    $res = Resolve-Game -eventUrl $pick.Url -mainIndex 1
    $target = $res.PlayerUrl
    if (-not $target) { $target = $pick.Url }

    Say ''
    if ($res.Status -eq 'live') {
        Say ("  chain ok   {0} -> {1}" -f $res.PlayerHost, $res.EmbedHost) 'DarkGray'
        if ($res.StartsAt) { Say ('  tip-off    {0:HH:mm}' -f $res.StartsAt) 'DarkGray' }
    } elseif ($res.Status -eq 'gated') {
        Say ("  NOT OPEN YET -- " + $res.Note) 'Yellow'
        Say '  the page is a gate until then; opening it now shows a black box.' 'DarkGray'
        $ans = Read-Host '  open it anyway? (y/N)'
        if ($ans -notmatch '^(y|yes)$') { exit 0 }
    } elseif ($res.Status -eq 'scheduled') {
        Say ("  NOT POSTED YET -- " + $res.Note) 'Yellow'
        Say '  the event exists but no player is wired to it yet.' 'DarkGray'
        Say '  try again closer to tip-off, or watch NBA TV: NBA.bat' 'DarkGray'
        Start-Sleep -Seconds 2
        exit 0
    } elseif ($res.Status -eq 'final') {
        Say '  that game is already over -- the page is there, the feed is not.' 'Yellow'
        Say '  pick a live game, or NBA TV: NBA.bat' 'DarkGray'
        Start-Sleep -Seconds 2
        exit 0
    } else {
        Say ("  resolve: " + $res.Status + " " + $res.Note) 'DarkGray'
        Say '  opening the event page -- the player may still work there.' 'DarkGray'
    }

    # open it in Chrome/Firefox, not in whatever Windows calls the default
    # (that is Edge here, and Edge kept being the browser that came up).
    $exe = $null
    $txt = Join-Path $PSScriptRoot 'hub\browser.txt'
    $names = @{
        'chrome'  = "$env:ProgramFiles\Google\Chrome\Application\chrome.exe"
        'firefox' = "$env:ProgramFiles\Mozilla Firefox\firefox.exe"
        'edge'    = "${env:ProgramFiles(x86)}\Microsoft\Edge\Application\msedge.exe"
        'brave'   = "$env:ProgramFiles\BraveSoftware\Brave-Browser\Application\brave.exe"
    }
    if (Test-Path $txt) {
        $want = (Get-Content $txt | Where-Object { $_ -and -not $_.StartsWith('#') } | Select-Object -First 1)
        if ($want) {
            $want = $want.Trim()
            if ($names.ContainsKey($want.ToLower())) { $want = $names[$want.ToLower()] }
            if (Test-Path $want) { $exe = $want }
        }
    }
    if (-not $exe) {
        foreach ($k in 'chrome', 'firefox', 'brave', 'edge') {
            if (Test-Path $names[$k]) { $exe = $names[$k]; break }
        }
    }
    if ($exe) {
        Start-Process -FilePath $exe -ArgumentList $target
        Say ("  Opened in " + (Split-Path $exe -Leaf)) 'DarkGray'
    } else {
        Start-Process $target
        Say '  Opened in the default browser.' 'DarkGray'
    }
    Say '  Press play in the browser.' 'DarkGray'
    Start-Sleep -Seconds 2
}
