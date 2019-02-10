import Vue from 'vue'
import App from './App.vue'
import router from './router'
import './app.scss'
import BootstrapVue from 'bootstrap-vue'
import axios from 'axios'
import VueAxios from 'vue-axios'
 
Vue.use(BootstrapVue)
Vue.use(VueAxios, axios)

Vue.config.productionTip = false

new Vue({
    router:router,
  render: h => h(App),
}).$mount('#app')
