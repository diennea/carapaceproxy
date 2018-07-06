import Vue from 'vue'
import Router from 'vue-router'

import Backends from './components/Backends'
import CacheStatus from './components/CacheStatus'
Vue.component('backends-status', Backends)
Vue.component('cache-status', CacheStatus)

Vue.use(Router)

export default new Router({
  routes: [
    {
      path: '/',
      name: 'Root',
      component: Backends
    },{
      path: '/cache',
      name: 'Cache',
      component: CacheStatus
    }
  ]
})
