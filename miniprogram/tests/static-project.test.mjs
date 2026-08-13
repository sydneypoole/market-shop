import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { access, readFile, readdir, stat } from 'node:fs/promises'
import { relative, resolve } from 'node:path'
import test from 'node:test'
import vm from 'node:vm'
import { inflateSync } from 'node:zlib'

import { miniprogramRoot } from './helpers.mjs'

async function walk(directory) {
  const entries = await readdir(directory, { withFileTypes: true })
  const nested = await Promise.all(entries.map(async (entry) => {
    const path = resolve(directory, entry.name)
    return entry.isDirectory() ? walk(path) : [path]
  }))
  return nested.flat()
}

async function exists(path) {
  try {
    await access(path)
    return true
  } catch {
    return false
  }
}

function projectPath(path) {
  return relative(miniprogramRoot, path).split('\\').join('/')
}

function decodeRgbaPng(source, filename) {
  assert.equal(source.subarray(0, 8).toString('hex'), '89504e470d0a1a0a', `${filename} must be PNG`)
  let offset = 8
  let width = 0
  let height = 0
  const compressed = []

  while (offset < source.length) {
    const length = source.readUInt32BE(offset)
    const type = source.subarray(offset + 4, offset + 8).toString('ascii')
    const data = source.subarray(offset + 8, offset + 8 + length)
    if (type === 'IHDR') {
      width = data.readUInt32BE(0)
      height = data.readUInt32BE(4)
      assert.equal(data[8], 8, `${filename} must use 8-bit channels`)
      assert.equal(data[9], 6, `${filename} must be RGBA`)
    } else if (type === 'IDAT') {
      compressed.push(data)
    }
    offset += length + 12
  }

  const bytesPerPixel = 4
  const stride = width * bytesPerPixel
  const encoded = inflateSync(Buffer.concat(compressed))
  const pixels = Buffer.alloc(stride * height)
  const paeth = (a, b, c) => {
    const p = a + b - c
    const pa = Math.abs(p - a)
    const pb = Math.abs(p - b)
    const pc = Math.abs(p - c)
    return pa <= pb && pa <= pc ? a : (pb <= pc ? b : c)
  }

  for (let y = 0; y < height; y += 1) {
    const encodedRow = y * (stride + 1)
    const filter = encoded[encodedRow]
    for (let x = 0; x < stride; x += 1) {
      const raw = encoded[encodedRow + x + 1]
      const target = y * stride + x
      const left = x >= bytesPerPixel ? pixels[target - bytesPerPixel] : 0
      const up = y > 0 ? pixels[target - stride] : 0
      const upperLeft = y > 0 && x >= bytesPerPixel ? pixels[target - stride - bytesPerPixel] : 0
      const predictor = filter === 0
        ? 0
        : filter === 1
          ? left
          : filter === 2
            ? up
            : filter === 3
              ? Math.floor((left + up) / 2)
              : filter === 4
                ? paeth(left, up, upperLeft)
                : NaN
      assert.ok(Number.isFinite(predictor), `${filename} uses unsupported PNG filter ${filter}`)
      pixels[target] = (raw + predictor) & 0xff
    }
  }

  return { width, height, pixels }
}

function assertBalancedWxml(source, filename) {
  const withoutComments = source
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/\{\{[\s\S]*?\}\}/g, 'EXPRESSION')
  const tagPattern = /<\s*(\/?)\s*([A-Za-z][\w:-]*)([^<>]*?)(\/?)\s*>/g
  const stack = []
  let match

  while ((match = tagPattern.exec(withoutComments)) !== null) {
    const [, closing, tagName, attributes, selfClosing] = match
    if (closing) {
      const opened = stack.pop()
      assert.equal(opened, tagName, `${filename}: closing </${tagName}> does not match <${opened}>`)
    } else if (selfClosing !== '/' && !attributes.trimEnd().endsWith('/')) {
      stack.push(tagName)
    }
  }

  assert.deepEqual(stack, [], `${filename}: unclosed WXML tags: ${stack.join(', ')}`)
}

function declaredHandlers(source) {
  const shorthand = [...source.matchAll(
    /^\s{2,}(?:async\s+)?([A-Za-z_$][\w$]*)\s*\([^)]*\)\s*\{/gm
  )].map((match) => match[1])
  const functionProperties = [...source.matchAll(
    /^\s{2,}([A-Za-z_$][\w$]*)\s*:\s*(?:async\s+)?function\s*\([^)]*\)\s*\{/gm
  )].map((match) => match[1])
  return new Set([...shorthand, ...functionProperties])
}

function referencedHandlers(source) {
  return [...source.matchAll(
    /\b(?:capture-)?(?:bind|catch)(?::?[A-Za-z][\w-]*)\s*=\s*["']([A-Za-z_$][\w$]*)["']/g
  )].map((match) => match[1])
}

test('all native miniprogram JavaScript and JSON files parse without a build tool', async () => {
  const files = await walk(miniprogramRoot)
  const sourceFiles = files.filter((path) => !path.includes('/tests/'))

  for (const path of sourceFiles.filter((candidate) => candidate.endsWith('.js'))) {
    const source = await readFile(path, 'utf8')
    if (/^\s*(?:import|export)\b/m.test(source)) {
      const syntax = spawnSync(process.execPath, ['--input-type=module', '--check'], {
        input: source,
        encoding: 'utf8'
      })
      assert.equal(
        syntax.status,
        0,
        `${projectPath(path)} must contain valid JavaScript modules\n${syntax.stderr}`
      )
      continue
    }
    assert.doesNotThrow(
      () => new vm.Script(source, { filename: projectPath(path) }),
      `${projectPath(path)} must contain valid JavaScript`
    )
  }

  for (const path of sourceFiles.filter((candidate) => candidate.endsWith('.json'))) {
    const source = await readFile(path, 'utf8')
    assert.doesNotThrow(
      () => JSON.parse(source),
      `${projectPath(path)} must contain strict JSON`
    )
  }
})

test('app pages, tab icons and local components resolve to complete native bundles', async () => {
  const appConfig = JSON.parse(await readFile(resolve(miniprogramRoot, 'app.json'), 'utf8'))
  const declaredPages = new Set(appConfig.pages || [])
  assert.ok(declaredPages.size > 0, 'app.json must declare at least one page')

  for (const page of declaredPages) {
    for (const extension of ['.js', '.json', '.wxml', '.wxss']) {
      assert.equal(
        await exists(resolve(miniprogramRoot, `${page}${extension}`)),
        true,
        `${page}${extension} is declared by app.json but missing`
      )
    }
  }

  const pageFiles = await walk(resolve(miniprogramRoot, 'pages'))
  const implementedPages = pageFiles
    .filter((path) => path.endsWith('.js'))
    .map((path) => projectPath(path).replace(/\.js$/, ''))
  assert.deepEqual(
    implementedPages.sort(),
    [...declaredPages].sort(),
    'every implemented page must be registered exactly once in app.json'
  )

  for (const item of appConfig.tabBar?.list || []) {
    assert.ok(declaredPages.has(item.pagePath), `${item.pagePath} must be registered before it is a tab`)
    for (const iconField of ['iconPath', 'selectedIconPath']) {
      assert.equal(
        await exists(resolve(miniprogramRoot, item[iconField])),
        true,
        `${item[iconField]} referenced by tabBar is missing`
      )
    }
  }

  const componentFiles = await walk(resolve(miniprogramRoot, 'components'))
  const componentConfigs = componentFiles.filter((path) => path.endsWith('.json'))
  for (const componentJson of componentConfigs) {
    const componentConfig = JSON.parse(await readFile(componentJson, 'utf8'))
    if (componentConfig.component !== true) {
      continue
    }
    const componentBase = componentJson.replace(/\.json$/, '')
    for (const extension of ['.js', '.json', '.wxml', '.wxss']) {
      assert.equal(
        await exists(`${componentBase}${extension}`),
        true,
        `${projectPath(componentBase)} is missing ${extension}`
      )
    }
  }

  const jsonConfigs = [...pageFiles, ...componentFiles].filter((path) => path.endsWith('.json'))
  for (const jsonPath of jsonConfigs) {
    const ownerConfig = JSON.parse(await readFile(jsonPath, 'utf8'))
    for (const [name, componentPath] of Object.entries(ownerConfig.usingComponents || {})) {
      assert.match(componentPath, /^\/components\//, `${name} must resolve from the miniprogram root`)
      const componentBase = resolve(miniprogramRoot, componentPath.slice(1))
      for (const extension of ['.js', '.json', '.wxml', '.wxss']) {
        assert.equal(
          await exists(`${componentBase}${extension}`),
          true,
          `${name} references missing component bundle ${componentPath}${extension}`
        )
      }
    }
  }
})

test('native tab icons use the pinned FirstUI family and match app colors', async () => {
  const appConfig = JSON.parse(await readFile(resolve(miniprogramRoot, 'app.json'), 'utf8'))
  const pinnedIconFiles = {
    'fui-icon.js': '092346c4191600484b07663c7365f46e824ed3dafa2d4e6eaa6dfea7aac37235',
    'fui-icon.json': '4a0976b08df9b20d33c41e43ee32d12c91774a0148d55232ad070b1944333646',
    'fui-icon.wxml': 'ed6caaeaaeed9975363b2d1bf9b668f8d03395fba1a8558eb0f5fe90861fc6ff',
    'fui-icon.wxss': '056fd4159dc5a7718c60215d7482c78a6ed430b5e167bebd27811f054acb5312',
    'index.js': '1660a0ced6c342f966fe5e46a657b9fe12f9ab6e8c8acc0abcff40ce966a3dba'
  }
  for (const [filename, expectedHash] of Object.entries(pinnedIconFiles)) {
    const source = await readFile(resolve(miniprogramRoot, `components/firstui/fui-icon/${filename}`))
    assert.equal(
      createHash('sha256').update(source).digest('hex'),
      expectedHash,
      `components/firstui/fui-icon/${filename} must remain byte-identical to the pinned commit`
    )
  }

  const expectedRgb = (hex) => [
    Number.parseInt(hex.slice(1, 3), 16),
    Number.parseInt(hex.slice(3, 5), 16),
    Number.parseInt(hex.slice(5, 7), 16)
  ]

  for (const item of appConfig.tabBar.list) {
    const variants = [
      [item.iconPath, appConfig.tabBar.color],
      [item.selectedIconPath, appConfig.tabBar.selectedColor]
    ]
    const variantBuffers = []

    for (const [iconPath, color] of variants) {
      const runtimePath = resolve(miniprogramRoot, iconPath)
      const designPath = resolve(miniprogramRoot, `../docs/design/miniprogram/icons/${iconPath.split('/').at(-1)}`)
      const [runtime, design] = await Promise.all([readFile(runtimePath), readFile(designPath)])
      variantBuffers.push(runtime)
      assert.ok(runtime.length < 40 * 1024, `${iconPath} must stay below WeChat's 40 KiB limit`)
      assert.deepEqual(runtime, design, `${iconPath} must match its design-source copy`)

      const { width, height, pixels } = decodeRgbaPng(runtime, iconPath)
      assert.equal(width, 81, `${iconPath} must use the recommended 81 px canvas`)
      assert.equal(height, 81, `${iconPath} must use the recommended 81 px canvas`)
      const inkColor = expectedRgb(color)
      let minX = width
      let minY = height
      let maxX = -1
      let maxY = -1
      let transparent = false
      for (let y = 0; y < height; y += 1) {
        for (let x = 0; x < width; x += 1) {
          const offset = (y * width + x) * 4
          const alpha = pixels[offset + 3]
          if (alpha === 0) {
            transparent = true
            continue
          }
          assert.deepEqual(
            [...pixels.subarray(offset, offset + 3)],
            inkColor,
            `${iconPath} must use ${color} for every visible pixel`
          )
          minX = Math.min(minX, x)
          minY = Math.min(minY, y)
          maxX = Math.max(maxX, x)
          maxY = Math.max(maxY, y)
        }
      }
      assert.equal(transparent, true, `${iconPath} must keep a transparent background`)
      assert.ok(maxX >= 0, `${iconPath} must contain a visible glyph`)
      const inkWidth = maxX - minX + 1
      const inkHeight = maxY - minY + 1
      assert.ok(inkWidth >= 55 && inkWidth <= 66, `${iconPath} ink width ${inkWidth} is not optically sized`)
      assert.ok(inkHeight >= 60 && inkHeight <= 66, `${iconPath} ink height ${inkHeight} is not optically sized`)
      assert.ok(Math.abs((minX + maxX) - (width - 1)) <= 2, `${iconPath} must be horizontally centered`)
      assert.ok(Math.abs((minY + maxY) - (height - 1)) <= 2, `${iconPath} must be vertically centered`)
    }

    assert.notDeepEqual(
      variantBuffers[0],
      variantBuffers[1],
      `${item.text} normal and selected icons must use distinct outline/fill glyphs`
    )
  }

  const generator = await readFile(
    resolve(miniprogramRoot, '../scripts/generate-miniprogram-tab-icons.py'),
    'utf8'
  )
  const iconIndex = await readFile(
    resolve(miniprogramRoot, 'components/firstui/fui-icon/index.js'),
    'utf8'
  )
  const mappings = {
    'tab-home.png': ['home', 'E7ED'],
    'tab-home-active.png': ['home-fill', 'E7EC'],
    'tab-category.png': ['classify', 'E7FE'],
    'tab-category-active.png': ['classify-fill', 'E7FF'],
    'tab-cart.png': ['cart', 'E801'],
    'tab-cart-active.png': ['cart-fill', 'E800'],
    'tab-profile.png': ['my', 'E7D5'],
    'tab-profile-active.png': ['my-fill', 'E7D4']
  }
  for (const [filename, [iconName, codepoint]] of Object.entries(mappings)) {
    assert.match(
      generator,
      new RegExp(`"${filename}": 0x${codepoint}`),
      `tab icon generator must map ${filename} to FirstUI ${iconName}`
    )
    assert.match(
      iconIndex,
      new RegExp(`"${iconName}"\\s*:\\s*"\\\\u${codepoint.toLowerCase()}"`),
      `pinned FirstUI index must map ${iconName} to U+${codepoint}`
    )
  }
})

test('WXML is balanced and every static page or asset reference exists', async () => {
  const appConfig = JSON.parse(await readFile(resolve(miniprogramRoot, 'app.json'), 'utf8'))
  const declaredPages = new Set(appConfig.pages || [])
  const files = (await walk(miniprogramRoot)).filter((path) => !path.includes('/tests/'))

  for (const path of files.filter((candidate) => candidate.endsWith('.wxml'))) {
    assertBalancedWxml(await readFile(path, 'utf8'), projectPath(path))
  }

  for (const path of files.filter((candidate) => /\.(?:js|json|wxml)$/.test(candidate))) {
    const source = await readFile(path, 'utf8')
    for (const match of source.matchAll(/\/?pages\/[A-Za-z0-9_-]+\/[A-Za-z0-9_-]+/g)) {
      const page = match[0].replace(/^\//, '')
      assert.ok(declaredPages.has(page), `${projectPath(path)} references undeclared page ${page}`)
    }
    for (const match of source.matchAll(/\/?assets\/[A-Za-z0-9_./-]+/g)) {
      const asset = match[0].replace(/^\//, '')
      assert.equal(
        await exists(resolve(miniprogramRoot, asset)),
        true,
        `${projectPath(path)} references missing asset ${asset}`
      )
    }
  }
})

test('WXML avoids deprecated text-selection attributes', async () => {
  const files = (await walk(miniprogramRoot)).filter((path) => !path.includes('/tests/'))
  const wxmlFiles = files.filter((candidate) => candidate.endsWith('.wxml'))

  for (const path of wxmlFiles) {
    const source = await readFile(path, 'utf8')
    assert.doesNotMatch(
      source,
      /\bselectable\s*=/,
      `${projectPath(path)} must use user-select instead of the deprecated selectable attribute`
    )
  }

  for (const filename of ['pages/rules/rules.wxml', 'pages/member/invite.wxml']) {
    const source = await readFile(resolve(miniprogramRoot, filename), 'utf8')
    assert.match(source, /\buser-select="\{\{true\}\}"/)
  }
})

test('every static WXML event binding resolves to a page or component handler', async () => {
  const files = (await walk(miniprogramRoot)).filter((path) => !path.includes('/tests/'))

  for (const wxmlPath of files.filter((candidate) => candidate.endsWith('.wxml'))) {
    const scriptPath = wxmlPath.replace(/\.wxml$/, '.js')
    assert.equal(await exists(scriptPath), true, `${projectPath(wxmlPath)} is missing its JavaScript owner`)
    const [wxml, script] = await Promise.all([
      readFile(wxmlPath, 'utf8'),
      readFile(scriptPath, 'utf8')
    ])
    const handlers = declaredHandlers(script)
    for (const referenced of referencedHandlers(wxml)) {
      assert.ok(
        handlers.has(referenced),
        `${projectPath(wxmlPath)} binds missing handler ${referenced}`
      )
    }
  }
})

test('project configuration targets a native miniprogram project', async () => {
  const project = JSON.parse(
    await readFile(resolve(miniprogramRoot, 'project.config.json'), 'utf8')
  )
  const privateProject = JSON.parse(
    await readFile(resolve(miniprogramRoot, 'project.private.config.json'), 'utf8')
  )
  assert.equal(project.compileType, 'miniprogram')
  assert.match(project.appid, /^(?:touristappid|wx[a-f0-9]{16})$/)
  assert.equal(project.setting.urlCheck, true, '共享项目配置必须开启合法域名校验')
  assert.equal(privateProject.setting.urlCheck, true, '提交的私有项目配置也必须默认开启合法域名校验')
  assert.equal(project.setting.nodeModules, false)
  assert.equal(project.setting.packNpmManually, false)

  const ignored = new Set((project.packOptions?.ignore || []).map((entry) => `${entry.type}:${entry.value}`))
  assert.ok(ignored.has('folder:tests'), 'tests must not enter the WeChat upload package')
  assert.ok(ignored.has('file:README.md'), 'README must not enter the WeChat upload package')
  assert.equal(ignored.has('file:components/firstui/LICENSE'), false, 'FirstUI license must remain distributed')
})

test('FirstUI source, theme mapping and all-page wrapper registration stay pinned', async () => {
  const [appSource, appWxss, upstream, license, shellWxml, shellWxss] = await Promise.all([
    readFile(resolve(miniprogramRoot, 'app.json'), 'utf8'),
    readFile(resolve(miniprogramRoot, 'app.wxss'), 'utf8'),
    readFile(resolve(miniprogramRoot, 'components/firstui/UPSTREAM.md'), 'utf8'),
    readFile(resolve(miniprogramRoot, 'components/firstui/LICENSE'), 'utf8'),
    readFile(resolve(miniprogramRoot, 'components/brand-shell/brand-shell.wxml'), 'utf8'),
    readFile(resolve(miniprogramRoot, 'components/brand-shell/brand-shell.wxss'), 'utf8')
  ])
  const appConfig = JSON.parse(appSource)

  assert.equal(appConfig.style, undefined, 'FirstUI compatibility requires app.json style:v2 to be removed')
  assert.match(appWxss, /^@import "\.\/components\/firstui\/fui-theme\/fui-theme\.wxss";/)
  for (const token of [
    '--brand-plum:',
    '--brand-pink:',
    '--brand-cream:',
    '--brand-ink:',
    '--fui-color-primary: var(--brand-plum)',
    '--fui-bg-color-grey: var(--brand-canvas)'
  ]) {
    assert.ok(appWxss.includes(token), `app.wxss must map ${token}`)
  }

  assert.match(upstream, /FirstUI-weixin/)
  assert.match(upstream, /V2\.4\.0/)
  assert.match(upstream, /fa7863720afcf591aaf3ba6de29c42a88c6dde80/)
  assert.match(upstream, /Apache License 2\.0/)
  assert.match(license, /Apache License\s+Version 2\.0/)
  assert.doesNotMatch(shellWxml, /<scroll-view\b/, 'brand-shell must not create a nested page scroller')
  assert.doesNotMatch(shellWxss, /\boverflow(?:-[xy])?\s*:/, 'brand-shell must not become a nested scroll container')

  for (const vipComponent of ['fui-upload', 'fui-timeaxis', 'fui-nav-bar', 'fui-searchbar']) {
    assert.equal(
      await exists(resolve(miniprogramRoot, `components/firstui/${vipComponent}`)),
      false,
      `${vipComponent} is not part of the pinned public source`
    )
  }

  const allFiles = await walk(miniprogramRoot)
  const registrationSources = await Promise.all(
    allFiles.filter((filename) => filename.endsWith('.json')).map((filename) => readFile(filename, 'utf8'))
  )
  const registrations = registrationSources.join('\n')
  const usageSources = await Promise.all(
    allFiles.filter((filename) => filename.endsWith('.wxml')).map((filename) => readFile(filename, 'utf8'))
  )
  const componentUsage = usageSources.join('\n')
  const vendorEntries = await readdir(resolve(miniprogramRoot, 'components/firstui'), { withFileTypes: true })
  for (const entry of vendorEntries.filter((item) => item.isDirectory() && item.name !== 'fui-theme')) {
    const componentJson = resolve(
      miniprogramRoot,
      `components/firstui/${entry.name}/${entry.name}.json`
    )
    if (!(await exists(componentJson))) {
      continue
    }
    assert.match(
      registrations,
      new RegExp(`/components/firstui/${entry.name}/${entry.name}`),
      `${entry.name} is vendored but never registered; keep the FirstUI subset selective`
    )
    assert.match(
      componentUsage,
      new RegExp(`<${entry.name}\\b`),
      `${entry.name} is vendored and registered but never rendered`
    )
  }

  for (const page of appConfig.pages) {
    const [configSource, wxml] = await Promise.all([
      readFile(resolve(miniprogramRoot, `${page}.json`), 'utf8'),
      readFile(resolve(miniprogramRoot, `${page}.wxml`), 'utf8')
    ])
    const config = JSON.parse(configSource)
    assert.equal(
      config.usingComponents?.['brand-shell'],
      '/components/brand-shell/brand-shell',
      `${page} must register the project FirstUI wrapper`
    )
    assert.match(wxml, /<brand-shell\b/, `${page} must visibly use the brand shell`)
    assert.match(wxml, /<\/brand-shell>/, `${page} must close the brand shell`)
    for (const componentName of Object.keys(config.usingComponents || {})) {
      assert.match(
        wxml,
        new RegExp(`<${componentName}(?:\\s|/?>)`),
        `${page} registers ${componentName} but never renders it`
      )
    }
  }

  const wrapperRegistrations = {
    'components/brand-shell/brand-shell.json': ['fui-loading'],
    'components/empty/empty.json': ['fui-button', 'fui-empty', 'fui-icon'],
    'components/goods-card/goods-card.json': ['fui-icon'],
    'components/stepper/stepper.json': ['fui-input-number'],
    'components/sku-sheet/sku-sheet.json': ['fui-bottom-popup', 'fui-button', 'fui-tag']
  }
  for (const [filename, names] of Object.entries(wrapperRegistrations)) {
    const config = JSON.parse(await readFile(resolve(miniprogramRoot, filename), 'utf8'))
    for (const name of names) {
      assert.match(
        config.usingComponents?.[name] || '',
        new RegExp(`^/components/firstui/${name}/${name}$`),
        `${filename} must register ${name}`
      )
    }
  }
})

test('taste redesign preserves the cold-luxury visual contract without template tells', async () => {
  const files = await walk(miniprogramRoot)
  const projectUiFiles = files.filter((filename) => {
    if (!/\.(?:wxml|wxss)$/.test(filename) || filename.includes('/components/firstui/')) {
      return false
    }
    return filename.endsWith('/app.wxss') || filename.includes('/pages/') || filename.includes('/components/') || filename.includes('/styles/')
  })
  const projectUi = (await Promise.all(projectUiFiles.map((filename) => readFile(filename, 'utf8')))).join('\n')
  const [appWxss, goodsCard, shellWxml, shellWxss] = await Promise.all([
    readFile(resolve(miniprogramRoot, 'app.wxss'), 'utf8'),
    readFile(resolve(miniprogramRoot, 'components/goods-card/goods-card.wxml'), 'utf8'),
    readFile(resolve(miniprogramRoot, 'components/brand-shell/brand-shell.wxml'), 'utf8'),
    readFile(resolve(miniprogramRoot, 'components/brand-shell/brand-shell.wxss'), 'utf8')
  ])

  assert.match(appWxss, /--brand-canvas:\s*#F3F4F6/)
  assert.match(appWxss, /--brand-ink:\s*#202126/)
  assert.match(appWxss, /--brand-muted:\s*#6F656B/)
  assert.match(appWxss, /--brand-radius-card:\s*20rpx/)
  assert.match(appWxss, /--brand-radius-control:\s*16rpx/)
  assert.match(appWxss, /@media \(prefers-reduced-motion: reduce\)/)
  assert.doesNotMatch(projectUi, /(?:\.serif\b|class=["'][^"']*\bserif\b|Songti|STSong)/)
  assert.doesNotMatch(projectUi, /section-eyebrow|radial-gradient|brand-shell__glow/)
  assert.doesNotMatch(projectUi, /story-leaf|border-(?:top-left|bottom-right)-radius:[^;]+;[\s\S]{0,120}rotate\(/)
  assert.doesNotMatch(projectUi, />\s*(?:›|→|↑|↓|×|✓|＋)\s*</)
  assert.doesNotMatch(projectUi, /#(?:FFF9F7|F8F3F1|FFF0F4|F6DDE6|8B7881|AD98A1)/i)
  assert.doesNotMatch(projectUi, /[—–]/, 'visible miniprogram copy must use plain punctuation')
  assert.doesNotMatch(goodsCard, /宏杉生物商城|<fui-tag\b/, 'product photos must not carry a decorative brand pill')
  assert.doesNotMatch(shellWxml, /brand-shell__glow/)
  assert.doesNotMatch(shellWxss, /radial-gradient/)

  const appConfig = JSON.parse(await readFile(resolve(miniprogramRoot, 'app.json'), 'utf8'))
  for (const page of appConfig.pages) {
    const config = JSON.parse(await readFile(resolve(miniprogramRoot, `${page}.json`), 'utf8'))
    const wxml = await readFile(resolve(miniprogramRoot, `${page}.wxml`), 'utf8')
    assert.equal(
      config.usingComponents?.['fui-icon'],
      '/components/firstui/fui-icon/fui-icon',
      `${page} must use the one approved icon family`
    )
    assert.match(wxml, /<fui-icon\b/, `${page} must render its registered icon family`)
    for (const icon of wxml.match(/<fui-icon\b[\s\S]*?\/>/g) || []) {
      assert.match(icon, /\sdisabled(?:\s|=|\/>)/, `${page} decorative icons must not own tap events`)
      assert.match(icon, /\saria-hidden="true"/, `${page} decorative icons need an accessible outer label`)
    }
  }
})

test('WeChat main-package source stays below the 2 MiB limit after configured ignores', async () => {
  const project = JSON.parse(await readFile(resolve(miniprogramRoot, 'project.config.json'), 'utf8'))
  const ignores = project.packOptions?.ignore || []
  const files = await walk(miniprogramRoot)
  let bytes = 0

  for (const filename of files) {
    const relativePath = projectPath(filename)
    const ignored = ignores.some((entry) => {
      if (entry.type === 'folder') {
        return relativePath === entry.value || relativePath.startsWith(`${entry.value}/`)
      }
      return entry.type === 'file' && relativePath === entry.value
    })
    if (!ignored) {
      bytes += (await stat(filename)).size
    }
  }

  assert.ok(bytes < 1.4 * 1024 * 1024, `main-package source is ${bytes} bytes; taste redesign budget is 1.4 MiB`)
  assert.ok(bytes < 2 * 1024 * 1024, `main-package source is ${bytes} bytes; expected less than 2 MiB`)
})

test('native WeChat capabilities remain in place beside FirstUI presentation wrappers', async () => {
  const [appSource, profile, goodsDetail, addressEdit, orderDetail, aftersaleDetail] = await Promise.all([
    readFile(resolve(miniprogramRoot, 'app.json'), 'utf8'),
    readFile(resolve(miniprogramRoot, 'pages/profile/profile.wxml'), 'utf8'),
    readFile(resolve(miniprogramRoot, 'pages/goods/detail.wxml'), 'utf8'),
    readFile(resolve(miniprogramRoot, 'pages/address/edit.wxml'), 'utf8'),
    readFile(resolve(miniprogramRoot, 'pages/order/detail.js'), 'utf8'),
    readFile(resolve(miniprogramRoot, 'pages/aftersale/detail.js'), 'utf8')
  ])
  const appConfig = JSON.parse(appSource)

  assert.equal(appConfig.tabBar?.list?.length, 4, 'native four-tab navigation must remain enabled')
  assert.match(profile, /open-type="contact"/)
  assert.match(goodsDetail, /open-type="contact"/)
  assert.match(addressEdit, /<picker\b[^>]*mode="region"/)
  for (const detailSource of [orderDetail, aftersaleDetail]) {
    assert.match(detailSource, /wx\.chooseMedia\s*\(/)
    assert.match(detailSource, /wx\.previewImage\s*\(/)
  }
})

test('profile services omit the about entry while the public about API remains available', async () => {
  const [profileMarkup, profileScript, systemApi] = await Promise.all([
    readFile(resolve(miniprogramRoot, 'pages/profile/profile.wxml'), 'utf8'),
    readFile(resolve(miniprogramRoot, 'pages/profile/profile.js'), 'utf8'),
    readFile(resolve(miniprogramRoot, 'api/system.js'), 'utf8')
  ])

  assert.doesNotMatch(profileMarkup, /查看关于宏杉生物|关于宏杉生物|bindtap="onAbout"/)
  assert.doesNotMatch(profileScript, /require\(['"]\.\.\/\.\.\/api\/system['"]\)|\bonAbout\b|systemApi\.about/)
  assert.match(systemApi, /function about\(\)/)
  assert.match(systemApi, /request\('\/system\/about', \{ auth: false \}\)/)
})

test('public brand name and logo stay consistent across miniprogram identity surfaces', async () => {
  const [appSource, indexSource, indexWxml, indexScript, projectSource, privateProjectSource, loginWxml, loginScript, registerWxml, registerScript, profileWxml, profileScript, logo] = await Promise.all([
    readFile(resolve(miniprogramRoot, 'app.json'), 'utf8'),
    readFile(resolve(miniprogramRoot, 'pages/index/index.json'), 'utf8'),
    readFile(resolve(miniprogramRoot, 'pages/index/index.wxml'), 'utf8'),
    readFile(resolve(miniprogramRoot, 'pages/index/index.js'), 'utf8'),
    readFile(resolve(miniprogramRoot, 'project.config.json'), 'utf8'),
    readFile(resolve(miniprogramRoot, 'project.private.config.json'), 'utf8'),
    readFile(resolve(miniprogramRoot, 'pages/login/login.wxml'), 'utf8'),
    readFile(resolve(miniprogramRoot, 'pages/login/login.js'), 'utf8'),
    readFile(resolve(miniprogramRoot, 'pages/register/register.wxml'), 'utf8'),
    readFile(resolve(miniprogramRoot, 'pages/register/register.js'), 'utf8'),
    readFile(resolve(miniprogramRoot, 'pages/profile/profile.wxml'), 'utf8'),
    readFile(resolve(miniprogramRoot, 'pages/profile/profile.js'), 'utf8'),
    readFile(resolve(miniprogramRoot, 'assets/brand/logo.png'))
  ])

  assert.equal(JSON.parse(appSource).window.navigationBarTitleText, '宏杉生物')
  assert.equal(JSON.parse(indexSource).navigationBarTitleText, '宏杉生物')
  assert.equal(JSON.parse(projectSource).description, '宏杉生物微信小程序')
  assert.equal(JSON.parse(projectSource).projectname, 'market-shop-miniprogram', 'internal market-shop project identifiers must remain stable')
  assert.equal(JSON.parse(privateProjectSource).projectname, 'market')
  assert.equal(logo.subarray(0, 8).toString('hex'), '89504e470d0a1a0a', '品牌资产必须是有效 PNG')
  assert.equal(
    createHash('sha256').update(logo).digest('hex'),
    '488b60fcc1d18c750933a4ffedd3947e18cdc8502815431ef932739613f1801b',
    '小程序必须使用用户提供的 logo.png 原始字节'
  )

  for (const source of [loginWxml, registerWxml]) {
    assert.match(source, /src="\/assets\/brand\/logo\.png"/)
    assert.match(source, /aria-label="宏杉生物品牌标志"/)
  }
  assert.doesNotMatch(profileWxml, /src="\/assets\/brand\/logo\.png"/, '会员头像缺失时不得冒用品牌 Logo')
  assert.match(profileWxml, /src="\{\{avatarUrl\}\}"/)
  assert.match(profileWxml, /class="avatar-fallback"/)

  const publicIdentity = [appSource, indexSource, indexScript, projectSource, privateProjectSource, loginWxml, loginScript, registerWxml, registerScript, profileWxml, profileScript].join('\n')
  assert.doesNotMatch(publicIdentity, /拾光优选|拾光会员|特殊分销商城演示版/)
  assert.match(indexScript, /FALLBACK_HERO_TITLE = '认识宏杉生物'/)
  assert.match(indexScript, /summary: '了解品牌理念与平台服务。'/)
  assert.doesNotMatch(indexScript, /一只杯子的烧成记|揉泥|拉坯|窑火|本周甄选/)
  assert.doesNotMatch(`${indexWxml}\n${indexScript}`, /宏杉生物会员商城\s*·\s*欢迎选购/)
  assert.match(indexWxml, /<text wx:if="\{\{announcement\}\}" class="welcome-note">\{\{announcement\}\}<\/text>/)
})
