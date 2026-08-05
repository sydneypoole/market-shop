const { BASE_URL } = require('./utils/config')
const { getToken } = require('./utils/request')

App({
  globalData: {
    baseUrl: BASE_URL,
    token: ''
  },

  onLaunch() {
    this.globalData.token = getToken()
  }
})
