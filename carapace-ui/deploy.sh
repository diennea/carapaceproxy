#!/bin/bash
mvn clean install -Pproduction
unzip target/*.war -d ../carapace-server/target/carapace-server*/web
cd ../carapace-server/target
carapace-server*/bin/service server console conf/server.properties
