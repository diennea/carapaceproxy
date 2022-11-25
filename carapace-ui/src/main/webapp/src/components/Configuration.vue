<template>
    <div>
        <div class="page-header">
            <h2>Configuration</h2>
            <b-form-checkbox name="check-button"
                             v-model="checked"
                             size="lg"
                             switch
                             @change="onMaintenanceModeChange()">
                Maintenance Mode
            </b-form-checkbox>
        </div>
        <b-alert
            id="status-alert"
            fade
            dismissible
            :show="opMessage ? 10 : 0"
            @dismissed="opMessage = ''"
            :variant="opSuccess ? 'success' : 'danger'"
            >{{opMessage}}</b-alert>
        <b-form-textarea
            id="config-editor"
            rows="30"
            max-rows="30"
            no-resize
            v-model="configuration"
            :state="opSuccess"
            placeholder="Edit Dynamic Configuration">
        </b-form-textarea>
        <div>
            <b-button variant="outline-secondary" @click="fetch">Fetch</b-button>
            <b-button variant="outline-secondary" @click="validate">Validate</b-button>
            <b-button @click="save">Save</b-button>
        </div>
        <div>
            <b-modal
                id="maintenance"
                title="Maintenance"
                cancel-title="Close"
                no-close-on-backdrop
                @ok="setupMaintenanceMode"
                @hidden="reset"
                >
                <p class="my-4" v-if="checked">You are about to enable maintenance mode</p>
                <p class="my-4" v-else>You are about to disable maintenance mode</p>
            </b-modal>
        </div>
    </div>
</template>

<script>
    import { doGet } from "../serverapi";
    import { doPost } from "../serverapi";

    export default {
        name: "Configuration",
        data() {
            return {
                configuration: "",
                opSuccess: null,
                opMessage: "",
                checked: false
            };
        },
        created() {
            this.fetch();
            this.getMaintenanceStatus();
        },
        methods: {
            save() {
                doPost(
                        "/api/config/apply",
                        this.configuration,
                        () => {
                            this.opSuccess = true
                            this.opMessage = "Configuration saved successfully."
                        },
                        error => {
                            this.opSuccess = false
                            this.opMessage = "Error: unable to save the configuration (" + error.message +")."
                        }
                );
            },
            fetch() {
                doGet(
                        "/api/config",
                        conf => {
                            this.configuration = conf
                            this.opSuccess = true
                            this.opMessage = ""
                        },
                        error => {
                            this.opSuccess = false
                            this.opMessage = "Error: unable to fetch current configuration (" + error.message + ")."
                        }
                );
            },
            validate() {
                doPost(
                        "/api/config/validate",
                        this.configuration,
                        () => {
                            this.opSuccess = true
                            this.opMessage = "Configuration is ok."
                        },
                        error => {
                            this.opSuccess = false
                            this.opMessage = "Error: unable to validate the configuration (" + error.message + ")."
                        }
                );
            },
            onMaintenanceModeChange() {
                const newChecked = this.checked;
                this.checked = !newChecked;
                const message = this.$createElement('div', {
                    domProps: {
                        innerHTML: 'System gonna <strong>' + (newChecked ? 'enter' : 'exit') + '</strong> maintenance mode, proceed?'
                    }
                });
                this.$bvModal.msgBoxConfirm(message, {
                    title: 'Attention',
                    okVariant: newChecked ? 'warning' : "success",
                    okTitle: 'Yes',
                    footerClass: 'p-2',
                    hideHeaderClose: false,
                    centered: true
                }).then(value => {
                    if (value != true) {
                        return;
                    }
                    doPost("/api/config/maintenance?enable=" + newChecked,
                            newChecked,
                            () => window.location.reload(),
                            error => {
                                this.opSuccess = false
                                this.opMessage = "Something went wrong (" + error.message + ")"
                            }
                    );
                })
            },
            getMaintenanceStatus() {
                doGet("/api/config/maintenance", data => {
                    this.checked = data.ok;
                });
            },
            reset() {
                this.getMaintenanceStatus();
            }
        }
    };
</script>

<style lang="scss" scoped>
    @import "../variables.scss";

    .page-header {
        display: flex;
        align-items: center;
        justify-content: space-between;
    }

    #config-editor {
        height: 72vh;
        margin: 0.5rem auto;
    }

    #status-alert {
        position: absolute;
        top: 2rem;
        left: 25%;
        right: 25%;
        z-index: 999;
    }

    button {
        margin-right: 0.5rem;
    }
</style>