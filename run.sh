#!/bin/bash

# Carapace bump version
CARAPACE_V=$(mvn org.apache.maven.plugins:maven-help-plugin:3.1.1:evaluate -Dexpression=project.version -q -DforceStdout -Dmaven.wagon.http.ssl.insecure=true)

# stop if running
carapace-server/target/carapace-server-${CARAPACE_V}/bin/service server stop

mvn clean install -DskipTests -Pproduction
cd carapace-server/target
unzip *.zip
cd carapace-server-${CARAPACE_V}
./bin/service server start

timeout 22 sh -c 'until nc -z $0 $1; do sleep 1; done' localhost 8001

# apply dynamic configuration
curl -X POST --data-binary @conf/server.dynamic.properties http://$(hostname):8001/api/config/apply --user admin:admin -H "Content-Type: text/plain"

tail -f server.service.log

