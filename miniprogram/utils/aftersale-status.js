const AFTERSALE_STATUS_TEXT = {
  PENDING_ADMIN_REVIEW: '待后台审核',
  AWAITING_RETURN: '待用户回寄',
  RETURN_SHIPPED: '用户已回寄',
  PENDING_OFFLINE_REFUND: '待线下退款',
  PENDING_BUYER_REFUND_CONFIRMATION: '待用户确认退款',
  COMPLETED: '已完成',
  REJECTED: '已拒绝',
  CANCELLED: '已撤销'
}

const AFTERSALE_STATUS_TONE = {
  PENDING_ADMIN_REVIEW: 'coral',
  AWAITING_RETURN: 'coral',
  RETURN_SHIPPED: 'gold',
  PENDING_OFFLINE_REFUND: 'gold',
  PENDING_BUYER_REFUND_CONFIRMATION: 'coral',
  COMPLETED: 'green',
  REJECTED: 'danger',
  CANCELLED: 'danger'
}

const AFTERSALE_TYPE_TEXT = {
  REFUND_ONLY: '仅退款',
  RETURN_REFUND: '退货退款'
}

function aftersaleStatusText(status) {
  return AFTERSALE_STATUS_TEXT[status] || '未知售后状态'
}

function aftersaleStatusTone(status) {
  return AFTERSALE_STATUS_TONE[status] || 'muted'
}

function isKnownAftersaleStatus(status) {
  return Object.prototype.hasOwnProperty.call(AFTERSALE_STATUS_TEXT, status)
}

function resolveAftersaleActions(view, isApplicant, isSuperior) {
  const current = view || {}
  const status = current.status
  if (!isKnownAftersaleStatus(status)) {
    return {
      canUploadProof: false,
      canReturnShip: false,
      canConfirmRefund: false,
      canConfirmOffline: false,
      canCancel: false
    }
  }
  const applicant = isApplicant === true
  const superior = isSuperior === true
  const terminal = ['COMPLETED', 'REJECTED', 'CANCELLED'].indexOf(status) >= 0
  return {
    canUploadProof: applicant && !terminal,
    canReturnShip: applicant && status === 'AWAITING_RETURN' && current.type === 'RETURN_REFUND',
    canConfirmRefund: applicant && status === 'PENDING_BUYER_REFUND_CONFIRMATION',
    canConfirmOffline: superior && status === 'PENDING_OFFLINE_REFUND',
    canCancel: applicant && ['PENDING_ADMIN_REVIEW', 'AWAITING_RETURN'].indexOf(status) >= 0
  }
}

module.exports = {
  AFTERSALE_STATUS_TEXT,
  AFTERSALE_STATUS_TONE,
  AFTERSALE_TYPE_TEXT,
  aftersaleStatusText,
  aftersaleStatusTone,
  isKnownAftersaleStatus,
  resolveAftersaleActions
}
