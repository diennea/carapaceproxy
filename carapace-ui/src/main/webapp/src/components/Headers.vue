<template>
    <div>
        <h2>Headers</h2>
        <datatable-list :fields="fields" :items="headers"></datatable-list>
    </div>
</template>

<script>
import { doGet } from "../serverapi";
export default {
    name: "headers",
    data() {
        return {
            headers: []
        };
    },
    created() {
        doGet("/api/headers", data => {
            this.headers = Object.values(data || {});
        });
    },
    computed: {
        fields() {
            return [
                { key: "id", label: "ID", sortable: true },
                { key: "name", label: "Name", sortable: true },
                { key: "value", label: "Value", sortable: true },
                { key: "mode", label: "Mode", sortable: true }
            ];
        }
    }
};
</script>

<style scoped>
</style>