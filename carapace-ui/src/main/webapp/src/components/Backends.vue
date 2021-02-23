<template>
    <div>
        <h2>Backends</h2>
        <datatable-list :fields="fields" :items="backends">
            <template v-slot:host="{ item }">{{item.host}}:{{item.port}}</template>
            <template v-slot:isAvailable="{ item }">
                <span class="badge-status"
                      :class="[item.isAvailable ? 'success' : 'error']">
                    {{item.isAvailable | backendStatusFormat}}
                </span>
            </template>
            <template v-slot:reportedAsUnreachable="{ item }">
                <span v-if="item.reportedAsUnreachableTs && item.reportedAsUnreachable"
                      class="badge-status error">
                    {{item.reportedAsUnreachable | unreachableFormat}}
                </span>
            </template>
            <template v-slot:lastProbeSuccess="{ item }">
                <span v-if="item.lastProbeTs"
                      class="badge-status"
                      :class="[item.lastProbeSuccess ? 'success' : 'error']">
                    {{item.lastProbeSuccess | probeSuccessFormat}}
                </span>
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
                        label: "Open connections",
                        sortable: true
                    },
                    {
                        key: "totalRequests",
                        label: "Total Requests",
                        sortable: true
                    },
                    {
                        key: "lastActivityTs",
                        label: "Last Activity (Timestamp)",
                        sortable: true,
                        formatter: formatTimestamp
                    },
                    {key: "isAvailable", label: "Status", sortable: true},
                    {
                        key: "reportedAsUnreachable",
                        label: "Reported as Unreachable",
                        sortable: true
                    },
                    {
                        key: "reportedAsUnreachableTs",
                        label: "Reported as Unreachable (Timestamp)",
                        sortable: true,
                        formatter: formatTimestamp
                    },
                    {key: "lastProbePath", label: "Probe path", sortable: true},
                    {
                        key: "lastProbeSuccess",
                        label: "Last probe (Status)",
                        sortable: true
                    },
                    {
                        key: "lastProbeTs",
                        label: "Last probe (Timestamp)",
                        sortable: true,
                        formatter: formatTimestamp
                    },
                    {
                        key: "httpResponse",
                        label: "Last probe (Result)",
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