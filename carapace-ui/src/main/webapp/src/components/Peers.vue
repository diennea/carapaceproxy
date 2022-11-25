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
export default {
    name: "Peers",
    data() {
        return {
            peers: []
        };
    },
    created() {
        doGet("/api/cluster/peers", data => {
            this.peers = Object.values(data || {});
        });
    },
    computed: {
        fields() {
            return [
                { key: "id", label: "ID", sortable: true },
                { key: "description", label: "Description", sortable: true },
                { key: "info", label: "Admin Server UI", sortable: true }
            ];
        }
    }
};
</script>

<style scoped>
</style>