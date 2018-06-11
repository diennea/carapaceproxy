#!/bin/bash
cd httpproxy-server
mvn clean install -DskipTests
cd target
unzip *.zip
./bin/service server console conf/server.properties
