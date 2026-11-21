# Releasing BetterAntiDupe

Cutting a release happens by pushing a `v*` git tag to GitHub. The GitHub Actions workflow in `.github/workflows/release.yml` builds the single jar, creates the GitHub release, and publishes the same jar and changelog to Modrinth automatically.

## 1. Before you tag

1. Update `version` in `build.gradle.kts`. That also sets the `:api` module version and fills in `plugin.yml`.
2. Add a new `## [X.Y.Z] - YYYY-MM-DD` section at the top of `CHANGELOG.md`. The release workflow takes the first `## [` section as the release notes for both GitHub and Modrinth.
3. If this release adds support for a new Minecraft version, add it to `MC_VERSIONS` in `.github/workflows/release.yml`.
4. Run a full local build and test pass to be sure the shrunk jar builds cleanly:

```bash
./gradlew build
```

This produces `build/libs/BetterAntiDupe-X.Y.Z.jar`.

## 2. Cut the release on GitHub

Commit your changes and push `master`, then create and push the version tag (for example `v4.9.5`):

```bash
git push origin master
git tag vX.Y.Z
git push origin vX.Y.Z
```

What `.github/workflows/release.yml` does automatically when a `v*` tag is pushed:

1. Builds `build/libs/BetterAntiDupe-X.Y.Z.jar` using JDK 25 (with JDK 21 jmods for ProGuard).
2. Extracts the top `## [` section of `CHANGELOG.md` into the release notes.
3. Creates a GitHub release titled `vX.Y.Z` with `BetterAntiDupe-X.Y.Z.jar` attached.
4. Uploads the same jar, changelog, loaders (`paper purpur folia spigot bukkit`), and `MC_VERSIONS` to Modrinth (project `dlVLKfYZ`) using the repository's `MODRINTH_TOKEN` secret. Tag names containing `alpha`, `beta`, or `rc` are marked accordingly; everything else is published as a full `release`.

Note on older servers: servers running `4.8.0` or older `-mc26` / `-mc263` builds follow the newest version matching their suffix, and fall back to the plain version number once no matching suffixed release is newer.

## 3. Manual follow-ups

- Upload `build/libs/BetterAntiDupe-X.Y.Z.jar` to SpigotMC (Spigot has no automated upload step in the workflow).
- If `MODRINTH.md` or `SPIGOT.md` changed, paste the updated text onto the Modrinth or SpigotMC listing page.
