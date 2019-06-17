<template>
    <div>
        <h2>Peers</h2>
        <table class="table table-striped">
            <thead>
                <tr>
                    <th scope="col">ID</th>
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
                        <div v-if="item.info['peer_admin_server_port'] > 0" class="label">
                            <a :href="'http://' + item.info['peer_admin_server_host'] + ':' + item.info['peer_admin_server_port'] + '/ui/#/'" >
                                http://{{item.info['peer_admin_server_host']}}:{{item.info['peer_admin_server_port']}}
                            </a>
                        </div>
                        <div v-if="item.info['peer_admin_server_https_port'] > 0" class="label">
                            <a :href="'https://' + item.info['peer_admin_server_host'] + ':' + item.info['peer_admin_server_https_port'] + '/ui/#/'" >
                                https://{{item.info['peer_admin_server_host']}}:{{item.info['peer_admin_server_https_port']}}
                            </a>
                        </div>
                    </td>
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
            var url = "/api/cluster/peers"
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
</style>