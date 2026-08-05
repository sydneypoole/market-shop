const addressApi = require('../../api/address')
const { getToken } = require('../../utils/request')

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
    saving: false
  },

  onLoad(query) {
    const id = Number(query && query.id)
    if (id) {
      this.setData({ isEdit: true, addressId: id })
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
    addressApi
      .list()
      .then((rows) => {
        const found = (rows || []).find((row) => row.id === this.data.addressId)
        if (!found) {
          wx.showToast({ title: '地址不存在', icon: 'none' })
          return
        }
        this._loaded = true
        this.setData({
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
        if (err && err.code === 'NOT_LOGGED_IN') {
          return
        }
        wx.showToast({ title: (err && err.message) || '加载失败', icon: 'none' })
      })
  },

  onInput(e) {
    const field = e.currentTarget.dataset.field
    const value = e.detail.value || ''
    const patch = {}
    patch[field] = value
    this.setData(patch)
  },

  onDefaultChange(e) {
    this.setData({ defaultAddress: !!(e.detail && e.detail.value) })
  },

  onRegionChange(e) {
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
    return {
      recipientName: this.data.recipientName.trim(),
      phone: this.data.phone.trim(),
      province: this.data.province,
      city: this.data.city,
      district: this.data.district,
      detailAddress: this.data.detailAddress.trim(),
      postalCode: (this.data.postalCode || '').trim() || null,
      defaultAddress: !!this.data.defaultAddress,
      version: this.data.isEdit ? this.data.version : 0
    }
  },

  onSave() {
    const errMsg = this.validate()
    if (errMsg) {
      wx.showToast({ title: errMsg, icon: 'none' })
      return
    }
    if (this.data.saving) {
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
        this.setData({ saving: false })
        wx.showToast({ title: (err && err.message) || '保存失败', icon: 'none' })
      })
  },

  onDelete() {
    if (!this.data.isEdit) {
      return
    }
    wx.showModal({
      title: '删除地址',
      content: '确定删除该收货地址？',
      success: (res) => {
        if (!res.confirm) {
          return
        }
        addressApi
          .remove(this.data.addressId, this.data.version)
          .then(() => {
            wx.showToast({ title: '已删除', icon: 'none' })
            setTimeout(function () {
              wx.navigateBack()
            }, 400)
          })
          .catch((err) => {
            wx.showToast({ title: (err && err.message) || '删除失败', icon: 'none' })
          })
      }
    })
  }
})
