<template>
    <div class="mt-4">
        <b-input-group id="filter">
            <b-form-input type="search" placeholder="Type to Search" v-model="filter"></b-form-input>
            <b-input-group-append>
                <b-button :disabled="!filter" @click="filter = ''">Clear</b-button>
            </b-input-group-append>
        </b-input-group>
        <b-form-select id="selector" v-model="perPage" :options="pageOptions" />
        <b-table
            id="datatable"
            hover
            striped
            responsive
            show-empty
            sticky-header
            :items="items"
            :fields="fields"
            :perPage="perPage"
            :currentPage="currentPage"
            :sort-by="sortBy"
            :sort-compare="sortCompare"
            :filter="filter"
            @filtered="onFiltered"
            @row-clicked="rowClicked"
            :class="['tall', { 'row-clickable': rowClicked }]"
        >
            <!-- This allows to forward b-table columns-value customization out of the component -->
            <template
                v-for="field of fields"
                v-slot:[`cell(${field.key})`]="data"
            >
                <slot :name="field.key" v-bind:item="data.item">{{data.value}}</slot>
            </template>
        </b-table>
        <b-pagination
            align="center"
            class="m-0"
            pills
            v-model="currentPage"
            :total-rows="totalRows"
            :per-page="perPage"
        ></b-pagination>
    </div>
</template>

<script>
import { compareTimestamp } from "../lib/formatter.js";
export default {
    props: {
        fields: Array,
        items: Array,
        sortBy: String,
        rowClicked: Function
    },
    data() {
        return {
            pageOptions: [10, 25, 50],
            perPage: 10,
            totalRows: this.items.length,
            currentPage: 1,
            sortCompare: (a, b, key) => {
                const value = this.fields.find(
                    el => el.key === key && el.isDate === true
                );
                if (value) {
                    return compareTimestamp(a[key], b[key]);
                }
                return a[key] < b[key] ? -1 : a[key] > b[key] ? 1 : 0;
            },
            filter: null
        };
    },
    watch: {
        items() {
            this.totalRows = this.items.length;
        }
    },
    methods: {
        onFiltered(filteredItems) {
            // Trigger pagination to update the number of buttons/pages due to filtering
            this.totalRows = filteredItems.length;
            this.currentPage = 1;
        }
    }
};
</script>
<style>
.row-clickable tr {
    cursor: pointer;
}

.tall {
    max-height: 72vh;
    margin: 0.25rem auto;
}

#filter {
    float: left;
    width: 50%;
}

#selector {
    float: right;
    width: 5rem;
}

#filter, #selector {
    margin-bottom: 0.25rem;
}
</style>