import Vue from 'vue';
import Router from 'vue-router';

import Backends from './components/Backends';
import ConnectionPools from './components/ConnectionPools';
import Routes from './components/Routes';
import Actions from './components/Actions';
import Directors from './components/Directors';
import CacheStatus from './components/CacheStatus';
import ListenersStatus from './components/ListenersStatus';
import UserRealmStatus from './components/UserRealmStatus';
import RequestFiltersStatus from './components/RequestFiltersStatus';
import CertificatesStatus from './components/CertificatesStatus';
import Certificate from './components/Certificate';
import DatatableList from './components/DatatableList';
import Configuration from './components/Configuration';
import Metrics from './components/Metrics';
import Peers from './components/Peers';
import Headers from './components/Headers';

Vue.component('backends-status', Backends);
Vue.component('connection-pools', ConnectionPools);
Vue.component('routes', Routes);
Vue.component('actions', Actions);
Vue.component('directors', Directors);
Vue.component('cache-status', CacheStatus);
Vue.component('listeners-status', ListenersStatus);
Vue.component('userrealm-status', UserRealmStatus);
Vue.component('requestfilters-status', RequestFiltersStatus);
Vue.component('certificates-status', CertificatesStatus);
Vue.component('datatable-list', DatatableList);
Vue.component('configuration', Configuration);
Vue.component('metrics', Metrics);
Vue.component('peers', Peers);
Vue.component('headers', Headers);

Vue.use(Router);

export default new Router({
    routes: [
        {
            path: '/',
            name: 'Root',
            component: Backends
        },
        {
            path: '/connectionpools',
            name: 'Connection Pools',
            component: ConnectionPools
        },
        {
            path: '/routes',
            name: 'Routes',
            component: Routes
        },
        {
            path: '/actions',
            name: 'Actions',
            component: Actions
        },
        {
            path: '/directors',
            name: 'Directors',
            component: Directors
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
        },
        {
            path: '/metrics',
            name: 'Metrics',
            component: Metrics
        },
        {
            path: '/peers',
            name: 'Peers',
            component: Peers
        },
        {
            path: '/headers',
            name: 'Headers',
            component: Headers
        }
    ]
})
