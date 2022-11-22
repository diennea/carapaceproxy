<template>
    <div>
        <h2>Prometheus Metrics</h2>
        <div>
            <p>
                Prometheus Metrics at:
                <a :href="metricsPath">{{metricsPath}}</a>
            </p>
        </div>
    </div>
</template>

<script>
import { doGet } from "../serverapi";
export default {
    name: "Metrics",
    data() {
        return {
            metricsPath: ""
        };
    },
    created() {
        doGet("/api/metrics/url", data => {
            this.metricsPath = data;
        });
    }
};
</script>