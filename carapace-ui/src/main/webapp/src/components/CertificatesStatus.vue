<template>
    <div>
        <div class="page-header">
            <h2 class="title">Certificates</h2>
            <b-button v-b-modal.edit>Create certificate</b-button>
            <b-modal id="edit" title="Create certificate" modal-ok="Save">
                <b-form @submit="onSubmit" @reset="onReset">
                    <b-form-group id="domain"
                                  label="Domain"
                                  label-for="domain"
                                  description="Certificate domain">
                        <b-form-input v-model="form.domain"
                                      type="text"
                                      placeholder="the domain"
                                      required>
                        </b-form-input>
                    </b-form-group>
                </b-form>
                <b-button type="submit" variant="primary">Submit</b-button>
                <b-button type="reset" variant="danger">Reset</b-button>
            </b-modal>
        </div>
        <div v-if="expiredCertificates.length > 0" class="box-warning">
            <strong>These certificates are expired:</strong>
            <ul>
                <li v-for="cert in expiredCertificates" :key="cert.id">
                    <strong>{{cert.id}}</strong> since {{cert.expiringDate}}
                </li>
            </ul>
        </div>
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
    import { doGet } from "./../mockserver";
    import { toBooleanSymbol } from "./../lib/formatter";
    export default {
        name: "Certificates",
        data() {
            return {
                certificates: [],
                loading: true,
                form: {
                    domain: ""
                }
            };
        },
        created() {
            doGet("/api/certificates", data => {
                this.certificates = Object.values(data || {})
                this.loading = false
            });
        },
        computed: {
            fields() {
                return [
                    {key: "id", label: "ID", sortable: true},
                    {key: "hostname", label: "Hostname", sortable: true},
                    {key: "mode", label: "Mode", sortable: true},
                    {
                        key: "dynamic",
                        label: "Dynamic",
                        sortable: true,
                        formatter: toBooleanSymbol
                    },
                    {key: "status", label: "Status", sortable: true},
                    {key: "expiringDate", label: "Expiring Date", sortable: true},
                    {key: "daysBeforeRenewal", label: "Days Before Renewal", sortable: true},
                    {key: "serialNumber", label: "Serial Number", sortable: true},
                    {
                        key: "sslCertificateFile",
                        label: "SSL Certificate File",
                        sortable: true
                    }
                ];
            },
            expiredCertificates() {
                return (this.certificates || []).filter(c => c.status === "expired")
            }
        },
        methods: {
            showCertDetail(cert) {
                this.$router.push({name: "Certificate", params: {id: cert.id}});
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
            onSubmit() {
                console.log("submit", this.form);
            },
            onReset() {
                this.form = {};
                console.log("reset", this.form);
            }
        }
    };
</script>