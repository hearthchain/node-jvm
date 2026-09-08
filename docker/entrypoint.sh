#!/bin/bash

# The node itself must not run as root, and only the ownership fix here needs privilege, so this script re-execs
# itself unprivileged: everything below runs as hearth, the JVM included. The chown is what makes a bind-mounted
# host directory usable (it arrives with the host's ownership; a fresh named volume inherits the image's instead),
# and it is skipped once the directory is already ours, so a normal start costs one stat. An operator who runs the
# image with --user is left alone.
if [ "$(id -u)" = "0" ] ; then
  for dir in "$HEARTH_DATA" "$HEARTH_LOG" ; do
    [ "$(stat -c %u "$dir")" = "$(id -u hearth)" ] || chown -R hearth:hearth "$dir"
  done
  exec setpriv --reuid=hearth --regid=hearth --init-groups --inh-caps=-all --no-new-privs "$0" "$@"
fi

JAVA_OPTS="-XX:+ExitOnOutOfMemoryError
  -Xmx${HEARTH_HEAP_SIZE}
  --add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED
  --add-opens=java.base/sun.nio.ch=ALL-UNNAMED
  --enable-native-access=ALL-UNNAMED
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

if [ -n "$HEARTH_WALLET_MNEMONIC" ] ; then
  JAVA_OPTS="-Dhearth.wallet.mnemonic=\"${HEARTH_WALLET_MNEMONIC}\" ${JAVA_OPTS}"
fi

if [ -n "$HEARTH_WALLET_PASSWORD" ] ; then
  JAVA_OPTS="-Dhearth.wallet.password=\"${HEARTH_WALLET_PASSWORD}\" ${JAVA_OPTS}"
fi

if [ $# -eq 0 ] && [ -f /etc/hearth/hearth.conf ] ; then
  ARGS=(/etc/hearth/hearth.conf)
else
  ARGS=("$@")
fi

# JAVA_OPTS arrives as one string, so plain expansion would word-split a value containing spaces (a wallet mnemonic,
# a genesis asset description) into several arguments and java would read the second word as the main class name.
# xargs re-splits it honoring the quotes around such values.
mapfile -t JAVA_ARGS < <(printf '%s' "$JAVA_OPTS" | xargs printf '%s\n')

exec java "${JAVA_ARGS[@]}" -cp "$HEARTH_INSTALL_PATH/lib/plugins/*:$HEARTH_INSTALL_PATH/lib/*" tech.hearth.Application "${ARGS[@]}"
