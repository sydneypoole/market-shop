const aftersaleApi = require('../../api/aftersale')
const authApi = require('../../api/auth')
const { dateTime } = require('../../utils/format')
const {
  AFTERSALE_TYPE_TEXT,
  aftersaleStatusText
} = require('../../utils/aftersale-status')
const { getToken } = require('../../utils/request')

const TERMINAL_STATUS = ['COMPLETED', 'REJECTED', 'CANCELLED']

const BUYER_HINT = {
  PENDING_ADMIN_REVIEW: '申请已提交，等待后台审核',
  AWAITING_RETURN: '审核已通过，请按回寄地址寄回商品并填写物流单号',
  RETURN_SHIPPED: '回寄物流已提交，等待商家收货后安排退款',
  PENDING_OFFLINE_REFUND: '等待上级线下退款',
  PENDING_BUYER_REFUND_CONFIRMATION: '退款已发起，请确认到账',
  COMPLETED: '售后已完成',
  REJECTED: '售后申请未通过，可查看拒绝原因',
  CANCELLED: '售后申请已撤销'
}

const SUPERIOR_HINT = {
  PENDING_ADMIN_REVIEW: '会员已提交售后申请，等待后台审核',
  AWAITING_RETURN: '等待会员回寄商品',
  RETURN_SHIPPED: '会员已回寄，等待后台确认后进入退款',
  PENDING_OFFLINE_REFUND: '请线下向会员退款，完成后在此确认',
  PENDING_BUYER_REFUND_CONFIRMATION: '等待会员确认退款到账',
  COMPLETED: '售后已完成',
  REJECTED: '该售后申请已被后台拒绝',
  CANCELLED: '会员已撤销售后申请'
}

function parseReturnAddress(json) {
  if (!json) {
    return null
  }
  try {
    const a = typeof json === 'string' ? JSON.parse(json) : json
    const region = [a.province, a.city, a.district].filter(Boolean).join('')
    return {
      name: a.recipientName || a.name || '',
      phone: a.phone || '',
      address: region + (a.detailAddress || a.detail || '')
    }
  } catch (e) {
    return null
  }
}

function buildTimeline(view) {
  const isReturn = view.type === 'RETURN_REFUND'
  const status = view.status
  const nodes = [
    { key: 'created', label: '提交申请', time: dateTime(view.createdAt) },
    { key: 'review', label: '后台审核', time: '' }
  ]
  if (isReturn) {
    nodes.push({ key: 'return', label: '回寄', time: '' })
  }
  nodes.push({ key: 'refund', label: '线下退款', time: '' })
  nodes.push({ key: 'done', label: '完成', time: dateTime(view.completedAt) })

  const currentKeyByStatus = {
    PENDING_ADMIN_REVIEW: 'review',
    AWAITING_RETURN: isReturn ? 'return' : 'refund',
    RETURN_SHIPPED: 'refund',
    PENDING_OFFLINE_REFUND: 'refund',
    PENDING_BUYER_REFUND_CONFIRMATION: 'done',
    REJECTED: 'review'
  }

  let currentIndex = -1
  const doneAll = status === 'COMPLETED'
  if (!doneAll && status !== 'CANCELLED') {
    const key = currentKeyByStatus[status]
    for (let i = 0; i < nodes.length; i++) {
      if (nodes[i].key === key) {
        currentIndex = i
        break
      }
    }
  }

  return nodes.map(function (node, i) {
    let state = 'pending'
    if (i === 0 || doneAll || i < currentIndex) {
      state = 'done'
    } else if (i === currentIndex) {
      state = 'current'
    }
    return {
      key: node.key,
      label: node.label,
      time: node.time || '',
      hint: state === 'current' && !node.time ? '待处理' : '',
      state: state
    }
  })
}

Page({
  data: {
    loading: true,
    aftersaleId: 0,
    view: null,
    statusTitle: '',
    statusHint: '',
    typeText: '',
    createdText: '',
    returnAddress: null,
    timeline: [],
    isApplicant: false,
    isSuperior: false,
    actions: {
      canUploadProof: false,
      canReturnShip: false,
      canConfirmRefund: false,
      canConfirmOffline: false,
      canCancel: false
    },
    carrier: '',
    trackingNo: '',
    proofPreviews: [],
    proofCount: 0,
    pendingUpload: false,
    submitting: false
  },

  onLoad(query) {
    const id = Number(query && query.id)
    this.setData({ aftersaleId: id })
  },

  onShow() {
    if (!getToken()) {
      wx.reLaunch({ url: '/pages/login/login' })
      return
    }
    if (!this.data.aftersaleId) {
      this.setData({ loading: false, view: null })
      return
    }
    this.loadDetail()
  },

  loadDetail() {
    const id = this.data.aftersaleId
    this.setData({ loading: true })
    Promise.all([
      aftersaleApi.detail(id),
      aftersaleApi.proofs(id).catch(function () { return [] }),
      authApi.me().catch(function () { return null })
    ])
      .then((results) => {
        const view = results[0]
        const proofs = results[1] || []
        const me = results[2]
        if (!view || !view.id) {
          this.setData({ loading: false, view: null })
          return
        }
        const myId = me && me.userId
        const isApplicant = Number(myId) === Number(view.applicantUserId)
        const isSuperior = !isApplicant && Number(myId) === Number(view.superiorUserId)
        const status = view.status
        const terminal = TERMINAL_STATUS.indexOf(status) >= 0
        const hints = isSuperior ? SUPERIOR_HINT : BUYER_HINT

        this.setData({
          loading: false,
          view: view,
          statusTitle: aftersaleStatusText(status),
          statusHint: hints[status] || BUYER_HINT[status] || '',
          typeText: AFTERSALE_TYPE_TEXT[view.type] || view.type || '',
          createdText: dateTime(view.createdAt),
          returnAddress: parseReturnAddress(view.returnAddressJson),
          timeline: buildTimeline(view),
          isApplicant: isApplicant,
          isSuperior: isSuperior,
          actions: {
            canUploadProof: isApplicant && !terminal,
            canReturnShip: isApplicant && status === 'AWAITING_RETURN' && view.type === 'RETURN_REFUND',
            canConfirmRefund: isApplicant && status === 'PENDING_BUYER_REFUND_CONFIRMATION',
            canConfirmOffline: isSuperior && status === 'PENDING_OFFLINE_REFUND',
            canCancel:
              isApplicant && ['PENDING_ADMIN_REVIEW', 'AWAITING_RETURN'].indexOf(status) >= 0
          }
        })

        this.loadProofPreviews(proofs)
      })
      .catch((err) => {
        this.setData({ loading: false, view: null })
        if (err && err.code === 'NOT_LOGGED_IN') {
          return
        }
        wx.showToast({ title: (err && err.message) || '加载失败', icon: 'none' })
      })
  },

  loadProofPreviews(proofs) {
    const list = proofs || []
    if (!list.length) {
      this.setData({ proofPreviews: [], proofCount: 0 })
      return
    }
    const tasks = list.map(function (p) {
      return aftersaleApi
        .proofDownload(p.id)
        .then(function (d) {
          return { proofId: p.id, url: (d && d.signedUrl) || '' }
        })
        .catch(function () {
          return { proofId: p.id, url: '' }
        })
    })
    Promise.all(tasks).then((previews) => {
      this.setData({ proofPreviews: previews, proofCount: list.length })
    })
  },

  previewProof(e) {
    const url = e.currentTarget.dataset.url
    if (!url) {
      return
    }
    const urls = (this.data.proofPreviews || [])
      .map(function (p) {
        return p.url
      })
      .filter(Boolean)
    wx.previewImage({ current: url, urls: urls })
  },

  onUploadProof() {
    if (!this.data.actions.canUploadProof) {
      return
    }
    if ((this.data.proofCount || 0) >= 3) {
      wx.showToast({ title: '最多上传 3 张凭证', icon: 'none' })
      return
    }
    if (this.data.pendingUpload) {
      return
    }
    const id = this.data.aftersaleId
    wx.chooseMedia({
      count: 1,
      mediaType: ['image'],
      sourceType: ['album', 'camera'],
      success: (res) => {
        const file = res.tempFiles && res.tempFiles[0]
        if (!file || !file.tempFilePath) {
          return
        }
        this.setData({ pendingUpload: true })
        wx.showLoading({ title: '上传中' })
        aftersaleApi
          .uploadProof(id, file.tempFilePath)
          .then(() => {
            wx.hideLoading()
            this.setData({ pendingUpload: false })
            wx.showToast({ title: '上传成功', icon: 'none' })
            this.loadDetail()
          })
          .catch((err) => {
            wx.hideLoading()
            this.setData({ pendingUpload: false })
            wx.showToast({ title: (err && err.message) || '上传失败', icon: 'none' })
          })
      }
    })
  },

  onInput(e) {
    const field = e.currentTarget.dataset.field
    if (field) {
      this.setData({ [field]: e.detail.value })
    }
  },

  onSubmitShipment() {
    if (this.data.submitting) {
      return
    }
    const carrier = (this.data.carrier || '').trim()
    const trackingNo = (this.data.trackingNo || '').trim()
    if (!carrier || !trackingNo) {
      wx.showToast({ title: '请填写承运商和物流单号', icon: 'none' })
      return
    }
    const id = this.data.aftersaleId
    this.setData({ submitting: true })
    aftersaleApi
      .returnShipment(id, { carrier: carrier, trackingNo: trackingNo })
      .then(() => {
        this.setData({ submitting: false })
        wx.showToast({ title: '已提交物流', icon: 'none' })
        this.loadDetail()
      })
      .catch((err) => {
        this.setData({ submitting: false })
        wx.showToast({ title: (err && err.message) || '提交失败', icon: 'none' })
      })
  },

  onConfirmRefund() {
    const id = this.data.aftersaleId
    wx.showModal({
      title: '确认退款到账',
      content: '确认已收到退款？',
      success: (res) => {
        if (!res.confirm) {
          return
        }
        aftersaleApi
          .confirmRefund(id)
          .then(() => {
            wx.showToast({ title: '已确认', icon: 'none' })
            this.loadDetail()
          })
          .catch((err) => {
            wx.showToast({ title: (err && err.message) || '操作失败', icon: 'none' })
          })
      }
    })
  },

  onConfirmOfflineRefund() {
    const id = this.data.aftersaleId
    wx.showModal({
      title: '确认线下退款',
      content: '确认已向会员完成线下退款？',
      success: (res) => {
        if (!res.confirm) {
          return
        }
        aftersaleApi
          .superiorConfirmOfflineRefund(id)
          .then(() => {
            wx.showToast({ title: '已确认', icon: 'none' })
            this.loadDetail()
          })
          .catch((err) => {
            wx.showToast({ title: (err && err.message) || '操作失败', icon: 'none' })
          })
      }
    })
  },

  onCancel() {
    const id = this.data.aftersaleId
    wx.showModal({
      title: '撤销申请',
      editable: true,
      placeholderText: '请填写撤销原因',
      success: (res) => {
        if (!res.confirm) {
          return
        }
        const reason = (res.content || '').trim()
        if (!reason) {
          wx.showToast({ title: '请填写撤销原因', icon: 'none' })
          return
        }
        aftersaleApi
          .cancel(id, reason)
          .then(() => {
            wx.showToast({ title: '已撤销', icon: 'none' })
            this.loadDetail()
          })
          .catch((err) => {
            wx.showToast({ title: (err && err.message) || '撤销失败', icon: 'none' })
          })
      }
    })
  },

  goOrder() {
    const view = this.data.view
    if (!view || !view.orderId) {
      return
    }
    wx.navigateTo({ url: '/pages/order/detail?id=' + view.orderId })
  }
})
