# The Marketplace screenshots

Nine shots, taken from [`demo/`](../../demo/README.md) so that any of them can be re-taken at any
commit by anyone.

They are committed here rather than living only in the Marketplace listing, for the reason the demo
project is committed: a listing image with no source is an image nobody can reproduce, and a shot
that cannot be reproduced cannot be corrected when the dialog it shows moves on.

## Rules every shot has to meet

- **1280 × 800**, and the same aspect for all nine. The Marketplace scales them together; one odd
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
non-zero in `preview-dialog.png` and `copy-balloon-with-counts.png`, and a listing that showed a
zero there would be illustrating the one disclosure this product most wants read.

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

**The committed frame predates the unlock and has to be re-shot.** It shows the dialog with nothing
under the mapping table, and the shipped dialog carries the `Unlock Preserve for resolved names…`
link there. A listing image missing a control the reader will find on their own screen is the exact
failure this directory exists to prevent, and it is worse than a missing image: it is a picture of a
product that no longer exists.

**Shoot 3, 4, 5 and 6 from one opening of the dialog, in that order.** They are four states of one
preview, and the numbers make that a requirement rather than a preference — a placeholder's number
is burnt when it is handed out, so a second invocation on the same selection re-mints every local
and parameter while the types, methods and fields keep the numbers they already have. Four frames
from four openings would disagree with each other down the middle of the table.

One opening: capture the dialog, click the unlock and capture the warning, **Cancel** it, rename two
rows and capture that, then unlock again and mean it, tick two names and capture that. The cancel is
what leaves the Preserve column in shot 4 looking the way it does in shot 3, so that the only thing
changing between any two frames is the thing that frame is about. Ticking a name re-runs the plan,
so the locals and fields in shot 6 carry different numbers from the ones in shots 3 to 5 — that is
the preserve being applied, not the set drifting.

### 4. `preview-rename-placeholder.png`

The same preview, on the same selection, with the **Placeholder** column being edited: one row
already carrying a stem of its own, and one open in its cell editor with the caret in it.

**Selection: `LateFeeCalculator.java`, lines 30–59**, as in shot 3, so the two frames read as one
dialog a moment apart rather than as two dialogs.

**Two rows in two states, because one frame has to carry both halves of the feature.** A committed
rename is only a word in a table — nothing in it tells a reader they could have typed it. An open
editor says the cell takes typing, and shows the number sitting outside the editable text, which is
the promise the whole feature rests on: the stem is yours and the number always stays.

**Rename to a hint, never to the real name.** The pair to type is `OverdueType` on the `Invoice`
row, committed, and `charge` on the `feeFor` row, left open in its editor: both are words that tell
a model which symbol the question is about, and neither is the name that was replaced. A shot of a
placeholder renamed back to the identifier it stands for would illustrate a use the product does not
have — the real name never leaves the machine, and a listing image is a poor place to suggest
otherwise.

**Do not quote the numbers in this file.** Which number each row carries depends on how many
placeholders the sandbox IDE has minted before the shot, and the counter never rewinds. The rows are
named here by the symbol they stand for for exactly that reason.

### 5. `preview-unlock-preserve-warning.png`

The unlock's confirmation, open over the preview, with the empty **Preserve** column and the
`Unlock Preserve for resolved names…` link visible behind it.

**Selection: `LateFeeCalculator.java`, lines 30–59**, again. Every name in `demo/` resolves, so the
Preserve column behind the warning is empty — which is not a flat shot but the exact case the unlock
exists for: nothing in this table can be preserved until you ask for it.

**The sentence is the subject.** Frame it so all three lines are readable — *Preserved names are
sent exactly as written in your code. SnippetVeil will not conceal a name you tick. Only preserve
names you would be comfortable typing into the chat yourself.* — together with the two buttons,
`I understand, unlock` and `Cancel`. The button that carries it out is named for the decision rather
than `OK`, and that is half of what the shot is showing.

**The question is drawn inside the warning, not in a title bar.** *Unlock Preserve for all names?*
is the first line of the modal itself, above the three sentences and beside the platform's warning
icon, so the whole subject of this shot is one rectangle and the crop is the dialog behind it.

**A warning is an odd thing to put in a listing, and it is the strongest frame in the set.** The
product's claim is that it never quietly reduces what it conceals; a shot of it stopping to say so,
in a dialog that has no *don't warn me again*, demonstrates that claim instead of asserting it.

### 6. `preview-preserve-unlocked.png`

The column the warning opened, and what a tick costs. Every row carries a checkbox now, two of them
are ticked, and the two names those rows stand for are in the code beside the table exactly as they
are written in the source.

**Same opening of the dialog as shots 3, 4 and 5, and tick one local and one method** — `daysLate`
and `dueOn`. One of each, so the frame cannot be read as a rule about one kind of name.

**The warning is the question and this is the answer, which is why both are here.** A reader who
sees only the warning has been shown a product that asks; this shows what it does when told to go
ahead, and it is not flattering by accident — the counts strip moves from `19 renamed · 0 unknown ·
15 preserved` to `17 renamed · 0 unknown · 17 preserved` in the same frame as the ticks, and the
Placeholder cell of a preserved row reads `—`, because there is no placeholder to name. A reduction
that shows up in the count the product reports about itself is a reduction a reader can audit.

**If the listing ever has to give a slot back, give this one.** It is the only frame in the set
showing a reduction taken rather than offered, and the warning above carries the decision without
it.

### 7. `preview-fidelity-notice.png`

The preview again, on a selection that makes a fidelity notice fire.

**Selection: `DunningRun.java`, lines 27–81** — `remindersFor` together with the whole `Reminder`
class below it. `customer`, `overdue` and `fees` are each both a field and a method there, so several
renamed symbols share one source name and the preview says so. The notice is the subject of the shot
— frame it so the sentence is readable rather than implied.

### 8. `copy-balloon-with-counts.png`

The balloon after a plain **Copy Anonymized**, showing the counts and the **Show mapping** action.

Take it from the selection in shot 1, so that a reader moving between the two shots is looking at
one story rather than two.

**Turn off the code author inlay first** — `Settings > Editor > Inlay Hints > Code Vision > Code
author`. It renders the name of whoever last touched the line, from `git blame`, in the editor
beside the method signature. In shots 1 and 2 the context menu happens to cover it; nothing covers
it here, and a real person's name in a listing image is the one rule in this file with no judgement
in it.

### 9. `right-click-deanonymize-and-paste.png`

The same popup, with **De-anonymize Clipboard and Paste** highlighted.

**Selection: `LateFeeCalculator.java`, lines 30-59**, as in shots 1 and 2.

A third shot of one menu, which the rule against repetition has to be argued past rather than
waved at. It earns the slot on the same ground shot 2 does, and more strongly: this is the only item
in the submenu that **writes into a source file** rather than onto the clipboard, and a reader
deciding whether to install has to be able to see that the plugin has a row that does that. The
ellipsis distinction in shot 2 is a smaller difference than this one.

**Last among the stills rather than third, because the set is a round trip.** Shots 1-8 go out —
select, preview, edit the preview, copy, read the counts — and this is the return leg, the reply
coming back into the editor. Grouped with the other menu shots it would put three near-identical
frames at the front of the listing, which is where a reader decides whether to keep looking.

### 10. `walkthrough.gif` (optional)

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

Keep `<x>,<y>` the same for all nine so the shots line up, and place the region well inside the
editor area rather than against the window's edges.

**Or capture the window and cut the shadow off afterwards**, which is how the three preview frames
were made and is the easier method when the subject is a dialog. `⌘⇧4` then `Space`, aimed at the
dialog, writes the window and its drop shadow with an alpha channel around them. The shadow is
chrome, and it is chrome a machine can remove without any judgement: the dialog's own pixels are the
flood-filled region of its chrome colour, and everything outside that box is shadow. Three captures
that were three different sizes came out the same size to the pixel that way. What follows is
unchanged — scale to fit 1280 × 800, centre on a ground sampled from the image's own edge.

## What is here

All nine stills, shot from `demo/` in a sandbox IDE and normalised to 1280 x 800: each scaled to
fit and centred on a ground sampled from its own edge, so the letterboxing is invisible and the set
reads as one product rather than as nine window sizes.

**Two of them are known to be wrong, and one opening of the dialog fixes both.** Neither has been
uploaded to the listing yet:

- **`preview-dialog.png` predates the unlock**, as shot 3 says. It is the only frame that
  misrepresents the dialog rather than merely aging, and the listing must not be updated while it is
  the frame carrying the product.
- **`preview-rename-placeholder.png` renames the row below the one it meant to.** `OverdueType`
  landed on the `invoice` parameter instead of on the `Invoice` type, so a parameter carries a stem
  naming a type while the type above it still reads `Type154`. What the frame is for it does show
  correctly — that the cell takes typing, and that the number sits outside the editable text where
  it cannot be typed over.

**The numbers in the three preview frames start at 153, and that is not a defect.** The sandbox they
were shot in had minted that many already, and a number is burnt when it is handed out. A mature
project's preview looks like these frames rather than like the `1`-to-`19` ones shot in August, so
re-shooting for lower numbers would be making the product look newer than it is.

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
