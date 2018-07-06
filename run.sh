#!/bin/bash
mvn clean install -DskipTests -Pproduction
cd httpproxy-server/target
unzip *.zip
httpproxy-server*/bin/service server console conf/server.properties
