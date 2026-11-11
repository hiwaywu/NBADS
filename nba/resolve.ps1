# resolve.ps1 -- walk the rotating player chain for one game, hand back a URL that opens.
#
#   .\resolve.ps1 -Game https://methstreams.st/event/m-la-clippers-vs-phoenix-suns-1111
#   .\resolve.ps1 -Game <event url> -Main 2 -Open
#   .\resolve.ps1 -Game <event url> -Json
#
# The chain as it stood 2026-11-10:
#   index    methstreams.st / crackstreams.st                     (stable)
#   event    /event/m-<away>-vs-<home>-MMDD                       (MMDD = UTC date of tip-off)
#   player   <rotating>/event/.../main/N                          (today: foxiy.st)
#   embed    <rotating>/tai/nba/YYYY-MM-DD/<away>-<home>           (today: trendy48.site)
#   source   <rotating>/embed-noads/nba/.../js/bundle-jw.js        (today: taifood-blog.asia)
#
# Nothing in that chain contains an .m3u8 -- the playlist is minted by the JW bundle
# once the player loads, with short-lived tokens. refresh.ps1 can therefore never
# harvest a game feed; resolve per session. (Channel feeds in feeds.txt are different;
# those really are plain playlists.)
#
# States you will actually hit:
#   scheduled  event page has <time data-utc> + chip "Scheduled", no player yet
#   gated      player page is <div class="player is-gated"> with data-open in the future
#   live       player page has the real <iframe id="streamIframe">
#   final      chip "Final" -- game over, page still there, feed gone
param(
    [string]$Game = '',      # not Mandatory: games.ps1 dot-sources this file
    [int]$Main = 1,
    [switch]$Open,
    [switch]$Json,
    [switch]$Quiet
)

$script:UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36'

# -Json and -Quiet both silence the trace; -Json keeps stdout clean for a pipe.
function Say($m, $c = 'Gray') { if (-not $Quiet -and -not $Json) { Write-Host $m -ForegroundColor $c } }

function Get-Page([string]$url, [string]$referer) {
    $cargs = @('-sSLk', '-m', '25', '-A', $script:UA)
    if ($referer) { $cargs += @('-H', "Referer: $referer") }
    $body = (& curl.exe @cargs $url 2>$null) -join "`n"
    return $body
}

function ConvertFrom-Unix([string]$s) {
    if (-not $s) { return $null }
    $n = 0L
    if (-not [int64]::TryParse($s, [ref]$n)) { return $null }
    if ($n -lt 1000000000) { return $null }
    return [DateTimeOffset]::FromUnixTimeSeconds($n).ToLocalTime()
}

function Join-Url([string]$base, [string]$href) {
    if (-not $href) { return $null }
    if ($href -match '^https?://') { return $href }
    try {
        $b = [uri]$base
        return ("$($b.Scheme)://$($b.Host)$href")
    } catch { return $null }
}

# Tip-off as the event page states it, plus the status chip. Cheap: one request.
function Get-EventStub([string]$eventUrl) {
    $o = [pscustomobject]@{
        Event    = $eventUrl
        StartsAt = $null
        Chip     = ''
        HasPlayer = $false
        Ok       = $false
    }
    $h = Get-Page $eventUrl
    if (-not $h) { return $o }
    $o.Ok = $true

    $u = [regex]::Match($h, 'data-utc="(?<u>[^"]+)"').Groups['u'].Value
    if ($u) {
        try { $o.StartsAt = [DateTimeOffset]::Parse($u).ToLocalTime() } catch { }
    }
    $c = [regex]::Match($h, 'class="ev-chip">(?<c>[^<]+)<').Groups['c'].Value
    if ($c) { $o.Chip = $c.Trim() }
    $o.HasPlayer = ($h -match 'id="streamIframe"') -or ($h -match 'data-e="http')
    return $o
}

function Resolve-Game([string]$eventUrl, [int]$mainIndex = 1) {
    $r = [pscustomobject]@{
        Event       = $eventUrl
        PlayerUrl   = $null
        PlayerHost  = $null
        EmbedUrl    = $null
        EmbedHost   = $null
        Mirrors     = @()
        Gated       = $false
        OpensAt     = $null
        StartsAt    = $null
        MinutesToGo = $null
        Status      = 'unknown'
        Note        = ''
    }

    Say ("  event page  " + $eventUrl) 'DarkGray'
    $eventHtml = Get-Page $eventUrl
    if (-not $eventHtml) { $r.Status = 'event-page-unreachable'; $r.Note = 'the index site did not answer'; return $r }

    # tip-off + chip ("Scheduled", "Final", ...) straight from the event page
    $u = [regex]::Match($eventHtml, 'data-utc="(?<u>[^"]+)"').Groups['u'].Value
    if ($u) { try { $r.StartsAt = [DateTimeOffset]::Parse($u).ToLocalTime() } catch { } }
    $chip = [regex]::Match($eventHtml, 'class="ev-chip">(?<c>[^<]+)<').Groups['c'].Value
    $chip = if ($chip) { $chip.Trim() } else { '' }

    if ($eventHtml -match 'id="streamIframe"') {
        # some pages carry the player inline -- take it and stop
        $r.EmbedUrl = [regex]::Match($eventHtml, '<iframe[^>]+src="(?<u>[^"]+)"').Groups['u'].Value
        if ($r.EmbedUrl) { try { $r.EmbedHost = ([uri]$r.EmbedUrl).Host } catch { } }
        $r.PlayerUrl = $eventUrl
        try { $r.PlayerHost = ([uri]$eventUrl).Host } catch { }
        $r.Status = 'live'
        $r.Note = 'player embedded on the event page'
        Say ("  embed       " + $r.EmbedUrl) 'DarkGray'
        return $r
    }

    # hop 3: the player page lives on a rotating host
    $all = [regex]::Matches($eventHtml, 'https?://[^"''<>\s]+?/main/\d+') | ForEach-Object { $_.Value } | Select-Object -Unique
    if (-not $all -and $eventHtml -match 'href="(?<u>/[^"]*?/main/\d+)"') { $all = @(Join-Url $eventUrl $Matches['u']) }
    $r.Mirrors = @($all)
    $playerUrl = $all | Where-Object { $_ -match "/main/$mainIndex$" } | Select-Object -First 1
    if (-not $playerUrl) { $playerUrl = $all | Select-Object -First 1 }

    if (-not $playerUrl) {
        if ($chip -eq 'Final') {
            $r.Status = 'final'
            $r.Note = 'game is over -- the page is still up but the feed is gone'
        } else {
            $r.Status = 'scheduled'
            if ($r.StartsAt) { $r.Note = ('no player until around {0:ddd HH:mm}' -f $r.StartsAt) }
            else { $r.Note = 'event page lists no player yet' }
        }
        return $r
    }

    $r.PlayerUrl = $playerUrl
    try { $r.PlayerHost = ([uri]$playerUrl).Host } catch { }
    Say ("  player      " + $playerUrl) 'DarkGray'
    if ($all.Count -gt 1) {
        Say ("  mirrors     " + (($all | ForEach-Object { ($_ -split '/main/')[-1] }) -join ', ')) 'DarkGray'
    }

    $pHtml = Get-Page $playerUrl $eventUrl
    if (-not $pHtml) { $r.Status = 'player-unreachable'; $r.Note = 'the player host did not answer'; return $r }

    $r.OpensAt = ConvertFrom-Unix ([regex]::Match($pHtml, 'data-open="(\d+)"').Groups[1].Value)
    $startRaw = ConvertFrom-Unix ([regex]::Match($pHtml, 'data-start="(\d+)"').Groups[1].Value)
    if (-not $r.StartsAt -and $startRaw) { $r.StartsAt = $startRaw }

    # prefer the real iframe; ignore the chat frame
    $frames = [regex]::Matches($pHtml, '<iframe[^>]+src="(?<u>[^"]+)"') | ForEach-Object { $_.Groups['u'].Value } | Where-Object { $_ -notmatch 'chat\.' }
    $iframe = $frames | Select-Object -First 1
    if (-not $iframe) {
        # mirror links carry the embed in data-e (data-q=stream=N picks the variant)
        $iframe = [regex]::Match($pHtml, 'data-e="(?<u>[^"]+)"').Groups['u'].Value
    }
    if ($iframe) {
        $r.EmbedUrl = $iframe
        try { $r.EmbedHost = ([uri]$iframe).Host } catch { }
        Say ("  embed       " + $iframe) 'DarkGray'
    }

    $isGated = ($pHtml -match 'player\s+is-gated') -or (-not $iframe)
    $now = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    if ($r.OpensAt) {
        $secs = [int64](([DateTimeOffset]$r.OpensAt).ToUnixTimeSeconds() - $now)
        $r.MinutesToGo = [math]::Round($secs / 60.0, 1)
        if ($secs -gt 0) { $isGated = $true }
    }
    $r.Gated = $isGated

    if ($isGated) {
        $r.Status = 'gated'
        if ($r.OpensAt) { $r.Note = ('opens {0:HH:mm} (in {1} min)' -f $r.OpensAt, [math]::Max(0, [int]$r.MinutesToGo)) }
        elseif ($r.StartsAt) { $r.Note = ('not open yet -- tip-off {0:ddd HH:mm}' -f $r.StartsAt) }
        else { $r.Note = 'player is gated and the page carries no data-open' }
    } elseif ($r.EmbedUrl) {
        $r.Status = 'live'
        $r.Note = 'player unlocked, embed resolved'
    } elseif ($chip -eq 'Final') {
        $r.Status = 'final'
        $r.Note = 'game is over'
    } else {
        $r.Status = 'unlocked-no-embed'
        $r.Note = 'not gated but no iframe/data-e found -- open the player page in a browser'
    }
    return $r
}

# ---- run (skipped when dot-sourced by games.ps1 / the hub)
if ($MyInvocation.InvocationName -ne '.') {

    if (-not $Game) {
        Write-Host '  usage: .\resolve.ps1 -Game <event url> [-Main 1..3] [-Open] [-Json]' -ForegroundColor Yellow
        exit 1
    }

    $res = Resolve-Game -eventUrl $Game -mainIndex $Main

    if ($Json) {
        $res | ConvertTo-Json -Depth 4
    } else {
        $colour = 'Red'
        if ($res.Status -eq 'live') { $colour = 'Green' }
        elseif ($res.Status -eq 'gated' -or $res.Status -eq 'scheduled') { $colour = 'Yellow' }
        Say ''
        Say ('  STATUS   ' + $res.Status.ToUpper()) $colour
        if ($res.Note) { Say ('  ' + $res.Note) 'Gray' }
        if ($res.StartsAt) { Say ('  tip-off  {0:ddd MMM d HH:mm}' -f $res.StartsAt) 'DarkGray' }
        Say ''
        if ($res.PlayerUrl) { Say ('  open:  ' + $res.PlayerUrl) 'Cyan' }
        if ($res.EmbedUrl) { Say ('  embed: ' + $res.EmbedUrl) 'DarkCyan' }
        Say ''
    }

    if ($Open -and $res.PlayerUrl) {
        Start-Process $res.PlayerUrl
        Say '  opened in the default browser' 'DarkGray'
    }

    if ($res.Status -eq 'live') { exit 0 }
    if ($res.Status -eq 'gated' -or $res.Status -eq 'scheduled') { exit 3 }
    exit 2
}
