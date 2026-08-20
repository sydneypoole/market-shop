import assert from 'node:assert/strict'
import test from 'node:test'

import {
  isRuleVersionList,
  parseOrderTimerParameters,
  parsePersistedOrderTimerParameters,
  parsePersistedRuleParameters,
  parseRuleParameters,
  resolveRuleBaseline,
  verifyPublishedRuleReadback
} from '../src/rules/rule-parameters.ts'

const now = Date.parse('2026-08-01T00:00:00Z')

const version = (parametersJson, overrides = {}) => ({
  id: 31,
  ruleCode: 'DIRECT_REFERRAL_POINTS',
  version: 2,
  ruleType: 'DIRECT_REFERRAL_POINTS',
  parametersJson,
  status: 'ACTIVE',
  effectiveFrom: '2026-07-01T00:00:00Z',
  effectiveTo: null,
  ...overrides
})

const validPoints = JSON.stringify({
  qualificationCount: 5,
  pointsStartOrdinal: 6,
  totalPoints: 320,
  availableAPoints: 160,
  frozenBPoints: 160,
  maxRewardDepth: 1,
  eligibleSalesScenes: ['UPGRADE']
})

test('current versions with invalid JSON, arrays, or missing required fields fail closed', () => {
  const invalid = ['{broken', '[]', '{"pointsStartOrdinal":6,"availableAPoints":160}']
  for (const parametersJson of invalid) {
    assert.equal(parseRuleParameters(parametersJson, 'DIRECT_REFERRAL_POINTS').ok, false)
    const baseline = resolveRuleBaseline([version(parametersJson)], 'DIRECT_REFERRAL_POINTS', 'DIRECT_REFERRAL_POINTS', now)
    assert.equal(baseline.state, 'invalid')
  }
})

test('current release rules with unsupported safety modes fail closed', () => {
  const unsafe = JSON.stringify({
    eligibleSalesScenes: ['REPURCHASE'],
    minimumCompletedOrderAmountFen: 199800,
    releasePointsPerOrder: 160,
    releaseMode: 'PERCENTAGE',
    batchOrder: 'LIFO'
  })

  assert.equal(parseRuleParameters(unsafe, 'FROZEN_POINTS_RELEASE').ok, false)
  assert.equal(
    resolveRuleBaseline([
      version(unsafe, {
        ruleCode: 'REPURCHASE_RELEASE',
        ruleType: 'FROZEN_POINTS_RELEASE'
      })
    ], 'REPURCHASE_RELEASE', 'FROZEN_POINTS_RELEASE', now).state,
    'invalid'
  )
})

test('the rule-list boundary rejects non-arrays and malformed version rows', () => {
  assert.equal(isRuleVersionList({}), false)
  assert.equal(isRuleVersionList([{ id: 1 }]), false)
  assert.equal(isRuleVersionList([version(validPoints)]), true)
})

test('safe defaults are allowed only when the server explicitly has no version for the code', () => {
  assert.deepEqual(
    resolveRuleBaseline([], 'DIRECT_REFERRAL_POINTS', 'DIRECT_REFERRAL_POINTS', now),
    { state: 'missing' }
  )
  const future = version(validPoints, { effectiveFrom: '2026-09-01T00:00:00Z' })
  assert.equal(
    resolveRuleBaseline([future], 'DIRECT_REFERRAL_POINTS', 'DIRECT_REFERRAL_POINTS', now).state,
    'invalid'
  )
})

test('a valid current version unlocks hydration from the authoritative parameters', () => {
  const baseline = resolveRuleBaseline(
    [version(validPoints)],
    'DIRECT_REFERRAL_POINTS',
    'DIRECT_REFERRAL_POINTS',
    now
  )
  assert.equal(baseline.state, 'valid')
  assert.equal(baseline.state === 'valid' && baseline.rule.id, 31)
})

test('publication readback must contain the committed version with a valid parameter object', () => {
  const published = version(validPoints)
  assert.equal(verifyPublishedRuleReadback([], published, 'DIRECT_REFERRAL_POINTS').ok, false)
  assert.equal(
    verifyPublishedRuleReadback([version('[]')], published, 'DIRECT_REFERRAL_POINTS').ok,
    false
  )
  assert.equal(
    verifyPublishedRuleReadback([published], published, 'DIRECT_REFERRAL_POINTS').ok,
    true
  )
})

test('ORDER_TIMER parameters use safe integers and the backend bounds', () => {
  const valid = JSON.stringify({
    autoReceiveDays: 365,
    afterSaleDaysAfterCompletion: 1,
    pendingSuperiorTimeoutDays: 7,
    pendingAdminReviewTimeoutDays: 7,
    pendingShipmentTimeoutDays: 7,
    awaitingReturnTimeoutDays: 15,
    returnShippedTimeoutDays: 15,
    offlineRefundTimeoutDays: 7,
    buyerRefundConfirmTimeoutDays: 7,
    proofRetentionDays: 3650,
    maxProofFiles: 20,
    maxProofSizeBytes: 1024
  })
  assert.equal(parseOrderTimerParameters(valid).ok, true)
  assert.equal(parseOrderTimerParameters(JSON.stringify({
    autoReceiveDays: 7,
    afterSaleDaysAfterCompletion: 7,
    proofRetentionDays: 30,
    maxProofFiles: 5,
    maxProofSizeBytes: 5 * 1024 * 1024
  })).ok, false)

  for (const [key, value] of [
    ['autoReceiveDays', 0],
    ['autoReceiveDays', 366],
    ['afterSaleDaysAfterCompletion', 365.5],
    ['pendingSuperiorTimeoutDays', 0],
    ['pendingAdminReviewTimeoutDays', 366],
    ['pendingShipmentTimeoutDays', 1.5],
    ['awaitingReturnTimeoutDays', 0],
    ['returnShippedTimeoutDays', 366],
    ['offlineRefundTimeoutDays', 1.5],
    ['buyerRefundConfirmTimeoutDays', 0],
    ['proofRetentionDays', 3651],
    ['maxProofFiles', 0],
    ['maxProofFiles', 21],
    ['maxProofSizeBytes', 1023],
    ['maxProofSizeBytes', 20 * 1024 * 1024 + 1],
    ['maxProofSizeBytes', Number.MAX_SAFE_INTEGER + 1]
  ]) {
    const parameters = {
      autoReceiveDays: 7,
      afterSaleDaysAfterCompletion: 7,
      pendingSuperiorTimeoutDays: 7,
      pendingAdminReviewTimeoutDays: 7,
      pendingShipmentTimeoutDays: 7,
      awaitingReturnTimeoutDays: 15,
      returnShippedTimeoutDays: 15,
      offlineRefundTimeoutDays: 7,
      buyerRefundConfirmTimeoutDays: 7,
      proofRetentionDays: 30,
      maxProofFiles: 5,
      maxProofSizeBytes: 5 * 1024 * 1024
    }
    parameters[key] = value
    assert.equal(
      parseOrderTimerParameters(JSON.stringify(parameters)).ok,
      false,
      `${key}=${value} must fail closed`
    )
  }

  assert.equal(parseOrderTimerParameters('[]').ok, false)
  assert.equal(parseOrderTimerParameters('{"maxProofFiles":null}').ok, false)
})

test('ORDER_TIMER publication uses autoReceiveDays and persisted reads normalize the legacy alias', () => {
  const canonical = JSON.stringify({
    autoReceiveDays: 7,
    afterSaleDaysAfterCompletion: 7,
    pendingSuperiorTimeoutDays: 7,
    pendingAdminReviewTimeoutDays: 7,
    pendingShipmentTimeoutDays: 7,
    awaitingReturnTimeoutDays: 15,
    returnShippedTimeoutDays: 15,
    offlineRefundTimeoutDays: 7,
    buyerRefundConfirmTimeoutDays: 7,
    proofRetentionDays: 180,
    maxProofFiles: 3,
    maxProofSizeBytes: 8388608
  })
  assert.equal(parseOrderTimerParameters(canonical).ok, true)
  const legacy = canonical.replace('autoReceiveDays', 'autoReceiveDaysAfterShipment')
  assert.equal(parseOrderTimerParameters(legacy).ok, false)
  const persisted = parsePersistedOrderTimerParameters(legacy)
  assert.equal(persisted.ok, true)
  assert.equal(persisted.ok && persisted.value.autoReceiveDays, 7)
})

test('DIRECT_REFERRAL_POINTS rejects an A/B total that exceeds JavaScript safe integer range', () => {
  const unsafeTotal = JSON.stringify({
    qualificationCount: 5,
    pointsStartOrdinal: 6,
    totalPoints: Number.MAX_SAFE_INTEGER,
    availableAPoints: Number.MAX_SAFE_INTEGER,
    frozenBPoints: Number.MAX_SAFE_INTEGER,
    maxRewardDepth: 1,
    eligibleSalesScenes: ['UPGRADE']
  })

  const parsed = parseRuleParameters(unsafeTotal, 'DIRECT_REFERRAL_POINTS')
  assert.equal(parsed.ok, false)
  assert.match(parsed.error, /安全范围/)
  assert.equal(parseRuleParameters(JSON.stringify({
    qualificationCount: 5,
    pointsStartOrdinal: 6,
    totalPoints: Number.MAX_SAFE_INTEGER,
    availableAPoints: Number.MAX_SAFE_INTEGER,
    frozenBPoints: 0,
    maxRewardDepth: 1,
    eligibleSalesScenes: ['UPGRADE']
  }), 'DIRECT_REFERRAL_POINTS').ok, true)
})

test('canonical rule parsing rejects unknown fields and invalid qualification relationships', () => {
  const unknown = parseRuleParameters(JSON.stringify({
    minimumCompletedOrderAmountFen: 199800,
    eligibleSalesScenes: ['UPGRADE'],
    targetLevel: 'SUPER_MEMBER',
    unused: true
  }), 'SELF_ORDER_TASK')
  assert.equal(unknown.ok, false)

  const invalidPoints = parseRuleParameters(JSON.stringify({
    qualificationCount: 6,
    pointsStartOrdinal: 6,
    totalPoints: 320,
    availableAPoints: 160,
    frozenBPoints: 160,
    maxRewardDepth: 1,
    eligibleSalesScenes: ['UPGRADE']
  }), 'DIRECT_REFERRAL_POINTS')
  assert.equal(invalidPoints.ok, false)
  assert.match(invalidPoints.error, /pointsStartOrdinal/)
})

test('legacy points are persisted-read compatibility only and normalize canonically', () => {
  const legacy = '{"pointsStartOrdinal":6,"availableAPoints":160,"frozenBPoints":160}'
  assert.equal(parseRuleParameters(legacy, 'DIRECT_REFERRAL_POINTS').ok, false)
  const repaired = parsePersistedRuleParameters(legacy, 'DIRECT_REFERRAL_POINTS')
  assert.equal(repaired.ok, true)
  assert.equal(repaired.ok && repaired.value.maxRewardDepth, 1)
  assert.equal(repaired.ok && repaired.value.eligibleSalesScenes[0], 'UPGRADE')
})

test('strict JSON parity accepts pretty-printed JSON and escaped string keys/values', () => {
  const pretty = `{
\t"autoReceiveDays": 7,
\t"afterSaleDaysAfterCompletion": 7,
\t"pendingSuperiorTimeoutDays": 7,
\t"pendingAdminReviewTimeoutDays": 7,
\t"pendingShipmentTimeoutDays": 7,
\t"awaitingReturnTimeoutDays": 15,
\t"returnShippedTimeoutDays": 15,
\t"offlineRefundTimeoutDays": 7,
\t"buyerRefundConfirmTimeoutDays": 7,
\t"proofRetentionDays": 180,
\t"maxProofFiles": 3,
\t"maxProofSizeBytes": 8388608
}`
  assert.equal(parseOrderTimerParameters(pretty).ok, true)

  const escaped = String.raw`{"minimumCompletedOrderAmountFen":199800,"eligibleSalesScenes":["UPGRADE"],"targetLevel":"CUSTOM\\ACTIVE"}`
  const parsed = parseRuleParameters(escaped, 'SELF_ORDER_TASK')
  assert.equal(parsed.ok, true)
  assert.equal(parsed.ok && parsed.value.targetLevel, 'CUSTOM\\ACTIVE')

  const duplicateDecodedKey = '{"minimumCompletedOrderAmountFen":199800,"eligibleSalesScenes":["UPGRADE"],"targetLevel":"A","targetLe' + String.fromCharCode(92) + 'u0076el":"B"}'
  assert.equal(parseRuleParameters(duplicateDecodedKey, 'SELF_ORDER_TASK').ok, false)
})

test('strict JSON parity rejects fractional/exponent numbers, duplicate keys, and trailing tokens', () => {
  for (const json of [
    '{"maxProofFiles":1.0}',
    '{"maxProofFiles":1e3}',
    '{"maxProofFiles":1,"maxProofFiles":2}',
    '{"maxProofFiles":1}{"maxProofFiles":2}',
    '{"maxProofFiles":1} trailing'
  ]) {
    assert.equal(parseOrderTimerParameters(json).ok, false, json)
  }
  assert.equal(parseOrderTimerParameters('{"maxProofFiles":20,"maxProofSizeBytes":20971520,"autoReceiveDays":1,"afterSaleDaysAfterCompletion":1,"pendingSuperiorTimeoutDays":1,"pendingAdminReviewTimeoutDays":1,"pendingShipmentTimeoutDays":1,"awaitingReturnTimeoutDays":1,"returnShippedTimeoutDays":1,"offlineRefundTimeoutDays":1,"buyerRefundConfirmTimeoutDays":1,"proofRetentionDays":1}').ok, true)
})

test('level fields remain structurally valid for custom levels while backend owns active status', () => {
  const parsed = parseRuleParameters(JSON.stringify({
    minimumCompletedOrderAmountFen: 199800,
    eligibleSalesScenes: ['UPGRADE'],
    targetLevel: 'CUSTOM_ACTIVE_LEVEL'
  }), 'SELF_ORDER_TASK')
  assert.equal(parsed.ok, true)
})
