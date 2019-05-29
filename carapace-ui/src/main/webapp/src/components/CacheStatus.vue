<template>
    <div>
        <div class="row">
            <h2 class="col-2">Cache</h2>
            <button class="btn btn-dark col-2 offset-8" v-on:click="flushCache">Flush cache</button>
        </div>
        <div class="form-group row">
            <label  class="col-sm-2 col-form-label">Status</label>
            <div class="col-sm-10">
                <input type="text" readonly class="form-control-plaintext" v-model="status.result">
            </div>
        </div>
        <div class="form-group row">
            <label class="col-sm-2 col-form-label">Size</label>
            <div class="col-sm-10">
                <input type="text" readonly class="form-control-plaintext" v-model="status.cachesize">
            </div>
        </div>
        <div class="form-group row">
            <label class="col-sm-2 col-form-label">Misses</label>
            <div class="col-sm-10">
                <input type="text" readonly class="form-control-plaintext" v-model="status.misses">
            </div>
        </div>
        <div class="form-group row">
            <label class="col-sm-2 col-form-label">Direct memory used</label>
            <div class="col-sm-10">
                <input type="text" readonly class="form-control-plaintext" v-model="status.directMemoryUsed">
            </div>
        </div>
        <div class="form-group row">
            <label class="col-sm-2 col-form-label">Heap memory used</label>
            <div class="col-sm-10">
                <input type="text" readonly class="form-control-plaintext" v-model="status.heapMemoryUsed">
            </div>
        </div>


        <datatable-list 
            :fields="headers"
            :items="cacheitems"
            >
        </datatable-list>


    </div>
</template>

<script>
    import { doRequest } from './../mockserver'
    import { formatTimestamp } from './../lib/formatter'
    export default {
        name: 'CacheStatus',
        data: function () {
            return {
                status: {},
                cacheitems: []
            }
        },

        created: function () {
            var url = "/api/cache/info";
            var d = this;
            doRequest(url, {}, function (json) {
                d.status = json;
            })
            doRequest("/api/cache/inspect", {}, function (json) {
                json.forEach(function (it) {
                    it.key = it.host + it.uri
                    it.creationTs = formatTimestamp(it.creationTs)
                    it.expireTs = formatTimestamp(it.expireTs)
                    d.cacheitems.push(it);
                });
            })

        },
        computed: {
            headers: function () {
                return [
                    {key: 'key', label: 'Key', sortable: true},
                    {key: 'method', label: 'Method', sortable: true},
                    {key: 'hits', label: 'Hits', sortable: true},
                    {key: 'heapSize', label: 'Heap memory size', sortable: true},
                    {key: 'directSize', label: 'Direct memory size', sortable: true},
                    {key: 'totalSize', label: 'Entry size', sortable: true},
                    {key: 'creationTs', label: 'Creation', sortable: true},
                    {key: 'expireTs', label: 'Expire', sortable: true}
                ];
            }
        },
        methods: {
            getKey: function (item) {
                return item.host + item.uri;
            },
            flushCache: function () {
                var url = "/api/cache/flush";
                doRequest(url, {}, function () {});
            }
        }
    }
</script>