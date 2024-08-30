<template>
    <div>
        <div class="page-header">
            <h2>Certificates</h2>
            <div class="header-actions">
                <b-form-radio-group
                    v-model="selectedFilter"
                    button-variant="outline-secondary"
                    buttons
                    button-class="btn">
                    <b-form-radio value="all">All</b-form-radio>
                    <b-form-radio value="available">Available{{ availableCount > 0 ? ': ' + availableCount : '' }}</b-form-radio>
                    <b-form-radio value="expired">Expired{{ expiredCount > 0 ? ': ' + expiredCount : '' }}</b-form-radio>
                    <b-form-radio value="unreachable">Unreachable{{ unreachableCount > 0 ? ': ' + unreachableCount : '' }}</b-form-radio>
                </b-form-radio-group>
                <b-button v-b-modal.edit>Create certificate</b-button>
            </div>
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
            v-if="expiredCount > 0"
            title="These certificates are expired"
            status="warning">
            <ul>
                <li v-for="cert in expiredCertificates" :key="cert.id">
                    <strong>{{cert.id}}</strong> since {{cert.expiringDate}}
                </li>
            </ul>
        </status-box>
        <br>
        <status-box
            v-if="unreachableCount > 0"
            :title="`There are also ${unreachableCount} unreachable certificates`"
            status="warning" />
        <datatable-list
            :fields="fields"
            :items="filteredCertificates"
            :rowClicked="showCertDetail"
            :loading="loading"
        >
            <template v-slot:status="{ item }">
                <div class="badge-status" :class="[statusClass(item)]">
                    {{item.status}}
                </div>
            </template>
        </datatable-list>
    </div>
</template>

<script>
import {BAlert, BButton, BFormRadio, BFormRadioGroup} from 'bootstrap-vue';
import {doGet, doPost} from '../../serverapi';
import {toBooleanSymbol} from '../../lib/formatter';
import CertificateForm from './CertificateForm.vue';
import StatusBox from '../StatusBox.vue';

/**
 * Certificate status options for dynamic SSL certificates.
 *
 * @typedef {'waiting'|'domain unreachable'|'dns-challenge-wait'|'verifying'|'verified'|'ordering'|'request failed'|'available'|'expired'} CertificateStatus
 */

/**
 * @typedef {Object} Certificate
 * @property {number} id - Unique identifier for the certificate.
 * @property {string} hostname - The hostname the certificate is issued for.
 * @property {string[]} subjectAltNames - Array of Subject Alternative Names (SANs).
 * @property {string} mode - The mode of the certificate.
 * @property {boolean} dynamic - Indicates if the certificate is dynamic.
 * @property {CertificateStatus} status - The current status of the certificate.
 * @property {number} attemptsCount - Number of attempts made to renew the certificate.
 * @property {string} expiringDate - Expiry date of the certificate in ISO format.
 * @property {number} daysBeforeRenewal - Days before the certificate is due for renewal.
 * @property {string} serialNumber - Serial number of the certificate.
 * @property {string} sslCertificateFile - Path to the SSL certificate file.
 */

export default {
    name: "Certificates",

    components: {
        CertificateForm,
        StatusBox,
        BFormRadioGroup,
        BFormRadio,
        BButton,
        BAlert,
    },

    data() {
        return {
            /**
             * List of certificates fetched from the API.
             * @type {Certificate[]}
             */
            certificates: [],
            localStorePath: null,
            loading: true,
            opSuccess: null,
            opMessage: '',
            /**
             * Filter applied to the certificates list.
             * @type {'all'|CertificateStatus}
             * @default 'all'
             */
            selectedFilter: 'all',
        };
    },

    created() {
        this.loadCertificates();
    },

    computed: {
        fields() {
            return [
                { key: "id", label: "ID", sortable: true },
                { key: "hostname", label: "Hostname", sortable: true },
                { key: "subjectAltNames", label: "SANs", sortable: true },
                { key: "mode", label: "Mode", sortable: true },
                { key: "dynamic", label: "Dynamic", sortable: true, formatter: toBooleanSymbol },
                { key: "status", label: "Status", sortable: true },
                { key: "attemptsCount", label: "Attempts count", sortable: true },
                { key: "expiringDate", label: "Expiring Date", sortable: true },
                { key: "daysBeforeRenewal", label: "Days Before Renewal", sortable: true },
                { key: "serialNumber", label: "Serial Number", sortable: true },
                { key: "sslCertificateFile", label: "SSL Certificate File", sortable: true },
            ];
        },

        /**
         * Certificates filtered by the selected status.
         * @returns {Certificate[]}
         */
        filteredCertificates() {
            return this.selectedFilter === 'all'
                ? this.certificates
                : this.certificates.filter(c => c.status === this.selectedFilter);
        },

        availableCertificates() {
            return this.filterByStatus(this.certificates, 'available');
        },

        availableCount() {
            return this.availableCertificates.length;
        },

        expiredCertificates() {
            return this.filterByStatus(this.certificates, 'expired');
        },

        expiredCount() {
            return this.expiredCertificates.length;
        },

        unreachableCertificates() {
            return this.filterByStatus(this.certificates, 'domain unreachable');
        },

        unreachableCount() {
            return this.unreachableCertificates.length;
        },
    },

    methods: {
        loadCertificates() {
            this.loading = true;
            doGet("/api/certificates", data => {
                this.certificates = data.certificates || [];
                this.localStorePath = data.localStorePath;
                this.loading = false;
            });
        },

        showCertDetail(cert) {
            this.$router.push({ name: "Certificate", params: { id: String(cert.id) } });
        },

        showCertStatus(domain) {
            this.$router.push({ name: "Certificate", params: { id: domain } });
        },

        /**
         * Returns a CSS class based on the certificate's status.
         *
         * @param {Certificate} cert - The certificate to evaluate.
         * @returns {string} Corresponding CSS class.
         */
        statusClass(cert) {
            switch (cert.status) {
                case 'available':
                    return 'success';
                case 'domain unreachable':
                    return 'warning';
                case 'expired':
                    return 'error';
                default:
                    return "info";
            }
        },

        openConfirmModal() {
            const message = this.$createElement('div', {
                domProps: {
                    innerHTML: `All certificates will be dumped to path: <strong>${this.localStorePath}</strong>`
                        + '<br>Any existing dump will be overwritten. Proceed?'
                }
            });
            this.$bvModal.msgBoxConfirm([message], {
                title: 'Attention',
                okVariant: 'warning',
                okTitle: 'Yes',
                footerClass: 'p-2',
                hideHeaderClose: false,
                centered: true
            }).then(value => {
                if (!value) {
                    return;
                }
                doPost("/api/certificates/storeall", null, () => {
                        this.opSuccess = true;
                        this.opMessage = 'Certificates dump started';
                    },
                    error => {
                        this.opSuccess = false;
                        this.opMessage = 'Unable to dump certificates to path: ' + this.localStorePath + '. Cause: ' + error.message;
                    }
                );
            });
        },

        /**
         * Filters a list of certificates by their status.
         *
         * @param {Certificate[]} certificates - The list of certificates to filter.
         * @param {CertificateStatus} status - The status to filter by.
         * @returns {Certificate[]} The filtered list of certificates matching the specified status.
         */
        filterByStatus(certificates, status) {
            return certificates.filter(c => c.status === status);
        },
    }
};
</script>

<style scoped lang="scss">
.page-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
}

.header-actions {
    display: flex;
    align-items: center;
}

.header-actions > *:not(:last-child) {
    margin-right: 10px;
}

#status-alert {
    position: absolute;
    top: 2rem;
    left: 25%;
    right: 25%;
    z-index: 999;
}
</style>
