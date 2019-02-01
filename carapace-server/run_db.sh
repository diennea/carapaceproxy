#!/bin/bash
mvn clean install -DskipTests
cd target
unzip *.zip
cd carapace-server-1.0
./bin/service server console conf/server.db.properties
