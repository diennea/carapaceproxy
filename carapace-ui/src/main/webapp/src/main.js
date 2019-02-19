import Vue from 'vue'
import App from './App.vue'
import router from './router'
import './app.scss'
import BootstrapVue from 'bootstrap-vue'

Vue.use(BootstrapVue)

Vue.config.productionTip = false

// Filters
Vue.filter('symbolize', function (value) {
  return value ? 'Yes' : 'No'
})

new Vue({
    router:router,
  render: h => h(App),
}).$mount('#app')