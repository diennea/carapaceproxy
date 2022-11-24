<template>
    <div>
        <h2>Routes</h2>
        <status-box
            title="Reminder"
            message="With no route matching the request, NOT-FOUND action will be performed"
            status="warning"
        ></status-box>
        <datatable-list :fields="fields" :items="routes"></datatable-list>
    </div>
</template>

<script>
import { doGet } from "../serverapi";
import { toBooleanSymbol } from "../lib/formatter";
import StatusBox from "./StatusBox.vue";
export default {
    name: "Routes",
    components: {
        "status-box": StatusBox
    },
    data() {
        return {
            routes: []
        };
    },
    created() {
        doGet("/api/routes", data => {
            this.routes = [];
            Object.keys(data).forEach(idx => {
                var r = data[idx];
                r.priority = idx; // routes fetched ordered by priority.
                this.routes.push(r);
            });
        });
    },
    computed: {
        fields() {
            return [
                { key: "priority", label: "Priority", sortable: true },
                { key: "id", label: "ID", sortable: true },
                { key: "matcher", label: "Request Matcher", sortable: true },
                { key: "action", label: "Action", sortable: true },
                { key: "errorAction", label: "Error Action", sortable: true },
                { key: "maintenanceAction", label: "Maintenance Action", sortable: true },
                {
                    key: "enabled",
                    label: "Enabled",
                    sortable: true,
                    formatter: toBooleanSymbol
                }
            ];
        }
    }
};
</script>