#!/bin/bash
set -e

CONFIGURATION="/carapace/conf/server.properties"

env | while IFS='=' read -r n v; do
    if [[ "$n" == CARAPACE_* ]]; then
      property=${n/CARAPACE_/}
      property=${property/_/\.}
      escaped_value=$(printf '%s\n' "$v" | sed -e 's/[\/&]/\\&/g')
      # shellcheck disable=SC1101
      # the key may be shipped commented out: match it anyway, and replace the whole line, comment included
      grep -qE "^#?[[:space:]]*$property=" "$CONFIGURATION" &&  sed -i -E "s/^#?[[:space:]]*$property=.*/$property=$escaped_value/g" "$CONFIGURATION" \
        || echo -e "\n$property=$escaped_value" >> "$CONFIGURATION"
      printf "Setting configuration entry %s: %s\n" "$property" "$v"
    fi
done

exec "$@"
