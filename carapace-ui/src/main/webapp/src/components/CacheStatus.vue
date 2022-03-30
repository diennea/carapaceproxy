<template>
    <div>
        <h2>Cache</h2>
        <button class="btn btn-dark float-right" v-on:click="flushCache">Flush cache</button>
        <form class="float-left w-75">
            <div class="form-group row">
                <label class="col-sm-4 col-form-label">Status:</label>
                <div class="col-sm-8">
                    <input
                        type="text"
                        readonly
                        class="form-control-plaintext"
                        v-model="status.result"
                    />
                </div>
            </div>
            <div class="form-group row">
                <label class="col-sm-4 col-form-label">Size:</label>
                <div class="col-sm-8">
                    <input
                        type="text"
                        readonly
                        class="form-control-plaintext"
                        v-model="status.cachesize"
                    />
                </div>
            </div>
            <div class="form-group row">
                <label class="col-sm-4 col-form-label">Misses:</label>
                <div class="col-sm-8">
                    <input
                        type="text"
                        readonly
                        class="form-control-plaintext"
                        v-model="status.misses"
                    />
                </div>
            </div>
            <div class="form-group row">
                <label class="col-sm-4 col-form-label">Direct memory used:</label>
                <div class="col-sm-8">
                    <input
                        type="text"
                        readonly
                        class="form-control-plaintext"
                        v-model="status.directMemoryUsed"
                    />
                </div>
            </div>
            <div class="form-group row">
                <label class="col-sm-4 col-form-label">Heap memory used:</label>
                <div class="col-sm-8">
                    <input
                        type="text"
                        readonly
                        class="form-control-plaintext"
                        v-model="status.heapMemoryUsed"
                    />
                </div>
            </div>
        </form>
        <datatable-list :fields="fields" :items="cacheitems"></datatable-list>
    </div>
</template>

<script>
import { doGet } from "./../mockserver";
import { formatTimestamp } from "./../lib/formatter";
export default {
    name: "CacheStatus",
    data() {
        return {
            status: {},
            cacheitems: []
        };
    },
    created() {
        this.loadData();
    },
    computed: {
        fields() {
            return [
                { key: "key", label: "Key", sortable: true },
                { key: "method", label: "Method", sortable: true },
                { key: "hits", label: "Hits", sortable: true },
                { key: "heapSize", label: "Heap Memory Size", sortable: true },
                {
                    key: "directSize",
                    label: "Direct Memory Size",
                    sortable: true
                },
                { key: "totalSize", label: "Entry Size", sortable: true },
                { key: "creationTs", label: "Creation", sortable: true },
                { key: "expireTs", label: "Expire", sortable: true }
            ];
        }
    },
    methods: {
        loadData() {
            doGet("/api/cache/info", data => {
                this.status = data;
            });
            doGet("/api/cache/inspect", data => {
                data.forEach(it => {
                    it.key = it.scheme + '://' + it.host + it.uri;
                    it.creationTs = formatTimestamp(it.creationTs);
                    it.expireTs = formatTimestamp(it.expireTs);
                    this.cacheitems.push(it);
                });
            });
        },
        getKey(item) {
            return item.host + item.uri;
        },
        flushCache() {
            // eslint-disable-next-line
            doGet("/api/cache/flush", data => {
                this.cacheitems = [];
                this.loadData();
            });
        }
    }
};
</script>