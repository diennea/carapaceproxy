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
                    <th scope="col">Status</th>
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
                        {{item.host}}:{{item.port}}
                    </div></td>
                    <td><div class="label">
                        {{item.openConnections}}
                    </div></td>
                    <td><div class="label">
                        {{item.totalRequests}}
                    </div></td>
                    <td><div class="label">
                        {{item.lastActivityTs | dateFormat}}
                    </div></td>
                    <td><div class="label" :class="[item.isAvailable ? 'label-success' : 'label-error']">
                        {{item.isAvailable | backendStatusFormat}}
                    </div></td>
                    <td>
                        <div v-if="item.reportedAsUnreachableTs && item.reportedAsUnreachable" class="label label-error">
                            {{item.reportedAsUnreachable | unreachableFormat}}
                        </div>
                    </td>
                    <td><div class="label">
                        {{item.reportedAsUnreachableTs | dateFormat}}
                    </div></td>
                    <td>
                        <div v-if="item.lastProbeTs" class="label" :class="[item.lastProbeSuccess ? 'label-success' : 'label-error']">
                            {{item.lastProbeSuccess | probeSuccessFormat}}
                        </div>
                    </td>
                    <td><div class="label">
                        {{item.lastProbeTs | dateFormat}}
                    </div></td>
                    <td><div class="label" v-html="item.lastProbeResult"></div></td>
                </tr>
            </tbody>
        </table>
    </div>
</template>

<script>
    import { doGet } from './../mockserver'
    export default {
        name: 'Backends',
        data: function () {
            return {
                backends: []
            }
        },
        created: function () {
            var url = "/api/backends"
            var d = this
            doGet(url, response => {
                d.backends = [];
                Object.keys(response).forEach(function(key) {
                    d.backends.push(response[key])
                })
            })

        },
        filters: {
            unreachableFormat(status) {
                if (status === true) {
                    return "UNREACHABLE"
                }
            },
            backendStatusFormat(status) {
                if (status === true) {
                    return "UP"
                }
                return "DOWN"
            },
            probeSuccessFormat(status) {
                if (status === true) {
                    return "SUCCESS"
                }
                return "ERROR"
            }
        }
    }
</script>