const catalogApi = require('../../api/catalog')
const { resolveMediaUrl, resolveRichTextMedia } = require('../../utils/format')

Page({
  data: {
    contentId: 0,
    loading: true,
    error: '',
    content: null,
    coverUrl: ''
  },

  onLoad(query) {
    const id = Number(query && query.id)
    this.setData({ contentId: id })
    if (!id) {
      this.setData({ loading: false, error: '内容参数无效' })
      return
    }
    this.loadContent()
  },

  loadContent() {
    const id = this.data.contentId
    if (!id) {
      return
    }
    this.setData({ loading: true, error: '' })
    catalogApi
      .content(id)
      .then((content) => {
        if (!content || !content.id) {
          this.setData({ loading: false, error: '内容不存在或已下线', content: null })
          return
        }
        wx.setNavigationBarTitle({ title: content.title || '内容详情' })
        this.setData({
          loading: false,
          error: '',
          content: Object.assign({}, content, {
            bodyHtml: resolveRichTextMedia(content.bodyHtml || '')
          }),
          coverUrl: resolveMediaUrl(content.coverUrl || '')
        })
      })
      .catch((err) => {
        this.setData({
          loading: false,
          content: null,
          error: (err && err.message) || '内容加载失败'
        })
      })
  }
})
