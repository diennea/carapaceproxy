<template>
    <div>
        <h2>Backends</h2>
        <datatable-list :fields="fields" :items="backends">
            <template v-slot:host="{ item }">{{item.host}}:{{item.port}}</template>
            <template v-slot:isAvailable="{ item }">
                <div
                    class="label"
                    :class="[item.isAvailable ? 'label-success' : 'label-error']"
                    >{{item.isAvailable | backendStatusFormat}}</div>
            </template>
            <template v-slot:reportedAsUnreachable="{ item }">
                <div
                    v-if="item.reportedAsUnreachableTs && item.reportedAsUnreachable"
                    class="label label-error"
                    >{{item.reportedAsUnreachable | unreachableFormat}}</div>
                <div v-else></div>
            </template>
            <template v-slot:lastProbeSuccess="{ item }">
                <div
                    v-if="item.lastProbeTs"
                    class="label"
                    :class="[item.lastProbeSuccess ? 'label-success' : 'label-error']"
                    >{{item.lastProbeSuccess | probeSuccessFormat}}</div>
                <div v-else></div>
            </template>
            <template v-slot:httpResponse="{ item }">
                <b>{{item.httpResponse}}</b>
                <br />
                <a href="#" @click="openDetail(item.httpBody)">Open probe page</a>
            </template>
        </datatable-list>
    </div>
</template>

<script>
    import { doGet } from "./../mockserver";
    import { formatTimestamp } from "./../lib/formatter";
    export default {
        name: "Backends",
        data() {
            return {
                backends: []
            };
        },
        created() {
            doGet("/api/backends", data => {
                this.backends = Object.values(data || {});
            });
        },
        computed: {
            fields() {
                return [
                    {key: "id", label: "ID", sortable: true},
                    {key: "host", label: "Host:Port", sortable: true},
                    {
                        key: "openConnections",
                        label: "Open conn",
                        sortable: true
                    },
                    {
                        key: "totalRequests",
                        label: "Req",
                        sortable: true
                    },
                    {key: "isAvailable", label: "Status", sortable: true},
                    {
                        key: "lastActivityTs",
                        label: "Last Activity (TS)",
                        sortable: true,
                        formatter: formatTimestamp
                    },
                    {
                        key: "reportedAsUnreachable",
                        label: "Unreachable",
                        sortable: true
                    },
                    {
                        key: "reportedAsUnreachableTs",
                        label: "Unreachable (TS)",
                        sortable: true,
                        formatter: formatTimestamp
                    },
                    {
                        key: "lastProbeSuccess",
                        label: "Probe Status",
                        sortable: true
                    },
                    {
                        key: "lastProbeTs",
                        label: "Probe (TS)",
                        sortable: true,
                        formatter: formatTimestamp
                    },
                    {key: "lastProbePath", label: "Probe (path)", sortable: true},
                    {
                        key: "httpResponse",
                        label: "Probe (Result)",
                        sortable: true
                    }
                ];
            }
        },
        filters: {
            unreachableFormat(status) {
                if (status === true) {
                    return "UNREACHABLE";
                }
            },
            backendStatusFormat(status) {
                if (status === true) {
                    return "UP";
                }
                return "DOWN";
            },
            probeSuccessFormat(status) {
                if (status === true) {
                    return "SUCCESS";
                }
                return "ERROR";
            }
        },
        methods: {
            openDetail(detail) {
                window
                        .open("", "", "width=900,height=600")
                        .document.write(detail ? detail : "");
            }
        }
    };
</script>

<style lang="scss" scoped>
    @import "../variables.scss";

    table th {
        font-size: 13px;
    }

    table tr td {
        & .label {
            font-size: 13px;
            border-radius: 2px;
            text-transform: uppercase;
            text-align: center;
            font-weight: bold;
            color: $white;
            padding: 10px;
        }

        & .label-success {
            background-color: $success;
        }

        & .label-error {
            background-color: $error;
        }
    }
</style>