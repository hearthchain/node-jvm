#!/bin/bash

HEARTH_VERSION=$(cut -d\" -f2 ../version.sbt)

docker build \
  --build-arg SBT_VERSION=$(cut -d= -f2 ../project/build.properties) \
  --build-arg HEARTH_VERSION=$HEARTH_VERSION \
  --pull \
  -t hearth/node-sbt-builder:$HEARTH_VERSION \
  - < node-sbt-builder.Dockerfile
