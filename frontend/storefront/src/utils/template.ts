import type { StorefrontTemplate } from '../types'

export type TemplatePreset = 'EDITORIAL' | 'VIBRANT' | 'MINIMAL'
export type SectionType =
  | 'ANNOUNCEMENT'
  | 'HERO'
  | 'CATEGORY_NAV'
  | 'PRODUCT_COLLECTION'
  | 'CONTENT_STORY'
  | 'SERVICE_BENEFITS'
  | 'QUICK_LINKS'

export type TemplateTokens = {
  primary: string
  accent: string
  canvas: string
  surface: string
  ink: string
  muted: string
  radius: string
  headingFont: 'serif' | 'sans'
}

export type TemplateSection = {
  id: string
  type: SectionType
  enabled: boolean
  settings: Readonly<Record<string, unknown>>
}

export type ParsedTemplate = {
  preset: TemplatePreset
  name: string
  tokens: TemplateTokens
  sections: TemplateSection[]
}

const sectionTypes = new Set<string>([
  'ANNOUNCEMENT',
  'HERO',
  'CATEGORY_NAV',
  'PRODUCT_COLLECTION',
  'CONTENT_STORY',
  'SERVICE_BENEFITS',
  'QUICK_LINKS'
])

const fallbackTokens: TemplateTokens = {
  primary: '#173F35',
  accent: '#C75B45',
  canvas: '#F4F0E8',
  surface: '#FFFEFA',
  ink: '#17201C',
  muted: '#707970',
  radius: '24px',
  headingFont: 'serif'
}

const fallbackSections: TemplateSection[] = [
  {
    id: 'fallback-hero',
    type: 'HERO',
    enabled: true,
    settings: {
      eyebrow: '本期编辑甄选',
      title: '把日常过成，值得收藏的篇章',
      description: '从产地、工艺到日常体验，我们替你认真筛选每一件好物。',
      primaryLabel: '浏览本期精选',
      primaryLink: '#products',
      contentType: 'BANNER'
    }
  },
  {
    id: 'fallback-products',
    type: 'PRODUCT_COLLECTION',
    enabled: true,
    settings: {
      eyebrow: 'SELECTED OBJECTS',
      title: '值得反复使用的日常之物',
      description: '简洁、可靠，也保留一点让人愉悦的细节。',
      limit: 8,
      columns: 4,
      scene: 'ALL'
    }
  },
  {
    id: 'fallback-benefits',
    type: 'SERVICE_BENEFITS',
    enabled: true,
    settings: { items: ['严格甄选', '上级确认', '平台审核', '售后留痕'] }
  }
]

function isObject(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value)
}

function json(value: string): unknown {
  try {
    return JSON.parse(value) as unknown
  } catch {
    return undefined
  }
}

function stringValue(source: Record<string, unknown>, key: string, fallback: string) {
  const value = source[key]
  return typeof value === 'string' && value ? value : fallback
}

function preset(value: string): TemplatePreset {
  return value === 'VIBRANT' || value === 'MINIMAL' ? value : 'EDITORIAL'
}

function sectionType(value: string): SectionType | undefined {
  switch (value) {
    case 'ANNOUNCEMENT':
    case 'HERO':
    case 'CATEGORY_NAV':
    case 'PRODUCT_COLLECTION':
    case 'CONTENT_STORY':
    case 'SERVICE_BENEFITS':
    case 'QUICK_LINKS':
      return value
    default:
      return undefined
  }
}

export function parseTemplate(value?: StorefrontTemplate): ParsedTemplate {
  if (!value) {
    return {
      preset: 'EDITORIAL',
      name: '序章 · 编辑甄选',
      tokens: fallbackTokens,
      sections: fallbackSections
    }
  }

  const rawTokens = json(value.designTokensJson)
  const tokenSource = isObject(rawTokens) ? rawTokens : {}
  const tokens: TemplateTokens = {
    primary: stringValue(tokenSource, 'primary', fallbackTokens.primary),
    accent: stringValue(tokenSource, 'accent', fallbackTokens.accent),
    canvas: stringValue(tokenSource, 'canvas', fallbackTokens.canvas),
    surface: stringValue(tokenSource, 'surface', fallbackTokens.surface),
    ink: stringValue(tokenSource, 'ink', fallbackTokens.ink),
    muted: stringValue(tokenSource, 'muted', fallbackTokens.muted),
    radius: stringValue(tokenSource, 'radius', fallbackTokens.radius),
    headingFont: tokenSource.headingFont === 'sans' ? 'sans' : 'serif'
  }

  const rawLayout = json(value.layoutJson)
  const rawSections = isObject(rawLayout) && Array.isArray(rawLayout.sections)
    ? rawLayout.sections
    : []
  const sections = rawSections.flatMap((candidate): TemplateSection[] => {
    const type = isObject(candidate) && typeof candidate.type === 'string'
      ? sectionType(candidate.type)
      : undefined
    if (!isObject(candidate)
      || typeof candidate.id !== 'string'
      || !type
      || !sectionTypes.has(type)
      || !isObject(candidate.settings)) {
      return []
    }
    return [{
      id: candidate.id,
      type,
      enabled: candidate.enabled !== false,
      settings: candidate.settings
    }]
  })

  return {
    preset: preset(value.presetType),
    name: value.name,
    tokens,
    sections: sections.length ? sections : fallbackSections
  }
}

export function settingText(section: TemplateSection, key: string, fallback = '') {
  const value = section.settings[key]
  return typeof value === 'string' ? value : fallback
}

export function settingNumber(section: TemplateSection, key: string, fallback: number) {
  const value = section.settings[key]
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback
}

export function settingStrings(section: TemplateSection, key: string, fallback: string[]) {
  const value = section.settings[key]
  if (!Array.isArray(value)) return fallback
  const strings = value.filter((item): item is string => typeof item === 'string')
  return strings.length ? strings : fallback
}

export function applyTemplateTheme(template: ParsedTemplate) {
  const root = document.documentElement
  root.dataset.storefrontPreset = template.preset.toLowerCase()
  root.style.setProperty('--theme-primary', template.tokens.primary)
  root.style.setProperty('--theme-accent', template.tokens.accent)
  root.style.setProperty('--theme-canvas', template.tokens.canvas)
  root.style.setProperty('--theme-surface', template.tokens.surface)
  root.style.setProperty('--theme-ink', template.tokens.ink)
  root.style.setProperty('--theme-muted', template.tokens.muted)
  root.style.setProperty('--theme-radius', template.tokens.radius)
  root.style.setProperty(
    '--theme-heading',
    template.tokens.headingFont === 'serif'
      ? '"Noto Serif SC", "Source Han Serif SC", "Songti SC", serif'
      : 'Inter, "PingFang SC", "Microsoft YaHei", sans-serif'
  )
}
