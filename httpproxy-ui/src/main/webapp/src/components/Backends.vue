<style scoped>
    table th, table tr {
        font-size: 13px;
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
                    <td>{{formatBackendName(item.host, item.port)}}</td>
                    <td>{{item.openConnections}}</td>
                    <td>{{item.totalRequests}}</td>
                    <td>{{formatDate(item.lastActivityTs)}}</td>
                    <td>{{item.isAvailable}}</td>
                    <td>{{item.reportedAsUnreachable}}</td>
                    <td>{{formatDate(item.reportedAsUnreachableTs)}}</td>
                    <td>{{item.lastProbeSuccess}}</td>
                    <td>{{formatDate(item.lastProbeTs)}}</td>
                    <td v-html="item.lastProbeResult"></td>
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
                return host + ":" + port
            },
            formatDate(value) {
                return new Date(value).toUTCString();
            }
        }
    }
</script>