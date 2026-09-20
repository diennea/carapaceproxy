# Carapaceproxy
A Distributed Java Reverse Proxy

## For Developers
Start Carapace by running `./run.sh` script. This just launch the server, the ui and load a ready to use dynamic configuration.
Server will start at `http://localhost:8001/ui/#/` and `https://localhost:4001/ui/#/`. The script configures `SimpleUserRealm`, so the local admin interface accepts any credentials; see [Admin authentication](#admin-authentication).

To launch/update the ui only, run `./carapace-ui/deploy.sh`

To bundle the project into a .zip archive for a standalone installation run `mvn clean install -DskipTests -Pproduction`. You'll find the generated zip in `carapace-server/target/carapace-server-X.Y.Z-SNAPSHOT.zip`

## For Admins
To install Carapace, just unzip the carapace.zip archive and then run `./bin/service server start [custom-server.properties]` (default server.properties is loaded).
The server will start at `hostname:port` as defined in the server.properties file loaded.

## Admin authentication

Every request to the admin API is authenticated against the realm named by `userrealm.class`, and while the admin interface is enabled a realm is **required**: the server refuses to start without one, so that an admin API is never left open by accident.

```
userrealm.class=org.carapaceproxy.user.FileUserRealm
userrealm.path=conf/user.properties
```

`FileUserRealm` reads the `user.username=password` entries of `userrealm.path` and reloads them at every configuration apply. The alternative, `org.carapaceproxy.user.SimpleUserRealm`, is a development stub: it accepts any username and password, and says so with a warning at startup.

## Docker

You can build carapace docker image by running:
```
mvn clean install -DskipTests -Pproduction
docker/build.sh
```
Then you can run the container and the admin interface will be listening on 0.0.0.0:8001, reachable from outside the container: pick a realm for it, as described in [Admin authentication](#admin-authentication).
```
docker run -p 8001:8001 -e CARAPACE_userrealm.class=org.carapaceproxy.user.FileUserRealm carapace/carapace-server:latest
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