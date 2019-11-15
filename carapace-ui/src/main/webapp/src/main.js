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
import { faAngleRight } from "@fortawesome/free-solid-svg-icons";
import { faHome } from "@fortawesome/free-solid-svg-icons";
import { faInfo } from "@fortawesome/free-solid-svg-icons";
import { faServer } from "@fortawesome/free-solid-svg-icons";
import { faCrosshairs } from "@fortawesome/free-solid-svg-icons";
import { faBolt } from "@fortawesome/free-solid-svg-icons";
import { faMapSigns } from "@fortawesome/free-solid-svg-icons";
import { faDoorOpen } from "@fortawesome/free-solid-svg-icons";
import { faHeading } from "@fortawesome/free-solid-svg-icons";
import { faFilter } from "@fortawesome/free-solid-svg-icons";
import { faFileSignature } from "@fortawesome/free-solid-svg-icons";
import { faArchive } from "@fortawesome/free-solid-svg-icons";
import { faUsers } from "@fortawesome/free-solid-svg-icons";
import { faChartBar } from "@fortawesome/free-solid-svg-icons";
import { faNetworkWired } from "@fortawesome/free-solid-svg-icons";
import { faSlidersH } from "@fortawesome/free-solid-svg-icons";
library.add(faAngleRight);
library.add(faHome);
library.add(faInfo);
library.add(faServer);
library.add(faCrosshairs);
library.add(faBolt);
library.add(faMapSigns);
library.add(faDoorOpen);
library.add(faHeading);
library.add(faFilter);
library.add(faFileSignature);
library.add(faArchive);
library.add(faUsers);
library.add(faChartBar);
library.add(faNetworkWired);
library.add(faSlidersH);

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
