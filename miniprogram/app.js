const { getBaseUrl } = require('./utils/config')
const { getToken } = require('./utils/request')

App({
  globalData: {
    baseUrl: '',
    configError: '',
    token: '',
    // 结算页地址选择回传（address/list select 模式写入，order/confirm 消费后清空）
    selectedAddress: null
  },

  onLaunch() {
    this.globalData.token = getToken()
    try {
      this.globalData.baseUrl = getBaseUrl()
      this.globalData.configError = ''
    } catch (err) {
      this.globalData.baseUrl = ''
      this.globalData.configError = (err && err.message) || '小程序接口地址配置无效'
    }
  }
})
