ARG baseImage=eclipse-temurin:25-jre-noble
FROM $baseImage

ARG TARGETARCH
ARG CORRETTO_VERSION=2.5.0
ARG ROCKSDB_VERSION=10.10.1.1

ENV HEARTH_LOG_LEVEL=INFO
ENV HEARTH_LOG_JAVA_OPTS=true

ENV HEARTH_HEAP_SIZE=2g
ENV HEARTH_NETWORK=mainnet

SHELL ["/bin/bash", "-c"]

# Node files
ENV HEARTH_DATA=/var/lib/hearth
ENV HEARTH_LOG=/var/log/hearth
ENV HEARTH_INSTALL_PATH=/usr/share/hearth
ENV HEARTH_CONFIG=/etc/hearth/hearth.conf

RUN apt-get update && apt-get install -y --no-install-recommends wget curl ca-certificates

RUN if [ "$TARGETARCH" = "amd64" ]; then \
        export CORRETTO_ARCH="linux-x86_64"; \
    elif [ "$TARGETARCH" = "arm64" ]; then \
        export CORRETTO_ARCH="linux-aarch_64"; \
    fi && \
    export CORRETTO_JAR="AmazonCorrettoCryptoProvider-${CORRETTO_VERSION}-${CORRETTO_ARCH}.jar" && \
    export ROCKSDB_JAR="rocksdbjni-${ROCKSDB_VERSION}-linux64.jar" && \
    mkdir -p "${HEARTH_INSTALL_PATH}/lib" && \
    wget "https://repo1.maven.org/maven2/software/amazon/cryptools/AmazonCorrettoCryptoProvider/${CORRETTO_VERSION}/${CORRETTO_JAR}" -O "${HEARTH_INSTALL_PATH}/lib/software.amazon.cryptools.${CORRETTO_JAR}" && \
    wget "https://repo1.maven.org/maven2/org/rocksdb/rocksdbjni/${ROCKSDB_VERSION}/${ROCKSDB_JAR}" -O "${HEARTH_INSTALL_PATH}/lib/org.rocksdb.${ROCKSDB_JAR}"

# Setup node
COPY entrypoint.sh $HEARTH_INSTALL_PATH/bin/entrypoint.sh
RUN mkdir -p $HEARTH_DATA $HEARTH_LOG; \
    # Create user
    groupadd -r hearth --gid=999; \
    useradd -r -g hearth --uid=999 --home-dir=$HEARTH_DATA --shell=/bin/bash hearth

RUN --mount=type=bind,source=target,target=/tmp/ \
    # Unpack tgz packages
    tar zxvf /tmp/hearth.tgz -C $HEARTH_INSTALL_PATH --strip-components=1 --exclude '*AmazonCorrettoCryptoProvider*.jar' --exclude '*conscrypt*.jar' --exclude '*rocksdbjni*' && \
    tar zxvf /tmp/hearth-grpc-server.tgz -C $HEARTH_INSTALL_PATH --strip-components=1 && \
    # Set permissions
    chown -R hearth:hearth $HEARTH_DATA $HEARTH_LOG $HEARTH_INSTALL_PATH && chmod 755 $HEARTH_DATA $HEARTH_LOG;

EXPOSE 6869 6868 6863 6862 6870
VOLUME $HEARTH_DATA
VOLUME $HEARTH_LOG
VOLUME $HEARTH_INSTALL_PATH/lib/plugins
WORKDIR $HEARTH_DATA

HEALTHCHECK CMD curl -f http://localhost:6869/node/status || exit 1

STOPSIGNAL SIGINT
ENTRYPOINT ["/usr/share/hearth/bin/entrypoint.sh"]
