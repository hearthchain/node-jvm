#!/usr/bin/env bash
# Commits an account to generating in the next generation period: signs a CommitToGeneration transaction and
# broadcasts it.
#
# The signing runs inside the node's own container rather than on the host, because everything the commitment needs
# beyond the height is already in the config mounted there: the generator keys under hearth.miner.accounts (the
# endorser BLS key, the VRF key, and the proofs of possession over both) and the network id the transaction is
# signed for. No key material passes through this script, the host environment, or a command line.
#
# The height is the one thing a config cannot supply, so it is read from the running node instead, and the signed
# transaction goes back to the same node to be broadcast.
#
# Usage:
#   ./sign-commit-to-generation.sh                commit the sole configured account
#   ./sign-commit-to-generation.sh thrth1...      commit that account, when several are configured
#
# Environment:
#   HEARTH_NODE_API            node REST API           (default http://localhost:5520)
#   HEARTH_SERVICE             compose service name    (default hearth-node)
#   HEARTH_CONF                config path in the container (default /etc/hearth/hearth.conf)
#   HEARTH_GENERATOR_ADDRESS   account to commit, same as the positional argument

set -euo pipefail

API=${HEARTH_NODE_API:-http://localhost:5520}
SERVICE=${HEARTH_SERVICE:-hearth-node}
CONF=${HEARTH_CONF:-/etc/hearth/hearth.conf}
ADDRESS=${HEARTH_GENERATOR_ADDRESS:-}

# TransactionType.CommitToGeneration
TX_TYPE=5

usage() { sed -n '2,/^$/p' "$0" | cut -c 3-; }

addresses=0
while [ $# -gt 0 ]; do
  case "$1" in
    -h | --help)
      usage
      exit 0
      ;;
    -*)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
    *)
      addresses=$((addresses + 1))
      [ "$addresses" -eq 1 ] || { echo "Only one address can be committed at a time" >&2; exit 2; }
      ADDRESS=$1
      ;;
  esac
  shift
done

command -v jq >/dev/null || { echo "jq is required" >&2; exit 1; }

# A period is only known once deterministic finality is active, and a commitment always names the next one: the
# period a generator can still join, rather than the one it is already too late for.
finality=$(curl -fsS "$API/blockchain/finality")
if ! period_start=$(jq -e -r '.nextGenerationPeriod.start' <<<"$finality"); then
  echo "$API reports no next generation period, so there is nothing to commit to yet." >&2
  exit 1
fi

# --argjson rejects a period start the node did not report as a number, so a malformed one fails here rather than
# reaching the node. Naming no sender lets the util commit for the sole configured account.
request=$(jq -nc --argjson type "$TX_TYPE" --argjson start "$period_start" --arg sender "$ADDRESS" \
  '{type: $type, generationPeriodStart: $start} + (if $sender == "" then {} else {sender: $sender} end)')

# Logging is silenced on both appenders because the signed transaction leaves on stdout, and the config load this
# command performs logs there by default. --user hearth keeps the exec from writing anything into the container as
# root; the util itself only reads. Note that -c belongs after the command chain, not before it: scopt reads the
# util's shared options as children of the command, and "-c ... transaction sign" fails with "Unknown argument".
signed=$(printf '%s' "$request" | docker compose exec -T --user hearth "$SERVICE" \
  java -Dlogback.stdout.level=OFF -Dlogback.file.level=OFF --enable-native-access=ALL-UNNAMED \
  -cp '/usr/share/hearth/lib/*' tech.hearth.utils.UtilApp transaction sign -c "$CONF")

# The body of a rejected broadcast says why, so the status code is read alongside it rather than through curl -f,
# which would discard it.
response=$(curl -sS -w $'\n%{http_code}' -X POST -H 'Content-Type: application/json' --data-binary "$signed" \
  "$API/transactions/broadcast")
status=${response##*$'\n'}
body=${response%$'\n'*}

if [ "$status" != 200 ]; then
  echo "Broadcast failed with HTTP $status: $body" >&2
  exit 1
fi

jq -r --arg start "$period_start" \
  '"Committed " + .sender + " to the generation period starting at " + $start, "Transaction " + .id' <<<"$body"
