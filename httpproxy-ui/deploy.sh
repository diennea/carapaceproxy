#!/bin/bash
mvn clean install -Pproduction
unzip target/*.war -d ../httpproxy-server/target/httpproxy-server*/web
cd ../httpproxy-server/target
httpproxy-server*/bin/service server console conf/server.properties
