<template>
    <div>
        <h2>Configuration</h2>
        <b-alert
            id="status-alert"
            fade
            dismissible
            :show="opMessage ? 5 : 0"
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
            opMessage: ""
        };
    },
    created() {
        this.fetch();
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
                        : "Error: unable to save the configuration.";
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
                        : "Error: configuration contains some errors, check it out before save it.";
                },
                error => {
                    this.opSuccess = false;
                    this.opMessage =
                        "Error: unable to validate the configuration (" +
                        error +
                        ").";
                }
            );
        }
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

button {
    margin-right: 0.5rem;
}
</style>