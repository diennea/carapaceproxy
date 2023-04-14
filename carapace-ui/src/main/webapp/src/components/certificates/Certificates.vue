<template>
    <div>
        <div class="page-header">
            <h2>Certificates</h2>
            <b-button v-b-modal.edit>Create certificate</b-button>
            <certificate-form id="edit" @done="showCertStatus"></certificate-form>
            <b-button v-if="localStorePath"
                      @click="openConfirmModal"
                      class="btn">
                Dump all certificates
            </b-button>
        </div>
        <b-alert id="status-alert"
                 fade
                 dismissible
                 :show="opMessage ? 10 : 0"
                 @dismissed="opMessage = ''"
                 :variant="opSuccess ? 'success' : 'danger'">
            {{opMessage}}
        </b-alert>
        <status-box
            v-if="expiredCertificates.length > 0"
            title="These certificates are expired"
            status="warning"
        >
            <ul>
                <li v-for="cert in expiredCertificates" :key="cert.id">
                    <strong>{{cert.id}}</strong> since {{cert.expiringDate}}
                </li>
            </ul>
        </status-box>
        <datatable-list :fields="fields" :items="certificates" :rowClicked="showCertDetail" :loading="loading">
            <template v-slot:status="{ item }">
                <div class="badge-status" :class="[statusClass(item)]">
                    {{item.status}}
                </div>
            </template>
        </datatable-list>
    </div>
</template>

<script>
    import { doGet, doPost } from '../../serverapi'
    import { toBooleanSymbol } from "../../lib/formatter";
    import CertificateForm from "./CertificateForm.vue";
    import StatusBox from "../StatusBox.vue";
    export default {
        name: "Certificates",
        components: {
            "certificate-form": CertificateForm,
            "status-box": StatusBox
        },
        data() {
            return {
                certificates: [],
                localStorePath: null,
                loading: true,
                opSuccess: null,
                opMessage: ""
            };
        },
        created() {
            this.loadCertificates()
        },
        computed: {
            fields() {
                return [
                    {key: "id", label: "ID", sortable: true},
                    {key: "hostname", label: "Hostname", sortable: true},
                    {key: "subjectAltNames", label: "SANs", sortable: true},
                    {key: "mode", label: "Mode", sortable: true},
                    {
                        key: "dynamic",
                        label: "Dynamic",
                        sortable: true,
                        formatter: toBooleanSymbol
                    },
                    {key: "status", label: "Status", sortable: true},
                    {
                        key: "attemptsCount",
                        label: "Attempts count",
                        sortable: true,
                    },
                    {key: "expiringDate", label: "Expiring Date", sortable: true},
                    {key: "daysBeforeRenewal", label: "Days Before Renewal", sortable: true},
                    {key: "serialNumber", label: "Serial Number", sortable: true},
                    {
                        key: "sslCertificateFile",
                        label: "SSL Certificate File",
                        sortable: true,
                    },
                ];
            },
            expiredCertificates() {
                return (this.certificates || []).filter(c => c.status === "expired")
            },
        },
        methods: {
            loadCertificates() {
                this.loading = true
                doGet("/api/certificates", data => {
                    this.certificates = data.certificates || {}
                    this.localStorePath = data.localStorePath
                    this.loading = false
                });
            },
            showCertDetail(cert) {
                this.$router.push({name: "Certificate", params: {id: cert.id}});
            },
            showCertStatus(domain) {
                this.$router.push({name: "Certificate", params: {id: domain}});
            },
            statusClass(cert) {
                if (cert.status === "available") {
                    return "success"
                }
                if (cert.status === 'domain unreachable') {
                    return "warning"
                }
                if (cert.status === "expired") {
                    return "error"
                }
                return "info";
            },
            openConfirmModal() {
                const message = this.$createElement('div', {
                    domProps: {
                        innerHTML: 'All certificates gonna be dumped to path: <strong>' + this.localStorePath + '</strong>'
                                + '<br>The existent dump gonna be overwritten and might take several time, proceed?'
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
                    if (value != true) {
                        return;
                    }
                    doPost("/api/certificates/storeall", null,() => {
                                this.opSuccess = true
                                this.opMessage = 'Certificates dump started'
                            },
                            error => {
                                this.opSuccess = false;
                                this.opMessage = 'Unable to dump certificates to path: ' + this.localStorePath + '. Cause: ' + error.message;
                            }
                    );
                })
            }
        }
    };
</script>

<style scoped lang="scss">
    .page-header {
        display: flex;
        align-items: center;
        justify-content: space-between;
    }

    #status-alert {
        position: absolute;
        top: 2rem;
        left: 25%;
        right: 25%;
        z-index: 999;
    }
</style>