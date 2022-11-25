<template>
    <b-modal
        :id="id"
        ref="modal"
        title="Create certificate"
        @show="resetModal"
        @hidden="resetModal"
        @ok="handleOk"
    >
        <status-box
            v-if="globalError"
            v-bind="globalError"
            status="error"
        ></status-box>
        <b-form ref="form" @submit.stop.prevent="handleSubmit">
            <b-form-group
                id="domain-group"
                ref="field-domain-group"
                label="Domain:"
                label-for="domain"
                invalid-feedback="Insert the domain name"
            >
                <b-form-input
                    id="domain"
                    ref="field-domain"
                    v-model="form.domain"
                    placeholder="m.domain.tld or *.domain.tld"
                    required
                ></b-form-input>
            </b-form-group>

            <b-form-group
                id="subjectaltnames-group"
                ref="field-subjectAltNames-group"
                label="Subject Alternative Names:"
                label-for="subjectaltnames"
                invalid-feedback="Subject Alternative Names must be unique and different to main domain"
            >
                <b-form-tags
                    input-id="subjectaltnames"
                    ref="field-subjectAltNames"
                    v-model="form.subjectAltNames"
                    separator=" ,"
                    :placeholder="form.subjectAltNames.length ? '' : 'm1.domain.tld, m2.domain.tld'"
                    remove-on-delete
                    tag-pills
                ></b-form-tags>
            </b-form-group>

            <b-form-group
                id="type-group"
                ref="field-type-group"
                label="Type:"
                label-for="type"
                invalid-feedback="Choose the certificate type"
            >
                <b-form-select
                    id="type"
                    ref="field-type"
                    v-model="form.type"
                    :options="types"
                    required
                ></b-form-select>
            </b-form-group>

            <b-form-group
                v-if="form.type === 'acme'"
                id="daysbeforerenewal-group"
                ref="field-daysBeforeRenewal-group"
                label="Days before renewal:"
                label-for="daysbeforerenewal"
                invalid-feedback="Insert a value greater than zero"
            >
                <b-form-input
                    id="daysbeforerenewal"
                    ref="field-daysBeforeRenewal"
                    type="number"
                    v-model="form.daysBeforeRenewal"
                    required
                ></b-form-input>
            </b-form-group>
        </b-form>
        <template #modal-footer="{ok, cancel}">
            <b-button variant="outline-secondary" @click="cancel()">Cancel</b-button>
            <b-button variant="success" @click="ok()">Create</b-button>
        </template>
    </b-modal>
</template>

<script>
    import {doPost} from "../../serverapi";
    import StatusBox from "../StatusBox.vue";
    export default {
        name: "CertificateForm",
        components: {
            "status-box": StatusBox
        },
        props: {
            id: String
        },
        data() {
            return {
                form: {
                    domain: '',
                    subjectAltNames: [],
                    type: 'acme',
                    daysBeforeRenewal: 30
                },
                globalError: null
            }
        },
        computed: {
            types() {
                return [
                    {value: 'acme', text: 'Acme'}
                ]
            }
        },
        methods: {
            resetModal() {
                this.form.domain = ''
                this.form.subjectAltNames = []
                this.form.type = 'acme'
                this.form.daysBeforeRenewal = 30
                this.clearFormErrors()
            },
            clearFormErrors() {
                this.globalError = null
                for (const ref in this.$refs) {
                    if (ref.startsWith('field-')) {
                        const field = this.$refs[ref]
                        if (field) {
                            field.state = null
                            field.invalidFeedback = null
                        }
                    }
                }
            },
            handleOk(bvModalEvent) {
                bvModalEvent.preventDefault()
                this.handleSubmit()
            },
            handleSubmit() {
                this.clearFormErrors();
                doPost(
                    "/api/certificates/",
                    this.form,
                    () => {
                        this.$nextTick(() => this.$bvModal.hide(this.id))
                        this.$emit('done')
                    },
                    error => {
                        if (error.field) {
                            this.globalError = {
                                title: "Fields error",
                                message: "Some fields are invalid, check them before creating the certificate"
                            }
                            this.setErrorForField(error.field, error.message)
                        } else {
                            this.globalError = {
                                title: "An error occurred",
                                message: error.message
                            }
                        }
                    }
                );
            },
            setErrorForField(fieldId, error) {
                const group = this.$refs['field-' + fieldId + '-group']
                group.state = false
                group.invalidFeedback = error
                this.$refs['field-' + fieldId].state = false
            }
        }
    }
</script>

<style scoped lang="scss">
.b-form-tags {
    .b-form-tag-content {
        padding-bottom: 0.2rem !important;
    }
}
</style>