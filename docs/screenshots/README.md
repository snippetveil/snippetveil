# The Marketplace screenshots

Five shots, taken from [`demo/`](../../demo/README.md) so that any of them can be re-taken at any
commit by anyone.

They are committed here rather than living only in the Marketplace listing, for the reason the demo
project is committed: a listing image with no source is an image nobody can reproduce, and a shot
that cannot be reproduced cannot be corrected when the dialog it shows moves on.

## Rules every shot has to meet

- **1280 × 800**, and the same aspect for all five. The Marketplace scales them together; one odd
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

### 6. `walkthrough.gif` (optional)

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

Keep `<x>,<y>` the same for all five so the shots line up, and place the region well inside the
editor area rather than against the window's edges.

## What is here

Four of the six, shot from `demo/` in a sandbox IDE, normalised to 1280 x 800: each scaled to fit
and centred on a ground sampled from its own edge, so the set reads as one product rather than as
four window sizes. `assertBothPluginIconsShip` fails the build if any image other than the two icons
reaches the distribution — screenshots are cut somewhere while they are being worked on, and
`plugin/src/main/resources/` is a directory one lands in without anybody deciding it should.

Still outstanding:

- **`copy-balloon-with-counts.png`** — shot, and not committed: the code author inlay was on, so a
  real person's name is in the frame beside line 36. Re-shoot with the setting off, per shot 5.
- **`preview-fidelity-notice.png` is a crop, not the whole dialog.** It was shot from a window 3442
  pixels wide, and scaling all of that down to 1280 puts the notice — the subject — at a size nobody
  reads. The committed version crops to the code pane, the first column of the table and the notice.
  A re-shoot from a window around 1600 points wide would carry the whole dialog at the same
  legibility.
- **`walkthrough.gif`** — optional, and not attempted.
