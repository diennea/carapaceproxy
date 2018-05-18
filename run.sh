#/bin/bash
HERE=$(dirname $0)
JAR=$(ls $HERE/target/nettyhttpproxy*.jar)
$JAVA_HOME/bin/java -jar $JAR $HERE/conf/server.properties 
