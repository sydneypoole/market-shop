const orderApi = require('../../api/order')
const { fenToYuan, dateTime, resolveMediaUrl } = require('../../utils/format')
const { statusText, resolveOrderActions } = require('../../utils/order-status')
const { getToken } = require('../../utils/request')

const STATUS_HINT = {
  PENDING_SUPERIOR: '上级确认线下收款后，订单将进入后台审核',
  SUPERIOR_REJECTED: '上级已拒绝该订单，可查看拒绝原因',
  PENDING_ADMIN_REVIEW: '后台审核中，请耐心等待',
  ADMIN_REJECTED: '后台审核未通过',
  PENDING_SHIPMENT: '审核已通过，商家正在安排发货',
  SHIPPED: '商品已发出，请注意查收',
  COMPLETED: '订单已完成，感谢购买',
  CANCELLED: '订单已取消'
}

function maskPhone(phone) {
  const s = String(phone || '')
  if (s.length < 7) {
    return s
  }
  return s.slice(0, 3) + '****' + s.slice(-4)
}

function parseAddress(addressJson) {
  if (!addressJson) {
    return null
  }
  try {
    const a = typeof addressJson === 'string' ? JSON.parse(addressJson) : addressJson
    const region = [a.province, a.city, a.district].filter(Boolean).join('')
    const fullAddress = region + (a.detailAddress || '') + (a.postalCode ? ' (' + a.postalCode + ')' : '')
    return {
      recipientName: a.recipientName || '',
      phone: a.phone || '',
      phoneMasked: maskPhone(a.phone),
      fullAddress: fullAddress
    }
  } catch (e) {
    return null
  }
}

function buildTimeline(detail) {
  const order = detail.order || {}
  const shipment = detail.shipment || null
  const status = order.status
  const steps = [
    {
      key: 'created',
      label: '提交订单',
      time: dateTime(order.createdAt),
      done: true
    },
    {
      key: 'superior',
      label: '上级确认',
      time: dateTime(detail.superiorConfirmedAt),
      pending: !detail.superiorConfirmedAt && status === 'PENDING_SUPERIOR',
      done: !!detail.superiorConfirmedAt || ['PENDING_ADMIN_REVIEW', 'ADMIN_REJECTED', 'PENDING_SHIPMENT', 'SHIPPED', 'COMPLETED'].indexOf(status) >= 0
    },
    {
      key: 'admin',
      label: '后台审核',
      time: dateTime(detail.adminReviewedAt),
      pending: status === 'PENDING_ADMIN_REVIEW',
      done: !!detail.adminReviewedAt || ['PENDING_SHIPMENT', 'SHIPPED', 'COMPLETED'].indexOf(status) >= 0
    },
    {
      key: 'ship',
      label: '安排发货',
      time: shipment && shipment.shippedAt ? dateTime(shipment.shippedAt) : '',
      pending: status === 'PENDING_SHIPMENT',
      done: !!shipment || status === 'SHIPPED' || status === 'COMPLETED'
    },
    {
      key: 'done',
      label: '完成',
      time: dateTime(detail.completedAt),
      pending: status === 'SHIPPED',
      done: status === 'COMPLETED'
    }
  ]

  return steps.map(function (step) {
    let state = 'pending'
    if (step.done) {
      state = 'done'
    } else if (step.pending) {
      state = 'current'
    }
    return {
      key: step.key,
      label: step.label,
      time: step.time || '',
      hint: step.pending && !step.time ? '待处理' : '',
      state: state
    }
  })
}

Page({
  data: {
    loading: true,
    orderId: 0,
    order: null,
    statusTitle: '',
    statusHint: '',
    address: null,
    items: [],
    goodsAmountText: '0.00',
    totalAmountText: '0.00',
    timeline: [],
    actions: {
      canCancel: false,
      canUploadProof: false,
      canReceive: false,
      canSuperiorDecide: false
    },
    canApplyAftersale: false,
    proofPreviews: [],
    proofCount: 0,
    pendingUpload: false
  },

  onLoad(query) {
    const id = Number(query && query.id)
    this.pendingAction = (query && query.action) || ''
    this.setData({ orderId: id })
  },

  onShow() {
    if (!getToken()) {
      wx.reLaunch({ url: '/pages/login/login' })
      return
    }
    if (!this.data.orderId) {
      this.setData({ loading: false, order: null })
      return
    }
    this.loadDetail()
  },

  loadDetail() {
    const id = this.data.orderId
    this.setData({ loading: true })
    Promise.all([orderApi.detail(id), orderApi.proofs(id).catch(function () { return [] })])
      .then((results) => {
        const detail = results[0]
        const proofs = results[1] || []
        if (!detail || !detail.order) {
          this.setData({ loading: false, order: null })
          return
        }
        const order = detail.order
        const actions = resolveOrderActions(detail.actorCapabilities)
        const items = (detail.items || []).map(function (item) {
          return {
            skuId: item.skuId,
            productName: item.productName,
            skuName: item.skuName,
            coverUrl: resolveMediaUrl(item.coverUrl || ''),
            quantity: item.quantity,
            priceText: fenToYuan(item.unitPriceFen)
          }
        })
        const goodsAmount = (detail.items || []).reduce(function (sum, item) {
          return sum + (Number(item.subtotalFen) || 0)
        }, 0)

        this.setData({
          loading: false,
          order: order,
          statusTitle: statusText(order.status),
          statusHint: STATUS_HINT[order.status] || '',
          address: parseAddress(detail.addressJson),
          items: items,
          goodsAmountText: fenToYuan(goodsAmount || order.totalAmountFen),
          totalAmountText: fenToYuan(order.totalAmountFen),
          timeline: buildTimeline(detail),
          actions: actions,
          canApplyAftersale: ['SHIPPED', 'COMPLETED'].indexOf(order.status) >= 0
        })

        this.loadProofPreviews(proofs)

        if (this.pendingAction === 'upload' && actions.canUploadProof) {
          this.pendingAction = ''
          this.onUploadProof()
        }
      })
      .catch((err) => {
        this.setData({ loading: false, order: null })
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
    // ProofView 无直链；优先签下载 URL，失败时仍保留占位以展示张数
    const download =
      typeof orderApi.proofDownload === 'function'
        ? orderApi.proofDownload.bind(orderApi)
        : null

    const tasks = list.map(function (p) {
      if (!download) {
        return Promise.resolve({ proofId: p.proofId, url: '' })
      }
      return download(p.proofId)
        .then(function (d) {
          return { proofId: p.proofId, url: (d && d.signedUrl) || '' }
        })
        .catch(function () {
          return { proofId: p.proofId, url: '' }
        })
    })

    Promise.all(tasks).then((previews) => {
      this.setData({
        proofPreviews: previews,
        proofCount: list.length
      })
    })
  },

  previewProof(e) {
    const url = e.currentTarget.dataset.url
    if (!url) {
      return
    }
    const urls = (this.data.proofPreviews || []).map(function (p) {
      return p.url
    })
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
    const id = this.data.orderId
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
        orderApi
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

  onCancel() {
    const id = this.data.orderId
    wx.showModal({
      title: '取消订单',
      editable: true,
      placeholderText: '请填写取消原因',
      success: (res) => {
        if (!res.confirm) {
          return
        }
        const reason = (res.content || '').trim()
        if (!reason) {
          wx.showToast({ title: '请填写取消原因', icon: 'none' })
          return
        }
        orderApi
          .cancel(id, reason)
          .then(() => {
            wx.showToast({ title: '已取消', icon: 'none' })
            this.loadDetail()
          })
          .catch((err) => {
            wx.showToast({ title: (err && err.message) || '取消失败', icon: 'none' })
          })
      }
    })
  },

  onSuperiorApprove() {
    const id = this.data.orderId
    orderApi
      .superiorDecision(id, true, null)
      .then(() => {
        wx.showToast({ title: '已确认收款', icon: 'none' })
        this.loadDetail()
      })
      .catch((err) => {
        wx.showToast({ title: (err && err.message) || '操作失败', icon: 'none' })
      })
  },

  onSuperiorReject() {
    const id = this.data.orderId
    wx.showModal({
      title: '拒绝订单',
      editable: true,
      placeholderText: '请填写拒绝原因',
      success: (res) => {
        if (!res.confirm) {
          return
        }
        const reason = (res.content || '').trim()
        if (!reason) {
          wx.showToast({ title: '请填写拒绝原因', icon: 'none' })
          return
        }
        orderApi
          .superiorDecision(id, false, reason)
          .then(() => {
            wx.showToast({ title: '已拒绝', icon: 'none' })
            this.loadDetail()
          })
          .catch((err) => {
            wx.showToast({ title: (err && err.message) || '操作失败', icon: 'none' })
          })
      }
    })
  },

  goAftersaleApply() {
    if (!this.data.canApplyAftersale) {
      return
    }
    wx.navigateTo({ url: '/pages/aftersale/apply?orderId=' + this.data.orderId })
  },

  onReceive() {
    const id = this.data.orderId
    wx.showModal({
      title: '确认收货',
      content: '确认已收到商品？',
      success: (res) => {
        if (!res.confirm) {
          return
        }
        orderApi
          .receive(id)
          .then(() => {
            wx.showToast({ title: '已确认收货', icon: 'none' })
            this.loadDetail()
          })
          .catch((err) => {
            wx.showToast({ title: (err && err.message) || '操作失败', icon: 'none' })
          })
      }
    })
  }
})
