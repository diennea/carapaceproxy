<template>
    <div>
        <div v-if="found">
            <div class="row">
                <h2>Certificate {{$route.params.id}}</h2>
            </div>
            <div class="row mt-3">
                <div class="panel panel-info">
                    <div class="panel-body">
                        <ul class="list-group">
                            <li class="list-group-item"><strong>Certificate id</strong> {{certificate.id}}</li>
                            <li class="list-group-item"><strong>Hostname:</strong> {{certificate.hostname}}</li>
                            <li class="list-group-item"><strong>SSL Certificate file:</strong> {{certificate.sslCertificateFile}}</li>
                        </ul>
                    </div>
                </div>
            </div>
        </div>
        <div v-else>
            <h2>Certificate not found.</h2>
            <div class="alert alert-danger">
                Certificate with id of "{{$route.params.id}}" not found.
            </div>
        </div>
    </div>
</template>

<script>
    import { doRequest } from './../mockserver'
    export default {
        name: 'Certificate',
        data: function () {
            return {
                found: true,
                certificate: {}
            }
        },
        created: function () {
            var d = this;
            var url = "/api/certificates/" + (d.$route.params.id || 0);
            doRequest(url, {}, function (response) {  
                d.certificate = response[(d.$route.params.id || 0)];
                if (!d.certificate) {
                    d.found = false;
                }

            })
        }
    }
</script>