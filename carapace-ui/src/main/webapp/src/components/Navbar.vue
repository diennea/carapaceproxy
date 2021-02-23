<template>
    <nav class="navbar">
        <a class="navbar-brand" href="#">
            <img id="logo" :src="logo" height="45px" width="45px" />
            <span id="label">{{label}}</span>
        </a>
        <router-link to="/nodes">
            <b>Node:</b> {{nodeId}}
        </router-link>
    </nav>
</template>

<script>
export default {
    name: "Navbar",
    props: {
        logo: String,
        label: String,
        nodeId: String
    },
    data() {
        return {};
    },
    methods: {}
};
</script>

<style lang="scss" scoped>
@import "../variables.scss";

.navbar {
    min-height: $navbar-height;
    max-height: $navbar-height;
    padding: 0.5rem;
    background-color: $navbar-background;
    box-shadow: 2px 0px 7px -2px $shadow;
    -webkit-box-shadow: 2px 0px 7px -2px $shadow;

    * {
        color: $navbar-elements;
    }

    a,
    a:hover,
    a:focus {
        text-decoration: none;
    }

    .navbar-brand {
        padding: 0 0.1rem;
    }

    #logo {
        border-radius: 7px;
        padding-left: 3px;
        background-color: $navbar-logo;
    }

    #label {
        padding: 0.5rem;
        letter-spacing: 0.5rem;
    }
}
</style>