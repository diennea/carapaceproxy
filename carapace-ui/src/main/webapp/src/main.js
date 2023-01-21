import Vue from "vue";
import App from "./App.vue";
import router from "./router";
import "./app.scss";
import { toBooleanSymbol } from "./lib/formatter";
import { formatTimestamp } from "./lib/formatter";
import BootstrapVue from "bootstrap-vue";
import "bootstrap/dist/css/bootstrap.css";
import "bootstrap-vue/dist/bootstrap-vue.css";

// Icons
import { FontAwesomeIcon } from "@fortawesome/vue-fontawesome";
import { library } from "@fortawesome/fontawesome-svg-core";
import {
    faAngleRight,
    faArchive,
    faBolt,
    faChartBar,
    faCircleInfo,
    faCrosshairs,
    faDatabase,
    faDoorOpen,
    faFileSignature,
    faFilter,
    faHeading,
    faHome,
    faInfo,
    faMapSigns,
    faNetworkWired,
    faServer,
    faSlidersH,
    faUsers
} from "@fortawesome/free-solid-svg-icons";
library.add(faAngleRight);
library.add(faArchive);
library.add(faBolt);
library.add(faChartBar);
library.add(faCircleInfo);
library.add(faCrosshairs);
library.add(faDatabase);
library.add(faDoorOpen);
library.add(faFileSignature);
library.add(faFilter);
library.add(faHeading);
library.add(faHome);
library.add(faInfo);
library.add(faMapSigns);
library.add(faNetworkWired);
library.add(faServer);
library.add(faSlidersH);
library.add(faUsers);

Vue.component("font-awesome-icon", FontAwesomeIcon);

// Boostrap Vue
Vue.use(BootstrapVue);

Vue.config.productionTip = false;

// Filters
Vue.filter("symbolFormat", value => {
    return toBooleanSymbol(value);
});
Vue.filter("dateFormat", value => {
    if (!value || value <= 0) {
        return "";
    }
    return formatTimestamp(value);
});

new Vue({
    router: router,
    render: h => h(App)
}).$mount("#app");
