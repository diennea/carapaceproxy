# Carapaceproxy
A Distributed Java Reverse Proxy

## For Developers
Start Carapace by running `./run.sh` script. This just launch the server, the ui and load a ready to use dynamic configuration.
Server will start at `http://localhost:8001/ui/#/` and `https://localhost:4001/ui/#/` with no authentication required.

To launch/update the ui only, run `./carapace-ui/deploy.sh`

To bundle the project into a .zip archive for a standalone installation run `mvn clean install -DskipTests -Pproduction`. You'll find the generated zip in `carapace-server/target/carapace-server-X.Y.Z-SNAPSHOT.zip`

## For Admins
To install Carapace, just unzip the carapace.zip archive and then run `./bin/service server start [custom-server.properties]` (default server.properties is loaded).
The server will start at `hostname:port` as defined in the server.properties file loaded.
