<template>
    <div>
        <h2>Peers</h2>
        <table class="table table-striped">
            <thead>
                <tr>
                    <th scope="col">Id</th>
                    <th scope="col">Description</th>
                    <th scope="col">Admin Server UI</th>
                </tr>
            </thead>
            <tbody>
                <tr v-for="item of peers" :key="item.id">
                    <td>
                        <div class="label">
                            {{item.id}}
                        </div>
                    </td>
                    <td>
                        <div class="label">
                            {{item.description}}
                        </div>
                    </td>
                    <td>
                        <div class="label">
                            {{item.info['peer_admin_server_host']}}:{{item.info['peer_admin_server_port']}}
                        </div>
                    </td>
<!--                    <td>
                        <div class="label">
                            <b>{{item.httpResponse}}</b><br>
                            <a href="#" @click="openDetail(item.httpBody)">Open probe page</a>
                        </div>
                    </td>-->
                </tr>
            </tbody>
        </table>
    </div>
</template>

<script>
    import { doGet } from './../mockserver'
    export default {
        name: 'Peers',
        data: function () {
            return {
                peers: []
            }
        },
        created: function () {
            var url = "/api/peers"
            var d = this
            doGet(url, response => {
                d.peers = [];
                Object.keys(response).forEach(function (key) {
                    d.peers.push(response[key])
                })
            })

        }
    }
</script>

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