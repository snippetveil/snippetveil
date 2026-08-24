# Changelog

## Unreleased

- Copy Anonymized replaces project-owned names in a Java selection with stable placeholders and
  puts the result on the clipboard.
- Anonymize with Preview shows the anonymized text and its mapping in a dialog before anything is
  copied.
- De-anonymize Clipboard puts the real names back, on any file type.
- Export Mapping writes the placeholder-to-name mapping for one invocation.
- Unresolved references fail closed into an Unknown namespace rather than being left as written.
- Comments and javadoc are stripped by default, split by the parse verdict for the selection.
- A settings page under Tools carries the one persistent setting, the internal-organization package
  prefix, and a Reset Mappings action.
- The plugin makes no network calls and starts no subprocesses. `./gradlew check` asserts both
  against the built distribution rather than against the sources.
