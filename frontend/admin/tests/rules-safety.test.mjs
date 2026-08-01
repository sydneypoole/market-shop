import assert from 'node:assert/strict'
import test from 'node:test'

import {
  isRuleVersionList,
  parseOrderTimerParameters,
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
  pointsStartOrdinal: 6,
  availableAPoints: 160,
  frozenBPoints: 160
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
    autoReceiveDaysAfterShipment: 365,
    afterSaleDaysAfterCompletion: 1,
    proofRetentionDays: 3650,
    maxProofFiles: 20,
    maxProofSizeBytes: 1024
  })
  assert.equal(parseOrderTimerParameters(valid).ok, true)

  for (const [key, value] of [
    ['autoReceiveDaysAfterShipment', 0],
    ['autoReceiveDaysAfterShipment', 366],
    ['afterSaleDaysAfterCompletion', 365.5],
    ['proofRetentionDays', 3651],
    ['maxProofFiles', 0],
    ['maxProofFiles', 21],
    ['maxProofSizeBytes', 1023],
    ['maxProofSizeBytes', 20 * 1024 * 1024 + 1],
    ['maxProofSizeBytes', Number.MAX_SAFE_INTEGER + 1]
  ]) {
    const parameters = {
      autoReceiveDaysAfterShipment: 7,
      afterSaleDaysAfterCompletion: 7,
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

test('DIRECT_REFERRAL_POINTS rejects an A/B total that exceeds JavaScript safe integer range', () => {
  const unsafeTotal = JSON.stringify({
    pointsStartOrdinal: 6,
    availableAPoints: Number.MAX_SAFE_INTEGER,
    frozenBPoints: Number.MAX_SAFE_INTEGER
  })

  const parsed = parseRuleParameters(unsafeTotal, 'DIRECT_REFERRAL_POINTS')
  assert.equal(parsed.ok, false)
  assert.match(parsed.error, /安全范围/)
})
