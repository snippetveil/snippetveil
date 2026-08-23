# `demo/` — the project the screenshots are shot from

Business-shaped Java, invented, committed. It exists so that the Marketplace screenshots are
**reproducible at any commit** and re-shootable by anyone the day a dialog changes.

## Why a committed sample rather than a scratch project

Every alternative was worse.

- **A real work codebase is barred outright**, and committing anonymized real code as a corpus was
  already refused for the reason CONTRIBUTING.md gives: if it leaked, the leak would be in a public
  repository, permanently.
- **A scratch project on one machine** makes the shots unreproducible a year out. The dialog changes,
  the person who took them has a different laptop, and nobody can tell which of the differences
  between the old shot and the new one are the product.
- **The test fixtures** are deliberately pathological — they exist to break the anonymiser — and they
  would put a first-time browser's first impression on code nobody would ever write.

## It is not a Gradle subproject

**The build is fixed at `:core` + `:plugin`, and it stays that way.** `settings.gradle.kts` includes
those two and nothing else, and the release asserts that the shipped runtime classpath contains
`:core` and nothing at all besides. A third subproject perturbs both: it puts Java sources into a
build that compiles none, and it gives the emptiness assertion something new to be right about.

So this is **loose source**, opened as its own IDEA project, and **never on the distribution's
path**. `:plugin:assertTheDemoIsNotShipped` fails the build if a `demo/` file ever appears in the
zip that gets uploaded, and fails it too if `settings.gradle.kts` ever includes this directory.

## It uses the JDK and nothing else

There is no `pom.xml` and no `build.gradle.kts` here, and no dependency to resolve. Two reasons,
and both of them matter more than the convenience of a framework annotation:

- **A screenshot is a listing surface.** The Approval Guidelines forbid third-party brand references,
  and a shot of a class covered in another vendor's annotations is a third-party brand reference
  1280 pixels wide.
- **An unresolved name is anonymized, not preserved.** SnippetVeil is fail-closed: a name the IDE
  cannot resolve is replaced from an `Unknown` namespace of its own. A demo project with unresolved
  framework imports would show those names replaced — the exact opposite of the claim the shot is
  there to illustrate, which is that the libraries you call go as-is. `BigDecimal`, `Optional`,
  `UUID`, `Instant` and `ChronoUnit` resolve from the JDK alone, and they are preserved in the
  output because the plugin can see what they are.

## Opening it

1. **File > Open**, and choose this `demo` directory. Open it in a new window, not as a module of
   the plugin project — it is a separate project and the screenshots depend on it being one.
2. **File > Project Structure > Project**: set the SDK to any JDK 17 or newer.
3. **File > Project Structure > Modules**: mark `src/main/java` as *Sources* if the IDE has not
   already.
4. Confirm there are no red squiggles. Unresolved code anonymizes differently, so a project with
   import errors produces a shot that misrepresents the product.

Anything the IDE writes here — `.idea/`, `out/` — is already ignored by the repository's
`.gitignore`.

## What is in it, and what each file is for

| File | What it shows in a shot |
|---|---|
| `LateFeeCalculator.java` | The main subject. Project types, an interface it depends on, JDK types that survive, `BigDecimal` constants that survive as numbers, javadoc that gets stripped |
| `Invoice.java`, `InvoiceLine.java`, `Customer.java` | The domain vocabulary the anonymiser replaces — the reason the shots read as a business rather than as a puzzle |
| `DunningRun.java` | A snippet where two different symbols share one source name, which is what makes the preview's fidelity notice fire |
| `SettlementPolicy.java`, `InvoiceRepository.java`, `InvoiceStatus.java`, `PaymentTerms.java` | The rest of the package, so that names resolve and the shots are of resolved code |

Every name in here is invented. There is no Harborlight.

## Shooting the screenshots

See [`docs/screenshots/README.md`](../docs/screenshots/README.md), which lists the five shots, the
selection each one is taken from, and the rules they all have to meet.
