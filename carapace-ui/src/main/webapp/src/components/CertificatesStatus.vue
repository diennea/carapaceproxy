<style scoped>
    table tr {
        cursor: pointer;
    }
</style>

<template>
    <div>
        <h2>Certificates</h2>
        <table class="table table-striped">
            <thead>
                <tr>
                    <th scope="col">ID</th>
                    <th scope="col">Hostname</th>
                    <th scope="col">Dynamic</th>
                    <th scope="col">Status</th>
                    <th scope="col">SSLCertificateFile</th>
                </tr>
            </thead>
            <tbody>
                <router-link
                    tag="tr"
                    :to="{ name: 'Certificate', params: { id: item.id }}"
                    v-for="item of certificates"
                    :key="item.id"
                    replace >

                    <td>{{(item.id)}}</td>
                    <td>{{(item.hostname)}}</td>
                    <td>{{(item.dynamic) | symbolize}}</td>
                    <td>{{(item.status)}}</td>
                    <td>{{(item.sslCertificateFile)}}</td>
                </router-link>
            </tbody>
        </table>
    </div>
</template>

<script>
    import { doRequest } from './../mockserver'
    export default {
        name: 'Certificates',
        data: function () {
            return {
                certificates: []
            }
        },
        created: function () {
            var url = "/api/certificates";
            var d = this;
            doRequest(url, {}, function (response) {
                d.certificates = [];
                Object.keys(response).forEach(function(key) {
                    d.certificates.push(response[key]);
                });
            })
        }
    }
</script>
