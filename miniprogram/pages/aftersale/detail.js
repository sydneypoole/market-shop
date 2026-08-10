const aftersaleApi = require('../../api/aftersale')
const authApi = require('../../api/auth')
const systemApi = require('../../api/system')
const { dateTime, resolveMediaUrl } = require('../../utils/format')
const {
  AFTERSALE_TYPE_TEXT,
  aftersaleStatusText,
  resolveAftersaleActions
} = require('../../utils/aftersale-status')
const { getToken, isConflict } = require('../../utils/request')
const { resolveProofLimits, aftersaleProofType } = require('../../utils/proof')

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
    error: '',
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
    proofError: '',
    proofLoading: false,
    proofType: 'APPLICATION',
    maxProofFiles: 0,
    maxProofSizeBytes: 0,
    maxProofSizeText: '',
    pendingUpload: false,
    submitting: false,
    actionPending: false
  },

  _detailLoadGeneration: 0,
  _proofLoadGeneration: 0,

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
      this.setData({ loading: false, view: null, error: '售后参数无效' })
      return
    }
    this.loadDetail()
  },

  loadDetail() {
    const id = this.data.aftersaleId
    const loadGeneration = (this._detailLoadGeneration || 0) + 1
    this._detailLoadGeneration = loadGeneration
    this._proofLoadGeneration = (this._proofLoadGeneration || 0) + 1
    this.setData({ loading: true, error: '', proofError: '', proofLoading: false })
    return Promise.all([
      aftersaleApi.detail(id),
      aftersaleApi.proofs(id),
      authApi.me(),
      systemApi.capabilities()
    ])
      .then((results) => {
        if (this._detailLoadGeneration !== loadGeneration) {
          return
        }
        const view = results[0]
        const proofs = results[1] || []
        const me = results[2]
        const limits = resolveProofLimits(results[3])
        if (!view || !view.id) {
          this.setData({ loading: false, view: null, error: '售后单不存在或已失效' })
          return
        }
        const myId = me && me.userId
        const isApplicant = Number(myId) === Number(view.applicantUserId)
        const isSuperior = !isApplicant && Number(myId) === Number(view.superiorUserId)
        const status = view.status
        const hints = isSuperior ? SUPERIOR_HINT : BUYER_HINT

        this.setData({
          loading: false,
          view: view,
          statusTitle: aftersaleStatusText(status),
          statusHint: hints[status] || BUYER_HINT[status] || '当前售后状态暂不可识别，请稍后刷新',
          typeText: AFTERSALE_TYPE_TEXT[view.type] || '未知售后类型',
          createdText: dateTime(view.createdAt),
          returnAddress: parseReturnAddress(view.returnAddressJson),
          timeline: buildTimeline(view),
          isApplicant: isApplicant,
          isSuperior: isSuperior,
          proofType: aftersaleProofType(status),
          maxProofFiles: limits.maxProofFiles,
          maxProofSizeBytes: limits.maxProofSizeBytes,
          maxProofSizeText: limits.maxProofSizeText,
          actions: resolveAftersaleActions(view, isApplicant, isSuperior)
        })

        this.loadProofPreviews(proofs)
      })
      .catch((err) => {
        if (this._detailLoadGeneration !== loadGeneration) {
          return
        }
        this.setData({
          loading: false,
          view: null,
          error: (err && err.message) || '售后详情加载失败'
        })
        if (err && err.code === 'NOT_LOGGED_IN') {
          return
        }
      })
  },

  loadProofPreviews(proofs, generation) {
    const requestGeneration = generation || (this._proofLoadGeneration || 0) + 1
    if (!generation) {
      this._proofLoadGeneration = requestGeneration
    }
    if (this._proofLoadGeneration !== requestGeneration) {
      return Promise.resolve()
    }
    const list = proofs || []
    if (!list.length) {
      this.setData({ proofPreviews: [], proofCount: 0, proofError: '', proofLoading: false })
      return Promise.resolve()
    }
    this.setData({ proofLoading: true })
    const tasks = list.map(function (p) {
      return aftersaleApi
        .proofDownload(p.id)
        .then(function (d) {
          return { proofId: p.id, url: resolveMediaUrl((d && d.signedUrl) || ''), failed: false }
        })
        .catch(function (err) {
          return { proofId: p.id, url: '', failed: true, message: (err && err.message) || '' }
        })
    })
    return Promise.all(tasks).then((previews) => {
      if (this._proofLoadGeneration !== requestGeneration) {
        return
      }
      const failed = previews.some(function (preview) { return preview.failed })
      this.setData({
        proofPreviews: previews,
        proofCount: list.length,
        proofError: failed ? '部分凭证预览加载失败，请重试' : '',
        proofLoading: false
      })
    })
  },

  retryProofs() {
    if (this.data.proofLoading) {
      return Promise.resolve()
    }
    const id = this.data.aftersaleId
    const requestGeneration = (this._proofLoadGeneration || 0) + 1
    this._proofLoadGeneration = requestGeneration
    this.setData({ proofError: '', proofLoading: true })
    return aftersaleApi
      .proofs(id)
      .then((proofs) => {
        if (this._proofLoadGeneration !== requestGeneration) {
          return
        }
        return this.loadProofPreviews(proofs || [], requestGeneration)
      })
      .catch((err) => {
        if (this._proofLoadGeneration === requestGeneration) {
          this.setData({
            proofError: (err && err.message) || '凭证加载失败，请重试',
            proofLoading: false
          })
        }
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
    wx.previewImage({
      current: url,
      urls: urls,
      fail: () => {
        wx.showToast({ title: '凭证链接已失效，正在刷新', icon: 'none' })
        this.retryProofs()
      }
    })
  },

  onUploadProof() {
    if (
      !this.data.actions.canUploadProof ||
      this.data.pendingUpload ||
      this.data.submitting ||
      this.data.actionPending ||
      this.data.proofLoading
    ) {
      return
    }
    const maxProofFiles = this.data.maxProofFiles
    if (!maxProofFiles) {
      wx.showToast({ title: '凭证上传规则暂不可用', icon: 'none' })
      return
    }
    if ((this.data.proofCount || 0) >= maxProofFiles) {
      wx.showToast({ title: '最多上传 ' + maxProofFiles + ' 张凭证', icon: 'none' })
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
        if (Number(file.size) > Number(this.data.maxProofSizeBytes)) {
          wx.showToast({ title: '单张凭证不能超过 ' + this.data.maxProofSizeText, icon: 'none' })
          return
        }
        this.setData({ pendingUpload: true })
        wx.showLoading({ title: '上传中' })
        aftersaleApi
          .uploadProof(id, file.tempFilePath, this.data.proofType)
          .then(() => {
            wx.hideLoading()
            this.setData({ pendingUpload: false })
            wx.showToast({ title: '上传成功', icon: 'none' })
            this.loadDetail()
          })
          .catch((err) => {
            wx.hideLoading()
            this.setData({ pendingUpload: false })
            if (isConflict(err)) {
              wx.showToast({ title: '售后或凭证状态已变化，正在刷新', icon: 'none' })
              this.loadDetail()
              return
            }
            wx.showToast({ title: (err && err.message) || '上传失败', icon: 'none' })
          })
      },
      fail: (err) => {
        if (!/cancel/i.test((err && err.errMsg) || '')) {
          wx.showToast({ title: '选择图片失败，请重试', icon: 'none' })
        }
      }
    })
  },

  handleActionError(err, fallback) {
    this.setData({ submitting: false, actionPending: false })
    if (isConflict(err)) {
      wx.showToast({ title: '售后状态已变化，正在刷新', icon: 'none' })
      this.loadDetail()
      return
    }
    wx.showToast({ title: (err && err.message) || fallback, icon: 'none' })
  },

  onInput(e) {
    const field = e.currentTarget.dataset.field
    if (field) {
      this.setData({ [field]: e.detail.value })
    }
  },

  onSubmitShipment() {
    if (
      !this.data.actions.canReturnShip ||
      this.data.submitting ||
      this.data.actionPending ||
      this.data.pendingUpload
    ) {
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
        this.handleActionError(err, '提交失败')
      })
  },

  onConfirmRefund() {
    if (
      !this.data.actions.canConfirmRefund ||
      this.data.actionPending ||
      this.data.submitting ||
      this.data.pendingUpload
    ) {
      return
    }
    const id = this.data.aftersaleId
    wx.showModal({
      title: '确认退款到账',
      content: '确认已收到退款？',
      success: (res) => {
        if (!res.confirm) {
          return
        }
        this.setData({ actionPending: true })
        aftersaleApi
          .confirmRefund(id)
          .then(() => {
            this.setData({ actionPending: false })
            wx.showToast({ title: '已确认', icon: 'none' })
            this.loadDetail()
          })
          .catch((err) => this.handleActionError(err, '操作失败'))
      }
    })
  },

  onConfirmOfflineRefund() {
    if (
      !this.data.actions.canConfirmOffline ||
      this.data.actionPending ||
      this.data.submitting ||
      this.data.pendingUpload
    ) {
      return
    }
    const id = this.data.aftersaleId
    wx.showModal({
      title: '确认线下退款',
      content: '确认已向会员完成线下退款？',
      success: (res) => {
        if (!res.confirm) {
          return
        }
        this.setData({ actionPending: true })
        aftersaleApi
          .superiorConfirmOfflineRefund(id, '')
          .then(() => {
            this.setData({ actionPending: false })
            wx.showToast({ title: '已确认', icon: 'none' })
            this.loadDetail()
          })
          .catch((err) => this.handleActionError(err, '操作失败'))
      }
    })
  },

  onCancel() {
    if (
      !this.data.actions.canCancel ||
      this.data.actionPending ||
      this.data.submitting ||
      this.data.pendingUpload
    ) {
      return
    }
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
        this.setData({ actionPending: true })
        aftersaleApi
          .cancel(id, reason)
          .then(() => {
            this.setData({ actionPending: false })
            wx.showToast({ title: '已撤销', icon: 'none' })
            this.loadDetail()
          })
          .catch((err) => this.handleActionError(err, '撤销失败'))
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
