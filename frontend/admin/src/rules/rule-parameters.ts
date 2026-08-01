export type PublishableRuleType =
  | 'SELF_ORDER_TASK'
  | 'DIRECT_REFERRAL_TASK'
  | 'DIRECT_REFERRAL_POINTS'
  | 'FROZEN_POINTS_RELEASE'
  | 'INACTIVITY_DOWNGRADE'

/**
 * ORDER_TIMER is edited from the system-settings page rather than the
 * general rule publisher.  Keep its parser here nevertheless so both entry
 * points apply the same server contract and a corrupt active version cannot
 * be mistaken for a usable baseline.
 */
export type OrderTimerParameters = Readonly<{
  autoReceiveDaysAfterShipment: number
  afterSaleDaysAfterCompletion: number
  proofRetentionDays: number
  maxProofFiles: number
  maxProofSizeBytes: number
}>

export type OrderTimerParameterParse =
  | Readonly<{ ok: true; value: OrderTimerParameters }>
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
  | Readonly<{ ok: true; value: Record<string, unknown> }>
  | Readonly<{ ok: false; error: string }>

export type RuleBaseline =
  | Readonly<{ state: 'missing' }>
  | Readonly<{ state: 'valid'; rule: RuleVersionLike; parameters: Record<string, unknown> }>
  | Readonly<{ state: 'invalid'; rule?: RuleVersionLike; error: string }>

export function isRuleVersionList(value: unknown): value is RuleVersionLike[] {
  return Array.isArray(value) && value.every((item: unknown) => {
    if (!isParameterObject(item)) return false
    return typeof item.id === 'number'
      && typeof item.ruleCode === 'string'
      && typeof item.version === 'number'
      && typeof item.ruleType === 'string'
      && typeof item.parametersJson === 'string'
      && typeof item.status === 'string'
      && typeof item.effectiveFrom === 'string'
      && (item.effectiveTo === undefined || item.effectiveTo === null || typeof item.effectiveTo === 'string')
  })
}

function parameterObject(value: string): RuleParameterParse {
  if (!value.trim()) return { ok: false, error: '规则参数为空' }
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

function isParameterObject(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

function positiveInteger(source: Record<string, unknown>, key: string, maximum = Number.MAX_SAFE_INTEGER): string | undefined {
  const value = source[key]
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value <= 0 || value > maximum) {
    return `${key} 必须是大于 0 的整数`
  }
  return undefined
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

function nonNegativeInteger(source: Record<string, unknown>, key: string): string | undefined {
  const value = source[key]
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0) {
    return `${key} 必须是非负整数`
  }
  return undefined
}

function text(source: Record<string, unknown>, key: string): string | undefined {
  const value = source[key]
  if (typeof value !== 'string' || !value.trim()) return `${key} 不能为空`
  return undefined
}

function optionalEquals(
  source: Record<string, unknown>,
  key: string,
  expected: string
): string | undefined {
  const value = source[key]
  if (value !== undefined && value !== expected) return `${key} 当前仅支持 ${expected}`
  return undefined
}

function firstError(errors: Array<string | undefined>): string | undefined {
  return errors.find((value): value is string => Boolean(value))
}

/**
 * Validate the immutable ORDER_TIMERS payload against the backend's exact
 * bounds.  In particular, JSON numbers are checked as finite safe integers;
 * merely checking `typeof value === 'number'` would allow NaN-like UI state,
 * fractions, and values which lose precision when sent to Java.
 */
export function parseOrderTimerParameters(value: string): OrderTimerParameterParse {
  const parsed = parameterObject(value)
  if (!parsed.ok) return parsed
  const source = parsed.value
  const error = firstError([
    boundedSafeInteger(source, 'autoReceiveDaysAfterShipment', 1, 365),
    boundedSafeInteger(source, 'afterSaleDaysAfterCompletion', 1, 365),
    boundedSafeInteger(source, 'proofRetentionDays', 1, 3650),
    boundedSafeInteger(source, 'maxProofFiles', 1, 20),
    boundedSafeInteger(source, 'maxProofSizeBytes', 1024, 20 * 1024 * 1024)
  ])
  if (error) return { ok: false, error }
  return {
    ok: true,
    value: {
      autoReceiveDaysAfterShipment: Number(source.autoReceiveDaysAfterShipment),
      afterSaleDaysAfterCompletion: Number(source.afterSaleDaysAfterCompletion),
      proofRetentionDays: Number(source.proofRetentionDays),
      maxProofFiles: Number(source.maxProofFiles),
      maxProofSizeBytes: Number(source.maxProofSizeBytes)
    }
  }
}

export function parseRuleParameters(value: string, ruleType: PublishableRuleType): RuleParameterParse {
  const parsed = parameterObject(value)
  if (!parsed.ok) return parsed
  const source = parsed.value
  let error: string | undefined
  switch (ruleType) {
    case 'SELF_ORDER_TASK':
      error = firstError([
        positiveInteger(source, 'minimumCompletedOrderAmountFen'),
        text(source, 'targetLevel')
      ])
      break
    case 'DIRECT_REFERRAL_TASK':
      error = firstError([
        positiveInteger(source, 'requiredCompletedDirectReferrals', 100_000),
        positiveInteger(source, 'minimumReferralOrderAmountFen'),
        text(source, 'requiredReferralLevel'),
        text(source, 'targetLevel')
      ])
      break
    case 'DIRECT_REFERRAL_POINTS': {
      error = firstError([
        positiveInteger(source, 'pointsStartOrdinal', 100_000),
        nonNegativeInteger(source, 'availableAPoints'),
        nonNegativeInteger(source, 'frozenBPoints')
      ])
      if (!error) {
        const total = Number(source.availableAPoints) + Number(source.frozenBPoints)
        if (!Number.isSafeInteger(total)) {
          error = 'A/B 积分总量超出安全范围'
        } else if (total <= 0) {
          error = 'A/B 积分至少一项必须大于 0'
        }
      }
      break
    }
    case 'FROZEN_POINTS_RELEASE':
      error = firstError([
        positiveInteger(source, 'minimumCompletedOrderAmountFen'),
        positiveInteger(source, 'releasePointsPerOrder'),
        optionalEquals(source, 'releaseMode', 'FIXED'),
        optionalEquals(source, 'batchOrder', 'FIFO')
      ])
      break
    case 'INACTIVITY_DOWNGRADE':
      error = firstError([
        positiveInteger(source, 'inactiveMonths', 60),
        text(source, 'sourceLevel'),
        text(source, 'targetLevel')
      ])
      if (!error && source.sourceLevel === source.targetLevel) error = '降级前后等级不能相同'
      break
  }
  return error ? { ok: false, error } : parsed
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
  if (current.ruleType !== ruleType) {
    return { state: 'invalid', rule: current, error: '当前规则类型与编辑器不匹配' }
  }
  const parsed = parseRuleParameters(current.parametersJson, ruleType)
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
  if (readback.ruleCode !== published.ruleCode || readback.ruleType !== expectedType) {
    return { ok: false, error: '已提交版本的服务端回读身份不匹配' }
  }
  return parseRuleParameters(readback.parametersJson, expectedType)
}
