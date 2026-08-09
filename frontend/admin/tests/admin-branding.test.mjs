import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const adminFile = (path, encoding) => readFile(new URL(`../${path}`, import.meta.url), encoding)

test('admin identity surfaces use the Hongshan Biology brand and bundled logo', async () => {
  const [index, app, login, styles, logo] = await Promise.all([
    adminFile('index.html', 'utf8'),
    adminFile('src/App.vue', 'utf8'),
    adminFile('src/views/LoginView.vue', 'utf8'),
    adminFile('src/styles.css', 'utf8'),
    adminFile('public/logo.png')
  ])

  assert.equal(logo.subarray(0, 8).toString('hex'), '89504e470d0a1a0a', '品牌资产必须是有效 PNG')
  assert.equal(
    createHash('sha256').update(logo).digest('hex'),
    '488b60fcc1d18c750933a4ffedd3947e18cdc8502815431ef932739613f1801b',
    '后台必须使用用户提供的 logo.png 原始字节'
  )
  assert.match(index, /<title>宏杉生物 · 运营后台<\/title>/)
  assert.match(index, /<link rel="icon" href="\/logo\.png" type="image\/png" \/>/)
  assert.match(app, /<img src="\/logo\.png" alt="宏杉生物 Logo" \/>/)
  assert.match(app, /<span>宏杉生物<small>运营控制台<\/small><\/span>/)
  assert.match(login, /<img class="mark" src="\/logo\.png" alt="宏杉生物 Logo" \/>/)
  assert.match(login, /宏杉生物 · 商城运营中心/)
  assert.match(styles, /\.admin-brand > img/)

  assert.doesNotMatch([index, app, login].join('\n'), /拾光优选|拾光会员/)
})
