module.exports = {
  configureWebpack: {
    output: {
      publicPath: ''

    },
    resolve: {
      alias: {
        '@': 'src'
      }
    }
  },
}