<template>
    <div>
        <h2>Routes</h2>       
        <table class="table table-striped">
            <thead>
                <tr>                    
                    <th scope="col">Priority</th>
                    <th scope="col">ID</th>
                    <th scope="col">Request matcher</th>
                    <th scope="col">Action</th>
                    <th scope="col">Enabled</th>
                </tr>
            </thead>
            <tbody>
                <tr v-for="(route, index) of routes" :key="route.id">
                    <td>
                        <div class="label">{{index + 1}}</div>
                    </td>
                    <td>
                        <div class="label">{{route.id}}</div>
                    </td>
                    <td>
                        <div class="label">{{route.matcher}}</div>
                    </td>
                    <td>
                        <div class="label">{{route.action}}</div>
                    </td>
                    <td>
                        <div class="label">{{route.enabled | symbolFormat}}</div>
                    </td>
                </tr>
            </tbody>
        </table>
        <div class="box-warning">With no route matching the NOT-FOUND action will be performed.</div>
    </div>
</template>

<script>
    import { doGet } from './../mockserver'
    export default {
        name: 'Routes',
        data: function () {
            return {
                routes: []
            }
        },
        created: function () {
            var url = "/api/routes"
            var self = this
            doGet(url, data => {
                self.routes = []
                Object.keys(data).forEach(idx => {
                    self.routes.push(data[idx])
                })
            })
        }
    }
</script>

<style scoped>
</style>