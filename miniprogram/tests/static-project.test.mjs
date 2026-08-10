import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { access, readFile, readdir, stat } from 'node:fs/promises'
import { relative, resolve } from 'node:path'
import test from 'node:test'
import vm from 'node:vm'

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
    'components/empty/empty.json': ['fui-button', 'fui-empty'],
    'components/goods-card/goods-card.json': ['fui-tag'],
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

test('public brand name and logo stay consistent across miniprogram identity surfaces', async () => {
  const [appSource, indexSource, indexScript, projectSource, privateProjectSource, loginWxml, loginScript, profileWxml, profileScript, logo] = await Promise.all([
    readFile(resolve(miniprogramRoot, 'app.json'), 'utf8'),
    readFile(resolve(miniprogramRoot, 'pages/index/index.json'), 'utf8'),
    readFile(resolve(miniprogramRoot, 'pages/index/index.js'), 'utf8'),
    readFile(resolve(miniprogramRoot, 'project.config.json'), 'utf8'),
    readFile(resolve(miniprogramRoot, 'project.private.config.json'), 'utf8'),
    readFile(resolve(miniprogramRoot, 'pages/login/login.wxml'), 'utf8'),
    readFile(resolve(miniprogramRoot, 'pages/login/login.js'), 'utf8'),
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

  for (const source of [loginWxml, profileWxml]) {
    assert.match(source, /src="\/assets\/brand\/logo\.png"/)
    assert.match(source, /aria-label="宏杉生物品牌标志"/)
  }

  const publicIdentity = [appSource, indexSource, indexScript, projectSource, privateProjectSource, loginWxml, loginScript, profileWxml, profileScript].join('\n')
  assert.doesNotMatch(publicIdentity, /拾光优选|拾光会员|特殊分销商城演示版/)
  assert.match(indexScript, /FALLBACK_HERO_TITLE = '认识宏杉生物'/)
  assert.match(indexScript, /summary: '了解品牌理念与平台服务。'/)
  assert.doesNotMatch(indexScript, /一只杯子的烧成记|揉泥|拉坯|窑火|本周甄选/)
})
