#!/bin/bash

# stop if running
carapace-server/target/carapace-server-1.0/bin/service server stop

mvn clean install -DskipTests -Pproduction
cd carapace-server/target
unzip *.zip
cd carapace-server-1.0
./bin/service server start


timeout 22 sh -c 'until nc -z $0 $1; do sleep 1; done' localhost 8001

# apply dynamic configuration
curl -X POST --data-binary @conf/server.db.dynamic.properties http://localhost:8001/api/config/apply --user admin:admin -H "Content-Type: text/plain"

tail -f server.service.log

