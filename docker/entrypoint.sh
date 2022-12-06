#!/bin/bash
set -e

#Cluster mode
if [[ "$CARAPACE_mode" == cluster ]]; then
  mv /data/carapace/conf/server.properties /data/carapace/conf/_server.properties_
  mv /data/carapace/conf/cluster.properties /data/carapace/conf/server.properties
fi

env | while IFS='=' read -r n v; do
    if [[ "$n" == CARAPACE_* ]]; then
      property=${n/CARAPACE_/}
      property=${property/_/\.}
      escaped_value=$(printf '%s\n' "$v" | sed -e 's/[\/&]/\\&/g')
      sed -i "s/$property.*/$property=$escaped_value/g" /carapace/conf/server.properties
      printf "Setting configuration entry %s: %s\n" "$property" "$v"
    fi
done

exec "$@"