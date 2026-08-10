# Hearth private node

The image is useful for developing dApps and other smart contracts on the Hearth blockchain.

## Getting started

To run the node,\
`docker run -d --name hearth-private-node -p 6869:6869 hearth-private-node`

To view node API documentation, open http://localhost:6869/

## Preserve blockchain state

If you want to keep the blockchain state, then just stop the container instead of killing it, and start it again when needed:\
`docker stop hearth-private-node`
`docker start hearth-private-node`

## Configuration details

The node is configured with:

- faster generation of blocks (**10 sec** interval)
- all features pre-activated
- custom chain id - **R**
- api_key `hearth-private-node`

Full node configuration is available in [`hearth.custom.conf`](./hearth.custom.conf).
