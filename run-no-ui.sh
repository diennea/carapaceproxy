#!/bin/bash
mvn clean install -DskipTests
cd httpproxy-server/target
unzip *.zip
httpproxy-server*/bin/service server console conf/server.properties
