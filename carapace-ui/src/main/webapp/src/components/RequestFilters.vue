<template>
    <div>
        <h2>Request filters</h2>
        <datatable-list :fields="fields" :items="requestfilters"></datatable-list>
    </div>
</template>

<script>
import { doGet } from "../serverapi";
export default {
    name: "RequestFiltersStatus",
    data() {
        return {
            requestfilters: []
        };
    },
    created() {
        doGet("/api/requestfilters", data => {
            data.forEach(item => {
                var requestfilter = {};
                requestfilter.type = item.type;
                var result = [];
                Object.keys(item.values).forEach(key => {
                    result.push(key + ": " + item.values[key]);
                });
                requestfilter.description = result.join(", ");
                this.requestfilters.push(requestfilter);
            });
        });
    },
    computed: {
        fields() {
            return [
                { key: "type", label: "Request Type", sortable: true },
                { key: "description", label: "Description", sortable: false }
            ];
        }
    }
};
</script>