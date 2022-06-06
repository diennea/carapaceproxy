    <template>
    <div>
        <h2>Listeners</h2>
        <datatable-list :fields="fields" :items="listeners">
            <template v-slot:defaultCertificate="{ item }">
                <router-link
                    v-if="item.ssl"
                    :to="{ name: 'Certificate', params: { id: item.defaultCertificate }}"
                >{{item.defaultCertificate}}</router-link>
            </template>
        </datatable-list>
    </div>
</template>

<script>
import { doGet } from "./../mockserver";
import { toBooleanSymbol } from "../lib/formatter";
export default {
    name: "Listeners",
    data() {
        return {
            listeners: []
        };
    },
    created() {
        doGet("/api/listeners", data => {
            this.listeners = Object.values(data || {});
        });
    },
    computed: {
        fields() {
            return [
                { key: "host", label: "Host", sortable: true },
                { key: "port", label: "Port", sortable: true },
                {
                    key: "ssl",
                    label: "SSL",
                    sortable: true,
                    formatter: toBooleanSymbol
                },
                { key: "sslCiphers", label: "SSLCiphers", sortable: true },
                {
                    key: "sslProtocols",
                    label: "SSLProtocols",
                    formatter(protos) {
                        return protos.join(", ");
                    }
                },
                {
                    key: "totalRequests",
                    label: "Total Requests",
                    sortable: true
                },
                {
                    key: "defaultCertificate",
                    label: "Default Certificate",
                    sortable: true
                }
            ];
        }
    }
};
</script>


                   