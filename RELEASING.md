# Releasing

1. Update `VERSION_NAME` in `gradle.properties` to the release version.
2. Update `CHANGELOG.md`.
3. Commit and tag `X.Y.Z`.
4. `./gradlew build apiCheck` at the tag. Publish only what just built green.
5. Push the branch and the tag.
6. `./gradlew publishAndReleaseToMavenCentral`
7. Bump `VERSION_NAME` to the next `-SNAPSHOT` and commit.
