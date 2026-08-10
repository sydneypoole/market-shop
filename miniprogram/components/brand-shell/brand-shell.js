Component({
  options: {
    addGlobalClass: true
  },

  properties: {
    loading: { type: Boolean, value: false },
    loadingText: { type: String, value: '加载中' },
    safeBottom: { type: Boolean, value: false },
    padded: { type: Boolean, value: false },
    tone: { type: String, value: 'default' },
    ariaLabel: { type: String, value: '宏杉生物页面内容' }
  }
})
