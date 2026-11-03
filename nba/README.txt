NBA -- one click stream launcher
===============================

START HERE
----------
NBAHUB.bat      (desktop icon: "NBA Hub")   <-- the nice one, use this
                A local page: games listed by date on the left, NBA TV under
                them, player on the right. Pick a game, it opens the player.

The older one-click icon still works:
NBA.bat         Plays NBA TV straight away, no page, no list.


NBAHUB  --  what you see
------------------------
  left side, GAMES      today's slate; arrows change the day; if there is
                        nothing today it jumps to the next date that has games
  left side, NBA TV     four always-on 1080p channels, they play in the page
                        at max quality with a local buffer
  right side            the video

  click a game   ->  its best feed opens in its own clean player window
                     (Chrome app mode: no tabs, no address bar)
  click a channel -> plays right in the page, 1080p, ~40-80 s buffered

The date header is live: "Today / Tomorrow / Mon..." always follows the clock,
and the game list is fetched fresh from the stream sites each time you open it.


THE OLD ICONS (still work)
--------------------------
NBA.bat     Play NBA TV. Best quality, buffered, fullscreen.            <-- main
GAMES.bat   List tonight's games, pick one, opens it in your browser.
CHECK.bat   Show every link with its resolution and real speed.
REFRESH.bat Hunt for new links when the list starts failing.

Desktop shortcuts for all four already exist.


NBA.bat  --  what actually happens
----------------------------------
  1. tests every link in the list,
  2. picks the highest-quality one that is really playing,
  3. buffers ~40-60 s of it locally on 127.0.0.1,
  4. opens it fullscreen in VLC.

Why the local buffer: the upstream site hands out segments on its own schedule.
One slow segment normally freezes the picture. Here the player only ever reads
from localhost, and bad segments are retried and skipped instead of stalling.
Trade-off: you run 40-60 s behind live. That is the price of no stutter.

Why the quality is maxed: the launcher reads the HLS master playlist and
force-selects the highest bitrate rendition. The site cannot downgrade you to
480p. On the main feed that is 1920x1080 @ ~60 fps, 6-8 Mbps.


GAMES.bat  --  for a specific game
----------------------------------
NBA TV is a channel; it only carries the games it carries. For a specific game
(say Heat vs Lakers on Nov 3):

  Double-click GAMES.bat  ->  it lists the slate with dates
  type the number         ->  that game's stream page opens in your browser
  click "Main 1"          ->  press play

The browser route is deliberate: those pages gate the player behind a click and
an ad, and your ad blocker cleans that up better than a script can.


THE FILES
---------
feeds.txt    your permanent channel list.   Format:  NAME | URL  (top tried first)
live.txt     written by REFRESH.bat, read before feeds.txt
sites.txt    pages REFRESH.bat scans
play.ps1     launcher    (don't delete)
games.ps1    game list   (don't delete)
refresh.ps1  link hunter (don't delete)
check.ps1    quality check
relay.py     the local buffer + rendition picker (don't delete)
sniff.py     experimental: headless-browser stream sniffer (not wired in)
rec\         recordings land here
relay.log    buffer log, useful if something looks wrong


SWITCHES
--------
  .\play.ps1 -Menu         pick a feed by number instead of auto
  .\play.ps1 -LowLatency   half the buffer, ~20 s behind live
  .\play.ps1 -Record       save the stream to rec\ as .ts while watching
  .\play.ps1 -NoRelay      skip the buffer, hit the site directly
  .\play.ps1 -Check        same as CHECK.bat


IF IT SAYS "NOTHING IS PLAYING"
-------------------------------
  1. Run REFRESH.bat. It re-tests everything and scans for new links.
  2. Open feeds.txt in Notepad, paste a link you found, save, double-click NBA.bat:
        MY LINK | https://something/index.m3u8
  3. Only one line is needed. Everything else is optional.
