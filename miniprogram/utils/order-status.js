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
  SUPERIOR_REJECTED: 'danger',
  PENDING_ADMIN_REVIEW: 'coral',
  ADMIN_REJECTED: 'danger',
  PENDING_SHIPMENT: 'gold',
  SHIPPED: 'green',
  COMPLETED: 'green',
  CANCELLED: 'danger'
}

function statusText(status) {
  return STATUS_TEXT[status] || '未知订单状态'
}

function statusTone(status) {
  return STATUS_TONE[status] || 'muted'
}

function isKnownOrderStatus(status) {
  return Object.prototype.hasOwnProperty.call(STATUS_TEXT, status)
}

function resolveOrderActions(capabilities, status) {
  const caps = capabilities || {}
  if (!isKnownOrderStatus(status)) {
    return {
      canReceive: false,
      canUploadProof: false,
      canCancel: false,
      canSuperiorDecide: false
    }
  }
  return {
    canReceive: status === 'SHIPPED' && caps.canReceive === true,
    canUploadProof: status === 'PENDING_SUPERIOR' && caps.canUploadProof === true,
    canCancel: status === 'PENDING_SUPERIOR' && caps.canCancel === true,
    canSuperiorDecide: status === 'PENDING_SUPERIOR' && caps.canSuperiorDecide === true
  }
}

module.exports = {
  STATUS_TEXT,
  STATUS_TONE,
  statusText,
  statusTone,
  isKnownOrderStatus,
  resolveOrderActions
}
