#!/bin/bash
mvn clean install -DskipTests
cd target
unzip *.zip
cd ..
target/nettyhttpproxy*/bin/service server console conf/server.properties
