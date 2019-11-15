<template>
    <nav id="sidebar" :class="{'collapsed' : collapsed}">
        <!-- HEADER -->
        <div id="sidebar-header">
            <h3 v-if="collapsed">{{headerCollapsed}}</h3>
            <h3 v-else>{{header}}</h3>
        </div>
        <!-- ELEMENTS -->
        <ul id="sidebar-elements" class="list-unstyled">
            <router-link
                v-for="el in elements"
                tag="li"
                exact-active-class="active"
                :key="el.label"
                :to="el.href"
                :title="collapsed ? el.label : null"
            >
                <font-awesome-icon :icon="el.icoName" fixed-width></font-awesome-icon>
                <span v-if="!collapsed">{{el.label}}</span>
            </router-link>
        </ul>
        <!-- SIDEBAR-TOGGLE-BUTTON -->
        <div id="sidebar-toogle-button" @click="toggleSidebar()">
            <font-awesome-icon icon="angle-right" :rotation="collapsed ? 0 : 180"></font-awesome-icon>
            <font-awesome-icon icon="angle-right" :rotation="collapsed ? 0 : 180"></font-awesome-icon>
        </div>
    </nav>
</template>

<script>
export default {
    name: "Sidebar",
    props: {
        header: String,
        headerCollapsed: String,
        elements: Array
    },
    data() {
        return {
            collapsed: false
        };
    },
    created() {
        window.addEventListener("resize", this.handleResize);
        this.handleResize();
    },
    destroyed() {
        window.removeEventListener("resize", this.handleResize);
    },
    methods: {
        handleResize() {
            if (
                (this.collapsed && window.innerWidth > 768) ||
                (!this.collapsed && window.innerWidth < 768)
            ) {
                this.toggleSidebar();
            }
        },
        toggleSidebar() {
            this.collapsed = !this.collapsed;
        }
    }
};
</script>

<style lang="scss" scoped>
@import "../variables.scss";

#sidebar {
    min-width: 240px;
    max-width: 240px;
    min-height: 100vh;
    text-align: center;
    background: $secondary;
    color: $white;
    transition: all 0.5s ease;
    text-align: left;
    -webkit-box-shadow: 0px 0px 7px 2px $shadow;
    box-shadow: 0px 0px 7px 2px $shadow;
    position: relative;
}

#sidebar.collapsed {
    min-width: 65px;
    max-width: 65px;
    text-align: center;
    transition: all 0.5s ease;
}

#sidebar.collapsed #sidebar-header {
    padding-left: 0px;
    padding-right: 0px;
    background: $secondary;
}

#sidebar-toogle-button {
    position: absolute;
    bottom: 0;
    left: 0;
    right: 0;
    padding: 1em;
    text-align: center;
    color: $primary;
}

#sidebar-toogle-button svg {
    margin: auto 2px; /* default open */
}

#sidebar-toogle-button:hover svg {
    margin: 0; /* on-hover > close */
}

#sidebar.collapsed #sidebar-toogle-button svg {
    margin: 0; /* default closed */
}

#sidebar.collapsed #sidebar-toogle-button:hover svg {
    margin: auto 2px; /* on-hover > open */
}

#sidebar-header,
#sidebar li,
#sidebar-toogle-button {
    cursor: pointer;
}

#sidebar-header {
    min-height: 5em;
    max-height: 5em;
    margin-bottom: 1em;
}

#sidebar-header h3 {
    overflow: hidden;
}

a,
a:hover,
a:focus {
    color: inherit;
    text-decoration: none;
}

#sidebar #sidebar-header {
    padding: 1em;
    background: $secondary;
}

#sidebar ul li {
    font-size: 1.1rem;
    padding: 0.5em 0.75em;
    display: block;
    position: relative;
    border: 0.25em solid transparent;
}

#sidebar ul li span {
    padding: 0.5em 0;
    position: absolute;
    top: 0;
    bottom: 0;
    left: 3em;
}

#sidebar ul li:not(active):hover {
    background: $secondary-accent2;
}

#sidebar ul li.active:hover {
    background: $secondary-accent1;
}

.active {
    color: $primary;
    border-left-color: $primary !important;
}

#sidebar ul,
a[aria-expanded="true"] {
    color: $white;
    background: $secondary;
}

#sidebar-elements {
    overflow-x: hidden;
    max-height: 76vh;
}
</style>