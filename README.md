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
  -v /path/to/data:/var/lib/waves \
  -v /path/to/config:/etc/waves \
  -p 6869:6869 \
  hearth-node
```

See [docker/README.md](./docker/README.md) for configuration options, environment variables, and network ports.

Alternatively, run the assembled jar directly:

```bash
sbt node/assembly
java -jar node/target/hearth-all-*.jar path/to/waves-{network}.conf
```

Network config templates (mainnet, testnet, stagenet) are in [network-defaults.conf](./node/src/main/resources/network-defaults.conf).
