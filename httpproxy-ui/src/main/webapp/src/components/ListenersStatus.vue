<template>
    <div>
        <h2>Listeners</h2>
        <table class="table table-striped">
            <thead>
                <tr>
                    <th scope="col">Host</th>
                    <th scope="col">Port</th>
                    <th scope="col">SSL</th>
                    <th scope="col">OCPS</th>
                    <th scope="col">SSLCiphers</th>
                    <th scope="col">Default certificate</th>

                </tr>
            </thead>
            <tbody>
                <tr v-for="item of listeners" :key="item.id">
                    <td>{{item.host}}</td>
                    <td>{{item.port}}</td>
                    <td>{{item.ssl}}</td>
                    <td>{{item.ssl ? item.ocps : ""}}</td>
                    <td>{{item.ssl ? item.sslCiphers : ""}}</td>
                    <td>
                        <router-link :to="{ name: 'Certificate', params: { id: item.defaultCertificate }}"
                                     v-if="item.ssl">
                                {{item.defaultCertificate}}
                        </router-link>
                    </td>
                </tr>
            </tbody>
        </table>
    </div>
</template>

<script>
    import { doRequest } from './../mockserver'
    export default {
        name: 'Listeners',
        data: function () {
            return {
                listeners: []
            }
        },
        created: function () {
            var url = "/api/listeners";
            var d = this;
            doRequest(url, {}, function (response) {
                d.listeners = [];
                Object.keys(response).forEach(function(key) {
                    d.listeners.push(response[key]);
                });
            })

        }
    }
</script>