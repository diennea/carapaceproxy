import Vue from 'vue'
import Router from 'vue-router'

import Backends from './components/Backends'
import CacheStatus from './components/CacheStatus'
import ListenersStatus from './components/ListenersStatus'
import UserRealmStatus from './components/UserRealmStatus'
import RequestFiltersStatus from './components/RequestFiltersStatus'
import CertificatesStatus from './components/CertificatesStatus'
import Certificate from './components/Certificate'
import DatatableList from './components/DatatableList'
import Configuration from './components/Configuration'

Vue.component('backends-status', Backends)
Vue.component('cache-status', CacheStatus)
Vue.component('listeners-status', ListenersStatus)
Vue.component('userrealm-status', UserRealmStatus)
Vue.component('requestfilters-status', RequestFiltersStatus)
Vue.component('certificates-status', CertificatesStatus)
Vue.component('datatable-list', DatatableList)
Vue.component('configuration', Configuration)

Vue.use(Router)

export default new Router({
  routes: [
    {
      path: '/',
      name: 'Root',
      component: Backends
    },
    {
      path: '/cache',
      name: 'Cache',
      component: CacheStatus
    },
    {
      path: '/listeners',
      name: 'Listeners',
      component: ListenersStatus
    },
    {
      path: '/requestfilters',
      name: 'Request filters',
      component: RequestFiltersStatus
    },
    {
      path: '/users',
      name: 'Users',
      component: UserRealmStatus
    },
    {
      path: '/certificates',
      name: 'Certificates',
      component: CertificatesStatus
    },
    {
      path: '/certificates/:id',
      name: 'Certificate',
      component: Certificate
    },
    {
      path: '/configuration',
      name: 'Configuration',
      component: Configuration
    }
  ]
})
