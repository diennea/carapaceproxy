import Vue from 'vue'
import App from './App.vue'
import router from './router'
import 'bootstrap/dist/css/bootstrap.css'
import './app.css'

Vue.config.productionTip = false

new Vue({
    router:router,
  render: h => h(App),
}).$mount('#app')
