<template>
    <div>
        <h2>Connection Pools</h2>
        <datatable-list :fields="fields" :items="connectionPools">
        </datatable-list>
    </div>
</template>

<script>
    import { doGet } from "./../mockserver";
    import { toBooleanSymbol } from "../lib/formatter";
    export default {
        name: "ConnectionPools",
        data() {
            return {
                connectionPools: []
            };
        },
        created() {
            doGet("/api/connectionpools", data => {
                this.connectionPools = Object.values(data || {});
            });
        },
        computed: {
            fields() {
                return [
                    {key: "id", label: "ID", sortable: true},
                    {key: "domain", label: "Domain (RegExp)", sortable: true},
                    {
                        key: "maxConnectionsPerEndpoint",
                        label: "Max Endpoint Conn.",
                        sortable: true
                    },
                    {
                        key: "borrowTimeout",
                        label: "Borrow Timeout (ms)",
                        sortable: true
                    },
                    {
                        key: "connectTimeout",
                        label: "Connect Timeout (ms)",
                        sortable: true
                    },
                    {
                        key: "stuckRequestTimeout",
                        label: "Stuck Timeout (ms)",
                        sortable: true
                    },
                    {
                        key: "idleTimeout",
                        label: "Idle Timeout (ms)",
                        sortable: true
                    },
                    {
                        key: "disposeTimeout",
                        label: "Dispose Timeout (ms)",
                        sortable: true
                    },
                    {
                        key: "enabled",
                        label: "Enabled",
                        sortable: true,
                        formatter: toBooleanSymbol
                    },
                    {
                        key: "totalConnections",
                        label: "Open Conn.",
                        sortable: true
                    }
                ];
            }
        }
    };
</script>