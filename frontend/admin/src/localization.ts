export type SelectOption = Readonly<{ value: string; label: string }>

function toMap(options: readonly SelectOption[]): Readonly<Record<string, string>> {
  return Object.fromEntries(options.map(option => [option.value, option.label])) as Record<string, string>
}

export function labelOf(
  labels: Readonly<Record<string, string>>,
  value: string | null | undefined,
  unknownLabel: string
) {
  if (!value) return '未记录'
  return labels[value] ?? unknownLabel
}

export const orderStatusOptions = [
  { value: 'PENDING_SUPERIOR', label: '待直属上级确认' },
  { value: 'PENDING_ADMIN_REVIEW', label: '待后台审核' },
  { value: 'PENDING_SHIPMENT', label: '待发货' },
  { value: 'SHIPPED', label: '已发货' },
  { value: 'COMPLETED', label: '已完成' },
  { value: 'CANCELLED', label: '已取消' },
  { value: 'SUPERIOR_REJECTED', label: '直属上级已拒绝' },
  { value: 'ADMIN_REJECTED', label: '后台已拒绝' }
] as const satisfies readonly SelectOption[]
export const orderStatusText = toMap(orderStatusOptions)
export const orderStatusLabel = (value?: string) => labelOf(orderStatusText, value, '未知订单状态')
export const isKnownOrderStatus = (value?: string) =>
  typeof value === 'string' && Object.hasOwn(orderStatusText, value)

export const afterSaleStatusOptions = [
  { value: 'PENDING_ADMIN_REVIEW', label: '待后台审核' },
  { value: 'AWAITING_RETURN', label: '待用户回寄' },
  { value: 'RETURN_SHIPPED', label: '用户已回寄' },
  { value: 'PENDING_OFFLINE_REFUND', label: '待线下退款' },
  { value: 'PENDING_BUYER_REFUND_CONFIRMATION', label: '待用户确认退款' },
  { value: 'COMPLETED', label: '已完成' },
  { value: 'REJECTED', label: '已拒绝' },
  { value: 'CANCELLED', label: '已撤销' }
] as const satisfies readonly SelectOption[]
export const afterSaleStatusText = toMap(afterSaleStatusOptions)
export const afterSaleStatusLabel = (value?: string) => labelOf(afterSaleStatusText, value, '未知售后状态')

export const memberStatusOptions = [
  { value: 'ACTIVE', label: '正常' },
  { value: 'DISABLED', label: '已停用' },
  { value: 'LOCKED', label: '已锁定' }
] as const satisfies readonly SelectOption[]
export const memberStatusText = toMap(memberStatusOptions)
export const memberStatusLabel = (value?: string) => labelOf(memberStatusText, value, '未知会员状态')

export const accountStatusOptions = [
  { value: 'ACTIVE', label: '启用' },
  { value: 'DISABLED', label: '停用' }
] as const satisfies readonly SelectOption[]
export const accountStatusText = toMap(accountStatusOptions)
export const accountStatusLabel = (value?: string) => labelOf(accountStatusText, value, '未知账号状态')

export const memberLevelOptions = [
  { value: 'BASIC', label: '基础会员' },
  { value: 'EXPERIENCE_OFFICER', label: '体验官' },
  { value: 'SUPER_MEMBER', label: '超级会员' },
  { value: 'DIVIDEND_MEMBER', label: '分红会员' }
] as const satisfies readonly SelectOption[]
export const memberLevelText = toMap(memberLevelOptions)
export const memberLevelLabel = (value?: string) => labelOf(memberLevelText, value, '未知会员等级')

export const salesSceneOptions = [
  { value: 'UPGRADE', label: '升级专区' },
  { value: 'REPURCHASE', label: '复购专区' }
] as const satisfies readonly SelectOption[]
export const salesSceneText = toMap(salesSceneOptions)
export const salesSceneLabel = (value?: string) => labelOf(salesSceneText, value, '未知销售场景')

export const catalogStatusOptions = [
  { value: 'ON_SALE', label: '在售' },
  { value: 'OFF_SALE', label: '已下架' }
] as const satisfies readonly SelectOption[]
export const catalogStatusText = toMap(catalogStatusOptions)
export const catalogStatusLabel = (value?: string) => labelOf(catalogStatusText, value, '未知商品状态')

export const enableStatusOptions = [
  { value: 'ACTIVE', label: '启用' },
  { value: 'DISABLED', label: '停用' }
] as const satisfies readonly SelectOption[]
export const enableStatusText = toMap(enableStatusOptions)
export const enableStatusLabel = (value?: string) => labelOf(enableStatusText, value, '未知启用状态')

export const contentTypeOptions = [
  { value: 'BANNER', label: '首页横幅' },
  { value: 'ANNOUNCEMENT', label: '商城公告' },
  { value: 'QUICK_ENTRY', label: '快捷入口' },
  { value: 'HELP', label: '帮助内容' }
] as const satisfies readonly SelectOption[]
export const contentTypeText = toMap(contentTypeOptions)
export const contentTypeLabel = (value?: string) => labelOf(contentTypeText, value, '未知内容类型')

export const contentStatusOptions = [
  { value: 'DRAFT', label: '草稿' },
  { value: 'PUBLISHED', label: '已发布' },
  { value: 'OFFLINE', label: '已下线' }
] as const satisfies readonly SelectOption[]
export const contentStatusText = toMap(contentStatusOptions)
export const contentStatusLabel = (value?: string) => labelOf(contentStatusText, value, '未知内容状态')

export const ruleTypeOptions = [
  { value: 'SELF_ORDER_TASK', label: '个人订单升级任务' },
  { value: 'DIRECT_REFERRAL_TASK', label: '直属推荐升级任务' },
  { value: 'DIRECT_REFERRAL_POINTS', label: '直属推荐积分' },
  { value: 'FROZEN_POINTS_RELEASE', label: '冻结积分释放' },
  { value: 'INACTIVITY_DOWNGRADE', label: '连续无业绩降级' },
  { value: 'ORDER_TIMER', label: '订单与凭证时限' }
] as const satisfies readonly SelectOption[]
export const ruleTypeText = toMap(ruleTypeOptions)
export const ruleTypeLabel = (value?: string) => labelOf(ruleTypeText, value, '未知规则类型')

const ruleStatusText: Readonly<Record<string, string>> = {
  ACTIVE: '生效中',
  CANCELLED: '已取消',
  REVOKED: '已撤销',
  EXPIRED: '已失效'
}
export const ruleStatusLabel = (value?: string) => labelOf(ruleStatusText, value, '未知规则状态')

const afterSaleTypeText: Readonly<Record<string, string>> = {
  RETURN_REFUND: '退货并线下退款',
  REFUND_ONLY: '仅线下退款'
}
export const afterSaleTypeLabel = (value?: string) => labelOf(afterSaleTypeText, value, '未知售后类型')

const proofTypeText: Readonly<Record<string, string>> = {
  APPLICATION: '申请凭证',
  RETURN: '退货凭证',
  REFUND: '退款凭证'
}
export const proofTypeLabel = (value?: string) => labelOf(proofTypeText, value, '其他售后凭证')

const mediaTypeText: Readonly<Record<string, string>> = {
  'image/jpeg': '图片（JPEG）',
  'image/png': '图片（PNG）',
  'image/webp': '图片（WebP）'
}
export const mediaTypeLabel = (value?: string) => labelOf(mediaTypeText, value, '图片文件')

const evidenceTypeText: Readonly<Record<string, string>> = {
  SELF_ORDER_COMPLETED: '个人有效订单完成'
}
export const evidenceTypeLabel = (value?: string) => labelOf(evidenceTypeText, value, '其他任务证据')

const evidenceStatusText: Readonly<Record<string, string>> = {
  ACTIVE: '有效',
  INVALID: '已失效',
  REVERSED: '已冲正'
}
export const evidenceStatusLabel = (value?: string) => labelOf(evidenceStatusText, value, '未知证据状态')

const levelTriggerText: Readonly<Record<string, string>> = {
  ORDER_COMPLETED: '订单完成',
  DIRECT_REFERRAL_QUALIFIED: '直属推荐达标',
  INACTIVITY_DOWNGRADE: '连续无业绩降级',
  ADMIN_RECOMPUTE: '后台资格重算',
  AFTERSALE: '售后冲正'
}
export const levelTriggerLabel = (value?: string) => labelOf(levelTriggerText, value, '其他等级变更')

const ledgerEntryText: Readonly<Record<string, string>> = {
  DIRECT_REFERRAL_AWARD: '直属推荐积分奖励',
  FROZEN_POINTS_RELEASED: '复购释放冻结积分',
  REVERSAL: '售后积分冲正'
}
export const ledgerEntryLabel = (value?: string) => labelOf(ledgerEntryText, value, '其他积分变动')

const roleText: Readonly<Record<string, string>> = {
  SUPER_ADMIN: '超级管理员',
  ORDER_REVIEWER: '订单审核员',
  FULFILLMENT_OPERATOR: '履约发货员',
  CATALOG_OPERATOR: '商品运营员',
  MEMBER_OPERATOR: '会员运营员',
  AUDIT_VIEWER: '审计查看员'
}

export function roleLabel(code: string, roles: readonly { code: string; name: string }[] = []) {
  return roles.find(role => role.code === code)?.name ?? roleText[code] ?? '自定义角色'
}

const permissionText: Readonly<Record<string, string>> = {
  'admin:account:manage': '后台账号管理',
  'admin:role:manage': '角色权限管理',
  'order:read': '查看订单',
  'order:review': '审核订单',
  'order:ship': '订单发货',
  'order:audit': '订单凭证审计',
  'catalog:read': '查看商品',
  'catalog:write': '维护商品',
  'member:read': '查看会员',
  'member:write': '维护会员',
  'rule:publish': '发布规则',
  'aftersale:review': '审核售后',
  'audit:read': '查看审计日志',
  'content:write': '内容运营',
  'notification:read': '查看通知',
  'system:setting:manage': '管理系统配置',
  'outbox:read': '查看 Outbox 死信',
  'outbox:replay': '重放 Outbox 死信'
}
export const permissionLabel = (value?: string) => labelOf(permissionText, value, '其他权限')

export const auditActorOptions = [
  { value: 'ADMIN', label: '后台管理员' },
  { value: 'USER', label: '商城用户' },
  { value: 'SYSTEM', label: '系统任务' }
] as const satisfies readonly SelectOption[]
const auditActorText = toMap(auditActorOptions)
export const auditActorLabel = (value?: string) => labelOf(auditActorText, value, '未知操作主体')

const auditActionText: Readonly<Record<string, string>> = {
  ADMIN_ACCOUNT_CREATED: '创建后台账号',
  ADMIN_PASSWORD_CHANGED: '管理员修改密码',
  ADMIN_PASSWORD_RESET: '重置后台账号密码',
  ADMIN_STATUS_CHANGED: '修改后台账号状态',
  ADMIN_UNLOCKED: '解锁后台账号',
  ADMIN_ROLES_CHANGED: '调整后台账号角色',
  ADMIN_LINKED_USER_CHANGED: '调整关联会员',
  ADMIN_ROLE_SAVED: '保存自定义角色',
  ADMIN_ROLE_DELETED: '删除自定义角色',
  OUTBOX_DEAD_LETTER_REPLAYED: '重放 Outbox 死信',
  MEMBER_STATUS_UPDATED: '修改会员状态',
  MEMBER_LEVEL_RECOMPUTED: '重新计算会员等级',
  PROOF_UPLOAD: '上传付款凭证',
  PROOF_LIST: '查看付款凭证列表',
  PROOF_DOWNLOAD: '下载付款凭证',
  PROOF_DELETE: '删除付款凭证',
  AFTERSALE_PROOF_UPLOAD: '上传售后凭证',
  AFTERSALE_PROOF_LIST: '查看售后凭证列表',
  AFTERSALE_PROOF_DOWNLOAD: '下载售后凭证',
  AFTERSALE_PROOF_DELETE: '删除售后凭证',
  CATALOG_ASSET_UPLOADED: '上传商品素材',
  CATALOG_ASSET_DELETED: '删除商品素材',
  OPERATION_SETTINGS_CHANGED: '修改运营配置'
}

const httpActionText: Readonly<Record<string, string>> = {
  GET: '查询',
  POST: '新增或执行',
  PUT: '修改',
  PATCH: '部分修改',
  DELETE: '删除'
}

export function auditActionLabel(value?: string) {
  if (!value) return '未记录'
  if (auditActionText[value]) return auditActionText[value]
  const method = value.split(' ', 1)[0]
  return httpActionText[method] ? `${httpActionText[method]}后台资源` : '其他后台操作'
}

const auditResourceText: Readonly<Record<string, string>> = {
  ORDER: '订单',
  AFTERSALE: '售后单',
  CATALOG: '商品目录',
  RULE: '运营规则',
  ADMIN_ACCOUNT: '后台账号',
  ADMIN_ROLE: '后台角色',
  MEMBER: '会员',
  ORDER_PROOF: '付款凭证',
  AFTERSALE_PROOF: '售后凭证',
  CATALOG_ASSET: '商品素材',
  OPERATION_SETTINGS: '运营配置',
  AUDIT: '审计日志',
  ADMIN_RESOURCE: '后台资源'
}
export const auditResourceLabel = (value?: string) => labelOf(auditResourceText, value, '其他后台资源')

const ruleParameterText: Readonly<Record<string, string>> = {
  minimumCompletedOrderAmountFen: '完成订单金额门槛',
  eligibleSalesScenes: '适用销售场景',
  targetLevel: '目标等级',
  requiredCompletedDirectReferrals: '有效直属人数',
  minimumReferralOrderAmountFen: '新会员订单门槛',
  requiredReferralLevel: '新会员所需等级',
  qualificationCount: '资格人数',
  pointsStartOrdinal: '开始计分序号',
  totalPoints: '总积分',
  availableAPoints: 'A 池可用积分',
  frozenBPoints: 'B 池冻结积分',
  maxRewardDepth: '最大奖励层级',
  releaseMode: '释放方式',
  releasePointsPerOrder: '每单释放积分',
  batchOrder: '释放顺序',
  inactiveMonths: '连续无业绩月数',
  sourceLevel: '原等级',
  autoReceiveDaysAfterShipment: '发货后自动收货天数',
  afterSaleDaysAfterCompletion: '订单完成后售后天数',
  pendingSuperiorTimeoutDays: '待上级确认超时天数',
  pendingAdminReviewTimeoutDays: '待后台审核超时天数',
  pendingShipmentTimeoutDays: '待发货超时天数',
  proofRetentionDays: '凭证保留天数',
  maxProofFiles: '单据最大凭证数',
  maxProofSizeBytes: '单张凭证大小上限'
}

export function ruleParameterLabel(value: string) {
  return ruleParameterText[value] ?? '其他规则参数'
}

export function ruleParameterValue(key: string, value: unknown): string {
  if (typeof value === 'string') {
    if (memberLevelText[value]) return memberLevelText[value]
    if (salesSceneText[value]) return salesSceneText[value]
    if (value === 'FIXED') return '固定积分'
    if (value === 'FIFO') return '先进先出'
    return value
  }
  if (Array.isArray(value)) return value.map(item => ruleParameterValue(key, item)).join('、')
  if (typeof value !== 'number') return String(value ?? '未记录')
  if (key.endsWith('AmountFen')) return `¥${(value / 100).toLocaleString('zh-CN', { minimumFractionDigits: 2 })}`
  if (key === 'maxProofSizeBytes') return `${(value / 1024 / 1024).toFixed(0)} MB`
  if (key.endsWith('Days') || key.includes('DaysAfter')) return `${value} 天`
  if (key === 'inactiveMonths') return `${value} 个月`
  if (key === 'qualificationCount' || key === 'requiredCompletedDirectReferrals') return `${value} 人`
  if (key === 'pointsStartOrdinal') return `第 ${value} 人`
  if (key === 'maxRewardDepth') return `${value} 层`
  return value.toLocaleString('zh-CN')
}
