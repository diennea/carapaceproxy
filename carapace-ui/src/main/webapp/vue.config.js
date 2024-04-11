const DEV_MODE = process.env.NODE_ENV !== "production";
module.exports = {
  publicPath: DEV_MODE ? '' : '/ui/',
  configureWebpack: {
    resolve: {
      alias: {
        '@': 'src'
      }
    }
  },
  chainWebpack: (config) => {
    config.resolve.alias.set('vue', '@vue/compat')

    config.module
      .rule('vue')
      .use('vue-loader')
      .tap((options) => {
        return {
          ...options,
          compilerOptions: {
            compatConfig: {
              MODE: 2
            }
          }
        }
      })
  }
}