<template>
    <nav id="sidebar" :class="{'collapsed' : collapsed}">
        <!-- HEADER -->
        <div id="sidebar-header">
            <h2 v-if="collapsed">{{headerCollapsed}}</h2>
            <h2 v-else>{{header}}</h2>
        </div>
        <!-- ELEMENTS -->
        <ul id="sidebar-elements" class="list-unstyled">
            <router-link
                v-for="el in elements"
                tag="li"
                exact-active-class="active"
                :key="el.label"
                :id="el.label"
                :to="el.href"
            >
                <font-awesome-icon :icon="el.icoName" fixed-width></font-awesome-icon>
                <b-tooltip
                    v-if="collapsed"
                    :target="el.label"
                    :title="el.label"
                    placement="right"
                    boundary="viewport"
                ></b-tooltip>
                <span v-else>{{el.label}}</span>
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
    min-height: 15vh;
    max-height: 15vh;
}

#sidebar-header h2 {
    overflow: hidden;
}

a,
a:hover,
a:focus {
    color: inherit;
    text-decoration: none;
}

#sidebar-header,
#sidebar-toogle-button {
    padding: 0.5rem 1rem;
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
    min-height: 66vh;
    max-height: 66vh;
    margin: 2vh auto;
}
</style>