export type PublishableRuleType =
  | 'SELF_ORDER_TASK'
  | 'DIRECT_REFERRAL_TASK'
  | 'DIRECT_REFERRAL_POINTS'
  | 'FROZEN_POINTS_RELEASE'
  | 'INACTIVITY_DOWNGRADE'

export type OrderTimerParameters = Readonly<{
  autoReceiveDays: number
  afterSaleDaysAfterCompletion: number
  pendingSuperiorTimeoutDays: number
  pendingAdminReviewTimeoutDays: number
  pendingShipmentTimeoutDays: number
  awaitingReturnTimeoutDays: number
  returnShippedTimeoutDays: number
  offlineRefundTimeoutDays: number
  buyerRefundConfirmTimeoutDays: number
  proofRetentionDays: number
  maxProofFiles: number
  maxProofSizeBytes: number
}>

export type OrderTimerParameterParse =
  | Readonly<{ ok: true; value: OrderTimerParameters; repaired?: boolean }>
  | Readonly<{ ok: false; error: string }>

export type RuleVersionLike = Readonly<{
  id: number
  ruleCode: string
  version: number
  ruleType: string
  parametersJson: string
  status: string
  effectiveFrom: string
  effectiveTo?: string | null
}>

export type RuleParameterParse =
  | Readonly<{ ok: true; value: Record<string, unknown>; repaired?: boolean }>
  | Readonly<{ ok: false; error: string }>

export type RuleBaseline =
  | Readonly<{ state: 'missing' }>
  | Readonly<{ state: 'valid'; rule: RuleVersionLike; parameters: Record<string, unknown> }>
  | Readonly<{ state: 'invalid'; rule?: RuleVersionLike; error: string }>

const MAX_SAFE_INTEGER = Number.MAX_SAFE_INTEGER
const ruleCodeTypes: Readonly<Record<string, PublishableRuleType | 'ORDER_TIMER'>> = {
  EXPERIENCE_OFFICER_UPGRADE: 'SELF_ORDER_TASK',
  SUPER_MEMBER_UPGRADE: 'SELF_ORDER_TASK',
  DIVIDEND_MEMBER_QUALIFICATION: 'DIRECT_REFERRAL_TASK',
  DIRECT_REFERRAL_POINTS: 'DIRECT_REFERRAL_POINTS',
  REPURCHASE_RELEASE: 'FROZEN_POINTS_RELEASE',
  DIVIDEND_INACTIVITY_DOWNGRADE: 'INACTIVITY_DOWNGRADE',
  ORDER_TIMERS: 'ORDER_TIMER'
}

export function isRuleCodeType(ruleCode: string, ruleType: string): boolean {
  return ruleCodeTypes[ruleCode.trim()] === ruleType
}

export function isRuleVersionList(value: unknown): value is RuleVersionLike[] {
  return Array.isArray(value) && value.every((item: unknown) => {
    if (!isParameterObject(item)) return false
    return typeof item.id === 'number'
      && Number.isSafeInteger(item.id)
      && typeof item.ruleCode === 'string'
      && typeof item.version === 'number'
      && Number.isSafeInteger(item.version)
      && typeof item.ruleType === 'string'
      && typeof item.parametersJson === 'string'
      && typeof item.status === 'string'
      && typeof item.effectiveFrom === 'string'
      && (item.effectiveTo === undefined || item.effectiveTo === null || typeof item.effectiveTo === 'string')
  })
}

function parameterObject(value: string): RuleParameterParse {
  if (!value.trim()) return { ok: false, error: '规则参数为空' }
  const lexicalError = strictJsonError(value)
  if (lexicalError) return { ok: false, error: lexicalError }
  try {
    const parsed: unknown = JSON.parse(value)
    if (!isParameterObject(parsed)) {
      return { ok: false, error: '规则参数必须是 JSON 对象' }
    }
    return { ok: true, value: parsed }
  } catch {
    return { ok: false, error: '规则参数不是合法 JSON' }
  }
}

function strictJsonError(value: string): string | undefined {
  let index = 0
  const whitespace = () => {
    while (index < value.length && /[\t\n\r ]/.test(value[index])) index += 1
  }
  const readString = (): string | undefined => {
    if (value[index] !== '"') return undefined
    const start = index
    index += 1
    while (index < value.length) {
      const current = value[index]
      if (current === '\\') {
        index += 2
        continue
      }
      if (current === '"') {
        index += 1
        try {
          return JSON.parse(value.slice(start, index)) as string
        } catch {
          return undefined
        }
      }
      index += 1
    }
    return undefined
  }
  const parseNumber = (): string | undefined => {
    const start = index
    if (value[index] === '-') index += 1
    if (value[index] === '0') {
      index += 1
    } else if (value[index] >= '1' && value[index] <= '9') {
      while (value[index] >= '0' && value[index] <= '9') index += 1
    } else {
      return '规则参数不是合法 JSON'
    }
    let nonInteger = false
    if (value[index] === '.') {
      nonInteger = true
      index += 1
      if (!(value[index] >= '0' && value[index] <= '9')) return '规则参数不是合法 JSON'
      while (value[index] >= '0' && value[index] <= '9') index += 1
    }
    if (value[index] === 'e' || value[index] === 'E') {
      nonInteger = true
      index += 1
      if (value[index] === '+' || value[index] === '-') index += 1
      if (!(value[index] >= '0' && value[index] <= '9')) return '规则参数不是合法 JSON'
      while (value[index] >= '0' && value[index] <= '9') index += 1
    }
    if (nonInteger) return '规则参数数字必须是整数'
    return value.slice(start, index) ? undefined : '规则参数不是合法 JSON'
  }
  const parseValue = (): string | undefined => {
    whitespace()
    const current = value[index]
    if (current === '{') return parseObject()
    if (current === '[') return parseArray()
    if (current === '"') return readString() === undefined ? '规则参数不是合法 JSON' : undefined
    if (current === '-' || (current >= '0' && current <= '9')) return parseNumber()
    for (const literal of ['true', 'false', 'null']) {
      if (value.startsWith(literal, index)) {
        index += literal.length
        return undefined
      }
    }
    return '规则参数不是合法 JSON'
  }
  const parseObject = (): string | undefined => {
    index += 1
    whitespace()
    const keys = new Set<string>()
    if (value[index] === '}') {
      index += 1
      return undefined
    }
    while (index < value.length) {
      whitespace()
      const key = readString()
      if (key === undefined) return '规则参数不是合法 JSON'
      if (keys.has(key)) return `规则参数包含重复字段：${key}`
      keys.add(key)
      whitespace()
      if (value[index] !== ':') return '规则参数不是合法 JSON'
      index += 1
      const error = parseValue()
      if (error) return error
      whitespace()
      if (value[index] === '}') {
        index += 1
        return undefined
      }
      if (value[index] !== ',') return '规则参数不是合法 JSON'
      index += 1
    }
    return '规则参数不是合法 JSON'
  }
  const parseArray = (): string | undefined => {
    index += 1
    whitespace()
    if (value[index] === ']') {
      index += 1
      return undefined
    }
    while (index < value.length) {
      const error = parseValue()
      if (error) return error
      whitespace()
      if (value[index] === ']') {
        index += 1
        return undefined
      }
      if (value[index] !== ',') return '规则参数不是合法 JSON'
      index += 1
    }
    return '规则参数不是合法 JSON'
  }

  const error = parseValue()
  if (error) return error
  whitespace()
  return index === value.length ? undefined : '规则参数不是合法 JSON'
}

function isParameterObject(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

function rejectUnknown(source: Record<string, unknown>, allowed: ReadonlySet<string>): string | undefined {
  return Object.keys(source).find(key => !allowed.has(key))
    ? `不支持的规则参数：${Object.keys(source).find(key => !allowed.has(key)) ?? ''}`
    : undefined
}

function boundedSafeInteger(
  source: Record<string, unknown>,
  key: string,
  minimum: number,
  maximum: number
): string | undefined {
  const value = source[key]
  if (typeof value !== 'number'
    || !Number.isFinite(value)
    || !Number.isSafeInteger(value)
    || value < minimum
    || value > maximum) {
    return `${key} 必须是 ${minimum} 到 ${maximum} 之间的安全整数`
  }
  return undefined
}

function positiveInteger(source: Record<string, unknown>, key: string, maximum = MAX_SAFE_INTEGER): string | undefined {
  return boundedSafeInteger(source, key, 1, maximum)
}

function nonNegativeInteger(source: Record<string, unknown>, key: string): string | undefined {
  return boundedSafeInteger(source, key, 0, MAX_SAFE_INTEGER)
}

function text(source: Record<string, unknown>, key: string): string | undefined {
  const value = source[key]
  if (typeof value !== 'string' || !value.trim()) return `${key} 不能为空`
  return undefined
}

function exactText(source: Record<string, unknown>, key: string, expected: string): string | undefined {
  const error = text(source, key)
  if (error) return error
  return typeof source[key] === 'string' && source[key].trim() === expected
    ? undefined
    : `${key} 当前仅支持 ${expected}`
}

function salesScenes(
  source: Record<string, unknown>,
  key: string,
  expected: 'UPGRADE' | 'REPURCHASE',
  allowMissing: boolean
): string | undefined {
  const value = source[key]
  if (value === undefined && allowMissing) return undefined
  if (!Array.isArray(value) || value.length !== 1 || value[0] !== expected) {
    return `${key} 当前仅支持 [${expected}]`
  }
  return undefined
}

function firstError(errors: Array<string | undefined>): string | undefined {
  return errors.find((value): value is string => Boolean(value))
}

function canonicalizeRuleParameters(
  source: Record<string, unknown>,
  ruleType: PublishableRuleType,
  persistedRead: boolean
): RuleParameterParse {
  switch (ruleType) {
    case 'SELF_ORDER_TASK': {
      const unknown = rejectUnknown(source, new Set(['minimumCompletedOrderAmountFen', 'eligibleSalesScenes', 'targetLevel']))
      const error = firstError([
        unknown,
        positiveInteger(source, 'minimumCompletedOrderAmountFen'),
        salesScenes(source, 'eligibleSalesScenes', 'UPGRADE', persistedRead),
        text(source, 'targetLevel')
      ])
      if (error) return { ok: false, error }
      const targetLevel = String(source.targetLevel).trim()
      return {
        ok: true,
        value: {
          minimumCompletedOrderAmountFen: source.minimumCompletedOrderAmountFen,
          eligibleSalesScenes: source.eligibleSalesScenes ?? ['UPGRADE'],
          targetLevel
        },
        repaired: source.eligibleSalesScenes === undefined
      }
    }
    case 'DIRECT_REFERRAL_TASK': {
      const unknown = rejectUnknown(source, new Set([
        'requiredCompletedDirectReferrals', 'minimumReferralOrderAmountFen',
        'eligibleSalesScenes', 'requiredReferralLevel', 'targetLevel'
      ]))
      const error = firstError([
        unknown,
        boundedSafeInteger(source, 'requiredCompletedDirectReferrals', 1, 100_000),
        positiveInteger(source, 'minimumReferralOrderAmountFen'),
        salesScenes(source, 'eligibleSalesScenes', 'UPGRADE', persistedRead),
        text(source, 'requiredReferralLevel'),
        text(source, 'targetLevel')
      ])
      if (error) return { ok: false, error }
      const requiredReferralLevel = String(source.requiredReferralLevel).trim()
      const targetLevel = String(source.targetLevel).trim()
      return {
        ok: true,
        value: {
          requiredCompletedDirectReferrals: source.requiredCompletedDirectReferrals,
          minimumReferralOrderAmountFen: source.minimumReferralOrderAmountFen,
          eligibleSalesScenes: source.eligibleSalesScenes ?? ['UPGRADE'],
          requiredReferralLevel,
          targetLevel
        },
        repaired: source.eligibleSalesScenes === undefined
      }
    }
    case 'DIRECT_REFERRAL_POINTS': {
      const unknown = rejectUnknown(source, new Set([
        'qualificationCount', 'pointsStartOrdinal', 'totalPoints',
        'availableAPoints', 'frozenBPoints', 'maxRewardDepth', 'eligibleSalesScenes'
      ]))
      if (unknown) return { ok: false, error: unknown }
      const legacyMinimal = source.qualificationCount === undefined
        && source.totalPoints === undefined
        && source.maxRewardDepth === undefined
        && source.eligibleSalesScenes === undefined
      if (legacyMinimal && !persistedRead) {
        return { ok: false, error: 'DIRECT_REFERRAL_POINTS 缺少规范必填参数' }
      }
      const missingCanonical = !legacyMinimal && [
        'qualificationCount', 'totalPoints', 'maxRewardDepth'
      ].find(key => source[key] === undefined)
      if (missingCanonical) return { ok: false, error: 'DIRECT_REFERRAL_POINTS 缺少必填参数' }
      const error = firstError([
        boundedSafeInteger(source, 'pointsStartOrdinal', legacyMinimal ? 1 : 2, 100_000),
        nonNegativeInteger(source, 'availableAPoints'),
        nonNegativeInteger(source, 'frozenBPoints'),
        legacyMinimal ? undefined : boundedSafeInteger(source, 'qualificationCount', 1, 100_000),
        legacyMinimal ? undefined : nonNegativeInteger(source, 'totalPoints'),
        legacyMinimal ? undefined : boundedSafeInteger(source, 'maxRewardDepth', 1, 1),
        salesScenes(source, 'eligibleSalesScenes', 'UPGRADE', persistedRead || legacyMinimal)
      ])
      if (error) return { ok: false, error }
      const qualificationCount = legacyMinimal
        ? Number(source.pointsStartOrdinal) - 1
        : Number(source.qualificationCount)
      const totalPoints = legacyMinimal
        ? Number(source.availableAPoints) + Number(source.frozenBPoints)
        : Number(source.totalPoints)
      const calculated = Number(source.availableAPoints) + Number(source.frozenBPoints)
      if (!Number.isSafeInteger(calculated) || calculated > MAX_SAFE_INTEGER || calculated <= 0) {
        return { ok: false, error: 'A/B 积分总量超出安全范围' }
      }
      if (!legacyMinimal && Number(source.pointsStartOrdinal) <= qualificationCount) {
        return { ok: false, error: 'pointsStartOrdinal 必须大于 qualificationCount' }
      }
      if (!Number.isSafeInteger(totalPoints) || totalPoints !== calculated || totalPoints <= 0) {
        return { ok: false, error: 'totalPoints 必须等于 A/B 积分之和且大于 0' }
      }
      return {
        ok: true,
        value: {
          qualificationCount,
          pointsStartOrdinal: source.pointsStartOrdinal,
          totalPoints,
          availableAPoints: source.availableAPoints,
          frozenBPoints: source.frozenBPoints,
          maxRewardDepth: legacyMinimal ? 1 : source.maxRewardDepth,
          eligibleSalesScenes: source.eligibleSalesScenes ?? ['UPGRADE']
        },
        repaired: legacyMinimal || source.eligibleSalesScenes === undefined
      }
    }
    case 'FROZEN_POINTS_RELEASE': {
      const unknown = rejectUnknown(source, new Set([
        'eligibleSalesScenes', 'minimumCompletedOrderAmountFen',
        'releaseMode', 'releasePointsPerOrder', 'batchOrder'
      ]))
      const error = firstError([
        unknown,
        salesScenes(source, 'eligibleSalesScenes', 'REPURCHASE', persistedRead),
        positiveInteger(source, 'minimumCompletedOrderAmountFen'),
        exactText(source, 'releaseMode', 'FIXED'),
        positiveInteger(source, 'releasePointsPerOrder'),
        exactText(source, 'batchOrder', 'FIFO')
      ])
      if (error) return { ok: false, error }
      return {
        ok: true,
        value: {
          eligibleSalesScenes: source.eligibleSalesScenes ?? ['REPURCHASE'],
          minimumCompletedOrderAmountFen: source.minimumCompletedOrderAmountFen,
          releaseMode: 'FIXED',
          releasePointsPerOrder: source.releasePointsPerOrder,
          batchOrder: 'FIFO'
        },
        repaired: source.eligibleSalesScenes === undefined
      }
    }
    case 'INACTIVITY_DOWNGRADE': {
      const unknown = rejectUnknown(source, new Set(['inactiveMonths', 'sourceLevel', 'targetLevel']))
      const error = firstError([
        unknown,
        boundedSafeInteger(source, 'inactiveMonths', 1, 60),
        text(source, 'sourceLevel'),
        text(source, 'targetLevel')
      ])
      if (error) return { ok: false, error }
      const sourceLevel = String(source.sourceLevel).trim()
      const targetLevel = String(source.targetLevel).trim()
      if (sourceLevel === targetLevel) return { ok: false, error: '降级前后等级不能相同' }
      return { ok: true, value: { inactiveMonths: source.inactiveMonths, sourceLevel, targetLevel } }
    }
  }
}

export function parseOrderTimerParameters(value: string): OrderTimerParameterParse {
  const parsed = parameterObject(value)
  if (!parsed.ok) return parsed
  const source = parsed.value
  const unknown = rejectUnknown(source, new Set([
    'autoReceiveDays', 'afterSaleDaysAfterCompletion',
    'pendingSuperiorTimeoutDays', 'pendingAdminReviewTimeoutDays',
    'pendingShipmentTimeoutDays', 'awaitingReturnTimeoutDays', 'returnShippedTimeoutDays',
    'offlineRefundTimeoutDays', 'buyerRefundConfirmTimeoutDays', 'proofRetentionDays',
    'maxProofFiles', 'maxProofSizeBytes'
  ]))
  const error = firstError([
    unknown,
    boundedSafeInteger(source, 'autoReceiveDays', 1, 365),
    boundedSafeInteger(source, 'afterSaleDaysAfterCompletion', 1, 365),
    boundedSafeInteger(source, 'pendingSuperiorTimeoutDays', 1, 365),
    boundedSafeInteger(source, 'pendingAdminReviewTimeoutDays', 1, 365),
    boundedSafeInteger(source, 'pendingShipmentTimeoutDays', 1, 365),
    boundedSafeInteger(source, 'awaitingReturnTimeoutDays', 1, 365),
    boundedSafeInteger(source, 'returnShippedTimeoutDays', 1, 365),
    boundedSafeInteger(source, 'offlineRefundTimeoutDays', 1, 365),
    boundedSafeInteger(source, 'buyerRefundConfirmTimeoutDays', 1, 365),
    boundedSafeInteger(source, 'proofRetentionDays', 1, 3650),
    boundedSafeInteger(source, 'maxProofFiles', 1, 20),
    boundedSafeInteger(source, 'maxProofSizeBytes', 1024, 20 * 1024 * 1024)
  ])
  if (error) return { ok: false, error }
  return { ok: true, value: {
    autoReceiveDays: source.autoReceiveDays as number,
    afterSaleDaysAfterCompletion: source.afterSaleDaysAfterCompletion as number,
    pendingSuperiorTimeoutDays: source.pendingSuperiorTimeoutDays as number,
    pendingAdminReviewTimeoutDays: source.pendingAdminReviewTimeoutDays as number,
    pendingShipmentTimeoutDays: source.pendingShipmentTimeoutDays as number,
    awaitingReturnTimeoutDays: source.awaitingReturnTimeoutDays as number,
    returnShippedTimeoutDays: source.returnShippedTimeoutDays as number,
    offlineRefundTimeoutDays: source.offlineRefundTimeoutDays as number,
    buyerRefundConfirmTimeoutDays: source.buyerRefundConfirmTimeoutDays as number,
    proofRetentionDays: source.proofRetentionDays as number,
    maxProofFiles: source.maxProofFiles as number,
    maxProofSizeBytes: source.maxProofSizeBytes as number
  } }
}

export function parsePersistedOrderTimerParameters(value: string): OrderTimerParameterParse {
  const parsed = parameterObject(value)
  if (!parsed.ok) return parsed
  const source = parsed.value
  if (source.autoReceiveDaysAfterShipment !== undefined) {
    if (source.autoReceiveDays !== undefined) {
      return { ok: false, error: 'autoReceiveDays 与 autoReceiveDaysAfterShipment 不能同时存在' }
    }
    source.autoReceiveDays = source.autoReceiveDaysAfterShipment
    delete source.autoReceiveDaysAfterShipment
  }
  const retention = source.proofRetentionDays
  if (typeof retention !== 'number' || !Number.isSafeInteger(retention) || retention < 1 || retention > 3650) {
    source.proofRetentionDays = 180
  }
  return parseOrderTimerParameters(JSON.stringify(source))
}

export function parseRuleParameters(value: string, ruleType: PublishableRuleType): RuleParameterParse {
  const parsed = parameterObject(value)
  if (!parsed.ok) return parsed
  return canonicalizeRuleParameters(parsed.value, ruleType, false)
}

export function parsePersistedRuleParameters(value: string, ruleType: PublishableRuleType): RuleParameterParse {
  const parsed = parameterObject(value)
  if (!parsed.ok) return parsed
  return canonicalizeRuleParameters(parsed.value, ruleType, true)
}

export function parseRuleParameterObject(value: string): RuleParameterParse {
  return parameterObject(value)
}

function effectiveAt(rule: RuleVersionLike, now: number): boolean {
  const from = Date.parse(rule.effectiveFrom)
  const to = rule.effectiveTo ? Date.parse(rule.effectiveTo) : Number.POSITIVE_INFINITY
  return Number.isFinite(from)
    && (!rule.effectiveTo || Number.isFinite(to))
    && from <= now
    && to > now
}

export function resolveRuleBaseline(
  rules: readonly RuleVersionLike[],
  ruleCode: string,
  ruleType: PublishableRuleType,
  now = Date.now()
): RuleBaseline {
  const versions = rules.filter(rule => rule.ruleCode === ruleCode)
  if (versions.length === 0) return { state: 'missing' }
  const current = versions
    .filter(rule => rule.status === 'ACTIVE' && effectiveAt(rule, now))
    .sort((left, right) => right.version - left.version)[0]
  if (!current) {
    return { state: 'invalid', error: '服务端已有规则版本，但当前生效版本不存在' }
  }
  if (!isRuleCodeType(current.ruleCode, ruleType) || current.ruleType !== ruleType) {
    return { state: 'invalid', rule: current, error: '当前规则编码与类型不匹配' }
  }
  const parsed = parsePersistedRuleParameters(current.parametersJson, ruleType)
  return parsed.ok
    ? { state: 'valid', rule: current, parameters: parsed.value }
    : { state: 'invalid', rule: current, error: parsed.error }
}

export function verifyPublishedRuleReadback(
  rules: readonly RuleVersionLike[],
  published: RuleVersionLike,
  expectedType: PublishableRuleType
): RuleParameterParse {
  const readback = rules.find(rule => rule.id === published.id)
  if (!readback) return { ok: false, error: '已提交版本未在服务端回读结果中找到' }
  if (!isRuleCodeType(readback.ruleCode, expectedType)
    || readback.ruleCode !== published.ruleCode
    || readback.ruleType !== expectedType) {
    return { ok: false, error: '已提交版本的服务端回读身份不匹配' }
  }
  return parseRuleParameters(readback.parametersJson, expectedType)
}
