const { BASE_URL } = require('./utils/config')
const { getToken } = require('./utils/request')

App({
  globalData: {
    baseUrl: BASE_URL,
    token: '',
    // 结算页地址选择回传（address/list select 模式写入，order/confirm 消费后清空）
    selectedAddress: null
  },

  onLaunch() {
    this.globalData.token = getToken()
  }
})
