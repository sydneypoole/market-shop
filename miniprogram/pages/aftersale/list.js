const aftersaleApi = require('../../api/aftersale')
const { dateTime } = require('../../utils/format')
const {
  AFTERSALE_TYPE_TEXT,
  aftersaleStatusText,
  aftersaleStatusTone
} = require('../../utils/aftersale-status')
const { getToken } = require('../../utils/request')

const TABS = [
  { key: 'mine', label: '我申请的' },
  { key: 'superior', label: '我处理的' }
]

const EMPTY_TEXT = {
  mine: { text: '还没有售后申请', hint: '订单完成后可在订单详情发起售后' },
  superior: { text: '没有待处理的售后', hint: '直推会员的售后会显示在这里' }
}

function mapRow(row) {
  return {
    id: row.id,
    afterSaleNo: row.afterSaleNo,
    typeText: AFTERSALE_TYPE_TEXT[row.type] || row.type || '',
    statusText: aftersaleStatusText(row.status),
    tone: aftersaleStatusTone(row.status),
    createdText: dateTime(row.createdAt)
  }
}

Page({
  data: {
    tabs: TABS,
    activeTab: 0,
    loading: true,
    error: '',
    displayList: [],
    emptyText: EMPTY_TEXT.mine.text,
    emptyHint: EMPTY_TEXT.mine.hint
  },

  onLoad(query) {
    const tab = Number(query && query.tab)
    if (Number.isFinite(tab) && tab >= 0 && tab < TABS.length) {
      this.setData({ activeTab: tab })
    }
  },

  onShow() {
    if (!getToken()) {
      wx.reLaunch({ url: '/pages/login/login' })
      return
    }
    this.loadList()
  },

  loadList() {
    const tabKey = TABS[this.data.activeTab].key
    const empty = EMPTY_TEXT[tabKey]
    this.setData({
      loading: true,
      error: '',
      emptyText: empty.text,
      emptyHint: empty.hint
    })
    const fetch = tabKey === 'mine' ? aftersaleApi.list() : aftersaleApi.superiorList()
    fetch
      .then((rows) => {
        this.setData({
          loading: false,
          displayList: (rows || []).map(mapRow)
        })
      })
      .catch((err) => {
        this.setData({
          loading: false,
          displayList: [],
          error: (err && err.message) || '加载售后列表失败'
        })
        if (err && err.code === 'NOT_LOGGED_IN') {
          return
        }
      })
  },

  onTabChange(e) {
    const index = Number(e.currentTarget.dataset.index)
    if (index === this.data.activeTab) {
      return
    }
    this.setData({ activeTab: index })
    this.loadList()
  },

  goDetail(e) {
    const id = e.currentTarget.dataset.id
    if (!id) {
      return
    }
    wx.navigateTo({ url: '/pages/aftersale/detail?id=' + id })
  }
})
