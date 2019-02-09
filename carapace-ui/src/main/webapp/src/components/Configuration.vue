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
    export default {
        name: 'Configuration',
        data: function () {
            return {
                configuration: "",
                axiosParams: {
                    headers: {
                        'Content-Type': 'text/plain'
                    }
                },
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
                axios.post('/api/config/apply', this.configuration, this.axiosParams).then(function (response) {
                    var data = response.data
                    self.opSuccess = data.ok
                    self.opMessage = data.ok ? "Configuration saved successfully." : "Error on configuration saving: " + data.error
                    console.log(response)
                }).catch(function (error) {
                    self.opSuccess = false
                    self.opMessage = "Error on configuration saving: " + error
                    console.log(error)
                })
            },
            reload: function () {
                var self = this
                axios.get('/api/config', this.axiosParams).then(function (response) {
                    self.configuration = response.data
                    self.opSuccess = true
                    self.opMessage = "Configuration reloaded successfully."
                    console.log(response)
                }).catch(function (error) {
                    self.opSuccess = false
                    self.opMessage = "Error on reloading current configuration:" + error
                    console.log(error)
                })
            }
        }
    }
</script>

<style>
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