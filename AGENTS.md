# molecule-viewmodel

Two Android library modules: `molecule-viewmodel` (runtime) and `molecule-viewmodel-test`
(the test harness).

- Build and test everything: `./gradlew build`
- The public API is locked by binary-compatibility-validator. After changing any public or
  protected declaration, run `./gradlew apiDump` and commit the updated `api/*.api` files, or
  the build fails.
- Usage rules for the library itself are in `.claude/skills/molecule-viewmodel/SKILL.md`.
