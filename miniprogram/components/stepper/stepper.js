Component({
  properties: {
    value: { type: Number, value: 1 },
    min: { type: Number, value: 1 },
    max: { type: Number, value: 99 }
  },

  methods: {
    emit(next) {
      if (!Number.isFinite(next)) {
        return
      }
      const min = Math.max(0, Number(this.data.min))
      const maxRaw = Number(this.data.max)
      const max = Math.max(min, Number.isFinite(maxRaw) ? maxRaw : 99)
      const value = Math.min(max, Math.max(min, next))
      if (value === this.data.value) {
        return
      }
      this.triggerEvent('change', value)
    },

    onMinus() {
      this.emit(Number(this.data.value) - 1)
    },

    onPlus() {
      this.emit(Number(this.data.value) + 1)
    },

    onFirstUiChange(e) {
      const detail = e && e.detail
      this.emit(Number(detail && detail.value))
    }
  }
})
