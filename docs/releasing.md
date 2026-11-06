# Releasing

Release automation is tracked by [#53](https://github.com/splincode-taiga-labs/taiga-ui-jetbrains-plugin/issues/53).

Publishing is supported through two entry points:

- **Recommended:** publish a GitHub Release with an existing `vX.Y.Z` tag. The `Publish to JetBrains Marketplace` workflow automatically checks out that tag, builds, verifies, signs, and publishes the plugin, then attaches the signed ZIP to the GitHub Release.
- **Legacy/manual:** run **Actions → Release → Run workflow** on `main`. This workflow generates changelog/release notes, commits and tags the release, verifies and publishes the plugin, and creates the GitHub Release with a signed ZIP.

Pull requests and ordinary pushes never publish. GitHub Releases created using the manual workflow's `GITHUB_TOKEN` do not trigger a second publication workflow, per GitHub Actions event rules.

The automatic publish workflow builds the exact tagged commit, requires that commit to be reachable from `main`, and does not change `main`, tags, or `CHANGELOG.md`.

## Marketplace prerequisite

JetBrains requires the first plugin publication to be created/uploaded manually before subsequent versions can be published through Gradle.

Before enabling automated publishing:

1. Create the plugin entry in JetBrains Marketplace and complete its required metadata.
2. Upload the first signed distribution manually.
3. Generate a Marketplace publishing token.
4. Add the signing and publishing credentials as GitHub Actions secrets.

## Required GitHub Actions secrets

Configure these repository or protected-environment secrets:

| Secret                 | Purpose                                      |
| ---------------------- | -------------------------------------------- |
| `CERTIFICATE_CHAIN`    | X.509 certificate chain used by `signPlugin` |
| `PRIVATE_KEY`          | PEM private key used to sign the plugin      |
| `PRIVATE_KEY_PASSWORD` | Password for the private key                 |
| `PUBLISH_TOKEN`        | JetBrains Marketplace publishing token       |

The certificate chain and private key must never be committed. The IntelliJ Platform Gradle Plugin accepts the signing values through environment variables; Base64-encoded values are also supported and are convenient for multiline CI secrets.

## Automatic publish from a GitHub Release (recommended)

1. Merge and verify the changes on `main`.
2. Create a unique version tag on a commit in `main`, for example `v0.1.5`.
3. On GitHub, select **Releases → Draft a new release**, choose that tag, and click **Publish release**.

The `release: published` event triggers [`publish.yml`](../.github/workflows/publish.yml). The workflow:

- requires a published, non-draft GitHub Release and a valid `vX.Y.Z` version tag;
- checks out the release tag, not the current `main` HEAD;
- verifies that the tagged commit belongs to `main`;
- runs tests, Plugin Verifier, plugin packaging/icon checks, and signature verification;
- publishes with `publishPlugin`, then attaches the signed ZIP to the GitHub Release.

The GitHub Release type selects the JetBrains Marketplace channel automatically:

| GitHub Release | Marketplace channel |
| -------------- | ------------------- |
| Prerelease     | `beta`              |
| Normal release | `default`           |

A tag push alone is **not** sufficient: the GitHub Release must be published. GitHub Releases published before this workflow was enabled are not processed retroactively.

### Retry an existing published release

For a release that was created before automation was enabled (such as `v0.1.4`) or whose publication failed, run **Actions → Publish to JetBrains Marketplace → Run workflow** on `main` and enter its existing tag. The workflow reads the channel from the published GitHub Release.

Only retry after verifying the version was **not already published** to JetBrains Marketplace: publishing the same plugin version twice may be rejected. A manually triggered run must not be used for a new release without a published GitHub Release.

## Manual end-to-end release workflow (legacy)

Run **Actions → Release → Run workflow** from the `main` branch.

Provide:

- an explicit non-SNAPSHOT version, for example `0.1.5` or `0.2.0-beta.1`;
- the Marketplace channel:
    - `beta` creates a GitHub prerelease;
    - `default` creates a normal GitHub release.

The workflow refuses to continue when:

- it is started from a branch other than `main`;
- the version is invalid or ends in `-SNAPSHOT`;
- the release tag already exists;
- `CHANGELOG.md` already contains the version;
- there are no commits since the previous tag;
- `main` changes while the release candidate is being verified.

If `main` changes during verification, rerun the manual release workflow so it builds from the latest `main`.

## Generated changelog

Release notes are generated through GitHub's Release Notes API using the previous reachable tag as the start point.

The workflow inserts them below `## Unreleased` as:

```md
## 0.1.4 - 2026-09-30

### What's Changed

- ...
```

GitHub's generated `Full Changelog` footer is omitted from `CHANGELOG.md` but preserved in the GitHub Release notes.

The changelog update is committed as:

```text
chore(release): 0.1.4
```

The release tag points to this commit.

## Local signed dry-run

Use an explicit release version and keep credentials in environment variables:

```bash
export CERTIFICATE_CHAIN='...'
export PRIVATE_KEY='...'
export PRIVATE_KEY_PASSWORD='...'

./gradlew \
  check \
  buildPlugin \
  verifyPluginStructure \
  verifyPluginProjectConfiguration \
  verifyPlugin \
  signPlugin \
  verifyPluginSignature \
  -PpluginVersion=0.1.4 \
  -PmarketplaceChannel=beta
```

This does not update `CHANGELOG.md`, push commits/tags, create a GitHub Release, or publish anything.

## Local publish

Only run this for recovery/debugging after confirming the exact version and channel:

```bash
export PUBLISH_TOKEN='...'

./gradlew publishPlugin \
  -PpluginVersion=0.1.4 \
  -PmarketplaceChannel=beta
```

`publishPlugin` refuses versions ending in `-SNAPSHOT`.
