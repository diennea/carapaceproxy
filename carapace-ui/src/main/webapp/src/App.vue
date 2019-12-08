<template>
    <div id="app" class="d-flex">
        <sidebar
            logo="assets/logo-full-white.png"
            logoCollapsed="assets/logo-small.png"
            :elements="[
                {
                    label: 'Backends',
                    icoName: 'server',
                    href: '/',
                },
                {
                    label: 'Directors',
                    icoName: 'crosshairs',
                    href: '/directors',
                },
                {
                    label: 'Actions',
                    icoName: 'bolt',
                    href: '/actions',
                },
                {
                    label: 'Routes',
                    icoName: 'map-signs',
                    href: '/routes',
                },
                {
                    label: 'Listeners',
                    icoName: 'door-open',
                    href: '/listeners',
                },
                {
                    label: 'Headers',
                    icoName: 'heading',
                    href: '/headers',
                },
                {
                    label: 'Filters',
                    icoName: 'filter',
                    href: '/requestfilters',
                },
                {
                    label: 'Certificates',
                    icoName: 'file-signature',
                    href: '/certificates',
                },
                    {
                    label: 'Cache',
                    icoName: 'archive',
                    href: '/cache',
                },
                {
                    label: 'Users',
                    icoName: 'users',
                    href: '/users',
                },
                {
                    label: 'Metrics',
                    icoName: 'chart-bar',
                    href: '/metrics',
                },
                {
                    label: 'Peers',
                    icoName: 'network-wired',
                    href: '/peers',
                },
                {
                    label: 'Configuration',
                    icoName: 'sliders-h',
                    href: '/configuration',
                }
            ]"
        />
        <router-view class="main flex-grow-1 p-2" />
        <span id="peerId">{{peerId}}</span>
    </div>
</template>

<script>
import { doGet } from "./mockserver";
import Sidebar from "./components/Sidebar.vue";

export default {
    name: "app",
    components: {
        sidebar: Sidebar
    },
    data() {
        return {
            peerId: ""
        };
    },
    created() {
        doGet("/api/cluster/localpeer", data => {
            this.peerId = data.id;
            document.title = this.peerId;
        });
    }
};
</script>

<style>
#app {
    font-family: "Avenir", Helvetica, Arial, sans-serif;
    -webkit-font-smoothing: antialiased;
    -moz-osx-font-smoothing: grayscale;
}

#peerId {
    position: absolute;
    right: 0.5rem;
    bottom: 0;
}

.main {
    max-height: 100vh;
    overflow-x: hidden;
    position: relative;
}
</style>