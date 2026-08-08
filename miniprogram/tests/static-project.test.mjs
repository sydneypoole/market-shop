import assert from 'node:assert/strict'
import { access, readFile, readdir } from 'node:fs/promises'
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
  return new Set(
    [...source.matchAll(/^\s{2,}(?:async\s+)?([A-Za-z_$][\w$]*)\s*\([^)]*\)\s*\{/gm)]
      .map((match) => match[1])
  )
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
  for (const componentScript of componentFiles.filter((path) => path.endsWith('.js'))) {
    const componentBase = componentScript.replace(/\.js$/, '')
    for (const extension of ['.js', '.json', '.wxml', '.wxss']) {
      assert.equal(
        await exists(`${componentBase}${extension}`),
        true,
        `${projectPath(componentBase)} is missing ${extension}`
      )
    }
    const componentConfig = JSON.parse(await readFile(`${componentBase}.json`, 'utf8'))
    assert.equal(componentConfig.component, true, `${projectPath(componentBase)}.json must declare component=true`)
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
})
