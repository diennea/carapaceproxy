#!/bin/bash
set -e

CLUSTER=$1
ROOT_DIR=$(git rev-parse --show-toplevel)

cp "$ROOT_DIR"/carapace-server/target/carapace-server-*.zip "$ROOT_DIR"/docker

cd "$ROOT_DIR"/docker
TARBALL=$(realpath carapace-server-*.zip)
# shellcheck disable=SC2046
 docker build -t carapace/carapace-server:latest --build-arg TARBALL=$(basename "$TARBALL") .
