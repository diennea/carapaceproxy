<template>
    <div>
        <h2>Configuration</h2>
        <div id="maintenance-switch" >
            <b-form-checkbox
                v-model="checked" 
                name="check-button" 
                size="lg" 
                @change="$bvModal.show('maintenance')" 
                switch>
                Maintenance Mode <b>(Enable: {{ checked }})</b>
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
            placeholder="Edit Dynamic Configuration"
            class="mb-2"
        ></b-form-textarea>
        <div>
            <b-button variant="outline-primary" @click="fetch">Fetch</b-button>
            <b-button variant="outline-primary" @click="validate">Validate</b-button>
            <b-button variant="primary" @click="save">Save</b-button>
        </div>
        <div>
            <b-modal 
            id="maintenance" 
            title="Maintenance"
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
import { doGet } from "./../mockserver";
import { doPost } from "./../mockserver";

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
                data => {
                    this.opSuccess = data.ok;
                    this.opMessage = data.ok
                        ? "Configuration saved successfully."
                        : "Error: unable to save the configuration. Reason: " + data.error;
                },
                error => {
                    this.opSuccess = false;
                    this.opMessage =
                        "Error: unable to save the configuration (" +
                        error +
                        ").";
                }
            );
        },
        fetch() {
            doGet(
                "/api/config",
                conf => {
                    this.configuration = conf;
                    this.opSuccess = true;
                    this.opMessage = "";
                },
                error => {
                    this.opSuccess = false;
                    this.opMessage =
                        "Error: unable to fetch current configuration (" +
                        error +
                        ").";
                }
            );
        },
        validate() {
            doPost(
                "/api/config/validate",
                this.configuration,
                data => {
                    this.opSuccess = data.ok;
                    this.opMessage = data.ok
                        ? "Configuration is ok."
                        : "Error: configuration contains some errors. Details: " + data.error;
                },
                error => {
                    this.opSuccess = false;
                    this.opMessage =
                        "Error: unable to validate the configuration (" +
                        error +
                        ").";
                }
            );
        },
       setupMaintenanceMode() {
           doPost("/api/config/maintenance?enable=" + this.checked,
               this.checked,
               data => {
                   this.checked = data.ok;
                   this.opSuccess = true;
                   this.opMessage = data.ok
                        ? "Maintenance mode has been enabled."
                        : "Maintenance mode has been disabled";
               },
               error => {
                   this.opSuccess = false;
                   this.opMessage = "Something went wrong (" + error + ")";
               }
           );
       },
       getMaintenanceStatus() {
            doGet("/api/config/maintenance", data => {
                this.checked = data.ok;
            });
       },
       reset() {
           this.getMaintenanceStatus();
           this.fetch();
       },
    }
};
</script>

<style scoped>
#config-editor {
    height: 72vh;
}

#status-alert {
    position: absolute;
    top: 2rem;
    left: 25%;
    right: 25%;
    z-index: 999;
}

#maintenance-switch {
    float: right;
}

button {
    margin-right: 0.5rem;
}
</style>