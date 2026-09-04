---
purpose: Implementation notes for build tooling (dependency cleanup, token rename, protobuf package migration, sbt 2 lint and action cache)
---

# Build tooling

## Dependency cleanup: web3j and blst-java

`web3j` and the direct `blst-java` dependency are gone from `node`'s build - both legacy from the pre-fork Waves
codebase, with no live callers left except one: `P256Curve.toBytesPadded` (pads a `BigInteger` to a fixed-length
unsigned byte array, for P-256 cert-chain verification), moved to
`org.bouncycastle.util.BigIntegers.asUnsignedByteArray` (`bcprov-jdk18on`, already a hard dependency).

BLS (`crypto.bls.BlsUtils`/`BlsKeyPair`) no longer imports `supranational.blst` directly; it calls
`tech.hearth.crypto.BlsKey` instead. `blst-java` itself is still on the classpath (`tech.hearth:crypto`'s own pom
depends on it), just not as a direct dependency of this repo any more.

This repo's BLS scheme did not match what `tech.hearth.crypto.BlsKey` shipped with. `BlsKey.sign`/`verify`/
`fastAggregateVerify` hardcode the eth2 proof-of-possession ciphersuite DST (`..._POP_`); this repo signs block
endorsements and generation commitments under the Basic (unaugmented, `..._NUL_`) DST instead, and implements its
own period-bound proof of possession at commit time (`CommitToGenerationTransaction.mkPopMessage`, `pubkey ++
periodStart`, checked with the same signing primitive as everything else) rather than the library's dedicated PoP
DST. `BlsKey` had no way to pick a DST, so a straight swap would have silently changed the on-chain BLS scheme.
Fixed by patching the crypto library itself (sibling repo, `../hearth-chain/java`) to add a second, explicitly
named Basic ciphersuite alongside the POP one: `signBasic`/`verifyBasic`/`fastAggregateVerifyBasic`, plus
`isValidPublicKey` (in-group, non-infinity check, backs this repo's `BlsPublicKey.validated`) and
`fromSeedKeygenV5` (blst's own arbitrary-length-seed `keygen_v5`, for `BlsKeyPair.fromSeed`, the seed-derived
test/tooling convenience path; distinct from `fromSeed`'s EIP-2333 derivation, which needs a real account index
and rejects seeds under 32 bytes). This makes the migration byte-for-byte identical to the previous
directly-on-blst implementation, not a protocol change: same DST, same `keygen_v5` salt, confirmed by the
"expected public keys" fixture in `BlsUtilsTest` and the hash-pinned fixture in `TxStateSnapshotHashSpec` (see
"Node Tests" in `docs/notes/testing.md`) needing zero changes.

A short seed to `BlsKeyPair.fromSeed`/`fromSeedKeygenV5` collapses to the zero scalar (blst's own `keygen_v5`
quirk, not EIP-2333); `TxStateSnapshotHashSpec`'s "with generation commitment" case deliberately relies on this
(`Ints.toByteArray(101)`, 4 bytes) to get a compact, hash-pinned point-at-infinity BLS public key fixture. Do not
change `fromSeedKeygenV5`'s algorithm without recomputing that fixture.

The crypto library lives in a sibling repo, `../hearth-chain` (its Java module at `../hearth-chain/java`, Maven,
published as `tech.hearth:crypto:0.1.0-SNAPSHOT`). A change there needs `mvn install` (run from
`../hearth-chain/java`) to publish the rebuilt jar to `~/.m2` before `node` picks it up; `build.sbt` already
resolves through `Resolver.mavenLocal`, but sbt's dependency cache can still hold an already-resolved older
SNAPSHOT, so `sbt update`/a clean rebuild may be needed on top of the `mvn install` if a stale version was
resolved earlier in the same environment.

## Token rename: waves → Hearth/HRTH, wavelet → ember

The native currency's code-level naming was renamed to match the "Hearth"/"HRTH" branding the tokenomics spec
already used: `Waves`/`waves`/`WAVES` → `Hearth`/`hearth`/`HRTH` (`Asset.Waves` → `Asset.Hearth`,
`Constants.TotalWaves`/`UnitsInWave` → `TotalHearth`/`UnitsInHearth` (`TotalHearth` has since been removed), `WavesSettings` → `HearthSettings`, the
`waves {}` HOCON config root → `hearth {}` and every `-Dwaves.*` system property → `-Dhearth.*`, REST/gRPC JSON
fields like `totalWavesAmount`/`totalFeeInWaves` → `totalHearthAmount`/`totalFeeInHearth`), and the base unit
`wavelet` → `ember` (`CommitToGenerationTransaction.DepositInWavelets` → `DepositInEmbers`). `Constants.UnitsInHearth
= 100000000L`, i.e. 1 HRTH = 10^8 embers, unchanged by the rename. This also reached the
two local proto messages (`node/src/main/protobuf/hearth/database.proto`): `BlockMeta.total_waves_amount` →
`total_hearth_amount`, and `TransactionData`'s oneof case `waves_transaction` → `hearth_transaction` (so
`TD.WavesTransaction` → `TD.HearthTransaction` at its two call sites in `database/package.scala`). Renamed files:
`WavesSettings.scala`/`WavesSettingsSpecification.scala`/`WavesTxChecks.scala` → `Hearth*.scala`,
`node/waves-sample.conf` → `hearth-sample.conf`, `docker/private/waves.custom.conf` → `hearth.custom.conf`.

Docker/deployment packaging followed the same rename: `docker/Dockerfile`/`entrypoint.sh`'s env vars
(`WAVES_NETWORK`/`WVDATA`/`WVLOG`/etc. → `HEARTH_NETWORK`/`HEARTH_DATA`/`HEARTH_LOG`/etc.) and paths
(`/etc/waves`, `/var/lib/waves`, `/usr/share/waves` → `/etc/hearth`, `/var/lib/hearth`, `/usr/share/hearth`), the
`node-it` test image tag (`com.wavesplatform/node-it` → `hearth/node-it`), the tarball names `buildTarballsForDocker`
produces (`waves.tgz`/`waves-grpc-server.tgz` → `hearth.tgz`/`hearth-grpc-server.tgz`), `grpc-server`'s artifact
name (`waves-grpc-server` → `hearth-grpc-server`, matching `node`'s already-renamed `hearth-jvm`), and the Linux
package name/summary in `node/build.sbt`/`ExtensionPackaging.scala` (`waves${network}` → `hearth${network}`,
`maintainer` → `tech.hearth`).

The external `tech.hearth % protobuf-schemas` dependency's own fields were the last holdouts, and are now
renamed upstream too, so nothing local says "waves" any more. `SignedTransaction.waves_transaction` went first
(see "Transaction schema: Transfer merge, fee restructuring, new tx types" in `docs/notes/keys-and-signatures.md`):
`transaction.proto`'s top-level field is now `transaction`, with `.wavesTransaction`/`.getWavesTransaction`/
`.withWavesTransaction` renamed to `.transaction`/`.getTransaction`/`.withTransaction`. `BalanceResponse`'s
`WavesBalances`/`waves` (`accounts_api.proto`) then became `HearthBalances`/`hearth`, and `BlockAppend`'s
`updated_waves_amount` (`events.proto`) became `updated_hearth_amount`. Both sides of the wire are renamed
together: `AccountsApiGrpcImpl` (`withHearth`), the `node-it` gRPC helpers (`getHearth`), and `grpc-server`'s
vanilla event mirror (`events.scala`'s `BlockAppended.updatedHearthAmount`, `events/repo/LiquidState.scala`,
`events/protobuf/serde/package.scala`). A generated-code rename in `protobuf-schemas` surfaces here only as a
compile error, so `sbt Test/compile` after an upstream `mvn install` is the way to find every call site.

Other things are deliberately still saying "waves", each for a different reason:

- **CI publish destinations** — Docker Hub (`wavesplatform/wavesnode`, `wavesplatform/waves-private-node`,
  `wavesplatform/ride-runner`), `ghcr.io/wavesplatform/waves*`, `apt.wavesplatform.com`, and the `@waves/ride-lang`
  npm package (`.github/workflows/*.yml`, `create-aptly-repo.sh`) are real registries tied to existing
  `DOCKERHUB_USER`/`DOCKERHUB_PASSWORD`/`OSSRH_*` secrets; renaming the target without matching credentials would
  just break the release pipeline. Only cosmetic labels/descriptions in those workflows were updated (e.g.
  `org.opencontainers.image.description=Hearth Node`); `docker/private/Dockerfile`'s
  `ARG baseImage=wavesplatform/wavesnode:latest` default is the same case, left as-is.
- **`project/Dependencies.scala`'s `"com.wavesplatform" % "curve25519-java"`** is a real external Maven
  coordinate (an actual published groupId) — renaming the string breaks dependency resolution, not just cosmetics.
- **Historical lineage prose** ("a Scala 3 fork of the Waves node", "pre-fork Waves codebase", `gowaves`, and the
  `com.wavesplatform.*` → `tech.hearth.*` package-migration paragraphs above) describes this repo's actual
  ancestry and past migrations, not its current branding — left as written.
- **Real external references not owned by this repo**: `wavesnodes.com` seed hosts (`network-defaults.conf`),
  `waves.tech`/`docs.waves.tech` (homepage and doc links), and `mpotanin@wavesplatform.com`
  (`node/build.sbt`/`node/testkit/build.sbt` developer list).

## Protobuf package migration

Generated protobuf code lives under `tech.hearth.*` now, not `com.wavesplatform.*`. If a local `.proto` file's
`option java_package` is ever left stale (pointing at the old package), the symptom is a wave of "not found"/
type-mismatch errors in whatever hand-written code depends on it - fix the `java_package`, don't chase each site.

A `package object` re-export shim (`type X = tech.hearth.foo.X` / `val X = ...` for the companion, used so callers
can name a not-yet-renamed generated type unqualified) becomes a circular self-reference once the hand-written
package and the generated package actually converge - scalac reports this as `[E161] Naming Error: X is already
defined as class/object X in .../target/src_managed/main/...`. That specific shape (a *Naming* error pointing at
`target/src_managed`, not a plain "not found") is the tell that a shim has gone stale and its `type`/`val` alias
lines need deleting, not that a type is missing.

Package renames also live as string literals in sbt files, not just Scala imports - `mainClass`
(`project/RunApplicationSettings.scala`), `extensionClasses` (`grpc-server/build.sbt`), `V.scalaPackage`
(`node/build.sbt`), and `-Wconf:...&origin=...` filters (`build.sbt`) all embed a fully-qualified class name as a
string and don't fail compiling Scala sources - a stale `mainClass`/`extensionClasses` only breaks at runtime
(`ClassNotFoundException`), a stale `-Wconf` origin only breaks `-Werror` once the class it no longer matches emits
a fresh warning. Check both by hand after any package rename.

A file reduced to just a `package` line with no members doesn't error (`-Wunused`/`-Werror` catches it as "No
class, trait or object is defined in the compilation unit" only if referenced nowhere) - `grep -rl` for zero
references before deleting.

## SBT 2 unused-settings lint

`compilePR` warns of 24 sbt keys "not used by any other settings/tasks" - not new dead weight from the SBT 2
migration, just a stricter lint: `sbt-native-packager`'s settings graph (`Rpm`/`Debian`/`Universal`-scope bridges
like `Linux/javaOptions`, `Debian/executableScriptName`, the Rpm daemon-user block) is byte-for-byte identical
under sbt 1 and sbt 2 (confirmed by running the pre-migration build under real sbt 1.12.14), but sbt 1's own lint
never caught these particular keys. Each was confirmed genuinely dead by reading the plugin source and
cross-checking with `inspect tree`/`inspect uses` against this build, not by assumption - `Rpm/*` in particular
stays dead even where Rpm packaging is used on any project, a real (harmless) gap in the plugin itself, since this
repo never runs an Rpm task regardless (Debian + Universal tarballs only). None of the 24 are fixable beyond
`excludeLintKeys` (`build.sbt`): a key is defined the moment its owning plugin is enabled, and sbt has no API to
retract one. `gitDescribedVersion`'s `excludeLintKeys` entry needs the `git.` prefix (`git.gitDescribedVersion`,
`SbtGit.GitKeys`), unlike the others.

## SBT 2 action-cache: side-effecting tasks need `Def.uncached`

sbt 2's `ActionCache` treats every task as cacheable by default and, on a cache hit, replays the cached result
*without re-running the body*. A task whose only job is an out-of-band filesystem write sbt's output tracking can't
see (e.g. `buildTarballsForDocker`'s `IO.copyFile` into `docker/target/*.tgz`, not a declared task output) silently
no-ops on a cache hit - `setup-java`'s `cache: 'sbt'` persists that cache *across* CI runs, so a fresh checkout with
an empty `docker/target/` can still hit stale and skip the copy, breaking `node-it/docker`'s later `docker build`.
Same class of bug already fixed for `classpathOrdering`, `compilePRRaw`, `IntegrationTestsPlugin`'s
`logDirectory`/`testGrouping`, and `benchmark/build.sbt`'s `Jmh / compile`. Fix: wrap the task body in
`Def.uncached`. Any task in this build that writes to a path outside its own declared outputs is a candidate for
the same bug.


## `protobuf-schemas` resolves from `~/.m2` first, and a stale copy shadows the published snapshot

`tech.hearth:protobuf-schemas:0.1.0-SNAPSHOT` (classifier `protobuf-src`, `Dependencies.protoSchemasLib`) carries
every `.proto` this build compiles - there are no `.proto` files in this repo. `build.sbt` lists
`Resolver.mavenLocal` *before* `Resolver.sonatypeCentralSnapshots`, so a copy left in `~/.m2` by an old
`publishM2`/`publishLocal` from the sibling `protobuf-schemas` repo wins over the published snapshot forever - it
has no timestamp to compare against and no TTL to expire. The symptom is not a resolution error but a compile
error in code that consumes a *newer* schema than the local copy: fields and nested messages simply "are not a
member of" the generated class (this is how `StateUpdate.reserves`/`settlements` broke `grpc-server/events.scala`
while CI, which has no `~/.m2` copy, stayed green). Check
`~/.m2/repository/tech/hearth/protobuf-schemas/*/` against the schema the code expects before assuming the Scala
is wrong; the fix is to refresh or delete the local copy (delete `maven-metadata-local.xml` alongside it, or
mavenLocal still claims to serve the version), not to edit the consuming code.

Two follow-ons once the local copy is gone: sbt's *resolution* is cached too, so the next build fails with
`ArtifactError$NotFound` still pointing at the `~/.m2` path - `sbt --client shutdown` and rerun to force
re-resolution. And a schema change is only visible here after the sibling repo's CI publishes a new snapshot, so
a node-jvm branch that consumes new proto fields depends on the schemas PR having merged *and* published first.

## CI runs sbt with `--server`: the thin client races its own server on a cold checkout

sbt 2's `sbt` script is the native thin client (`sbtn`) by default: it forks a server in the background, then reads
`project/target/active.json` for the socket to connect to. On a cold CI checkout that file is being written while
the client polls it, and the client can read a truncated document
(`sbt connection file ... is corrupt or unreadable: IncompleteParseException: exhausted input; starting a new
server`), decide the server is stale, fork a second one, and exit 1 with `failed to connect to server` - before a
single task has run. Run 33628223824 died this way 44 seconds in.

Every workflow therefore invokes `sbt --server ...`, which runs the server in the foreground and never touches
`active.json`. CI has no second command to attach to, so the thin client buys nothing there. Do not "simplify" the
flag away.

`-Dsbt.ci=true` is *not* needed on top of it: `Terminal.isCI` is
`System.getProperty("sbt.ci") == "true" || sys.env.contains("BUILD_NUMBER") || sys.env.contains("CI")`, and GitHub
Actions sets `CI=true` on every runner. `BUILD_NUMBER` in that list is Jenkins/Hudson; GitHub Actions does not
define it (its equivalents are `GITHUB_RUN_NUMBER`/`GITHUB_RUN_ID`/`GITHUB_RUN_ATTEMPT`).

## Linux packaging: one package, one systemd template unit

Waves shipped one deb per network (`waves`, `waves-testnet`, `waves-stagenet`), built from a `network` sbt setting
that suffixed the package name and generated three `-D` lines into `conf/application.ini`. The binaries were
identical; only those lines differed. That is gone. There is one deb, `hearth-jvm`, and the network is runtime
configuration, the way every other chain does it (bitcoind `-chain=`, geth `--sepolia`, cosmos `--home`,
polkadot `--chain=`). `project/Network.scala`, the `network` setting and the `buildReleaseArtifacts <networks...>`
parser were deleted along with it; `buildReleaseArtifacts` now takes no arguments.

Everything derives from `Linux / packageName` = `hearth-jvm`: install dir `/usr/share/hearth-jvm`, config dir
`/etc/hearth-jvm`, data `/var/lib/hearth-jvm`, logs `/var/log/hearth-jvm`, daemon user and group `hearth-jvm`.
The Docker image is unaffected and keeps its own `/etc/hearth`, `/var/lib/hearth` paths: one container, one node.

Multiple nodes on one host are instances of a systemd template unit, `hearth-jvm@.service`
(`Debian / linuxStartScriptName := Some("hearth-jvm@.service")`), one directory per instance:

- `/etc/hearth-jvm/<instance>/hearth.conf` - the node config, passed to the launcher as its argument. Sets the
  network via `hearth.blockchain.type`; without it the package default from `application.ini` (mainnet) applies.
- `/etc/hearth-jvm/<instance>/env` - optional, `EnvironmentFile=-`, and the only place an operator sets JVM
  options (see below).
- `/etc/hearth-jvm/<instance>/logback.xml` - optional, included by the packaged `logback.xml` because the unit
  passes `-Dhearth.config.directory=/etc/hearth-jvm/%i`. This is why instances get a directory rather than a flat
  `<instance>.conf`: that include is keyed off the config directory, so flat files would force one logback config
  on every instance.
- `/var/lib/hearth-jvm/<instance>` and `/var/log/hearth-jvm/<instance>` - created and chowned by systemd through
  `StateDirectory=`/`LogsDirectory=`, passed to the node as `-Dhearth.defaults.directory` and
  `-Dlogback.file.directory`.

`hearth.defaults.*` rather than `hearth.*` for the data directory on purpose: `settings.loadConfig` promotes the
`hearth.defaults` subtree to `hearth` as a *fallback*, so an instance config file still wins over what the unit
passes.

### JVM options live in exactly one place

The launcher builds its java command line as `$JAVA_OPTS`, then the options it reads from
`<install>/conf/application.ini`, then its own command-line arguments. Later flags win, so **`application.ini`
silently overrides `JAVA_OPTS`**: with `-J-Xmx2g` in that file, `JAVA_OPTS="-Xmx8g"` left the heap at 2 GiB
(measured with `-J-XX:+PrintFlagsFinal`, `MaxHeapSize = 2147483648`). Waves shipped exactly that combination, so
an operator's heap setting was quietly ignored, `hearth-jvm -main tech.hearth.Importer` included, which is the one
invocation that most wants a large heap.

The split is therefore by ownership, and the rule is: **`Universal / javaOptions` carries only what the node
cannot run without; anything an operator might want to change goes in `JAVA_OPTS`, never in the ini.** What
remains in `application.ini` is `-XX:+ExitOnOutOfMemoryError`, `-Dfile.encoding=UTF-8` (consensus-relevant:
deterministic `getBytes`), the two `--add-opens` and `--enable-native-access`. Dropped: `-Xmx2g` (the JVM's
ergonomic 25% of RAM applies instead), `-XX:+UseG1GC` and `-XX:+ParallelRefProcEnabled` (already the JDK 25
defaults) and `-XX:+UseStringDeduplication` (now opt-in per instance). Do not add tuning flags back there:
`application.ini` is read by every entry point, the service and `-main` tool runs alike, and it cannot hold
anything instance-specific.

`/etc/default/hearth-jvm` is deliberately **not** shipped, though the launcher still sources it if it exists: it
is sourced *after* systemd has applied the instance's `env` file, so a `JAVA_OPTS` set there would override every
instance's own. The same variable works for tool runs: `JAVA_OPTS="-Xmx16g" hearth-jvm -main tech.hearth.Importer
-c /etc/hearth-jvm/mainnet/hearth.conf -i blockchain.bin`.

Notes on the packaging itself, all of them things that bit during this change:

- `/etc/hearth-jvm` has to be filtered out of `linuxPackageSymlinks`. JavaServerAppPackaging makes `/etc/<pkg>` a
  symlink to `/usr/share/<pkg>/conf`, which cannot hold per-instance config; it is now a real package-owned
  directory (0750, `hearth-jvm:hearth-jvm`), as is `/var/lib/hearth-jvm`.
- `application.ini` must stay at `/usr/share/hearth-jvm/conf/application.ini`: the launcher derives that path from
  `realpath "$0"`, so it follows the *script*, not the config directory or the cwd, and there is no override hook.
  A per-instance ini would need a per-instance copy of the generated launcher (which embeds the full classpath) or
  a `BindPaths=` in the unit. Neither is worth it, hence the ownership split above.
- `maintainerScripts` and `debianMaintainerScripts` are both wrapped in `Def.uncached`. The first reads
  `src/package/debian/*` from disk, the second writes them to `(Universal / target)/tmp/debian`; sbt 2 tracks
  neither, so a cache hit ships the *previous* build's maintainer scripts, and after a clean of that directory the
  jdeb step fails with `Source file .../tmp/debian/preinst does not exist`. `debianMaintainerScripts` is defined at
  project scope, not in `Debian`, and had to be reimplemented in `node/build.sbt` because the plugin's helper is
  `private[debian]`. When editing a maintainer script, restart the sbt server too: the long-lived server can serve
  a stale build definition across `sbt --batch` runs, which looks exactly like this cache staleness.
- `defaultLinuxStartScriptLocation` is overridden to `/usr/lib/systemd/system`. The plugin default is
  `/lib/systemd/system`, and shipping that aliased path makes dpkg fail on merged-usr systems.
- A `preinst` creates the user and group. The package ships directories owned by `hearth-jvm`, so it has to exist
  before dpkg unpacks, not in `postinst`.
- Maintainer scripts enumerate instances with `systemctl list-units` plus `list-unit-files` (globbing
  `hearth-jvm@*.service`), because an enabled-but-stopped instance shows up only in the second. They guard on
  `command -v systemctl` and `/run/systemd/system` so installing in a container or chroot still works.
- `postrm purge` deletes `/var/log/hearth-jvm` and every `/var/lib/hearth-jvm/*/data`, and deliberately keeps
  wallets, configs and the daemon user. Blockchain state resyncs; a wallet seed does not come back.
- Verify a change to the unit with `systemd-analyze verify /usr/lib/systemd/system/hearth-jvm@.service` on the
  installed package. In sandboxed dev environments dpkg fails to unpack with
  `unable to install new version of './usr/share/hearth-jvm': Invalid cross-device link` (overlayfs cannot rename
  directories); mount volumes over `/usr/share`, `/var` and `/etc` (`docker run -v vol:/usr/share ...`) to test an
  install.
