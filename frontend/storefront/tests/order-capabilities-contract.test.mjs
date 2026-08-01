import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

import { noOrderActions, resolveOrderActions } from '../src/order-capabilities.ts'

const source = path => readFile(new URL(path, import.meta.url), 'utf8')

test('buyer and superior action matrices consume server capabilities without reconstructing roles', () => {
  const cases = [
    {
      actor: 'buyer',
      status: 'PENDING_SUPERIOR',
      server: { canReceive: false, canUploadProof: true, canCancel: true, canSuperiorDecide: false }
    },
    {
      actor: 'superior',
      status: 'PENDING_SUPERIOR',
      server: { canReceive: false, canUploadProof: false, canCancel: false, canSuperiorDecide: true }
    },
    {
      actor: 'buyer',
      status: 'SHIPPED',
      server: { canReceive: true, canUploadProof: false, canCancel: false, canSuperiorDecide: false }
    },
    {
      actor: 'superior',
      status: 'SHIPPED',
      server: { canReceive: false, canUploadProof: false, canCancel: false, canSuperiorDecide: false }
    },
    {
      actor: 'buyer',
      status: 'COMPLETED',
      server: { canReceive: false, canUploadProof: false, canCancel: false, canSuperiorDecide: false }
    }
  ]

  for (const fixture of cases) {
    assert.deepEqual(
      resolveOrderActions(fixture.server),
      fixture.server,
      `${fixture.actor} ${fixture.status}`
    )
  }
  assert.deepEqual(resolveOrderActions(undefined), noOrderActions)
  assert.deepEqual(resolveOrderActions({ canReceive: /** @type {never} */ ('yes') }), noOrderActions)
})

test('order detail renders every write action from actorCapabilities and not from status guesses', async () => {
  const [detail, types] = await Promise.all([
    source('../src/views/OrderDetailView.vue'),
    source('../src/types.ts')
  ])

  for (const capability of ['canReceive', 'canUploadProof', 'canCancel', 'canSuperiorDecide']) {
    assert.match(detail, new RegExp(`actions\\.${capability}`))
    assert.match(types, new RegExp(`${capability}: boolean`))
  }
  assert.match(detail, /resolveOrderActions\(detail\.value\?\.actorCapabilities\)/)
  assert.doesNotMatch(detail, /v-if="detail\.order\.status === '(?:PENDING_SUPERIOR|SHIPPED)'"/)
})
