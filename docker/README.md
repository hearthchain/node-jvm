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
`./build-with-docker.sh && docker build -t hearth-node docker` (from the repository root) - builds an image with the current local repository

**You can specify following arguments when building the image:**


| Argument          | Default value | Description                                                                                                                                                                                                                                                                                   |
|-------------------|---------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `INCLUDE_GRPC`    | `true`        | Whether to include gRPC server files in the image.                                                                                                                                                                                                                                            |

**Note: All build arguments are optional.**

## Running Docker image



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
| `HEARTH_WALLET_SEED`      | Hex encoded seed, sets `-Dhearth.wallet.seed` system property.                                                                                                                                               |
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

## Hearth private node

The image is useful for developing dApps and other smart contracts on the Hearth blockchain.

### Getting started

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
- all features pre-activated
- custom chain id - **R**
- api_key `hearth-private-node`

Full node configuration is available in [`docker/private/hearth.custom.conf`](./private/hearth.custom.conf).

### Image tags

You can use the following tags:

- `latest` - current version of Mainnet
- `vX.X.X` - specific version of Hearth Node
