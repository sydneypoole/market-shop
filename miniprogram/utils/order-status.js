const STATUS_TEXT = {
  PENDING_SUPERIOR: '待上级确认',
  SUPERIOR_REJECTED: '上级已拒绝',
  PENDING_ADMIN_REVIEW: '待后台审核',
  ADMIN_REJECTED: '审核未通过',
  PENDING_SHIPMENT: '待发货',
  SHIPPED: '待收货',
  COMPLETED: '已完成',
  CANCELLED: '已取消'
}

const STATUS_TONE = {
  PENDING_SUPERIOR: 'coral',
  SUPERIOR_REJECTED: 'muted',
  PENDING_ADMIN_REVIEW: 'coral',
  ADMIN_REJECTED: 'muted',
  PENDING_SHIPMENT: 'gold',
  SHIPPED: 'green',
  COMPLETED: 'muted',
  CANCELLED: 'muted'
}

function statusText(status) {
  return STATUS_TEXT[status] || status || ''
}

function statusTone(status) {
  return STATUS_TONE[status] || 'muted'
}

function resolveOrderActions(capabilities) {
  const caps = capabilities || {}
  return {
    canReceive: !!caps.canReceive,
    canUploadProof: !!caps.canUploadProof,
    canCancel: !!caps.canCancel,
    canSuperiorDecide: !!caps.canSuperiorDecide
  }
}

module.exports = {
  STATUS_TEXT,
  STATUS_TONE,
  statusText,
  statusTone,
  resolveOrderActions
}
