# Hearth Node in Docker

## About Hearth
Hearth is a decentralized platform that allows any user to issue, transfer, swap and trade custom blockchain tokens on an integrated peer-to-peer exchange. You can find more information about Waves (the platform this project is forked from) at [waves.tech](https://waves.tech/) and in the official [documentation](https://docs.waves.tech).


## About the image
This Docker image is focused on fast and convenient deployment of Hearth Node.
The image contains scripts and configs to run Hearth Node for `mainnet`, `testnet` or `stagenet` networks.
If you need to run node in private network, see [Hearth private node](#hearth-private-node) section.

## Prerequisites
It is highly recommended to read more about [Waves Node configuration](https://docs.waves.tech/en/waves-node/node-configuration) before running the container, since Hearth Node's configuration format is derived from it.

## Building Docker image
`sbt stageForDocker && docker build -t hearth-node docker` (from the repository root) - builds an image with the current local repository

**You can specify following arguments when building the image:**


| Argument          | Default value | Description                                                                                                                                                                                                                                                                                   |
|-------------------|---------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `INCLUDE_GRPC`    | `true`        | Whether to include gRPC server files in the image.                                                                                                                                                                                                                                            |

**Note: All build arguments are optional.**

## Running Docker image

### The node runs as an unprivileged user

The node process runs as `hearth` (uid 999, gid 999), never as root. The container still starts as root so that
`entrypoint.sh` can take ownership of `/var/lib/hearth` and `/var/log/hearth` - a bind-mounted host directory arrives
with the host's ownership, unlike a named volume - and then drops to `hearth` before starting the JVM. That chown is
recursive on the first start after mounting a directory the node does not own yet, and skipped on every start after
it, so nothing needs to be prepared by hand.

Two things follow from it:

- A config directory mounted at `/etc/hearth` is left alone, so its files must be readable by uid 999. It is never
  chowned: it belongs to whoever runs the image.
- `docker run --user ...` is honoured as given. The entrypoint drops privileges only when it starts as root, so an
  explicit `--user` runs the node as that user and takes responsibility for the mounted directories' ownership.

### Configuration options

1. The image supports Hearth Node config customization. To change a config field use corresponding JVM options. JVM options can be sent to JVM using `JAVA_OPTS` environment variable.

    ```
    docker run -v /docker/hearth/hearth-data:/var/lib/hearth -v /docker/hearth/hearth-config:/etc/hearth -p 6869:6869 -p 6862:6862 -e JAVA_OPTS="-Dhearth.rest-api.enable=yes -Dhearth.wallet.password=myWalletSuperPassword" -ti hearth-node
    ```

2. Hearth Node is looking for a config in the directory `/etc/hearth/hearth.conf` which can be mounted using Docker volumes. For custom networks, correct configuration file must be provided when running container. If you use `CUSTOM` network and `/etc/hearth/hearth.conf` is NOT found Hearth Node container will exit.

3. You can use custom config  to override or the whole configuration. For additional information about Docker volumes mapping please refer to `Managing data` item.

4. You can override the default executable by using the following syntax:
    ```
    docker run -it hearth-node [command] [args]
    ```

### Environment variables

The following environment variables can be passed to the container:

| Env variable              | Description                                                                                                                                                                                                  |
|---------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `HEARTH_WALLET_MNEMONIC`  | BIP-39 phrase the wallet derives its accounts from, sets `-Dhearth.wallet.mnemonic` system property.                                                                                                                                               |
| `HEARTH_WALLET_PASSWORD`  | Password for the wallet file, sets `-Dhearth.wallet.password` system property.                                                                                                                               |
| `HEARTH_LOG_LEVEL`        | Node stdout logging level. Available values: `OFF`, `ERROR`, `WARN`, `INFO`, `DEBUG`, `TRACE`.                                                                                                               |
| `HEARTH_HEAP_SIZE`        | Default Java Heap Size limit in -X Command-line Options notation (`-Xmx=[your value]`). More details [here](https://docs.oracle.com/cd/E13150_01/jrockit_jvm/jrockit/jrdocs/refman/optionX.html).           |
| `HEARTH_NETWORK`          | Hearth Blockchain network. Available values are `mainnet`, `testnet`, `stagenet`.                                                                                                                            |
| `JAVA_OPTS`                | Additional Hearth Node JVM configuration options. 	                                                                                                                                                          |

All environment variables are optional, however you need to specify at least the desired network and wallet password (via environment variables, additional system properties defined in the `JAVA_OPTS` environment variable, or in the config file). 

### Managing data
We recommend to store the blockchain state as well as Hearth configuration on the host side. As such, consider using Docker volumes mapping to map host directories inside the container:

**Example:**

1. Create a directory to store Hearth data:

```
mkdir -p /docker/hearth
mkdir /docker/hearth/hearth-data
mkdir /docker/hearth/hearth-config
```

Once container is launched it will create:

- three subdirectories in `/docker/hearth/hearth-data`:
```
/docker/hearth/hearth-data/log    - Hearth Node logs
/docker/hearth/hearth-data/data   - Hearth Blockchain state
/docker/hearth/hearth-data/wallet - Hearth Wallet data
```
- `/docker/hearth/hearth-config/hearth.conf` - default Hearth config


3. If you already have Hearth Node configuration/data - place it in the corresponding directories

4. Add the appropriate arguments to ```docker run``` command: 
```
docker run -v /docker/hearth/hearth-data:/var/lib/hearth -v /docker/hearth/hearth-config:/etc/hearth -e HEARTH_NETWORK=stagenet -e HEARTH_WALLET_PASSWORD=myWalletSuperPassword -ti hearth-node
```

### Blockchain state

If you are launching Hearth Node for the first time be aware that after launch it will start downloading the whole blockchain state from the other nodes. During this download it will be verifying all blocks one after another. This procedure can take some time.

### Network Ports

1. REST-API interaction with Node.

2. Hearth Node communication port for incoming connections.


**Example:**
Below command will launch a container:
- with REST-API port enabled and configured on the socket `0.0.0.0:6870`
- Hearth node communication port enabled and configured on the socket `0.0.0.0:6868`
- Ports `6868` and `6870` mapped from the host to the container

```
docker run -v /docker/hearth/hearth-data:/var/lib/hearth -v /docker/hearth/hearth-config:/etc/hearth -p 6870:6870 -p 6868:6868 -e JAVA_OPTS="-Dhearth.network.declared-address=0.0.0.0:6868 -Dhearth.rest-api.port=6870 -Dhearth.rest-api.bind-address=0.0.0.0 -Dhearth.rest-api.enable=yes" -e HEARTH_WALLET_PASSWORD=myWalletSuperPassword -e HEARTH_NETWORK=stagenet -ti hearth-node
```

Check that REST API is up by navigating to the following URL from the host side:
http://localhost:6870/api-docs/index.html

### Extensions
You can run custom extensions in this way:
1. Copy all lib/*.jar files from extension to any directory, lets say `plugins`
2. Add extension class to configuration file, lets say `local.conf`, located in `config` directory containing also `hearth.conf`:
```hocon
hearth.extensions += com.johndoe.HearthExtension
```
3. Run `docker run -v "$(pwd)/plugins:/usr/share/hearth/lib/plugins" -v "$(pwd)/config:/etc/hearth" -i hearth-node`

## Signing a transaction offline

The image ships `hearth util` next to the node, so a machine with nothing but Docker installed can sign a transaction without running a node and without the key going anywhere near the network. The result is the signed JSON that `POST /transactions/broadcast` takes, so the machine holding the key never has to be the one that is online.

The entrypoint always starts the node, so the util is reached by overriding it:

```
docker run --rm -i --entrypoint java hearth-node \
  -Dlogback.stdout.level=OFF -Dlogback.file.level=OFF \
  -Dhearth.blockchain.type=MAINNET \
  --enable-native-access=ALL-UNNAMED \
  -cp '/usr/share/hearth/lib/*' tech.hearth.utils.UtilApp transaction sign-with-sk --private-key <hex>
```

Everything in that preamble is something `entrypoint.sh` would otherwise supply, and a `docker run --entrypoint` bypasses it:

| Flag | Why |
|------|-----|
| `-Dlogback.stdout.level=OFF -Dlogback.file.level=OFF` | The signed transaction leaves on stdout, where logging defaults to INFO. |
| `-Dhearth.blockchain.type=MAINNET` | The network the transaction is signed for, and the bech32 prefix addresses are read and printed with. The util defaults to `TESTNET`; a mounted config passed as `-c /etc/hearth/hearth.conf` sets it just as well. |
| `--enable-native-access=ALL-UNNAMED` | The crypto library is native. |
| `-i` (on `docker run`) | The unsigned transaction arrives on stdin. |

Options belong after the command chain, never before it: scopt reads them as children of the command, so `transaction sign-with-sk -c hearth.conf` works while `-c hearth.conf transaction sign-with-sk` fails with `Unknown argument 'transaction'`.

The input is the same JSON `POST /transactions/sign` takes, minus what the signer fills in - `senderPublicKey`, `sender`, `proofs`, and `timestamp` when it is omitted:

```
TX='{"type":1,"transfers":[{"recipient":"hrth19uvmpe6ll76dav0mvk06d35att3wk7a7gm8xwm","amount":100000000}],"fee":100000}'
```

Which of the three commands below applies depends only on the form the key is held in. All three end at the same place, so the `sender` in the output is worth checking against the address you expect before broadcasting: nothing here consults a chain, and a key given in the wrong form signs perfectly well as a different account.

### From a signing key scalar

A key that came off a vanity grinder has no seed - the search walks scalars by point addition, and nothing hashes to the winner - so the 32-byte secret scalar is the whole key:

```
echo "$TX" | docker run --rm -i --entrypoint java hearth-node \
  -Dlogback.stdout.level=OFF -Dlogback.file.level=OFF -Dhearth.blockchain.type=MAINNET \
  --enable-native-access=ALL-UNNAMED -cp '/usr/share/hearth/lib/*' tech.hearth.utils.UtilApp \
  transaction sign-with-sk --private-key-scalar 604b525b4dae161325ca191e4c06bb8da0d73aa73067fe05580bf0c2fd3af600
```

This is the same key material a node config takes as `signing-key-scalar` under `hearth.miner.accounts`.

### From a seed

A 32-byte Ed25519 seed, which is what a node config holds as `signing-key-seed`:

```
echo "$TX" | docker run --rm -i --entrypoint java hearth-node \
  -Dlogback.stdout.level=OFF -Dlogback.file.level=OFF -Dhearth.blockchain.type=MAINNET \
  --enable-native-access=ALL-UNNAMED -cp '/usr/share/hearth/lib/*' tech.hearth.utils.UtilApp \
  transaction sign-with-sk --private-key 604b525b4dae161325ca191e4c06bb8da0d73aa73067fe05580bf0c2fd3af600
```

The two forms are not interchangeable, which is why they are separate options and naming both is refused rather than resolved: 32 bytes are a valid seed *and* a valid scalar, and the two readings give different accounts. The example above is the same hex in both commands and signs as `hrth19uvm...` here against `hrth1jcrk...` there.

### From a mnemonic

A BIP-39 phrase is not fed to the signer directly. `crypto create-keys` derives the account from it at a derivation index and prints the seed, which the seed command above then takes:

```
MNEMONIC="abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

docker run --rm --entrypoint java hearth-node \
  -Dlogback.stdout.level=OFF -Dlogback.file.level=OFF -Dhearth.blockchain.type=MAINNET \
  --enable-native-access=ALL-UNNAMED -cp '/usr/share/hearth/lib/*' tech.hearth.utils.UtilApp \
  crypto create-keys --mnemonic "$MNEMONIC" --nonce 0
```

It reports the `address` the phrase derives at that index, so the account is confirmed before anything is signed, along with `minerAccountKeys.signing-key-seed`:

```
SEED=$(docker run --rm --entrypoint java hearth-node \
  -Dlogback.stdout.level=OFF -Dlogback.file.level=OFF -Dhearth.blockchain.type=MAINNET \
  --enable-native-access=ALL-UNNAMED -cp '/usr/share/hearth/lib/*' tech.hearth.utils.UtilApp \
  crypto create-keys --mnemonic "$MNEMONIC" --nonce 0 | jq -r '.minerAccountKeys."signing-key-seed"')

echo "$TX" | docker run --rm -i --entrypoint java hearth-node \
  -Dlogback.stdout.level=OFF -Dlogback.file.level=OFF -Dhearth.blockchain.type=MAINNET \
  --enable-native-access=ALL-UNNAMED -cp '/usr/share/hearth/lib/*' tech.hearth.utils.UtilApp \
  transaction sign-with-sk --private-key "$SEED"
```

`--nonce` is the derivation index, and a wallet account at index n is the same key as a mining account at that index of the same phrase, so it is the one thing to get right: the default 0 is a different account from 1.

Both the phrase and the derived seed pass through the host shell here, and every form above puts key material in the container's argv, where `ps` on the host can read it for as long as the command runs. Reading the phrase from a file (`--mnemonic "$(cat phrase.txt)"`) keeps it out of shell history but not out of argv. Signing inside a container that already has the key mounted as a config avoids both, which is what `sign-commit-to-generation.sh` does.

### What this does not cover

`transaction sign --signer-address <address>` signs from `wallet.dat` rather than from a key on the command line, and a container started only to sign has no wallet: `hearth.wallet.mnemonic` alone leaves it with no accounts registered, and the command fails with `MissingSenderPrivateKey`. That path belongs to a running node, over its REST API.

A `CommitToGeneration` transaction is signed by neither route: it registers generator keys the signing key does not carry. See `sign-commit-to-generation.sh` in the repository root, which reads the period from a running node and signs inside that node's own container, where the config with those keys is already mounted.

## Hearth private node

The image is useful for developing dApps and other smart contracts on the Hearth blockchain.

See [`docker/private/README.md`](./private/README.md) for the full private-node guide, including how to build the
image, seed predefined DCAP collateral at genesis, and rebuild genesis after changing balances/generators/collateral.

### Getting started

Build the base image above first, then build the private-node image on top of it (see
[`docker/private/README.md`](./private/README.md)):\
`docker build -t hearth-private-node docker/private`

To run the node,\
`docker run -d --name hearth-private-node -p 6869:6869 hearth-private-node`

To view node API documentation, open http://localhost:6869/

### Preserve blockchain state

If you want to keep the blockchain state, then just stop the container instead of killing it, and start it again when needed:\
`docker stop hearth-private-node`
`docker start hearth-private-node`

### Configuration details

The node is configured with:

- faster generation of blocks (**10 sec** interval)
- feature 1 (`SmallerMinimalGeneratingBalance`) pre-activated - the only one this fork currently implements
- custom chain id - **R**, bech32 address prefix `phrth`
- api_key `hearth-private-node`

Full node configuration is available in [`docker/private/hearth.custom.conf`](./private/hearth.custom.conf).

### Image tags

You can use the following tags:

- `latest` - current version of Mainnet
- `vX.X.X` - specific version of Hearth Node
