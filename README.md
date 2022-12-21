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

## Docker

You can build carapace docker image by running:
```
mvn clean install -DskipTests -Pproduction
docker/build.sh
```
Then you can run the container and the admin interface will be listening on 0.0.0.0:8001
```
docker run -p8001:8001 carapace/carapace-server:latest
```
You can also pass system properties using docker option `-e` with the prefix `CARAPACE`:

```
docker run -p 8001:8001 -e CARAPACE_mode=cluster -e CARAPACE_zkAddress=localhost:2181 carapace/carapace-server:latest
```

### Docker start a simple cluster with 1 node

You can run a simple 1-node cluster using the docker-compose.yml file.
This starts a Carapace node (with embedded bookkeeper and herddb) and Zookeeper.
The replication factor of bookkeeper in this case will be set to 1.

```
docker compose up -d
```