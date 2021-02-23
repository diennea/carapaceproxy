<template>
    <div>
        <h2>Nodes</h2>
        <datatable-list :fields="fields" :items="nodes">
            <template v-slot:admin="{ item }">
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
            <template v-slot:status="{ item }">
                <span v-if="item.info['status']" class="badge-status" :class="[statusClass(item.info['status'])]">
                    {{item.info["status"]}}
                </span>
            </template>
        </datatable-list>
    </div>
</template>

<script>
    import { doGet } from "./../mockserver";
    export default {
        name: "Nodes",
        data() {
            return {
                nodes: []
            };
        },
        created() {
            doGet("/api/cluster/nodes", data => {
                this.nodes = Object.values(data || {});
            });
        },
        computed: {
            fields() {
                return [
                    {key: "id", label: "ID", sortable: true},
                    {key: "description", label: "Description", sortable: true},
                    {key: "admin", label: "Admin Server UI", sortable: true},
                    {key: "status", label: "Status", sortable: true}
                ];
            }
        },
        methods: {
            statusClass(status) {
                if (status === "onlince") {
                    return "success"
                }
                if (status === "offline") {
                    return "error"
                }
                return "info";
            }
        }
    };
</script>