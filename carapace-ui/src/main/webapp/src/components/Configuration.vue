<template>
    <div>
        <h2>Configuration</h2>
        <div>
            <textarea id="edit-config" rows="32" v-model="configuration"></textarea>
        </div>
        <div>
            <button @click="validate">Validate</button>
            <button @click="save">Save</button>
            <button @click="fetch">Fetch</button>
            <span :class="[opSuccess ? 'success' : 'error']">{{opMessage}}</span>
        </div>
    </div>
</template>

<script>
    import { doGet } from './../mockserver'
    import { doPost } from './../mockserver'
    export default {
        name: 'Configuration',
        data: function () {
            return {
                configuration: "",
                opSuccess: false,
                opMessage: ""
            }
        },
        created: function () {
            this.fetch()
        },
        methods: {
            save: function () {
                var self = this
                doPost('/api/config/apply', this.configuration, data => {
                    self.opSuccess = data.ok
                    self.opMessage = data.ok ? "Configuration saved successfully." : "Error: unable to save the configuration."
                }, error => {
                    self.opSuccess = false
                    self.opMessage = "Error: unable to save the configuration (" + error + ")."
                })
            },
            fetch: function () {
                var self = this
                doGet('/api/config', conf => {
                    self.configuration = conf
                    self.opSuccess = true
                    self.opMessage = ""
                }, error => {
                    self.opSuccess = false
                    self.opMessage = "Error: unable to fetch current configuration (" + error + ")."
                })
            },
            validate: function () {
                var self = this
                doPost('/api/config/validate', this.configuration, data => {
                    self.opSuccess = data.ok
                    self.opMessage = data.ok ? "Configuration is ok." : "Error: configuration contains some errors, check it out before save it."
                }, error => {
                    self.opSuccess = false
                    self.opMessage = "Error: unable to validate the configuration (" + error + ")."
                })
            }
        }
    }
</script>

<style scoped>
    #edit-config {
        width: 100%;
        overflow-y: scroll;
        border-radius: 5px;
    }

    button {
        margin-right: 10px !important;
    }

    .success {
        color: green;
    }

    .error {
        color:red;
    }
</style>