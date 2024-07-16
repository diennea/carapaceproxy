#!/bin/bash



# Licensed to Diennea S.r.l. under one
# or more contributor license agreements. See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership. Diennea S.r.l. licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.


# Carapace version
CARAPACE_V=$(mvn org.apache.maven.plugins:maven-help-plugin:3.1.1:evaluate -Dexpression=project.version -q -DforceStdout -Dmaven.wagon.http.ssl.insecure=true)

# stop if running
carapace-server/target/carapace-server-${CARAPACE_V}/bin/service server stop

mvn clean install -DskipTests -Pproduction
cd carapace-server/target || exit
unzip ./*.zip
cd "carapace-server-${CARAPACE_V}" || exit
./bin/service server start

timeout 22 sh -c "until nc -z \$0 \$1; do sleep 1; done" localhost 8001

# apply dynamic configuration
curl -X POST --data-binary @conf/server.dynamic.properties http://localhost:8001/api/config/apply --user admin:admin -H "Content-Type: text/plain"

tail -f server.service.log

