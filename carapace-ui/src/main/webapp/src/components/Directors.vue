<template>
    <div>
        <h2>Directors</h2>
        <datatable-list :fields="fields" :items="directors"></datatable-list>
    </div>
</template>

<script>
import { doGet } from "../serverapi";
export default {
    name: "Directors",
    data() {
        return {
            directors: []
        };
    },
    created() {
        doGet("/api/directors", data => {
            this.directors = Object.values(data || {});
        });
    },
    computed: {
        fields() {
            return [
                { key: "id", label: "ID", sortable: true },
                {
                    key: "backends",
                    label: "Backends IDs",
                    sortable: true,
                    formatter: value => {
                        return value ? value.join(", ") : "";
                    }
                }
            ];
        }
    }
};
</script>

<style scoped>
</style>