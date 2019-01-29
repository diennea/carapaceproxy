<template>
  <div>
    <b-table hover responsive
             show-empty
             :items="items" 
             :fields="fields"
             :perPage="perPage"
             :currentPage="currentPage" 
             :sort-by="sortBy"
             :sort-compare="sortCompare"
             >
    </b-table>
    <div class="form-row">
      <div class='form-group col'>
        <b-pagination :total-rows="totalRows" :per-page="perPage" v-model="currentPage" ></b-pagination>
      </div>
      <div class='form-group col-1'>
        <b-form-select :options="pageOptions" 
            v-model="perPage" />
      </div>
    </div>
  </div>
</template>

<script>
  import { compareTimestamp } from '../lib/formatter.js';
  export default {

      props: {fields: Array, items: Array, sortBy: String},
      data: function () {
          return {
              pageOptions: [10, 25, 50, 250],
              perPage: 10,
              totalRows: this.items.length,
              currentPage: 1,
              sortCompare: (a, b, key) => {
                  const value = this.fields.find((el) => el.key === key && el.isDate === true);
                  if (value) {
                      return compareTimestamp(a[key], b[key])
                  }
                  return a[key] < b[key] ? -1 : (a[key] > b[key] ? 1 : 0)

              }
          }
      },
      watch: {
          items: function () {
              this.totalRows = this.items.length
          }
      }
  }
</script>