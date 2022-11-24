import Vue from 'vue';
import Router from 'vue-router';

import Backends from './components/Backends';
import ConnectionPools from './components/ConnectionPools';
import Routes from './components/Routes';
import Actions from './components/Actions';
import Directors from './components/Directors';
import Cache from './components/Cache';
import Listeners from './components/Listeners';
import UserRealm from './components/UserRealm';
import RequestFilters from './components/RequestFilters';
import Certificates from './components/certificates/Certificates';
import Certificate from './components/certificates/Certificate';
import DatatableList from './components/DatatableList';
import Configuration from './components/Configuration';
import Metrics from './components/Metrics';
import Peers from './components/Peers';
import Headers from './components/Headers';

Vue.component('backends', Backends);
Vue.component('connection-pools', ConnectionPools);
Vue.component('routes', Routes);
Vue.component('actions', Actions);
Vue.component('directors', Directors);
Vue.component('cache', Cache);
Vue.component('listeners', Listeners);
Vue.component('userrealm', UserRealm);
Vue.component('requestfilters', RequestFilters);
Vue.component('certificates', Certificates);
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
            component: Cache
        },
        {
            path: '/listeners',
            name: 'Listeners',
            component: Listeners
        },
        {
            path: '/requestfilters',
            name: 'Request filters',
            component: RequestFilters
        },
        {
            path: '/users',
            name: 'Users',
            component: UserRealm
        },
        {
            path: '/certificates',
            name: 'Certificates',
            component: Certificates
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
