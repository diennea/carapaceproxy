<style scoped>
    table th,
    table tr td .label {
        font-size: 13px;
    }
    table tr td .label-error{
        background-color: #f44336;
        border-radius: 2px;

        text-transform: uppercase;
        text-align: center;
        font-weight: bold;
        color: white;

        padding: 10px;
    }
    table tr td .label-success{
        background-color: #4CAF50;
        border-radius: 2px;
        
        text-transform: uppercase;
        font-weight: bold;
        text-align: center;
        color: white;

        padding: 10px;
    }
</style>

<template>
    <div>
        <h2>Backends</h2>
        <table class="table table-striped">
            <thead>
                <tr>
                    <th scope="col">Backend</th>
                    <th scope="col">Open connections</th>
                    <th scope="col">Total Requests</th>
                    <th scope="col">Last Activity (Timestamp)</th>
                    <th scope="col">Available</th>
                    <th scope="col">Reported as Unreachable</th>
                    <th scope="col">Reported as Unreachable (Timestamp)</th>
                    <th scope="col">Last probe success</th>
                    <th scope="col">Last probe success (Timestamp)</th>
                    <th scope="col">Last probe result</th>
                </tr>
            </thead>
            <tbody>
                <tr v-for="item of backends" :key="item.id">
                    <td><div class="label">
                        {{formatBackendName(item.host, item.port)}}
                    </div></td>
                    <td><div class="label">
                        {{item.openConnections}}
                    </div></td>
                    <td><div class="label">
                        {{item.totalRequests}}
                    </div></td>
                    <td><div class="label">
                        {{formatDate(item.lastActivityTs)}}
                    </div></td>
                    <td><div class="label" v-bind:class="[item.isAvailable ? 'label-success' : 'label-error']">
                        {{formatStatus(item.isAvailable)}}
                    </div></td>
                    <td><div class="label" v-bind:class="[!item.reportedAsUnreachable ? 'label-success' : 'label-error']">
                        {{formatUnreachable(item.reportedAsUnreachable)}}
                    </div></td>
                    <td><div class="label">
                        {{formatDate(item.reportedAsUnreachableTs)}}
                    </div></td>
                    <td><div class="label" v-bind:class="[item.lastProbeSuccess ? 'label-success' : 'label-error']">
                        {{formatStatus(item.lastProbeSuccess)}}
                    </div></td>
                    <td><div class="label">
                        {{formatDate(item.lastProbeTs)}}
                    </div></td>
                    <td><div class="label" v-html="item.lastProbeResult"></div></td>
                </tr>
            </tbody>
        </table>
    </div>
</template>

<script>
    import { doRequest } from './../mockserver'
    export default {
        name: 'Backends',
        data: function () {
            return {
                backends: []
            }
        },
        created: function () {
            var url = "/api/backends";
            var d = this;
            doRequest(url, {}, function (response) {
                d.backends = [];
                Object.keys(response).forEach(function(key) {
                    d.backends.push(response[key]);
                });
            })

        },
        methods: {
            formatBackendName(host, port) {
                return host + ":" + port + ""
            },
            formatUnreachable(status) {
                if (status === true) {
                    return "UNREACHABLE" 
                }
                return "OK"
            },
            formatStatus(status) {
                if (status === true) {
                    return "UP"
                }
                return "DOWN"
            },
            formatDate(value) {
                if (!value || value <= 0) {
                    return '';
                }
                return new Date(value).toUTCString();
            }
        }
    }
</script>