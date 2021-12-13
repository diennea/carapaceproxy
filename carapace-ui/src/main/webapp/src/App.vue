<template>
    <div id="app">
        <navbar logo="assets/logo-small.png" label="arapace" :nodeId="nodeId" />
        <div id="nav-space" class="d-flex">
            <sidebar
                :elements="[
                    {
                        label: 'Backends',
                        icoName: 'server',
                        href: '/'
                    },
                    {
                        label: 'Connection Pools',
                        icoName: 'database',
                        href: '/connectionpools'
                    },
                    {
                        label: 'Directors',
                        icoName: 'crosshairs',
                        href: '/directors'
                    },
                    {
                        label: 'Actions',
                        icoName: 'bolt',
                        href: '/actions'
                    },
                    {
                        label: 'Routes',
                        icoName: 'map-signs',
                        href: '/routes'
                    },
                    {
                        label: 'Listeners',
                        icoName: 'door-open',
                        href: '/listeners'
                    },
                    {
                        label: 'Headers',
                        icoName: 'heading',
                        href: '/headers'
                    },
                    {
                        label: 'Filters',
                        icoName: 'filter',
                        href: '/requestfilters',
                    },
                    {
                        label: 'Certificates',
                        icoName: 'file-signature',
                        href: '/certificates'
                    },
                    {
                        label: 'Cache',
                        icoName: 'archive',
                        href: '/cache'
                    },
                    {
                        label: 'Users',
                        icoName: 'users',
                        href: '/users'
                    },
                    {
                        label: 'Metrics',
                        icoName: 'chart-bar',
                        href: '/metrics'
                    },
                    {
                        label: 'Peers',
                        icoName: 'network-wired',
                        href: '/peers'
                    },
                    {
                        label: 'Configuration',
                        icoName: 'sliders-h',
                        href: '/configuration'
                    }
                ]"
            />
            <router-view class="main flex-grow-1 p-2" />
        </div>
    </div>
</template>

<script>
import { doGet } from "./mockserver";
import Navbar from "./components/Navbar.vue";
import Sidebar from "./components/Sidebar.vue";

export default {
    name: "app",
    components: {
        navbar: Navbar,
        sidebar: Sidebar
    },
    data() {
        return {
            nodeId: ""
        };
    },
    created() {
        doGet("/api/cluster/localpeer", data => {
            this.nodeId = data.id;
            document.title = this.nodeId;
        });
    }
};
</script>

<style lang="scss" scoped>
@import "./variables.scss";

#nav-space {
    position: absolute;
    top: $navbar-height;
    right: 0;
    bottom: 0;
    left: 0;
}

.main {
    overflow-x: hidden;
    position: relative;
}
</style>