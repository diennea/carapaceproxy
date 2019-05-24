<template>
    <div>
        <h2>Actions</h2>
        <table class="table table-striped">
            <thead>
                <tr>
                    <th scope="col">ID</th>
                    <th scope="col">Type</th>
                    <th scope="col">Description</th>
                    <th scope="col">Headers</th>
                    <th scope="col">Error code</th>
                </tr>
            </thead>
            <tbody>
                <tr v-for="action of actions" :key="action.id">
                    <td>
                        <div class="label">{{action.id}}</div>
                    </td>
                    <td>
                        <div class="label">{{action.type}}</div>
                    </td>
                    <td>
                        <div class="label">{{action.description}}</div>
                    </td>
                    <td>
                        <div class="label">{{action.headers}}</div>
                    </td>
                    <td>
                        <div class="label">{{action.errorcode}}</div>
                    </td>
                </tr>
            </tbody>
        </table>
    </div>
</template>

<script>
    import { doGet } from './../mockserver'
    export default {
        name: 'Actions',
        data: function () {
            return {
                actions: []
            }
        },
        created: function () {
            var url = "/api/actions"
            var self = this
            doGet(url, data => {
                self.actions = []
                Object.keys(data).forEach(idx => {
                    self.actions.push(data[idx])
                })
            })
        }
    }
</script>

<style scoped>
</style>