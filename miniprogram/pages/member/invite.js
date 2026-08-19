const memberApi = require('../../api/member')
const { fenToYuan, dateTime } = require('../../utils/format')
const { getToken } = require('../../utils/request')

const INVITATION_STATUS = {
  ACTIVE: '生效中',
  REVOKED: '已撤销',
  EXPIRED: '已过期'
}

const PERFORMANCE_STATUS = {
  ACTIVE: '已计入',
  REVERSED: '已冲回',
  UNQUALIFIED: '未达标'
}

Page({
  data: {
    loading: true,
    error: '',
    invitation: null,
    members: [],
    pending: false,
    qrImage: ''
  },

  onLoad(query) {
    this.scrollToMembers = !!(query && query.tab === 'members')
  },

  onShow() {
    if (!getToken()) {
      wx.reLaunch({ url: '/pages/login/login' })
      return
    }
    this.load()
  },

  load() {
    this.setData({ loading: true, error: '', qrImage: '' })
    Promise.all([
      memberApi.invitation(),
      memberApi.directMembers()
    ])
      .then((results) => {
        const inv = results[0]
        const list = results[1] || []
        const invitation = inv
          ? {
              code: inv.code,
              statusText: INVITATION_STATUS[inv.status] || '未知邀请码状态',
              useCount: inv.useCount,
              registrationPath: inv.registrationPath,
              expiresText: dateTime(inv.expiresAt)
            }
          : null
        const members = list.map(function (m) {
          return {
            userId: m.userId,
            nickname: m.nickname || m.publicId || '会员',
            levelName: m.levelName || '',
            performanceText: fenToYuan(m.performanceFen),
            performanceStatusText: PERFORMANCE_STATUS[m.performanceStatus] || '未知业绩状态',
            ordinalText: m.completedOrdinal > 0 ? '第' + m.completedOrdinal + '单' : ''
          }
        })
        this.setData({ loading: false, invitation: invitation, members: members })
        this.scrollToMembersIfNeeded()
        if (!invitation || !invitation.code) {
          return
        }
        return memberApi.invitationWxacode()
          .then((card) => {
            const contentType = (card && card.contentType) || 'image/png'
            const imageBase64 = card && card.imageBase64
            if (!imageBase64) {
              return
            }
            this.setData({
              qrImage: 'data:' + contentType + ';base64,' + imageBase64
            })
          })
          .catch(() => {})
      })
      .catch((err) => {
        this.setData({
          loading: false,
          error: (err && err.message) || '邀请信息加载失败'
        })
        if (err && err.code === 'NOT_LOGGED_IN') {
          return
        }
      })
  },

  scrollToMembersIfNeeded() {
    if (!this.scrollToMembers) {
      return
    }
    this.scrollToMembers = false
    const query = wx.createSelectorQuery().in(this)
    query.select('#members').boundingClientRect()
    query.selectViewport().scrollOffset()
    query.exec((res) => {
      const rect = res[0]
      const viewport = res[1]
      if (rect && viewport) {
        wx.pageScrollTo({ scrollTop: viewport.scrollTop + rect.top, duration: 0 })
      }
    })
  },

  runAction(action, successText) {
    if (this.data.pending) {
      return
    }
    this.setData({ pending: true })
    action()
      .then(() => {
        this.setData({ pending: false })
        if (successText) {
          wx.showToast({ title: successText, icon: 'none' })
        }
        this.load()
      })
      .catch((err) => {
        this.setData({ pending: false })
        wx.showToast({ title: (err && err.message) || '操作失败', icon: 'none' })
      })
  },

  onCreate() {
    this.runAction(function () {
      return memberApi.createInvitation()
    }, '邀请码已生成')
  },

  onCopy() {
    const code = this.data.invitation && this.data.invitation.code
    if (!code) {
      return
    }
    wx.setClipboardData({
      data: code,
      success: () => {
        wx.showToast({ title: '已复制', icon: 'none' })
      }
    })
  },

  onShareAppMessage() {
    const invitation = this.data.invitation
    if (!invitation || !invitation.registrationPath) {
      return undefined
    }
    let path = invitation.registrationPath
    if (path.startsWith('/')) {
      path = path.slice(1)
    }
    return {
      title: '宏杉生物邀请你注册',
      path
    }
  },

  onSaveCard() {
    const image = this.data.qrImage
    if (!image) {
      return
    }
    const prefix = 'base64,'
    const idx = image.indexOf(prefix)
    const data = idx >= 0 ? image.slice(idx + prefix.length) : image
    const filePath = wx.env.USER_DATA_PATH + '/invite-wxacode.png'
    wx.getFileSystemManager().writeFile({
      filePath,
      data,
      encoding: 'base64',
      success() {
        wx.saveImageToPhotosAlbum({ filePath })
      }
    })
  },

  onRevoke() {
    wx.showModal({
      title: '撤销邀请码',
      content: '撤销后该邀请码将不可使用，确认撤销？',
      success: (res) => {
        if (!res.confirm) {
          return
        }
        this.runAction(function () {
          return memberApi.revokeInvitation()
        }, '已撤销')
      }
    })
  },

  onRegenerate() {
    wx.showModal({
      title: '重建邀请码',
      content: '将生成新邀请码，原邀请码同时失效，确认重建？',
      success: (res) => {
        if (!res.confirm) {
          return
        }
        this.runAction(function () {
          return memberApi.regenerateInvitation(365)
        }, '邀请码已重建')
      }
    })
  }
})
