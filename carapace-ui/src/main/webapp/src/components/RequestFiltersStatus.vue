<template>
    <div>
        <h2>Request filters</h2>
        <datatable-list 
          :fields="getItemsHeaders()"
          :items="requestfilters"
          >
      </datatable-list>
    </div>
</template>

<script>
    import { doRequest } from './../mockserver'
    export default {
        name: 'RequestFiltersStatus',
        data: function () {
            return {
                requestfilters: []
            }
        },
        created: function () {
            var url = "/api/requestfilters";
            var d = this;
            doRequest(url, {}, function (response) {
                response.forEach(function (item)  {
                    var requestfilter = {};
                    requestfilter.type = item.type;
                    var result = [];
                    Object.keys(item.values).forEach(function(key) {
                        result.push(key + ": " + item.values[key]);
                    });
                    requestfilter.description = result.join(', ');
                    d.requestfilters.push(requestfilter);
                });
            })
        },
        methods: {
            getItemsHeaders: function () {
                return [
                    {key: 'type', label: 'Request type', sortable: true},
                    {key: 'description', label: 'Description', sortable: false}
                ];
            }
        }
    }
</script>