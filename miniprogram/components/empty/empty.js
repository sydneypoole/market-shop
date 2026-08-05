Component({
  properties: {
    text: { type: String, value: '暂无内容' },
    hint: { type: String, value: '' },
    buttonText: { type: String, value: '' }
  },

  methods: {
    onAction() {
      this.triggerEvent('action')
    }
  }
})
