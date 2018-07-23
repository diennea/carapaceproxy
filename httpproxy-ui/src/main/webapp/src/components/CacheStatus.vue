<template>
    <div>
        <div class="row">
            <h2 class="col-2">Cache</h2>
            <button class="btn btn-dark col-2 offset-8" v-on:click="flushCache">Flush cache</button>
        </div>
        <div class="row mt-3">
        
        <table class="table table-striped">
            <thead>
                <tr>
                    <th scope="col">Status</th>
                    <th scope="col">Size</th>
                    <th scope="col">Misses</th>
                    <th scope="col">Direct memory used</th>
                    <th scope="col">Heap memory used</th>

                </tr>
            </thead>
            <tbody>
                    <td>{{status.result}}</td>
                    <td>{{status.cachesize}}</td>
                    <td>{{status.misses}}</td>
                    <td>{{status.directMemoryUsed}}</td>
                    <td>{{status.heapMemoryUsed}}</td>

            </tbody>
        </table>
        </div>
    </div>
</template>

<script>
    import { doRequest } from './../mockserver'
    export default {
        name: 'CacheStatus',
        data: function () {
            return {
                status: {}
            }
        },
        created: function () {
            var url = "/api/cache/info";
            var d = this;
            doRequest(url, {}, function (json) {
                d.status = json;
            })

        },
        methods: {
            flushCache: function () {
                var url = "/api/cache/flush";
                doRequest(url, {}, function () {});
            }
        }
    }
</script>