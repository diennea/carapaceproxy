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
                            <li class="list-group-item"><strong>ID:</strong> {{certificate.id}}</li>
                            <li class="list-group-item"><strong>Hostname:</strong> {{certificate.hostname}}</li>
                            <li class="list-group-item"><strong>Mode:</strong> {{certificate.mode}}</li>
                            <li class="list-group-item"><strong>Dynamic:</strong> {{certificate.dynamic | symbolFormat}}</li>
                            <li class="list-group-item"><strong>Status:</strong> {{certificate.status}}</li>                            
                            <li class="list-group-item">
                                <strong>SSL Certificate file: </strong>
                                <span v-if="certificate.dynamic">
                                    <a :href="'/api/certificates/' + certificate.id + '/download'">download here</a>
                                </span>
                                <span v-else>{{certificate.sslCertificateFile}}</span>
                            </li>
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
    import { doGet } from './../mockserver'
    export default {
        name: 'Certificate',
        data: function () {
            return {
                found: true,
                certificate: {}
            }
        },
        created: function () {
            var self = this
            var url = "/api/certificates/" + (self.$route.params.id || 0)
            doGet(url, data => {                
                self.certificate = data
                if (!data) {
                    self.found = false
                }
            })
        }
    }
</script>