<template>
    <div>
        <h2>Certificates</h2>
        <datatable-list :fields="fields" :items="certificates" :rowClicked="showCertDetail"></datatable-list>
    </div>
</template>

<script>
import { doGet } from "./../mockserver";
import { toBooleanSymbol } from "./../lib/formatter";
export default {
    name: "Certificates",
    data() {
        return {
            certificates: []
        };
    },
    created() {
        var url = "/api/certificates";
        var self = this;
        doGet(url, data => {
            self.certificates = Object.values(data || {});
        });
    },
    computed: {
        fields() {
            return [
                { key: "id", label: "ID", sortable: true },
                { key: "hostname", label: "Hostname", sortable: true },
                { key: "mode", label: "Mode", sortable: true },
                {
                    key: "dynamic",
                    label: "Dynamic",
                    sortable: true,
                    formatter: toBooleanSymbol
                },
                { key: "status", label: "Status", sortable: true },
                {
                    key: "sslCertificateFile",
                    label: "SSL Certificate File",
                    sortable: true
                }
            ];
        }
    },
    methods: {
        showCertDetail(cert) {
            this.$router.push({ name: "Certificate", params: { id: cert.id } });
        }
    }
};
</script>