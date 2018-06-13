#!/bin/bash
cd httpproxy-server
mvn clean install -DskipTests
cd target
unzip *.zip
cd httpproxy-server-1.0
./bin/service server console conf/server.properties
