import { readFile } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import vm from 'node:vm'

export const miniprogramRoot = fileURLToPath(new URL('../', import.meta.url))

export async function loadCommonJs(relativePath, options = {}) {
  const filename = resolve(miniprogramRoot, relativePath)
  const source = await readFile(filename, 'utf8')
  const module = { exports: {} }
  const requireMap = options.requireMap || {}
  const context = vm.createContext({
    console,
    setTimeout,
    clearTimeout,
    ...options.globals
  })
  const localRequire = (specifier) => {
    if (Object.hasOwn(requireMap, specifier)) {
      return requireMap[specifier]
    }
    throw new Error(`Unexpected require(${JSON.stringify(specifier)}) in ${relativePath}`)
  }
  const wrapper = new vm.Script(
    `(function (require, module, exports, __filename, __dirname) {\n${source}\n})`,
    { filename }
  ).runInContext(context)

  wrapper(localRequire, module, module.exports, filename, dirname(filename))
  return module.exports
}

export function plain(value) {
  if (value === undefined) {
    return undefined
  }
  return JSON.parse(JSON.stringify(value))
}
