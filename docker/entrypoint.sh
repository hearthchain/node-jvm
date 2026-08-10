#!/bin/bash

JAVA_OPTS="-XX:+ExitOnOutOfMemoryError
  -Xmx${HEARTH_HEAP_SIZE}
  --add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED
  --add-opens=java.base/sun.nio.ch=ALL-UNNAMED
  -Dlogback.stdout.level=${HEARTH_LOG_LEVEL}
  -Dlogback.file.directory=${HEARTH_LOG}
  -Dlogback.file.level=TRACE
  -Dhearth.config.directory=/etc/hearth
  -Dhearth.defaults.blockchain.type=${HEARTH_NETWORK}
  -Dhearth.directory=${HEARTH_DATA}
  -Dhearth.rest-api.bind-address=0.0.0.0
  ${JAVA_OPTS}"

if [ "$HEARTH_LOG_JAVA_OPTS" = "true" ] ; then
  echo "JAVA_OPTS=${JAVA_OPTS}" | tee -a ${HEARTH_LOG}/hearth.log
fi

if [ -n "$HEARTH_WALLET_SEED" ] ; then
  JAVA_OPTS="-Dhearth.wallet.seed=${HEARTH_WALLET_SEED} ${JAVA_OPTS}"
fi

if [ -n "$HEARTH_WALLET_PASSWORD" ] ; then
  JAVA_OPTS="-Dhearth.wallet.password=${HEARTH_WALLET_PASSWORD} ${JAVA_OPTS}"
fi

if [ $# -eq 0 ] && [ -f /etc/hearth/hearth.conf ] ; then
  ARGS="/etc/hearth/hearth.conf"
else
  ARGS=$@
fi

exec java $JAVA_OPTS -cp "$HEARTH_INSTALL_PATH/lib/plugins/*:$HEARTH_INSTALL_PATH/lib/*" tech.hearth.Application $ARGS
