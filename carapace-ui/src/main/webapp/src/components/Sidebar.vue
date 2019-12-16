<template>
    <nav id="sidebar" :class="{'collapsed' : collapsed}">
        <!-- SIDEBAR HEADER -->
        <div id="sidebar-header">
            <img v-if="collapsed" :src="logoCollapsed" />
            <img v-else :src="logo" />
        </div>
        <!-- SIDEBAR ELEMENTS -->
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
        <!-- SIDEBAR TOGGLE-BUTTON -->
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
        logo: String,
        logoCollapsed: String,
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
    background: $secondary;
    color: $white;
    transition: all 0.15s linear;
    text-align: left;
    -webkit-box-shadow: 0px 0px 7px 2px $shadow;
    box-shadow: 0px 0px 7px 2px $shadow;
    position: relative;

    #sidebar-header {
        min-height: 7.5vh;
        max-height: 10vh;
        padding: 0.5rem 1rem;
        background: $secondary;
    }

    #sidebar-toogle-button {
        position: absolute;
        bottom: 0;
        left: 0;
        right: 0;
        text-align: center;
        padding: 0.5rem;
        color: $primary;
        background: $secondary;

        svg {
            margin: auto 2px; /* default open */
        }

        &:hover svg {
            margin: 0; /* on-hover > close */
        }
    }

    /* sidebar collapsed */
    &.collapsed {
        min-width: 65px;
        max-width: 65px;
        transition: all 0.15s linear;

        #sidebar-header {
            padding: 0.5rem;
        }

        #sidebar-toogle-button {
            svg {
                margin: 0; /* default closed */
            }

            &:hover svg {
                margin: auto 2px; /* on-hover > open */
            }
        }
    }
}

#sidebar-elements {
    overflow-x: hidden;
    min-height: 73vh;
    max-height: 73vh;
    margin: 7vh auto;

    li {
        font-size: 1.1rem;
        padding: 0.5rem 0.75rem;
        position: relative;
        border: 0.25rem solid transparent;

        svg {
            margin: auto 0.25rem;
        }

        span {
            position: absolute;
            top: 0;
            bottom: 0;
            left: 3.5rem;
            display: inline-flex;
            align-items: center;
        }

        &:hover {
            background: $secondary-accent2;
        }

        &.active {
            color: $primary;
            border-left-color: $primary !important;
        }

        &.active:hover {
            background: $secondary-accent1;
        }
    }
}

#sidebar-header,
#sidebar-toogle-button,
#sidebar-elements li {
    cursor: pointer;
}

#sidebar-elements,
a[aria-expanded="true"] {
    color: $white;
    background: $secondary;
}

a,
a:hover,
a:focus {
    color: inherit;
    text-decoration: none;
}
</style>