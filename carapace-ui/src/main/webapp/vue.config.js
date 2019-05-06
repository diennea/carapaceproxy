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
}