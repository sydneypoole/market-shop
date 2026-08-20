const rulesApi = require('../../api/rules')
const { dateTime, fenToYuan, fileSize } = require('../../utils/format')
const { getToken } = require('../../utils/request')

const TYPE_TEXT = {
  SELF_ORDER_TASK: '自购升级任务',
  DIRECT_REFERRAL_TASK: '直推资格任务',
  DIRECT_REFERRAL_POINTS: '直推积分规则',
  FROZEN_POINTS_RELEASE: '冻结积分释放',
  INACTIVITY_DOWNGRADE: '不活跃降级',
  ORDER_TIMER: '订单与凭证时效'
}

const PARAM_TEXT = {
  minimumCompletedOrderAmountFen: '最低完成订单金额',
  eligibleSalesScenes: '适用销售专区',
  targetLevel: '目标会员等级',
  requiredCompletedDirectReferrals: '所需完成直推人数',
  minimumReferralOrderAmountFen: '直推订单最低金额',
  requiredReferralLevel: '直推会员等级',
  qualificationCount: '资格人数',
  pointsStartOrdinal: '积分起算序号',
  totalPoints: '总积分',
  availableAPoints: '可用积分',
  frozenBPoints: '冻结积分',
  maxRewardDepth: '奖励层级',
  releaseMode: '释放方式',
  releasePointsPerOrder: '每单释放积分',
  batchOrder: '释放顺序',
  inactiveMonths: '不活跃月数',
  sourceLevel: '原会员等级',
  autoReceiveDays: '发货后自动收货天数',
  autoReceiveDaysAfterShipment: '发货后自动收货天数',
  afterSaleDaysAfterCompletion: '完成后售后期限',
  pendingSuperiorTimeoutDays: '待上级确认超时',
  pendingAdminReviewTimeoutDays: '待后台审核超时',
  pendingShipmentTimeoutDays: '待发货超时',
  awaitingReturnTimeoutDays: '待寄回超时',
  returnShippedTimeoutDays: '待确认收货超时',
  offlineRefundTimeoutDays: '待线下退款超时',
  buyerRefundConfirmTimeoutDays: '待买家确认退款超时',
  proofRetentionDays: '凭证保留天数',
  maxProofFiles: '凭证张数上限',
  maxProofSizeBytes: '单张凭证大小'
}

const VALUE_TEXT = {
  UPGRADE: '升级专区',
  REPURCHASE: '复购专区',
  BASIC: '基础会员',
  EXPERIENCE_OFFICER: '体验官',
  SUPER_MEMBER: '超级会员',
  DIVIDEND_MEMBER: '分红会员',
  FIXED: '固定积分',
  FIFO: '先冻结先释放'
}

const MONEY_KEYS = [
  'minimumCompletedOrderAmountFen',
  'minimumReferralOrderAmountFen'
]

const POINT_KEYS = [
  'totalPoints',
  'availableAPoints',
  'frozenBPoints',
  'releasePointsPerOrder'
]

const DAY_KEYS = [
  'autoReceiveDays',
  'autoReceiveDaysAfterShipment',
  'afterSaleDaysAfterCompletion',
  'pendingSuperiorTimeoutDays',
  'pendingAdminReviewTimeoutDays',
  'pendingShipmentTimeoutDays',
  'awaitingReturnTimeoutDays',
  'returnShippedTimeoutDays',
  'offlineRefundTimeoutDays',
  'buyerRefundConfirmTimeoutDays',
  'proofRetentionDays'
]

function enumText(value) {
  if (Object.prototype.hasOwnProperty.call(VALUE_TEXT, value)) {
    return VALUE_TEXT[value]
  }
  return String(value)
}

function stringifyValue(key, value) {
  if (value === null) {
    return '未配置（null）'
  }
  if (value === undefined) {
    return '未配置'
  }
  if (MONEY_KEYS.indexOf(key) >= 0) {
    return '¥' + fenToYuan(value)
  }
  if (key === 'maxProofSizeBytes') {
    return fileSize(value)
  }
  if (DAY_KEYS.indexOf(key) >= 0) {
    return value + ' 天'
  }
  if (key === 'inactiveMonths') {
    return value + ' 个月'
  }
  if (key === 'maxProofFiles') {
    return value + ' 张'
  }
  if (key === 'requiredCompletedDirectReferrals' || key === 'qualificationCount') {
    return value + ' 人'
  }
  if (key === 'maxRewardDepth') {
    return value + ' 层'
  }
  if (POINT_KEYS.indexOf(key) >= 0) {
    return value + ' 积分'
  }
  if (Array.isArray(value)) {
    return value.map(enumText).join('、')
  }
  if (typeof value === 'object') {
    return JSON.stringify(value)
  }
  if (typeof value === 'boolean') {
    return value ? '是' : '否'
  }
  return enumText(value)
}

function parseParams(parametersJson) {
  try {
    const obj = JSON.parse(parametersJson || '{}')
    if (!obj || typeof obj !== 'object' || Array.isArray(obj)) {
      return []
    }
    return Object.keys(obj).map(function (key) {
      return {
        key: PARAM_TEXT[key] || '其他配置 · ' + key,
        value: stringifyValue(key, obj[key])
      }
    })
  } catch (e) {
    return []
  }
}

function mapRule(row) {
  return {
    id: row.id,
    title: TYPE_TEXT[row.ruleType] || '其他规则',
    version: row.version,
    effectiveText: dateTime(row.effectiveFrom),
    params: parseParams(row.parametersJson)
  }
}

Page({
  data: {
    loading: true,
    error: '',
    rules: []
  },

  onShow() {
    if (!getToken()) {
      wx.reLaunch({ url: '/pages/login/login' })
      return
    }
    this.loadRules()
  },

  loadRules() {
    this.setData({ loading: true, error: '' })
    return rulesApi
      .active()
      .then((rows) => {
        this.setData({ rules: (rows || []).map(mapRule), loading: false })
      })
      .catch((err) => {
        this.setData({
          loading: false,
          rules: [],
          error: (err && err.message) || '加载规则失败'
        })
        if (err && err.code === 'NOT_LOGGED_IN') {
          return
        }
      })
  }
})
