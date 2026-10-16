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

## Backup and restore

The admin API can export and restore the whole Carapace state: the dynamic configuration, the dynamic certificates and the key pairs, including the ACME account key of every provider. ACME challenge tokens are not part of it. The archive is a JSON file that does not depend on the database, so it can be restored on any running installation.

```
curl -f -u admin:password -OJ https://carapace.example.com:4001/api/config/backup
curl -u admin:password -H 'Content-Type: application/json' --data-binary @carapace-state-2026-01-01T000000Z.json https://carapace.example.com:4001/api/config/restore
```

Use the HTTPS admin port (`https.admin.port`, 4001 in the sample configuration): the requests carry the admin credentials and the archive carries every private key.

**The archive holds every private key, the ACME account keys included, and every password or secret of the configuration: treat it as a secret.**

The boot-only settings of `server.properties` (admin ports and certificate, cluster and ZooKeeper, database, user realm class, AWS credentials, metrics endpoint) are never exported nor restored: a restore drops them with a warning and every node keeps its own.

A restore **overwrites** the whole state: whatever is not in the archive is removed. The archive also brings back the maintenance mode it was taken with. It is all or nothing: the archive, the configuration it holds, the static certificate, truststore and backend CA files it references, the AWS credentials of its wildcard certificates and the user realm it configures are checked on the node that receives the restore, before anything is written, and the restored realm must still accept the credentials of the request. Every peer must have the same files. A refused restore answers 422 and changes nothing. A restore that was stored but could not be applied on the receiving node answers 500; the state is stored and the other peers reload it. A restore also answers 500 when the store failed while writing: repeat the restore. If the node that received the restore stops before answering, the state may be stored without the other peers knowing it: repeat the restore.

An archive taken on an older Carapace always restores on a newer one. An archive taken on a newer Carapace is refused, unless you add `?acceptNewerVersion=true` to the restore URL, at your own risk: the settings the older version does not know are kept but ignored, and the certificates of an ACME provider it does not support use the default one. An archive without a valid `carapaceVersion` is refused, unless it comes from or goes to a development build. Only `major.minor.patch` is compared, so `2.4.0-SNAPSHOT` and `2.4.0` are the same version.

In cluster mode every peer must run the same Carapace version, and every replica of the database tablespace must be up (active within the last 30 seconds, change it with `-Dcarapace.store.replica.maxinactivity=<milliseconds>`), otherwise the restore is refused. With `config.type=file` the restore is applied in memory only, like a configuration apply: a restart reloads the configuration file. With `config.type=file` a backup holds the configuration file, or the last restored archive, not what was applied through the API.

Configuration writes, backup and restore included, queue on a cluster-wide lock: one that waits more than 60 seconds answers 422 with `configuration store is busy, try again later`, and in cluster mode one sent while the database does not answer (for example while the leader of its tablespace is down) answers 422 within 10 seconds. Backup and restore wait for the certificate renewal pass in progress, up to 60 seconds, and then answer 422 with `certificate renewal in progress, try again later`. When renewals keep the cluster busy, for example while many certificates are issued again, first apply a configuration with `dynamiccertificatesmanager.period=0` to stop them, wait for the pass in progress to end, then restore: the archive brings back its own period. A lower `dynamiccertificatesmanager.ratelimit` (how many certificates may reach the CA in one pass, 300 by default) also shortens each pass.

A backup is never refused for unavailable certificates, but it tells you when some dynamic certificates of its configuration are not available, because restoring it would issue them again: the `X-Carapace-Domains-Without-Certificate-Count` response header holds their count, `X-Carapace-Domains-Without-Certificate` lists them when the list is short, and the server log always lists them.

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