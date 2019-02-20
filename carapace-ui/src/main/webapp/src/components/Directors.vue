<template>
    <div>
        <h2>Directors</h2>
        <table class="table table-striped">
            <thead>
                <tr>
                    <th scope="col">Director ID</th>
                    <th scope="col">Backends IDs</th>
                </tr>
            </thead>
            <tbody>
                <tr v-for="director of directors" :key="director.id">
                    <td>
                        <div class="label">{{director.id}}</div>
                    </td>
                    <td>
                        <div class="label">{{director.backends.join()}}</div>
                    </td>
                </tr>
            </tbody>
        </table>
    </div>
</template>

<script>
    import { doGet } from './../mockserver'
    export default {
        name: 'Directors',
        data: function () {
            return {
                directors: []
            }
        },
        created: function () {
            var url = "/api/directors"
            var self = this
            doGet(url, data => {
                self.directors = []
                Object.keys(data).forEach(idx => {
                    self.directors.push(data[idx])
                })
            })
        }
    }
</script>

<style scoped>
</style>