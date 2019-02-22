<template>
    <div>
        <h2>Routes</h2>
        <table class="table table-striped">
            <thead>
                <tr>
                    <th scope="col">Route ID</th>
                    <th scope="col">Action</th>
                    <th scope="col">Enabled</th>
                    <th scope="col">Request matcher</th>
                </tr>
            </thead>
            <tbody>
                <tr v-for="route of routes" :key="route.id">
                    <td>
                        <div class="label">{{route.id}}</div>
                    </td>
                    <td>
                        <div class="label">{{route.action}}</div>
                    </td>
                    <td>
                        <div class="label">{{route.enabled | symbolFormat}}</div>
                    </td>
                    <td>
                        <div class="label">{{route.matcher}}</div>
                    </td>
                </tr>
            </tbody>
        </table>
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