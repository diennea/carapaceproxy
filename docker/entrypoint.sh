#!/bin/bash
set -e

CONFIGURATION="/carapace/conf/server.properties"

env | while IFS='=' read -r n v; do
    if [[ "$n" == CARAPACE_* ]]; then
      property=${n/CARAPACE_/}
      property=${property/_/\.}
      escaped_value=$(printf '%s\n' "$v" | sed -e 's/[\/&]/\\&/g')
      # shellcheck disable=SC1101
      grep -q "$property" "$CONFIGURATION" &&  sed -i "s/$property.*/$property=$escaped_value/g" "$CONFIGURATION" \
        || echo -e "\n$property=$escaped_value" >> "$CONFIGURATION"
      printf "Setting configuration entry %s: %s\n" "$property" "$v"
    fi
done

exec "$@"
