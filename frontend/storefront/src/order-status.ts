export const statusText: Readonly<Record<string, string>> = {
  PENDING_SUPERIOR: '待上级确认',
  SUPERIOR_REJECTED: '上级已拒绝',
  PENDING_ADMIN_REVIEW: '待后台审核',
  ADMIN_REJECTED: '后台已拒绝',
  PENDING_SHIPMENT: '待发货',
  SHIPPED: '已发货',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
  AWAITING_RETURN: '待回寄',
  RETURN_SHIPPED: '已回寄',
  PENDING_OFFLINE_REFUND: '待上级线下退款',
  PENDING_BUYER_REFUND_CONFIRMATION: '待买家确认退款',
  REJECTED: '已驳回'
}

export const orderStatusLabel = (status: string) => statusText[status] ?? '未知订单状态'
