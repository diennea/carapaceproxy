<template>
    <div>
        <h2>Certificates</h2>
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
                <span class="badge-status" :class="[statusClass(item)]">
                    {{item.status}}
                </span>
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
                loading: true
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
                if (cert.status === "expired") {
                    return "error"
                }
                return "info";
            }
        }
    };
</script>