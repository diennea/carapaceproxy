<template>
    <div>
        <h2>Users</h2>
        <div v-if="users.length == 0">
            <p>No users found.</p>
        </div>
        <div v-else class="panel">
            <ul class="list-group">
                <li class="list-group-item" v-for="item of users" :key="item">{{item}}</li>
            </ul>
        </div>
    </div>
</template>

<script>
import { doGet } from "../serverapi";
export default {
    name: "UserRealmStatus",
    data() {
        return {
            users: []
        };
    },
    created() {
        doGet("/api/users/all", data => {
            this.users = data;
        });
    }
};
</script>

<style scoped>
.panel {
    max-width: 25rem;
}
</style>