const DEV_MODE = process.env.NODE_ENV !== "production";

module.exports = {
  configureWebpack: {
    output: {
      publicPath: DEV_MODE ? '' : '/ui/'
    },
    resolve: {
      alias: {
        '@': 'src'
      }
    }
  },
}