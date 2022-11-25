<template>
    <div class="status-box" :class="'status-box_' + status">
        <strong>{{ title }}</strong>
        <div>
            <slot>{{message}}</slot>
        </div>
    </div>
</template>

<script>
    export default {
        name: "StatusBox",
        props: {
            title: String,
            message: String,
            status: String
        }
    }
</script>