#!/bin/bash
mvn clean install -DskipTests -Pproduction
cd carapace-server/target
unzip *.zip
carapace-server*/bin/service server console conf/server.properties
