<template>
  <div id="app">

    <div id="wrapper" class="toggled">
      <div id="sidebar-wrapper">
        <ul class="sidebar-nav">
          <li class="sidebar-brand">
            <a href="#">
              Http Proxy
            </a>
          </li>
          <li>
            <router-link to="/">Backends</router-link>
          </li>
          <li>
            <router-link to="/cache">Cache</router-link>
          </li>
          <li>
            <router-link to="/certificates">Certificates</router-link>
          </li>
          <li>
            <router-link to="/listeners">Listeners</router-link>
          </li>
          <li>
            <router-link to="/requestfilters">Request filters</router-link>
          </li>
          <li>
            <router-link to="/users">Users</router-link>
          </li>
          <li>
            <router-link to="/configuration">Configuration</router-link>
          </li>
        </ul>
      </div>
      <div id="page-content-wrapper">
        <div class="container-fluid">
          <router-view></router-view>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
  export default {
    name: 'app',
  }
</script>

<style>
  #app {
    font-family: 'Avenir', Helvetica, Arial, sans-serif;
    -webkit-font-smoothing: antialiased;
    -moz-osx-font-smoothing: grayscale;
  }

  .wrapper {
    display: flex;
    width: 100%;
  }

  #sidebar {
    width: 250px;
    position: fixed;
    top: 0;
    left: 0;
    height: 100vh;
    z-index: 999;
    background: #7386D5;
    color: #fff;
    transition: all 0.3s;
  }
</style>