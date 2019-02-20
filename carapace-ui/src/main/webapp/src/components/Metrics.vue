<template>
    <div>
        <h2>Prometheus Metrics</h2>
        <div>
            <p>Prometheus Metrics at path:  <a :href="metricsPath">{{metricsPath}}</a></p>
        </div>
    </div>
</template>

<script>
    import { doGet } from './../mockserver'
    export default {
        name: 'Metrics',
        data: function () {
            return {
                metricsPath: ""
            }
        },
        created: function () {
            var url = "/api/metrics/path";
            var self = this;
            doGet(url, data => {
                console.log(data)
                self.metricsPath = data
            })
        }
    }
</script>