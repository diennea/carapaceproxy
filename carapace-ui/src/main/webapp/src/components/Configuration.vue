<template>
    <div>
        <h2>Configuration</h2>
        <div>
            <textarea id="edit-config" rows="32" v-model="configuration"></textarea>
        </div>
        <div>
            <button @click="save">Save</button>
            <button @click="reload">Reload</button>
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
            this.reload()
        },
        methods: {
            save: function () {
                var self = this               
                doPost('/api/config/apply', this.configuration, data => {                    
                    self.opSuccess = data.ok
                    self.opMessage = data.ok ? "Configuration saved successfully." : "Error on configuration saving: " + data.error
                }, error => {
                    self.opSuccess = false
                    self.opMessage = "Error on configuration saving: " + error                    
                })
            },
            reload: function () {
                var self = this
                doGet('/api/config', conf => {
                    self.configuration = conf
                    self.opSuccess = true
                    self.opMessage = "Configuration reloaded successfully."                    
                }, error => {
                    self.opSuccess = false
                    self.opMessage = "Error on reloading current configuration:" + error                    
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