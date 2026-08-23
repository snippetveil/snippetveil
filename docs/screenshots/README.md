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

## The five

### 1. `right-click-copy-anonymized.png`

The editor popup, open, with **Copy Anonymized** visible under the **SnippetVeil** submenu.

Select the body of `LateFeeCalculator.feeFor` in `LateFeeCalculator.java`, right-click inside the
selection, hover **SnippetVeil**. The shot is the selection and the open submenu together — the
point of it is that the entry point is the menu you were already using.

### 2. `preview-dialog.png`

**Anonymize with Preview…** on the same selection, showing the anonymized code beside its mapping
table, with the counts strip along the bottom.

This is the shot that carries the product. The mapping table should be scrolled to the top, so that
the first rows are the type and method names a reader recognises from the code beside them.

### 3. `preview-fidelity-notice.png`

The preview again, on a selection that makes a fidelity notice fire.

Select the whole of `DunningRun.remindersFor` together with the `Reminder` class below it: `customer`
is both a field and a method there, so two renamed symbols share one source name and the preview
says so. The notice is the subject of the shot — frame it so the sentence is readable rather than
implied.

### 4. `copy-balloon-with-counts.png`

The balloon after a plain **Copy Anonymized**, showing the counts and the **Show mapping** action.

Take it from the selection in shot 1, so that a reader moving between the two shots is looking at
one story rather than two.

### 5. `walkthrough.gif` (optional)

Roughly ten seconds, no audio: select, **Copy Anonymized**, paste into a scratch buffer, then
**De-anonymize Clipboard** on a reply pasted back. Same window, same theme, same rules as above.

It is optional because a GIF that is merely the four shots in sequence adds nothing; it earns its
place only by showing the round trip, which no still can.

## Status

**The images are not in this directory yet.** Shooting them needs a running sandbox IDE
(`./gradlew runIde`) and a person at the screen. Everything they need to be reproducible — the
project, the selections, and the rules — is committed; the capture is the step that is not.
