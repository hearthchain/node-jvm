ARG baseImage=eclipse-temurin:25-jre-noble
FROM $baseImage

ARG TARGETARCH

ENV HEARTH_LOG_LEVEL=INFO
ENV HEARTH_LOG_JAVA_OPTS=true

ENV HEARTH_HEAP_SIZE=2g
ENV HEARTH_NETWORK=mainnet

SHELL ["/bin/bash", "-c"]

# Node files
ENV HEARTH_DATA=/var/lib/hearth
ENV HEARTH_LOG=/var/log/hearth
ENV HEARTH_INSTALL_PATH=/usr/share/hearth

# curl is the healthcheck below. Nothing is downloaded at build time: every jar the image runs on, the Linux-only
# rocksdb and Corretto builds included, is staged into the build context by `sbt stageForDocker`.
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates

# Setup node. There is deliberately no USER instruction: entrypoint.sh starts as root only to take ownership of a
# bind-mounted data or log directory, then re-execs itself as hearth (uid 999) with setpriv, from util-linux in the
# base image. Declaring USER instead would leave every bind mount for the operator to chown by hand.
RUN groupadd -r hearth --gid=999; \
    useradd -r -g hearth --uid=999 --home-dir=$HEARTH_DATA --shell=/bin/bash hearth; \
    # The COPYs below own what they bring in, so only the directories themselves are created here
    install -d -o hearth -g hearth -m 755 $HEARTH_DATA $HEARTH_LOG $HEARTH_INSTALL_PATH

# Ahead of the COPYs below: it is the only instruction left that touches the filesystem, and running it after them
# would force BuildKit to materialize the merged --link layers instead of ending the build with them.
WORKDIR $HEARTH_DATA

# `sbt stageForDocker` lays the node and grpc-server files out under target/: third-party jars in lib/, the ones that
# exist per platform in native/<arch>/, hearth's own jars and scripts in app/. Copying them as separate layers keeps
# the big one cached across builds that only changed hearth's code, and copying (rather than unpacking a tarball in a
# RUN) needs no emulation on a cross-platform build.
# --chown is numeric because --link copies into a layer that has no user database of its own.
COPY --link --chown=999:999 target/lib $HEARTH_INSTALL_PATH/lib
COPY --link --chown=999:999 target/native/$TARGETARCH $HEARTH_INSTALL_PATH/lib
COPY --link --chown=999:999 target/app $HEARTH_INSTALL_PATH
COPY --link --chown=999:999 entrypoint.sh $HEARTH_INSTALL_PATH/bin/entrypoint.sh

VOLUME $HEARTH_DATA
VOLUME $HEARTH_LOG
VOLUME $HEARTH_INSTALL_PATH/lib/plugins

HEALTHCHECK CMD curl -f http://localhost:6869/node/status || exit 1

STOPSIGNAL SIGINT
ENTRYPOINT ["/usr/share/hearth/bin/entrypoint.sh"]
