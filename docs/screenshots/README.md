# The Marketplace screenshots

Six shots, taken from [`demo/`](../../demo/README.md) so that any of them can be re-taken at any
commit by anyone.

They are committed here rather than living only in the Marketplace listing, for the reason the demo
project is committed: a listing image with no source is an image nobody can reproduce, and a shot
that cannot be reproduced cannot be corrected when the dialog it shows moves on.

## Rules every shot has to meet

- **1280 × 800**, and the same aspect for all six. The Marketplace scales them together; one odd
  shot reads as a mistake in the listing rather than as a difference in the product.
- **No window chrome.** Capture the IDE's content, not the title bar, the traffic lights or the
  desktop behind them.
- **No personal information.** No account name in the title bar, no avatar, no file path containing
  a person's home directory, no branch name from a real repository, no notification from another
  plugin.
- **No third-party advertisement**, which in practice means no other plugin's toolbar button, badge
  or balloon in frame.
- **Default theme**, at default zoom, in a window sized so the code is legible when the image is
  scaled to a listing card.
- **Nothing but `demo/` on screen.** Close every other project window and every other editor tab.

## The shots

### 1. `right-click-copy-anonymized.png`

The editor popup, open, with **Copy Anonymized** visible under the **SnippetVeil** submenu.

**Selection: `LateFeeCalculator.java`, lines 30–59** — the javadoc on `feeFor` through the method's
closing brace. Right-click inside the selection, hover **SnippetVeil**. The shot is the selection and
the open submenu together: the point of it is that the entry point is the menu you were already
using.

**The javadoc is inside the selection deliberately.** It is what makes the stripped-comment count
non-zero in shots 2 and 4, and a listing that showed a zero there would be illustrating the one
disclosure this product most wants read.

### 2. `right-click-anonymize-with-preview.png`

The same popup, the same selection, with **Anonymize with Preview…** highlighted instead.

Two shots of one menu is a repetition worth paying for: the two items are two different promises —
one copies, one stops and shows you what it is about to copy — and the difference between them is
the ellipsis, which is not a thing a reader spots in a single frame.

### 3. `preview-dialog.png`

**Anonymize with Preview…** on the same selection — `LateFeeCalculator.java`, lines 30–59 — showing
the anonymized code beside its mapping table, with the counts strip along the bottom.

This is the shot that carries the product. The mapping table should be scrolled to the top, so that
the first rows are the type and method names a reader recognises from the code beside them.

### 4. `preview-fidelity-notice.png`

The preview again, on a selection that makes a fidelity notice fire.

**Selection: `DunningRun.java`, lines 27–81** — `remindersFor` together with the whole `Reminder`
class below it. `customer`, `overdue` and `fees` are each both a field and a method there, so several
renamed symbols share one source name and the preview says so. The notice is the subject of the shot
— frame it so the sentence is readable rather than implied.

### 5. `copy-balloon-with-counts.png`

The balloon after a plain **Copy Anonymized**, showing the counts and the **Show mapping** action.

Take it from the selection in shot 1, so that a reader moving between the two shots is looking at
one story rather than two.

**Turn off the code author inlay first** — `Settings > Editor > Inlay Hints > Code Vision > Code
author`. It renders the name of whoever last touched the line, from `git blame`, in the editor
beside the method signature. In shots 1 and 2 the context menu happens to cover it; nothing covers
it here, and a real person's name in a listing image is the one rule in this file with no judgement
in it.

### 6. `right-click-deanonymize-and-paste.png`

The same popup, with **De-anonymize Clipboard and Paste** highlighted.

**Selection: `LateFeeCalculator.java`, lines 30-59**, as in shots 1 and 2.

A third shot of one menu, which the rule against repetition has to be argued past rather than
waved at. It earns the slot on the same ground shot 2 does, and more strongly: this is the only item
in the submenu that **writes into a source file** rather than onto the clipboard, and a reader
deciding whether to install has to be able to see that the plugin has a row that does that. The
ellipsis distinction in shot 2 is a smaller difference than this one.

**Last among the stills rather than third, because the set is a round trip.** Shots 1-5 go out —
select, preview, copy, read the counts — and this is the return leg, the reply coming back into the
editor. Grouped with the other menu shots it would put three near-identical frames at the front of
the listing, which is where a reader decides whether to keep looking.

### 7. `walkthrough.gif` (optional)

Roughly ten seconds, no audio: select, **Copy Anonymized**, paste into a scratch buffer, then
**De-anonymize Clipboard** on a reply pasted back. Same window, same theme, same rules as above.

It is optional because a GIF that is merely the four shots in sequence adds nothing; it earns its
place only by showing the round trip, which no still can.

## Capturing them

macOS puts a drop shadow and a title bar on a window capture, and both are chrome. Capture a
**region** instead, from a window sized larger than the region:

```
# 8 seconds to open the menu or the dialog, then a 1280x800 region with its top-left at (x, y)
screencapture -T 8 -R <x>,<y>,1280,800 docs/screenshots/<name>.png

# On a Retina display the file comes out at 2560x1600. Down to the listing size:
sips -z 800 1280 docs/screenshots/<name>.png
```

Keep `<x>,<y>` the same for all six so the shots line up, and place the region well inside the
editor area rather than against the window's edges.

## What is here

All six stills, shot from `demo/` in a sandbox IDE and normalised to 1280 x 800: each scaled to fit
and centred on a ground sampled from its own edge, so the letterboxing is invisible and the set
reads as one product rather than as six window sizes.

`assertBothPluginIconsShip` fails the build if any image other than the two icons reaches the
distribution. That rule exists because of how these were made: a screenshot has to live somewhere
while it is being cut, and `plugin/src/main/resources/` is a directory one lands in without anybody
deciding it should — from there it is in the jar, in the distribution, and on every user's disk,
and nothing else in the build would have noticed.

**Shoot at 2560 pixels wide or narrower.** A capture on a Retina display is twice the logical size,
so a 2560-wide shot scales to 1280 at exactly the size the UI is drawn at, and a wider one arrives
smaller than natural. `preview-fidelity-notice.png` is the one that was shot wide — 3442 px — which
put its notice, the only reason that shot exists, at 74% of natural size. The committed version
crops to the code pane, the first column of the table and the notice rather than scaling all of it
down. A re-shoot from a dialog at its designed width would carry the whole of it at the same
legibility; the dialog remembers whatever width it was last dragged to, so the fix is to drag it
back rather than to reframe the capture.

`walkthrough.gif` is optional and was not attempted.
