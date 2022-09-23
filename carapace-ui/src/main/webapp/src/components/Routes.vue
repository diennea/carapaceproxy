<template>
    <div>
        <h2>Routes</h2>
        <div class="box-warning">
            With no route matching the request, NOT-FOUND action will be performed
        </div>
        <datatable-list :fields="fields" :items="routes"></datatable-list>
    </div>
</template>

<script>
import { doGet } from "./../mockserver";
import { toBooleanSymbol } from "../lib/formatter";
export default {
    name: "Routes",
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