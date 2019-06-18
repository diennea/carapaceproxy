<template>
    <div>
        <h2>Actions</h2>
        <datatable-list :fields="fields" :items="actions"></datatable-list>
    </div>
</template>

<script>
import { doGet } from "./../mockserver";
export default {
    name: "Actions",
    data() {
        return {
            actions: []
        };
    },
    created() {
        var url = "/api/actions";
        var self = this;
        doGet(url, data => {
            self.actions = Object.values(data || {});
        });
    },
    computed: {
        fields() {
            return [
                { key: "id", label: "ID", sortable: true },
                { key: "type", label: "Type", sortable: true },
                { key: "description", label: "Description", sortable: true },
                { key: "headers", label: "Headers", sortable: true },
                { key: "errorcode", label: "Error Code", sortable: true }
            ];
        }
    }
};
</script>

<style scoped>
</style>