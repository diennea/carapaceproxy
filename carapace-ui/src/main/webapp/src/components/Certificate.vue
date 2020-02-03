<template>
    <div>
        <div v-if="found">          
            <h2>Certificate {{$route.params.id}}</h2>            
            <div class="panel panel-info">
                <div class="panel-body">
                    <ul class="list-group">
                        <li class="list-group-item"><strong>ID:</strong> {{certificate.id}}</li>
                        <li class="list-group-item"><strong>Hostname:</strong> {{certificate.hostname}}</li>
                        <li class="list-group-item"><strong>Mode:</strong> {{certificate.mode}}</li>
                        <li class="list-group-item"><strong>Dynamic:</strong> {{certificate.dynamic | symbolFormat}}</li>
                        <li class="list-group-item"><strong>Status:</strong> {{certificate.status}}</li>
                        <li v-if="certificate.dynamic" class="list-group-item"><strong>Expiring Date:</strong> {{certificate.expiringDate}}</li>
                        <li v-if="certificate.mode == 'acme'" class="list-group-item"><strong>Advance Renewal (days):</strong> {{certificate.daysAdvanceRenewal}}</li>
                        <li v-if="certificate.dynamic" class="p-2 text-center">
                            <b-button
                                :href="'/api/certificates/' + certificate.id + '/download'"
                                variant="primary"
                            >
                                Download
                            </b-button>
                        </li>
                        <li v-else class="list-group-item">
                            <strong>SSL Certificate file: </strong>{{certificate.sslCertificateFile}}                            
                        </li>
                    </ul>
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
        data() {
            return {
                found: true,
                certificate: {}
            }
        },
        created() {
            var url = "/api/certificates/" + (this.$route.params.id || 0)
            doGet(url, data => {
                this.certificate = data
                if (!data) {
                    this.found = false
                }
            })
        }
    }
</script>

<style scoped>
    .panel {
        max-width: 25rem;
    }

    li {
        word-wrap: break-word;
    }
</style>
