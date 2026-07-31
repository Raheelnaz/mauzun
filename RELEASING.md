# Releasing

1. Update `VERSION_NAME` in `gradle.properties` to the release version.
2. Update `CHANGELOG.md`.
3. Commit, tag `X.Y.Z`, and push.
4. `./gradlew publishAndReleaseToMavenCentral`
5. Bump `VERSION_NAME` to the next `-SNAPSHOT` and commit.
