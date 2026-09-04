# Hearth Chain Node

A Scala 3 fork of the Waves node: consensus, state, REST/gRPC API, and RIDE.

## Building

Requires JDK 25 and sbt.

```bash
sbt compilePR
```

Cleans, checks formatting (`scalafmtCheck`), and compiles the whole project, including tests, with `-Werror`.

## Running tests

Unit tests:

```bash
sbt node-tests/test
sbt grpc-server/test

# a single suite
sbt "node-tests/testOnly *SuiteName"
```

Integration tests (require Docker, and are slow, so not run by default):

```bash
sbt node-it/docker
sbt node-it/test
```

`sbt checkPR` runs `compilePR`, both unit test suites, `node/assembly`, and packages the Docker tarballs.

## Running the node

The recommended way to run a node is the Docker image.

Build it:

```bash
sbt buildTarballsForDocker
docker build -t hearth-node docker
```

Run it, mounting data and config directories from the host:

```bash
docker run \
  -v /path/to/data:/var/lib/hearth \
  -v /path/to/config:/etc/hearth \
  -p 6869:6869 \
  hearth-node
```

See [docker/README.md](./docker/README.md) for configuration options, environment variables, and network ports.

On Debian and Ubuntu there is a single package, `hearth-jvm`:

```bash
sbt buildDebPackages
sudo apt-get install ./target/out/jvm/u/node/hearth-jvm_*_amd64.deb
```

It ships a systemd template unit and starts one instance, `hearth-jvm@mainnet`. An instance is a directory under
`/etc/hearth-jvm`, with its own data under `/var/lib/hearth-jvm/<instance>` and logs under
`/var/log/hearth-jvm/<instance>`, so several networks (or several nodes on one network) run side by side:

```bash
sudo mkdir /etc/hearth-jvm/testnet
sudo cp /usr/share/hearth-jvm/doc/hearth.conf.sample /etc/hearth-jvm/testnet/hearth.conf
# set hearth.blockchain.type = TESTNET in it, then
sudo systemctl enable --now hearth-jvm@testnet
```

Each instance directory can also hold an optional `env` file for JVM options (`JAVA_OPTS="-Xmx8g"`) and an
optional `logback.xml`. The same variable works for the tools: `JAVA_OPTS="-Xmx16g" hearth-jvm -main
tech.hearth.Importer -c /etc/hearth-jvm/mainnet/hearth.conf -i blockchain.bin`.

Alternatively, run the assembled jar directly:

```bash
sbt node/assembly
java -jar node/target/hearth-all-*.jar path/to/hearth-{network}.conf
```

Network config templates (mainnet, testnet, stagenet) are in [network-defaults.conf](./node/src/main/resources/network-defaults.conf).
