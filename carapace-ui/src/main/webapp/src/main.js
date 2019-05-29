import Vue from 'vue'
import App from './App.vue'
import router from './router'
import './app.scss'
import BootstrapVue from 'bootstrap-vue'
import { formatTimestamp } from './lib/formatter'

Vue.use(BootstrapVue)
Vue.config.productionTip = false

// Filters
Vue.filter('symbolFormat', value => {
    return value ? 'Yes' : 'No'
})
Vue.filter('dateFormat', value => {
    if (!value || value <= 0) {
        return ''
    }
    return formatTimestamp(value)
})

new Vue({
    router: router,
    render: h => h(App),
}).$mount('#app')