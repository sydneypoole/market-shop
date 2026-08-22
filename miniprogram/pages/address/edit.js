const addressApi = require('../../api/address')
const { getToken, isConflict } = require('../../utils/request')

const PHONE_RE = /^1\d{10}$/

Page({
  data: {
    isEdit: false,
    addressId: 0,
    version: 0,
    recipientName: '',
    phone: '',
    province: '',
    city: '',
    district: '',
    regionText: '',
    regionValue: [],
    detailAddress: '',
    postalCode: '',
    defaultAddress: false,
    loadingAddress: false,
    addressLoaded: false,
    loadError: '',
    saving: false,
    deleting: false
  },

  onLoad(query) {
    const id = Number(query && query.id)
    if (id) {
      this.setData({ isEdit: true, addressId: id, addressLoaded: false })
      wx.setNavigationBarTitle({ title: '编辑地址' })
    } else {
      wx.setNavigationBarTitle({ title: '新增地址' })
    }
  },

  onShow() {
    if (!getToken()) {
      wx.reLaunch({ url: '/pages/login/login' })
      return
    }
    if (this.data.isEdit && this.data.addressId && !this._loaded) {
      this.loadAddress()
    }
  },

  loadAddress() {
    if (!this.data.isEdit || !this.data.addressId || this.data.loadingAddress) {
      return
    }
    const requestId = (this._loadRequestId || 0) + 1
    this._loadRequestId = requestId
    this._loaded = false
    this.setData({
      loadingAddress: true,
      addressLoaded: false,
      loadError: ''
    })
    return addressApi
      .list()
      .then((rows) => {
        if (requestId !== this._loadRequestId) {
          return
        }
        const found = (rows || []).find((row) => row.id === this.data.addressId)
        if (!found) {
          this.setData({
            loadingAddress: false,
            addressLoaded: false,
            loadError: '地址不存在或已删除'
          })
          return
        }
        this._loaded = true
        this.setData({
          loadingAddress: false,
          addressLoaded: true,
          loadError: '',
          version: found.version,
          recipientName: found.recipientName || '',
          phone: found.phone || '',
          province: found.province || '',
          city: found.city || '',
          district: found.district || '',
          regionText: [found.province, found.city, found.district].filter(Boolean).join(' '),
          regionValue: [found.province || '', found.city || '', found.district || ''],
          detailAddress: found.detailAddress || '',
          postalCode: found.postalCode || '',
          defaultAddress: !!found.defaultAddress
        })
      })
      .catch((err) => {
        if (requestId !== this._loadRequestId) {
          return
        }
        this._loaded = false
        this.setData({
          loadingAddress: false,
          addressLoaded: false,
          loadError: err && err.code === 'NOT_LOGGED_IN'
            ? ''
            : (err && err.message) || '加载地址失败'
        })
        if (err && err.code === 'NOT_LOGGED_IN') {
          return
        }
      })
  },

  onInput(e) {
    if (this.data.isEdit && (!this.data.addressLoaded || this.data.loadingAddress)) {
      return
    }
    const field = e.currentTarget.dataset.field
    const value = e.detail.value || ''
    const patch = {}
    patch[field] = value
    this.setData(patch)
  },

  onDefaultChange(e) {
    if (this.data.isEdit && (!this.data.addressLoaded || this.data.loadingAddress)) {
      return
    }
    this.setData({ defaultAddress: !!(e.detail && e.detail.value) })
  },

  onRegionChange(e) {
    if (this.data.isEdit && (!this.data.addressLoaded || this.data.loadingAddress)) {
      return
    }
    const value = (e.detail && e.detail.value) || []
    this.setData({
      province: value[0] || '',
      city: value[1] || '',
      district: value[2] || '',
      regionValue: value,
      regionText: value.filter(Boolean).join(' ')
    })
  },

  validate() {
    const d = this.data
    if (!d.recipientName.trim()) {
      return '请填写收货人'
    }
    if (!PHONE_RE.test(d.phone.trim())) {
      return '请填写正确的 11 位手机号'
    }
    if (!d.province || !d.city || !d.district) {
      return '请选择所在地区'
    }
    if (!d.detailAddress.trim()) {
      return '请填写详细地址'
    }
    return ''
  },

  buildBody() {
    const body = {
      recipientName: this.data.recipientName.trim(),
      phone: this.data.phone.trim(),
      province: this.data.province,
      city: this.data.city,
      district: this.data.district,
      detailAddress: this.data.detailAddress.trim(),
      defaultAddress: !!this.data.defaultAddress
    }
    const postalCode = (this.data.postalCode || '').trim()
    if (postalCode) {
      body.postalCode = postalCode
    }
    if (this.data.isEdit) {
      body.version = Number(this.data.version)
    }
    return body
  },

  handleMutationError(err, fallback) {
    this.setData({ saving: false, deleting: false })
    if (isConflict(err)) {
      wx.showToast({ title: '地址已在其他端变更，正在刷新', icon: 'none' })
      this._loaded = false
      this.loadAddress()
      return
    }
    wx.showToast({ title: (err && err.message) || fallback, icon: 'none' })
  },

  onSave() {
    if (this.data.isEdit && (!this.data.addressLoaded || this.data.loadingAddress)) {
      wx.showToast({ title: this.data.loadError || '正在读取地址，请稍候', icon: 'none' })
      return
    }
    const errMsg = this.validate()
    if (errMsg) {
      wx.showToast({ title: errMsg, icon: 'none' })
      return
    }
    if (this.data.saving || this.data.deleting) {
      return
    }
    this.setData({ saving: true })
    const body = this.buildBody()
    const req = this.data.isEdit
      ? addressApi.update(this.data.addressId, body)
      : addressApi.create(body)

    req
      .then(() => {
        this.setData({ saving: false })
        wx.showToast({ title: '已保存', icon: 'none' })
        setTimeout(function () {
          wx.navigateBack()
        }, 400)
      })
      .catch((err) => {
        this.handleMutationError(err, '保存失败')
      })
  },

  onDelete() {
    if (
      !this.data.isEdit ||
      !this.data.addressLoaded ||
      this.data.loadingAddress ||
      this.data.saving ||
      this.data.deleting
    ) {
      return
    }
    wx.showModal({
      title: '删除地址',
      content: '确定删除该收货地址？',
      success: (res) => {
        if (!res.confirm) {
          return
        }
        this.setData({ deleting: true })
        addressApi
          .remove(this.data.addressId, this.data.version)
          .then(() => {
            wx.showToast({ title: '已删除', icon: 'none' })
            setTimeout(function () {
              wx.navigateBack()
            }, 400)
          })
          .catch((err) => {
            this.handleMutationError(err, '删除失败')
          })
      }
    })
  }
})
