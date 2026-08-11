#!/bin/bash

HEARTH_VERSION=$(cut -d\" -f2 version.sbt)

docker run \
  -v "$PWD":/src \
  -e HOME=/opt/sbt/home \
  -w /src \
  --rm -it hearth/node-sbt-builder:$HEARTH_VERSION \
  /bin/sh -c "sbt --batch packageAll"
