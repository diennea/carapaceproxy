<template>
    <div>
        <h2>Peers</h2>
        <datatable-list :fields="fields" :items="peers">
            <template v-slot:info="{ item }">
                <div v-if="item.info['peer_admin_server_port'] > 0" class="label">
                    <a :href="'http://' + item.info['peer_admin_server_host'] + ':' + item.info['peer_admin_server_port'] + '/ui/#/'">
                        http://{{item.info['peer_admin_server_host']}}:{{item.info['peer_admin_server_port']}}
                    </a>
                </div>
                <div v-if="item.info['peer_admin_server_https_port'] > 0" class="label">
                    <a :href="'https://' + item.info['peer_admin_server_host'] + ':' + item.info['peer_admin_server_https_port'] + '/ui/#/'">
                        https://{{item.info['peer_admin_server_host']}}:{{item.info['peer_admin_server_https_port']}}
                    </a>
                </div>
            </template>
        </datatable-list>
    </div>
</template>

<script>
import { doGet } from "../serverapi";
import { formatVersion } from "../version";
export default {
    name: "Peers",
    data() {
        return {
            peers: []
        };
    },
    created() {
        doGet("/api/cluster/peers", data => {
            // older servers can list a peer as null when it leaves during the request
            this.peers = Object.values(data || {}).filter(Boolean).map(peer => ({
                ...peer,
                version: formatVersion(peer.info["peer_version"], peer.info["peer_commit"])
            }));
        });
    },
    computed: {
        fields() {
            return [
                { key: "id", label: "ID", sortable: true },
                { key: "description", label: "Description", sortable: true },
                // empty for peers that predate the version in the peer info: the sign of a half-upgraded cluster
                { key: "version", label: "Version", sortable: true, isVersion: true },
                { key: "info", label: "Admin Server UI", sortable: true }
            ];
        }
    }
};
</script>

<style scoped>
</style>