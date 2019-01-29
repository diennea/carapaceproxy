<template>
    <div>
        <h2>Users</h2>
        <div class="row">
            <div v-if="users.length == 0">
                <p>No users found.</p>
            </div>
            <div class="col-3" v-else>
                <ul class="list-group">
                    <li class="list-group-item" 
                    v-for="item of users" 
                    :key="item">{{item}}</li>
                </ul>
            </div>
        </div>
    </div>
</template>

<script>
    import { doRequest } from './../mockserver'
    export default {
        name: 'UserRealmStatus',
        data: function () {
            return {
                users: []
            }
        },
        created: function () {
            var url = "/api/users/all";
            var d = this;
            doRequest(url, {}, function (response) {
                d.users = response;
            })
        }
    }
</script>