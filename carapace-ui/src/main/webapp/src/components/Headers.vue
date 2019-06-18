<template>
    <div>
        <h2>Headers</h2>
        <datatable-list :fields="fields" :items="headers"></datatable-list>
    </div>
</template>

<script>
import { doGet } from "./../mockserver";
export default {
    name: "headers",
    data() {
        return {
            headers: []
        };
    },
    created() {
        var url = "/api/headers";
        var self = this;
        doGet(url, data => {
            self.headers = Object.values(data || {});
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