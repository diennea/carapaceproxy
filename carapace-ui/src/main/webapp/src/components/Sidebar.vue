<template>
    <nav id="sidebar" :class="{'collapsed' : collapsed}">
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
        <div id="sidebar-version" :title="versions.join(', ')">
            <font-awesome-icon v-if="collapsed" icon="circle-info" fixed-width></font-awesome-icon>
            <div v-else v-for="line in versions" :key="line">{{ line }}</div>
        </div>
        <div id="sidebar-toogle-button" @click="toggleSidebar()">
            <font-awesome-icon icon="angle-right" :rotation="collapsed ? 0 : 180"></font-awesome-icon>
            <font-awesome-icon icon="angle-right" :rotation="collapsed ? 0 : 180"></font-awesome-icon>
        </div>
    </nav>
</template>

<script>
import { doGet } from "../serverapi";
import { UI_VERSION, UI_COMMIT, formatVersion } from "../version";

export default {
    name: "Sidebar",
    props: {
        elements: Array
    },
    data() {
        return {
            collapsed: false,
            server: null
        };
    },
    created() {
        window.addEventListener("resize", this.handleResize);
        this.handleResize();
        // a server older than /api/version must not pass for the same build as the UI
        doGet("/api/version", data => {
            this.server = data;
        }, () => {
            this.server = { version: "unknown" };
        });
    },
    computed: {
        // The server line appears only when web/ and lib/ come from different builds
        versions() {
            const ui = formatVersion(UI_VERSION, UI_COMMIT);
            const server = this.server ? formatVersion(this.server.version, this.server.commit) : ui;
            return server === ui ? ["Carapace " + ui] : ["UI " + ui, "Server " + server];
        }
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
    min-width: $sidebar-width;
    max-width: $sidebar-width;
    background: $sidebar-background;
    color: $white;
    text-align: left;
    -webkit-box-shadow: 2px 0px 7px -2px $shadow;
    box-shadow: 2px 0px 7px -2px $shadow;
    position: relative;

    #sidebar-toogle-button {
        position: absolute;
        bottom: 0;
        left: 0;
        right: 0;
        text-align: center;
        padding: 0.5rem;
        color: $primary;
        background: $sidebar-background;

        svg {
            margin: auto 2px; /* default open */
        }

        &:hover svg {
            margin: 0; /* on-hover > close */
        }
    }

    #sidebar-version {
        position: absolute;
        bottom: 2rem;
        left: 0;
        right: 0;
        padding: 0.5rem 0.75rem;
        font-size: 0.8rem;
        color: $sidebar-elements;
        opacity: 0.8;
        overflow: hidden;
        white-space: nowrap;
    }

    /* sidebar collapsed */
    &.collapsed {
        min-width: $sidebar-collapsed-width;
        max-width: $sidebar-collapsed-width;

        #sidebar-version {
            text-align: center;
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
    position: absolute;
    top: 0;
    right: 0;
    bottom: 5.5rem;
    left: 0;
    overflow-x: hidden;
    padding-top: 0.5rem;

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
            background: $primary-dark-accent2;
        }

        &.active {
            color: $primary;
            border-left-color: $primary !important;
        }

        &.active:hover {
            background: $primary-dark-accent1;
        }
    }
}

#sidebar-toogle-button,
#sidebar-elements li {
    cursor: pointer;
}

#sidebar-elements,
a[aria-expanded="true"] {
    color: $sidebar-elements;
    background: $sidebar-background;
}

a,
a:hover,
a:focus {
    color: inherit;
    text-decoration: none;
}
</style>