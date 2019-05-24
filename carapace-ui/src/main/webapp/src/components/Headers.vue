<template>
    <div>
        <h2>Headers</h2>
        <table class="table table-striped">
            <thead>
                <tr>
                    <th scope="col">ID</th>
                    <th scope="col">Name</th>
                    <th scope="col">Value</th>
                    <th scope="col">Mode</th>                    
                </tr>
            </thead>
            <tbody>
                <tr v-for="header of headers" :key="header.id">
                    <td>
                        <div class="label">{{header.id}}</div>
                    </td>
                    <td>
                        <div class="label">{{header.name}}</div>
                    </td>
                    <td>
                        <div class="label">{{header.value}}</div>
                    </td>
                    <td>
                        <div class="label">{{header.mode}}</div>
                    </td>                   
                </tr>
            </tbody>
        </table>
    </div>
</template>

<script>
    import { doGet } from './../mockserver'
    export default {
        name: 'headers',
        data: function () {
            return {
                headers: []
            }
        },
        created: function () {
            var url = "/api/headers"
            var self = this
            doGet(url, data => {
                self.headers = []
                Object.keys(data).forEach(idx => {
                    self.headers.push(data[idx])
                })
            })
        }
    }
</script>

<style scoped>
</style>