const catalogApi = require('../../api/catalog')
const { resolveMediaUrl, resolveRichTextMedia } = require('../../utils/format')

Page({
  data: {
    contentId: 0,
    loading: true,
    error: '',
    retryable: false,
    content: null,
    coverUrl: ''
  },

  onLoad(query) {
    const id = Number(query && query.id)
    this.setData({ contentId: id })
    if (!id) {
      this.setData({ loading: false, error: '内容参数无效', retryable: false })
      return
    }
    this.loadContent()
  },

  loadContent() {
    const id = this.data.contentId
    if (!id || (this.data.error && !this.data.retryable)) {
      return
    }
    this.setData({ loading: true, error: '', retryable: false })
    return catalogApi
      .content(id)
      .then((content) => {
        if (!content || !content.id) {
          this.setData({ loading: false, error: '内容不存在或已下线', retryable: false, content: null })
          return
        }
        wx.setNavigationBarTitle({ title: content.title || '内容详情' })
        this.setData({
          loading: false,
          error: '',
          retryable: false,
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
          error: (err && err.message) || '内容加载失败',
          retryable: Number(err && (err.statusCode || err.status)) !== 404
        })
      })
  }
})
