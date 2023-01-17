<template>
    <div>
        <div v-if="found">
            <h2>Certificate {{$route.params.id}}</h2>
            <b-alert id="status-alert"
                     fade
                     dismissible
                     :show="opMessage ? 10 : 0"
                     @dismissed="opMessage = ''"
                     :variant="opSuccess ? 'success' : 'danger'">
                {{opMessage}}
            </b-alert>
            <div class="panel panel-info">
                <div class="panel-body">
                    <ul class="list-group">
                        <li class="list-group-item"><strong>ID:</strong> {{certificate.id}}</li>
                        <li class="list-group-item"><strong>Hostname:</strong> {{certificate.hostname}}</li>
                        <li class="list-group-item"><strong>Subject Alternative Names:</strong> {{certificate.subjectAltNames}}</li>
                        <li class="list-group-item"><strong>Mode:</strong> {{certificate.mode}}</li>
                        <li class="list-group-item"><strong>Dynamic:</strong> {{certificate.dynamic | symbolFormat}}</li>
                        <li class="list-group-item">
                            <div class="flex-container">
                                <div>
                                    <strong>Status: </strong>
                                    <span class="badge-status" :class="[statusClass(certificate)]">
                                        {{certificate.status}}
                                    </span>
                                </div>
                                <b-button v-b-toggle.details variant="outline-secondary">
                                    <font-awesome-icon icon="circle-info" />
                                </b-button>
                            </div>
                            <b-collapse id="details" class="mt-2">
                                <b-card>
                                    <strong>Attempts:</strong>
                                    {{certificate.attemptsCount || 0}}
                                    <b-button
                                        v-if="certificate.mode === 'acme'"
                                        class="btn"
                                        variant="outline-secondary"
                                        @click="reset">
                                        Reset to waiting
                                    </b-button>
                                </b-card>
                                <b-card v-if="!!certificate.message">
                                    {{certificate.message}}
                                </b-card>
                            </b-collapse>
                        </li>
                        <li class="list-group-item"><strong>Expiring Date:</strong> {{certificate.expiringDate}}</li>
                        <li v-if="certificate.mode === 'acme'" class="list-group-item"><strong>Days Before Renewal:</strong> {{certificate.daysBeforeRenewal}}</li>
                        <li class="list-group-item"><strong>Serial Number:</strong> {{certificate.serialNumber}}</li>
                        <li v-if="certificate.dynamic" class="p-2 text-center">
                        <b-button :href="'/api/certificates/' + certificate.id + '/download'" class="btn">
                            Download
                        </b-button>
                        <b-button v-if="certificate.mode === 'acme' && localStorePath"
                                  @click="openConfirmModal"
                                  variant="outline-secondary"
                                  class="btn">
                            Dump
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
    import { doGet, doPost } from '../../serverapi'
    export default {
        name: 'Certificate',
        data() {
            return {
                certificate: {},
                found: true,
                localStorePath: null,
                opSuccess: null,
                opMessage: ""
            }
        },
        created() {
            this.fetchCertificateData();
        },
        methods: {
            fetchCertificateData() {
                const url = "/api/certificates/" + (this.$route.params.id || 0);
                doGet(url, data => {
                    this.certificate = data.certificates[0] || {}
                    this.localStorePath = data.localStorePath
                    if (!data) {
                        this.found = false
                    }
                })
            },
            statusClass(cert) {
                if (cert.status === 'available') {
                    return "success"
                }
                if (cert.status === 'domain unreachable') {
                    return "warning"
                }
                if (cert.status === 'expired') {
                    return "error"
                }
                return "info";
            },
            openConfirmModal() {
                const message = this.$createElement('div', {
                    domProps: {
                        innerHTML: 'The certificate gonna be dumped to path: <strong>'
                                + this.localStorePath + this.certificate.id + '</strong>'
                                + '<br>The existent dump gonna be overwritten, proceed?'
                    }
                })
                this.$bvModal.msgBoxConfirm([message], {
                    title: 'Attention',
                    okVariant: 'warning',
                    okTitle: 'Yes',
                    footerClass: 'p-2',
                    hideHeaderClose: false,
                    centered: true
                }).then(value => {
                    if (value !== true) {
                        return;
                    }
                    doPost("/api/certificates/" + this.certificate.id + "/store", null, () => {
                                this.opSuccess = true
                                this.opMessage = 'Certificate dumped'
                            },
                            error => {
                                this.opSuccess = false
                                this.opMessage = 'Unable to dump certificate to path: ' + this.localStorePath + '. Cause: ' + error.message
                            }
                    );
                })
            },
            reset() {
                doPost("/api/certificates/" + this.certificate.id + "/reset", null, () => {
                        this.opSuccess = true;
                        this.opMessage = 'Certificate state reset';
                        this.fetchCertificateData();
                    },
                    error => {
                        this.opSuccess = false;
                        this.opMessage = 'Unable to reset certificate for ' + this.certificate.id + '. Cause: ' + error.message;
                        this.fetchCertificateData();
                    }
                );
            }
        }
    }
</script>

<style scoped lang="scss">
    .panel {
        max-width: 30rem;
    }
    li {
        word-wrap: break-word;
        .btn {
            margin: auto 0.5rem;
        }
    }
    #status-alert {
        position: absolute;
        top: 2rem;
        left: 25%;
        right: 25%;
        z-index: 999;
    }
    .flex-container {
        display: flex;
        justify-content: space-between;
        align-items: center;
    }
</style>
