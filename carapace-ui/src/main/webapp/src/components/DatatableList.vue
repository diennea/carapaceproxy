<template>
    <div>
        <b-table
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
            @row-clicked="rowClicked"
            :class="['tall', { 'row-clickable': rowClicked }]"
        >
            <!-- This allows to forward b-table columns-value customization out of the component -->
            <template v-for="field of fields" v-slot:[`cell(${field.key})`]="data">
                <slot :name="field.key" v-bind:item="data.item">{{data.value}}</slot>
            </template>
        </b-table>
        <b-pagination id="pager" :total-rows="totalRows" :per-page="perPage" v-model="currentPage"></b-pagination>
        <b-form-select id="selector" :options="pageOptions" v-model="perPage" />
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
            pageOptions: [10, 25, 50, 250],
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
            }
        };
    },
    watch: {
        items() {
            this.totalRows = this.items.length;
        }
    }
};
</script>
<style>
.row-clickable tr {
    cursor: pointer;
}

.tall {
    max-height: 80vh;
}

#pager {
    float: left;
    margin-bottom: 0;
}

#selector {
    float: right;
    width: 5rem;
}
</style>